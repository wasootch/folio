package ca.scottadams.folio.ui;

import ca.scottadams.folio.model.BookMetadata;

import java.nio.file.Path;

/**
 * A single row in the file list. It is either an EPUB (the file on disk, its
 * parsed metadata or parse error, and the currently-computed rename preview)
 * or a {@linkplain #isLocation() location} the user can open: a subfolder, a
 * drive, a server and so on.
 * <p>
 * For EPUBs, {@link #metadata} is what the app currently believes (and renames
 * from) and {@link #original} is what is stored in the file; they differ while
 * the user has edits that haven't been saved to the EPUB.
 */
final class BookRow {

    Path file;
    /** Current (possibly edited) metadata; null for locations and unreadable files. */
    BookMetadata metadata;
    /** The metadata as last read from, or saved to, the file. */
    BookMetadata original;
    final String error;
    /** Where double-clicking this row goes; null for EPUB rows. */
    final Location target;
    String newBaseName;
    /** What is being written to the EPUB right now (a save is in flight), or null. */
    BookMetadata saving;
    /** Cached raw OPF metadata block, loaded on demand; null until then (or after a save). */
    String rawMetadata;

    BookRow(Path file, BookMetadata metadata, String error) {
        this.file = file;
        this.metadata = metadata;
        this.original = metadata;
        this.error = error;
        this.target = null;
    }

    private BookRow(Location target) {
        this.file = null;
        this.metadata = null;
        this.original = null;
        this.error = null;
        this.target = target;
    }

    static BookRow location(Location target) {
        return new BookRow(target);
    }

    boolean isLocation() {
        return target != null;
    }

    /** True when the metadata has been changed and not yet saved to the EPUB. */
    boolean isEdited() {
        return metadata != null && !metadata.equals(original);
    }

    /** True when there are edits that are neither saved nor already on their way to the file. */
    boolean needsSave() {
        return isEdited() && !metadata.equals(saving);
    }

    /**
     * Records that the file now contains {@code stored}. If the user kept editing while the save was
     * running, those newer edits stay (and remain unsaved) instead of being overwritten.
     */
    void markStored(BookMetadata written, BookMetadata stored) {
        boolean untouched = metadata.equals(written);
        this.original = stored;
        this.rawMetadata = null;
        if (untouched) {
            this.metadata = stored;
        }
    }

    /** Records that the file now contains {@code saved}. */
    void markSaved(BookMetadata saved) {
        this.metadata = saved;
        this.original = saved;
        this.rawMetadata = null;
    }

    String extension() {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }
}
