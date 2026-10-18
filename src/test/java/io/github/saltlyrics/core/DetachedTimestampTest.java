package io.github.saltlyrics.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DetachedTimestampTest {
    private static final String ORIGINAL = "いい事尽くめの夢から覚めた";
    private static final String TRANSLATION = "从满是美好事物的梦中醒来";
    private final LyricNormalizer normalizer = new LyricNormalizer();

    @Test
    void suppliedDetachedTimestampSupportsBothDisplayOrders() {
        for (Options.OutputOrder output : Options.OutputOrder.values()) {
            boolean originalFirst = output == Options.OutputOrder.ORIGINAL_FIRST;
            assertEquals("[00:18.27]\n[00:18.270]" + (originalFirst ? ORIGINAL : TRANSLATION)
                            + "\n[00:18.270]" + (originalFirst ? TRANSLATION : ORIGINAL),
                    normalize("[00:18.27]\n" + ORIGINAL + "\n" + TRANSLATION,
                            new Options(output, Options.InputOrder.AUTO, true)));
        }
    }

    @Test
    void entireSongCanUseDetachedBlocksWithMixedInputOrders() {
        String raw = "[00:18.27]\n" + TRANSLATION + "\n" + ORIGINAL
                + "\n[00:29.43]\nロンリーガールはいつまでも 届かない夢見て"
                + "\nLonely Girl不管是什么时候 都在做着无法企及的梦";
        assertEquals("[00:18.27]\n[00:18.270]" + ORIGINAL + "\n[00:18.270]" + TRANSLATION
                + "\n[00:29.43]\n[00:29.430]ロンリーガールはいつまでも 届かない夢見て"
                + "\n[00:29.430]Lonely Girl不管是什么时候 都在做着无法企及的梦",
                normalize(raw, Options.defaults()));
    }

    @Test
    void monolingualBodyAndInlinePairCanFollowDetachedTimestamp() {
        assertEquals("[00:01]\n[00:01.000]Hello", normalize("[00:01]\nHello", Options.defaults()));
        assertEquals("[00:01]\n[00:01.000]Hello\n[00:01.000]你好",
                normalize("[00:01]\nHello 你好", Options.defaults()));
    }

    @Test
    void repeatedTimestampsAttachAllLanguagesToEveryOccurrence() {
        assertEquals("[00:01][00:02]\n[00:01.000]Hello\n[00:01.000]你好\n[00:01.000]ni hao"
                        + "\n[00:02.000]Hello\n[00:02.000]你好\n[00:02.000]ni hao",
                normalize("[00:01][00:02]\nHello\n你好\nni hao", Options.defaults()));
    }

    @Test
    void metadataOffsetBomAndCrLfArePreserved() {
        assertEquals("\uFEFF[offset:-250]\r\n[00:18.27]\r\n[00:18.270]" + ORIGINAL
                        + "\r\n[00:18.270]" + TRANSLATION + "\r\n",
                normalize("\uFEFF[offset:-250]\r\n[00:18.27]\r\n" + TRANSLATION
                        + "\r\n" + ORIGINAL + "\r\n", Options.defaults()));
        assertEquals("\uFEFF[00:01]\r\n[00:01.000]Hello",
                normalize("\uFEFF[00:01]\r\nHello", Options.defaults()));
    }

    @Test
    void precedingEndAndFollowingTimedLineKeepTheirMeaning() {
        assertEquals("[00:01.000]Hello<00:04>\n[00:02]\n[00:02.000]新的一句"
                        + "\n[00:05]\n[00:05.000]Next",
                normalize("[00:01]Hello<00:04>\n[00:02]\n新的一句\n[00:05]\n[00:05]Next",
                        Options.defaults()));
    }

    @Test
    void blankLinesMetadataAndOtherTimedRowsPreventPromotion() {
        for (String raw : new String[]{
                "[00:18.27]\n\n" + ORIGINAL + "\n" + TRANSLATION,
                "[00:18.27]\n[ti:Title]\n" + ORIGINAL + "\n" + TRANSLATION,
                "[00:18.27]\n[00:20]Hello",
                "[00:18.27]\n[00:20]",
                "[00:18.27]"}) {
            assertTrue(normalizer.normalize(raw, Options.defaults()).isEmpty(), raw);
        }
    }

    @Test
    void malformedPromotedWordTimestampsFallBackToHost() {
        for (String body : new String[]{"你<00:99>好", "你<00:xx>好", "你[00:01.1234567]好"}) {
            assertTrue(normalizer.normalize("[00:01]\n" + body + "\n[00:02]你好\n[00:02]Hello",
                    Options.defaults()).isEmpty(), body);
        }
    }

    @Test
    void equivalentWordTimingAndExplicitEndsSurvivePromotionAndReordering() {
        assertEquals("[00:01]\n[00:01.000]Hello<00:02>\n[00:01.000]你<00:01.5>好[00:02]",
                normalize("[00:01]\n你<00:01.5>好[00:02]\nHello<00:02>", Options.defaults()));
    }

    @Test
    void normalizedDetachedBlocksAreIdempotent() {
        String normalized = normalize("[00:18.27]\n" + ORIGINAL + "\n" + TRANSLATION, Options.defaults());
        assertTrue(normalizer.normalize(normalized, Options.defaults()).isEmpty());
    }

    private String normalize(String raw, Options options) {
        return normalizer.normalize(raw, options).orElseThrow(() -> new AssertionError(raw));
    }
}
