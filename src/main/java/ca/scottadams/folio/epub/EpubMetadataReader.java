package ca.scottadams.folio.epub;

import ca.scottadams.folio.model.BookMetadata;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.scottadams.folio.logging.AppLogging;

/**
 * Reads title/author/series metadata out of an EPUB's OPF package document.
 * <p>
 * An EPUB is a zip archive. {@code META-INF/container.xml} points at the OPF
 * file, whose {@code <metadata>} block carries Dublin Core fields plus,
 * conventionally, series info as either Calibre-style {@code <meta name="calibre:series" .../>}
 * elements or EPUB3 {@code belongs-to-collection} / {@code group-position} meta.
 */
public final class EpubMetadataReader {

    private static final String OPF_NS = "http://www.idpf.org/2007/opf";
    private static final Logger LOG = Logger.getLogger(AppLogging.LOGGER_NAME);

    private EpubMetadataReader() {
    }

    public static BookMetadata read(Path epubFile) throws EpubParseException {
        LOG.fine(() -> "Reading EPUB metadata: " + epubFile);
        try (ZipFile zip = new ZipFile(epubFile.toFile())) {
            String opfPath = findOpfPath(zip);
            Document opfDoc = parseXml(zip, opfPath);
            BookMetadata metadata = extractMetadata(opfDoc);
            LOG.fine(() -> "Parsed " + epubFile.getFileName() + ": title=" + metadata.title()
                    + ", authors=" + metadata.authors() + ", series=" + metadata.series());
            return metadata;
        } catch (EpubParseException e) {
            LOG.log(Level.WARNING, "Could not parse EPUB metadata for " + epubFile, e);
            throw e;
        } catch (IOException e) {
            EpubParseException wrapped = new EpubParseException("Could not read EPUB: " + e.getMessage(), e);
            LOG.log(Level.WARNING, "Could not read EPUB file " + epubFile, wrapped);
            throw wrapped;
        }
    }

    /**
     * Returns the OPF {@code <metadata>} block exactly as stored in the EPUB, pretty-printed
     * for display. Unlike {@link #read}, nothing is interpreted: every element and attribute
     * (including ones Folio doesn't understand) is shown.
     */
    public static String readRawMetadata(Path epubFile) throws EpubParseException {
        try (ZipFile zip = new ZipFile(epubFile.toFile())) {
            Document opfDoc = parseXml(zip, findOpfPath(zip));
            NodeList nodes = opfDoc.getElementsByTagNameNS("*", "metadata");
            if (nodes.getLength() == 0) {
                throw new EpubParseException("The package document has no <metadata> element");
            }
            Node copy = nodes.item(0).cloneNode(true);
            stripBlankText(copy);

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(copy), new StreamResult(out));
            return out.toString().strip();
        } catch (EpubParseException e) {
            throw e;
        } catch (IOException | TransformerException e) {
            throw new EpubParseException("Could not read raw metadata: " + e.getMessage(), e);
        }
    }

    /** Drops whitespace-only text nodes so the serializer's indentation isn't doubled up. */
    private static void stripBlankText(Node node) {
        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else {
                stripBlankText(child);
            }
            child = next;
        }
    }

    static String findOpfPath(ZipFile zip) throws IOException {
        ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
        if (containerEntry == null) {
            throw new EpubParseException("Not a valid EPUB (missing META-INF/container.xml)");
        }
        Document containerDoc = parseXml(zip, containerEntry);
        NodeList rootfiles = containerDoc.getElementsByTagNameNS("*", "rootfile");
        if (rootfiles.getLength() == 0) {
            throw new EpubParseException("container.xml has no <rootfile> entry");
        }
        Element rootfile = (Element) rootfiles.item(0);
        String fullPath = rootfile.getAttribute("full-path");
        if (fullPath.isBlank()) {
            throw new EpubParseException("container.xml <rootfile> is missing full-path");
        }
        return fullPath;
    }

    static Document parseXml(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            throw new EpubParseException("Missing expected entry: " + entryName);
        }
        return parseXml(zip, entry);
    }

    private static Document parseXml(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            DocumentBuilder builder = newSecureBuilder();
            return builder.parse(in);
        } catch (EpubParseException e) {
            throw e;
        } catch (Exception e) {
            throw new EpubParseException("Could not parse " + entry.getName() + ": " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder newSecureBuilder() throws Exception {
        // Namespace-aware but hardened against XXE/entity-expansion attacks from untrusted EPUB files.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder();
    }

    static BookMetadata extractMetadata(Document opfDoc) {
        String title = firstElementText(opfDoc, "title");
        List<String> authors = extractAuthors(opfDoc);
        String publisher = firstElementText(opfDoc, "publisher");
        String date = firstElementText(opfDoc, "date");
        String language = firstElementText(opfDoc, "language");
        String identifier = firstElementText(opfDoc, "identifier");
        String description = firstElementText(opfDoc, "description");

        String series = null;
        Double seriesIndex = null;

        NodeList metas = opfDoc.getElementsByTagNameNS("*", "meta");

        // EPUB2 / Calibre convention: <meta name="calibre:series" content="..."/>
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            String name = meta.getAttribute("name");
            if ("calibre:series".equals(name)) {
                series = emptyToNull(meta.getAttribute("content"));
            } else if ("calibre:series_index".equals(name)) {
                seriesIndex = parseDouble(meta.getAttribute("content"));
            }
        }

        // EPUB3 convention: <meta property="belongs-to-collection" id="x">Name</meta>
        // paired with <meta property="group-position" refines="#x">N</meta>
        if (series == null) {
            for (int i = 0; i < metas.getLength(); i++) {
                Element meta = (Element) metas.item(i);
                if (!"belongs-to-collection".equals(meta.getAttribute("property"))) {
                    continue;
                }
                series = emptyToNull(meta.getTextContent());
                String id = meta.getAttribute("id");
                if (!id.isBlank()) {
                    String refinesTarget = "#" + id;
                    for (int j = 0; j < metas.getLength(); j++) {
                        Element other = (Element) metas.item(j);
                        if (refinesTarget.equals(other.getAttribute("refines"))
                                && "group-position".equals(other.getAttribute("property"))) {
                            seriesIndex = parseDouble(other.getTextContent());
                        }
                    }
                }
                break;
            }
        }

        return new BookMetadata(title, authors, series, seriesIndex, publisher, date, language, identifier, description);
    }

    private static List<String> extractAuthors(Document opfDoc) {
        NodeList creators = opfDoc.getElementsByTagNameNS("*", "creator");
        List<String> authors = new ArrayList<>();
        for (int i = 0; i < creators.getLength(); i++) {
            Element creator = (Element) creators.item(i);
            String role = creator.getAttributeNS(OPF_NS, "role");
            if (role.isBlank()) {
                role = creator.getAttribute("role");
            }
            // No role attribute at all is common in the wild and is treated as "author".
            if (role.isBlank() || role.equalsIgnoreCase("aut")) {
                String name = creator.getTextContent();
                if (name != null && !name.isBlank()) {
                    authors.add(name.trim());
                }
            }
        }
        return authors;
    }

    private static String firstElementText(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return emptyToNull(nodes.item(0).getTextContent());
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
