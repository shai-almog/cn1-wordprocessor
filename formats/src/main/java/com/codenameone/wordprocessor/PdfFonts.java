package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/** Embedded TrueType faces with explicit glyph mapping and searchable Unicode text. */
final class PdfFonts {
  private final PdfExporter.FontProvider provider;
  private final Map<String, Face> faces = new LinkedHashMap<>();

  PdfFonts(PdfExporter.FontProvider provider) {
    this.provider = provider;
  }

  Face face(TextStyle style) throws IOException {
    var name = provider.name(style);
    if (!validName(name)) throw new IOException("Invalid PDF font name");
    var face = faces.get(name);
    if (face == null) {
      face = new Face(name, provider.bytes(name), faces.size() + 4);
      faces.put(name, face);
    }
    return face;
  }

  private static boolean validName(String name) {
    if (name == null || name.length() == 0 || name.length() > 127) return false;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-'))
        return false;
    }
    return true;
  }

  String install(List<byte[]> objects) throws IOException {
    var resources = new StringBuilder();
    for (Face face : faces.values()) {
      int id = face.install(objects);
      resources.append(" /F").append(face.number).append(' ').append(id).append(" 0 R");
    }
    return resources.toString();
  }

  static final class Face {
    final String name;
    final int number;
    final TrueType font;
    final Map<Integer, Integer> codes = new LinkedHashMap<>();

    Face(String name, byte[] bytes, int number) throws IOException {
      this.name = name;
      this.number = number;
      font = new TrueType(bytes);
    }

    String encode(String text) throws IOException {
      var out = new StringBuilder("<");
      for (int i = 0; i < text.length(); i++) {
        int cp = text.charAt(i);
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < text.length()) {
          int low = text.charAt(i + 1);
          if (low >= 0xdc00 && low <= 0xdfff) {
            cp = 0x10000 + ((cp - 0xd800) << 10) + low - 0xdc00;
            i++;
          }
        }
        if (cp < 32 || cp >= 0xd800 && cp <= 0xdfff || font.glyph(cp) == 0)
          throw new IOException(
              "PDF font " + name + " does not cover U+" + Integer.toHexString(cp));
        var cid = codes.get(cp);
        if (cid == null) {
          if (codes.size() == 65535) throw new IOException("Too many PDF font characters");
          codes.put(cp, cid = codes.size() + 1);
        }
        out.append(hex(cid));
      }
      return out.append('>').toString();
    }

    int install(List<byte[]> objects) throws IOException {
      int file = add(objects, stream("/Length1 " + font.data.length, font.data, true));
      int descriptor =
          add(
              objects,
              ascii(
                  "<< /Type /FontDescriptor /FontName /"
                      + name
                      + " /Flags 32 /FontBBox ["
                      + font.box()
                      + "] /ItalicAngle "
                      + font.italic
                      + " /Ascent "
                      + font.scaled(font.ascent)
                      + " /Descent "
                      + font.scaled(font.descent)
                      + " /CapHeight "
                      + font.scaled(font.ascent)
                      + " /StemV 80 /FontFile2 "
                      + file
                      + " 0 R >>"));
      byte[] mapping = new byte[(codes.size() + 1) * 2];
      StringBuilder widths = new StringBuilder("[1 ["),
          unicode =
              new StringBuilder(
                  """
                  /CIDInit /ProcSet findresource begin
                  12 dict begin
                  begincmap
                  /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                  /CMapName /CN1Unicode def
                  /CMapType 2 def
                  1 begincodespacerange
                  <0000> <FFFF>
                  endcodespacerange
                  """);
      int count = 0;
      for (Map.Entry<Integer, Integer> e : codes.entrySet()) {
        if (count % 100 == 0)
          unicode.append(Math.min(100, codes.size() - count)).append(" beginbfchar\n");
        int cp = e.getKey(), cid = e.getValue(), glyph = font.glyph(cp);
        mapping[2 * cid] = (byte) (glyph >> 8);
        mapping[2 * cid + 1] = (byte) glyph;
        widths.append(font.scaled(font.advance(glyph))).append(' ');
        unicode.append('<').append(hex(cid)).append("> <");
        if (cp > 0xffff)
          unicode
              .append(hex(0xd800 + ((cp - 0x10000) >> 10)))
              .append(hex(0xdc00 + ((cp - 0x10000) & 1023)));
        else unicode.append(hex(cp));
        unicode.append(">\n");
        count++;
        if (count % 100 == 0 || count == codes.size()) unicode.append("endbfchar\n");
      }
      unicode.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
      int map = add(objects, stream("", mapping, true));
      int toUnicode = add(objects, stream("", ascii(unicode.toString()), false));
      int descendant =
          add(
              objects,
              ascii(
                  "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /"
                      + name
                      + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >>"
                      + " /FontDescriptor "
                      + descriptor
                      + " 0 R /DW 1000 /W "
                      + widths
                      + "]]"
                      + " /CIDToGIDMap "
                      + map
                      + " 0 R >>"));
      return add(
          objects,
          ascii(
              "<< /Type /Font /Subtype /Type0 /BaseFont /"
                  + name
                  + " /Encoding /Identity-H /DescendantFonts ["
                  + descendant
                  + " 0 R] /ToUnicode "
                  + toUnicode
                  + " 0 R >>"));
    }
  }

  /** Bounds-checked SFNT reader; supports Unicode cmap 4 and 12 and TrueType outlines. */
  static final class TrueType {
    final byte[] data;
    final Map<String, int[]> tables = new HashMap<>();
    final int units, ascent, descent, metrics, glyphs, hmtx, cmap, cmapLength, format, head;
    final float italic;

    TrueType(byte[] bytes) throws IOException {
      if (bytes == null || bytes.length > 20 * 1024 * 1024)
        throw new IOException("Invalid TrueType font");
      data = bytes;
      if (u32(0) != 0x10000) throw new IOException("PDF embedding requires TrueType outlines");
      int count = u16(4);
      check(12, count * 16);
      for (int i = 0; i < count; i++) {
        int at = 12 + 16 * i, offset = u32(at + 8), length = u32(at + 12);
        check(offset, length);
        tables.put(new String(data, at, 4, "ISO-8859-1"), new int[] {offset, length});
      }
      table("glyf", 0);
      head = table("head", 54);
      units = u16(head + 18);
      if (units < 16 || units > 16384) throw new IOException("Invalid font units");
      int hhea = table("hhea", 36);
      ascent = s16(hhea + 4);
      descent = s16(hhea + 6);
      metrics = u16(hhea + 34);
      glyphs = u16(table("maxp", 6) + 4);
      if (metrics == 0 || metrics > glyphs) throw new IOException("Invalid font metrics");
      hmtx = table("hmtx", 4 * metrics + 2 * (glyphs - metrics));
      var post = tables.get("post");
      italic = post != null && post[1] >= 8 ? ((int) raw32(post[0] + 4)) / 65536f : 0;
      var os = tables.get("OS/2");
      if (os != null && os[1] >= 10 && (u16(os[0] + 8) & 0x202) != 0)
        throw new IOException("Font license prohibits outline embedding");
      int base = table("cmap", 4),
          length = tables.get("cmap")[1],
          n = u16(base + 2),
          selected = -1,
          selectedLength = 0,
          best = 0;
      if (4 + 8 * n > length) throw new IOException("Invalid font cmap records");
      for (int i = 0; i < n; i++) {
        int at = base + 4 + i * 8,
            platform = u16(at),
            encoding = u16(at + 2),
            relative = u32(at + 4);
        if (!(platform == 0 || platform == 3 && (encoding == 1 || encoding == 10))) continue;
        if (relative > length - 4) throw new IOException("Invalid font cmap offset");
        int sub = base + relative, kind = u16(sub);
        if (kind != 4 && kind != 12) continue;
        if (kind == 12 && relative > length - 16) throw new IOException("Invalid font cmap header");
        int size = kind == 4 ? u16(sub + 2) : u32(sub + 4);
        if (size > length - relative || size < (kind == 4 ? 16 : 16))
          throw new IOException("Invalid font cmap length");
        if (kind > best) {
          best = kind;
          selected = sub;
          selectedLength = size;
        }
      }
      if (selected < 0) throw new IOException("No supported Unicode font mapping");
      cmap = selected;
      cmapLength = selectedLength;
      format = best;
      if (format == 4 && (u16(cmap + 6) % 2 != 0 || 16 + u16(cmap + 6) * 4 > cmapLength)
          || format == 12 && u32(cmap + 12) > (cmapLength - 16) / 12)
        throw new IOException("Invalid font cmap segments");
    }

    int glyph(int cp) throws IOException {
      int result = 0;
      if (format == 12) {
        int low = 0, high = u32(cmap + 12) - 1;
        while (low <= high) {
          int mid = (low + high) >>> 1,
              at = cmap + 16 + mid * 12,
              start = u32(at),
              end = u32(at + 4);
          if (cp < start) high = mid - 1;
          else if (cp > end) low = mid + 1;
          else {
            long value = (long) u32(at + 8) + cp - start;
            result = value < glyphs ? (int) value : 0;
            break;
          }
        }
      } else if (cp <= 0xffff) {
        int count = u16(cmap + 6) / 2,
            ends = cmap + 14,
            starts = ends + 2 * count + 2,
            deltas = starts + 2 * count,
            ranges = deltas + 2 * count;
        for (int i = 0; i < count; i++)
          if (cp <= u16(ends + 2 * i)) {
            if (cp < u16(starts + 2 * i)) break;
            int delta = s16(deltas + 2 * i), range = u16(ranges + 2 * i);
            if (range == 0) result = (cp + delta) & 65535;
            else {
              int at = ranges + 2 * i + range + 2 * (cp - u16(starts + 2 * i));
              if (at < cmap || at > cmap + cmapLength - 2)
                throw new IOException("Invalid font glyph offset");
              result = u16(at);
              if (result != 0) result = (result + delta) & 65535;
            }
            break;
          }
      }
      return result < glyphs ? result : 0;
    }

    int advance(int glyph) throws IOException {
      return u16(hmtx + 4 * Math.min(glyph, metrics - 1));
    }

    int scaled(int value) {
      return Math.round(value * 1000f / units);
    }

    String box() throws IOException {
      return scaled(s16(head + 36))
          + " "
          + scaled(s16(head + 38))
          + " "
          + scaled(s16(head + 40))
          + " "
          + scaled(s16(head + 42));
    }

    int table(String name, int min) throws IOException {
      var t = tables.get(name);
      if (t == null || t[1] < min) throw new IOException("Missing or truncated font table " + name);
      return t[0];
    }

    void check(int at, int size) throws IOException {
      if (at < 0 || size < 0 || at > data.length - size)
        throw new IOException("Truncated TrueType font");
    }

    int u16(int at) throws IOException {
      check(at, 2);
      return (data[at] & 255) << 8 | (data[at + 1] & 255);
    }

    int s16(int at) throws IOException {
      return (short) u16(at);
    }

    long raw32(int at) throws IOException {
      return ((long) u16(at) << 16) | u16(at + 2);
    }

    int u32(int at) throws IOException {
      long n = raw32(at);
      if (n > Integer.MAX_VALUE) throw new IOException("Invalid font offset");
      return (int) n;
    }
  }

  private static String hex(int value) {
    return TextUtil.padded(value, 16, 4);
  }

  private static int add(List<byte[]> objects, byte[] bytes) {
    objects.add(bytes);
    return objects.size();
  }

  private static byte[] ascii(String text) throws IOException {
    return text.getBytes("ISO-8859-1");
  }

  private static byte[] stream(String attributes, byte[] bytes, boolean compress)
      throws IOException {
    if (compress) {
      var out = new ByteArrayOutputStream();
      var z = new DeflaterOutputStream(out);
      z.write(bytes);
      z.close();
      bytes = out.toByteArray();
      attributes += " /Filter /FlateDecode";
    }
    var out = new ByteArrayOutputStream();
    out.write(ascii("<< " + attributes + " /Length " + bytes.length + " >>\nstream\n"));
    out.write(bytes);
    out.write(ascii("\nendstream"));
    return out.toByteArray();
  }
}
