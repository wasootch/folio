package ca.scottadams.folio.rename;

import ca.scottadams.folio.model.BookMetadata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a filename from a token template and a book's metadata.
 * <p>
 * Supported tokens: {@code {title}}, {@code {author}}, {@code {authors}},
 * {@code {series}}, {@code {series_index}}, {@code {publisher}}, {@code {year}},
 * {@code {language}}. Unknown/missing values render as empty strings; leftover
 * separator artifacts (e.g. a dangling " - " where a series was absent) are
 * cleaned up automatically.
 */
public final class RenameTemplate {

    public static final String DEFAULT_TEMPLATE = "{author} - {series} {series_index} - {title}";
    public static final String DEFAULT_TEMPLATE_NO_SERIES = "{author} - {title}";

    private static final Pattern TOKEN = Pattern.compile("\\{(\\w+)}");

    private RenameTemplate() {
    }

    /** Renders the template against metadata, returning a sanitized filename (without extension). */
    public static String render(String template, BookMetadata meta) {
        String effectiveTemplate = (template == null || template.isBlank())
                ? (meta.hasSeries() ? DEFAULT_TEMPLATE : DEFAULT_TEMPLATE_NO_SERIES)
                : template;

        Map<String, String> values = tokenValues(meta);

        Matcher matcher = TOKEN.matcher(effectiveTemplate);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1).toLowerCase(), "");
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);

        return FilenameSanitizer.sanitize(cleanupSeparators(rendered.toString()));
    }

    private static Map<String, String> tokenValues(BookMetadata meta) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", nullToEmpty(meta.title()));
        values.put("author", nullToEmpty(meta.primaryAuthor()));
        values.put("authors", nullToEmpty(meta.authorsJoined()));
        values.put("series", nullToEmpty(meta.series()));
        values.put("series_index", formatSeriesIndex(meta.seriesIndex()));
        values.put("publisher", nullToEmpty(meta.publisher()));
        values.put("year", extractYear(meta.publishedDate()));
        values.put("language", nullToEmpty(meta.language()));
        return values;
    }

    private static String formatSeriesIndex(Double index) {
        if (index == null) {
            return "";
        }
        if (index == Math.floor(index)) {
            return String.format("%02d", index.intValue());
        }
        return String.format("%04.1f", index);
    }

    private static String extractYear(String publishedDate) {
        if (publishedDate == null || publishedDate.length() < 4) {
            return "";
        }
        return publishedDate.substring(0, 4);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Collapses leftover separator artifacts left behind by empty tokens, e.g. "Title -  - " to "Title". */
    private static String cleanupSeparators(String text) {
        String cleaned = text.replaceAll("[ \\t]+", " ");
        cleaned = cleaned.replaceAll("(\\s*-\\s*){2,}", " - ");
        cleaned = cleaned.replaceAll("^(\\s*-\\s*)+", "");
        cleaned = cleaned.replaceAll("(\\s*-\\s*)+$", "");
        return cleaned.trim();
    }
}
