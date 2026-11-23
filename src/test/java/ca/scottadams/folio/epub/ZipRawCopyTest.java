package ca.scottadams.folio.epub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipRawCopyTest {

    @TempDir
    Path dir;

    private Path book() throws Exception {
        return TestEpubs.write(dir.resolve("in.epub"), TestEpubs.bareOpf("Old"));
    }

    @Test
    void replacesOneEntryAndCopiesTheRestByteForByte() throws Exception {
        Path in = book();
        Path out = dir.resolve("out.epub");
        byte[] replacement = "<replaced/>".getBytes(StandardCharsets.UTF_8);

        assertTrue(ZipRawCopy.replaceEntry(in, out, TestEpubs.OPF_PATH, replacement));

        assertEquals(EpubMetadataWriterTest.entryNames(in), EpubMetadataWriterTest.entryNames(out));
        try (ZipFile src = new ZipFile(in.toFile()); ZipFile dst = new ZipFile(out.toFile())) {
            assertArrayEquals(replacement, EpubMetadataWriterTest.read(dst, TestEpubs.OPF_PATH));
            for (ZipEntry e : java.util.Collections.list(src.entries())) {
                if (e.getName().equals(TestEpubs.OPF_PATH)) {
                    continue;
                }
                ZipEntry copy = dst.getEntry(e.getName());
                assertEquals(e.getCompressedSize(), copy.getCompressedSize(), e.getName());
                assertEquals(e.getCrc(), copy.getCrc(), e.getName());
                assertEquals(e.getMethod(), copy.getMethod(), e.getName());
                assertArrayEquals(EpubMetadataWriterTest.read(src, e.getName()),
                        EpubMetadataWriterTest.read(dst, e.getName()), e.getName());
            }
        }
    }

    @Test
    void handlesEntriesWrittenWithDataDescriptors() throws Exception {
        // ZipOutputStream writes deflated entries of unknown size with a trailing data descriptor.
        Path in = book();
        try (ZipFile zip = new ZipFile(in.toFile())) {
            assertTrue(zip.stream().anyMatch(e -> e.getMethod() == ZipEntry.DEFLATED));
        }
        Path out = dir.resolve("out.epub");

        assertTrue(ZipRawCopy.replaceEntry(in, out, TestEpubs.OPF_PATH, "<x/>".getBytes(StandardCharsets.UTF_8)));

        try (ZipFile zip = new ZipFile(out.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                EpubMetadataWriterTest.read(zip, e.getName()); // CRC check on every entry
            }
        }
    }

    @Test
    void declinesWhenTheEntryIsNotThere() throws Exception {
        Path out = dir.resolve("out.epub");

        assertFalse(ZipRawCopy.replaceEntry(book(), out, "no/such/entry.opf", new byte[0]));
    }

    @Test
    void declinesAFileThatIsNotAZip() throws Exception {
        Path junk = dir.resolve("junk.epub");
        Files.writeString(junk, "definitely not a zip archive, just some text", StandardCharsets.UTF_8);

        assertFalse(ZipRawCopy.replaceEntry(junk, dir.resolve("out.epub"), "x", new byte[0]));
    }

    @Test
    void declinesAMultiDiskArchive() throws Exception {
        Path in = book();
        byte[] bytes = Files.readAllBytes(in);
        int eocd = EpubMetadataWriterTest.lastIndexOfSignature(bytes, 0x06054b50);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(eocd + 4, (short) 2);
        Files.write(in, bytes);

        assertFalse(ZipRawCopy.replaceEntry(in, dir.resolve("out.epub"), TestEpubs.OPF_PATH, new byte[0]));
    }

    @Test
    void declinesEncryptedEntries() throws Exception {
        Path in = book();
        byte[] bytes = Files.readAllBytes(in);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int firstCentralHeader = buffer.getInt(EpubMetadataWriterTest.lastIndexOfSignature(bytes, 0x06054b50) + 16);
        buffer.putShort(firstCentralHeader + 8, (short) (buffer.getShort(firstCentralHeader + 8) | 1));
        Files.write(in, bytes);

        assertFalse(ZipRawCopy.replaceEntry(in, dir.resolve("out.epub"), TestEpubs.OPF_PATH, new byte[0]));
    }

    @Test
    void declinesATruncatedCentralDirectory() throws Exception {
        Path in = book();
        byte[] bytes = Files.readAllBytes(in);
        // Corrupt the signature of the first central-directory header.
        int cd = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .getInt(EpubMetadataWriterTest.lastIndexOfSignature(bytes, 0x06054b50) + 16);
        bytes[cd] = 0;
        Files.write(in, bytes);

        assertFalse(ZipRawCopy.replaceEntry(in, dir.resolve("out.epub"), TestEpubs.OPF_PATH, new byte[0]));
    }
}
