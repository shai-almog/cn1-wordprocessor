package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/**
 * Embedded TrueType faces with explicit glyph mapping and searchable Unicode text. A character the
 * chosen face lacks falls back to the provider's fallback faces, then to the face's .notdef glyph.
 */
final class PdfFonts {
  private final PdfExporter.FontProvider provider;
  private final Map<String, Face> faces = new LinkedHashMap<>();
  private final Set<String> broken = new HashSet<>();

  PdfFonts(PdfExporter.FontProvider provider) {
    this.provider = provider;
  }

  Face face(TextStyle style) throws IOException {
    return named(provider.name(style));
  }

  private Face named(String name) throws IOException {
    if (!validName(name)) throw new IOException("Invalid PDF font name");
    var face = faces.get(name);
    if (face == null) {
      face = new Face(name, provider.bytes(name), faces.size() + 4);
      faces.put(name, face);
    }
    return face;
  }

  /** The chosen face then each loadable fallback face, loading fallbacks only when needed. */
  private final class Chain implements PdfShaping.Coverage {
    final Face primary;
    final List<String> rest;
    final List<Face> loaded = new ArrayList<>();

    Chain(TextStyle style) throws IOException {
      primary = face(style);
      rest =
          provider instanceof PdfExporter.FallbackFonts
              ? new ArrayList<>(((PdfExporter.FallbackFonts) provider).fallbacks(style))
              : new ArrayList<String>();
    }

    Face pick(int cp) throws IOException {
      if (cp < 32 || primary.font.glyph(cp) != 0) return primary;
      for (int i = 0; ; i++) {
        while (i >= loaded.size() && !rest.isEmpty()) {
          var name = rest.remove(0);
          if (broken.contains(name)) continue;
          try {
            loaded.add(named(name));
          } catch (IOException missing) {
            broken.add(name);
          }
        }
        if (i >= loaded.size()) return primary;
        if (loaded.get(i).font.glyph(cp) != 0) return loaded.get(i);
      }
    }

    public boolean covers(int cp) {
      try {
        return pick(cp).font.glyph(cp) != 0;
      } catch (IOException e) {
        return false;
      }
    }
  }

  /** Unkerned advance of shaped text, in points. */
  float width(TextStyle style, String text, float size) throws IOException {
    var chain = new Chain(style);
    float width = 0;
    for (int[] u : PdfShaping.shape(text, false, chain)) {
      var font = chain.pick(u[0]).font;
      width += font.scaled(font.advance(font.glyph(u[0]))) * size / 1000;
    }
    return width;
  }

  /** Text-showing operators: a font selection and a kerned TJ array for each face run. */
  String show(TextStyle style, String text, boolean rtl, String size) throws IOException {
    return show(style, text, rtl, size, true);
  }

  String show(TextStyle style, String text, boolean rtl, String size, boolean kerning)
      throws IOException {
    var chain = new Chain(style);
    var out = new StringBuilder();
    Face current = null;
    int previous = 0;
    for (int[] u : PdfShaping.shape(text, rtl, chain)) {
      var face = chain.pick(u[0]);
      int glyph = face.font.glyph(u[0]);
      if (face != current) {
        if (current != null) out.append(">] TJ ");
        out.append("/F").append(face.number).append(' ').append(size).append(" Tf [<");
        current = face;
      } else {
        int kern = kerning ? face.font.kern(previous, glyph) : 0;
        if (kern != 0) out.append("> ").append(-face.font.scaled(kern)).append(" <");
      }
      previous = glyph;
      out.append(hex(face.cid(u[0], text.substring(u[1], u[2]))));
    }
    if (current != null) out.append(">] TJ");
    return out.toString();
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

  /** The font resource entries, installed after all text was shown. */
  String install(List<byte[]> objects) throws IOException {
    var resources = new StringBuilder();
    for (Face face : faces.values()) {
      if (face.codes.isEmpty()) continue;
      int id = face.install(objects);
      resources.append(" /F").append(face.number).append(' ').append(id).append(" 0 R");
    }
    return resources.toString();
  }

  static final class Face {
    final String name;
    final int number;
    final TrueType font;
    final Map<String, Integer> codes = new LinkedHashMap<>();
    final List<Integer> glyphCodes = new ArrayList<>();
    final List<String> texts = new ArrayList<>();

    Face(String name, byte[] bytes, int number) throws IOException {
      this.name = name;
      this.number = number;
      font = new TrueType(bytes);
    }

    /** CID showing code point {@code cp}'s glyph (or .notdef) and extracting as {@code text}. */
    int cid(int cp, String text) throws IOException {
      if (cp < 32 || cp >= 0xd800 && cp <= 0xdfff) cp = 0;
      var key = cp + ":" + text;
      var cid = codes.get(key);
      if (cid == null) {
        if (codes.size() == 65535) throw new IOException("Too many PDF font characters");
        codes.put(key, cid = codes.size() + 1);
        glyphCodes.add(cp);
        texts.add(text);
      }
      return cid;
    }

    String encode(String text) throws IOException {
      var out = new StringBuilder("<");
      for (int[] u : PdfShaping.shape(text, false, cp -> false))
        out.append(hex(cid(u[0], text.substring(u[1], u[2]))));
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
      for (int i = 0; i < glyphCodes.size(); i++) {
        if (count % 100 == 0)
          unicode.append(Math.min(100, codes.size() - count)).append(" beginbfchar\n");
        int cid = i + 1, glyph = font.glyph(glyphCodes.get(i));
        mapping[2 * cid] = (byte) (glyph >> 8);
        mapping[2 * cid + 1] = (byte) glyph;
        widths.append(font.scaled(font.advance(glyph))).append(' ');
        unicode.append('<').append(hex(cid)).append("> <");
        var text = texts.get(i);
        if (text.isEmpty()) text = " ";
        for (int k = 0; k < text.length() && k < 32; k++) unicode.append(hex(text.charAt(k)));
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

    private Map<Integer, Integer> kernPairs;
    private int[] kernLookups;
    private final Map<Integer, Integer> kernCache = new HashMap<>();

    /** Pair adjustment in font units from GPOS 'kern' PairPos lookups, else the 'kern' table. */
    int kern(int left, int right) {
      if (left == 0 || right == 0) return 0;
      int key = left << 16 | right;
      var cached = kernCache.get(key);
      if (cached != null) return cached;
      int value;
      try {
        if (kernLookups == null) kernLookups = gposLookups();
        value = 0;
        for (int lookup : kernLookups) value += pairLookup(lookup, left, right);
        if (kernLookups.length == 0) {
          if (kernPairs == null) kernPairs = kernTable();
          var v = kernPairs.get(key);
          value = v == null ? 0 : v;
        }
      } catch (IOException malformed) {
        kernLookups = new int[0];
        kernPairs = new HashMap<>();
        value = 0;
      }
      if (kernCache.size() < 65536) kernCache.put(key, value);
      return value;
    }

    private Map<Integer, Integer> kernTable() throws IOException {
      var pairs = new HashMap<Integer, Integer>();
      var t = tables.get("kern");
      if (t == null || t[1] < 4 || u16(t[0]) != 0) return pairs;
      int at = t[0] + 4, end = t[0] + t[1];
      for (int i = u16(t[0] + 2); i > 0 && at + 6 <= end; i--) {
        int length = u16(at + 2), coverage = u16(at + 4);
        if ((coverage >> 8) == 0 && (coverage & 7) == 1) {
          int n = u16(at + 6);
          for (int k = 0; k < n; k++) {
            int p = at + 14 + 6 * k;
            pairs.put(u16(p) << 16 | u16(p + 2), s16(p + 4));
          }
        }
        if (length < 6) break;
        at += length;
      }
      return pairs;
    }

    /** Lookup table offsets of every GPOS 'kern' feature (PairPos, possibly via Extension). */
    private int[] gposLookups() throws IOException {
      var t = tables.get("GPOS");
      if (t == null || t[1] < 10) return new int[0];
      int base = t[0], features = base + u16(base + 6), lookups = base + u16(base + 8);
      var indices = new TreeSet<Integer>();
      for (int i = 0, n = u16(features); i < n; i++) {
        int record = features + 2 + 6 * i;
        if (!"kern".equals(new String(data, record, 4, "ISO-8859-1"))) continue;
        int feature = features + u16(record + 4);
        for (int k = 0, c = u16(feature + 2); k < c; k++) indices.add(u16(feature + 4 + 2 * k));
      }
      var result = new ArrayList<Integer>();
      int count = u16(lookups);
      for (int index : indices) {
        if (index >= count) continue;
        int lookup = lookups + u16(lookups + 2 + 2 * index), type = u16(lookup);
        for (int s = 0, n = u16(lookup + 4); s < n; s++) {
          int sub = lookup + u16(lookup + 6 + 2 * s);
          if (type == 9 && u16(sub + 2) == 2) result.add(sub + u32(sub + 4));
          else if (type == 2) result.add(sub);
        }
      }
      int[] out = new int[result.size()];
      for (int i = 0; i < out.length; i++) out[i] = result.get(i);
      return out;
    }

    private int pairLookup(int sub, int left, int right) throws IOException {
      int format = u16(sub), index = coverage(sub + u16(sub + 2), left);
      if (index < 0) return 0;
      int f1 = u16(sub + 4), f2 = u16(sub + 6), size1 = 2 * bits(f1 & 0xff),
          size2 = 2 * bits(f2 & 0xff);
      if ((f1 & 4) == 0) return 0;
      int advance = 2 * bits(f1 & 3);
      if (format == 1) {
        if (index >= u16(sub + 8)) return 0;
        int set = sub + u16(sub + 10 + 2 * index), record = 2 + size1 + size2;
        int low = 0, high = u16(set) - 1;
        while (low <= high) {
          int mid = (low + high) >>> 1, at = set + 2 + mid * record, glyph = u16(at);
          if (glyph < right) low = mid + 1;
          else if (glyph > right) high = mid - 1;
          else return s16(at + 2 + advance);
        }
        return 0;
      }
      if (format != 2) return 0;
      int c1 = classOf(sub + u16(sub + 8), left), c2 = classOf(sub + u16(sub + 10), right);
      int count1 = u16(sub + 12), count2 = u16(sub + 14);
      if (c1 >= count1 || c2 >= count2) return 0;
      return s16(sub + 16 + (c1 * count2 + c2) * (size1 + size2) + advance);
    }

    private static int bits(int v) {
      int n = 0;
      for (; v != 0; v &= v - 1) n++;
      return n;
    }

    private int coverage(int table, int glyph) throws IOException {
      int format = u16(table), n = u16(table + 2);
      if (format == 1) {
        int low = 0, high = n - 1;
        while (low <= high) {
          int mid = (low + high) >>> 1, g = u16(table + 4 + 2 * mid);
          if (g < glyph) low = mid + 1;
          else if (g > glyph) high = mid - 1;
          else return mid;
        }
      } else if (format == 2) {
        for (int i = 0; i < n; i++) {
          int at = table + 4 + 6 * i;
          if (glyph >= u16(at) && glyph <= u16(at + 2)) return u16(at + 4) + glyph - u16(at);
        }
      }
      return -1;
    }

    private int classOf(int table, int glyph) throws IOException {
      int format = u16(table);
      if (format == 1) {
        int start = u16(table + 2), n = u16(table + 4);
        return glyph >= start && glyph < start + n ? u16(table + 6 + 2 * (glyph - start)) : 0;
      }
      if (format == 2)
        for (int i = 0, n = u16(table + 2); i < n; i++) {
          int at = table + 4 + 6 * i;
          if (glyph >= u16(at) && glyph <= u16(at + 2)) return u16(at + 4);
        }
      return 0;
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
