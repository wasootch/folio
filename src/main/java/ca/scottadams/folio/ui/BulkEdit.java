package ca.scottadams.folio.ui;

import ca.scottadams.folio.model.BookMetadata;

import java.util.List;

/**
 * The fields that make sense to change on several books at once (shared by a whole series or
 * author), and how to read or replace each one in a {@link BookMetadata}. Fields that are specific
 * to one book (title, series number, identifier, ...) are deliberately absent.
 */
enum BulkEdit {
    SERIES("Series"),
    AUTHORS("Author(s)"),
    PUBLISHER("Publisher"),
    LANGUAGE("Language");

    private final String label;

    BulkEdit(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    /** The field's value: a {@code List<String>} for authors, otherwise a String or null. */
    Object get(BookMetadata m) {
        return switch (this) {
            case SERIES -> m.series();
            case AUTHORS -> m.authors();
            case PUBLISHER -> m.publisher();
            case LANGUAGE -> m.language();
        };
    }

    /** A copy of {@code m} with this field replaced. */
    @SuppressWarnings("unchecked")
    BookMetadata with(BookMetadata m, Object value) {
        return new BookMetadata(
                m.title(),
                this == AUTHORS ? (List<String>) value : m.authors(),
                this == SERIES ? (String) value : m.series(),
                m.seriesIndex(),
                this == PUBLISHER ? (String) value : m.publisher(),
                m.publishedDate(),
                this == LANGUAGE ? (String) value : m.language(),
                m.identifier(),
                m.description());
    }

    static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        return value instanceof List<?> list && list.isEmpty();
    }

    /** Human-readable form of a value, for dialogs and hints. */
    static String display(Object value) {
        if (isEmpty(value)) {
            return "(empty)";
        }
        return value instanceof List<?> list
                ? String.join("; ", list.stream().map(String::valueOf).toList())
                : String.valueOf(value);
    }
}
