package io.github.saltlyrics.plugin;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;
import io.github.saltlyrics.core.Options.OutputOrder;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicReference;

public final class BilingualLyricsPlugin extends SpwPlugin {
    private static final LyricsProcessor PROCESSOR = new LyricsProcessor();
    private static volatile PluginSettings settings;
    // This belongs to one native reload generation, not one callback: a
    // cancelled previous load may still enter the callback for the same song.
    private record Refresh(String mediaId, PluginSettings settings, BooleanSupplier current) {}
    private static final AtomicReference<Refresh> refresh = new AtomicReference<>();
    private ConfigManager manager;
    private Consumer<ConfigHelper> listener;
    private Object registration;
    private AutoCloseable menu;
    private final Supplier<ConfigManager> configs;
    private final MenuInstaller menus;

    @FunctionalInterface
    interface MenuInstaller {
        AutoCloseable install(Supplier<PluginSettings> settings, Supplier<OutputOrder> toggle);
    }

    public BilingualLyricsPlugin(PluginContext context) {
        this(context, () -> WorkshopApi.manager().createConfigManager(), PlayerMenuSupport::install);
    }

    BilingualLyricsPlugin(PluginContext context, Supplier<ConfigManager> configs) {
        this(context, configs, (settings, toggle) -> null);
    }

    BilingualLyricsPlugin(PluginContext context, Supplier<ConfigManager> configs, MenuInstaller menus) {
        super(context);
        this.configs = configs;
        this.menus = menus;
    }

    @Override
    public synchronized void start() {
        if (manager != null) return;
        ConfigManager created = configs.get();
        Object token = new Object();
        Consumer<ConfigHelper> changed = config -> {
            // stop() uses the same monitor, preventing a late notification from re-enabling the plugin.
            synchronized (BilingualLyricsPlugin.this) {
                if (registration == token) applySettings(PluginSettings.read(config));
            }
        };
        try {
            manager = created;
            registration = token;
            listener = changed;
            created.addConfigChangeListener(changed);
            refresh.set(null);
            settings = PluginSettings.read(created.getConfig());
            menu = menus.install(() -> currentSettings(token), () -> toggleOrder(token));
        } catch (RuntimeException exception) {
            closeMenu();
            settings = null;
            refresh.set(null);
            manager = null;
            registration = null;
            listener = null;
            created.removeConfigChangeListener(changed);
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        settings = null;
        refresh.set(null);
        registration = null;
        closeMenu();
        if (manager != null) {
            ConfigManager previous = manager;
            Consumer<ConfigHelper> previousListener = listener;
            manager = null;
            listener = null;
            previous.removeConfigChangeListener(previousListener);
        }
    }

    private synchronized PluginSettings currentSettings(Object token) {
        return registration == token ? settings : null;
    }

    private synchronized OutputOrder toggleOrder(Object token) {
        if (registration != token || manager == null || settings == null || !settings.enabled()) return null;
        PluginSettings previous = settings;
        OutputOrder next = previous.normalization().outputOrder() == OutputOrder.ORIGINAL_FIRST
                ? OutputOrder.TRANSLATION_FIRST : OutputOrder.ORIGINAL_FIRST;
        ConfigHelper config = manager.getConfig();
        String oldValue = config.get("output_order", "ORIGINAL_FIRST");
        try {
            config.set("output_order", next.name());
            if (!config.save()) throw new IllegalStateException("Configuration save failed");
            applySettings(PluginSettings.read(config));
            return next;
        } catch (RuntimeException exception) {
            config.set("output_order", oldValue);
            settings = previous;
            HostMenuAccess.log("歌词顺序保存失败", exception);
            return null;
        }
    }

    private static void applySettings(PluginSettings next) {
        // Saving a menu choice also triggers asynchronous file-watch events.
        // Equivalent notifications must not invalidate an in-flight reload.
        if (!next.equals(settings)) {
            settings = next;
            refresh.set(null);
        }
    }

    static Runnable prepareRefresh(String mediaId, BooleanSupplier current) {
        PluginSettings snapshot = settings;
        if (snapshot == null || mediaId == null || mediaId.isBlank()) return () -> {};
        Refresh request = new Refresh(mediaId, snapshot, current);
        refresh.set(request);
        return () -> refresh.compareAndSet(request, null);
    }

    private void closeMenu() {
        AutoCloseable previous = menu;
        menu = null;
        if (previous != null) {
            try {
                previous.close();
            } catch (Exception exception) {
                HostMenuAccess.log("歌曲菜单按钮清理失败", exception);
            }
        }
    }

    static String load(String mediaPath) {
        return load("", mediaPath);
    }

    static String load(String mediaId, String mediaPath) {
        PluginSettings snapshot = settings;
        if (snapshot == null) return null;
        Refresh request = refresh.get();
        boolean current = request != null && request.settings() == snapshot && request.current().getAsBoolean();
        boolean explicit = current && request.mediaId().equals(mediaId);
        if (request != null && !current) {
            refresh.compareAndSet(request, null);
        }
        String result = PROCESSOR.load(mediaPath, snapshot, explicit);
        return settings == snapshot ? result : null;
    }
}
