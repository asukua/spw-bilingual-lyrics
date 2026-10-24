package io.github.saltlyrics.plugin;

import com.xuncorp.spw.workshop.api.PluginPermission;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.hook.MethodHook;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;
import io.github.saltlyrics.core.Options.OutputOrder;
import java.util.Arrays;
import java.util.function.Supplier;

/** Adds one native menu item without replacing any existing menu content. */
final class PlayerMenuSupport {
    static AutoCloseable install(Supplier<PluginSettings> settings, Supplier<OutputOrder> toggle) {
        try {
            if (!WorkshopApi.manager().isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
                unavailable("歌曲菜单按钮未加载：请在模组权限中允许修改应用行为");
                return null;
            }
            HostMenuAccess access = HostMenuAccess.discover(PlayerMenuSupport.class.getClassLoader());
            return WorkshopApi.hookRegistrar().hookMethod(access.target.inClass(), access.builder.method().getName(),
                    Arrays.stream(access.builder.method().getParameterTypes()).map(Class::getName).toList(),
                    new MethodHook() {
                        @Override public void after(MethodHookParam call) {
                            if (call.getThrowable() != null) return;
                            Object[] arguments = call.getArgs();
                            append(access, arguments[access.builder.scopeIndex()], access.dismissAction(arguments),
                                    settings, toggle);
                        }
                    });
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException | LinkageError exception) {
            HostMenuAccess.log("歌曲菜单按钮不可用，歌词整理仍正常运行", exception);
            unavailable("歌曲菜单按钮加载失败，仍可在模组配置中切换顺序");
            return null;
        }
    }

    private static void append(HostMenuAccess access, Object scope, Runnable dismiss,
                               Supplier<PluginSettings> settings, Supplier<OutputOrder> toggle) {
        PluginSettings snapshot = settings.get();
        if (snapshot == null) return;
        String title = snapshot.normalization().outputOrder() == OutputOrder.ORIGINAL_FIRST
                ? "切换为译文在上" : "切换为原文在上";
        try {
            access.append(scope, title, snapshot.enabled(), () -> {
                OutputOrder order = toggle.get();
                if (order == null) {
                    if (settings.get() != null) toast("歌词顺序保存失败，请重试", false);
                    return;
                }
                dismiss.run();
                boolean refreshed = access.reloadLyrics(BilingualLyricsPlugin::prepareRefresh);
                String text = order == OutputOrder.ORIGINAL_FIRST ? "已切换为原文在上" : "已切换为译文在上";
                toast(text + (refreshed ? "" : "，切歌后生效"), refreshed);
            });
        } catch (ReflectiveOperationException | RuntimeException exception) {
            HostMenuAccess.log("歌曲菜单按钮添加失败", exception);
        }
    }

    private static void toast(String text, boolean success) {
        WorkshopApi.ui().toast(text, success ? WorkshopApi.Ui.ToastType.Success : WorkshopApi.Ui.ToastType.Warning);
    }

    private static void unavailable(String text) {
        try {
            toast(text, false);
        } catch (RuntimeException | LinkageError exception) {
            HostMenuAccess.log(text, exception);
        }
    }
}
