package io.github.saltlyrics.plugin;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import java.util.jar.JarFile;

/** Signature-checked access to the host's native menu and lyric reload operation. */
final class HostMenuAccess {
    private static final String MENU_DESCRIPTOR = "(Lkotlin/jvm/functions/Function0;"
            + "Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function1;"
            + "Landroidx/compose/runtime/Composer;II)V";
    record Target(String owner, String method, String inClass) {}
    record Builder(Method method, int scopeIndex, int dismissIndex, Method dismiss) {}
    @FunctionalInterface
    interface Reload {
        boolean run(BiFunction<String, BooleanSupplier, Runnable> prepare);
    }

    final Target target;
    final Class<?> scopeType;
    final Builder builder;
    private final Method item;
    private final Method label;
    private final Reload reload;

    private HostMenuAccess(Target target, Class<?> scopeType, Method item, Method label, Reload reload) {
        this(target, scopeType, item, label, reload, null);
    }

    private HostMenuAccess(Target target, Class<?> scopeType, Method item, Method label,
                           Reload reload, Builder builder) {
        this.target = target;
        this.scopeType = scopeType;
        this.item = item;
        this.label = label;
        this.reload = reload;
        this.builder = builder;
    }

    static HostMenuAccess discover(ClassLoader loader) throws ReflectiveOperationException, IOException {
        String controllerName = "com.xuncorp.voxzen.service.PlaybackController";
        var resource = loader.getResource(controllerName.replace('.', '/') + ".class");
        if (resource == null || !(resource.openConnection() instanceof JarURLConnection connection)) {
            throw new IllegalStateException("Host archive is unavailable");
        }
        connection.setUseCaches(false);
        Target target;
        try (JarFile jar = connection.getJarFile()) {
            target = discoverTarget(jar);
        }
        HostMenuAccess access = bind(target, loader, reloadOperation(Class.forName(controllerName, false, loader)));
        Builder builder = findBuilder(Class.forName(target.inClass(), false, loader), access.scopeType);
        return new HostMenuAccess(target, access.scopeType, access.item, access.label, access.reload, builder);
    }

    static Target discoverTarget(JarFile jar) throws IOException {
        List<Target> targets = new ArrayList<>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (!entry.getName().endsWith(".class") || entry.getSize() > 2_000_000
                    || !(entry.getName().startsWith("androidx/compose/ui/")
                    || entry.getName().startsWith("com/xuncorp/voxzen/ui/player/"))) continue;
            try (var input = jar.getInputStream(entry)) {
                targets.addAll(inspectMenu(input.readAllBytes()));
            }
        }
        return unique(targets.stream().distinct().toList(), "player menu call");
    }

    static List<Target> inspectMenu(byte[] bytes) {
        // The original source marker survives obfuscation. Resolve the actual
        // invocation from bytecode instead of maintaining obfuscated class names.
        if (!new String(bytes, StandardCharsets.ISO_8859_1).contains("PlayerMoreMenuFlyout.kt")) {
            return List.of();
        }
        var model = ClassFile.of().parse(bytes);
        List<Target> targets = new ArrayList<>();
        for (var method : model.methods()) {
            if (method.code().isEmpty()) continue;
            for (var element : method.code().orElseThrow()) {
                if (element instanceof InvokeInstruction call && call.type().equalsString(MENU_DESCRIPTOR)) {
                    targets.add(new Target(call.owner().asInternalName().replace('/', '.'),
                            call.name().stringValue(), model.thisClass().asInternalName().replace('/', '.')));
                }
            }
        }
        return targets;
    }

    static HostMenuAccess bind(Target target, ClassLoader loader, BooleanSupplier reload)
            throws ReflectiveOperationException {
        return bind(target, loader, prepare -> reload.getAsBoolean());
    }

    private static HostMenuAccess bind(Target target, ClassLoader loader, Reload reload)
            throws ReflectiveOperationException {
        Class<?> owner = Class.forName(target.owner(), false, loader);
        Method popup = owner.getDeclaredMethod(target.method(), Function0.class, Function2.class,
                Function1.class, Composer.class, int.class, int.class);
        if (!(popup.getGenericParameterTypes()[2] instanceof ParameterizedType builder)
                || !(builder.getActualTypeArguments()[0] instanceof WildcardType argument)
                || argument.getLowerBounds().length != 1
                || !(argument.getLowerBounds()[0] instanceof Class<?> scope)) {
            throw new IllegalStateException("Unsupported menu scope signature");
        }
        Method item = unique(Arrays.stream(scope.getMethods()).filter(method ->
                method.getReturnType() == void.class && Arrays.equals(method.getParameterTypes(),
                        new Class<?>[]{Function0.class, boolean.class, Function2.class})).toList(), "menu item");
        Method label = unique(Arrays.stream(owner.getDeclaredMethods()).filter(method ->
                method.getReturnType() == void.class && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class, Modifier.class,
                        Function2.class, boolean.class, Composer.class, int.class, int.class})).toList(), "menu label");
        item.setAccessible(true);
        label.setAccessible(true);
        return new HostMenuAccess(target, scope, item, label, reload);
    }

    void append(Object scope, String title, boolean enabled, Runnable action) throws ReflectiveOperationException {
        Function0<Unit> click = () -> { action.run(); return Unit.INSTANCE; };
        // Forward rendering to the host's already compiled Composable. The
        // plugin creates no Compose nodes or state and inherits native styling.
        Function2<Composer, Integer, Unit> content = (composer, changed) -> {
            try {
                label.invoke(null, title, null, null, enabled, composer, 0, 6);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                log("菜单文字显示失败", exception);
            }
            return Unit.INSTANCE;
        };
        item.invoke(scope, click, enabled, content);
    }

    boolean reloadLyrics() {
        return reloadLyrics((id, current) -> () -> {});
    }

    boolean reloadLyrics(BiFunction<String, BooleanSupplier, Runnable> prepare) {
        return reload.run(prepare);
    }

    Runnable dismissAction(Object[] arguments) {
        Object state = arguments[builder.dismissIndex()];
        return () -> {
            try {
                builder.dismiss().invoke(state);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                log("歌曲菜单关闭失败", exception);
            }
        };
    }

    static Builder findBuilder(Class<?> menuClass, Class<?> scopeType)
            throws ReflectiveOperationException, IOException {
        ClassModel model = classModel(menuClass);
        // Obfuscation can erase the native Function1 parameter's source name.
        // Hook its concrete ordinary builder after it populates the scope;
        // neither this method nor the dismiss method accepts a Composer.
        Method builder = unique(Arrays.stream(menuClass.getDeclaredMethods()).filter(method ->
                java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getReturnType() == Unit.class
                        && Arrays.asList(method.getParameterTypes()).contains(scopeType)
                        && !Arrays.asList(method.getParameterTypes()).contains(Composer.class)
                        && calls(model, method).stream().anyMatch(call ->
                        call.owner().asInternalName().equals(scopeType.getName().replace('.', '/'))
                                && call.type().stringValue().contains("Lkotlin/jvm/functions/Function0;")
                                && call.type().stringValue().contains("Lkotlin/jvm/functions/Function2;")))
                .toList(), "ordinary player menu builder");
        List<Builder> candidates = new ArrayList<>();
        Class<?>[] types = builder.getParameterTypes();
        int scopeIndex = Arrays.asList(types).indexOf(scopeType);
        for (int index = 0; index < types.length; index++) {
            if (types[index] == scopeType || types[index].isPrimitive()) continue;
            for (Method helper : menuClass.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(helper.getModifiers()) || helper.getReturnType() != Unit.class
                        || !Arrays.equals(helper.getParameterTypes(), new Class<?>[]{types[index]})) continue;
                for (InvokeInstruction call : calls(model, helper)) {
                    if (!call.owner().asInternalName().equals(types[index].getName().replace('.', '/'))
                            || !call.type().equalsString("()V")) continue;
                    Method dismiss = types[index].getDeclaredMethod(call.name().stringValue());
                    dismiss.setAccessible(true);
                    candidates.add(new Builder(builder, scopeIndex, index, dismiss));
                }
            }
        }
        builder.setAccessible(true);
        return unique(candidates, "player menu dismiss binding");
    }

    private static ClassModel classModel(Class<?> type) throws IOException {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (input == null) throw new IOException("Missing class bytes: " + type.getName());
            return ClassFile.of().parse(input.readAllBytes());
        }
    }

    private static List<InvokeInstruction> calls(ClassModel model, Method method) {
        String descriptor = MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString();
        List<InvokeInstruction> calls = new ArrayList<>();
        for (var candidate : model.methods()) {
            if (!candidate.methodName().equalsString(method.getName()) || !candidate.methodType().equalsString(descriptor)
                    || candidate.code().isEmpty()) continue;
            for (var element : candidate.code().orElseThrow()) {
                if (element instanceof InvokeInstruction call) calls.add(call);
            }
        }
        return calls;
    }

    static BooleanSupplier reloadBinding(Class<?> controller) throws ReflectiveOperationException, IOException {
        Reload operation = reloadOperation(controller);
        return () -> operation.run((id, current) -> () -> {});
    }

    static Reload reloadOperation(Class<?> controller) throws ReflectiveOperationException, IOException {
        Field service = controller.getDeclaredField("service");
        Field lyricLoader = service.getType().getDeclaredField("lyricsLoader");
        Class<?> loaderType = lyricLoader.getType();
        Field currentItem = loaderType.getDeclaredField("currentItem");
        Field generation = loaderType.getDeclaredField("generation");
        Field lock = loaderType.getDeclaredField("lock");
        Method reload = loaderType.getDeclaredMethod("reload", String.class);
        List<String> getters = new ArrayList<>();
        try (var bytes = loaderType.getResourceAsStream("/" + loaderType.getName().replace('.', '/') + ".class")) {
            if (bytes == null) throw new IOException("Missing lyric loader bytecode");
            for (var method : ClassFile.of().parse(bytes.readAllBytes()).methods()) {
                if (!method.methodName().equalsString("reload")
                        || !method.methodType().equalsString("(Ljava/lang/String;)V") || method.code().isEmpty()) continue;
                for (var element : method.code().orElseThrow()) {
                    if (element instanceof InvokeInstruction call
                            && call.owner().asInternalName().replace('/', '.').equals(currentItem.getType().getName())
                            && call.type().equalsString("()Ljava/lang/String;")) getters.add(call.name().stringValue());
                }
            }
        }
        Method id = currentItem.getType().getDeclaredMethod(
                unique(getters.stream().distinct().toList(), "current media ID getter"));
        for (Field field : List.of(service, lyricLoader, currentItem, generation, lock)) field.setAccessible(true);
        reload.setAccessible(true);
        id.setAccessible(true);
        return prepare -> {
            Runnable cancel = () -> {};
            try {
                Object loader = lyricLoader.get(service.get(null));
                Object monitor = lock.get(loader);
                synchronized (monitor) {
                    Object media = currentItem.get(loader);
                    if (media == null) return true;
                    String mediaId = (String) id.invoke(media);
                    long expected = generation.getLong(loader) + 1;
                    BooleanSupplier current = () -> {
                        synchronized (monitor) {
                            try {
                                return generation.getLong(loader) == expected;
                            } catch (IllegalAccessException exception) {
                                return false;
                            }
                        }
                    };
                    cancel = prepare.apply(mediaId, current);
                    reload.invoke(loader, mediaId);
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                cancel.run();
                log("当前歌词刷新失败", exception);
                return false;
            }
        };
    }

    private static <T> T unique(List<T> values, String description) {
        if (values.size() != 1) throw new IllegalStateException("Missing or ambiguous " + description);
        return values.getFirst();
    }

    static void log(String message, Throwable exception) {
        System.getLogger(HostMenuAccess.class.getName()).log(System.Logger.Level.WARNING, message, exception);
        // System.Logger goes to stderr in the packaged player. Mirror failures
        // to its existing file logger so installed-plugin failures are readable.
        try {
            Class<?> logger = Class.forName("com.xuncorp.voxzen.util.FileLogger");
            logger.getMethod("error", String.class, String.class, Throwable.class)
                    .invoke(logger.getField("INSTANCE").get(null), "BilingualLyrics", message, exception);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // File logging is optional and must not break playback or cleanup.
        }
    }
}
