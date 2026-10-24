package io.github.saltlyrics.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static io.github.saltlyrics.fixtures.LyricFixtures.flacWithLyrics;

class LyricSourceReaderTest {
    @TempDir Path directory;
    private final LyricSourceReader reader = new LyricSourceReader();

    @Test
    void readsSameBasenameAndMixedCaseExtensionWithoutChangingFiles() throws IOException {
        Path audio = audioPlaceholder("song.live.raw");
        String lyrics = "[00:01.00]原文\r\n[00:01.00]translation\r";
        Path sidecar = directory.resolve("song.live.LrC");
        Files.writeString(sidecar, lyrics);
        Files.writeString(directory.resolve("another.lrc"), "unrelated");
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("nested/song.live.lrc"), "nested");

        List<LyricSourceReader.Candidate> result = readSidecars(audio, TextEncoding.AUTO);

        assertEquals(List.of("[00:01.00]原文\n[00:01.00]translation\n"), texts(result));
        assertEquals(lyrics, Files.readString(sidecar));
        assertEquals(0, Files.size(audio));
    }

    @Test
    void readsAllSupportedSidecarTypesInDeterministicOrder() throws IOException {
        Path audio = audioPlaceholder("song.raw");
        for (String suffix : List.of("vtt", "srt", "spl", "lrc")) {
            Files.writeString(directory.resolve("song." + suffix), suffix);
        }
        assertEquals(List.of("lrc", "spl", "srt", "vtt"), texts(readSidecars(audio, TextEncoding.AUTO)));
    }

    @Test
    void unicodeBomOverridesManualEncodingAndIsRemoved() throws IOException {
        Path audio = audioPlaceholder("bom.raw");
        String lyrics = "[00:01.00]你好 hello";
        Files.write(directory.resolve("bom.lrc"), concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                lyrics.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(lyrics), texts(readSidecars(audio, TextEncoding.SHIFT_JIS)));

        Files.write(directory.resolve("bom.lrc"), concat(new byte[]{(byte) 0xFF, (byte) 0xFE},
                lyrics.getBytes(StandardCharsets.UTF_16LE)));
        assertEquals(List.of(lyrics), texts(readSidecars(audio, TextEncoding.AUTO)));

        Files.write(directory.resolve("bom.lrc"), concat(new byte[]{(byte) 0xFE, (byte) 0xFF},
                lyrics.getBytes(StandardCharsets.UTF_16BE)));
        assertEquals(List.of(lyrics), texts(readSidecars(audio, TextEncoding.UTF8)));
    }

    @Test
    void autoFallsBackToStrictGb18030() throws IOException {
        Path audio = audioPlaceholder("legacy.raw");
        String lyrics = "[00:01.00]你好世界";
        Files.write(directory.resolve("legacy.lrc"), lyrics.getBytes(Charset.forName("GB18030")));
        assertEquals(List.of(lyrics), texts(readSidecars(audio, TextEncoding.AUTO)));
        assertTrue(readSidecars(audio, TextEncoding.UTF8).isEmpty());
    }

    @Test
    void supportsExplicitShiftJisAndUtf16WithoutBom() throws IOException {
        Path audio = audioPlaceholder("japanese.raw");
        String lyrics = "[00:01.00]かぼそいひがこころのはしにもる";
        Files.write(directory.resolve("japanese.lrc"), lyrics.getBytes(Charset.forName("Shift_JIS")));
        assertEquals(List.of(lyrics), texts(readSidecars(audio, TextEncoding.SHIFT_JIS)));
        for (var encoding : List.of(TextEncoding.UTF16LE, TextEncoding.UTF16BE)) {
            Charset charset = encoding == TextEncoding.UTF16LE ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
            Files.write(directory.resolve("japanese.lrc"), lyrics.getBytes(charset));
            assertEquals(List.of(lyrics), texts(readSidecars(audio, encoding)));
        }
    }

    @Test
    void malformedOversizedAndBlankSourcesDoNotHideNextFormat() throws IOException {
        Path audio = audioPlaceholder("fallback.raw");
        Files.write(directory.resolve("fallback.lrc"), new byte[]{(byte) 0xC3});
        Files.write(directory.resolve("fallback.spl"), new byte[LyricSourceReader.MAX_SIDECAR_BYTES + 1]);
        Files.writeString(directory.resolve("fallback.srt"), " \r\n\t");
        Files.writeString(directory.resolve("fallback.vtt"), "WEBVTT\n\n00:01.000 --> 00:02.000\nhello");

        assertEquals(List.of("WEBVTT\n\n00:01.000 --> 00:02.000\nhello"),
                texts(readSidecars(audio, TextEncoding.UTF8)));
    }

    @Test
    void rejectsReplacementCharactersNulsAndUtf32() throws IOException {
        Path audio = audioPlaceholder("invalid.raw");
        Files.writeString(directory.resolve("invalid.lrc"), "[00:01]bad\uFFFD");
        Files.writeString(directory.resolve("invalid.spl"), "[00:01]bad\0");
        Files.write(directory.resolve("invalid.srt"), new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0, 65, 0, 0, 0});
        assertTrue(readSidecars(audio, TextEncoding.AUTO).isEmpty());
    }

    @Test
    void skipsDirectoriesAndMissingAudioEvenWhenSidecarExists() throws IOException {
        Path audioDirectory = Files.createDirectory(directory.resolve("directory.mp3"));
        Files.writeString(directory.resolve("directory.lrc"), "[00:01]wrong");
        Files.writeString(directory.resolve("missing.lrc"), "[00:01]wrong");
        assertTrue(readSidecars(audioDirectory, TextEncoding.AUTO).isEmpty());
        assertTrue(readSidecars(directory.resolve("missing.mp3"), TextEncoding.AUTO).isEmpty());
        assertTrue(reader.readCandidates(null, null, null).isEmpty());
    }

    @Test
    void sidecarDirectoryIsIgnoredAndDuplicatesAreRemoved() throws IOException {
        Path audio = audioPlaceholder("duplicates.raw");
        Files.createDirectory(directory.resolve("duplicates.lrc"));
        Files.writeString(directory.resolve("duplicates.spl"), "[00:01]same\r\n");
        Files.writeString(directory.resolve("duplicates.srt"), "[00:01]same\n");
        assertEquals(List.of("[00:01]same\n"), texts(readSidecars(audio, TextEncoding.AUTO)));
    }

    @Test
    void followsRegularFileMusicAndSidecarSymlinksWhenAvailable() throws IOException {
        Path actualAudio = audioPlaceholder("actual.raw");
        Path actualLyrics = Files.writeString(directory.resolve("actual-lyrics.txt"), "[00:01]linked lyrics");
        Path linkedAudio = directory.resolve("linked.raw");
        Path linkedLyrics = directory.resolve("linked.lrc");
        try {
            Files.createSymbolicLink(linkedAudio, actualAudio);
            Files.createSymbolicLink(linkedLyrics, actualLyrics);
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            assumeTrue(false, "Symbolic links unavailable: " + failure.getClass().getSimpleName());
        }

        assertEquals(List.of("[00:01]linked lyrics"), texts(readSidecars(linkedAudio, TextEncoding.AUTO)));
        assertEquals("[00:01]linked lyrics", Files.readString(actualLyrics));
    }

    @Test
    void readsRealId3UsltFieldsIndependentlyAndRespectsSourcePreference() throws IOException {
        String embedded = "[00:01.00]hello";
        String translation = "[00:01.00]你好";
        Path audio = directory.resolve("tagged.mp3");
        byte[] fixture = mp3WithUslt(embedded, translation);
        Files.write(audio, fixture);
        Files.writeString(directory.resolve("tagged.lrc"), "[00:01.00]sidecar");

        List<String> embeddedOnly = texts(reader.readCandidates(audio, SourcePreference.EMBEDDED_ONLY, TextEncoding.AUTO));
        assertEquals(2, embeddedOnly.size());
        assertTrue(embeddedOnly.containsAll(List.of(embedded, translation)));
        assertTrue(embeddedOnly.stream().noneMatch(text -> text.contains("hello") && text.contains("你好")));

        List<String> embeddedFirst = texts(reader.readCandidates(audio, SourcePreference.EMBEDDED_FIRST, TextEncoding.AUTO));
        assertEquals(embeddedOnly, embeddedFirst.subList(0, 2));
        assertEquals("[00:01.00]sidecar", embeddedFirst.get(2));
        assertEquals("[00:01.00]sidecar", reader.readCandidates(audio, SourcePreference.SIDECAR_FIRST, TextEncoding.AUTO).getFirst().text());
        assertEquals(List.of("[00:01.00]sidecar"), texts(readSidecars(audio, TextEncoding.AUTO)));
        assertArrayEquals(fixture, Files.readAllBytes(audio));
    }

    @Test
    void readsFlacVorbisLyricsWithoutChangingMetadata() throws IOException {
        String lyrics = "[00:01.00]ねえ\n[00:01.00]呐";
        byte[] fixture = flacWithLyrics(lyrics);
        Path audio = directory.resolve("tagged.flac");
        Files.write(audio, fixture);

        assertEquals(List.of(lyrics), texts(reader.readCandidates(audio, SourcePreference.EMBEDDED_ONLY, TextEncoding.AUTO)));
        assertArrayEquals(fixture, Files.readAllBytes(audio));
    }

    private Path audioPlaceholder(String name) throws IOException {
        return Files.write(directory.resolve(name), new byte[0]);
    }

    private List<LyricSourceReader.Candidate> readSidecars(Path audio, TextEncoding encoding) {
        return reader.readCandidates(audio, SourcePreference.SIDECAR_ONLY, encoding);
    }

    private static List<String> texts(List<LyricSourceReader.Candidate> candidates) {
        return candidates.stream().map(LyricSourceReader.Candidate::text).toList();
    }

    /** Synthetic ID3v2.4 metadata and MPEG frame headers; no copyrighted audio. */
    private static byte[] mp3WithUslt(String... lyrics) throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (int i = 0; i < lyrics.length; i++) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(3); // ID3v2.4 UTF-8
            body.write("eng".getBytes(StandardCharsets.US_ASCII));
            body.write(("value" + i).getBytes(StandardCharsets.UTF_8));
            body.write(0);
            body.write(lyrics[i].getBytes(StandardCharsets.UTF_8));
            frames.write("USLT".getBytes(StandardCharsets.US_ASCII));
            frames.write(syncSafe(body.size()));
            frames.write(new byte[2]);
            frames.write(body.toByteArray());
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(new byte[]{'I', 'D', '3', 4, 0, 0});
        result.write(syncSafe(frames.size()));
        result.write(frames.toByteArray());
        for (int i = 0; i < 10; i++) {
            byte[] frame = new byte[417]; // MPEG 1 Layer III, 128kbps, 44.1kHz
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB;
            frame[2] = (byte) 0x90;
            frame[3] = (byte) 0x64;
            result.write(frame);
        }
        return result.toByteArray();
    }

    private static byte[] syncSafe(int value) {
        return new byte[]{(byte) ((value >>> 21) & 0x7F), (byte) ((value >>> 14) & 0x7F),
                (byte) ((value >>> 7) & 0x7F), (byte) (value & 0x7F)};
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
