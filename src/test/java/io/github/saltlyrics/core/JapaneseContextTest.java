package io.github.saltlyrics.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JapaneseContextTest {
    private final LyricNormalizer normalizer = new LyricNormalizer();

    @Test
    void hanOnlyJapaneseInheritsAgreeingPairsInBothInputAndOutputOrders() {
        for (boolean reversed : new boolean[]{false, true}) {
            String raw = pair("00:01", "君の夢", "你的梦想", reversed)
                    + "\n" + pair("00:02", "世界", "这个世界", reversed)
                    + "\n" + pair("00:03", "未来", "美好的未来", reversed)
                    + "\n" + pair("00:04", "光の中へ", "走进光芒中", reversed);
            for (Options.OutputOrder output : Options.OutputOrder.values()) {
                boolean translatedFirst = output == Options.OutputOrder.TRANSLATION_FIRST;
                Options options = new Options(output, Options.InputOrder.AUTO, true);
                String actual = normalizer.normalize(raw, options).orElse(raw);
                String expected = pair("00:01", "君の夢", "你的梦想", translatedFirst)
                        + "\n" + pair("00:02", "世界", "这个世界", translatedFirst)
                        + "\n" + pair("00:03", "未来", "美好的未来", translatedFirst)
                        + "\n" + pair("00:04", "光の中へ", "走进光芒中", translatedFirst);
                assertEquals(expected, actual.replaceAll("(\\[\\d+:\\d+)\\.000]", "$1]"));
                assertTrue(normalizer.normalize(actual, options).isEmpty());
            }
        }
    }

    @Test
    void contextUsesCompleteWordTimedLinesAndPreservesTheirIndividualMarkers() {
        String raw = pair("00:01", "君[00:01.5]の夢", "你的梦想", true)
                + "\n" + pair("00:02", "世[00:02.4]界", "这[00:02.3]个世界", true)
                + "\n" + pair("00:03", "光<00:03.5>の中へ", "走进光芒中", true);
        assertTrue(normalize(raw).contains("[00:02.000]世[00:02.4]界\n[00:02.000]这[00:02.3]个世界"));
    }

    @Test
    void timestampOnlyEndMarkersAndDetachedBodiesDoNotEraseLanguageContext() {
        String raw = "[00:01]\n你的梦想\n君の夢\n[00:02]\n这个世界\n世界\n"
                + "[00:03]\n走进光芒中\n光の中へ";
        assertTrue(normalize(raw).contains("[00:02]\n[00:02.000]世界\n[00:02.000]这个世界"));
    }

    @Test
    void conflictingJapaneseInputOrdersKeepAmbiguousGroupUntouched() {
        String raw = pair("00:01", "君の夢", "你的梦想", true)
                + "\n" + pair("00:02", "这个世界", "世界", false)
                + "\n" + pair("00:03", "光の中へ", "走进光芒中", false);
        assertTrue(normalize(raw).contains("[00:02.000]这个世界\n[00:02.000]世界"));
    }

    @Test
    void aSingleAnchorAndIsolatedHanOnlyPairsDoNotGuess() {
        for (String raw : new String[]{
                pair("00:02", "世界", "这个世界", true),
                pair("00:01", "君の夢", "你的梦想", false) + "\n" + pair("00:02", "世界", "这个世界", true),
                pair("00:02", "世界", "这个世界", true) + "\n" + pair("00:03", "光の中へ", "走进光芒中", false)}) {
            assertTrue(normalizer.normalize(raw, Options.defaults()).isEmpty(), raw);
        }
    }

    @Test
    void metadataMonolingualAndThirdLanguageGroupsAreContextBarriers() {
        for (String barrier : new String[]{"[ti:Title]\n", "[00:02.5]間奏\n",
                "[00:02.5]かな\n[00:02.5]中文\n[00:02.5]kana\n"}) {
            String raw = pair("00:01", "君の夢", "你的梦想", true)
                    + "\n" + pair("00:02", "世界", "这个世界", true)
                    + "\n" + barrier + pair("00:03", "光の中へ", "走进光芒中", true);
            assertTrue(normalize(raw).contains("[00:02.000]这个世界\n[00:02.000]世界"), barrier);
        }
    }

    @Test
    void koreanAndLatinPairsDoNotSupplyJapaneseContext() {
        for (String foreign : new String[]{"Hello", "안녕"}) {
            String raw = pair("00:01", foreign, "你好", true)
                    + "\n" + pair("00:02", "世界", "这个世界", true)
                    + "\n" + pair("00:03", foreign, "你好", true);
            assertTrue(normalize(raw).contains("[00:02.000]这个世界\n[00:02.000]世界"));
        }
    }

    @Test
    void manualOrderOverridesTheContext() {
        String raw = pair("00:01", "君の夢", "你的梦想", true)
                + "\n" + pair("00:02", "世界", "这个世界", true)
                + "\n" + pair("00:03", "光の中へ", "走进光芒中", true);
        assertTrue(normalizer.normalize(raw, new Options(Options.OutputOrder.ORIGINAL_FIRST,
                Options.InputOrder.ORIGINAL_FIRST, true)).isEmpty());
    }

    private String normalize(String raw) {
        return normalizer.normalize(raw, Options.defaults()).orElseThrow(() -> new AssertionError(raw));
    }

    private static String pair(String time, String original, String translation, boolean reversed) {
        return "[" + time + "]" + (reversed ? translation : original)
                + "\n[" + time + "]" + (reversed ? original : translation);
    }
}
