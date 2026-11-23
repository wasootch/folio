package ca.scottadams.folio.epub;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds small but realistic EPUB files for tests. */
final class TestEpubs {

    static final String OPF_PATH = "OEBPS/content.opf";

    private TestEpubs() {
    }

    private static final String CONTAINER = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
            """;

    /** Calibre style: dc:* elements plus calibre:series / calibre:series_index metas. */
    static String calibreOpf(String title, String author, String series, String index) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    <dc:title>%s</dc:title>
                    <dc:creator opf:role="aut">%s</dc:creator>
                    <dc:identifier id="uid">urn:uuid:1234</dc:identifier>
                    <dc:publisher>Tor</dc:publisher>
                    <dc:date>2010-08-31T00:00:00+00:00</dc:date>
                    <dc:language>en</dc:language>
                    <meta name="calibre:series" content="%s"/>
                    <meta name="calibre:series_index" content="%s"/>
                  </metadata>
                  <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
                """.formatted(title, author, series, index);
    }

    /** EPUB 3 style: belongs-to-collection with a refining group-position. */
    static String epub3Opf(String title, String author, String series, String position) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">urn:uuid:5678</dc:identifier>
                    <dc:title>%s</dc:title>
                    <dc:creator id="a1">%s</dc:creator>
                    <dc:language>en-US</dc:language>
                    <meta property="belongs-to-collection" id="c1">%s</meta>
                    <meta refines="#c1" property="collection-type">series</meta>
                    <meta refines="#c1" property="group-position">%s</meta>
                  </metadata>
                  <manifest><item id="c1x" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1x"/></spine>
                </package>
                """.formatted(title, author, series, position);
    }

    /** Just a title and an identifier. */
    static String bareOpf(String title) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>%s</dc:title>
                    <dc:identifier id="uid">urn:uuid:9999</dc:identifier>
                  </metadata>
                  <manifest/>
                  <spine/>
                </package>
                """.formatted(title);
    }

    /** Incompressible bytes, so a recompressing rewrite would visibly change the compressed size. */
    static byte[] randomBytes(int length) {
        byte[] data = new byte[length];
        new Random(42).nextBytes(data);
        return data;
    }

    /** The extra (non-OPF) entries every test book carries. */
    static Map<String, byte[]> payload() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OEBPS/chapter1.xhtml", ("<html><body>" + "Hello world. ".repeat(2000) + "</body></html>")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("OEBPS/cover.jpg", randomBytes(60_000));
        return entries;
    }

    /** Writes a valid EPUB: {@code mimetype} first and stored, then container.xml, the OPF and the payload. */
    static Path write(Path file, String opf) throws IOException {
        return write(file, opf, payload());
    }

    static Path write(Path file, String opf, Map<String, byte[]> extra) throws IOException {
        try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(raw)) {
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry stored = new ZipEntry("mimetype");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(mimetype.length);
            stored.setCompressedSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            stored.setCrc(crc.getValue());
            zip.putNextEntry(stored);
            zip.write(mimetype);
            zip.closeEntry();

            put(zip, "META-INF/container.xml", CONTAINER.getBytes(StandardCharsets.UTF_8));
            put(zip, OPF_PATH, opf.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : extra.entrySet()) {
                put(zip, e.getKey(), e.getValue());
            }
        }
        return file;
    }

    /** An archive with no container.xml, so it isn't an EPUB at all. */
    static Path writeWithoutContainer(Path file) throws IOException {
        try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(raw)) {
            put(zip, "readme.txt", "not an epub".getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
