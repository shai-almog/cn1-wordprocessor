package com.codenameone.wordprocessor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes a package's parts as a ZIP archive. Compression goes through {@link #codec}, which a
 * platform can replace: on ParparVM the Java deflater is translated JZlib and dominated save
 * time, while the system zlib does the same work several times faster.
 */
public final class ZipParts {
  /** Raw (headerless) deflate and CRC-32. */
  public interface Codec {
    byte[] deflate(byte[] data);

    int crc32(byte[] data);
  }

  public static volatile Codec codec;

  private ZipParts() {}

  static void write(Map<String, byte[]> parts, OutputStream out) throws IOException {
    Codec c = codec == null ? JAVA : codec;
    ByteArrayOutputStream central = new ByteArrayOutputStream(parts.size() * 64);
    long offset = 0;
    for (Map.Entry<String, byte[]> e : parts.entrySet()) {
      byte[] name = e.getKey().getBytes("UTF-8"), data = e.getValue();
      byte[] packed = c.deflate(data);
      int crc = c.crc32(data);
      boolean deflated = packed != null && packed.length < data.length;
      byte[] body = deflated ? packed : data;
      byte[] local = new byte[30 + name.length];
      header(local, 0x04034b50, 4, deflated, crc, body.length, data.length, name);
      out.write(local);
      out.write(body);
      byte[] entry = new byte[46 + name.length];
      header(entry, 0x02014b50, 6, deflated, crc, body.length, data.length, name);
      int32(entry, 42, (int) offset);
      central.write(entry);
      offset += local.length + body.length;
    }
    byte[] end = new byte[22];
    int32(end, 0, 0x06054b50);
    int16(end, 8, parts.size());
    int16(end, 10, parts.size());
    int32(end, 12, central.size());
    int32(end, 16, (int) offset);
    central.writeTo(out);
    out.write(end);
    out.flush();
  }

  /** Fills the fields shared by local and central headers; `at` is where "version needed" sits. */
  private static void header(
      byte[] b, int signature, int at, boolean deflated, int crc, int packed, int size, byte[] name) {
    int32(b, 0, signature);
    if (at == 6) int16(b, 4, 20);
    int16(b, at, 20);
    int16(b, at + 4, deflated ? 8 : 0);
    // 1980-01-01 00:00, the ZIP epoch: saves are byte-identical for identical documents.
    int16(b, at + 8, (1 << 5) | 1);
    int32(b, at + 10, crc);
    int32(b, at + 14, packed);
    int32(b, at + 18, size);
    int16(b, at + 22, name.length);
    System.arraycopy(name, 0, b, at == 4 ? 30 : 46, name.length);
  }

  /**
   * UTF-8 bytes of an XML part. ParparVM's String.getBytes goes through generic charset code
   * per character; part XML is almost all ASCII, which this copies directly.
   */
  public static byte[] utf8(CharSequence text) {
    int n = text.length();
    char[] c = new char[n];
    if (text instanceof String) ((String) text).getChars(0, n, c, 0);
    else if (text instanceof StringBuilder) ((StringBuilder) text).getChars(0, n, c, 0);
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

  private static void int16(byte[] b, int at, int v) {
    b[at] = (byte) v;
    b[at + 1] = (byte) (v >>> 8);
  }

  private static void int32(byte[] b, int at, int v) {
    int16(b, at, v);
    int16(b, at + 2, v >>> 16);
  }

  private static final Codec JAVA =
      new Codec() {
        public byte[] deflate(byte[] data) {
          // Fastest level: XML compresses well at any level, and save latency is user-visible.
          Deflater d = new Deflater(1, true);
          d.setInput(data);
          d.finish();
          ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 4 + 64);
          byte[] buffer = new byte[16384];
          while (!d.finished()) out.write(buffer, 0, d.deflate(buffer));
          d.end();
          return out.toByteArray();
        }

        public int crc32(byte[] data) {
          CRC32 crc = new CRC32();
          crc.update(data, 0, data.length);
          return (int) crc.getValue();
        }
      };
}
