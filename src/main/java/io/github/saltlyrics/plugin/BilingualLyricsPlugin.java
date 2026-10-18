package io.github.saltlyrics.plugin;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BilingualLyricsPlugin extends SpwPlugin {
    private static final LyricsProcessor PROCESSOR = new LyricsProcessor();
    private static volatile PluginSettings settings;
    private ConfigManager manager;
    private Consumer<ConfigHelper> listener;
    private Object registration;
    private final Supplier<ConfigManager> configs;

    public BilingualLyricsPlugin(PluginContext context) {
        this(context, () -> WorkshopApi.manager().createConfigManager());
    }

    BilingualLyricsPlugin(PluginContext context, Supplier<ConfigManager> configs) {
        super(context);
        this.configs = configs;
    }

    @Override
    public synchronized void start() {
        if (manager != null) return;
        ConfigManager created = configs.get();
        Object token = new Object();
        Consumer<ConfigHelper> changed = config -> {
            // stop() uses the same monitor, preventing a late notification from re-enabling the plugin.
            synchronized (BilingualLyricsPlugin.this) {
                if (registration == token) settings = PluginSettings.read(config);
            }
        };
        try {
            manager = created;
            registration = token;
            listener = changed;
            created.addConfigChangeListener(changed);
            settings = PluginSettings.read(created.getConfig());
        } catch (RuntimeException exception) {
            settings = null;
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
        registration = null;
        if (manager != null) {
            ConfigManager previous = manager;
            Consumer<ConfigHelper> previousListener = listener;
            manager = null;
            listener = null;
            previous.removeConfigChangeListener(previousListener);
        }
    }

    static String load(String mediaPath) {
        PluginSettings snapshot = settings;
        if (snapshot == null) return null;
        String result = PROCESSOR.load(mediaPath, snapshot);
        return settings == snapshot ? result : null;
    }
}
