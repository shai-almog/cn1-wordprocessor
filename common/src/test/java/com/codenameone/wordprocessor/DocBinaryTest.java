package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import org.junit.Test;

public class DocBinaryTest {
  private static final byte[] PNG = bytes(
      0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 1, 0, 0, 0, 1,
      8, 6, 0, 0, 0, 0x1F, 0x15, 0xC4, 0x89, 0, 0, 0, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0, 1, 0, 0,
      5, 0, 1, 0x0D, 0x0A, 0x2D, 0xB4, 0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82);

  private static byte[] bytes(int... v) {
    var b = new byte[v.length];
    for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
    return b;
  }

  /** A minimal Word 97 writer: one or two pieces, CHPX/PAPX FKPs and table-stream structures. */
  static final class DocWriter {
    final StringBuilder text = new StringBuilder();
    final List<byte[]> chpx = new ArrayList<>();
    final TreeMap<Integer, byte[]> papx = new TreeMap<>();
    final Map<Integer, byte[]> tables = new TreeMap<>();
    final List<byte[]> sepx = new ArrayList<>();
    int ccpText, ccpFtn, ccpHdd, ccpAtn, ccpEdn, compressed, flags = 0x0200;
    byte[] data;

    DocWriter run(String s, int... grpprl) {
      for (int i = 0; i < s.length(); i++) {
        text.append(s.charAt(i));
        chpx.add(bytes(grpprl));
      }
      return this;
    }

    DocWriter para(int istd, int... grpprl) {
      return mark('\r', istd, grpprl);
    }

    DocWriter mark(char c, int istd, int... grpprl) {
      papx.put(text.length(), cat(le16(istd), bytes(grpprl)));
      text.append(c);
      chpx.add(new byte[0]);
      return this;
    }

    int cp() {
      return text.length();
    }

    int fc(int cp) {
      if (cp < compressed) return 1024 + cp;
      return utf16Start() + (cp - compressed) * 2;
    }

    int utf16Start() {
      return compressed == 0 ? 1024 : (1024 + compressed + 1) & ~1;
    }

    int width(int cp) {
      return cp < compressed ? 1 : 2;
    }

    byte[] build() throws IOException {
      var w = new ByteArrayOutputStream();
      w.write(new byte[1024]);
      for (int i = 0; i < compressed; i++) w.write(text.charAt(i) == '€' ? 0x80 : text.charAt(i));
      while (w.size() < utf16Start()) w.write(0);
      for (int i = compressed; i < text.length(); i++) w.write(le16(text.charAt(i)));
      var sepxAt = new ArrayList<Integer>();
      for (byte[] s : sepx) {
        while (w.size() % 2 != 0) w.write(0);
        sepxAt.add(w.size());
        w.write(le16(s.length));
        w.write(s);
      }
      while (w.size() % 512 != 0) w.write(0);
      // CHPX FKPs: runs of equal grpprl, 20 per page
      var bteC = new ByteArrayOutputStream();
      var pnC = new ArrayList<Integer>();
      var runs = new ArrayList<int[]>();
      for (int i = 0; i < text.length(); ) {
        int j = i;
        while (j + 1 < text.length() && Arrays.equals(chpx.get(j + 1), chpx.get(i))) j++;
        runs.add(new int[] {i, j});
        i = j + 1;
      }
      for (int p = 0; p < runs.size(); p += 20) {
        var page = new byte[512];
        var chunk = runs.subList(p, Math.min(runs.size(), p + 20));
        int crun = chunk.size(), pos = 511;
        for (int k = 0; k < crun; k++) {
          put32(page, k * 4, fc(chunk.get(k)[0]));
          byte[] g = chpx.get(chunk.get(k)[0]);
          if (g.length > 0) {
            pos = (pos - 1 - g.length) & ~1;
            page[pos] = (byte) g.length;
            System.arraycopy(g, 0, page, pos + 1, g.length);
            page[(crun + 1) * 4 + k] = (byte) (pos / 2);
          }
        }
        int[] last = chunk.get(crun - 1);
        put32(page, crun * 4, fc(last[1]) + width(last[1]));
        page[511] = (byte) crun;
        bteC.write(le32(fc(chunk.get(0)[0])));
        pnC.add(w.size() / 512);
        w.write(page);
      }
      int textEnd = fc(text.length() - 1) + width(text.length() - 1);
      bteC.write(le32(textEnd));
      for (int pn : pnC) bteC.write(le32(pn));
      // PAPX FKPs, 8 paragraphs per page
      var bteP = new ByteArrayOutputStream();
      var pnP = new ArrayList<Integer>();
      var marks = new ArrayList<>(papx.keySet());
      for (int p = 0; p < marks.size(); p += 8) {
        var page = new byte[512];
        int crun = Math.min(8, marks.size() - p), pos = 511;
        for (int k = 0; k < crun; k++) {
          int mark = marks.get(p + k), start = p + k == 0 ? 0 : marks.get(p + k - 1) + 1;
          put32(page, k * 4, fc(start));
          byte[] d = papx.get(mark);
          int size = d.length % 2 == 1 ? 1 + d.length : 2 + d.length;
          pos = (pos - size) & ~1;
          if (d.length % 2 == 1) page[pos] = (byte) ((d.length + 1) / 2);
          else {
            page[pos] = 0;
            page[pos + 1] = (byte) (d.length / 2);
          }
          System.arraycopy(d, 0, page, pos + size - d.length, d.length);
          page[(crun + 1) * 4 + k * 13] = (byte) (pos / 2);
        }
        int lastMark = marks.get(p + crun - 1);
        put32(page, crun * 4, fc(lastMark) + width(lastMark));
        page[511] = (byte) crun;
        bteP.write(le32(fc(p == 0 ? 0 : marks.get(p - 1) + 1)));
        pnP.add(w.size() / 512);
        w.write(page);
      }
      bteP.write(le32(textEnd));
      for (int pn : pnP) bteP.write(le32(pn));
      // Table stream
      var clx = new ByteArrayOutputStream();
      clx.write(2);
      int pieces = compressed > 0 ? 2 : 1;
      clx.write(le32(4 + pieces * 12));
      if (pieces == 2) {
        clx.write(le32(0));
        clx.write(le32(compressed));
      } else clx.write(le32(0));
      clx.write(le32(text.length()));
      if (pieces == 2) {
        clx.write(le16(0));
        clx.write(le32((1024 * 2) | 0x40000000));
        clx.write(le16(0));
      }
      clx.write(le16(0));
      clx.write(le32(utf16Start()));
      clx.write(le16(0));
      tables.put(33, clx.toByteArray());
      tables.put(12, bteC.toByteArray());
      tables.put(13, bteP.toByteArray());
      if (!sepx.isEmpty()) {
        // PlcfSed: the CPs were placed by the caller in tables[6]; fill in the SEDs
        byte[] cps = tables.get(6);
        var sed = new ByteArrayOutputStream();
        sed.write(cps);
        for (int at : sepxAt) {
          sed.write(le16(0));
          sed.write(le32(at));
          sed.write(le16(0));
          sed.write(le32(-1));
        }
        tables.put(6, sed.toByteArray());
      }
      var t = new ByteArrayOutputStream();
      var where = new TreeMap<Integer, int[]>();
      for (var e : tables.entrySet()) {
        while (t.size() % 2 != 0) t.write(0);
        where.put(e.getKey(), new int[] {t.size(), e.getValue().length});
        t.write(e.getValue());
      }
      byte[] wd = w.toByteArray();
      put16(wd, 0, 0xA5EC);
      put16(wd, 2, 0x00C1);
      put16(wd, 0x0A, flags);
      put16(wd, 0x20, 14);
      put16(wd, 0x3E, 22);
      put32(wd, 0x40, wd.length);
      put32(wd, 0x40 + 3 * 4, ccpText);
      put32(wd, 0x40 + 4 * 4, ccpFtn);
      put32(wd, 0x40 + 5 * 4, ccpHdd);
      put32(wd, 0x40 + 7 * 4, ccpAtn);
      put32(wd, 0x40 + 8 * 4, ccpEdn);
      put16(wd, 0x98, 0x5D);
      for (var e : where.entrySet()) {
        put32(wd, 0x9A + e.getKey() * 8, e.getValue()[0]);
        put32(wd, 0x9A + e.getKey() * 8 + 4, e.getValue()[1]);
      }
      var streams = new LinkedHashMap<String, byte[]>();
      streams.put("WordDocument", wd);
      streams.put("1Table", t.toByteArray());
      if (data != null) streams.put("Data", data);
      return CompoundFile.write(streams);
    }
  }

  static byte[] cat(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (byte[] p : parts) out.write(p, 0, p.length);
    return out.toByteArray();
  }

  static byte[] le16(int v) {
    return bytes(v & 0xff, (v >> 8) & 0xff);
  }

  static byte[] le32(int v) {
    return bytes(v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >>> 24) & 0xff);
  }

  static void put16(byte[] b, int at, int v) {
    b[at] = (byte) v;
    b[at + 1] = (byte) (v >> 8);
  }

  static void put32(byte[] b, int at, int v) {
    put16(b, at, v);
    put16(b, at + 2, v >>> 16);
  }

  static byte[] utf16(String s) {
    var out = new ByteArrayOutputStream();
    for (int i = 0; i < s.length(); i++) out.write(le16(s.charAt(i)), 0, 2);
    return out.toByteArray();
  }

  static byte[] plc(int... cps) {
    var out = new ByteArrayOutputStream();
    for (int c : cps) out.write(le32(c), 0, 4);
    return out.toByteArray();
  }

  static byte[] fonts(String... names) {
    var out = new ByteArrayOutputStream();
    out.write(le16(names.length), 0, 2);
    out.write(le16(0), 0, 2);
    for (String n : names) {
      byte[] ffn = cat(new byte[39], utf16(n), new byte[2]);
      out.write(ffn.length);
      out.write(ffn, 0, ffn.length);
    }
    return out.toByteArray();
  }

  static byte[] style(int stk, int base, String name, byte[] papx, byte[] chpx) {
    var std = new ByteArrayOutputStream();
    var stdf = new byte[10];
    put16(stdf, 2, stk | base << 4);
    put16(stdf, 4, stk == 1 ? 2 : 1);
    std.write(stdf, 0, 10);
    std.write(le16(name.length()), 0, 2);
    byte[] n = cat(utf16(name), new byte[2]);
    std.write(n, 0, n.length);
    List<byte[]> upx = stk == 1 ? List.of(cat(le16(0), papx), chpx) : List.of(chpx);
    for (byte[] u : upx) {
      std.write(le16(u.length), 0, 2);
      std.write(u, 0, u.length);
      if (u.length % 2 == 1) std.write(0);
    }
    return cat(le16(std.size()), std.toByteArray());
  }

  static byte[] lvl(int nfc, int[] xst, byte[] papx) {
    var l = new byte[28];
    put32(l, 0, 1);
    l[4] = (byte) nfc;
    if (nfc != 23) l[6] = 1;
    l[25] = (byte) papx.length;
    var x = new ByteArrayOutputStream();
    x.write(le16(xst.length), 0, 2);
    for (int c : xst) x.write(le16(c), 0, 2);
    return cat(l, papx, x.toByteArray());
  }

  static byte[] picture() {
    byte[] blip = cat(le16(0x6E0 << 4), le16(0xF01E), le32(17 + PNG.length), new byte[17], PNG);
    var fbseBody = new byte[36];
    fbseBody[0] = 6;
    fbseBody[1] = 6;
    byte[] fbse = cat(le16(2 | 6 << 4), le16(0xF007), le32(36 + blip.length), fbseBody, blip);
    byte[] sp = cat(le16(0xF), le16(0xF004), le32(0));
    var picf = new byte[0x44];
    put32(picf, 0, 0x44 + sp.length + fbse.length);
    put16(picf, 4, 0x44);
    put16(picf, 6, 0x64);
    put16(picf, 0x1C, 1440);
    put16(picf, 0x1E, 720);
    put16(picf, 0x20, 1000);
    put16(picf, 0x22, 1000);
    return cat(picf, sp, fbse);
  }

  private static DocWriter rich() {
    var d = new DocWriter();
    d.run("Hello ").run("bold", 0x35, 0x08, 1).run(" ").run("italic", 0x36, 0x08, 1).run(" ")
        .run("big", 0x43, 0x4A, 48, 0).run(" ").run("arial", 0x4F, 0x4A, 1, 0)
        .para(0, 0x03, 0x24, 1, 0x0F, 0x84, 0xD0, 0x02);
    d.run("Styled heading").para(1);
    d.run("First bullet").para(0, 0x0B, 0x46, 1, 0, 0x0A, 0x26, 0);
    d.run("First number").para(0, 0x0B, 0x46, 2, 0, 0x0A, 0x26, 0);
    int[] tdef = {0x08, 0xD6, 1 + 1 + 6 + 40, 0, 2, 0, 0, 0x40, 0x06, 0x80, 0x0C};
    int[] tdefFull = Arrays.copyOf(tdef, tdef.length + 40);
    for (String[] row : new String[][] {{"A1", "B1"}, {"A2", "B2"}}) {
      for (String c : row) d.run(c).mark('\u0007', 0, 0x16, 0x24, 1);
      int[] end = new int[6 + tdefFull.length];
      System.arraycopy(new int[] {0x16, 0x24, 1, 0x17, 0x24, 1}, 0, end, 0, 6);
      System.arraycopy(tdefFull, 0, end, 6, tdefFull.length);
      d.mark('\u0007', 0, end);
    }
    d.run("Note here");
    int ref = d.cp();
    d.run("\u0002").para(0);
    d.run("Before break").mark('\u000c', 0);
    int sect0 = d.cp();
    d.run("Picture: ").run("\u0001", 0x03, 0x6A, 0, 0, 0, 0, 0x55, 0x08, 1).para(0);
    d.run("Last").para(0);
    d.ccpText = d.cp();
    d.run("\u0002", 0x48, 0x2A, 1).run(" Footnote text").para(0);
    int noteLen = d.cp() - d.ccpText;
    d.para(0);
    d.ccpFtn = d.cp() - d.ccpText;
    int hddStart = d.cp();
    d.run("Header text").para(0);
    int hlen = d.cp() - hddStart;
    d.para(0);
    d.ccpHdd = d.cp() - hddStart;
    int[] hdd = new int[20];
    for (int i = 8; i < 19; i++) hdd[i] = hlen;
    hdd[19] = hlen + 1;
    d.tables.put(11, plc(hdd));
    d.tables.put(2, cat(plc(ref, d.ccpText), le16(1)));
    d.tables.put(3, plc(0, noteLen, noteLen + 1));
    d.tables.put(6, plc(0, sect0, d.ccpText));
    d.sepx.add(bytes(0x09, 0x30, 2));
    d.sepx.add(bytes(0x21, 0xB0, 0xA0, 0x05));
    d.tables.put(15, fonts("Times New Roman", "Arial"));
    var stshi = new byte[18];
    put16(stshi, 0, 2);
    put16(stshi, 2, 10);
    d.tables.put(1, cat(le16(18), stshi, style(1, 0xFFF, "Normal", new byte[0], new byte[0]),
        style(1, 0, "My Heading", bytes(0x03, 0x24, 1), bytes(0x35, 0x08, 1, 0x43, 0x4A, 32, 0))));
    byte[] ind = bytes(0x0F, 0x84, 0xD0, 0x02, 0x11, 0x84, 0x98, 0xFE);
    var lstf = new byte[56];
    put32(lstf, 0, 100);
    lstf[26] = 1;
    put32(lstf, 28, 200);
    lstf[54] = 1;
    d.tables.put(73, cat(le16(2), lstf, lvl(23, new int[] {0xF0B7}, ind), lvl(0, new int[] {0, '.'}, ind)));
    var lfo = new byte[32];
    put32(lfo, 0, 100);
    put32(lfo, 16, 200);
    d.tables.put(74, cat(le32(2), lfo, new byte[8]));
    d.data = picture();
    return d;
  }

  private static String part(Map<String, byte[]> parts, String name) throws Exception {
    assertTrue(name + " missing: " + parts.keySet(), parts.containsKey(name));
    return new String(parts.get(name), "UTF-8");
  }

  @Test
  public void richDocumentTranslatesToWordprocessingMl() throws Exception {
    var parts = DocBinary.convert(rich().build());
    String doc = part(parts, "word/document.xml");
    assertTrue(doc, doc.contains("<w:b/>"));
    assertTrue(doc.contains("<w:i/>"));
    assertTrue(doc.contains("<w:sz w:val=\"48\"/>"));
    assertTrue(doc.contains("w:ascii=\"Arial\""));
    assertTrue(doc.contains("<w:jc w:val=\"center\"/>"));
    assertTrue(doc.contains("<w:ind w:left=\"720\"/>"));
    assertTrue(doc.contains("<w:pStyle w:val=\"MyHeading\"/>"));
    assertTrue(doc.contains("<w:numId w:val=\"1\"/>") && doc.contains("<w:numId w:val=\"2\"/>"));
    assertEquals(1, doc.split("<w:tbl>", -1).length - 1);
    assertEquals(2, doc.split("<w:tr>", -1).length - 1);
    assertEquals(4, doc.split("<w:tc>", -1).length - 1);
    assertTrue(doc.contains("<w:footnoteReference w:id=\"1\"/>"));
    assertEquals(2, doc.split("<w:sectPr>", -1).length - 1);
    assertTrue(doc.contains("w:left=\"1440\""));
    assertTrue(doc.contains("<w:headerReference w:type=\"default\""));
    assertTrue(doc.contains("r:embed="));
    String styles = part(parts, "word/styles.xml");
    assertTrue(styles, styles.contains("w:styleId=\"MyHeading\"") && styles.contains("<w:basedOn w:val=\"Normal\"/>"));
    String numbering = part(parts, "word/numbering.xml");
    assertTrue(numbering, numbering.contains("w:val=\"bullet\"") && numbering.contains("w:val=\"%1.\""));
    assertTrue(numbering.contains("w:hanging=\"360\""));
    assertTrue(part(parts, "word/footnotes.xml").contains("Footnote text"));
    assertTrue(part(parts, "word/header1.xml").contains("Header text"));
    assertTrue(parts.containsKey("word/media/image1.png"));
  }

  @Test
  public void richDocumentOpensThroughTheDocxModel() throws Exception {
    Document d = DocBinary.read(rich().build()).document;
    String text = d.plainText();
    assertTrue(text, text.contains("Hello bold italic big arial"));
    assertTrue(text.contains("Styled heading"));
    assertTrue(text.contains("A1") && text.contains("B2"));
    Paragraph first = d.paragraph(0);
    assertTrue(first.text.styleAt(7).bold);
    assertFalse(first.text.styleAt(1).bold);
    assertTrue(first.text.styleAt(12).italic);
    assertEquals(24, first.text.styleAt(19).size, .01);
    assertEquals("Arial", first.text.styleAt(23).font);
    assertEquals("center", first.align);
    boolean table = false;
    for (int i = 0; i < d.blocks.size(); i++) table |= d.blocks.get(i) instanceof Table;
    assertTrue(table);
    assertEquals(1, Notes.all(d).size());
    assertFalse(d.furniture.stories.isEmpty());
    assertTrue(d.styles.containsKey("MyHeading"));
    var lists = new HashMap<String, String>();
    for (int i = 0; i < d.blocks.size(); i++)
      if (d.blocks.get(i) instanceof Paragraph) {
        var p = (Paragraph) d.blocks.get(i);
        lists.put(p.text.toString(), p.list);
      }
    assertNotEquals(lists.get("Last"), lists.get("First bullet"));
    assertNotEquals(lists.get("Last"), lists.get("First number"));
    assertNotEquals(lists.get("First bullet"), lists.get("First number"));
  }

  @Test
  public void compressedAndUnicodePiecesDecode() throws Exception {
    var d = new DocWriter();
    d.run("Café € ").run("中文 😀").para(0);
    d.compressed = 7;
    d.ccpText = d.cp();
    var parts = DocBinary.convert(d.build());
    String doc = part(parts, "word/document.xml");
    assertTrue(doc, doc.contains("Café € 中文 😀"));
    assertTrue(DocBinary.read(d.build()).document.plainText().contains("中文"));
  }

  @Test
  public void encryptedDocumentsAreRejected() throws Exception {
    var d = new DocWriter();
    d.run("secret").para(0);
    d.ccpText = d.cp();
    d.flags = 0x0300;
    try {
      DocBinary.read(d.build());
      fail();
    } catch (IOException expected) {
      assertEquals("encrypted .doc files are not supported", expected.getMessage());
    }
  }

  @Test
  public void garbageOnlyRaisesIOException() throws Exception {
    var random = new Random(7);
    List<byte[]> inputs = new ArrayList<>();
    inputs.add(new byte[0]);
    var noise = new byte[4096];
    random.nextBytes(noise);
    inputs.add(noise);
    var signed = noise.clone();
    System.arraycopy(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1), 0, signed, 0, 8);
    inputs.add(signed);
    inputs.add(CompoundFile.write(Map.of("WordDocument", noise)));
    byte[] good = rich().build();
    var streams = CompoundFile.read(good);
    for (int i = 0; i < 300; i++) {
      var copy = new LinkedHashMap<String, byte[]>();
      for (var e : streams.entrySet()) copy.put(e.getKey(), e.getValue().clone());
      byte[] target = copy.get(i % 3 == 0 ? "WordDocument" : i % 3 == 1 ? "1Table" : "Data");
      for (int k = 0; k < 1 + random.nextInt(12); k++)
        target[random.nextInt(target.length)] = (byte) random.nextInt(256);
      inputs.add(CompoundFile.write(copy));
    }
    for (byte[] in : inputs)
      try {
        DocBinary.read(in);
      } catch (IOException expected) {
        assertNotNull(expected.getMessage());
      }
  }

  static byte[] sttb(int cbExtra, byte[] extra, String... names) {
    var out = new ByteArrayOutputStream();
    out.writeBytes(le16(0xFFFF));
    out.writeBytes(le16(names.length));
    out.writeBytes(le16(cbExtra));
    for (String n : names) {
      out.writeBytes(le16(n.length()));
      out.writeBytes(utf16(n));
      if (cbExtra > 0) out.writeBytes(extra);
    }
    return out.toByteArray();
  }

  static byte[] rec(int verInst, int type, byte[]... body) {
    byte[] b = cat(body);
    return cat(le16(verInst), le16(type), le32(b.length), b);
  }

  /** Comment over "Alpha ins", a bookmark, and inserted/deleted runs by two authors. */
  private static DocWriter revised() {
    var d = new DocWriter();
    int dttm = 5 | 14 << 6 | 20 << 11 | 10 << 16 | 126 << 20;
    d.run("Alpha ").run("ins", 0x01, 0x08, 1, 0x04, 0x48, 1, 0, 0x05, 0x68, dttm & 255, dttm >> 8 & 255,
        dttm >> 16 & 255, dttm >>> 24).run("del", 0x00, 0x08, 1, 0x63, 0x48, 0, 0);
    int ref = d.cp();
    d.run("\u0005").para(0);
    d.run("Beta").para(0);
    d.ccpText = d.cp();
    d.run("\u0005Remark").para(0);
    int len = d.cp() - d.ccpText;
    d.para(0);
    d.ccpAtn = d.cp() - d.ccpText;
    var atrd = new byte[30];
    put16(atrd, 0, 2);
    put16(atrd, 2, 'A');
    put16(atrd, 4, 'N');
    put32(atrd, 26, 7);
    d.tables.put(4, cat(plc(ref, d.ccpText), atrd));
    d.tables.put(5, plc(0, len, len + 1));
    d.tables.put(36, cat(le16(3), utf16("Ann")));
    d.tables.put(37, sttb(10, cat(le16(0x100), le32(7), le32(0)), ""));
    d.tables.put(42, cat(plc(0, d.ccpText), le32(0)));
    d.tables.put(43, plc(ref, d.ccpText));
    d.tables.put(21, sttb(0, null, "Mark"));
    d.tables.put(22, cat(plc(0, d.ccpText), le32(0)));
    d.tables.put(23, plc(5, d.ccpText));
    d.tables.put(51, sttb(0, null, "Bob", "Carol"));
    return d;
  }

  @Test
  public void commentsBookmarksAndRevisionsTranslate() throws Exception {
    var parts = DocBinary.convert(revised().build());
    String doc = part(parts, "word/document.xml");
    assertTrue(doc, doc.contains("<w:bookmarkStart w:id=\"1\" w:name=\"Mark\"/>"));
    assertTrue(doc.indexOf("<w:bookmarkEnd w:id=\"1\"/>") < doc.indexOf(">ins<"));
    assertTrue(doc.contains("<w:commentRangeStart w:id=\"0\"/>") && doc.contains("<w:commentRangeEnd w:id=\"0\"/>"));
    assertTrue(doc.contains("<w:commentReference w:id=\"0\"/>"));
    assertTrue(doc.contains("w:author=\"Carol\" w:date=\"2026-10-20T14:05:00Z\">"));
    assertTrue(doc.contains("<w:ins w:id="));
    assertTrue(doc.contains("<w:del w:id=") && doc.contains("w:author=\"Bob\""));
    assertTrue(doc.contains("<w:delText xml:space=\"preserve\">del</w:delText>"));
    String comments = part(parts, "word/comments.xml");
    assertTrue(comments, comments.contains("w:author=\"Ann\" w:initials=\"AN\"") && comments.contains("Remark"));
    Document m = DocBinary.read(revised().build()).document;
    assertEquals(1, m.comments.size());
    assertEquals("Ann", m.comments.get(0).author);
    assertTrue(m.plainText(), m.plainText().contains("Alpha"));
  }

  static int[] tdef(int f0, int f1) {
    int[] t = new int[11 + 40];
    System.arraycopy(new int[] {0x08, 0xD6, 48, 0, 2, 0, 0, 0x40, 0x06, 0x80, 0x0C}, 0, t, 0, 11);
    t[11] = f0;
    t[31] = f1;
    return t;
  }

  static int[] join(int[]... parts) {
    int n = 0;
    for (int[] p : parts) n += p.length;
    int[] out = new int[n];
    n = 0;
    for (int[] p : parts) {
      System.arraycopy(p, 0, out, n, p.length);
      n += p.length;
    }
    return out;
  }

  private static DocWriter tables() {
    var d = new DocWriter();
    int[] cell = {0x16, 0x24, 1}, inner = {0x16, 0x24, 1, 0x49, 0x66, 2, 0, 0, 0}, row = {0x16, 0x24, 1, 0x17, 0x24, 1};
    d.run("A").mark('\u0007', 0, cell).run("B").mark('\u0007', 0, cell).mark('\u0007', 0, join(row, tdef(0x60, 0)));
    d.mark('\u0007', 0, cell);
    d.run("x").mark('\u0007', 0, inner).run("y").mark('\u0007', 0, inner);
    d.mark('\r', 0, join(inner, new int[] {0x4C, 0x24, 1}, tdef(0, 0)));
    d.mark('\u0007', 0, cell).mark('\u0007', 0, join(row, tdef(0x20, 0)));
    d.run("M").mark('\u0007', 0, cell).run("N").mark('\u0007', 0, cell).mark('\u0007', 0, join(row, tdef(1, 2)));
    d.run("After").para(0);
    d.ccpText = d.cp();
    return d;
  }

  @Test
  public void mergedAndNestedTablesTranslate() throws Exception {
    String doc = part(DocBinary.convert(tables().build()), "word/document.xml");
    assertEquals(doc, 2, doc.split("<w:tbl>", -1).length - 1);
    assertTrue(doc.indexOf("<w:tbl>", doc.indexOf("<w:tbl>") + 1) < doc.indexOf(">M<"));
    assertTrue(doc.contains("<w:vMerge w:val=\"restart\"/>") && doc.contains("<w:vMerge/>"));
    assertTrue(doc.contains("<w:gridSpan w:val=\"2\"/>"));
    assertEquals(3 + 1, doc.split("<w:tr>", -1).length - 1);
    Document m = DocBinary.read(tables().build()).document;
    assertTrue(m.plainText(), m.plainText().contains("After"));
    boolean table = false;
    for (int i = 0; i < m.blocks.size(); i++) table |= m.blocks.get(i) instanceof Table;
    assertTrue(table);
  }

  static byte[] fspa(int spid, int x, int y, int w, int h, int flags) {
    var b = new byte[26];
    put32(b, 0, spid);
    put32(b, 4, x);
    put32(b, 8, y);
    put32(b, 12, x + w);
    put32(b, 16, y + h);
    put16(b, 20, flags);
    return b;
  }

  static byte[] shape(int type, int spid, byte[] props, int count) {
    return rec(0xF, 0xF004, rec(2 | type << 4, 0xF00A, le32(spid), le32(0xA00)), rec(3 | count << 4, 0xF00B, props));
  }

  private static DocWriter floating() {
    var d = new DocWriter();
    d.run("Float ");
    int a = d.cp();
    d.run("\u0008 shape ");
    int b = d.cp();
    d.run("\u0008 box ");
    int c = d.cp();
    d.run("\u0008").para(0);
    d.ccpText = d.cp();
    d.run("Boxed").para(0);
    int len = d.cp() - d.ccpText;
    d.para(0);
    byte[] blip = rec(0x6E0 << 4, 0xF01E, new byte[17], PNG);
    var fbseBody = new byte[36];
    fbseBody[0] = 6;
    byte[] dgg = rec(0xF, 0xF000, rec(0xF, 0xF001, rec(2 | 6 << 4, 0xF007, fbseBody, blip)));
    byte[] dg = rec(0xF, 0xF002, rec(0xF, 0xF003,
        shape(75, 1025, cat(le16(0x4104), le32(1)), 1),
        shape(3, 1026, cat(le16(0x181), le32(0x0000FF)), 1),
        shape(202, 1027, new byte[0], 0)));
    d.tables.put(50, cat(dgg, new byte[] {0}, dg));
    d.tables.put(40, cat(plc(a, b, c, d.ccpText), fspa(1025, 1440, 720, 2880, 1440, 1 << 1 | 1 << 3 | 3 << 5 | 0x4000),
        fspa(1026, 0, 0, 1440, 1440, 2 << 1 | 2 << 3 | 1 << 5), fspa(1027, 0, 0, 2880, 720, 0)));
    var ftx = new byte[44];
    put32(ftx, 14, 1027);
    d.tables.put(56, cat(plc(0, len, len + 1), ftx));
    return d;
  }

  @Test
  public void floatingPicturesAndShapesTranslate() throws Exception {
    var parts = DocBinary.convert(floating().build());
    String doc = part(parts, "word/document.xml");
    assertEquals(doc, 3, doc.split("<wp:anchor ", -1).length - 1);
    assertTrue(doc.contains("behindDoc=\"1\"") && doc.contains("<wp:wrapNone/>"));
    assertTrue(doc.contains("<wp:positionH relativeFrom=\"page\"><wp:posOffset>914400</wp:posOffset>"));
    assertTrue(doc.contains("<wp:wrapTopAndBottom/>") && doc.contains("relativeFrom=\"column\""));
    assertTrue(doc.contains("prst=\"ellipse\"") && doc.contains("<a:srgbClr val=\"FF0000\"/>"));
    assertTrue(doc.contains("txBox=\"1\"") && doc.contains("<w:txbxContent>"));
    assertTrue(doc.indexOf("Boxed") > doc.indexOf("<w:txbxContent>"));
    assertTrue(parts.containsKey("word/media/image1.png"));
    Document m = DocBinary.read(floating().build()).document;
    assertTrue(m.plainText(), m.plainText().contains("Float"));
  }

  @Test
  public void hostileFeatureTablesOnlyRaiseIOException() throws Exception {
    var random = new Random(11);
    for (DocWriter w : new DocWriter[] {revised(), tables(), floating()}) {
      var streams = CompoundFile.read(w.build());
      for (int i = 0; i < 200; i++) {
        var copy = new LinkedHashMap<String, byte[]>();
        for (var e : streams.entrySet()) copy.put(e.getKey(), e.getValue().clone());
        byte[] target = copy.get(i % 4 == 0 ? "WordDocument" : "1Table");
        for (int k = 0; k < 1 + random.nextInt(16); k++)
          target[random.nextInt(target.length)] = (byte) (i % 3 == 0 ? 0xFF : random.nextInt(256));
        try {
          DocBinary.read(CompoundFile.write(copy));
        } catch (IOException expected) {
          assertNotNull(expected.getMessage());
        }
      }
    }
    // A shape table whose records claim more bytes than exist, and anchors to unknown shapes.
    var d = floating();
    d.tables.put(50, rec(0xF, 0xF000, rec(0xF, 0xF001, le32(-1))));
    d.tables.put(21, cat(le16(0xFFFF), le16(60000), le16(0)));
    String doc = part(DocBinary.convert(d.build()), "word/document.xml");
    assertFalse(doc.contains("<wp:anchor"));
  }
}
