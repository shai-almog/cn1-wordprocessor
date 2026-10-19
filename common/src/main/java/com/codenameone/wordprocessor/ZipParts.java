package com.codenameone.wordprocessor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Reads and writes a package's parts as a ZIP archive. The archive format is done here, with
 * the platform's Deflater/Inflater for the compressed data: java.util.zip's streams compute
 * CRC32 through a static table read per byte, which made it the largest cost of a native DOCX
 * save, and they copy every entry through small intermediate buffers.
 */
public final class ZipParts {
  private ZipParts() {}

  static final int MAX_ENTRY = 32 * 1024 * 1024, MAX_TOTAL = 128 * 1024 * 1024, MAX_ENTRIES = 4096;

  static void write(Map<String, byte[]> parts, OutputStream out) throws IOException {
    var archive = new Bytes(64 * 1024);
    var central = new Bytes(4096);
    var deflater = new Deflater(1, true); // fastest: XML compresses well at any level
    byte[] buffer = new byte[64 * 1024];
    int count = 0;
    try {
      for (var e : parts.entrySet()) {
        byte[] name = utf8(e.getKey()), data = e.getValue();
        int crc;
        Bytes packed;
        if (data.length >= 2 * CHUNK) {
          Chunk[] chunks = parallel(data);
          crc = chunks[0].crc;
          packed = new Bytes(data.length / 4 + 64);
          for (int i = 0; i < chunks.length; i++) {
            if (i > 0) crc = combine(crc, chunks[i].crc, chunks[i].length);
            packed.write(chunks[i].out.array(), 0, chunks[i].out.size());
          }
        } else {
          crc = crc(data, 0, data.length);
          deflater.reset();
          deflater.setInput(data, 0, data.length);
          deflater.finish();
          packed = new Bytes(data.length / 4 + 64);
          while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer, 0, buffer.length));
        }
        boolean stored = packed.size() >= data.length;
        int method = stored ? 0 : 8, size = stored ? data.length : packed.size();
        int offset = archive.size();
        // Fixed 1980-01-01 timestamps: identical documents save byte-identical archives.
        archive.int32(0x04034b50).int16(20).int16(0x0800).int16(method).int16(0).int16(DOS_DATE);
        archive.int32(crc).int32(size).int32(data.length).int16(name.length).int16(0);
        archive.write(name, 0, name.length);
        if (stored) archive.write(data, 0, data.length);
        else archive.write(packed.array(), 0, packed.size());
        central.int32(0x02014b50).int16(20).int16(20).int16(0x0800).int16(method).int16(0);
        central.int16(DOS_DATE).int32(crc).int32(size).int32(data.length).int16(name.length);
        central.int16(0).int16(0).int16(0).int16(0).int32(0).int32(offset);
        central.write(name, 0, name.length);
        count++;
      }
    } finally {
      deflater.end();
    }
    int directory = archive.size();
    archive.write(central.array(), 0, central.size());
    archive.int32(0x06054b50).int16(0).int16(0).int16(count).int16(count);
    archive.int32(central.size()).int32(directory).int16(0);
    out.write(archive.array(), 0, archive.size());
    out.flush();
  }

  /**
   * The parts of a ZIP archive by name, in directory order, using the central directory as
   * Office does. Entry names are checked for traversal and duplicates, sizes are bounded before
   * anything is inflated, and every entry's CRC is verified. Input that is not a ZIP archive
   * has no parts.
   */
  static LinkedHashMap<String, byte[]> read(InputStream input) throws IOException {
    return read(input, null);
  }

  /**
   * CRC checks of large parts that run on a background thread while the caller parses them.
   * The caller must call {@link #await()} before trusting the result, and on any failure, so
   * a corrupt package reports its corruption rather than a parse error.
   */
  static final class Deferred {
    private final java.util.List<Object[]> pending = new java.util.ArrayList<>();
    private Thread thread;
    private volatile String corrupt;

    private void start() {
      if (pending.isEmpty()) return;
      thread =
          new Thread(
              () -> {
                for (Object[] p : pending) {
                  byte[] b = (byte[]) p[1];
                  if (crc(b, 0, b.length) != (Integer) p[2]) {
                    corrupt = (String) p[0];
                    return;
                  }
                }
              },
              "zip-crc");
      thread.start();
    }

    void await() throws IOException {
      if (thread != null)
        try {
          thread.join();
        } catch (InterruptedException interrupted) {
          throw new IOException("Interrupted while verifying package");
        }
      if (corrupt != null) throw new IOException("Corrupt package entry: " + corrupt);
    }
  }

  static LinkedHashMap<String, byte[]> read(InputStream input, Deferred deferred) throws IOException {
    byte[] zip = readAll(input);
    var parts = new LinkedHashMap<String, byte[]>();
    int end = -1;
    for (int i = zip.length - 22; i >= 0 && i >= zip.length - 22 - 0xffff; i--)
      if (int32(zip, i) == 0x06054b50) {
        end = i;
        break;
      }
    if (end < 0) return parts;
    int entries = int16(zip, end + 10), at = int32(zip, end + 16);
    if (entries > MAX_ENTRIES) throw new IOException("Too many package entries");
    long total = 0;
    var inflater = new Inflater(true);
    try {
      for (int i = 0; i < entries; i++) {
        if (at < 0 || at + 46 > zip.length || int32(zip, at) != 0x02014b50)
          throw new IOException("Invalid package directory");
        int flags = int16(zip, at + 8), method = int16(zip, at + 10), crc = int32(zip, at + 16);
        long packed = int32(zip, at + 20) & 0xffffffffL, size = int32(zip, at + 24) & 0xffffffffL;
        int nameLength = int16(zip, at + 28), skip = int16(zip, at + 30) + int16(zip, at + 32);
        int local = int32(zip, at + 42);
        if (at + 46 + nameLength > zip.length) throw new IOException("Invalid package directory");
        var name = new String(zip, at + 46, nameLength, "UTF-8");
        at += 46 + nameLength + skip;
        if (name.startsWith("/")
            || name.contains("..")
            || name.contains("\\")
            || parts.containsKey(name)) throw new IOException("Invalid or duplicate package path");
        if (name.endsWith("/")) continue;
        if ((flags & 1) != 0) throw new IOException("Encrypted package entries are not supported");
        total += size;
        if (size > MAX_ENTRY || total > MAX_TOTAL) throw new IOException("DOCX package exceeds size limit");
        if (local < 0 || local + 30 > zip.length || int32(zip, local) != 0x04034b50)
          throw new IOException("Invalid package entry: " + name);
        int data = local + 30 + int16(zip, local + 26) + int16(zip, local + 28);
        if (data + packed > zip.length) throw new IOException("Truncated package entry: " + name);
        byte[] bytes = new byte[(int) size];
        if (method == 0) {
          if (packed != size) throw new IOException("Invalid package entry: " + name);
          System.arraycopy(zip, data, bytes, 0, bytes.length);
        } else if (method == 8) {
          inflater.reset();
          inflater.setInput(zip, data, (int) packed);
          int n = 0;
          try {
            while (n < bytes.length) {
              int read = inflater.inflate(bytes, n, bytes.length - n);
              if (read == 0 && (inflater.finished() || inflater.needsInput())) break;
              n += read;
            }
          } catch (DataFormatException e) {
            throw new IOException("Corrupt package entry: " + name);
          }
          if (n != bytes.length) throw new IOException("Corrupt package entry: " + name);
        } else throw new IOException("Unsupported compression in package entry: " + name);
        if (deferred != null && bytes.length >= CHUNK) deferred.pending.add(new Object[] {name, bytes, crc});
        else if (crc(bytes, 0, bytes.length) != crc) throw new IOException("Corrupt package entry: " + name);
        parts.put(name, bytes);
      }
    } finally {
      inflater.end();
    }
    if (deferred != null) deferred.start();
    return parts;
  }

  private static byte[] readAll(InputStream input) throws IOException {
    var out = new Bytes(64 * 1024);
    byte[] buffer = new byte[64 * 1024];
    int n;
    while ((n = input.read(buffer)) != -1) {
      if (out.size() + n > MAX_TOTAL) throw new IOException("DOCX package exceeds size limit");
      out.write(buffer, 0, n);
    }
    return out.size() == out.array().length ? out.array() : java.util.Arrays.copyOf(out.array(), out.size());
  }

  private static int int16(byte[] b, int at) {
    return at + 2 > b.length ? 0 : (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
  }

  private static int int32(byte[] b, int at) {
    return at < 0 || at + 4 > b.length
        ? 0
        : (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
  }

  private static final int DOS_DATE = (0 << 9) | (1 << 5) | 1; // 1980-01-01; time 00:00
  /** Slicing-by-8 tables: CRC[k * 256 + b] advances byte b by k further positions. */
  private static final int[] CRC = new int[8 * 256];

  static {
    for (int n = 0; n < 256; n++) {
      int c = n;
      for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC[n] = c;
    }
    for (int n = 0; n < 256; n++)
      for (int k = 1; k < 8; k++) {
        int c = CRC[(k - 1) * 256 + n];
        CRC[k * 256 + n] = CRC[c & 0xff] ^ (c >>> 8);
      }
  }

  /**
   * CRC-32 as ZIP uses it, eight bytes per step: a per-byte loop over a static table made CRC
   * the largest cost of a native save, since every array read is checked on ParparVM.
   */
  static int crc(byte[] b, int offset, int length) {
    int[] t = CRC;
    int c = ~0, i = offset, end = offset + length;
    for (int stop = end - 7; i < stop; i += 8) {
      int x = c ^ ((b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | b[i + 3] << 24);
      c = t[0x700 + (x & 0xff)]
          ^ t[0x600 + (x >>> 8 & 0xff)]
          ^ t[0x500 + (x >>> 16 & 0xff)]
          ^ t[0x400 + (x >>> 24)]
          ^ t[0x300 + (b[i + 4] & 0xff)]
          ^ t[0x200 + (b[i + 5] & 0xff)]
          ^ t[0x100 + (b[i + 6] & 0xff)]
          ^ t[b[i + 7] & 0xff];
    }
    for (; i < end; i++) c = t[(c ^ b[i]) & 0xff] ^ (c >>> 8);
    return ~c;
  }

  /** Large parts are split into chunks that are checksummed and compressed on several threads. */
  static final int CHUNK = 128 * 1024;

  private static final class Chunk implements Runnable {
    final byte[] data;
    final int offset, length;
    final boolean last;
    final Bytes out;
    int crc;
    Throwable failure;

    Chunk(byte[] data, int offset, int length, boolean last) {
      this.data = data;
      this.offset = offset;
      this.length = length;
      this.last = last;
      out = new Bytes(length / 4 + 64);
    }

    @Override
    public void run() {
      try {
        crc = crc(data, offset, length);
        // Raw deflate streams concatenate when every chunk but the last ends on a sync flush
        // (byte aligned, no final-block bit), as pigz does; each chunk starts a fresh window.
        var deflater = new Deflater(1, true);
        byte[] buffer = new byte[64 * 1024];
        try {
          deflater.setInput(data, offset, length);
          if (last) {
            deflater.finish();
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer, 0, buffer.length));
          } else {
            int n;
            do {
              n = deflater.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
              out.write(buffer, 0, n);
            } while (n == buffer.length || !deflater.needsInput());
          }
        } finally {
          deflater.end();
        }
      } catch (Throwable t) {
        failure = t;
      }
    }
  }

  private static Chunk[] parallel(byte[] data) throws IOException {
    int count = (data.length + CHUNK - 1) / CHUNK;
    var chunks = new Chunk[count];
    for (int i = 0; i < count; i++) {
      int start = i * CHUNK;
      chunks[i] = new Chunk(data, start, Math.min(CHUNK, data.length - start), i == count - 1);
    }
    // The portable API has no processor count; every supported desktop and phone has at least
    // two cores and three workers (this thread plus two) saturate a typical one without waste.
    int workers = Math.min(count, 3);
    var next = new int[1];
    Runnable worker =
        () -> {
          while (true) {
            int i;
            synchronized (next) {
              i = next[0]++;
            }
            if (i >= count) return;
            chunks[i].run();
          }
        };
    var threads = new Thread[workers - 1];
    for (int i = 0; i < threads.length; i++) (threads[i] = new Thread(worker, "zip-deflate")).start();
    worker.run();
    for (Thread t : threads)
      try {
        t.join();
      } catch (InterruptedException interrupted) {
        throw new IOException("Interrupted while compressing");
      }
    for (Chunk c : chunks) if (c.failure != null) throw new IOException("Compression failed: " + c.failure);
    return chunks;
  }

  /** The CRC of a + b from their CRCs and b's length (zlib's crc32_combine). */
  static int combine(int crcA, int crcB, long lengthB) {
    if (lengthB <= 0) return crcA;
    int[] even = new int[32], odd = new int[32];
    odd[0] = 0xedb88320; // the operator for one zero bit
    for (int i = 1, row = 1; i < 32; i++, row <<= 1) odd[i] = row;
    square(even, odd); // two zero bits
    square(odd, even); // four zero bits
    int a = crcA;
    do {
      square(even, odd);
      if ((lengthB & 1) != 0) a = times(even, a);
      lengthB >>= 1;
      if (lengthB == 0) break;
      square(odd, even);
      if ((lengthB & 1) != 0) a = times(odd, a);
      lengthB >>= 1;
    } while (lengthB != 0);
    return a ^ crcB;
  }

  private static int times(int[] matrix, int vector) {
    int sum = 0;
    for (int i = 0; vector != 0; i++, vector >>>= 1) if ((vector & 1) != 0) sum ^= matrix[i];
    return sum;
  }

  private static void square(int[] square, int[] matrix) {
    for (int i = 0; i < 32; i++) square[i] = times(matrix, matrix[i]);
  }

  /** A growable byte buffer whose array is read without a copy. */
  private static final class Bytes extends ByteArrayOutputStream {
    Bytes(int size) {
      super(size);
    }

    byte[] array() {
      return buf;
    }

    Bytes int16(int v) {
      write(v & 0xff);
      write(v >>> 8 & 0xff);
      return this;
    }

    Bytes int32(int v) {
      return int16(v).int16(v >>> 16);
    }
  }

  /**
   * UTF-8 bytes of an XML part. ParparVM's String.getBytes goes through generic charset code
   * per character; part XML is almost all ASCII, which this copies directly.
   */
  public static byte[] utf8(CharSequence text) {
    if (text instanceof String || text instanceof StringBuilder) {
      // A compact (Latin-1) String encodes natively on ParparVM without widening to char[].
      // One byte per character means every character was ASCII, or an unpaired surrogate
      // written as '?' as below, so the bytes match this encoder exactly.
      String string = text.toString();
      try {
        byte[] bytes = string.getBytes("UTF-8");
        if (bytes.length == string.length()) return bytes;
      } catch (java.io.UnsupportedEncodingException impossible) {
        // UTF-8 is always supported; fall through to the portable encoder.
      }
    }
    int n = text.length();
    char[] c = new char[n];
    if (text instanceof String string) string.getChars(0, n, c, 0);
    else if (text instanceof StringBuilder builder) builder.getChars(0, n, c, 0);
    else for (int i = 0; i < n; i++) c[i] = text.charAt(i);
    int size = n;
    for (int i = 0; i < n; i++) {
      char ch = c[i];
      if (ch < 0x80) continue;
      if (ch < 0x800) size += 1;
      else if (Character.isHighSurrogate(ch) && i + 1 < n && Character.isLowSurrogate(c[i + 1])) {
        size += 2; // two chars, four bytes
        i++;
      } else if (ch < 0xd800 || ch > 0xdfff) size += 2;
    }
    byte[] out = new byte[size];
    int at = 0;
    for (int i = 0; i < n; i++) {
      char ch = c[i];
      if (ch < 0x80) out[at++] = (byte) ch;
      else if (ch < 0x800) {
        out[at++] = (byte) (0xc0 | ch >> 6);
        out[at++] = (byte) (0x80 | ch & 0x3f);
      } else if (Character.isHighSurrogate(ch) && i + 1 < n && Character.isLowSurrogate(c[i + 1])) {
        int cp = Character.toCodePoint(ch, c[++i]);
        out[at++] = (byte) (0xf0 | cp >> 18);
        out[at++] = (byte) (0x80 | cp >> 12 & 0x3f);
        out[at++] = (byte) (0x80 | cp >> 6 & 0x3f);
        out[at++] = (byte) (0x80 | cp & 0x3f);
      } else if (ch >= 0xd800 && ch <= 0xdfff) {
        out[at++] = '?'; // an unpaired surrogate, as String.getBytes writes it
      } else {
        out[at++] = (byte) (0xe0 | ch >> 12);
        out[at++] = (byte) (0x80 | ch >> 6 & 0x3f);
        out[at++] = (byte) (0x80 | ch & 0x3f);
      }
    }
    return out;
  }
}
