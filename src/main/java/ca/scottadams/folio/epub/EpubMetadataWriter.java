package ca.scottadams.folio.epub;

import ca.scottadams.folio.logging.AppLogging;
import ca.scottadams.folio.model.BookMetadata;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes edited metadata back into an EPUB's OPF package document.
 * <p>
 * Only the fields that differ from what is currently in the file are touched, so
 * everything else in the OPF (manifest, spine, unrelated metadata, attributes such as
 * {@code opf:file-as}) is left exactly as it was. The original file is copied to
 * {@code <name>.epub.bak} first (never overwriting an existing backup, so the very
 * first original is the one kept), and the new archive is built beside the original
 * and swapped in only once it has been written completely.
 */
public final class EpubMetadataWriter {

    private static final Logger LOG = Logger.getLogger(AppLogging.LOGGER_NAME);

    private static final String DC_NS = "http://purl.org/dc/elements/1.1/";

    private EpubMetadataWriter() {
    }

    /** Where {@link #write} puts the backup of {@code epub}. */
    public static Path backupPath(Path epub) {
        return epub.resolveSibling(epub.getFileName() + ".bak");
    }

    public static void write(Path epub, BookMetadata edited) throws EpubParseException {
        Path backup = backupPath(epub);
        Path temp = epub.resolveSibling(epub.getFileName() + ".folio-tmp");
        LOG.info(() -> "Writing metadata into " + epub);
        long started = System.nanoTime();
        try {
            try (ZipFile zip = new ZipFile(epub.toFile())) {
                String opfPath = EpubMetadataReader.findOpfPath(zip);
                Document opf = EpubMetadataReader.parseXml(zip, opfPath);
                apply(opf, edited);
                byte[] newOpf = serialize(opf);
                if (!buildFast(epub, temp, opfPath, newOpf, zip.size())) {
                    LOG.fine(() -> "Using the general (recompressing) rewrite for " + epub.getFileName());
                    copyWithReplacedEntry(zip, temp, opfPath, newOpf);
                }
            }
            swapIn(epub, temp, backup);
            LOG.fine(() -> "Saved " + epub.getFileName() + " in " + (System.nanoTime() - started) / 1_000_000 + " ms");
        } catch (EpubParseException e) {
            deleteQuietly(temp);
            throw e;
        } catch (IOException | TransformerException e) {
            deleteQuietly(temp);
            throw new EpubParseException("Could not save metadata: " + e.getMessage(), e);
        }
    }

    /**
     * Builds {@code temp} by copying the compressed entries untouched (see {@link ZipRawCopy}) and checks
     * the result opens and parses. Returns false, leaving no file behind, if that route isn't available or
     * its output doesn't check out; the caller then falls back to the general rewrite.
     */
    private static boolean buildFast(Path epub, Path temp, String opfPath, byte[] newOpf, int entryCount) {
        try {
            if (!ZipRawCopy.replaceEntry(epub, temp, opfPath, newOpf)) {
                return false;
            }
            try (ZipFile check = new ZipFile(temp.toFile())) {
                if (check.size() != entryCount) {
                    throw new IOException("entry count " + check.size() + " != " + entryCount);
                }
            }
            EpubMetadataReader.read(temp);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING,
                    "Fast rewrite of " + epub.getFileName() + " failed; falling back: " + e.getMessage(), e);
            deleteQuietly(temp);
            return false;
        }
    }

    /**
     * Puts the finished {@code temp} in place of {@code epub}. The first time, the original is renamed to
     * the backup (instant, unlike copying a large file over the network); later saves leave the backup alone.
     */
    private static void swapIn(Path epub, Path temp, Path backup) throws IOException {
        if (Files.exists(backup)) {
            Files.move(temp, epub, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.move(epub, backup);
        LOG.info(() -> "Kept the original as " + backup);
        try {
            Files.move(temp, epub);
        } catch (IOException e) {
            // Put the original back rather than leave the book missing.
            try {
                Files.move(backup, epub);
            } catch (IOException restore) {
                e.addSuppressed(restore);
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ OPF edits

    private static void apply(Document doc, BookMetadata edited) throws EpubParseException {
        Element metadata = firstMetadataElement(doc);
        BookMetadata current = EpubMetadataReader.extractMetadata(doc);

        if (!Objects.equals(current.title(), edited.title())) {
            setDublinCore(doc, metadata, "title", edited.title(), true);
        }
        if (!current.authors().equals(edited.authors())) {
            setAuthors(doc, metadata, edited.authors());
        }
        if (!Objects.equals(current.series(), edited.series())
                || !Objects.equals(current.seriesIndex(), edited.seriesIndex())) {
            setSeries(doc, metadata, edited.series(), edited.seriesIndex());
        }
        if (!Objects.equals(current.publisher(), edited.publisher())) {
            setDublinCore(doc, metadata, "publisher", edited.publisher(), true);
        }
        if (!Objects.equals(current.publishedDate(), edited.publishedDate())) {
            setDublinCore(doc, metadata, "date", edited.publishedDate(), true);
        }
        if (!Objects.equals(current.language(), edited.language())) {
            setDublinCore(doc, metadata, "language", edited.language(), true);
        }
        if (!Objects.equals(current.identifier(), edited.identifier())) {
            // The package's unique-identifier points at an identifier, so never delete it.
            setDublinCore(doc, metadata, "identifier", edited.identifier(), false);
        }
        if (!Objects.equals(current.description(), edited.description())) {
            setDublinCore(doc, metadata, "description", edited.description(), true);
        }
    }

    private static Element firstMetadataElement(Document doc) throws EpubParseException {
        var nodes = doc.getElementsByTagNameNS("*", "metadata");
        if (nodes.getLength() == 0) {
            throw new EpubParseException("The package document has no <metadata> element");
        }
        return (Element) nodes.item(0);
    }

    /** Sets the first {@code dc:<local>} element, creating it if needed; a null value removes it. */
    private static void setDublinCore(Document doc, Element metadata, String local, String value,
                                      boolean removable) {
        List<Element> existing = children(metadata, DC_NS, local);
        if (value == null) {
            if (removable && !existing.isEmpty()) {
                metadata.removeChild(existing.get(0));
            }
            return;
        }
        if (!existing.isEmpty()) {
            existing.get(0).setTextContent(value);
            return;
        }
        Element element = doc.createElementNS(DC_NS, dcName(metadata, local));
        element.setTextContent(value);
        if (local.equals("identifier")) {
            element.setAttribute("id", "folio-id");
            Element pkg = doc.getDocumentElement();
            if (pkg.getAttribute("unique-identifier").isBlank()) {
                pkg.setAttribute("unique-identifier", "folio-id");
            }
        }
        metadata.appendChild(element);
    }

    /**
     * Replaces the author {@code dc:creator} elements (those with no role or role "aut") with
     * {@code authors}, in order. Existing elements whose text still matches are reused so their
     * attributes survive; creators with other roles (illustrator, translator…) are untouched.
     */
    private static void setAuthors(Document doc, Element metadata, List<String> authors) {
        List<Element> existing = new ArrayList<>();
        for (Element creator : children(metadata, DC_NS, "creator")) {
            if (isAuthorRole(creator)) {
                existing.add(creator);
            }
        }

        // A marker keeps the new authors where the old ones were.
        Node marker = doc.createTextNode("");
        if (existing.isEmpty()) {
            metadata.appendChild(marker);
        } else {
            metadata.insertBefore(marker, existing.get(0));
        }

        List<Element> ordered = new ArrayList<>();
        List<Element> unused = new ArrayList<>(existing);
        for (String name : authors) {
            Element reuse = unused.stream()
                    .filter(e -> e.getTextContent().trim().equals(name))
                    .findFirst().orElse(null);
            if (reuse != null) {
                unused.remove(reuse);
                ordered.add(reuse);
            } else {
                Element created = doc.createElementNS(DC_NS, dcName(metadata, "creator"));
                created.setTextContent(name);
                ordered.add(created);
            }
        }

        for (Element old : existing) {
            metadata.removeChild(old);
        }
        for (Element element : ordered) {
            metadata.insertBefore(element, marker);
        }
        metadata.removeChild(marker);
    }

    private static boolean isAuthorRole(Element creator) {
        String role = creator.getAttributeNS("http://www.idpf.org/2007/opf", "role");
        if (role.isBlank()) {
            role = creator.getAttribute("role");
        }
        return role.isBlank() || role.equalsIgnoreCase("aut");
    }

    /**
     * Updates series info in whichever convention the file already uses (Calibre
     * {@code calibre:series} metas and/or EPUB3 {@code belongs-to-collection}); if it has
     * neither, adds the Calibre form, which every reader understands. A null series removes both.
     */
    private static void setSeries(Document doc, Element metadata, String series, Double index) {
        List<Element> metas = children(metadata, null, "meta");
        Element calibreSeries = withAttribute(metas, "name", "calibre:series");
        Element calibreIndex = withAttribute(metas, "name", "calibre:series_index");
        Element collection = withAttribute(metas, "property", "belongs-to-collection");

        if (series == null) {
            remove(metadata, calibreSeries);
            remove(metadata, calibreIndex);
            if (collection != null) {
                String id = collection.getAttribute("id");
                if (!id.isBlank()) {
                    for (Element meta : metas) {
                        if (("#" + id).equals(meta.getAttribute("refines"))) {
                            metadata.removeChild(meta);
                        }
                    }
                }
                metadata.removeChild(collection);
            }
            return;
        }

        if (calibreSeries == null && collection == null) {
            calibreSeries = newMeta(doc, metadata);
            calibreSeries.setAttribute("name", "calibre:series");
            metadata.appendChild(calibreSeries);
        }

        if (calibreSeries != null) {
            calibreSeries.setAttribute("content", series);
            if (index == null) {
                remove(metadata, calibreIndex);
            } else {
                if (calibreIndex == null) {
                    calibreIndex = newMeta(doc, metadata);
                    calibreIndex.setAttribute("name", "calibre:series_index");
                    metadata.appendChild(calibreIndex);
                }
                calibreIndex.setAttribute("content", String.valueOf(index));
            }
        }

        if (collection != null) {
            collection.setTextContent(series);
            String id = collection.getAttribute("id");
            if (id.isBlank()) {
                id = "folio-series";
                collection.setAttribute("id", id);
            }
            String refines = "#" + id;
            Element position = null;
            for (Element meta : metas) {
                if (refines.equals(meta.getAttribute("refines"))
                        && "group-position".equals(meta.getAttribute("property"))) {
                    position = meta;
                }
            }
            if (index == null) {
                remove(metadata, position);
            } else {
                if (position == null) {
                    position = newMeta(doc, metadata);
                    position.setAttribute("refines", refines);
                    position.setAttribute("property", "group-position");
                    metadata.appendChild(position);
                }
                position.setTextContent(index == Math.floor(index)
                        ? String.valueOf(index.intValue()) : String.valueOf(index));
            }
        }
    }

    // ------------------------------------------------------------------ DOM helpers

    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())
                    && (namespace == null || namespace.equals(element.getNamespaceURI()))) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element withAttribute(List<Element> elements, String attribute, String value) {
        return elements.stream().filter(e -> value.equals(e.getAttribute(attribute))).findFirst().orElse(null);
    }

    private static void remove(Element parent, Element child) {
        if (child != null && child.getParentNode() == parent) {
            parent.removeChild(child);
        }
    }

    private static Element newMeta(Document doc, Element metadata) {
        String prefix = metadata.getPrefix();
        String name = prefix == null || prefix.isEmpty() ? "meta" : prefix + ":meta";
        return doc.createElementNS(metadata.getNamespaceURI(), name);
    }

    /** The qualified name to use for a new Dublin Core element, reusing the file's prefix (usually "dc"). */
    private static String dcName(Element metadata, String local) {
        String prefix = metadata.lookupPrefix(DC_NS);
        return prefix == null || prefix.isEmpty() ? local : prefix + ":" + local;
    }

    // ------------------------------------------------------------------ zip output

    private static byte[] serialize(Document doc) throws TransformerException {
        doc.setXmlStandalone(true);
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    /** Copies every entry of {@code source} in order, substituting {@code replacementBytes} for {@code replaced}. */
    private static void copyWithReplacedEntry(ZipFile source, Path target, String replaced,
                                              byte[] replacementBytes) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry original = entries.nextElement();
                boolean isReplacement = original.getName().equals(replaced);

                ZipEntry copy = new ZipEntry(original.getName());
                copy.setTime(original.getTime());
                if (original.getMethod() == ZipEntry.STORED && !isReplacement) {
                    // "mimetype" must stay the first, uncompressed entry of an EPUB.
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(original.getSize());
                    copy.setCompressedSize(original.getSize());
                    copy.setCrc(original.getCrc());
                } else {
                    copy.setMethod(ZipEntry.DEFLATED);
                }

                out.putNextEntry(copy);
                if (isReplacement) {
                    out.write(replacementBytes);
                } else if (!original.isDirectory()) {
                    try (InputStream in = source.getInputStream(original)) {
                        in.transferTo(out);
                    }
                }
                out.closeEntry();
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warning(() -> "Could not delete temporary file " + path + ": " + e.getMessage());
        }
    }
}
