package io.github.saltlyrics.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative, lossless lyric-text adapter for the host's SPL parser.
 *
 * <p>Supports timestamped LRC/SPL, including detached timestamp headers, repeated
 * leading timestamps, untimed translations, and clearly separated inline pairs.
 * The adapter does not decode QRC, YRC, SRT, WebVTT or XML/TTML. Empty results let
 * the host use its normal loading path, including when the text needs no change.
 * No files, network, host state or mutable per-track state are used.</p>
 */
public final class LyricNormalizer {
    private static final int MAX_CHARACTERS = 4_000_000;
    private static final int MAX_LINES = 100_000;
    private static final int MAX_LINE_CHARACTERS = 16_384;
    private static final int MAX_INLINE_BOUNDARIES = 64;
    private static final Pattern STAMP = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,6}))?\\]");
    private static final Pattern WORD_STAMP = Pattern.compile(
            "(?:\\[|<)\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,6})?(?:\\]|>)");
    private static final Pattern TIME_CANDIDATE = Pattern.compile("[\\[<]\\d[^\\[\\]<>\\r\\n]*[\\]>]");
    private static final Pattern METADATA = Pattern.compile(
            "\\[[A-Za-z][A-Za-z0-9_-]*:[^\\]\\r\\n]*\\][ \\t]*");
    private static final Pattern STRONG_SEPARATOR = Pattern.compile(
            "\\t+|[\\u2002-\\u200A\\u202F]+| {2,}|[ \\t]+[/|][ \\t]+");
    private static final Pattern TRAILING_STAMP = Pattern.compile(
            "(?:\\[|<)(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,6}))?(?:\\]|>)[ \\t]*$");

    /**
     * Returns normalized SPL only if a safe, useful change was found.
     * AUTO recognizes a Chinese translation paired with kana, Hangul, Latin or
     * another alphabet; two Han-only lines remain ambiguous and keep their order.
     * A manual input order assigns roles to each two-line group instead.
     */
    public Optional<String> normalize(String raw, Options options) {
        Objects.requireNonNull(options, "options");
        if (raw == null || raw.isBlank() || raw.length() > MAX_CHARACTERS || raw.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        if (unsupported(raw)) {
            return Optional.empty();
        }

        String newline = raw.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = raw.split("\\r\\n|\\n|\\r", -1);
        if (lines.length > MAX_LINES) {
            return Optional.empty();
        }
        List<Line> parsed = new ArrayList<>(lines.length);
        boolean hasTimedText = false;
        for (String line : lines) {
            if (line.length() > MAX_LINE_CHARACTERS) {
                return Optional.empty();
            }
            Line result = parse(line);
            if (result == null) {
                return Optional.empty();
            }
            parsed.add(result);
            hasTimedText |= result instanceof TimedLine timed && !timed.body().isBlank();
        }
        boolean changed = false;
        List<Line> associated = new ArrayList<>(parsed.size());
        for (int index = 0; index < parsed.size(); index++) {
            Line line = parsed.get(index);
            associated.add(line);
            if (line instanceof TimedLine timed && timed.body().isBlank()
                    && index + 1 < parsed.size()
                    && parsed.get(index + 1) instanceof PlainLine following
                    && following.lyricCandidate() && !following.source().isBlank()) {
                if (!validInteriorStamps(following.source())) return Optional.empty();
                // Preserve the original marker's cue-end meaning, then start a
                // new cue at that same time. Only the immediate body can bind;
                // blanks, metadata and other timestamp rows remain barriers.
                associated.add(new TimedLine(following.source(), timed.times(), following.source()));
                index++;
                hasTimedText = true;
                changed = true;
            }
        }
        if (!hasTimedText) return Optional.empty();

        List<Node> nodes = new ArrayList<>();
        Map<Long, Group> groups = new HashMap<>();
        TimedLine previous = null;
        for (Line line : associated) {
            if (line instanceof TimedLine timed) {
                if (timed.body().isBlank()) {
                    // A timestamp-only row still ends the previous cue, even
                    // when it also supplied a detached header for the next cue.
                    nodes.add(new RawNode(timed.source()));
                    groups.clear();
                    previous = null;
                    continue;
                }
                Body body = new Body(timed.body(), true);
                for (long time : timed.times()) {
                    Group group = getGroup(time, groups, nodes);
                    group.bodies.add(body);
                }
                previous = timed;
            } else if (line instanceof PlainLine plain) {
                // SPL assigns all consecutive untimed sublines to the preceding
                // timed source, including romanization that language detection
                // cannot classify. Preserve that ownership when moving groups.
                if (previous != null && plain.lyricCandidate() && !plain.source().isBlank()) {
                    for (long time : previous.times()) {
                        getGroup(time, groups, nodes).bodies.add(new Body(plain.source(), false));
                    }
                    changed = true;
                } else {
                    nodes.add(new RawNode(plain.source()));
                    if (!plain.source().isBlank()) {
                        groups.clear();
                    }
                    previous = null;
                }
            }
        }

        // A repeated timed source shares its text across occurrences. Split it
        // once, after preserving the surrounding pair's language context.
        Map<String, List<String>> splitCache = new IdentityHashMap<>();
        for (Node node : nodes) {
            if (!(node instanceof Group group)) {
                continue;
            }
            boolean establishedPair = group.bodies.size() == 2 && distinctivePair(
                    profile(group.bodies.get(0).text()), profile(group.bodies.get(1).text())).isPresent();
            if (options.splitInline() && !establishedPair) {
                List<Body> expanded = new ArrayList<>(group.bodies.size());
                for (Body body : group.bodies) {
                    if (body.timed()) {
                        List<String> parts = splitCache.computeIfAbsent(body.text(), text -> splitInline(text, options));
                        if (parts.size() > 1) {
                            for (String part : parts) expanded.add(new Body(part, false));
                        } else {
                            expanded.add(body);
                        }
                    } else {
                        expanded.add(body);
                    }
                }
                if (expanded.size() > group.bodies.size()) {
                    group.bodies.clear();
                    group.bodies.addAll(expanded);
                    changed = true;
                }
            }
            // SPL permits several secondary rows. Keep those intact: guessing
            // which one is romanization, a translation or a second voice is unsafe.
            if (group.bodies.size() != 2) {
                continue;
            }
            Optional<Boolean> firstOriginal = pairedRoles(
                    group.bodies.get(0).text(), group.bodies.get(1).text(), options.inputOrder());
            if (firstOriginal.isPresent()
                    && firstOriginal.get() != (options.outputOrder() == Options.OutputOrder.ORIGINAL_FIRST)) {
                // Moving a unique explicit end onto the secondary row can alter
                // the main cue's duration. Leave such input entirely to the host.
                if (!Objects.equals(explicitEnd(group.bodies.get(0).text()), explicitEnd(group.bodies.get(1).text()))) {
                    return Optional.empty();
                }
                Body first = group.bodies.get(0);
                group.bodies.set(0, group.bodies.get(1));
                group.bodies.set(1, first);
                changed = true;
            }
        }
        if (!changed) {
            return Optional.empty();
        }

        List<String> output = new ArrayList<>(lines.length);
        long outputCharacters = 0;
        for (Node node : nodes) {
            if (node instanceof RawNode plain) {
                outputCharacters += (long) plain.source().length() + newline.length();
                if (outputCharacters > MAX_CHARACTERS) return Optional.empty();
                output.add(plain.source());
            } else if (node instanceof Group group) {
                String timestamp = format(group.time);
                for (Body body : group.bodies) {
                    outputCharacters += (long) timestamp.length() + body.text().length() + newline.length();
                    if (outputCharacters > MAX_CHARACTERS) return Optional.empty();
                    output.add(timestamp + body.text());
                }
            }
        }
        String normalized = String.join(newline, output);
        if (raw.startsWith("\uFEFF") && !normalized.startsWith("\uFEFF")) {
            normalized = "\uFEFF" + normalized;
        }
        return Optional.of(normalized);
    }

    private static Group getGroup(long time, Map<Long, Group> groups, List<Node> nodes) {
        Group group = groups.get(time);
        if (group == null) {
            group = new Group(time);
            groups.put(time, group);
            nodes.add(group);
        }
        return group;
    }

    private static boolean unsupported(String raw) {
        String start = raw.stripLeading().toLowerCase(Locale.ROOT);
        return start.startsWith("<") || start.startsWith("webvtt")
                || raw.contains("-->");
    }

    private static Line parse(String source) {
        String candidate = source.startsWith("\uFEFF") ? source.substring(1) : source;
        if (candidate.isBlank() || METADATA.matcher(candidate).matches()) {
            return new PlainLine(source, false);
        }
        Matcher matcher = STAMP.matcher(candidate);
        int position = 0;
        List<Long> times = new ArrayList<>(2);
        while (matcher.find(position) && matcher.start() == position) {
            int seconds = Integer.parseInt(matcher.group(2));
            if (seconds >= 60) {
                return null;
            }
            long minutes = Long.parseLong(matcher.group(1));
            String fraction = matcher.group(3);
            long micros = fraction == null ? 0 : Long.parseLong((fraction + "000000").substring(0, 6));
            times.add((minutes * 60 + seconds) * 1_000_000 + micros);
            position = matcher.end();
        }
        if (times.isEmpty()) {
            // Bracket-led unknown formats include compressed QRC/YRC and broken
            // timestamps. Leaving the entire input to the host is safest.
            return candidate.stripLeading().startsWith("[") ? null : new PlainLine(source, true);
        }
        String body = candidate.substring(position);
        if ((body.startsWith("[") && looksLikeBrokenStamp(body)) || !validInteriorStamps(body)) {
            return null;
        }
        return new TimedLine(source, times, body);
    }

    private static boolean looksLikeBrokenStamp(String body) {
        return body.length() > 1 && Character.isDigit(body.charAt(1)) && body.indexOf(':') >= 0;
    }

    private static boolean validInteriorStamps(String body) {
        Matcher candidates = TIME_CANDIDATE.matcher(body);
        while (candidates.find()) {
            String token = candidates.group();
            if (token.indexOf(':') < 0) {
                continue;
            }
            if (token.startsWith("<")) {
                if (!token.endsWith(">")) {
                    return false;
                }
                token = "[" + token.substring(1, token.length() - 1) + "]";
            }
            Matcher stamp = STAMP.matcher(token);
            if (!stamp.matches() || Integer.parseInt(stamp.group(2)) >= 60) {
                return false;
            }
        }
        return true;
    }

    private static List<String> splitInline(String body, Options options) {
        // Splitting timed cells can turn an interior cell boundary into a cue
        // end or strand timing on one half. Retain this advanced layout intact.
        if (WORD_STAMP.matcher(body).find()) return List.of(body);
        Profile whole = profile(body);
        if (options.inputOrder() == Options.InputOrder.AUTO
                && (whole.han == 0 || whole.kana + whole.hangul + whole.latin + whole.other == 0)) {
            return List.of(body);
        }
        Matcher separators = STRONG_SEPARATOR.matcher(body);
        int candidates = 0;
        while (separators.find()) {
            if (++candidates > MAX_INLINE_BOUNDARIES) {
                return List.of(body);
            }
            String left = body.substring(0, separators.start()).strip();
            String right = body.substring(separators.end()).strip();
            if (!left.isEmpty() && !right.isEmpty()
                    && roles(left, right, options.inputOrder()).isPresent()) {
                return List.of(left, right);
            }
        }
        // An ordinary space is common inside a code-switched sentence. Only a
        // substantial alphabetic phrase followed by a substantial Han-only phrase
        // is a credible fallback; never split merely at a change of script.
        int firstHan = -1;
        int lastLatin = -1;
        for (int position = 0; position < body.length();) {
            int cp = body.codePointAt(position);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN && firstHan == -1) {
                firstHan = position;
            } else if (script == Character.UnicodeScript.LATIN && Character.isLetter(cp)) {
                lastLatin = position;
            }
            position += Character.charCount(cp);
        }
        // Every earlier space has Latin on its right, and every later space has
        // Han on its left. Only this boundary can satisfy the conservative rule.
        int boundary = firstHan < 0 ? -1 : body.lastIndexOf(' ', firstHan);
        if (boundary > lastLatin && lastLatin >= 0) {
            String left = body.substring(0, boundary);
            String right = body.substring(boundary + 1);
            Profile first = profile(left);
            Profile second = profile(right);
            if (first.latin >= 12 && first.han == 0 && first.kana == 0 && first.hangul == 0
                    && first.other == 0 && first.words >= 4 && second.han >= 5
                    && second.latin == 0 && second.kana == 0 && second.hangul == 0 && second.other == 0) {
                return List.of(left.strip(), right.strip());
            }
        }
        return List.of(body);
    }

    private static Optional<Boolean> roles(String first, String second, Options.InputOrder inputOrder) {
        if (inputOrder == Options.InputOrder.ORIGINAL_FIRST) {
            return Optional.of(true);
        }
        if (inputOrder == Options.InputOrder.TRANSLATION_FIRST) {
            return Optional.of(false);
        }
        Profile a = profile(first);
        Profile b = profile(second);
        if (a.foreign() && b.chinese()) {
            return Optional.of(true);
        }
        if (a.chinese() && b.foreign()) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    private static Optional<Boolean> pairedRoles(String first, String second, Options.InputOrder inputOrder) {
        Optional<Boolean> strict = roles(first, second, inputOrder);
        if (strict.isPresent()) return strict;
        return distinctivePair(profile(first), profile(second));
    }

    private static Optional<Boolean> distinctivePair(Profile first, Profile second) {
        // Kana/Hangul provides evidence independent of how many Latin letters a
        // Chinese translation borrows. Do not use this relaxed rule to split an
        // isolated mixed-language sentence at one of its internal word spaces.
        if (first.distinctiveForeign() && second.chineseWithLoanwords()) return Optional.of(true);
        if (first.chineseWithLoanwords() && second.distinctiveForeign()) return Optional.of(false);
        return Optional.empty();
    }

    private static Long explicitEnd(String body) {
        Matcher end = TRAILING_STAMP.matcher(body);
        if (!end.find()) return null;
        String fraction = end.group(3);
        long micros = fraction == null ? 0 : Long.parseLong((fraction + "000000").substring(0, 6));
        return (Long.parseLong(end.group(1)) * 60 + Long.parseLong(end.group(2))) * 1_000_000 + micros;
    }

    private static Profile profile(String text) {
        String content = WORD_STAMP.matcher(text).replaceAll("");
        int han = 0;
        int kana = 0;
        int hangul = 0;
        int latin = 0;
        int other = 0;
        int words = 0;
        boolean previousLatin = false;
        for (int position = 0; position < content.length();) {
            int cp = content.codePointAt(position);
            position += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN) {
                han++;
            } else if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
                kana++;
            } else if (script == Character.UnicodeScript.HANGUL) {
                hangul++;
            } else if (script == Character.UnicodeScript.LATIN && Character.isLetter(cp)) {
                latin++;
            } else if (Character.isLetter(cp)) {
                other++;
            }
            boolean currentLatin = script == Character.UnicodeScript.LATIN && Character.isLetter(cp);
            if (currentLatin && !previousLatin) {
                words++;
            }
            previousLatin = currentLatin;
        }
        return new Profile(han, kana, hangul, latin, other, words);
    }

    private static String format(long time) {
        long minutes = time / 60_000_000;
        long seconds = time / 1_000_000 % 60;
        String fraction = String.format(Locale.ROOT, "%06d", time % 1_000_000);
        while (fraction.length() > 3 && fraction.endsWith("0")) {
            fraction = fraction.substring(0, fraction.length() - 1);
        }
        return String.format(Locale.ROOT, "[%02d:%02d.%s]", minutes, seconds, fraction);
    }

    private interface Line {}
    private record PlainLine(String source, boolean lyricCandidate) implements Line {}
    private record TimedLine(String source, List<Long> times, String body) implements Line {}
    private record Body(String text, boolean timed) {}
    private interface Node {}
    private record RawNode(String source) implements Node {}

    private static final class Group implements Node {
        private final long time;
        private final List<Body> bodies = new ArrayList<>(2);

        private Group(long time) {
            this.time = time;
        }
    }

    private record Profile(int han, int kana, int hangul, int latin, int other, int words) {
        boolean chinese() {
            return han > 0 && kana == 0 && hangul == 0 && other == 0 && latin <= Math.max(2, han / 3);
        }

        boolean foreign() {
            return kana > 0 || hangul > 0 || han == 0 && (latin > 0 || other > 0);
        }

        boolean distinctiveForeign() {
            return kana > 0 || hangul > 0;
        }

        boolean chineseWithLoanwords() {
            return han >= 2 && kana == 0 && hangul == 0 && other == 0;
        }
    }
}
