package ca.scottadams.folio.epub;

import ca.scottadams.folio.model.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpubMetadataReaderTest {

    @TempDir
    Path dir;

    @Test
    void readsCalibreStyleMetadata() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("a.epub"),
                TestEpubs.calibreOpf("The Way of Kings", "Brandon Sanderson", "Stormlight Archive", "1.0"));

        BookMetadata m = EpubMetadataReader.read(epub);

        assertEquals("The Way of Kings", m.title());
        assertEquals(List.of("Brandon Sanderson"), m.authors());
        assertEquals("Stormlight Archive", m.series());
        assertEquals(1.0, m.seriesIndex());
        assertEquals("Tor", m.publisher());
        assertEquals("2010-08-31T00:00:00+00:00", m.publishedDate());
        assertEquals("en", m.language());
        assertEquals("urn:uuid:1234", m.identifier());
    }

    @Test
    void readsEpub3CollectionAndPosition() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("b.epub"),
                TestEpubs.epub3Opf("Mistborn", "Brandon Sanderson", "Mistborn Era 1", "2.5"));

        BookMetadata m = EpubMetadataReader.read(epub);

        assertEquals("Mistborn Era 1", m.series());
        assertEquals(2.5, m.seriesIndex());
        assertTrue(m.hasSeries());
    }

    @Test
    void bareFileGivesNullsAndNoAuthors() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("c.epub"), TestEpubs.bareOpf("Untitled Thing"));

        BookMetadata m = EpubMetadataReader.read(epub);

        assertEquals("Untitled Thing", m.title());
        assertEquals(List.of(), m.authors());
        assertNull(m.series());
        assertNull(m.seriesIndex());
        assertNull(m.publisher());
        assertEquals("", m.authorsJoined());
        assertEquals("", m.primaryAuthor());
    }

    @Test
    void keepsEveryAuthorInOrder() throws Exception {
        String opf = TestEpubs.bareOpf("Two Authors").replace("</metadata>",
                "<dc:creator>First Author</dc:creator><dc:creator>Second Author</dc:creator></metadata>");
        Path epub = TestEpubs.write(dir.resolve("d.epub"), opf);

        BookMetadata m = EpubMetadataReader.read(epub);

        assertEquals(List.of("First Author", "Second Author"), m.authors());
        assertEquals("First Author, Second Author", m.authorsJoined());
        assertEquals("First Author", m.primaryAuthor());
    }

    @Test
    void emptyCalibreSeriesIsTreatedAsNoSeries() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("e.epub"), TestEpubs.calibreOpf("T", "A", "", ""));

        BookMetadata m = EpubMetadataReader.read(epub);

        assertNull(m.series());
        assertNull(m.seriesIndex());
    }

    @Test
    void decodesNonAsciiText() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("f.epub"),
                TestEpubs.calibreOpf("Les Misérables – 日本語", "Victor Hugo &amp; Co", "S", "1"));

        BookMetadata m = EpubMetadataReader.read(epub);

        assertEquals("Les Misérables – 日本語", m.title());
        assertEquals(List.of("Victor Hugo & Co"), m.authors());
    }

    @Test
    void archiveWithoutContainerIsRejected() throws Exception {
        Path epub = TestEpubs.writeWithoutContainer(dir.resolve("g.epub"));

        EpubParseException e = assertThrows(EpubParseException.class, () -> EpubMetadataReader.read(epub));

        assertTrue(e.getMessage().contains("container.xml"), e.getMessage());
    }

    @Test
    void missingOpfEntryIsRejected() throws Exception {
        Path epub = dir.resolve("h.epub");
        TestEpubs.write(epub, TestEpubs.bareOpf("x"));
        // Rebuild without the OPF the container points at.
        Path broken = dir.resolve("broken.epub");
        try (var in = new java.util.zip.ZipFile(epub.toFile());
             var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(broken))) {
            for (var entries = in.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                if (entry.getName().equals(TestEpubs.OPF_PATH)) {
                    continue;
                }
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                in.getInputStream(entry).transferTo(out);
                out.closeEntry();
            }
        }

        assertThrows(EpubParseException.class, () -> EpubMetadataReader.read(broken));
    }

    @Test
    void notAZipIsRejectedWithAParseException() throws IOException {
        Path file = dir.resolve("i.epub");
        Files.writeString(file, "this is not a zip file", StandardCharsets.UTF_8);

        assertThrows(EpubParseException.class, () -> EpubMetadataReader.read(file));
    }

    @Test
    void malformedOpfIsRejected() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("j.epub"), "<package><metadata><dc:title>oops");

        assertThrows(EpubParseException.class, () -> EpubMetadataReader.read(epub));
    }

    @Test
    void doctypeEntitiesAreNotResolved() throws Exception {
        String opf = "<?xml version=\"1.0\"?><!DOCTYPE package [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\"><metadata "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>&x;</dc:title></metadata></package>";
        Path epub = TestEpubs.write(dir.resolve("k.epub"), opf);

        // Either refusing the DOCTYPE or ignoring the entity is fine; leaking file contents is not.
        try {
            BookMetadata m = EpubMetadataReader.read(epub);
            assertTrue(m.title() == null || !m.title().contains("root:"), "entity was expanded");
        } catch (EpubParseException expected) {
            // refused
        }
    }

    @Test
    void rawMetadataShowsEveryElementIncludingUnknownOnes() throws Exception {
        String opf = TestEpubs.calibreOpf("T", "A", "S", "1")
                .replace("</metadata>", "<meta name=\"custom:thing\" content=\"kept\"/></metadata>");
        Path epub = TestEpubs.write(dir.resolve("l.epub"), opf);

        String raw = EpubMetadataReader.readRawMetadata(epub);

        assertTrue(raw.contains("calibre:series"), raw);
        assertTrue(raw.contains("custom:thing"), raw);
        assertTrue(raw.contains("<dc:title>T</dc:title>") || raw.contains(">T<"), raw);
    }

    @Test
    void extraEntriesDoNotAffectReading() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("m.epub"), TestEpubs.bareOpf("Only Title"),
                Map.of("OEBPS/a.bin", TestEpubs.randomBytes(1000)));

        assertEquals("Only Title", EpubMetadataReader.read(epub).title());
    }
}
