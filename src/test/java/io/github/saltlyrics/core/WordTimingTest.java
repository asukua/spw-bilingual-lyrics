package io.github.saltlyrics.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WordTimingTest {
    private static final String ORIGINAL = "か[00:30.35]細[00:30.62]い[00:30.88]火[00:31.20]が";
    private static final String TRANSLATION = "纤[00:30.38]细[00:30.66]的[00:30.94]火[00:31.22]焰";
    private final LyricNormalizer normalizer = new LyricNormalizer();

    @Test
    void suppliedIndependentWordTimingsWorkInBothInputAndOutputOrders() {
        assertPair(ORIGINAL, TRANSLATION);
    }

    @Test
    void eitherOriginalOrTranslationCanHaveWordTimingWithoutInventingTimingForTheOther() {
        assertPair(ORIGINAL, "纤细的火焰");
        assertPair("か細い火が", TRANSLATION);
    }

    @Test
    void wordTimingDoesNotChangeWholeLineLanguageDetection() {
        assertPair("火[00:30.35]の[00:30.62]中[00:30.88]へ", TRANSLATION);
        assertPair("<00:30.20>火<00:30.35>の<00:30.62>中<00:30.88>へ", TRANSLATION);
        assertPair("火[00:30.35]ノ[00:30.62]中[00:30.88]ヘ", TRANSLATION);
        assertPair("火[00:30.35]ﾉ[00:30.62]中[00:30.88]ﾍ", TRANSLATION);
    }

    @Test
    void oneExplicitEndIsCarriedToAnUntimedMainWithoutDroppingWordBoundaries() {
        String endedOriginal = ORIGINAL + "[00:31.50]";
        assertEquals("[00:30.100]纤细的火焰[00:31.500]\n[00:30.100]" + endedOriginal,
                normalize("[00:30.10]" + endedOriginal + "\n[00:30.10]纤细的火焰",
                        Options.OutputOrder.TRANSLATION_FIRST));
        String endedTranslation = TRANSLATION + "<00:31.500000>";
        assertEquals("[00:30.100]か細い火が[00:31.500]\n[00:30.100]" + endedTranslation,
                normalize("[00:30.10]" + endedTranslation + "\n[00:30.10]か細い火が",
                        Options.OutputOrder.ORIGINAL_FIRST));
    }

    @Test
    void endCanAlsoBeCarriedToWordTimedMainWithAnOpenLastWord() {
        assertEquals("[00:30.100]" + ORIGINAL + "[00:31.500]\n[00:30.100]纤细的火焰[00:31.50]",
                normalize("[00:30.10]纤细的火焰[00:31.50]\n[00:30.10]" + ORIGINAL,
                        Options.OutputOrder.ORIGINAL_FIRST));
    }

    @Test
    void distinctExplicitEndsStayAttachedToTheirOwnWordTimedTexts() {
        assertPair(ORIGINAL + "[00:31.50]", TRANSLATION + "<00:31.75>");
    }

    @Test
    void untimedAndDetachedTranslationsSupportWordTiming() {
        for (String header : new String[]{"[00:30.10]", "[00:30.10]\n"}) {
            String raw = header + ORIGINAL + "\n纤细的火焰";
            String prefix = header.endsWith("\n") ? "[00:30.10]\n" : "";
            assertEquals(prefix + "[00:30.100]纤细的火焰\n[00:30.100]" + ORIGINAL,
                    normalize(raw, Options.OutputOrder.TRANSLATION_FIRST));
            assertEquals(prefix + "[00:30.100]か細い火が\n[00:30.100]" + TRANSLATION,
                    normalize(header + "か細い火が\n" + TRANSLATION,
                            Options.OutputOrder.ORIGINAL_FIRST));
        }
    }

    @Test
    void borrowedEnglishWordsAndSeparatorsInsideTranslationRemainInThePair() {
        assertPair(ORIGINAL, "Lonely Girl[00:30.38]纤细  的[00:31.22]火焰");
    }

    @Test
    void offsetMetadataBomAndCrLfDoNotChangeIndependentWordTimes() {
        String raw = "\uFEFF[offset:-250]\r\n[00:30.10]" + TRANSLATION
                + "\r\n[00:30.10]" + ORIGINAL + "\r\n";
        assertEquals("\uFEFF[offset:-250]\r\n[00:30.100]" + ORIGINAL
                        + "\r\n[00:30.100]" + TRANSLATION + "\r\n",
                normalize(raw, Options.OutputOrder.ORIGINAL_FIRST));
    }

    @Test
    void unsafeEndTransferLeavesOnlyThatPairAloneAndStillProcessesTheRest() {
        String raw = "[00:30.10]纤细的火焰[00:31]\n[00:30.10]" + ORIGINAL
                + "\n[00:40]再见\n[00:40]さようなら";
        assertEquals("[00:30.100]纤细的火焰[00:31]\n[00:30.100]" + ORIGINAL
                        + "\n[00:40.000]さようなら\n[00:40.000]再见",
                normalize(raw, Options.OutputOrder.ORIGINAL_FIRST));
    }

    @Test
    void explicitManualOrderKeepsWholeRowsWithInternalSeparators() {
        Options options = new Options(Options.OutputOrder.TRANSLATION_FIRST,
                Options.InputOrder.ORIGINAL_FIRST, true);
        assertEquals("[00:30.100]微弱  的火焰\n[00:30.100]世界  未来",
                normalizer.normalize("[00:30.10]世界  未来\n[00:30.10]微弱  的火焰", options).orElseThrow());
    }

    @Test
    void switchingDisplayOrderOnNormalizedMixedTimingKeepsAllWordMarkers() {
        String raw = "[00:30.10]" + ORIGINAL + "[00:31.50]\n[00:30.10]纤细的火焰";
        String translatedFirst = normalize(raw, Options.OutputOrder.TRANSLATION_FIRST);
        assertEquals("[00:30.100]" + ORIGINAL + "[00:31.50]\n[00:30.100]纤细的火焰[00:31.500]",
                normalize(translatedFirst, Options.OutputOrder.ORIGINAL_FIRST));
    }

    @Test
    void malformedWordTimesOnUntimedSublinesAlsoFallBackToHost() {
        for (String malformed : new String[]{"纤<00:99>细", "纤<00:xx>细", "纤<00:31]细",
                "纤[00:30.1234567]细"}) {
            assertTrue(normalizer.normalize("[00:30.10]" + ORIGINAL + "\n" + malformed,
                    options(Options.OutputOrder.TRANSLATION_FIRST)).isEmpty(), malformed);
            assertTrue(normalizer.normalize("[00:30.10]\n" + ORIGINAL + "\n" + malformed,
                    options(Options.OutputOrder.TRANSLATION_FIRST)).isEmpty(), malformed);
        }
    }

    private void assertPair(String original, String translation) {
        for (boolean reversed : new boolean[]{false, true}) {
            String raw = "[00:30.10]" + (reversed ? translation : original)
                    + "\n[00:30.10]" + (reversed ? original : translation);
            for (Options.OutputOrder output : Options.OutputOrder.values()) {
                Options options = options(output);
                String actual = normalizer.normalize(raw, options).orElse(raw);
                List<String> expected = output == Options.OutputOrder.ORIGINAL_FIRST
                        ? List.of(original, translation) : List.of(translation, original);
                assertEquals(expected, actual.lines().map(line -> line.replaceFirst("^\\[[^]]+]", "")).toList(),
                        raw + " / " + output);
                assertTrue(normalizer.normalize(actual, options).isEmpty(), "Repeated normalization must preserve word timing");
            }
        }
    }

    private String normalize(String raw, Options.OutputOrder output) {
        return normalizer.normalize(raw, options(output)).orElseThrow(() -> new AssertionError(raw));
    }

    private static Options options(Options.OutputOrder output) {
        return new Options(output, Options.InputOrder.AUTO, true);
    }
}
