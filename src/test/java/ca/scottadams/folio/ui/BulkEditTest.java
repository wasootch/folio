package ca.scottadams.folio.ui;

import ca.scottadams.folio.model.BookMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkEditTest {

    private static final BookMetadata BOOK = new BookMetadata("Title", List.of("Ann", "Bob"), "Series", 3.0,
            "Pub", "2020-01-01", "en", "id-1", "desc");

    @Test
    void getReadsTheMatchingField() {
        assertEquals("Series", BulkEdit.SERIES.get(BOOK));
        assertEquals(List.of("Ann", "Bob"), BulkEdit.AUTHORS.get(BOOK));
        assertEquals("Pub", BulkEdit.PUBLISHER.get(BOOK));
        assertEquals("en", BulkEdit.LANGUAGE.get(BOOK));
    }

    @Test
    void withReplacesOnlyItsOwnField() {
        BookMetadata changed = BulkEdit.SERIES.with(BOOK, "New Series");

        assertEquals("New Series", changed.series());
        assertEquals(new BookMetadata("Title", List.of("Ann", "Bob"), "New Series", 3.0, "Pub", "2020-01-01", "en",
                "id-1", "desc"), changed);
    }

    @Test
    void withCanReplaceAuthorsAndClearValues() {
        assertEquals(List.of("Zed"), BulkEdit.AUTHORS.with(BOOK, List.of("Zed")).authors());
        assertEquals(null, BulkEdit.PUBLISHER.with(BOOK, null).publisher());
        assertEquals("Title", BulkEdit.PUBLISHER.with(BOOK, null).title(), "title is never touched by a bulk edit");
        assertEquals(3.0, BulkEdit.SERIES.with(BOOK, null).seriesIndex(), "each book keeps its own number");
    }

    @Test
    void emptinessCoversNullBlankAndEmptyLists() {
        assertTrue(BulkEdit.isEmpty(null));
        assertTrue(BulkEdit.isEmpty(""));
        assertTrue(BulkEdit.isEmpty("   "));
        assertTrue(BulkEdit.isEmpty(List.of()));
        assertFalse(BulkEdit.isEmpty("x"));
        assertFalse(BulkEdit.isEmpty(List.of("x")));
    }

    @Test
    void displayIsReadableForDialogs() {
        assertEquals("(empty)", BulkEdit.display(null));
        assertEquals("(empty)", BulkEdit.display(List.of()));
        assertEquals("Series", BulkEdit.display("Series"));
        assertEquals("Ann; Bob", BulkEdit.display(List.of("Ann", "Bob")));
    }

    @Test
    void onlyFieldsSharedByASeriesOrAuthorAreOffered() {
        assertEquals(4, BulkEdit.values().length);
        assertEquals("Series", BulkEdit.SERIES.label());
    }
}
