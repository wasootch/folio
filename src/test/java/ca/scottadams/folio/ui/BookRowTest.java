package ca.scottadams.folio.ui;

import ca.scottadams.folio.model.BookMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookRowTest {

    private static BookMetadata meta(String title) {
        return new BookMetadata(title, List.of("A"), "S", 1.0, null, null, null, null, null);
    }

    private static BookRow row(String title) {
        return new BookRow(Path.of("book.epub"), meta(title), null);
    }

    // ------------------------------------------------------------------ editing state

    @Test
    void aFreshRowIsNotEditedAndNeedsNoSave() {
        BookRow r = row("One");

        assertFalse(r.isEdited());
        assertFalse(r.needsSave());
    }

    @Test
    void changingMetadataMarksTheRowEdited() {
        BookRow r = row("One");

        r.metadata = meta("Two");

        assertTrue(r.isEdited());
        assertTrue(r.needsSave());
    }

    @Test
    void editingBackToTheOriginalIsNoLongerAnEdit() {
        BookRow r = row("One");
        r.metadata = meta("Two");
        r.metadata = meta("One");

        assertFalse(r.isEdited());
    }

    @Test
    void editsAlreadyBeingSavedDoNotNeedAnotherSave() {
        BookRow r = row("One");
        r.metadata = meta("Two");
        r.saving = r.metadata;

        assertTrue(r.isEdited());
        assertFalse(r.needsSave());
    }

    @Test
    void typingDuringASaveNeedsAnotherSave() {
        BookRow r = row("One");
        r.metadata = meta("Two");
        r.saving = r.metadata;
        r.metadata = meta("Three");

        assertTrue(r.needsSave());
    }

    @Test
    void markSavedClearsTheEditAndTheCachedRawText() {
        BookRow r = row("One");
        r.metadata = meta("Two");
        r.rawMetadata = "<old/>";

        r.markSaved(meta("Two"));

        assertFalse(r.isEdited());
        assertEquals(meta("Two"), r.original);
        assertNull(r.rawMetadata);
    }

    @Test
    void markStoredAdoptsTheStoredValueWhenNothingChangedMeanwhile() {
        BookRow r = row("One");
        BookMetadata written = meta("Two");
        r.metadata = written;
        BookMetadata stored = new BookMetadata("Two", List.of("A"), "S", 1.0, null, null, null, "id-from-file", null);

        r.markStored(written, stored);

        assertEquals(stored, r.metadata);
        assertEquals(stored, r.original);
        assertFalse(r.isEdited());
    }

    @Test
    void markStoredKeepsEditsMadeWhileTheSaveWasRunning() {
        BookRow r = row("One");
        BookMetadata written = meta("Two");
        r.metadata = written;
        r.metadata = meta("Three"); // typed after the save began

        r.markStored(written, written);

        assertEquals(meta("Three"), r.metadata, "newer edits must not be overwritten");
        assertEquals(written, r.original);
        assertTrue(r.isEdited());
    }

    // ------------------------------------------------------------------ naming match

    @Test
    void matchesNamingWhenTheFileNameIsExactlyTheProposedName() {
        BookRow r = new BookRow(Path.of("Brandon Sanderson - Elantris.epub"), meta("Elantris"), null);
        r.newBaseName = "Brandon Sanderson - Elantris";

        assertTrue(r.matchesNaming());
    }

    @Test
    void doesNotMatchWhenTheNameDiffers() {
        BookRow r = new BookRow(Path.of("elantris.epub"), meta("Elantris"), null);
        r.newBaseName = "Brandon Sanderson - Elantris";

        assertFalse(r.matchesNaming());
    }

    @Test
    void caseOnlyDifferencesCountAsNotMatching() {
        BookRow r = new BookRow(Path.of("brandon sanderson - elantris.epub"), meta("Elantris"), null);
        r.newBaseName = "Brandon Sanderson - Elantris";

        assertFalse(r.matchesNaming());
    }

    @Test
    void theExtensionIsPartOfTheComparison() {
        BookRow r = new BookRow(Path.of("Name.EPUB"), meta("x"), null);
        r.newBaseName = "Name";

        assertTrue(r.matchesNaming(), "the extension is carried over, not compared against a fixed .epub");
        assertEquals(".EPUB", r.extension());
    }

    @Test
    void doesNotMatchBeforeAProposalExists() {
        BookRow r = row("One");

        assertFalse(r.matchesNaming());
    }

    @Test
    void locationRowsNeverMatch() {
        BookRow r = BookRow.location(Location.NETWORK);

        assertTrue(r.isLocation());
        assertFalse(r.matchesNaming());
        assertFalse(r.isEdited());
    }
}
