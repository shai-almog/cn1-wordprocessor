package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;

/**
 * TrueType subsetting for PDF embedding: keeps glyph numbering (so the CIDToGIDMap stays the
 * identity of the shaped glyph ids) but empties every glyph outline the document does not use,
 * after closing over composite-glyph components. The kept tables are the ones a TrueType
 * rasterizer needs (head, hhea, maxp, hmtx, loca, glyf, cvt, fpgm, prep, OS/2) plus a format 12
 * cmap restricted to the kept glyphs; layout tables (GSUB, GPOS, kern, name, post) are dropped
 * since PDF text is already shaped. Also writes standalone faces out of TrueType collections.
 */
final class FontSubset {
  private FontSubset() {}

  private static final String[] KEEP = {"OS/2", "cvt ", "fpgm", "head", "hhea", "hmtx", "maxp", "prep"};

  /** The glyph set closed over composite components; glyph 0 (.notdef) is always kept. */
  static TreeSet<Integer> closure(PdfFonts.TrueType font, Collection<Integer> used)
      throws IOException {
    var kept = new TreeSet<Integer>();
    var pending = new ArrayList<Integer>();
    pending.add(0);
    pending.addAll(used);
    while (!pending.isEmpty()) {
      int g = pending.remove(pending.size() - 1);
      if (g < 0 || g >= font.glyphs || !kept.add(g)) continue;
      int[] span = glyphSpan(font, g);
      if (span[1] - span[0] < 10 || font.s16(span[0]) >= 0) continue;
      int at = span[0] + 10, end = span[1];
      for (int guard = 0; guard < 1024 && at + 4 <= end; guard++) {
        int flags = font.u16(at), component = font.u16(at + 2);
        pending.add(component);
        at += 4 + ((flags & 1) != 0 ? 4 : 2);
        if ((flags & 8) != 0) at += 2;
        else if ((flags & 0x40) != 0) at += 4;
        else if ((flags & 0x80) != 0) at += 8;
        if ((flags & 0x20) == 0) break;
      }
    }
    return kept;
  }

  /** Absolute [start, end) of glyph {@code g}'s outline in the font data. */
  static int[] glyphSpan(PdfFonts.TrueType font, int g) throws IOException {
    int glyf = font.table("glyf", 0), loca = font.table("loca", 2);
    boolean longLoca = font.s16(font.head + 50) != 0;
    int start = longLoca ? font.u32(loca + 4 * g) : 2 * font.u16(loca + 2 * g),
        end = longLoca ? font.u32(loca + 4 * g + 4) : 2 * font.u16(loca + 2 * g + 2);
    int length = font.tables.get("glyf")[1];
    if (start > end || end > length) throw new IOException("Invalid font glyph location");
    return new int[] {glyf + start, glyf + end};
  }

  /** A standalone TrueType font holding only {@code used} glyph outlines (and their components). */
  static byte[] subset(PdfFonts.TrueType font, Collection<Integer> used) throws IOException {
    var kept = closure(font, used);
    var glyf = new ByteArrayOutputStream();
    byte[] loca = new byte[4 * (font.glyphs + 1)];
    for (int g = 0; g < font.glyphs; g++) {
      put32(loca, 4 * g, glyf.size());
      if (!kept.contains(g)) continue;
      int[] span = glyphSpan(font, g);
      glyf.write(font.data, span[0], span[1] - span[0]);
      while (glyf.size() % 4 != 0) glyf.write(0);
    }
    put32(loca, 4 * font.glyphs, glyf.size());
    var tables = new TreeMap<String, byte[]>();
    for (String tag : KEEP) {
      var t = font.tables.get(tag);
      if (t != null) tables.put(tag, Arrays.copyOfRange(font.data, t[0], t[0] + t[1]));
    }
    byte[] head = tables.get("head");
    put32(head, 8, 0);
    head[50] = 0;
    head[51] = 1;
    tables.put("loca", loca);
    tables.put("glyf", glyf.toByteArray());
    tables.put("cmap", cmap(font, kept));
    return write(tables);
  }

  /** A format 12 cmap of the code points mapping to kept glyphs (in the BMP and beyond). */
  private static byte[] cmap(PdfFonts.TrueType font, Set<Integer> kept) throws IOException {
    var groups = new ArrayList<int[]>();
    int limit = font.format == 12 ? 0x110000 : 0x10000;
    for (int cp = 1; cp < limit; cp++) {
      if (cp == 0xd800) cp = 0xe000;
      if (font.format == 12 && cp == 0x30000) cp = 0xe0000;
      int g = font.glyph(cp);
      if (g == 0 || !kept.contains(g)) continue;
      var last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
      if (last != null && last[1] == cp - 1 && last[2] + cp - last[0] == g) last[1] = cp;
      else groups.add(new int[] {cp, cp, g});
    }
    byte[] out = new byte[12 + 16 + 12 * groups.size()];
    put16(out, 2, 1);
    put16(out, 4, 3);
    put16(out, 6, 10);
    put32(out, 8, 12);
    put16(out, 12, 12);
    put32(out, 16, 16 + 12 * groups.size());
    put32(out, 24, groups.size());
    for (int i = 0; i < groups.size(); i++) {
      int[] gr = groups.get(i);
      put32(out, 28 + 12 * i, gr[0]);
      put32(out, 32 + 12 * i, gr[1]);
      put32(out, 36 + 12 * i, gr[2]);
    }
    return out;
  }

  /** Face {@code index} of a TrueType collection (or the font itself) as a standalone font. */
  static byte[] face(byte[] data, int index) throws IOException {
    if (data.length < 12) throw new IOException("Truncated font");
    if (!(data[0] == 't' && data[1] == 't' && data[2] == 'c' && data[3] == 'f')) return data;
    int faces = be32(data, 8);
    if (index < 0 || index >= faces || 12 + 4 * faces > data.length)
      throw new IOException("Invalid font collection");
    int offset = be32(data, 12 + 4 * index);
    if (offset < 0 || offset + 12 > data.length) throw new IOException("Invalid font collection");
    int count = be16(data, offset + 4);
    if (offset + 12 + 16 * count > data.length) throw new IOException("Invalid font collection");
    var tables = new TreeMap<String, byte[]>();
    for (int i = 0; i < count; i++) {
      int at = offset + 12 + 16 * i, start = be32(data, at + 8), length = be32(data, at + 12);
      if (start < 0 || length < 0 || start > data.length - length)
        throw new IOException("Invalid font collection table");
      tables.put(new String(data, at, 4, "ISO-8859-1"), Arrays.copyOfRange(data, start, start + length));
    }
    return write(tables, be32(data, offset));
  }

  /** Number of faces in a font file (1 for a plain font). */
  static int faces(byte[] data) {
    if (data.length >= 12 && data[0] == 't' && data[1] == 't' && data[2] == 'c' && data[3] == 'f')
      return Math.max(0, Math.min(256, be32(data, 8)));
    return 1;
  }

  static byte[] write(SortedMap<String, byte[]> tables) throws IOException {
    return write(tables, 0x10000);
  }

  private static byte[] write(SortedMap<String, byte[]> tables, int version) throws IOException {
    int n = tables.size(), pow = 1, log = 0;
    while (pow * 2 <= n) {
      pow *= 2;
      log++;
    }
    int size = 12 + 16 * n;
    for (byte[] t : tables.values()) size += (t.length + 3) & ~3;
    byte[] out = new byte[size];
    put32(out, 0, version);
    put16(out, 4, n);
    put16(out, 6, pow * 16);
    put16(out, 8, log);
    put16(out, 10, n * 16 - pow * 16);
    int record = 12, at = 12 + 16 * n, headAt = -1;
    for (var e : tables.entrySet()) {
      byte[] t = e.getValue();
      byte[] tag = e.getKey().getBytes("ISO-8859-1");
      System.arraycopy(tag, 0, out, record, 4);
      System.arraycopy(t, 0, out, at, t.length);
      if (e.getKey().equals("head") && t.length >= 12) {
        headAt = at;
        put32(out, at + 8, 0);
      }
      put32(out, record + 4, checksum(out, at, t.length));
      put32(out, record + 8, at);
      put32(out, record + 12, t.length);
      record += 16;
      at += (t.length + 3) & ~3;
    }
    if (headAt >= 0) put32(out, headAt + 8, (int) (0xB1B0AFBAL - checksum(out, 0, out.length)));
    return out;
  }

  private static int checksum(byte[] b, int at, int length) {
    long sum = 0;
    for (int i = 0; i < length; i += 4) {
      long word = 0;
      for (int k = 0; k < 4; k++)
        word = word << 8 | (i + k < length ? b[at + i + k] & 255 : 0);
      sum += word;
    }
    return (int) sum;
  }

  private static void put16(byte[] b, int at, int v) {
    b[at] = (byte) (v >> 8);
    b[at + 1] = (byte) v;
  }

  private static void put32(byte[] b, int at, int v) {
    put16(b, at, v >>> 16);
    put16(b, at + 2, v);
  }

  static int be16(byte[] b, int at) {
    return (b[at] & 255) << 8 | (b[at + 1] & 255);
  }

  static int be32(byte[] b, int at) {
    return be16(b, at) << 16 | be16(b, at + 2);
  }
}
