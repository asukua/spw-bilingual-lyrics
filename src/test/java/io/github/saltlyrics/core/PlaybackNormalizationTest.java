package io.github.saltlyrics.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackNormalizationTest {
    private final LyricNormalizer normalizer = new LyricNormalizer();

    @Test
    void alreadyMatchingPairsReturnTheirExactSourceForBothDisplayOrders() {
        for (Options.OutputOrder order : Options.OutputOrder.values()) {
            boolean originalFirst = order == Options.OutputOrder.ORIGINAL_FIRST;
            String raw = "\uFEFF[offset:-250]\r\n[00:01.00]" + (originalFirst ? "君の夢" : "你的梦想")
                    + "\r\n[00:01.00]" + (originalFirst ? "你的梦想" : "君の夢") + "\r\n";
            Options options = new Options(order, Options.InputOrder.AUTO, true);
            assertTrue(normalizer.normalize(raw, options).isEmpty(), "The change-only API keeps its original contract");
            assertEquals(raw, normalizer.normalizeForPlayback(raw, options).orElseThrow());
        }
    }

    @Test
    void playbackAndChangeOnlyApisProduceTheSameReordering() {
        String raw = "[00:01]你好\n[00:01]Hello";
        assertEquals(normalizer.normalize(raw, Options.defaults()),
                normalizer.normalizeForPlayback(raw, Options.defaults()));
    }

    @Test
    void unsafeUnsupportedAmbiguousAndMonolingualInputStillUsesHostFallback() {
        for (String raw : new String[]{null, "", "[00:01]Hello", "[00:01]世界\n[00:01]这个世界",
                "[00:99]你好\n[00:99]Hello", "[00:01]你好<00:xx>\n[00:01]Hello",
                "[00:01]你[00:02]\n[00:01]Hello<00:03>world",
                "1\n00:00:01,000 --> 00:00:02,000\nHello", "[0,1000](0,500,0)Hello"}) {
            assertTrue(normalizer.normalizeForPlayback(raw, Options.defaults()).isEmpty(), String.valueOf(raw));
        }
    }

    @Test
    void explicitInputOrderAlsoSuppliesAnAlreadyMatchingHanOnlyPair() {
        String raw = "[00:01]世界\n[00:01]这个世界";
        assertEquals(raw, normalizer.normalizeForPlayback(raw, new Options(Options.OutputOrder.ORIGINAL_FIRST,
                Options.InputOrder.ORIGINAL_FIRST, true)).orElseThrow());
    }
}
