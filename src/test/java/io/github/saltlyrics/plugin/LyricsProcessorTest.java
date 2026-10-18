package io.github.saltlyrics.plugin;

import io.github.saltlyrics.core.Options;
import io.github.saltlyrics.source.LyricSourceReader.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LyricsProcessorTest {
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
    void unchangedPreferredSourceDoesNotGetReplacedByLowerPrioritySource() {
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
