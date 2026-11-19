package ca.scottadams.folio.model;

import java.util.List;

/**
 * Metadata extracted from an EPUB's OPF package document.
 */
public record BookMetadata(
        String title,
        List<String> authors,
        String series,
        Double seriesIndex,
        String publisher,
        String publishedDate,
        String language,
        String identifier,
        String description
) {

    public static BookMetadata empty() {
        return new BookMetadata(null, List.of(), null, null, null, null, null, null, null);
    }

    public String authorsJoined() {
        return authors.isEmpty() ? "" : String.join(", ", authors);
    }

    public String primaryAuthor() {
        return authors.isEmpty() ? "" : authors.get(0);
    }

    public boolean hasSeries() {
        return series != null && !series.isBlank();
    }
}
