package com.codenameone.wordprocessor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Word 97-2003 binary (.doc, [MS-DOC]) import. The compound file is decoded (FIB, piece table, CHPX/PAPX
 * FKPs, stylesheet, lists, tables, sections, notes, headers, fields and inline pictures) and translated
 * into an in-memory WordprocessingML package that {@link Docx#read} maps like any other DOCX. Reading is
 * lenient and bounded: hostile input only ever yields an IOException.
 */
final class DocBinary {
  private static final int MAX_PIECES = 1000000, MAX_BINS = 100000, MAX_STYLES = 4096, MAX_LISTS = 2000,
      MAX_SECTIONS = 1024, MAX_NOTES = 10000, MAX_PARTS = 4096;
  private static final long MAX_CHARS = 50000000L;
  private static final String CP1252 =
      "€�‚ƒ„…†‡ˆ‰Š‹Œ�Ž�"
          + "�‘’“”•–—˜™š›œ�žŸ";
  private static final String NS =
      " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
          + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
          + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"";
  private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
  private static final String CT = "application/vnd.openxmlformats-officedocument.wordprocessingml.";

  private interface Prl {
    void op(int op, byte[] b, int at, int len);
  }

  private final byte[] word, table, data;
  private final long[] pairs;
  private final long ccpText, ccpFtn, ccpHdd, ccpAtn, ccpEdn;
  private long[] cps = new long[0], pfc = new long[0];
  private boolean[] pcomp = new boolean[0];
  private int[] pprm = new int[0];
  private final List<byte[]> prc = new ArrayList<>();
  private long[] chpFcs, chpPns, papFcs, papPns;
  private final List<String> fonts = new ArrayList<>();
  private final List<Style> styles = new ArrayList<>();
  private String[] styleIds = new String[0];
  private int lfoCount;
  private long next, budget;
  private int units, papIstd, papAt, papLen;
  private int cachePiece = -2;
  private final Map<Long, String> rprCache = new HashMap<>();
  private final Map<Long, Integer> footRefs = new HashMap<>(), endRefs = new HashMap<>();
  private final Set<Long> sectEnds = new HashSet<>();
  private final LinkedHashMap<String, byte[]> parts = new LinkedHashMap<>();
  private final StringBuilder rels = new StringBuilder(), types = new StringBuilder();
  private int pictures, relIds;

  private static final class Style {
    int stk, base = 0xFFF;
    String name = "";
    byte[] papx = new byte[0], chpx = new byte[0];
  }

  private static final class Chr {
    Boolean b, i, strike, caps, smallCaps, vanish;
    String u, color, font, highlight, vert, style;
    int sz;

    String xml() {
      var s = new StringBuilder();
      if (style != null) s.append("<w:rStyle w:val=\"").append(style).append("\"/>");
      if (font != null)
        s.append("<w:rFonts w:ascii=\"").append(font).append("\" w:hAnsi=\"").append(font)
            .append("\" w:cs=\"").append(font).append("\"/>");
      flag(s, "b", b);
      flag(s, "i", i);
      flag(s, "caps", caps);
      flag(s, "smallCaps", smallCaps);
      flag(s, "strike", strike);
      flag(s, "vanish", vanish);
      if (color != null) s.append("<w:color w:val=\"").append(color).append("\"/>");
      if (sz > 0) s.append("<w:sz w:val=\"").append(sz).append("\"/>");
      if (highlight != null) s.append("<w:highlight w:val=\"").append(highlight).append("\"/>");
      if (u != null) s.append("<w:u w:val=\"").append(u).append("\"/>");
      if (vert != null) s.append("<w:vertAlign w:val=\"").append(vert).append("\"/>");
      return s.length() == 0 ? "" : "<w:rPr>" + s + "</w:rPr>";
    }

    private static void flag(StringBuilder s, String name, Boolean v) {
      if (v != null) s.append("<w:").append(name).append(v ? "/>" : " w:val=\"0\"/>");
    }
  }

  private static final class PPr {
    String jc, style;
    Integer left, right, first, before, after, line;
    String lineRule;
    boolean keep, keepNext, pageBreak, inTable, ttp, innerTtp, borders;
    int outline = -1, ilvl, ilfo, itap;
    int[] grid;

    String xml(int lists) {
      var s = new StringBuilder();
      if (style != null) s.append("<w:pStyle w:val=\"").append(style).append("\"/>");
      if (keepNext) s.append("<w:keepNext/>");
      if (keep) s.append("<w:keepLines/>");
      if (pageBreak) s.append("<w:pageBreakBefore/>");
      int num = ilfo >= 1 && ilfo <= 0x7FE ? ilfo : ilfo <= -1 && ilfo >= -0x7FE ? -ilfo : 0;
      if (num > 0 && num <= lists)
        s.append("<w:numPr><w:ilvl w:val=\"").append(Math.min(8, Math.max(0, ilvl)))
            .append("\"/><w:numId w:val=\"").append(num).append("\"/></w:numPr>");
      if (before != null || after != null || line != null) {
        s.append("<w:spacing");
        if (before != null) s.append(" w:before=\"").append(before).append('"');
        if (after != null) s.append(" w:after=\"").append(after).append('"');
        if (line != null) s.append(" w:line=\"").append(line).append("\" w:lineRule=\"").append(lineRule).append('"');
        s.append("/>");
      }
      if (left != null || right != null || first != null) {
        s.append("<w:ind");
        if (left != null) s.append(" w:left=\"").append(left).append('"');
        if (right != null) s.append(" w:right=\"").append(right).append('"');
        if (first != null)
          s.append(first < 0 ? " w:hanging=\"" : " w:firstLine=\"").append(Math.abs(first)).append('"');
        s.append("/>");
      }
      if (jc != null) s.append("<w:jc w:val=\"").append(jc).append("\"/>");
      if (outline >= 0 && outline < 9) s.append("<w:outlineLvl w:val=\"").append(outline).append("\"/>");
      return s.toString();
    }
  }

  private static final class Para {
    final StringBuilder runs = new StringBuilder();
    String pPr = "", sect;
    boolean inTable, cellEnd, rowEnd, borders;
    int[] grid;

    String xml() {
      String props = sect == null ? pPr : pPr + sect;
      return "<w:p>" + (props.isEmpty() ? "" : "<w:pPr>" + props + "</w:pPr>") + runs + "</w:p>";
    }
  }

  private DocBinary(Map<String, byte[]> streams) throws IOException {
    byte[] w = streams.get("WordDocument");
    if (w == null) throw new IOException("Not a Word document: no WordDocument stream");
    if (w.length < 0x9A) throw new IOException("Not a Word 97-2003 document");
    int ident = u16(w, 0);
    if (ident == 0xA5DC) throw new IOException("Word 6.0/95 documents are not supported");
    if (ident != 0xA5EC) throw new IOException("Not a Word 97-2003 document");
    int flags = u16(w, 0x0A);
    if ((flags & 0x0100) != 0) throw new IOException("encrypted .doc files are not supported");
    if (u16(w, 0x20) != 14 || u16(w, 0x3E) != 22) throw new IOException("Damaged .doc file header");
    word = w;
    ccpText = ccp(3);
    ccpFtn = ccp(4);
    ccpHdd = ccp(5);
    ccpAtn = ccp(7);
    ccpEdn = ccp(8);
    int cb = u16(w, 0x98);
    if (cb == 0 || w.length < 0x9A + cb * 8) throw new IOException("Damaged .doc file header");
    pairs = new long[cb * 2];
    for (int i = 0; i < pairs.length; i++) pairs[i] = u32(w, 0x9A + i * 4);
    boolean one = (flags & 0x0200) != 0;
    byte[] t = streams.get(one ? "1Table" : "0Table");
    if (t == null) t = streams.get(one ? "0Table" : "1Table");
    table = t == null ? new byte[0] : t;
    byte[] d = streams.get("Data");
    data = d == null ? new byte[0] : d;
  }

  /** Reads a .doc file as a DOCX import. */
  static Docx.Opened read(byte[] bytes) throws IOException {
    var zip = new ByteArrayOutputStream();
    ZipParts.write(convert(bytes), zip);
    try {
      return Docx.read(new ByteArrayInputStream(zip.toByteArray()));
    } catch (RuntimeException ex) {
      throw new IOException("Unreadable .doc file: " + ex.getMessage());
    }
  }

  /** Translates a .doc file into the parts of an equivalent DOCX package. */
  static Map<String, byte[]> convert(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length < 512 || !CompoundFile.hasSignature(bytes))
      throw new IOException("Not a Word 97-2003 document");
    Map<String, byte[]> streams;
    try {
      streams = CompoundFile.read(bytes);
    } catch (RuntimeException ex) {
      throw new IOException("Damaged .doc container");
    }
    try {
      return new DocBinary(streams).build();
    } catch (RuntimeException | StackOverflowError ex) {
      throw new IOException("Damaged .doc file: " + ex);
    }
  }

  private long ccp(int i) {
    int v = (int) u32(word, 0x40 + i * 4);
    return Math.max(0, v);
  }

  private long[] pair(int i) {
    if (i * 2 + 1 >= pairs.length || pairs[i * 2 + 1] == 0) return null;
    return new long[] {pairs[i * 2], pairs[i * 2 + 1]};
  }

  private Map<String, byte[]> build() throws IOException {
    parsePieces();
    chpFcs = bins(12);
    chpPns = binPns;
    papFcs = bins(13);
    papPns = binPns;
    parseFonts();
    parseStyles();
    String numbering = numbering();
    long ftnBase = ccpText, hddBase = ccpText + ccpFtn, ednBase = hddBase + ccpHdd + ccpAtn;
    String footnotes = notes(2, 3, ftnBase, ccpFtn, "footnote", footRefs);
    String endnotes = notes(46, 47, ednBase, ccpEdn, "endnote", endRefs);
    List<long[]> sects = sections();
    for (long[] s : sects) sectEnds.add(s[0]);
    // Header/footer stories: six separators, then six per section (even/odd header, even/odd footer, first).
    List<long[]> stories = plc(11, 0);
    String[][] refs = new String[sects.size()][6];
    String[] carry = new String[6];
    String[] kinds = {"even", "default", "even", "default", "first", "first"};
    int hf = 0;
    for (int si = 0; si < sects.size(); si++)
      for (int k = 0; k < 6; k++) {
        int idx = 6 + si * 6 + k;
        if (idx + 1 < stories.size() && hf < MAX_PARTS) {
          long a = Math.min(stories.get(idx)[0], ccpHdd), b = Math.min(stories.get(idx + 1)[0], ccpHdd);
          if (b > a) {
            String body = blocks(walk(hddBase + a, hddBase + b, null));
            if (!body.isEmpty()) {
              boolean header = k == 0 || k == 1 || k == 4;
              String name = (header ? "header" : "footer") + (++hf);
              String root = header ? "hdr" : "ftr";
              parts.put("word/" + name + ".xml", ZipParts.utf8(xmlDecl() + "<w:" + root + NS + ">" + body + "</w:" + root + ">"));
              carry[k] = rel(header ? "header" : "footer", name + ".xml");
              override("/word/" + name + ".xml", CT + (header ? "header" : "footer") + "+xml");
            }
          }
        }
        if (carry[k] != null)
          refs[si][k] = "<w:" + (k == 0 || k == 1 || k == 4 ? "header" : "footer") + "Reference w:type=\""
              + kinds[k] + "\" r:id=\"" + carry[k] + "\"/>";
      }
    var paras = walkEnded(0, ccpText, null);
    int pi = 0;
    for (int si = 0; si + 1 < sects.size(); si++) {
      long end = sects.get(si)[0];
      while (pi < paras.size() && paras.get(pi).end < end) pi++;
      if (pi < paras.size() && paras.get(pi).end == end)
        paras.get(pi).para.sect = sectPr(sects.get(si), refs[si]);
    }
    var list = new ArrayList<Para>();
    for (Ended e : paras) list.add(e.para);
    String body = blocks(list);
    if (body.isEmpty()) body = "<w:p/>";
    body += sectPr(sects.get(sects.size() - 1), refs[sects.size() - 1]);
    parts.put("word/document.xml", ZipParts.utf8(xmlDecl() + "<w:document" + NS + "><w:body>" + body + "</w:body></w:document>"));
    parts.put("word/styles.xml", ZipParts.utf8(stylesXml()));
    rel("styles", "styles.xml");
    override("/word/styles.xml", CT + "styles+xml");
    if (numbering != null) {
      parts.put("word/numbering.xml", ZipParts.utf8(numbering));
      rel("numbering", "numbering.xml");
      override("/word/numbering.xml", CT + "numbering+xml");
    }
    if (footnotes != null) {
      parts.put("word/footnotes.xml", ZipParts.utf8(footnotes));
      rel("footnotes", "footnotes.xml");
      override("/word/footnotes.xml", CT + "footnotes+xml");
    }
    if (endnotes != null) {
      parts.put("word/endnotes.xml", ZipParts.utf8(endnotes));
      rel("endnotes", "endnotes.xml");
      override("/word/endnotes.xml", CT + "endnotes+xml");
    }
    parts.put(
        "word/_rels/document.xml.rels",
        ZipParts.utf8(xmlDecl() + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + rels + "</Relationships>"));
    parts.put(
        "_rels/.rels",
        ZipParts.utf8(xmlDecl() + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"" + REL + "officeDocument\" Target=\"word/document.xml\"/></Relationships>"));
    parts.put(
        "[Content_Types].xml",
        ZipParts.utf8(xmlDecl() + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
            + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"" + CT + "document.main+xml\"/>"
            + types + "</Types>"));
    return parts;
  }

  private static String xmlDecl() {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
  }

  private String rel(String type, String target) {
    String id = "rIdB" + (++relIds);
    rels.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(REL).append(type)
        .append("\" Target=\"").append(target).append("\"/>");
    return id;
  }

  private void override(String part, String type) {
    types.append("<Override PartName=\"").append(part).append("\" ContentType=\"").append(type).append("\"/>");
  }

  // ---- byte helpers ----

  private static int u8(byte[] b, long at) {
    return at >= 0 && at < b.length ? b[(int) at] & 0xff : 0;
  }

  private static int u16(byte[] b, long at) {
    return u8(b, at) | u8(b, at + 1) << 8;
  }

  private static int s16(byte[] b, long at) {
    return (short) u16(b, at);
  }

  private static long u32(byte[] b, long at) {
    return u16(b, at) | ((long) u16(b, at + 2) << 16);
  }

  private static boolean in(byte[] b, long at, long len) {
    return at >= 0 && len >= 0 && at + len <= b.length;
  }

  private static byte[] copy(byte[] b, long at, long len) {
    if (!in(b, at, len)) {
      at = Math.max(0, Math.min(at, b.length));
      len = Math.max(0, Math.min(len, b.length - at));
    }
    var out = new byte[(int) len];
    System.arraycopy(b, (int) at, out, 0, (int) len);
    return out;
  }

  /** Index of the last entry <= v, or -1. */
  private static int floor(long[] a, int n, long v) {
    int lo = 0, hi = Math.min(n, a.length) - 1, found = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (a[mid] <= v) {
        found = mid;
        lo = mid + 1;
      } else hi = mid - 1;
    }
    return found;
  }

  private static void sprms(byte[] b, int start, int len, Prl f) {
    int i = start, end = Math.min(b.length, start + len);
    while (i + 2 <= end) {
      int op = u16(b, i), a = i + 2, n;
      if (op == 0xC615) {
        if (a >= end) return;
        int first = u8(b, a);
        if (first == 255) {
          int del = u8(b, a + 1);
          n = 1 + 1 + 4 * del + 1 + 3 * u8(b, a + 2 + 4 * del);
        } else n = 1 + first;
      } else if (op == 0xD608) n = 1 + u16(b, a);
      else
        switch (op >>> 13) {
          case 0:
          case 1:
            n = 1;
            break;
          case 2:
          case 4:
          case 5:
            n = 2;
            break;
          case 3:
            n = 4;
            break;
          case 7:
            n = 3;
            break;
          case 6:
            if (a >= end) return;
            n = 1 + u8(b, a);
            break;
          default:
            return;
        }
      if (a + n > end) return;
      f.op(op, b, a, n);
      i = a + n;
    }
  }

  // ---- piece table and FKPs ----

  private void parsePieces() throws IOException {
    long[] p = pair(33);
    if (p == null || !in(table, p[0], p[1])) throw new IOException("Damaged .doc file: no piece table");
    int i = (int) p[0], end = (int) (p[0] + p[1]), start, lcb;
    while (true) {
      if (i >= end) throw new IOException("Damaged .doc piece table");
      int t = u8(table, i);
      if (t == 1) {
        int cb = u16(table, i + 1);
        if (i + 3 + cb > end) throw new IOException("Damaged .doc piece table");
        if (prc.size() < MAX_PIECES) prc.add(copy(table, i + 3, cb));
        i += 3 + cb;
      } else if (t == 2) {
        long l = u32(table, i + 1);
        start = i + 5;
        if (start + l > end) throw new IOException("Damaged .doc piece table");
        lcb = (int) l;
        break;
      } else throw new IOException("Damaged .doc piece table");
    }
    if (lcb < 4 || (lcb - 4) % 12 != 0) throw new IOException("Damaged .doc piece table");
    int n = (lcb - 4) / 12;
    if (n > MAX_PIECES) throw new IOException("Too many pieces in the .doc piece table");
    cps = new long[n + 1];
    pfc = new long[n];
    pcomp = new boolean[n];
    pprm = new int[n];
    for (int j = 0; j <= n; j++) cps[j] = Math.max(u32(table, start + j * 4), j == 0 ? 0 : cps[j - 1]);
    for (int j = 0; j < n; j++) {
      int at = start + (n + 1) * 4 + j * 8 + 2;
      long raw = u32(table, at);
      pfc[j] = raw & 0x3FFFFFFFL;
      pcomp[j] = (raw & 0x40000000L) != 0;
      pprm[j] = u16(table, at + 4);
    }
  }

  private int piece(long cp) {
    int j = floor(cps, cps.length, cp);
    return j >= 0 && j < pfc.length ? j : -1;
  }

  private long fcOf(long cp) {
    int j = piece(cp);
    if (j < 0) return -1;
    long off = cp - cps[j];
    return pcomp[j] ? pfc[j] / 2 + off : pfc[j] + off * 2;
  }

  /** The character at cp (sets units), or -1 with next set to the next readable cp. */
  private int charAt(long cp) {
    int j = floor(cps, cps.length, cp);
    if (j < 0) {
      next = cps.length > 0 ? cps[0] : Long.MAX_VALUE;
      return -1;
    }
    if (j >= pfc.length) {
      next = Long.MAX_VALUE;
      return -1;
    }
    long nx = cps[j + 1], off = cp - cps[j];
    units = 1;
    if (pcomp[j]) {
      long at = pfc[j] / 2 + off;
      if (at >= word.length) {
        next = nx;
        return -1;
      }
      int b = word[(int) at] & 0xff;
      return b >= 0x80 && b < 0xA0 ? CP1252.charAt(b - 0x80) : b;
    }
    long at = pfc[j] + off * 2;
    if (at + 2 > word.length) {
      next = nx;
      return -1;
    }
    int u = u16(word, at);
    if (u >= 0xD800 && u < 0xDC00 && cp + 1 < nx && at + 4 <= word.length) {
      int lo = u16(word, at + 2);
      if (lo >= 0xDC00 && lo < 0xE000) {
        units = 2;
        return 0x10000 + ((u - 0xD800) << 10) + (lo - 0xDC00);
      }
    }
    if (u >= 0xD800 && u < 0xE000 || u == 0xFFFE || u == 0xFFFF) return 0xFFFD;
    return u;
  }

  private long[] binPns;

  private long[] bins(int pair) {
    binPns = new long[0];
    long[] p = pair(pair);
    if (p == null || p[1] < 4 || (p[1] - 4) % 8 != 0 || !in(table, p[0], p[1])) return new long[0];
    int n = (int) ((p[1] - 4) / 8);
    if (n > MAX_BINS) return new long[0];
    var fcs = new long[n + 1];
    binPns = new long[n];
    for (int i = 0; i <= n; i++) fcs[i] = u32(table, p[0] + i * 4);
    for (int i = 0; i < n; i++) binPns[i] = u32(table, p[0] + (n + 1) * 4 + i * 4) & 0x3FFFFF;
    return fcs;
  }

  private int page(long[] fcs, long[] pns, long fc) {
    int j = floor(fcs, fcs.length, fc);
    if (j < 0 || j >= pns.length) return -1;
    long at = pns[j] * 512;
    return in(word, at, 512) ? (int) at : -1;
  }

  /** The CHPX grpprl {offset, length} in the WordDocument stream for fc, or null. */
  private int[] chpx(long fc) {
    int page = page(chpFcs, chpPns, fc);
    if (page < 0) return null;
    int crun = Math.min(u8(word, page + 511), 0x65), j = -1;
    for (int k = crun - 1; k >= 0; k--)
      if (u32(word, page + k * 4) <= fc) {
        j = k;
        break;
      }
    if (j < 0) return null;
    int off = u8(word, page + (crun + 1) * 4 + j);
    if (off == 0) return null;
    int at = page + off * 2, cb = Math.min(u8(word, at), Math.max(0, 511 - off * 2));
    return new int[] {at + 1, cb};
  }

  /** Locates the PAPX for fc into papIstd/papAt/papLen. */
  private void papx(long fc) {
    papIstd = 0;
    papAt = 0;
    papLen = 0;
    int page = page(papFcs, papPns, fc);
    if (page < 0) return;
    int crun = u8(word, page + 511);
    crun = Math.min(crun, (511 - (crun + 1) * 4) / 13);
    int j = -1;
    for (int k = crun - 1; k >= 0; k--)
      if (u32(word, page + k * 4) <= fc) {
        j = k;
        break;
      }
    if (j < 0) return;
    int off = u8(word, page + (crun + 1) * 4 + j * 13);
    if (off == 0) return;
    int at = page + off * 2, cb = u8(word, at), dataAt, len;
    if (cb != 0) {
      dataAt = at + 1;
      len = 2 * cb - 1;
    } else {
      dataAt = at + 2;
      len = 2 * u8(word, at + 1);
    }
    len = Math.min(len, page + 512 - dataAt);
    if (len < 2) return;
    papIstd = u16(word, dataAt);
    papAt = dataAt + 2;
    papLen = len - 2;
  }

  // ---- properties ----

  private static final String[] ICO = {
    null, "000000", "0000FF", "00FFFF", "00FF00", "FF00FF", "FF0000", "FFFF00", "FFFFFF", "000080", "008080",
    "008000", "800080", "800000", "808000", "808080", "C0C0C0"
  };
  private static final String[] HIGHLIGHT = {
    null, "black", "blue", "cyan", "green", "magenta", "red", "yellow", "white", "darkBlue", "darkCyan",
    "darkGreen", "darkMagenta", "darkRed", "darkYellow", "darkGray", "lightGray"
  };

  private static Boolean toggle(byte[] b, int at, Boolean old) {
    int v = u8(b, at);
    return v == 0 ? Boolean.FALSE : v == 0x80 ? old : Boolean.TRUE;
  }

  private static String hex(int r, int g, int b) {
    String s = Integer.toHexString(0x1000000 | r << 16 | g << 8 | b).toUpperCase();
    return s.substring(1);
  }

  private void applyChr(Chr c, int op, byte[] b, int a) {
    switch (op) {
      case 0x0835:
        c.b = toggle(b, a, c.b);
        break;
      case 0x0836:
        c.i = toggle(b, a, c.i);
        break;
      case 0x0837:
        c.strike = toggle(b, a, c.strike);
        break;
      case 0x083A:
        c.smallCaps = toggle(b, a, c.smallCaps);
        break;
      case 0x083B:
        c.caps = toggle(b, a, c.caps);
        break;
      case 0x083C:
        c.vanish = toggle(b, a, c.vanish);
        break;
      case 0x2A3E: {
        int k = u8(b, a);
        String[] kul = {"none", "single", "words", "double", "dotted", null, "thick", "dash", null, "dotDash",
          "dotDotDash", "wave"};
        c.u = k < kul.length && kul[k] != null ? kul[k] : "single";
        break;
      }
      case 0x2A42: {
        int k = u8(b, a);
        c.color = k > 0 && k < ICO.length ? ICO[k] : k == 0 ? "auto" : c.color;
        break;
      }
      case 0x6870:
        c.color = u8(b, a + 3) != 0 ? "auto" : hex(u8(b, a), u8(b, a + 1), u8(b, a + 2));
        break;
      case 0x4A43: {
        int v = u16(b, a);
        if (v > 0 && v <= 3276) c.sz = v;
        break;
      }
      case 0x4845: {
        int v = (byte) u8(b, a);
        c.vert = v > 0 ? "superscript" : v < 0 ? "subscript" : null;
        break;
      }
      case 0x2A48: {
        int v = u8(b, a);
        c.vert = v == 1 ? "superscript" : v == 2 ? "subscript" : null;
        break;
      }
      case 0x4A4F: {
        int v = u16(b, a);
        if (v < fonts.size() && !fonts.get(v).isEmpty()) c.font = Xml.escape(fonts.get(v));
        break;
      }
      case 0x2A0C: {
        int k = u8(b, a);
        c.highlight = k > 0 && k < HIGHLIGHT.length ? HIGHLIGHT[k] : null;
        break;
      }
      case 0x4A30: {
        int v = u16(b, a);
        if (v < styleIds.length && styleIds[v] != null && v < styles.size() && styles.get(v).stk == 2 && v != 10)
          c.style = styleIds[v];
        break;
      }
      default:
    }
  }

  private void applyPara(PPr p, int op, byte[] b, int a, int n) {
    switch (op) {
      case 0x2461:
      case 0x2403: {
        int v = u8(b, a);
        p.jc = v == 1 ? "center" : v == 2 ? "right" : v == 3 || v == 4 ? "both" : "left";
        break;
      }
      case 0x845E:
      case 0x840F:
        p.left = s16(b, a);
        break;
      case 0x845D:
      case 0x840E:
        p.right = s16(b, a);
        break;
      case 0x8460:
      case 0x8411:
        p.first = s16(b, a);
        break;
      case 0xA413:
        p.before = u16(b, a);
        break;
      case 0xA414:
        p.after = u16(b, a);
        break;
      case 0x6412: {
        int dya = s16(b, a), mult = s16(b, a + 2);
        if (mult != 0) {
          p.line = Math.max(1, Math.abs(dya));
          p.lineRule = "auto";
        } else {
          p.line = Math.max(1, Math.abs(dya));
          p.lineRule = dya < 0 ? "exact" : "atLeast";
        }
        break;
      }
      case 0x2405:
        p.keep = u8(b, a) != 0;
        break;
      case 0x2406:
        p.keepNext = u8(b, a) != 0;
        break;
      case 0x2407:
        p.pageBreak = u8(b, a) != 0;
        break;
      case 0x2640:
        p.outline = u8(b, a);
        break;
      case 0x260A:
        p.ilvl = u8(b, a);
        break;
      case 0x460B:
        p.ilfo = s16(b, a);
        break;
      case 0x2416:
        p.inTable = u8(b, a) != 0;
        break;
      case 0x2417:
        p.ttp = u8(b, a) != 0;
        break;
      case 0x244C:
        p.innerTtp = u8(b, a) != 0;
        break;
      case 0x6649:
        p.itap = (int) Math.min(64, Math.max(0, (int) u32(b, a)));
        if (p.itap > 0) p.inTable = true;
        break;
      case 0xD608: {
        int count = Math.min(63, u8(b, a + 2));
        if (count < 1 || n < 3 + (count + 1) * 2) break;
        p.grid = new int[count];
        for (int k = 0; k < count; k++)
          p.grid[k] = Math.max(1, s16(b, a + 3 + (k + 1) * 2) - s16(b, a + 3 + k * 2));
        int tc = a + 3 + (count + 1) * 2;
        for (int k = 0; k < count && tc + k * 20 + 20 <= a + n; k++)
          for (int e = 0; e < 4; e++) {
            long brc = u32(b, tc + k * 20 + 4 + e * 4);
            if (brc != 0 && brc != 0xFFFFFFFFL) p.borders = true;
          }
        break;
      }
      default:
    }
  }

  private void applyPrm(int j, Chr c, PPr p) {
    if (j < 0) return;
    int prm = pprm[j];
    if ((prm & 1) == 0) {
      int isprm = (prm >> 1) & 0x7F, op = fromPrm0(isprm);
      if (op == 0) return;
      byte[] v = {(byte) (prm >> 8), 0};
      if (c != null && (op >> 10 & 7) == 2) applyChr(c, op, v, 0);
      if (p != null && (op >> 10 & 7) == 1) applyPara(p, op, v, 0, 1);
    } else {
      int idx = prm >> 1;
      if (idx >= prc.size()) return;
      byte[] g = prc.get(idx);
      sprms(g, 0, g.length, (op, b, a, n) -> {
        if (c != null && (op >> 10 & 7) == 2) applyChr(c, op, b, a);
        if (p != null && (op >> 10 & 7) == 1) applyPara(p, op, b, a, n);
      });
    }
  }

  private static int fromPrm0(int isprm) {
    switch (isprm) {
      case 0x05: return 0x2461;
      case 0x07: return 0x2405;
      case 0x08: return 0x2406;
      case 0x09: return 0x2407;
      case 0x4D: return 0x2A0C;
      case 0x55: return 0x0835;
      case 0x56: return 0x0836;
      case 0x57: return 0x0837;
      case 0x5A: return 0x083A;
      case 0x5B: return 0x083B;
      case 0x5C: return 0x083C;
      case 0x5E: return 0x2A3E;
      case 0x62: return 0x2A42;
      default: return 0;
    }
  }

  private String rpr(long cp) {
    int j = piece(cp);
    if (j != cachePiece) {
      rprCache.clear();
      cachePiece = j;
    }
    int[] r = chpx(fcOf(cp));
    long key = r == null ? -1 : (long) r[0] << 8 | r[1];
    String s = rprCache.get(key);
    if (s != null) return s;
    Chr c = new Chr();
    if (r != null) sprms(word, r[0], r[1], (op, b, a, n) -> applyChr(c, op, b, a));
    applyPrm(j, c, null);
    s = c.xml();
    rprCache.put(key, s);
    return s;
  }

  // ---- fonts and styles ----

  private void parseFonts() {
    long[] p = pair(15);
    if (p == null || !in(table, p[0], p[1])) return;
    int base = (int) p[0], end = (int) (p[0] + p[1]), first = u16(table, base), count, extra, at;
    boolean ext = first == 0xFFFF;
    if (ext) {
      count = u16(table, base + 2);
      extra = u16(table, base + 4);
      at = base + 6;
    } else {
      count = first;
      extra = u16(table, base + 2);
      at = base + 4;
    }
    for (int i = 0; i < Math.min(count, 1024) && at < end; i++) {
      int cb = ext ? u16(table, at) * 2 : u8(table, at), start = at + (ext ? 2 : 1);
      int stop = Math.min(start + cb, end);
      var name = new StringBuilder();
      for (int k = start + 39; k + 1 < stop && name.length() < 64; k += 2) {
        int u = u16(table, k);
        if (u == 0) break;
        name.append(u >= 0xD800 && u < 0xE000 ? '�' : (char) u);
      }
      fonts.add(name.toString());
      at = start + cb + extra;
    }
  }

  private void parseStyles() {
    long[] p = pair(1);
    if (p == null || !in(table, p[0], p[1]) || p[1] < 4) {
      styleIds = new String[0];
      return;
    }
    int base = (int) p[0], end = (int) (p[0] + p[1]);
    int cbStshi = u16(table, base), cstd = Math.min(u16(table, base + 2), MAX_STYLES);
    int cbBase = u16(table, base + 4) == 0x12 ? 18 : 10;
    int at = base + 2 + cbStshi;
    for (int i = 0; i < cstd && at + 2 <= end; i++) {
      int cb = u16(table, at), std = at;
      at = Math.min(end, at + 2 + cb);
      var st = new Style();
      styles.add(st);
      if (cb < cbBase) continue;
      int stdEnd = at;
      st.stk = Math.max(1, u8(table, std + 2 + 2) & 0x0F);
      st.base = u16(table, std + 2 + 2) >> 4;
      int cupx = u16(table, std + 2 + 4) & 0x0F, k = std + 2 + cbBase;
      int cch = Math.min(256, u16(table, k));
      var name = new StringBuilder();
      for (int c = 0; c < cch && k + 2 + c * 2 + 1 < stdEnd; c++) {
        int u = u16(table, k + 2 + c * 2);
        name.append(u >= 0xD800 && u < 0xE000 ? '�' : (char) u);
      }
      st.name = name.toString();
      k += 2 + cch * 2 + 2;
      var upx = new ArrayList<byte[]>();
      for (int u = 0; u < Math.min(cupx, 3) && k + 2 <= stdEnd; u++) {
        int len = u16(table, k);
        upx.add(copy(table, k + 2, Math.min(len, Math.max(0, stdEnd - k - 2))));
        k += 2 + len + (len & 1);
      }
      if (st.stk == 1) {
        if (upx.size() > 0 && upx.get(0).length >= 2) st.papx = copy(upx.get(0), 2, upx.get(0).length - 2);
        if (upx.size() > 1) st.chpx = upx.get(1);
      } else if (st.stk == 2 && upx.size() > 0) st.chpx = upx.get(0);
    }
    styleIds = new String[styles.size()];
    var used = new HashSet<String>();
    for (int i = 0; i < styles.size(); i++) {
      Style st = styles.get(i);
      if (st.name.isEmpty() || (st.stk != 1 && st.stk != 2)) continue;
      String id = styleId(st.name);
      if (id.isEmpty()) id = "Style" + i;
      if (!used.add(id)) {
        id = id + i;
        used.add(id);
      }
      styleIds[i] = id;
    }
  }

  static String styleId(String name) {
    var s = new StringBuilder();
    boolean up = true;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == ',') break;
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
        s.append(up ? Character.toUpperCase(c) : c);
        up = false;
      } else up = true;
    }
    return s.toString();
  }

  private String stylesXml() {
    String font = fonts.isEmpty() || fonts.get(0).isEmpty() ? "Times New Roman" : Xml.escape(fonts.get(0));
    var s = new StringBuilder(xmlDecl() + "<w:styles" + NS + "><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"")
        .append(font).append("\" w:hAnsi=\"").append(font)
        .append("\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault/></w:docDefaults>");
    for (int i = 0; i < styles.size(); i++) {
      if (styleIds[i] == null) continue;
      Style st = styles.get(i);
      String type = st.stk == 1 ? "paragraph" : "character";
      s.append("<w:style w:type=\"").append(type).append("\" w:styleId=\"").append(styleIds[i]).append('"');
      if (i == 0 || i == 10) s.append(" w:default=\"1\"");
      s.append("><w:name w:val=\"").append(Xml.escape(st.name)).append("\"/>");
      if (st.base < styles.size() && st.base != i && styleIds[st.base] != null
          && styles.get(st.base).stk == st.stk)
        s.append("<w:basedOn w:val=\"").append(styleIds[st.base]).append("\"/>");
      if (st.stk == 1) {
        PPr p = new PPr();
        sprms(st.papx, 0, st.papx.length, (op, b, a, n) -> applyPara(p, op, b, a, n));
        String x = p.xml(lfoCount);
        if (!x.isEmpty()) s.append("<w:pPr>").append(x).append("</w:pPr>");
      }
      Chr c = new Chr();
      sprms(st.chpx, 0, st.chpx.length, (op, b, a, n) -> applyChr(c, op, b, a));
      c.style = null;
      s.append(c.xml()).append("</w:style>");
    }
    return s.append("</w:styles>").toString();
  }

  // ---- lists ----

  private static final class Lvl {
    long start;
    int nfc, jc, follow, len;
    byte[] papx, chpx;
    int[] xst;
  }

  private Lvl lvl(int at) {
    if (!in(table, at, 28)) return null;
    var l = new Lvl();
    l.start = Math.min(u32(table, at), 0x7FFF);
    l.nfc = u8(table, at + 4);
    l.jc = u8(table, at + 5) & 3;
    l.follow = u8(table, at + 15);
    int cbChpx = u8(table, at + 24), cbPapx = u8(table, at + 25), k = at + 28;
    l.papx = copy(table, k, cbPapx);
    k += cbPapx;
    l.chpx = copy(table, k, cbChpx);
    k += cbChpx;
    int cch = Math.min(64, u16(table, k));
    if (!in(table, k + 2, cch * 2L)) return null;
    l.xst = new int[cch];
    for (int i = 0; i < cch; i++) l.xst[i] = u16(table, k + 2 + i * 2);
    l.len = 28 + cbPapx + cbChpx + 2 + cch * 2;
    return l;
  }

  private static String numFmt(int nfc) {
    switch (nfc) {
      case 0: return "decimal";
      case 1: return "upperRoman";
      case 2: return "lowerRoman";
      case 3: return "upperLetter";
      case 4: return "lowerLetter";
      case 5: return "ordinal";
      case 6: return "cardinalText";
      case 7: return "ordinalText";
      case 22: return "decimalZero";
      case 23: return "bullet";
      case 255: return "none";
      default: return "decimal";
    }
  }

  private static char bullet(int c) {
    switch (c & 0x0FFF) {
      case 0xB7: return '•';
      case 0xA7: return '▪';
      case 0x6F: return '○';
      case 0x77:
      case 0x78: return '●';
      default: return c >= 0xF000 || c < 0x20 || (c >= 0xD800 && c < 0xE000) ? '•' : (char) c;
    }
  }

  private String lvlXml(Lvl l, int ilvl) {
    var s = new StringBuilder("<w:lvl w:ilvl=\"").append(ilvl).append("\"><w:start w:val=\"").append(l.start)
        .append("\"/><w:numFmt w:val=\"").append(numFmt(l.nfc)).append("\"/><w:suff w:val=\"")
        .append(l.follow == 1 ? "space" : l.follow == 2 ? "nothing" : "tab").append("\"/>");
    var text = new StringBuilder();
    boolean bulleted = l.nfc == 23;
    for (int c : l.xst) {
      if (c < 9 && !bulleted) text.append('%').append(c + 1);
      else text.append(bulleted ? bullet(c) : c < 0x20 || (c >= 0xD800 && c < 0xE000) ? '�' : (char) c);
    }
    s.append("<w:lvlText w:val=\"").append(Xml.escape(text.toString())).append("\"/><w:lvlJc w:val=\"")
        .append(l.jc == 1 ? "center" : l.jc == 2 ? "right" : "left").append("\"/>");
    PPr p = new PPr();
    sprms(l.papx, 0, l.papx.length, (op, b, a, n) -> applyPara(p, op, b, a, n));
    if (p.left != null || p.first != null) {
      s.append("<w:pPr><w:ind");
      if (p.left != null) s.append(" w:left=\"").append(p.left).append('"');
      if (p.first != null) s.append(p.first < 0 ? " w:hanging=\"" : " w:firstLine=\"").append(Math.abs(p.first)).append('"');
      s.append("/></w:pPr>");
    }
    Chr c = new Chr();
    sprms(l.chpx, 0, l.chpx.length, (op, b, a, n) -> applyChr(c, op, b, a));
    if (bulleted) c.font = null;
    c.style = null;
    return s.append(c.xml()).append("</w:lvl>").toString();
  }

  private String numbering() {
    long[] p = pair(73);
    if (p == null) return null;
    int at = (int) Math.min(p[0], Integer.MAX_VALUE), count = u16(table, at);
    if (count == 0 || count > MAX_LISTS || !in(table, at, 2 + count * 28L)) return null;
    var lsids = new long[count];
    var abstracts = new StringBuilder();
    int off = at + 2 + count * 28;
    var levels = new ArrayList<List<Lvl>>();
    for (int i = 0; i < count; i++) {
      int f = at + 2 + i * 28;
      lsids[i] = u32(table, f);
      boolean simple = (u8(table, f + 26) & 1) != 0;
      var ls = new ArrayList<Lvl>();
      for (int k = 0; k < (simple ? 1 : 9); k++) {
        Lvl l = lvl(off);
        if (l == null) break;
        ls.add(l);
        off += l.len;
      }
      levels.add(ls);
    }
    for (int i = 0; i < count; i++) {
      abstracts.append("<w:abstractNum w:abstractNumId=\"").append(i).append("\">");
      for (int k = 0; k < levels.get(i).size(); k++) abstracts.append(lvlXml(levels.get(i).get(k), k));
      abstracts.append("</w:abstractNum>");
    }
    var nums = new StringBuilder();
    long[] q = pair(74);
    if (q != null) {
      int lat = (int) Math.min(q[0], Integer.MAX_VALUE);
      int mac = (int) Math.min(u32(table, lat), MAX_LISTS);
      if (!in(table, lat, 4 + mac * 16L)) mac = 0;
      int dat = lat + 4 + mac * 16;
      int abstractIds = count;
      for (int i = 0; i < mac; i++) {
        int f = lat + 4 + i * 16, clfolvl = u8(table, f + 12), def = -1;
        long lsid = u32(table, f);
        for (int k = 0; k < count && def < 0; k++) if (lsids[k] == lsid) def = k;
        var overrides = new StringBuilder();
        Lvl[] formatted = new Lvl[9];
        if (clfolvl > 0) {
          dat += 4;
          for (int k = 0; k < clfolvl; k++) {
            if (!in(table, dat, 8)) break;
            long start = Math.min(u32(table, dat), 0x7FFF);
            int flags = u8(table, dat + 4), ilvl = flags & 0x0F;
            dat += 8;
            if ((flags & 0x20) != 0) {
              Lvl l = lvl(dat);
              if (l == null) break;
              dat += l.len;
              if (ilvl < 9) formatted[ilvl] = l;
            }
            if ((flags & 0x10) != 0 && ilvl < 9)
              overrides.append("<w:lvlOverride w:ilvl=\"").append(ilvl).append("\"><w:startOverride w:val=\"")
                  .append(start).append("\"/></w:lvlOverride>");
          }
        } else dat += 4;
        if (def < 0) def = 0;
        int abstractId = def;
        boolean anyFormat = false;
        for (Lvl l : formatted) anyFormat |= l != null;
        if (anyFormat) {
          abstractId = abstractIds++;
          abstracts.append("<w:abstractNum w:abstractNumId=\"").append(abstractId).append("\">");
          for (int k = 0; k < levels.get(def).size(); k++)
            abstracts.append(lvlXml(formatted[k] != null ? formatted[k] : levels.get(def).get(k), k));
          abstracts.append("</w:abstractNum>");
        }
        nums.append("<w:num w:numId=\"").append(i + 1).append("\"><w:abstractNumId w:val=\"").append(abstractId)
            .append("\"/>").append(overrides).append("</w:num>");
        lfoCount = i + 1;
      }
    }
    if (lfoCount == 0) return null;
    return xmlDecl() + "<w:numbering" + NS + ">" + abstracts + nums + "</w:numbering>";
  }

  // ---- PLCs, notes and sections ----

  /** The CPs of a PLC with data elements of the given size. */
  private List<long[]> plc(int pair, int size) {
    var out = new ArrayList<long[]>();
    long[] p = pair(pair);
    if (p == null || p[1] < 4 || !in(table, p[0], p[1])) return out;
    long n = (p[1] - size) / (4 + size);
    for (int i = 0; i <= Math.min(n, MAX_SECTIONS * 6 + 12L + MAX_NOTES); i++) {
      if (p[0] + i * 4L + 4 > p[0] + p[1]) break;
      out.add(new long[] {u32(table, p[0] + i * 4L)});
    }
    return out;
  }

  private String notes(int refPair, int txtPair, long base, long len, String kind, Map<Long, Integer> refs)
      throws IOException {
    List<long[]> cp = plc(refPair, 2), txt = plc(txtPair, 0);
    if (txt.size() < 2 || cp.isEmpty()) return null;
    var s = new StringBuilder(xmlDecl() + "<w:" + kind + "s" + NS + ">");
    s.append("<w:").append(kind).append(" w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:")
        .append(kind).append('>');
    s.append("<w:").append(kind).append(" w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:")
        .append(kind).append('>');
    int id = 0;
    long n = (pair(refPair)[1] - 4) / 6;
    for (int i = 0; i < Math.min(n, MAX_NOTES) && i + 1 < txt.size(); i++) {
      long a = Math.min(txt.get(i)[0], len), b = Math.min(txt.get(i + 1)[0], len);
      if (b <= a) continue;
      String body = blocks(walk(base + a, base + b, kind));
      if (body.isEmpty()) continue;
      refs.put(cp.get(i)[0], ++id);
      s.append("<w:").append(kind).append(" w:id=\"").append(id).append("\">").append(body).append("</w:").append(kind).append('>');
    }
    return id == 0 ? null : s.append("</w:").append(kind).append("s>").toString();
  }

  /** Sections as {endCp, bkc, titlePg, cols, pgnStart, orient, hdr, ftr, w, h, left, right, top, bottom, gutter}. */
  private List<long[]> sections() {
    var out = new ArrayList<long[]>();
    long[] p = pair(6);
    if (p != null && p[1] >= 4 && (p[1] - 4) % 16 == 0 && in(table, p[0], p[1])) {
      int n = (int) Math.min((p[1] - 4) / 16, MAX_SECTIONS);
      long total = (p[1] - 4) / 16;
      for (int i = 0; i < n; i++) {
        long[] s = {u32(table, p[0] + (i + 1) * 4L), 2, 0, 1, -1, 0, 720, 720, 12240, 15840, 1800, 1800, 1440, 1440, 0};
        long fcSepx = u32(table, p[0] + (total + 1) * 4 + i * 12L + 2);
        if (fcSepx != 0xFFFFFFFFL && in(word, fcSepx, 2)) {
          int cb = u16(word, fcSepx);
          if (in(word, fcSepx + 2, cb))
            sprms(word, (int) fcSepx + 2, cb, (op, b, a, len) -> section(s, op, b, a));
        }
        out.add(s);
      }
    }
    if (out.isEmpty()) out.add(new long[] {ccpText, 2, 0, 1, -1, 0, 720, 720, 12240, 15840, 1800, 1800, 1440, 1440, 0});
    return out;
  }

  private static void section(long[] s, int op, byte[] b, int a) {
    switch (op) {
      case 0x3009: s[1] = u8(b, a); break;
      case 0x300A: s[2] = u8(b, a); break;
      case 0x500B: s[3] = Math.min(44, Math.max(1, s16(b, a) + 1)); break;
      case 0x300C: s[4] = Math.max(0, s16(b, a)); break;
      case 0x301D: s[5] = u8(b, a); break;
      case 0xB017: s[6] = clampTw(u16(b, a)); break;
      case 0xB018: s[7] = clampTw(u16(b, a)); break;
      case 0xB01F: s[8] = Math.max(144, clampTw(u16(b, a))); break;
      case 0xB020: s[9] = Math.max(144, clampTw(u16(b, a))); break;
      case 0xB021: s[10] = clampTw(u16(b, a)); break;
      case 0xB022: s[11] = clampTw(u16(b, a)); break;
      case 0x9023: s[12] = s16(b, a); break;
      case 0x9024: s[13] = s16(b, a); break;
      case 0xB025: s[14] = clampTw(u16(b, a)); break;
      default:
    }
  }

  private static long clampTw(int v) {
    return Math.max(0, Math.min(31680, v));
  }

  private static String sectPr(long[] s, String[] refs) {
    var x = new StringBuilder("<w:sectPr>");
    for (int k : new int[] {1, 0, 4}) if (refs[k] != null) x.append(refs[k]);
    for (int k : new int[] {3, 2, 5}) if (refs[k] != null) x.append(refs[k]);
    int bkc = (int) s[1];
    x.append("<w:type w:val=\"").append(bkc == 0 ? "continuous" : bkc == 1 ? "nextColumn" : bkc == 3 ? "evenPage"
        : bkc == 4 ? "oddPage" : "nextPage").append("\"/>");
    boolean landscape = s[5] == 2 || (s[5] == 0 && s[8] > s[9]);
    x.append("<w:pgSz w:w=\"").append(s[8]).append("\" w:h=\"").append(s[9]).append('"');
    if (landscape) x.append(" w:orient=\"landscape\"");
    x.append("/><w:pgMar w:top=\"").append(s[12]).append("\" w:right=\"").append(s[11]).append("\" w:bottom=\"")
        .append(s[13]).append("\" w:left=\"").append(s[10]).append("\" w:header=\"").append(s[6])
        .append("\" w:footer=\"").append(s[7]).append("\" w:gutter=\"").append(s[14]).append("\"/>");
    if (s[4] >= 0) x.append("<w:pgNumType w:start=\"").append(s[4]).append("\"/>");
    if (s[3] > 1) x.append("<w:cols w:num=\"").append(s[3]).append("\"/>");
    if (s[2] != 0) x.append("<w:titlePg/>");
    return x.append("</w:sectPr>").toString();
  }

  // ---- text walk ----

  private static final class Ended {
    final Para para;
    final long end;

    Ended(Para para, long end) {
      this.para = para;
      this.end = end;
    }
  }

  /** Builds runs for one paragraph, merging equal formatting. */
  private static final class Runs {
    final StringBuilder out;
    String rPr;
    boolean instr;
    final StringBuilder text = new StringBuilder();

    Runs(StringBuilder out) {
      this.out = out;
    }

    void text(int c, String props, boolean instruction) {
      if (text.length() > 0 && (!props.equals(rPr) || instruction != instr)) flush();
      rPr = props;
      instr = instruction;
      if (c == '&') text.append("&amp;");
      else if (c == '<') text.append("&lt;");
      else if (c == '>') text.append("&gt;");
      else if (c >= 0x10000) {
        text.append((char) (0xD800 + ((c - 0x10000) >> 10))).append((char) (0xDC00 + ((c - 0x10000) & 0x3FF)));
      } else text.append((char) c);
    }

    void element(String props, String inner) {
      flush();
      out.append("<w:r>").append(props).append(inner).append("</w:r>");
    }

    void flush() {
      if (text.length() == 0) return;
      String tag = instr ? "w:instrText" : "w:t";
      out.append("<w:r>").append(rPr).append('<').append(tag).append(" xml:space=\"preserve\">").append(text)
          .append("</").append(tag).append("></w:r>");
      text.setLength(0);
    }
  }

  private List<Para> walk(long a, long b, String noteKind) throws IOException {
    var out = new ArrayList<Para>();
    for (Ended e : walkEnded(a, b, noteKind)) out.add(e.para);
    return out;
  }

  private List<Ended> walkEnded(long a, long b, String noteKind) throws IOException {
    var out = new ArrayList<Ended>();
    long end = Math.min(b, cps.length == 0 ? 0 : cps[cps.length - 1]), cp = a;
    var para = new Para();
    var runs = new Runs(para.runs);
    var fields = new ArrayList<Boolean>();
    boolean main = noteKind == null && a == 0;
    while (cp < end) {
      int c = charAt(cp);
      if (c < 0) {
        cp = Math.max(cp + 1, next);
        continue;
      }
      if (++budget > MAX_CHARS) throw new IOException("The .doc file is too large");
      int step = units;
      boolean sectMark = c == 12 && main && sectEnds.contains(cp + 1);
      boolean instr = !fields.isEmpty() && fields.get(fields.size() - 1);
      if (c == 13 || c == 7 || sectMark) {
        runs.flush();
        papx(fcOf(cp));
        PPr p = new PPr();
        int istd = papIstd;
        if (papLen > 0) sprms(word, papAt, papLen, (op, bb, at, n) -> applyPara(p, op, bb, at, n));
        applyPrm(piece(cp), null, p);
        if (istd < styleIds.length && styleIds[istd] != null && styles.get(istd).stk == 1 && istd != 0)
          p.style = styleIds[istd];
        para.inTable = p.inTable;
        para.borders = p.borders;
        if (p.inTable && c == 7) {
          if (p.itap > 1) {
            // nested tables are flattened into the outer cell
          } else if (p.ttp) para.rowEnd = true;
          else para.cellEnd = true;
        }
        if (p.inTable && p.ttp && p.itap <= 1) para.rowEnd = true;
        para.grid = p.grid;
        para.pPr = p.xml(lfoCount);
        out.add(new Ended(para, cp + 1));
        para = new Para();
        runs = new Runs(para.runs);
        cp += step;
        continue;
      }
      switch (c) {
        case 0x13:
          runs.element("", "<w:fldChar w:fldCharType=\"begin\"/>");
          fields.add(Boolean.TRUE);
          break;
        case 0x14:
          if (instr) {
            fields.set(fields.size() - 1, Boolean.FALSE);
            runs.element("", "<w:fldChar w:fldCharType=\"separate\"/>");
          }
          break;
        case 0x15:
          if (!fields.isEmpty()) {
            fields.remove(fields.size() - 1);
            runs.element("", "<w:fldChar w:fldCharType=\"end\"/>");
          }
          break;
        case 0x01: {
          int[] r = chpx(fcOf(cp));
          long[] loc = {-1};
          boolean[] spec = {false};
          if (r != null)
            sprms(word, r[0], r[1], (op, bb, at, n) -> {
              if (op == 0x6A03) loc[0] = u32(bb, at);
              if (op == 0x0855) spec[0] = u8(bb, at) != 0;
            });
          String pic = loc[0] >= 0 && !instr ? picture(loc[0]) : null;
          if (pic != null) runs.element(rpr(cp), pic);
          break;
        }
        case 0x02:
          if (noteKind != null)
            runs.element("<w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>", "<w:" + noteKind + "Ref/>");
          else if (main) {
            Integer f = footRefs.get(cp), e = endRefs.get(cp);
            if (f != null)
              runs.element("<w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>", "<w:footnoteReference w:id=\"" + f + "\"/>");
            else if (e != null)
              runs.element("<w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>", "<w:endnoteReference w:id=\"" + e + "\"/>");
          }
          break;
        case 0x09:
          if (instr) runs.text(' ', rpr(cp), true);
          else runs.element(rpr(cp), "<w:tab/>");
          break;
        case 0x0B:
          runs.element(rpr(cp), "<w:br/>");
          break;
        case 0x0C:
          runs.element(rpr(cp), "<w:br w:type=\"page\"/>");
          break;
        case 0x0E:
          runs.element(rpr(cp), "<w:br w:type=\"column\"/>");
          break;
        case 0x1E:
          runs.element(rpr(cp), "<w:noBreakHyphen/>");
          break;
        case 0x1F:
          runs.element(rpr(cp), "<w:softHyphen/>");
          break;
        default:
          if (c >= 0x20) runs.text(c, rpr(cp), instr);
      }
      cp += step;
    }
    runs.flush();
    for (int i = 0; i < fields.size(); i++) runs.element("", "<w:fldChar w:fldCharType=\"end\"/>");
    if (para.runs.length() > 0) out.add(new Ended(para, end));
    return out;
  }

  private String blocks(List<Para> ps) {
    var out = new StringBuilder();
    StringBuilder rows = null, cell = null;
    var cells = new ArrayList<String>();
    int[] grid = null;
    boolean borders = false;
    for (Para p : ps) {
      if (p.inTable) {
        if (rows == null) {
          rows = new StringBuilder();
          cell = new StringBuilder();
          grid = null;
          borders = false;
        }
        if (p.rowEnd) {
          if (cell.length() > 0) cells.add(cell.toString());
          cell = new StringBuilder();
          rows.append(row(cells, p.grid));
          if (grid == null || (p.grid != null && p.grid.length > grid.length)) grid = p.grid;
          borders |= p.borders;
          cells.clear();
          continue;
        }
        cell.append(p.xml());
        if (p.cellEnd) {
          cells.add(cell.toString());
          cell = new StringBuilder();
        }
        continue;
      }
      if (rows != null) {
        out.append(table(rows, cells, cell, grid, borders));
        rows = null;
        cells.clear();
      }
      out.append(p.xml());
    }
    if (rows != null) out.append(table(rows, cells, cell, grid, borders));
    return out.toString();
  }

  private static String row(List<String> cells, int[] grid) {
    var s = new StringBuilder("<w:tr>");
    if (cells.isEmpty()) s.append("<w:tc><w:p/></w:tc>");
    for (int i = 0; i < cells.size(); i++) {
      s.append("<w:tc>");
      if (grid != null && i < grid.length)
        s.append("<w:tcPr><w:tcW w:w=\"").append(grid[i]).append("\" w:type=\"dxa\"/></w:tcPr>");
      s.append(cells.get(i).isEmpty() ? "<w:p/>" : cells.get(i)).append("</w:tc>");
    }
    return s.append("</w:tr>").toString();
  }

  private static String table(StringBuilder rows, List<String> cells, StringBuilder cell, int[] grid, boolean borders) {
    if (cell.length() > 0) cells.add(cell.toString());
    if (!cells.isEmpty()) rows.append(row(cells, grid));
    if (rows.length() == 0) return "";
    var s = new StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>");
    if (borders) {
      s.append("<w:tblBorders>");
      for (String side : new String[] {"top", "left", "bottom", "right", "insideH", "insideV"})
        s.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>");
      s.append("</w:tblBorders>");
    }
    s.append("</w:tblPr><w:tblGrid>");
    if (grid != null) for (int w : grid) s.append("<w:gridCol w:w=\"").append(w).append("\"/>");
    return s.append("</w:tblGrid>").append(rows).append("</w:tbl>").toString();
  }

  // ---- pictures ----

  private String picture(long fc) {
    if (!in(data, fc, 0x44) || pictures >= MAX_PARTS) return null;
    long lcb = u32(data, fc);
    int cbHeader = u16(data, fc + 4), mm = u16(data, fc + 6);
    if (cbHeader < 0x44 || lcb < cbHeader) return null;
    long end = Math.min(data.length, fc + lcb), at = fc + cbHeader;
    if (mm == 0x66) at += 1 + u8(data, at);
    int dxa = s16(data, fc + 0x1C), dya = s16(data, fc + 0x1E), mx = u16(data, fc + 0x20), my = u16(data, fc + 0x22);
    String[] ext = new String[1];
    byte[] blip = blip(at, end, 0, ext);
    if (blip == null) return null;
    long cx = (long) (dxa > 0 ? dxa : 1440) * (mx > 0 ? mx : 1000) / 1000 * 635;
    long cy = (long) (dya > 0 ? dya : 1440) * (my > 0 ? my : 1000) / 1000 * 635;
    cx = Math.max(12700, Math.min(cx, 20116800L));
    cy = Math.max(12700, Math.min(cy, 20116800L));
    int n = ++pictures;
    String name = "media/image" + n + "." + ext[0];
    parts.put("word/" + name, blip);
    String id = rel("image", name);
    return "<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"" + cx + "\" cy=\""
        + cy + "\"/><wp:docPr id=\"" + n + "\" name=\"Picture " + n + "\"/>"
        + "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
        + "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
        + "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr>"
        + "<pic:cNvPr id=\"" + n + "\" name=\"image" + n + "\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill>"
        + "<a:blip r:embed=\"" + id + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr>"
        + "<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
        + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>"
        + "</wp:inline></w:drawing>";
  }

  /** Finds the first PNG or JPEG BLIP in the OfficeArt records between at and end. */
  private byte[] blip(long at, long end, int depth, String[] ext) {
    if (depth > 8) return null;
    for (int guard = 0; at + 8 <= end && guard < 1000; guard++) {
      int verInst = u16(data, at), type = u16(data, at + 2);
      long len = u32(data, at + 4), body = at + 8, stop = Math.min(end, body + len);
      int inst = verInst >> 4;
      if ((verInst & 0xF) == 0xF) {
        byte[] found = blip(body, stop, depth + 1, ext);
        if (found != null) return found;
      } else if (type == 0xF007) {
        byte[] found = blip(body + 36 + u8(data, body + 33), stop, depth + 1, ext);
        if (found != null) return found;
      } else if (type == 0xF01E || type == 0xF01D) {
        boolean two = type == 0xF01E ? inst == 0x6E1 : inst == 0x46B || inst == 0x6E3;
        long from = body + (two ? 32 : 16) + 1;
        if (from < stop) {
          byte[] b = copy(data, from, stop - from);
          boolean png = b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
          boolean jpg = b.length > 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8;
          if (png || jpg) {
            ext[0] = png ? "png" : "jpeg";
            return b;
          }
        }
      }
      if (len > end - body) break;
      at = body + len;
    }
    return null;
  }
}
