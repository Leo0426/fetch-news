package app.reader;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;

public record SearchResultView(
        ReaderItem article,
        List<TextPart> titleParts,
        List<TextPart> excerptParts,
        String excerptLabel) {

    private static final int EXCERPT_LENGTH = 140;
    private static final int LEADING_CONTEXT = 40;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}\\p{Co}][\\p{L}\\p{N}\\p{Co}\\p{M}]*");

    public SearchResultView {
        titleParts = List.copyOf(titleParts);
        excerptParts = List.copyOf(excerptParts);
    }

    public record TextPart(String text, boolean matched) {}

    public static SearchResultView from(ReaderItem article, String description, String query) {
        QueryPatterns patterns = queryPatterns(query);
        String title = article.title() == null ? "" : article.title();
        String body = description == null ? "" : Jsoup.parse(description).text();
        String summary = article.summary() == null ? "" : article.summary().strip();
        List<Match> bodyMatches = matches(body, patterns);
        List<Match> summaryMatches = matches(summary, patterns);
        boolean useBody = !bodyMatches.isEmpty() || (summaryMatches.isEmpty() && !body.isBlank());
        String excerpt = useBody ? body : summary;
        return new SearchResultView(
                article,
                highlight(title, matches(title, patterns), 0, title.length()),
                excerpt(excerpt, useBody ? bodyMatches : summaryMatches),
                excerpt.isBlank() ? "" : useBody ? "正文摘录" : "AI摘要");
    }

    private static QueryPatterns queryPatterns(String query) {
        List<String> terms = Arrays.stream((query == null ? "" : query).split("(?U)\\s+"))
                .filter(term -> !term.isEmpty())
                .distinct()
                .toList();
        String alternatives = terms.stream()
                .filter(SearchResultView::isLiteralTerm)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        Pattern literal = alternatives.isEmpty() ? null : Pattern.compile(alternatives, Pattern.CASE_INSENSITIVE);
        List<Pattern> words = terms.stream()
                .filter(term -> !isLiteralTerm(term))
                .map(term -> Pattern.compile(Pattern.quote(foldLatin(term)), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
        return new QueryPatterns(literal, words);
    }

    private static boolean isLiteralTerm(String term) {
        return term.codePoints().anyMatch(character ->
                Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN
                        || !Character.isLetterOrDigit(character));
    }

    private static String foldLatin(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder folded = new StringBuilder();
        boolean latinBase = false;
        for (int offset = 0; offset < normalized.length();) {
            int character = normalized.codePointAt(offset);
            int type = Character.getType(character);
            boolean mark = type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
            if (!mark || !latinBase) folded.appendCodePoint(character);
            if (!mark) latinBase = Character.UnicodeScript.of(character) == Character.UnicodeScript.LATIN;
            offset += Character.charCount(character);
        }
        return folded.toString();
    }

    private static List<Match> matches(String text, QueryPatterns patterns) {
        List<Match> ranges = new ArrayList<>();
        if (patterns.literal() != null) {
            Matcher literal = patterns.literal().matcher(text);
            while (literal.find()) ranges.add(new Match(literal.start(), literal.end()));
        }
        if (!patterns.words().isEmpty()) {
            Matcher word = WORD.matcher(text);
            while (word.find()) {
                String folded = foldLatin(word.group());
                if (patterns.words().stream().anyMatch(pattern -> pattern.matcher(folded).matches())) {
                    ranges.add(new Match(word.start(), word.end()));
                }
            }
        }
        ranges.sort(Comparator.comparingInt(Match::start));
        List<Match> merged = new ArrayList<>();
        for (Match match : ranges) {
            if (!merged.isEmpty() && merged.getLast().end() >= match.start()) {
                Match previous = merged.removeLast();
                merged.add(new Match(previous.start(), Math.max(previous.end(), match.end())));
            } else {
                merged.add(match);
            }
        }
        return merged;
    }

    private static List<TextPart> excerpt(String text, List<Match> matches) {
        if (text.isBlank()) return List.of();
        int length = text.codePointCount(0, text.length());
        int matchStart = matches.isEmpty() ? 0 : text.codePointCount(0, matches.getFirst().start());
        int start = Math.max(0, matchStart - LEADING_CONTEXT);
        int end = Math.min(length, start + EXCERPT_LENGTH);
        start = Math.max(0, Math.min(start, end - EXCERPT_LENGTH));
        List<TextPart> parts = new ArrayList<>();
        if (start > 0) parts.add(new TextPart("…", false));
        parts.addAll(highlight(text, matches, text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end)));
        if (end < length) parts.add(new TextPart("…", false));
        return parts;
    }

    private static List<TextPart> highlight(String text, List<Match> matches, int start, int end) {
        List<TextPart> parts = new ArrayList<>();
        int offset = start;
        for (Match match : matches) {
            if (match.start() >= end) break;
            if (match.end() <= start) continue;
            int matchStart = Math.max(start, match.start());
            int matchEnd = Math.min(end, match.end());
            if (matchStart > offset) parts.add(new TextPart(text.substring(offset, matchStart), false));
            parts.add(new TextPart(text.substring(matchStart, matchEnd), true));
            offset = matchEnd;
        }
        if (offset < end) parts.add(new TextPart(text.substring(offset, end), false));
        return parts;
    }

    private record QueryPatterns(Pattern literal, List<Pattern> words) {}

    private record Match(int start, int end) {}
}
