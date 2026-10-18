package com.codenameone.wordprocessor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a package's parts as a ZIP archive with the platform's java.util.zip. */
public final class ZipParts {
  private ZipParts() {}

  static void write(Map<String, byte[]> parts, OutputStream out) throws IOException {
    var zip = new ZipOutputStream(out);
    // Fastest level: XML compresses well at any level, and save latency is user-visible.
    zip.setLevel(1);
    for (var e : parts.entrySet()) {
      var entry = new ZipEntry(e.getKey());
      entry.setTime(EPOCH); // identical documents save byte-identical archives
      zip.putNextEntry(entry);
      zip.write(e.getValue());
      zip.closeEntry();
    }
    zip.finish();
    out.flush();
  }

  private static final long EPOCH = 315532800000L; // 1980-01-01, the ZIP epoch

  /**
   * UTF-8 bytes of an XML part. ParparVM's String.getBytes goes through generic charset code
   * per character; part XML is almost all ASCII, which this copies directly.
   */
  public static byte[] utf8(CharSequence text) {
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
