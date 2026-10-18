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

import static org.junit.jupiter.api.Assertions.*;

class PluginLifecycleTest {
    @TempDir Path directory;

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
            // Already matching the requested order can return null so the host uses the original.
            if (second != null) assertTrue(second.indexOf("你好世界") < second.indexOf("Hello world"));
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
            @Override public boolean save() { return true; }
            @Override public boolean reload() { return true; }
            @Override public Path getConfigPath() { return Path.of("test.json"); }
        };
        int added;
        int removed;
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
