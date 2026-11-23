package ca.scottadams.folio.epub;

import ca.scottadams.folio.model.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the reader and writer against real EPUBs (see {@code src/test/resources/epubs/NOTICE.md}),
 * which have quirks that hand-built test files don't: several titles, refining metas, comments,
 * media-overlay metadata and real compression.
 */
class RealWorldEpubTest {

    static final String CHILDRENS = "childrens-literature.epub";
    static final String MOBY = "moby-dick-trimmed.epub";

    @TempDir
    Path dir;

    private Path copyOf(String fixture) throws IOException {
        Path target = dir.resolve(fixture);
        try (InputStream in = RealWorldEpubTest.class.getResourceAsStream("/epubs/" + fixture)) {
            Files.copy(in, target);
        }
        return target;
    }

    private static BookMetadata edit(BookMetadata m, String title, List<String> authors, String series, Double index) {
        return new BookMetadata(title, authors, series, index, m.publisher(), m.publishedDate(), m.language(),
                m.identifier(), m.description());
    }

    // ------------------------------------------------------------------ reading

    @Test
    void readsAnEpub3BookWithTwoTitlesAndTwoAuthors() throws Exception {
        BookMetadata m = EpubMetadataReader.read(copyOf(CHILDRENS));

        assertEquals("Children's Literature", m.title(), "the main title, not the subtitle");
        assertEquals(List.of("Charles Madison Curry", "Erle Elsworth Clippinger"), m.authors());
        assertEquals("en", m.language());
        assertEquals("2008-05-20", m.publishedDate());
        assertEquals("http://www.gutenberg.org/ebooks/25545", m.identifier());
    }

    @Test
    void readsAMediaOverlayBookWithCommentsAndRoleMetas() throws Exception {
        BookMetadata m = EpubMetadataReader.read(copyOf(MOBY));

        assertEquals("Moby-Dick", m.title());
        assertEquals(List.of("Herman Melville"), m.authors(), "the marker contributor must not count as an author");
        assertEquals("Harper & Brothers, Publishers", m.publisher());
        assertEquals("en-US", m.language());
    }

    // ------------------------------------------------------------------ writing

    @ParameterizedTest
    @ValueSource(strings = {CHILDRENS, MOBY})
    void editingMetadataRoundTripsAndKeepsTheRestOfTheMetadata(String fixture) throws Exception {
        Path epub = copyOf(fixture);
        String rawBefore = EpubMetadataReader.readRawMetadata(epub);
        BookMetadata before = EpubMetadataReader.read(epub);

        EpubMetadataWriter.write(epub, edit(before, "Edited Title", List.of("New Author"), "Some Series", 2.0));

        BookMetadata after = EpubMetadataReader.read(epub);
        assertEquals("Edited Title", after.title());
        assertEquals(List.of("New Author"), after.authors());
        assertEquals("Some Series", after.series());
        assertEquals(2.0, after.seriesIndex());
        assertEquals(before.publisher(), after.publisher());
        assertEquals(before.language(), after.language());
        assertEquals(before.identifier(), after.identifier());
        assertEquals(before.publishedDate(), after.publishedDate());

        String rawAfter = EpubMetadataReader.readRawMetadata(epub);
        for (String kept : List.of("dcterms:modified", "refines", "dc:identifier")) {
            assertEquals(rawBefore.contains(kept), rawAfter.contains(kept), "lost or gained: " + kept);
        }
    }

    @Test
    void unrelatedMetadataSurvivesAnEdit() throws Exception {
        Path children = copyOf(CHILDRENS);
        EpubMetadataWriter.write(children, edit(EpubMetadataReader.read(children), "Changed", List.of("X"), null, null));
        String childrenRaw = EpubMetadataReader.readRawMetadata(children);
        assertTrue(childrenRaw.contains("Children -- Books and reading"), "subjects must survive");
        assertTrue(childrenRaw.contains("Public domain in the USA."), "rights must survive");

        Path moby = copyOf(MOBY);
        EpubMetadataWriter.write(moby, edit(EpubMetadataReader.read(moby), "Changed", List.of("X"), null, null));
        String mobyRaw = EpubMetadataReader.readRawMetadata(moby);
        assertTrue(mobyRaw.contains("media:narrator"), "media overlay metadata must survive");
        assertTrue(mobyRaw.contains("Dave Cramer"), "non-author contributors must survive");
    }

    @ParameterizedTest
    @ValueSource(strings = {CHILDRENS, MOBY})
    void theRewrittenArchiveIsStillAValidEpubContainer(String fixture) throws Exception {
        Path epub = copyOf(fixture);
        byte[] original = Files.readAllBytes(epub);
        List<String> namesBefore = EpubMetadataWriterTest.entryNames(epub);

        EpubMetadataWriter.write(epub, edit(EpubMetadataReader.read(epub), "Edited", List.of("A"), null, null));

        assertEquals(namesBefore, EpubMetadataWriterTest.entryNames(epub), "same entries in the same order");
        assertEquals("mimetype", namesBefore.get(0));
        assertArrayEquals(original, Files.readAllBytes(EpubMetadataWriter.backupPath(epub)),
                "the backup is the untouched original");
        String opfPath = opfPath(epub);
        try (ZipFile now = new ZipFile(epub.toFile());
             ZipFile was = new ZipFile(EpubMetadataWriter.backupPath(epub).toFile())) {
            assertEquals(ZipEntry.STORED, now.getEntry("mimetype").getMethod());
            for (ZipEntry entry : Collections.list(now.entries())) {
                byte[] content = EpubMetadataWriterTest.read(now, entry.getName()); // reads to the end: CRC checked
                if (entry.getName().equals(opfPath)) {
                    continue;
                }
                ZipEntry old = was.getEntry(entry.getName());
                assertArrayEquals(EpubMetadataWriterTest.read(was, entry.getName()), content, entry.getName());
                assertEquals(old.getMethod(), entry.getMethod(), entry.getName());
                assertEquals(old.getCompressedSize(), entry.getCompressedSize(),
                        entry.getName() + " must not be recompressed");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {CHILDRENS, MOBY})
    void savingTwiceInARowWorks(String fixture) throws Exception {
        Path epub = copyOf(fixture);

        EpubMetadataWriter.write(epub, edit(EpubMetadataReader.read(epub), "First", List.of("A"), "S", 1.0));
        EpubMetadataWriter.write(epub, edit(EpubMetadataReader.read(epub), "Second", List.of("B", "C"), null, null));

        BookMetadata m = EpubMetadataReader.read(epub);
        assertEquals("Second", m.title());
        assertEquals(List.of("B", "C"), m.authors());
        assertNull(m.series());
    }

    private static String opfPath(Path epub) throws IOException {
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            return EpubMetadataReader.findOpfPath(zip);
        }
    }
}
