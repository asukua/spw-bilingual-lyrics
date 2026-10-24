package io.github.saltlyrics.plugin;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginClassLoader;

import java.util.jar.JarFile;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HostMenuAccessTest {
    @TempDir Path plugins;
    @Test
    void findsTheNativeMenuInvocationFromSourceMarkerAndSignature() throws Exception {
        var targets = HostMenuAccess.inspectMenu(bytecode(FakePlayerMenu.class));
        assertEquals(1, targets.size());
        assertEquals(new HostMenuAccess.Target(FakeUi.class.getName(), "popup", FakePlayerMenu.class.getName()),
                targets.getFirst());
        assertTrue(HostMenuAccess.inspectMenu(bytecode(FakeUi.class)).isEmpty());
    }

    @Test
    void nativeScopeKeepsTheSuppliedActionEnabledStateAndHostLabelRenderer() throws Exception {
        var access = HostMenuAccess.bind(new HostMenuAccess.Target(FakeUi.class.getName(), "popup", "unused"),
                getClass().getClassLoader(), () -> true);
        FakeScope scope = new FakeScope();
        int[] clicked = {0};
        access.append(scope, "切换为译文在上", true, () -> clicked[0]++);
        assertTrue(scope.enabled);
        scope.content.invoke(null, 0);
        assertEquals("切换为译文在上", FakeUi.title);
        assertEquals(6, FakeUi.defaults, "Only native modifier/icon defaults should be used");
        assertTrue(FakeUi.enabled);
        scope.click.invoke();
        assertEquals(1, clicked[0]);

        access.append(scope, "切换为原文在上", false, () -> fail("Disabled item was clicked"));
        assertFalse(scope.enabled);
        scope.content.invoke(null, 0);
        assertFalse(FakeUi.enabled);
    }

    @Test
    void resolvesTheCurrentMediaIdFromTheLoadersOwnReloadImplementation() throws Exception {
        FakeLoader loader = FakeController.service.lyricsLoader;
        loader.currentItem = new FakeMedia("current-song");
        loader.reloaded = null;
        loader.fail = false;
        var reload = HostMenuAccess.reloadBinding(FakeController.class);
        assertTrue(reload.getAsBoolean());
        assertEquals("current-song", loader.reloaded);
        loader.currentItem = new FakeMedia("next-song");
        assertTrue(reload.getAsBoolean());
        assertEquals("next-song", loader.reloaded, "Do not keep a stale song ID from a previous lyric callback");
    }

    @Test
    void noCurrentSongAndReloadFailureHaveSafeResults() throws Exception {
        FakeLoader loader = FakeController.service.lyricsLoader;
        var reload = HostMenuAccess.reloadBinding(FakeController.class);
        loader.currentItem = null;
        loader.reloaded = null;
        assertTrue(reload.getAsBoolean());
        assertNull(loader.reloaded);
        loader.currentItem = new FakeMedia("current-song");
        loader.fail = true;
        try {
            assertFalse(reload.getAsBoolean());
        } finally {
            loader.fail = false;
        }
    }

    @Test
    void eachReloadPreparesOneRequestAndExpiresThePreviousGeneration() throws Exception {
        FakeLoader loader = FakeController.service.lyricsLoader;
        loader.currentItem = new FakeMedia("current-song");
        loader.fail = false;
        var operation = HostMenuAccess.reloadOperation(FakeController.class);
        AtomicInteger prepared = new AtomicInteger();
        AtomicReference<java.util.function.BooleanSupplier> latest = new AtomicReference<>();
        for (int index = 0; index < 6; index++) {
            var previous = latest.get();
            assertTrue(operation.run((mediaId, current) -> {
                assertEquals("current-song", mediaId);
                prepared.incrementAndGet();
                latest.set(current);
                return () -> fail("A successful reload must retain its generation guard");
            }));
            assertTrue(latest.get().getAsBoolean());
            if (previous != null) assertFalse(previous.getAsBoolean());
        }
        assertEquals(6, prepared.get());
        loader.generation++; // A subsequent ordinary load invalidates the menu request.
        assertFalse(latest.get().getAsBoolean());
    }

    @Test
    void failedReloadCancelsItsPreparedRequest() throws Exception {
        FakeLoader loader = FakeController.service.lyricsLoader;
        loader.currentItem = new FakeMedia("current-song");
        loader.fail = true;
        AtomicInteger cancelled = new AtomicInteger();
        try {
            assertFalse(HostMenuAccess.reloadOperation(FakeController.class)
                    .run((id, current) -> cancelled::incrementAndGet));
            assertEquals(1, cancelled.get());
        } finally {
            loader.fail = false;
        }
    }

    @Test
    void missingOrAmbiguousNativeSignaturesAreRejected() {
        assertThrows(NoSuchMethodException.class, () -> HostMenuAccess.bind(
                new HostMenuAccess.Target(FakeUi.class.getName(), "missing", "unused"),
                getClass().getClassLoader(), () -> true));
        assertThrows(NoSuchFieldException.class, () -> HostMenuAccess.reloadBinding(FakeMedia.class));
        assertThrows(IllegalStateException.class, () -> HostMenuAccess.findBuilder(FakeUi.class, FakeScope.class));
    }

    @Test
    void findsAnOrdinaryBuilderAndDismissOperationWithoutSourceParameterNames() throws Exception {
        var binding = HostMenuAccess.findBuilder(FakePlayerMenu.class, FakeScope.class);
        assertEquals("build", binding.method().getName());
        assertFalse(binding.method().getParameters()[0].isNamePresent());
        FakePopupState state = new FakePopupState();
        FakeScope scope = new FakeScope();
        binding.method().invoke(null, state, scope);
        assertEquals(1, scope.items);
        binding.dismiss().invoke(state);
        assertTrue(state.dismissed);
    }

    @Test
    @EnabledIfSystemProperty(named = "saltlyrics.hostArchive", matches = ".+")
    void targetHostHasExactlyOneCompatibleMenuAndReloadBinding() throws Exception {
        Path host = Path.of(System.getProperty("saltlyrics.hostArchive"));
        try (JarFile archive = new JarFile(host.toFile());
             URLClassLoader loader = new URLClassLoader(new java.net.URL[]{host.toUri().toURL()},
                     getClass().getClassLoader())) {
            var access = HostMenuAccess.discover(loader);
            var target = access.target;
            assertNotNull(archive.getJarEntry(target.owner().replace('.', '/') + ".class"));
            assertNotNull(archive.getJarEntry(target.inClass().replace('.', '/') + ".class"));
            assertNotNull(access.scopeType);
            assertNotNull(access.builder);
            assertHostAcceptsBuilder(archive, loader, access);
            assertNativeBuilderKeepsExistingItemsAndAcceptsTheToggle(loader, access);
            try (PluginClassLoader pluginLoader = new PluginClassLoader(new DefaultPluginManager(plugins),
                    new DefaultPluginDescriptor("test.menu", "", "unused", "1.0.0", "*", "", ""), loader)) {
                pluginLoader.addURL(HostMenuAccess.class.getProtectionDomain().getCodeSource().getLocation());
                Class<?> isolated = pluginLoader.loadClass(HostMenuAccess.class.getName());
                assertSame(pluginLoader, isolated.getClassLoader());
                Method discover = isolated.getDeclaredMethod("discover", ClassLoader.class);
                discover.setAccessible(true);
                assertNotNull(discover.invoke(null, pluginLoader), "Discovery must also work inside a real plugin loader");
            }
        }
    }

    private static void assertNativeBuilderKeepsExistingItemsAndAcceptsTheToggle(ClassLoader loader,
                                                                               HostMenuAccess access) throws Exception {
        List<Object[]> items = new ArrayList<>();
        Object scope = Proxy.newProxyInstance(loader, new Class<?>[]{access.scopeType}, (proxy, method, arguments) -> {
            if (arguments != null && arguments.length == 3 && arguments[0] instanceof Function0<?>) {
                items.add(arguments.clone());
            }
            return null;
        });
        Object[] arguments = new Object[access.builder.method().getParameterCount()];
        arguments[access.builder.scopeIndex()] = scope;
        Class<?> popupType = access.builder.method().getParameterTypes()[access.builder.dismissIndex()];
        Object popup = popupType.getConstructor(boolean.class).newInstance(true);
        arguments[access.builder.dismissIndex()] = popup;
        access.builder.method().invoke(null, arguments);
        int originalItems = items.size();
        assertTrue(originalItems >= 3, "The actual host builder should populate its existing menu");
        int[] clicked = {0};
        access.append(scope, "切换为译文在上", true, () -> clicked[0]++);
        assertEquals(originalItems + 1, items.size());
        ((Function0<?>) items.getLast()[0]).invoke();
        assertEquals(1, clicked[0]);
        access.dismissAction(arguments).run();
        Method expanded = Arrays.stream(popupType.getDeclaredMethods()).filter(method ->
                method.getParameterCount() == 0 && method.getReturnType() == boolean.class).findFirst().orElseThrow();
        assertEquals(false, expanded.invoke(popup));
    }

    private static void assertHostAcceptsBuilder(JarFile archive, ClassLoader loader,
                                                HostMenuAccess access) throws Exception {
        // Exercise the host's resolver as well as reflection binding: an ordinary
        // JVM signature alone does not prove the Hook API accepts this call.
        for (var entry : java.util.Collections.list(archive.entries())) {
            if (!entry.getName().startsWith("com/xuncorp/voxzen/workshop/hooks/")
                    || !entry.getName().endsWith(".class") || entry.getName().contains("$")) continue;
            byte[] bytes;
            try (var input = archive.getInputStream(entry)) { bytes = input.readAllBytes(); }
            if (!new String(bytes, StandardCharsets.ISO_8859_1).contains("WorkshopHookTargets.kt")) continue;
            Class<?> resolver = Class.forName(entry.getName().replace('/', '.').replace(".class", ""), false, loader);
            Method resolve = Arrays.stream(resolver.getDeclaredMethods()).filter(method ->
                    Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class, String.class,
                            List.class})).findFirst().orElseThrow();
            var singleton = Arrays.stream(resolver.getDeclaredFields()).filter(field ->
                    java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == resolver)
                    .findFirst().orElseThrow();
            singleton.setAccessible(true);
            resolve.setAccessible(true);
            Object resolved = resolve.invoke(singleton.get(null), access.target.inClass(), access.builder.method().getName(),
                    Arrays.stream(access.builder.method().getParameterTypes()).map(Class::getName).toList());
            Method selected = Arrays.stream(resolved.getClass().getDeclaredMethods()).filter(method ->
                    method.getParameterCount() == 0 && method.getReturnType() == Method.class).findFirst().orElseThrow();
            selected.setAccessible(true);
            assertEquals(access.builder.method(), selected.invoke(resolved),
                    "The host must accept the concrete ordinary builder for method registration");
            return;
        }
        fail("Host hook resolver was not found");
    }

    private static byte[] bytecode(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    static final class FakePlayerMenu {
        static final String SOURCE = "PlayerMoreMenuFlyout.kt";
        static void draw() {
            FakeUi.popup(null, null, scope -> build(new FakePopupState(), scope), null, 0, 2);
        }
        private static Unit build(FakePopupState state, FakeScope scope) {
            scope.item(() -> Unit.INSTANCE, true, (composer, changed) -> Unit.INSTANCE);
            return Unit.INSTANCE;
        }
        private static Unit close(FakePopupState state) {
            state.dismiss();
            return Unit.INSTANCE;
        }
    }

    static final class FakePopupState {
        boolean dismissed;
        void dismiss() { dismissed = true; }
    }

    static final class FakeScope {
        Function0<Unit> click;
        Function2<? super Composer, ? super Integer, Unit> content;
        boolean enabled;
        int items;
        public void item(Function0<Unit> click, boolean enabled,
                         Function2<? super Composer, ? super Integer, Unit> content) {
            this.click = click;
            this.enabled = enabled;
            this.content = content;
            items++;
        }
    }

    static final class FakeUi {
        static String title;
        static boolean enabled;
        static int defaults;
        public static void popup(Function0<Unit> dismiss, Function2<? super Composer, ? super Integer, Unit> header,
                                 Function1<? super FakeScope, Unit> content, Composer composer, int changed, int defaults) {}
        public static void label(String title, Modifier modifier, Function2<?, ?, ?> icon, boolean enabled,
                                 Composer composer, int changed, int defaults) {
            FakeUi.title = title;
            FakeUi.enabled = enabled;
            FakeUi.defaults = defaults;
        }
    }

    static final class FakeController {
        private static final FakeService service = new FakeService();
    }
    static final class FakeService {
        private final FakeLoader lyricsLoader = new FakeLoader();
    }
    static final class FakeLoader {
        private FakeMedia currentItem;
        private final Object lock = new Object();
        private long generation;
        String reloaded;
        boolean fail;
        public void reload(String id) {
            if (fail) throw new IllegalStateException("reload failed");
            if (currentItem != null && currentItem.key().equals(id)) {
                generation++;
                reloaded = id;
            }
        }
    }
    record FakeMedia(String key) {}
}
