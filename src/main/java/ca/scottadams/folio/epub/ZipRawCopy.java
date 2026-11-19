package ca.scottadams.folio.epub;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Rewrites a zip archive replacing one entry, copying every other entry's <em>compressed bytes</em>
 * untouched. That avoids decompressing and recompressing images and chapters, which is what made
 * saving a large EPUB slow. Entry order is preserved (an EPUB's {@code mimetype} must stay first
 * and stored).
 * <p>
 * Deliberately minimal: plain (non-zip64), unencrypted, single-disk archives only. For anything
 * else {@link #replaceEntry} returns false and the caller uses the slower, general route.
 */
final class ZipRawCopy {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CEN_SIG = 0x02014b50;
    private static final int LOC_SIG = 0x04034b50;
    private static final int FLAG_ENCRYPTED = 0x0001;
    private static final int FLAG_DATA_DESCRIPTOR = 0x0008;

    private ZipRawCopy() {
    }

    private record Entry(byte[] name, int versionMade, int versionNeeded, int flags, int method, int time, int date,
                         long crc, long compressedSize, long size, int internalAttrs, long externalAttrs,
                         long localOffset) {
    }

    /** @return true if {@code target} was written; false if this archive isn't one this class handles */
    static boolean replaceEntry(Path source, Path target, String entryName, byte[] replacement) throws IOException {
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ)) {
            List<Entry> entries = readCentralDirectory(in);
            if (entries == null) {
                return false;
            }
            byte[] wanted = entryName.getBytes(StandardCharsets.UTF_8);
            if (entries.stream().noneMatch(e -> java.util.Arrays.equals(e.name(), wanted))) {
                return false;
            }
            try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                write(in, out, entries, wanted, replacement);
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ reading

    /** The archive's entries in central-directory order, or null if it uses features we don't support. */
    private static List<Entry> readCentralDirectory(FileChannel in) throws IOException {
        long length = in.size();
        int tailLength = (int) Math.min(length, 22 + 65535);
        ByteBuffer tail = le(tailLength);
        readFully(in, tail, length - tailLength);
        int eocd = -1;
        for (int i = tailLength - 22; i >= 0; i--) {
            if (tail.getInt(i) == EOCD_SIG) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            return null;
        }
        int diskNumber = tail.getShort(eocd + 4) & 0xFFFF;
        int cdDisk = tail.getShort(eocd + 6) & 0xFFFF;
        int count = tail.getShort(eocd + 10) & 0xFFFF;
        long cdSize = tail.getInt(eocd + 12) & 0xFFFFFFFFL;
        long cdOffset = tail.getInt(eocd + 16) & 0xFFFFFFFFL;
        if (diskNumber != 0 || cdDisk != 0 || count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL
                || cdOffset + cdSize > length) {
            return null;
        }

        ByteBuffer cd = le((int) cdSize);
        readFully(in, cd, cdOffset);
        List<Entry> entries = new ArrayList<>(count);
        int p = 0;
        for (int i = 0; i < count; i++) {
            if (p + 46 > cd.limit() || cd.getInt(p) != CEN_SIG) {
                return null;
            }
            int flags = cd.getShort(p + 8) & 0xFFFF;
            long compressed = cd.getInt(p + 20) & 0xFFFFFFFFL;
            long size = cd.getInt(p + 24) & 0xFFFFFFFFL;
            int nameLength = cd.getShort(p + 28) & 0xFFFF;
            int extraLength = cd.getShort(p + 30) & 0xFFFF;
            int commentLength = cd.getShort(p + 32) & 0xFFFF;
            long localOffset = cd.getInt(p + 42) & 0xFFFFFFFFL;
            if ((flags & FLAG_ENCRYPTED) != 0 || compressed == 0xFFFFFFFFL || size == 0xFFFFFFFFL
                    || localOffset == 0xFFFFFFFFL || p + 46 + nameLength + extraLength + commentLength > cd.limit()) {
                return null;
            }
            byte[] name = new byte[nameLength];
            cd.get(p + 46, name);
            entries.add(new Entry(name, cd.getShort(p + 4) & 0xFFFF, cd.getShort(p + 6) & 0xFFFF, flags,
                    cd.getShort(p + 10) & 0xFFFF, cd.getShort(p + 12) & 0xFFFF, cd.getShort(p + 14) & 0xFFFF,
                    cd.getInt(p + 16) & 0xFFFFFFFFL, compressed, size, cd.getShort(p + 36) & 0xFFFF,
                    cd.getInt(p + 38) & 0xFFFFFFFFL, localOffset));
            p += 46 + nameLength + extraLength + commentLength;
        }
        return entries;
    }

    // ------------------------------------------------------------------ writing

    private static void write(FileChannel in, FileChannel out, List<Entry> entries, byte[] replacedName,
                              byte[] replacement) throws IOException {
        List<Entry> written = new ArrayList<>(entries.size());

        for (Entry e : entries) {
            long offset = out.position();
            boolean replaced = java.util.Arrays.equals(e.name(), replacedName);
            byte[] data = null;
            Entry w = e;
            if (replaced) {
                data = deflate(replacement);
                CRC32 crc = new CRC32();
                crc.update(replacement);
                w = new Entry(e.name(), e.versionMade(), Math.max(20, e.versionNeeded()), e.flags(), Deflater.DEFLATED,
                        e.time(), e.date(), crc.getValue(), data.length, replacement.length, e.internalAttrs(),
                        e.externalAttrs(), 0);
            }
            int flags = w.flags() & ~FLAG_DATA_DESCRIPTOR; // sizes and CRC are known, so no trailing descriptor

            ByteBuffer header = le(30 + w.name().length);
            header.putInt(LOC_SIG).putShort((short) w.versionNeeded()).putShort((short) flags)
                    .putShort((short) w.method()).putShort((short) w.time()).putShort((short) w.date())
                    .putInt((int) w.crc()).putInt((int) w.compressedSize()).putInt((int) w.size())
                    .putShort((short) w.name().length).putShort((short) 0).put(w.name());
            header.flip();
            writeFully(out, header);

            if (replaced) {
                writeFully(out, ByteBuffer.wrap(data));
            } else {
                copyRaw(in, e, out);
            }
            written.add(new Entry(w.name(), w.versionMade(), w.versionNeeded(), flags, w.method(), w.time(), w.date(),
                    w.crc(), w.compressedSize(), w.size(), w.internalAttrs(), w.externalAttrs(), offset));
        }

        long cdStart = out.position();
        for (Entry w : written) {
            ByteBuffer cen = le(46 + w.name().length);
            cen.putInt(CEN_SIG).putShort((short) w.versionMade()).putShort((short) w.versionNeeded())
                    .putShort((short) w.flags()).putShort((short) w.method()).putShort((short) w.time())
                    .putShort((short) w.date()).putInt((int) w.crc()).putInt((int) w.compressedSize())
                    .putInt((int) w.size()).putShort((short) w.name().length).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putShort((short) w.internalAttrs()).putInt((int) w.externalAttrs())
                    .putInt((int) w.localOffset()).put(w.name());
            cen.flip();
            writeFully(out, cen);
        }
        long cdSize = out.position() - cdStart;
        if (written.size() >= 0xFFFF || out.position() >= 0xFFFFFFFFL) {
            throw new IOException("Archive too large for the plain zip format");
        }
        ByteBuffer eocd = le(22);
        eocd.putInt(EOCD_SIG).putShort((short) 0).putShort((short) 0).putShort((short) written.size())
                .putShort((short) written.size()).putInt((int) cdSize).putInt((int) cdStart).putShort((short) 0);
        eocd.flip();
        writeFully(out, eocd);
    }

    /** Copies an entry's compressed bytes from {@code in} to {@code out} without touching them. */
    private static void copyRaw(FileChannel in, Entry e, FileChannel out) throws IOException {
        ByteBuffer local = le(30);
        readFully(in, local, e.localOffset());
        if (local.getInt(0) != LOC_SIG) {
            throw new IOException("Bad local header for " + new String(e.name(), StandardCharsets.UTF_8));
        }
        long start = e.localOffset() + 30 + (local.getShort(26) & 0xFFFF) + (local.getShort(28) & 0xFFFF);
        long remaining = e.compressedSize();
        long position = start;
        while (remaining > 0) {
            long n = in.transferTo(position, remaining, out);
            if (n <= 0) {
                throw new IOException("Unexpected end of archive while copying "
                        + new String(e.name(), StandardCharsets.UTF_8));
            }
            position += n;
            remaining -= n;
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(data);
            deflater.finish();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                bytes.write(buffer, 0, deflater.deflate(buffer));
            }
            return bytes.toByteArray();
        } finally {
            deflater.end();
        }
    }

    // ------------------------------------------------------------------ io helpers

    private static ByteBuffer le(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void readFully(FileChannel in, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int n = in.read(buffer, position + buffer.position());
            if (n < 0) {
                throw new IOException("Unexpected end of archive");
            }
        }
        buffer.flip();
    }

    private static void writeFully(FileChannel out, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            out.write(buffer);
        }
    }
}
