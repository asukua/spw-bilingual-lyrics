package io.github.saltlyrics.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.saltlyrics.core.Options.InputOrder.AUTO;
import static io.github.saltlyrics.core.Options.InputOrder.ORIGINAL_FIRST;
import static io.github.saltlyrics.core.Options.InputOrder.TRANSLATION_FIRST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricNormalizerTest {
    private final LyricNormalizer normalizer = new LyricNormalizer();

    @Test
    void nonadjacentRomanizationKeepsItsTimestampWhenGroupsMove() {
        String raw = "[00:01]微弱的火苗\n[00:02]下一句\n[00:01]かぼそいひ\nkabosoi hi";
        assertEquals("[00:01.000]微弱的火苗\n[00:01.000]かぼそいひ\n"
                + "[00:01.000]kabosoi hi\n[00:02.000]下一句", normalize(raw));
    }

    @Test
    void reorderingRetainsKnownEndOrUsesSelectedMainRowsOwnEnd() {
        assertEquals("[00:01.000]Hello[00:02.000]\n[00:01.000]你好[00:02]\n[00:10.000]Next",
                normalize("[00:01]你好[00:02]\n[00:01]Hello\n[00:10]Next"));
        assertEquals("[00:01.000]Hello[00:03]\n[00:01.000]你好[00:02]",
                normalize("[00:01]你好[00:02]\n[00:01]Hello[00:03]"));
    }

    @Test
    void inlineWordTimingIsRetainedInsteadOfChangingCueDuration() {
        assertTrue(normalizer.normalize("[00:01]Hello 你好[00:02]", Options.defaults()).isEmpty());
        assertTrue(normalizer.normalize("[00:01]Hello<00:02> 你好", Options.defaults()).isEmpty());
    }

    @Test
    void repeatedTimestampsCannotExpandOutputWithoutBound() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 550; i++) {
            raw.append(String.format(java.util.Locale.ROOT, "[%02d:%02d]", i / 60, i % 60));
        }
        raw.append("a".repeat(4_000)).append(' ').append("你".repeat(4_000));
        assertTrue(normalizer.normalize(raw.toString(), Options.defaults()).isEmpty());
    }

    @Test
    void suppliedChineseBeforeJapaneseExampleMovesOriginalAboveAndKeepsBackslash() {
        String raw = "[00:30.96]看这簇微弱火苗悄然在心底边缘生出\\\n"
                + "[00:30.96]かぼそいひがこころのはしにもる";
        assertEquals("[00:30.960]かぼそいひがこころのはしにもる\n"
                + "[00:30.960]看这簇微弱火苗悄然在心底边缘生出\\", normalize(raw));
    }

    @Test
    void suppliedJapaneseBeforeChineseExampleCanPutTranslationAbove() {
        String raw = "[00:03.95]ねえあたし知ってるよ　きみがひとり“XX”してるの知ってるよ\\\n"
                + "[00:03.95]呐 我知道的哦 我明白 你在独自一人时“XX”的模样 我都知晓";
        assertEquals("[00:03.950]呐 我知道的哦 我明白 你在独自一人时“XX”的模样 我都知晓\n"
                + "[00:03.950]ねえあたし知ってるよ　きみがひとり“XX”してるの知ってるよ\\",
                normalize(raw, new Options(Options.OutputOrder.TRANSLATION_FIRST, AUTO, true)));
    }

    @Test
    void suppliedInlineEnglishAndChineseExampleSplitsAtThinSpace() {
        String raw = "[00:02.81]You think I was born to ruin your life 在你看来 注定毁掉你一生的 是我";
        assertEquals("[00:02.810]You think I was born to ruin your life\n"
                + "[00:02.810]在你看来 注定毁掉你一生的 是我", normalize(raw));
    }

    @Test
    void inlinePairHonorsTranslationFirstOutput() {
        String raw = "[00:02.81]You think I was born to ruin your life 在你看来 注定毁掉你一生的 是我";
        assertEquals("[00:02.810]在你看来 注定毁掉你一生的 是我\n"
                + "[00:02.810]You think I was born to ruin your life",
                normalize(raw, new Options(Options.OutputOrder.TRANSLATION_FIRST, AUTO, true)));
    }

    @Test
    void normalizesMixedSourceOrdersSeparately() {
        String raw = "[00:01]你好\n[00:01]Hello\n[00:02]Goodbye\n[00:02]再见";
        assertEquals("[00:01.000]Hello\n[00:01.000]你好\n[00:02.000]Goodbye\n[00:02.000]再见", normalize(raw));
    }

    @Test
    void alreadyCorrectOrderReturnsEmptyForHostFallback() {
        assertTrue(normalizer.normalize("[00:01.000]Hello\n[00:01.000]你好", Options.defaults()).isEmpty());
    }

    @Test
    void ambiguousHanOnlyPairKeepsOrderAndReturnsEmpty() {
        assertTrue(normalizer.normalize("[00:01]静夜\n[00:01]寂静之夜", Options.defaults()).isEmpty());
        assertTrue(normalizer.normalize("[00:01]世界\n[00:01]世界啊", Options.defaults()).isEmpty());
    }

    @Test
    void manualInputOrderCanReorderAmbiguousHanOnlyPair() {
        assertEquals("[00:01.000]世界\n[00:01.000]世界啊",
                normalize("[00:01]世界啊\n[00:01]世界",
                        new Options(Options.OutputOrder.ORIGINAL_FIRST, TRANSLATION_FIRST, false)));
    }

    @Test
    void manualOriginalFirstCanPutAmbiguousTranslationAbove() {
        assertEquals("[00:01.000]世界啊\n[00:01.000]世界",
                normalize("[00:01]世界\n[00:01]世界啊",
                        new Options(Options.OutputOrder.TRANSLATION_FIRST, ORIGINAL_FIRST, false)));
    }

    @Test
    void singleCodeSwitchedSentenceIsNotSplitAtScriptBoundary() {
        assertTrue(normalizer.normalize("[00:01]I love 你", Options.defaults()).isEmpty());
        assertTrue(normalizer.normalize("[00:01]今夜の世界は美しい", Options.defaults()).isEmpty());
        assertTrue(normalizer.normalize("[00:01]I love 世界 and you", Options.defaults()).isEmpty());
    }

    @Test
    void ordinarySpaceBetweenLongPhrasesCanBeSplitConservatively() {
        assertEquals("[00:01.000]You think I was born to ruin your life\n"
                        + "[00:01.000]在你看来注定毁掉你一生的是我",
                normalize("[00:01]You think I was born to ruin your life 在你看来注定毁掉你一生的是我"));
    }

    @Test
    void supportsExplicitTabDoubleSpaceAndSpacedSlashOrPipeSeparators() {
        for (String delimiter : new String[]{"\t", "  ", " / ", " | "}) {
            assertEquals("[00:01.000]Hello there\n[00:01.000]你好啊",
                    normalize("[00:01]Hello there" + delimiter + "你好啊"), delimiter);
        }
    }

    @Test
    void inlineSplittingCanBeDisabled() {
        assertTrue(normalizer.normalize("[00:01]Hello 你好",
                new Options(Options.OutputOrder.ORIGINAL_FIRST, AUTO, false)).isEmpty());
    }

    @Test
    void repeatedLeadingStampsExpandAndEquivalentFractionsGroupNumerically() {
        assertEquals("[00:01.000]Hello\n[00:01.000]你好\n[00:02.000]Hello\n[00:02.000]你好",
                normalize("[00:01.0][00:02.00]你好\n[00:01:000][0:02.000000]Hello"));
    }

    @Test
    void supportsOneToSixFractionDigitsWithoutRounding() {
        for (String fraction : new String[]{"1", "12", "123", "1234", "12345", "123456"}) {
            String padded = (fraction + "000").substring(0, Math.max(3, fraction.length()));
            assertEquals("[01:02." + padded + "]Hello\n[01:02." + padded + "]你好",
                    normalize("[01:02:" + fraction + "]你好\n[01:02." + fraction + "]Hello"));
        }
    }

    @Test
    void preservesMetadataOffsetAndTrailingNewlineWithoutApplyingOffsetAgain() {
        String raw = "[ti:歌曲]\n[ar:Artist]\n[offset:-250]\n[00:01]你好\n[00:01]Hello\n";
        assertEquals("[ti:歌曲]\n[ar:Artist]\n[offset:-250]\n[00:01.000]Hello\n[00:01.000]你好\n", normalize(raw));
    }

    @Test
    void metadataIsNeverTreatedAsAnUntimedTranslation() {
        String raw = "[00:01]Hello\n[ti:中文标题]\n[00:02]你好\n[00:02]Hello";
        assertEquals("[00:01.000]Hello\n[ti:中文标题]\n[00:02.000]Hello\n[00:02.000]你好", normalize(raw));
    }

    @Test
    void preservesTimestampOnlyEndMarkersEvenWhenNextCueHasSameTime() {
        String raw = "[00:01]你好\n[00:01]Hello\n[00:02]\n[00:02]新世界\n[00:02]New world";
        assertEquals("[00:01.000]Hello\n[00:01.000]你好\n[00:02]\n"
                + "[00:02.000]New world\n[00:02.000]新世界", normalize(raw));
    }

    @Test
    void endMarkerSeparatesGroupsEvenForIdenticalStartTimes() {
        String raw = "[00:01]你好\n[00:01]Hello\n[00:01]\n[00:01]再见\n[00:01]Goodbye";
        assertEquals("[00:01.000]Hello\n[00:01.000]你好\n[00:01]\n"
                + "[00:01.000]Goodbye\n[00:01.000]再见", normalize(raw));
    }

    @Test
    void preservesSquareAndAngleWordTimestampsAndLineEndingMarker() {
        String raw = "[00:01]你[00:01.500]好[00:02]\n[00:01]<00:01>Hello<00:02>";
        assertEquals("[00:01.000]<00:01>Hello<00:02>\n[00:01.000]你[00:01.500]好[00:02]", normalize(raw));
    }

    @Test
    void preservesThreeOrMoreSimultaneousLinesAndTheirSourceOrder() {
        String raw = "[00:01]中文翻译\n[00:01]かぼそい火\n[00:01]kabosoi hi\n"
                + "[00:02]你好\n[00:02]Hello";
        assertEquals("[00:01.000]中文翻译\n[00:01.000]かぼそい火\n[00:01.000]kabosoi hi\n"
                + "[00:02.000]Hello\n[00:02.000]你好", normalize(raw));
    }

    @Test
    void recognizesImmediatelyFollowingUntimedTranslation() {
        assertEquals("[00:01.000]Hello\n[00:01.000]你好", normalize("[00:01]Hello\n你好"));
        assertEquals("[00:01.000]Hello\n[00:01.000]你好", normalize("[00:01]你好\nHello"));
    }

    @Test
    void untimedTranslationAfterRepeatedTimestampBelongsToEachOccurrence() {
        assertEquals("[00:01.000]Hello\n[00:01.000]你好\n[00:02.000]Hello\n[00:02.000]你好",
                normalize("[00:01][00:02]Hello\n你好"));
    }

    @Test
    void blankLinePreventsUntimedTranslationAttachment() {
        String raw = "[00:01]Hello\n\n你好\n[00:02]再见\n[00:02]Goodbye";
        assertEquals("[00:01.000]Hello\n\n你好\n[00:02.000]Goodbye\n[00:02.000]再见", normalize(raw));
    }

    @Test
    void multipleUntimedSublinesStayTogetherAndKeepSourceOrder() {
        String raw = "[00:01]かぼそい火\n微弱的火苗\nkabosoi hi\n"
                + "[00:02]你好\n[00:02]Hello";
        assertEquals("[00:01.000]かぼそい火\n[00:01.000]微弱的火苗\n[00:01.000]kabosoi hi\n"
                + "[00:02.000]Hello\n[00:02.000]你好", normalize(raw));
    }

    @Test
    void inlinePairAndFollowingThirdSublineBelongToEveryRepeatedTimestamp() {
        assertEquals("[00:01.000]Hello\n[00:01.000]你好\n[00:01.000]ni hao\n"
                        + "[00:02.000]Hello\n[00:02.000]你好\n[00:02.000]ni hao",
                normalize("[00:01][00:02]Hello 你好\nni hao"));
        assertEquals("[00:01.000]Hello\n[00:01.000]你好\n[00:01.000]ni hao",
                normalize("[00:01]Hello 你好\nni hao"));
    }

    @Test
    void unusuallyLongLinesFallBackBeforeInlineBoundaryScanning() {
        String raw = "[00:01]" + "long words ".repeat(2_000) + " 你好世界";
        assertTrue(normalizer.normalize(raw, Options.defaults()).isEmpty());
    }

    @Test
    void recognizesKoreanAndCyrillicAgainstChinese() {
        assertEquals("[00:01.000]안녕하세요\n[00:01.000]你好", normalize("[00:01]你好\n[00:01]안녕하세요"));
        assertEquals("[00:01.000]Привет, мир\n[00:01.000]你好世界", normalize("[00:01]你好世界\n[00:01]Привет, мир"));
    }

    @Test
    void preservesCrLfBomAndNonBmpLyrics() {
        assertEquals("\uFEFF[00:01.000]Hello 🌟\r\n[00:01.000]你好 🌟\r\n",
                normalize("\uFEFF[00:01]你好 🌟\r\n[00:01]Hello 🌟\r\n"));
    }

    @Test
    void unsupportedCompressedAndCueFormatsFallBackUnchanged() {
        for (String raw : new String[]{
                "[0,1000](0,500,0)Hello\n[0,1000]你好",
                "[0,1000]Hello(0,500)\n[0,1000]你好",
                "<?xml version=\"1.0\"?><tt><body>你好</body></tt>",
                "WEBVTT\n\n00:01.000 --> 00:02.000\nHello 你好",
                "1\n00:00:01,000 --> 00:00:02,000\nHello 你好"}) {
            assertTrue(normalizer.normalize(raw, Options.defaults()).isEmpty(), raw);
        }
    }

    @Test
    void malformedTimestampAnywhereInLeadingStampsFallsBack() {
        for (String malformed : new String[]{"[00:60]你好", "[00:xx]你好", "[1000:00]你好", "[00:01.1234567]你好", "[00:01][00:99]你好"}) {
            assertTrue(normalizer.normalize(malformed + "\n[00:01]Hello", Options.defaults()).isEmpty(), malformed);
        }
    }

    @Test
    void malformedInteriorWordTimestampsFallBackRatherThanProducingUnsafeSpl() {
        for (String malformed : new String[]{"你[00:99]好", "你<00:xx>好", "你<00:01]好", "你[00:01.1234567]好"}) {
            assertTrue(normalizer.normalize("[00:01]" + malformed + "\n[00:01]Hello",
                    Options.defaults()).isEmpty(), malformed);
        }
    }

    @Test
    void nullBlankPlainAndNullCharacterInputFallBack() {
        for (String raw : new String[]{null, "", "\n\t", "Hello 你好", "[00:01]Hello\0你好"}) {
            assertTrue(normalizer.normalize(raw, Options.defaults()).isEmpty());
        }
    }

    @Test
    void automaticNormalizationIsIdempotent() {
        String normalized = normalize("[00:01]你好\n[00:01]Hello");
        assertTrue(normalizer.normalize(normalized, Options.defaults()).isEmpty());
    }

    @Test
    void defaultOptionsAreSafeAndAllowInlineSplitting() {
        assertEquals(Options.OutputOrder.ORIGINAL_FIRST, Options.defaults().outputOrder());
        assertEquals(AUTO, Options.defaults().inputOrder());
        assertTrue(Options.defaults().splitInline());
        assertFalse(normalizer.normalize("[00:01]Hello 你好", Options.defaults()).isEmpty());
    }

    private String normalize(String raw) {
        return normalize(raw, Options.defaults());
    }

    private String normalize(String raw, Options options) {
        Optional<String> result = normalizer.normalize(raw, options);
        assertTrue(result.isPresent(), raw);
        return result.orElseThrow();
    }
}
