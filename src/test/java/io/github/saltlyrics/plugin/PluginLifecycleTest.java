package io.github.saltlyrics.plugin;

import com.xuncorp.spw.workshop.api.Channel;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.saltlyrics.core.Options.OutputOrder;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.saltlyrics.fixtures.LyricFixtures.flacWithLyrics;

class PluginLifecycleTest {
    @TempDir Path directory;

    @Test
    void repeatedMenuTogglesSupplyBothOrdersWithoutChangingTheSong() throws Exception {
        String original = "君の[00:01.5]夢";
        String translation = "你的[00:01.6]梦想";
        FakeConfigs configs = new FakeConfigs();
        configs.values.put("source", "EMBEDDED_ONLY");
        FakeMenu menus = new FakeMenu();
        BilingualLyricsPlugin plugin = plugin(configs, menus);
        AtomicInteger generation = new AtomicInteger();
        plugin.start();
        try {
            for (boolean translatedFirst : new boolean[]{true, false}) {
                String mediaId = translatedFirst ? "translation-first" : "original-first";
                String raw = "[00:01]" + (translatedFirst ? translation : original)
                        + "\n[00:01]" + (translatedFirst ? original : translation);
                Path audio = embedded(mediaId, raw);
                byte[] originalFile = Files.readAllBytes(audio);
                generation.incrementAndGet(); // Switching songs expires the previous reload request.
                String initial = BilingualLyricsPlugin.load(mediaId, audio.toString());
                if (initial == null) initial = raw;
                assertTrue(initial.indexOf(original) < initial.indexOf(translation));
                for (int cycle = 0; cycle < 6; cycle++) {
                    assertEquals(OutputOrder.TRANSLATION_FIRST, menus.toggle.get());
                    int translatedGeneration = generation.incrementAndGet();
                    BilingualLyricsPlugin.prepareRefresh(mediaId, () -> generation.get() == translatedGeneration);
                    String translationFirst = BilingualLyricsPlugin.load(mediaId, audio.toString());
                    assertNotNull(translationFirst);
                    assertTrue(translationFirst.indexOf(translation) < translationFirst.indexOf(original));
                    assertEquals(OutputOrder.ORIGINAL_FIRST, menus.toggle.get());
                    int originalGeneration = generation.incrementAndGet();
                    BilingualLyricsPlugin.prepareRefresh(mediaId, () -> generation.get() == originalGeneration);
                    String originalFirst = BilingualLyricsPlugin.load(mediaId, audio.toString());
                    assertNotNull(originalFirst);
                    assertTrue(originalFirst.indexOf(original) < originalFirst.indexOf(translation));
                    assertNotNull(BilingualLyricsPlugin.load(mediaId, audio.toString()),
                            "Callbacks in one valid generation must not consume each other's request");
                }
                assertArrayEquals(originalFile, Files.readAllBytes(audio), "Switching must not edit embedded tags");
            }
        } finally {
            plugin.stop();
        }
    }

    @Test
    void explicitRefreshDoesNotTakeOverAnotherSongOrLaterOrdinaryLoads() throws Exception {
        String originalFirst = "[00:01]君の夢\n[00:01]你的梦想";
        String translatedFirst = "[00:01]你的梦想\n[00:01]君の夢";
        Path a = embedded("a", originalFirst);
        Path b = embedded("b", translatedFirst);
        FakeConfigs configs = new FakeConfigs();
        configs.values.put("source", "EMBEDDED_ONLY");
        FakeMenu menus = new FakeMenu();
        BilingualLyricsPlugin plugin = plugin(configs, menus);
        AtomicBoolean current = new AtomicBoolean(true);
        try {
            plugin.start();
            assertNull(BilingualLyricsPlugin.load("a", a.toString()), "An ordinary matching source remains with the host");
            BilingualLyricsPlugin.prepareRefresh("b", current::get);
            assertNull(BilingualLyricsPlugin.load("a", a.toString()), "Song B's request must not take over song A");
            String bOriginal = BilingualLyricsPlugin.load("b", b.toString());
            assertTrue(bOriginal.indexOf("君の夢") < bOriginal.indexOf("你的梦想"));
            assertEquals(OutputOrder.TRANSLATION_FIRST, menus.toggle.get());
            BilingualLyricsPlugin.prepareRefresh("b", current::get);
            assertEquals(translatedFirst, BilingualLyricsPlugin.load("b", b.toString()));
            current.set(false);
            assertNull(BilingualLyricsPlugin.load("b", b.toString()), "An expired generation must restore the normal fallback");
            String aTranslated = BilingualLyricsPlugin.load("a", a.toString());
            assertTrue(aTranslated.indexOf("你的梦想") < aTranslated.indexOf("君の夢"));
        } finally {
            plugin.stop();
        }
    }

    @Test
    void fileWatchNotificationsPreserveEquivalentSettingsAndRejectChangedOnes() throws Exception {
        for (boolean changed : new boolean[]{false, true}) {
            Path audio = embedded("watch-" + changed, "[00:01]你的梦想\n[00:01]君の夢");
            FakeConfigs configs = new FakeConfigs();
            configs.values.put("source", "EMBEDDED_ONLY");
            configs.values.put("output_order", "TRANSLATION_FIRST");
            FakeMenu menus = new FakeMenu();
            BilingualLyricsPlugin plugin = plugin(configs, menus);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            try {
                plugin.start();
                BilingualLyricsPlugin.prepareRefresh("watch", () -> {
                    started.countDown();
                    await(resume);
                    return true;
                });
                CompletableFuture<String> result = CompletableFuture.supplyAsync(() ->
                        BilingualLyricsPlugin.load("watch", audio.toString()));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                if (changed) configs.values.put("output_order", "ORIGINAL_FIRST");
                configs.current.accept(configs.helper);
                configs.current.accept(configs.helper);
                resume.countDown();
                String document = result.get(5, TimeUnit.SECONDS);
                if (changed) assertNull(document, "Actual preference changes must discard the old result");
                else assertNotNull(document, "Equivalent file-watch events must not erase the refresh result");
            } finally {
                resume.countDown();
                plugin.stop();
            }
        }
    }

    private Path embedded(String name, String lyrics) throws Exception {
        return Files.write(directory.resolve(name + ".flac"), flacWithLyrics(lyrics));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @Test
    void menuTogglePersistsOrderAndUpdatesTheActiveSnapshotWithoutConfigNotification() {
        FakeConfigs configs = new FakeConfigs();
        FakeMenu menus = new FakeMenu();
        BilingualLyricsPlugin plugin = plugin(configs, menus);
        try {
            plugin.start();
            assertEquals(OutputOrder.TRANSLATION_FIRST, menus.toggle.get());
            assertEquals("TRANSLATION_FIRST", configs.values.get("output_order"));
            assertEquals(OutputOrder.TRANSLATION_FIRST, menus.settings.get().normalization().outputOrder());
            assertEquals(OutputOrder.ORIGINAL_FIRST, menus.toggle.get());
            assertEquals(2, configs.saved);
            configs.values.put("output_order", "TRANSLATION_FIRST");
            configs.current.accept(configs.helper);
            assertEquals(OutputOrder.ORIGINAL_FIRST, menus.toggle.get(), "The menu must follow settings changed elsewhere");
        } finally {
            plugin.stop();
        }
    }

    @Test
    void failedSaveRestoresBothConfigurationAndActiveOrder() {
        FakeConfigs configs = new FakeConfigs();
        FakeMenu menus = new FakeMenu();
        BilingualLyricsPlugin plugin = plugin(configs, menus);
        try {
            plugin.start();
            configs.saveSucceeds = false;
            assertNull(menus.toggle.get());
            assertEquals("ORIGINAL_FIRST", configs.values.get("output_order"));
            assertEquals(OutputOrder.ORIGINAL_FIRST, menus.settings.get().normalization().outputOrder());
        } finally {
            plugin.stop();
        }
    }

    @Test
    void oldMenuCallbacksCannotChangeSettingsAfterStopOrReenable() {
        FakeConfigs configs = new FakeConfigs();
        FakeMenu menus = new FakeMenu();
        BilingualLyricsPlugin plugin = plugin(configs, menus);
        try {
            plugin.start();
            plugin.start();
            assertEquals(1, menus.installed);
            Supplier<OutputOrder> oldToggle = menus.toggle;
            Supplier<PluginSettings> oldSettings = menus.settings;
            plugin.stop();
            assertEquals(1, menus.closed);
            assertNull(oldSettings.get());
            assertNull(oldToggle.get());
            plugin.start();
            assertEquals(2, menus.installed);
            assertNull(oldToggle.get());
            assertEquals(0, configs.saved);
            assertEquals(OutputOrder.TRANSLATION_FIRST, menus.toggle.get());
        } finally {
            plugin.stop();
        }
        assertEquals(2, menus.closed);
    }

    @Test
    void disablingLyricProcessingAlsoDisablesTheMenuAction() {
        FakeConfigs configs = new FakeConfigs();
        FakeMenu menus = new FakeMenu();
        BilingualLyricsPlugin plugin = plugin(configs, menus);
        try {
            plugin.start();
            configs.values.put("enabled", false);
            configs.current.accept(configs.helper);
            assertFalse(menus.settings.get().enabled());
            assertNull(menus.toggle.get());
            assertEquals(0, configs.saved);
        } finally {
            plugin.stop();
        }
    }

    private BilingualLyricsPlugin plugin(FakeConfigs configs, FakeMenu menus) {
        return new BilingualLyricsPlugin(new PluginContext("test", "0.1.5", directory.toString(),
                "1.19.0", Channel.Steam), () -> configs, menus);
    }

    private static final class FakeMenu implements BilingualLyricsPlugin.MenuInstaller {
        Supplier<PluginSettings> settings;
        Supplier<OutputOrder> toggle;
        int installed;
        int closed;
        @Override public AutoCloseable install(Supplier<PluginSettings> settings, Supplier<OutputOrder> toggle) {
            this.settings = settings;
            this.toggle = toggle;
            installed++;
            return () -> closed++;
        }
    }

    @Test
    void reenableCreatesFreshListenerAndStoppedPluginIgnoresLateNotification() throws Exception {
        Path audio = Files.createFile(directory.resolve("test.mp3"));
        Files.writeString(directory.resolve("test.lrc"), "[00:01.00]你好世界\n[00:01.00]Hello world");
        FakeConfigs configs = new FakeConfigs();
        configs.values.put("source", "SIDECAR_ONLY");
        BilingualLyricsPlugin plugin = new BilingualLyricsPlugin(
                new PluginContext("test", "0.1.0", directory.toString(), "1.19.0", Channel.Steam), () -> configs);
        try {
            plugin.start();
            plugin.start();
            assertEquals(1, configs.added);
            String first = BilingualLyricsPlugin.load(audio.toString());
            assertNotNull(first);
            assertTrue(first.indexOf("Hello world") < first.indexOf("你好世界"));
            configs.values.put("output_order", "TRANSLATION_FIRST");
            configs.current.accept(configs.helper);
            String second = BilingualLyricsPlugin.load(audio.toString());
            assertNull(second, "A matching ordinary load must preserve the host's source selection");
            Consumer<ConfigHelper> old = configs.current;
            plugin.stop();
            old.accept(configs.helper);
            assertNull(BilingualLyricsPlugin.load(audio.toString()));
            assertEquals(1, configs.removed);
            configs.values.put("output_order", "ORIGINAL_FIRST");
            plugin.start();
            assertEquals(2, configs.added);
            configs.values.put("enabled", false);
            old.accept(configs.helper);
            assertNotNull(BilingualLyricsPlugin.load(audio.toString()), "A listener from the old activation must be ignored");
            plugin.stop();
            old.accept(configs.helper);
            assertNull(BilingualLyricsPlugin.load(audio.toString()));
            assertEquals(2, configs.removed);
        } finally {
            plugin.stop();
        }
    }

    private static final class FakeConfigs implements ConfigManager {
        final Map<String, Object> values = new HashMap<>();
        final ConfigHelper helper = new ConfigHelper() {
            @Override @SuppressWarnings("unchecked") public <T> T get(String key, T fallback) {
                Object value = values.get(key);
                return fallback.getClass().isInstance(value) ? (T) value : fallback;
            }
            @Override public void set(String key, Object value) { values.put(key, value); }
            @Override public boolean save() { saved++; return saveSucceeds; }
            @Override public boolean reload() { return true; }
            @Override public Path getConfigPath() { return Path.of("test.json"); }
        };
        int added;
        int removed;
        int saved;
        boolean saveSucceeds = true;
        Consumer<ConfigHelper> current;
        @Override public ConfigHelper getConfig() { return helper; }
        @Override public ConfigHelper getConfig(String fileName) { return helper; }
        @Override public void addConfigChangeListener(Consumer<ConfigHelper> listener) { added++; current = listener; }
        @Override public void addConfigChangeListener(String file, Consumer<ConfigHelper> listener) {
            addConfigChangeListener(listener);
        }
        @Override public void removeConfigChangeListener(Consumer<ConfigHelper> listener) {
            assertSame(current, listener);
            removed++;
            current = null;
        }
    }
}
