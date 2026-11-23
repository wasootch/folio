package ca.scottadams.folio.epub;

import ca.scottadams.folio.model.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpubMetadataWriterTest {

    @TempDir
    Path dir;

    private static BookMetadata edit(BookMetadata m, String title, List<String> authors, String series,
                                     Double index) {
        return new BookMetadata(title, authors, series, index, m.publisher(), m.publishedDate(), m.language(),
                m.identifier(), m.description());
    }

    private Path calibreBook() throws IOException {
        return TestEpubs.write(dir.resolve("book.epub"),
                TestEpubs.calibreOpf("The Way of Kings", "Brandon Sanderson", "Stormlight Archive", "1.0"));
    }

    // ------------------------------------------------------------------ what gets written

    @Test
    void changesTitleAuthorsAndSeriesInACalibreBook() throws Exception {
        Path epub = calibreBook();
        BookMetadata before = EpubMetadataReader.read(epub);

        EpubMetadataWriter.write(epub, edit(before, "Words of Radiance",
                List.of("Brandon Sanderson", "Another Person"), "The Stormlight Archive", 2.0));

        BookMetadata after = EpubMetadataReader.read(epub);
        assertEquals("Words of Radiance", after.title());
        assertEquals(List.of("Brandon Sanderson", "Another Person"), after.authors());
        assertEquals("The Stormlight Archive", after.series());
        assertEquals(2.0, after.seriesIndex());
        assertEquals(before.publisher(), after.publisher());
        assertEquals(before.identifier(), after.identifier());
    }

    @Test
    void updatesEpub3CollectionInPlaceAndKeepsItsStyle() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("e3.epub"),
                TestEpubs.epub3Opf("Mistborn", "Brandon Sanderson", "Mistborn Era 1", "1"));
        BookMetadata before = EpubMetadataReader.read(epub);

        EpubMetadataWriter.write(epub, edit(before, before.title(), before.authors(), "Mistborn", 3.0));

        BookMetadata after = EpubMetadataReader.read(epub);
        assertEquals("Mistborn", after.series());
        assertEquals(3.0, after.seriesIndex());
        String raw = EpubMetadataReader.readRawMetadata(epub);
        assertTrue(raw.contains("belongs-to-collection"), raw);
        assertFalse(raw.contains("calibre:series"), "must not add a second convention: " + raw);
    }

    @Test
    void addsSeriesToABookThatHasNone() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("bare.epub"), TestEpubs.bareOpf("Standalone"));
        BookMetadata before = EpubMetadataReader.read(epub);

        EpubMetadataWriter.write(epub, edit(before, before.title(), List.of("New Author"), "A Series", 4.0));

        BookMetadata after = EpubMetadataReader.read(epub);
        assertEquals("A Series", after.series());
        assertEquals(4.0, after.seriesIndex());
        assertEquals(List.of("New Author"), after.authors());
    }

    @Test
    void removingTheSeriesRemovesBothConventions() throws Exception {
        for (String opf : List.of(TestEpubs.calibreOpf("T", "A", "S", "1"), TestEpubs.epub3Opf("T", "A", "S", "1"))) {
            Path epub = TestEpubs.write(dir.resolve("s" + opf.hashCode() + ".epub"), opf);
            BookMetadata before = EpubMetadataReader.read(epub);

            EpubMetadataWriter.write(epub, edit(before, before.title(), before.authors(), null, null));

            BookMetadata after = EpubMetadataReader.read(epub);
            assertNull(after.series());
            assertNull(after.seriesIndex());
            String raw = EpubMetadataReader.readRawMetadata(epub);
            assertFalse(raw.contains("calibre:series"), raw);
            assertFalse(raw.contains("belongs-to-collection"), raw);
            assertFalse(raw.contains("group-position"), raw);
        }
    }

    @Test
    void leavesNonAuthorCreatorsAlone() throws Exception {
        String opf = TestEpubs.calibreOpf("T", "Original Author", "S", "1").replace("</metadata>",
                "<dc:creator opf:role=\"ill\">Pat Illustrator</dc:creator></metadata>");
        Path epub = TestEpubs.write(dir.resolve("ill.epub"), opf);
        BookMetadata before = EpubMetadataReader.read(epub);

        EpubMetadataWriter.write(epub, edit(before, before.title(), List.of("Changed Author"), "S", 1.0));

        String raw = EpubMetadataReader.readRawMetadata(epub);
        assertTrue(raw.contains("Pat Illustrator"), raw);
        assertTrue(raw.contains("Changed Author"), raw);
        assertFalse(raw.contains("Original Author"), raw);
    }

    @Test
    void escapesMarkupAndKeepsUnicode() throws Exception {
        Path epub = calibreBook();
        BookMetadata before = EpubMetadataReader.read(epub);
        String title = "Q&A: <Tom> \"Sawyer\" – 日本語 ✓";

        EpubMetadataWriter.write(epub, edit(before, title, before.authors(), before.series(), before.seriesIndex()));

        assertEquals(title, EpubMetadataReader.read(epub).title());
    }

    @Test
    void unknownMetadataSurvives() throws Exception {
        String opf = TestEpubs.calibreOpf("T", "A", "S", "1")
                .replace("</metadata>", "<meta name=\"custom:thing\" content=\"kept\"/></metadata>");
        Path epub = TestEpubs.write(dir.resolve("custom.epub"), opf);
        BookMetadata before = EpubMetadataReader.read(epub);

        EpubMetadataWriter.write(epub, edit(before, "New Title", before.authors(), before.series(),
                before.seriesIndex()));

        assertTrue(EpubMetadataReader.readRawMetadata(epub).contains("custom:thing"));
    }

    @Test
    void savingUnchangedMetadataStillProducesAValidBook() throws Exception {
        Path epub = calibreBook();
        BookMetadata before = EpubMetadataReader.read(epub);

        EpubMetadataWriter.write(epub, before);

        assertEquals(before, EpubMetadataReader.read(epub));
    }

    // ------------------------------------------------------------------ zip integrity

    @Test
    void keepsMimetypeFirstAndStoredAndEveryOtherEntryIntact() throws Exception {
        Path epub = calibreBook();
        BookMetadata before = EpubMetadataReader.read(epub);
        List<String> namesBefore = entryNames(epub);
        long coverCompressedBefore;
        byte[] coverBefore;
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            coverCompressedBefore = zip.getEntry("OEBPS/cover.jpg").getCompressedSize();
            coverBefore = read(zip, "OEBPS/cover.jpg");
        }

        EpubMetadataWriter.write(epub, edit(before, "Another Title", before.authors(), before.series(),
                before.seriesIndex()));

        assertEquals(namesBefore, entryNames(epub), "entry order must be preserved");
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            ZipEntry mimetype = zip.getEntry("mimetype");
            assertEquals("mimetype", namesBefore.get(0));
            assertEquals(ZipEntry.STORED, mimetype.getMethod());
            assertEquals("application/epub+zip", new String(read(zip, "mimetype"), StandardCharsets.US_ASCII));
            assertArrayEquals(coverBefore, read(zip, "OEBPS/cover.jpg"));
            assertEquals(coverCompressedBefore, zip.getEntry("OEBPS/cover.jpg").getCompressedSize(),
                    "untouched entries must not be recompressed");
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                read(zip, entry.getName()); // reading to the end verifies each CRC
            }
        }
    }

    @Test
    void fallsBackToTheGeneralRewriteWhenTheFastCopyRefusesTheArchive() throws Exception {
        Path epub = calibreBook();
        // Mark the archive as spanning disks: the fast path declines it but java.util.zip still reads it.
        byte[] bytes = Files.readAllBytes(epub);
        int eocd = lastIndexOfSignature(bytes, 0x06054b50);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(eocd + 4, (short) 1);
        Files.write(epub, bytes);
        assertFalse(ZipRawCopy.replaceEntry(epub, dir.resolve("probe.tmp"), TestEpubs.OPF_PATH, new byte[0]));
        BookMetadata before = EpubMetadataReader.read(epub);

        EpubMetadataWriter.write(epub, edit(before, "Fallback Title", before.authors(), before.series(),
                before.seriesIndex()));

        assertEquals("Fallback Title", EpubMetadataReader.read(epub).title());
        assertEquals("mimetype", entryNames(epub).get(0));
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            assertEquals(ZipEntry.STORED, zip.getEntry("mimetype").getMethod());
        }
    }

    // ------------------------------------------------------------------ backup and failure behaviour

    @Test
    void firstSaveKeepsTheOriginalAsDotBakAndLaterSavesLeaveItAlone() throws Exception {
        Path epub = calibreBook();
        byte[] original = Files.readAllBytes(epub);
        Path backup = EpubMetadataWriter.backupPath(epub);
        assertEquals("book.epub.bak", backup.getFileName().toString());
        BookMetadata before = EpubMetadataReader.read(epub);

        EpubMetadataWriter.write(epub, edit(before, "First Edit", before.authors(), before.series(),
                before.seriesIndex()));
        assertArrayEquals(original, Files.readAllBytes(backup));

        BookMetadata mid = EpubMetadataReader.read(epub);
        EpubMetadataWriter.write(epub, edit(mid, "Second Edit", mid.authors(), mid.series(), mid.seriesIndex()));

        assertArrayEquals(original, Files.readAllBytes(backup), "the backup must stay the true original");
        assertEquals("Second Edit", EpubMetadataReader.read(epub).title());
    }

    @Test
    void leavesNoTemporaryFileBehind() throws Exception {
        Path epub = calibreBook();
        BookMetadata before = EpubMetadataReader.read(epub);

        EpubMetadataWriter.write(epub, edit(before, "X", before.authors(), before.series(), before.seriesIndex()));

        try (var files = Files.list(dir)) {
            assertEquals(List.of("book.epub", "book.epub.bak"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void aFailedSaveLeavesTheOriginalUntouchedAndCreatesNothingElse() throws Exception {
        Path notAnEpub = TestEpubs.writeWithoutContainer(dir.resolve("bad.epub"));
        byte[] original = Files.readAllBytes(notAnEpub);

        assertThrows(EpubParseException.class,
                () -> EpubMetadataWriter.write(notAnEpub, BookMetadata.empty()));

        assertArrayEquals(original, Files.readAllBytes(notAnEpub));
        try (var files = Files.list(dir)) {
            assertEquals(List.of("bad.epub"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void savingAMissingFileFailsCleanly() {
        Path missing = dir.resolve("nope.epub");

        assertThrows(EpubParseException.class, () -> EpubMetadataWriter.write(missing, BookMetadata.empty()));

        assertFalse(Files.exists(missing));
        assertFalse(Files.exists(EpubMetadataWriter.backupPath(missing)));
    }

    // ------------------------------------------------------------------ helpers

    static List<String> entryNames(Path epub) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            zip.stream().forEach(e -> names.add(e.getName()));
        }
        return names;
    }

    static byte[] read(ZipFile zip, String name) throws IOException {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return in.readAllBytes();
        }
    }

    static int lastIndexOfSignature(byte[] bytes, int signature) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = bytes.length - 4; i >= 0; i--) {
            if (buffer.getInt(i) == signature) {
                return i;
            }
        }
        throw new IllegalStateException("signature not found");
    }
}
