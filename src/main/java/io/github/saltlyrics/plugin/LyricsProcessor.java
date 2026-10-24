package io.github.saltlyrics.plugin;

import io.github.saltlyrics.core.LyricNormalizer;
import io.github.saltlyrics.core.Options;
import io.github.saltlyrics.source.LyricSourceReader;
import io.github.saltlyrics.source.SourcePreference;
import io.github.saltlyrics.source.TextEncoding;

import java.nio.file.Path;
import java.util.List;

/** Uses only the preferred available source; an unsupported source stays with the host. */
final class LyricsProcessor {
    @FunctionalInterface
    interface Sources {
        List<LyricSourceReader.Candidate> read(Path audio, SourcePreference source, TextEncoding encoding);
    }

    @FunctionalInterface
    interface Normalize {
        java.util.Optional<String> apply(String raw, Options options);
    }

    private final Sources sources;
    private final Normalize normalize;
    private final Normalize refresh;

    LyricsProcessor() {
        this(new LyricSourceReader()::readCandidates, new LyricNormalizer());
    }

    private LyricsProcessor(Sources sources, LyricNormalizer normalizer) {
        this(sources, normalizer::normalize, normalizer::normalizeForPlayback);
    }

    LyricsProcessor(Sources sources, Normalize normalize) {
        this(sources, normalize, normalize);
    }

    LyricsProcessor(Sources sources, Normalize normalize, Normalize refresh) {
        this.sources = sources;
        this.normalize = normalize;
        this.refresh = refresh;
    }

    String load(String mediaPath, PluginSettings settings) {
        return load(mediaPath, settings, false);
    }

    String load(String mediaPath, PluginSettings settings, boolean explicitRefresh) {
        if (!settings.enabled() || mediaPath == null || mediaPath.isBlank()) return null;
        try {
            Path path = Path.of(mediaPath);
            if (!path.isAbsolute()) return null;
            List<LyricSourceReader.Candidate> candidates = sources.read(path, settings.source(), settings.encoding());
            if (candidates.isEmpty()) return null;
            return (explicitRefresh ? refresh : normalize)
                    .apply(candidates.getFirst().text(), settings.normalization()).orElse(null);
        } catch (RuntimeException exception) {
            // A failed custom source must never prevent the host from loading its own lyrics.
            System.getLogger(LyricsProcessor.class.getName()).log(System.Logger.Level.WARNING,
                    "双语歌词处理失败，已交回播放器：{0}", exception.getClass().getSimpleName());
            return null;
        }
    }
}
