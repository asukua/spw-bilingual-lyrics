package io.github.saltlyrics.plugin;

import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import io.github.saltlyrics.core.Options;
import io.github.saltlyrics.core.Options.InputOrder;
import io.github.saltlyrics.core.Options.OutputOrder;
import io.github.saltlyrics.source.SourcePreference;
import io.github.saltlyrics.source.TextEncoding;

/** An immutable snapshot so a loading callback never sees half-updated settings. */
record PluginSettings(boolean enabled, Options normalization, SourcePreference source, TextEncoding encoding) {
    static PluginSettings defaults() {
        return new PluginSettings(true, Options.defaults(), SourcePreference.EMBEDDED_FIRST, TextEncoding.AUTO);
    }

    static PluginSettings read(ConfigHelper config) {
        return new PluginSettings(
                config.get("enabled", true),
                new Options(
                        value(OutputOrder.class, config.get("output_order", "ORIGINAL_FIRST"), OutputOrder.ORIGINAL_FIRST),
                        value(InputOrder.class, config.get("input_order", "AUTO"), InputOrder.AUTO),
                        config.get("split_inline", true)),
                value(SourcePreference.class, config.get("source", "EMBEDDED_FIRST"), SourcePreference.EMBEDDED_FIRST),
                value(TextEncoding.class, config.get("encoding", "AUTO"), TextEncoding.AUTO));
    }

    private static <T extends Enum<T>> T value(Class<T> type, String name, T fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return fallback;
        }
    }
}
