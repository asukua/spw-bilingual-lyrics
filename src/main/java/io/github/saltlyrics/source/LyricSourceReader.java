package io.github.saltlyrics.source;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads local lyric sources without modifying audio files or their tags.
 *
 * <p>Each embedded LYRICS value remains an independent candidate in tag order.
 * Separate values can be translations, alternate edits, or different songs;
 * joining them without that context can corrupt otherwise valid lyrics. The
 * caller chooses whether to try more than the first candidate.</p>
 *
 * <p>Sidecars use the audio file's exact basename and one of .lrc, .spl, .srt,
 * .vtt, in that order. All ASCII case spellings of the extension are checked
 * directly, so case-insensitive suffixes work without scanning large folders.
 * At most one spelling is selected per suffix, preferring lowercase.</p>
 *
 * <p>A Unicode BOM takes precedence over the requested sidecar encoding. AUTO
 * otherwise tries strict UTF-8, then GB18030. Legacy encodings cannot be
 * reliably inferred: select SHIFT_JIS explicitly for Japanese legacy files.</p>
 */
public final class LyricSourceReader {
    public static final int MAX_SIDECAR_BYTES = 2 * 1024 * 1024;
    private static final int MAX_EMBEDDED_VALUES = 16;
    private static final int MAX_REPORTED_FAILURES = 8;
    private static final Logger LOG = Logger.getLogger(LyricSourceReader.class.getName());
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");
    private static final List<String> SIDECAR_SUFFIXES = List.of("lrc", "spl", "srt", "vtt");
    private static final Set<String> AUDIO_SUFFIXES = Set.of(
            "mp3", "flac", "ogg", "oga", "m4a", "m4p", "mp4", "wma",
            "wav", "aif", "aiff", "aifc", "dsf", "dff");

    private final AtomicInteger reportedFailures = new AtomicInteger();

    public record Candidate(String text, String description) { }

    /**
     * Returns immutable, nonblank, distinct candidates in source preference
     * order. A failed source is skipped; no checked exception escapes.
     * Missing files, directories and non-local providers yield none. Local
     * regular-file symlinks are followed, as they are in ordinary music libraries.
     */
    public List<Candidate> readCandidates(Path audioPath, SourcePreference preference, TextEncoding encoding) {
        if (!isLocalRegularFile(audioPath)) {
            return List.of();
        }
        SourcePreference effectivePreference = preference == null ? SourcePreference.EMBEDDED_FIRST : preference;
        TextEncoding effectiveEncoding = encoding == null ? TextEncoding.AUTO : encoding;
        List<Candidate> candidates = new ArrayList<>();
        switch (effectivePreference) {
            case EMBEDDED_FIRST -> {
                readEmbedded(audioPath, candidates);
                readSidecars(audioPath, effectiveEncoding, candidates);
            }
            case SIDECAR_FIRST -> {
                readSidecars(audioPath, effectiveEncoding, candidates);
                readEmbedded(audioPath, candidates);
            }
            case EMBEDDED_ONLY -> readEmbedded(audioPath, candidates);
            case SIDECAR_ONLY -> readSidecars(audioPath, effectiveEncoding, candidates);
        }
        Set<String> seen = new HashSet<>();
        return candidates.stream().filter(candidate -> seen.add(candidate.text())).toList();
    }

    private void readEmbedded(Path audioPath, List<Candidate> output) {
        if (!AUDIO_SUFFIXES.contains(extension(audioPath))) {
            return;
        }
        try {
            // AudioFileIO.read uses read-only handles, including MP3File's readOnly=true path.
            // Do not call getTagOrCreate*, commit, write, delete, or any tag setter here.
            Tag tag = AudioFileIO.read(audioPath.toFile()).getTag();
            if (tag == null) {
                return;
            }
            List<String> values = tag.getAll(FieldKey.LYRICS);
            if (values == null) {
                return;
            }
            for (int i = 0; i < Math.min(values.size(), MAX_EMBEDDED_VALUES); i++) {
                addCandidate(output, values.get(i), "embedded LYRICS " + (i + 1));
            }
        } catch (Exception failure) {
            reportFailure("embedded metadata", failure);
        }
    }

    private void readSidecars(Path audioPath, TextEncoding encoding, List<Candidate> output) {
        Path fileName = audioPath.getFileName();
        Path parent = audioPath.toAbsolutePath().getParent();
        if (fileName == null || parent == null) {
            return;
        }
        String name = fileName.toString();
        int separator = name.lastIndexOf('.');
        String basename = separator > 0 ? name.substring(0, separator) : name;
        for (String suffix : SIDECAR_SUFFIXES) {
            for (String spelling : caseSpellings(suffix)) {
                Path sidecar = parent.resolve(basename + "." + spelling);
                if (!isLocalRegularFile(sidecar)) {
                    continue;
                }
                try {
                    byte[] bytes;
                    // The bounded read also handles a file that grows after Files.size().
                    if (Files.size(sidecar) > MAX_SIDECAR_BYTES) {
                        throw new IOException("Lyric file exceeds the size limit");
                    }
                    try (InputStream input = Files.newInputStream(sidecar)) {
                        bytes = input.readNBytes(MAX_SIDECAR_BYTES + 1);
                    }
                    if (bytes.length > MAX_SIDECAR_BYTES) {
                        throw new IOException("Lyric file exceeds the size limit");
                    }
                    addCandidate(output, decode(bytes, encoding), "sidecar ." + suffix);
                } catch (IOException | RuntimeException failure) {
                    reportFailure("sidecar ." + suffix, failure);
                }
                // A casing variant names the same logical sidecar; do not read it repeatedly
                // on Windows, where every successful variant can point at the same file.
                break;
            }
        }
    }

    private static void addCandidate(List<Candidate> output, String text, String description) {
        if (text == null || text.length() > MAX_SIDECAR_BYTES) {
            return;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.isBlank() && normalized.indexOf('\uFFFD') < 0 && normalized.indexOf('\0') < 0) {
            output.add(new Candidate(normalized, description));
        }
    }

    private static String decode(byte[] bytes, TextEncoding encoding) throws CharacterCodingException {
        if (bytes.length >= 4 && ((bytes[0] == 0 && bytes[1] == 0 && bytes[2] == (byte) 0xFE && bytes[3] == (byte) 0xFF)
                || (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE && bytes[2] == 0 && bytes[3] == 0))) {
            throw new CharacterCodingException(); // UTF-32 is not UTF-16 with an unusual first character.
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return decodeStrict(bytes, 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return decodeStrict(bytes, 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            return decodeStrict(bytes, 2, StandardCharsets.UTF_16BE);
        }
        if (encoding == TextEncoding.AUTO) {
            try {
                return decodeStrict(bytes, 0, StandardCharsets.UTF_8);
            } catch (CharacterCodingException ignored) {
                return decodeStrict(bytes, 0, GB18030);
            }
        }
        Charset charset = switch (encoding) {
            case UTF8 -> StandardCharsets.UTF_8;
            case GB18030 -> GB18030;
            case SHIFT_JIS -> SHIFT_JIS;
            case UTF16LE -> StandardCharsets.UTF_16LE;
            case UTF16BE -> StandardCharsets.UTF_16BE;
            case AUTO -> throw new AssertionError("AUTO handled above");
        };
        return decodeStrict(bytes, 0, charset);
    }

    private static String decodeStrict(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }

    private static List<String> caseSpellings(String suffix) {
        List<String> spellings = new ArrayList<>(1 << suffix.length());
        for (int mask = 0; mask < (1 << suffix.length()); mask++) {
            StringBuilder spelling = new StringBuilder(suffix.length());
            for (int i = 0; i < suffix.length(); i++) {
                char letter = suffix.charAt(i);
                spelling.append((mask & (1 << i)) == 0 ? letter : Character.toUpperCase(letter));
            }
            spellings.add(spelling.toString());
        }
        return spellings;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isLocalRegularFile(Path path) {
        if (path == null || !"file".equalsIgnoreCase(path.getFileSystem().provider().getScheme())) {
            return false;
        }
        // V1 supports local filesystem music libraries. Reject explicit UNC paths
        // before probing the filesystem, so no network share is contacted.
        String raw = path.toString();
        if (raw.startsWith("\\\\") || raw.startsWith("//")) {
            return false;
        }
        try {
            return Files.isRegularFile(path) && Files.isReadable(path);
        } catch (SecurityException failure) {
            return false;
        }
    }

    private void reportFailure(String source, Exception failure) {
        int failures = reportedFailures.getAndUpdate(count -> Math.min(count + 1, MAX_REPORTED_FAILURES));
        if (failures < MAX_REPORTED_FAILURES) {
            // Avoid printing music names, full paths, lyric contents, or library exception messages.
            LOG.log(Level.WARNING, "Skipped {0} lyric source ({1}){2}", new Object[]{
                    source, failure.getClass().getSimpleName(),
                    failures + 1 == MAX_REPORTED_FAILURES ? "; further source warnings suppressed" : ""});
        }
    }
}
