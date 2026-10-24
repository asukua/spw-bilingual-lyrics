package io.github.saltlyrics.plugin;

import io.github.saltlyrics.core.Options;
import io.github.saltlyrics.core.LyricNormalizer;
import io.github.saltlyrics.source.LyricSourceReader.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LyricsProcessorTest {
    @Test
    void sourceLoadingAppliesDisplaySettingsToMixedWordTiming() {
        String original = "か[00:30.35]細[00:30.62]い[00:30.88]火[00:31.20]が[00:31.50]";
        String translation = "纤细的火焰";
        LyricsProcessor processor = new LyricsProcessor((p, s, e) -> List.of(
                new Candidate("[00:30.10]" + original + "\n[00:30.10]" + translation, "sidecar")),
                new LyricNormalizer()::normalize);
        PluginSettings defaults = PluginSettings.defaults();
        PluginSettings translatedFirst = new PluginSettings(true,
                new Options(Options.OutputOrder.TRANSLATION_FIRST, Options.InputOrder.AUTO, true),
                defaults.source(), defaults.encoding());
        assertEquals("[00:30.100]纤细的火焰[00:31.500]\n[00:30.100]" + original,
                processor.load(java.nio.file.Path.of("test.mp3").toAbsolutePath().toString(), translatedFirst));
    }

    @Test
    void disabledOrNonlocalPathDoesNotReadAnything() {
        AtomicInteger reads = new AtomicInteger();
        LyricsProcessor processor = new LyricsProcessor((p, s, e) -> {
            reads.incrementAndGet();
            return List.of();
        }, (raw, options) -> Optional.of(raw));
        PluginSettings defaults = PluginSettings.defaults();
        PluginSettings disabled = new PluginSettings(false, defaults.normalization(), defaults.source(), defaults.encoding());
        assertNull(processor.load("C:/music/test.mp3", disabled));
        assertNull(processor.load("relative.mp3", defaults));
        assertNull(processor.load("https://example.test/song.mp3", defaults));
        assertNull(processor.load("\u0000", defaults));
        assertEquals(0, reads.get());
    }

    @Test
    void unsupportedPreferredSourceDoesNotGetReplacedByLowerPrioritySource() {
        AtomicInteger normalized = new AtomicInteger();
        LyricsProcessor processor = new LyricsProcessor((p, s, e) -> List.of(
                new Candidate("preferred", "embedded"), new Candidate("other", "sidecar")), (raw, options) -> {
            normalized.incrementAndGet();
            assertEquals("preferred", raw);
            return Optional.empty();
        });
        assertNull(processor.load(java.nio.file.Path.of("test.mp3").toAbsolutePath().toString(), PluginSettings.defaults()));
        assertEquals(1, normalized.get());
    }

    @Test
    void sourceFailureReturnsControlToHost() {
        LyricsProcessor processor = new LyricsProcessor((p, s, e) -> { throw new IllegalStateException(); },
                (raw, options) -> Optional.of(raw));
        assertNull(processor.load(java.nio.file.Path.of("test.mp3").toAbsolutePath().toString(), PluginSettings.defaults()));
    }
}
