package io.github.saltlyrics.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MixedTranslationTest {
    private static final String JAPANESE = "ロンリーガールはいつまでも 届かない夢見て";
    private static final String TRANSLATION = "Lonely Girl不管是什么时候 都在做着无法企及的梦";
    private final LyricNormalizer normalizer = new LyricNormalizer();

    @Test
    void suppliedEnglishPrefixedTranslationWorksInBothInputAndOutputOrders() {
        assertPair(JAPANESE, TRANSLATION);
    }

    @Test
    void borrowedWordsAndInternalSeparatorsDoNotBreakAnExistingJapanesePair() {
        for (String translation : new String[]{
                "Lonely Girl 不管是什么时候 都在做着无法企及的梦",
                "Lonely Girl今天还在追梦",
                "Lonely Girl  今天还在追梦",
                "今天 Lonely Girl 还在追梦",
                "今天还在追梦 Lonely Girl",
                "Supercalifragilisticexpialidocious 今天还在追梦"}) {
            assertPair(JAPANESE, translation);
        }
    }

    @Test
    void koreanOriginalAlsoSupportsEnglishWordsInsideChineseTranslation() {
        assertPair("Lonely Girl 같은 꿈을 꿔", TRANSLATION);
    }

    @Test
    void untimedMixedTranslationRetainsTheCompleteLine() {
        String raw = "[00:29.43]" + JAPANESE + "\n" + TRANSLATION;
        assertEquals("[00:29.430]" + TRANSLATION + "\n[00:29.430]" + JAPANESE,
                normalizer.normalize(raw, options(Options.OutputOrder.TRANSLATION_FIRST)).orElseThrow());
    }

    @Test
    void relaxedPairDetectionDoesNotSplitStandaloneCodeSwitchedLyrics() {
        assertTrue(normalizer.normalize("[00:29.43]Lonely Girl今天还在追梦", Options.defaults()).isEmpty());
    }

    @Test
    void existingInlinePlusRomanizationStillProducesThreeRows() {
        assertEquals("[00:01.000]Hi\n[00:01.000]你好世界\n[00:01.000]ni hao shi jie",
                normalizer.normalize("[00:01]Hi 你好世界\nni hao shi jie", Options.defaults()).orElseThrow());
    }

    @Test
    void untimedSublinesAreNotNewlySplit() {
        assertEquals("[00:01.000]こんばんは\n[00:01.000]Hello 你好",
                normalizer.normalize("[00:01]こんばんは\nHello 你好", Options.defaults()).orElseThrow());
    }

    @Test
    void multipleSublinesRetainExistingInlineExpansion() {
        assertEquals("[00:01.000]Hello\n[00:01.000]你好\n[00:01.000]ni hao\n[00:01.000]안녕",
                normalizer.normalize("[00:01]Hello 你好\nni hao\n안녕", Options.defaults()).orElseThrow());
    }

    @Test
    void twoAmbiguousMixedChineseLinesStillKeepTheirInputOrder() {
        assertTrue(normalizer.normalize("[00:01]Lonely Girl今天还在追梦\n[00:01]Happy Boy明天也要追梦",
                options(Options.OutputOrder.TRANSLATION_FIRST)).isEmpty());
    }

    private void assertPair(String original, String translation) {
        for (boolean reversed : new boolean[]{false, true}) {
            String raw = "[00:29.43]" + (reversed ? translation : original)
                    + "\n[00:29.43]" + (reversed ? original : translation);
            for (Options.OutputOrder order : Options.OutputOrder.values()) {
                String actual = normalizer.normalize(raw, options(order)).orElse(raw);
                boolean originalFirst = order == Options.OutputOrder.ORIGINAL_FIRST;
                String expected = (originalFirst ? original : translation) + "\n" + (originalFirst ? translation : original);
                assertEquals(expected, actual.replaceAll("\\[\\d+:\\d+(?:\\.\\d+)?]", ""), raw + " / " + order);
                assertEquals(2, actual.lines().count(), "An existing bilingual pair must retain exactly two rows");
            }
        }
    }

    private static Options options(Options.OutputOrder output) {
        return new Options(output, Options.InputOrder.AUTO, true);
    }
}
