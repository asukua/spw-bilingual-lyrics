package io.github.saltlyrics.core;

import java.util.Objects;

/** Immutable lyric display preferences, including an explicit input-order override. */
public record Options(OutputOrder outputOrder, InputOrder inputOrder, boolean splitInline) {
    public Options {
        Objects.requireNonNull(outputOrder, "outputOrder");
        Objects.requireNonNull(inputOrder, "inputOrder");
    }

    public static Options defaults() {
        return new Options(OutputOrder.ORIGINAL_FIRST, InputOrder.AUTO, true);
    }

    public enum OutputOrder {
        ORIGINAL_FIRST,
        TRANSLATION_FIRST
    }

    public enum InputOrder {
        AUTO,
        ORIGINAL_FIRST,
        TRANSLATION_FIRST
    }
}
