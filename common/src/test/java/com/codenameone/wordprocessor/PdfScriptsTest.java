package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

/** CFF embedding, GPOS cursive attachment and the Indic, Thai/Lao, Khmer and Myanmar shapers. */
public class PdfScriptsTest {
  private static final String SUPPLEMENTAL = "/System/Library/Fonts/Supplemental/";

  @After
  public void restoreSystemFonts() {
    SystemFonts.configure(null, null);
  }

  private static byte[] resource(String name) throws IOException {
    try (var in = PdfScriptsTest.class.getResourceAsStream("/" + name + ".ttf")) {
      return in.readAllBytes();
    }
  }

  /** First face of an installed font, or a skipped test when it is not installed. */
  private static byte[] installed(String file) throws IOException {
    var f = new File(SUPPLEMENTAL + file);
    Assume.assumeTrue(f.exists());
    try (var in = new FileInputStream(f)) {
      return FontSubset.face(in.readAllBytes(), 0);
    }
  }

  private static List<OpenType.Glyph> shape(PdfFonts.TrueType font, String text)
      throws IOException {
    return PdfShaping.shapeRun(font, new OpenType(font), text, 0, text.length(), false, false);
  }

  private static String cps(List<OpenType.Glyph> glyphs) {
    var sb = new StringBuilder();
    for (var g : glyphs) sb.append(Integer.toHexString(g.cp)).append(' ');
    return sb.toString().trim();
  }

  // ------------------------------------------------------------------------------ CFF

  private static void u16(ByteArrayOutputStream out, int v) {
    out.write(v >> 8);
    out.write(v);
  }

  @Test
  public void cffSubsetKeepsUsedCharstringsAndLocalSubrs() throws Exception {
    // A name-keyed CFF: three charstrings and a private DICT with one local subroutine.
    var charstrings = Cff.writeIndex(Arrays.asList(new byte[] {14}, new byte[] {(byte) 139, 14},
        new byte[] {(byte) 140, (byte) 141, 14}));
    byte[] subrs = Cff.writeIndex(Collections.singletonList(new byte[] {11}));
    byte[] names = Cff.writeIndex(Collections.singletonList("T".getBytes("ISO-8859-1")));
    byte[] empty = Cff.writeIndex(Collections.<byte[]>emptyList());
    // Private DICT: BlueValues [] then Subrs right after the 7-byte DICT.
    byte[] priv = {(byte) 139, (byte) 139, 6, 28, 0, 7, 19};
    int topSize = 3 + 4 * 2 + 17;
    int charstringsAt = 4 + names.length + topSize + empty.length * 2,
        privateAt = charstringsAt + charstrings.length;
    var top = new ByteArrayOutputStream();
    top.write(29);
    top.write(new byte[] {0, 0, (byte) (charstringsAt >> 8), (byte) charstringsAt});
    top.write(17);
    top.write(29);
    top.write(new byte[] {0, 0, 0, (byte) priv.length});
    top.write(29);
    top.write(new byte[] {0, 0, (byte) (privateAt >> 8), (byte) privateAt});
    top.write(18);
    var out = new ByteArrayOutputStream();
    out.write(new byte[] {1, 0, 4, 4});
    out.write(names);
    byte[] topIndex = Cff.writeIndex(Collections.singletonList(top.toByteArray()));
    assertEquals(topSize, topIndex.length);
    out.write(topIndex);
    out.write(empty);
    out.write(empty);
    out.write(charstrings);
    out.write(priv);
    out.write(subrs);
    byte[] subset = Cff.subset(out.toByteArray(), Arrays.asList(2));
    // Parse the subset back: CID-keyed, .notdef plus the one used glyph, Subrs kept.
    int[] n = Cff.index(subset, 4), t = Cff.index(subset, n[n.length - 1]);
    var dict = Cff.dict(subset, t[0], t[1]);
    assertTrue(dict.containsKey(1230));
    assertEquals(2, (int) Cff.num(dict, 1234, 0));
    int[] cs = Cff.index(subset, (int) Cff.num(dict, 17, 0));
    assertEquals(3, cs.length);
    assertArrayEquals(new byte[] {(byte) 140, (byte) 141, 14},
        Arrays.copyOfRange(subset, cs[1], cs[2]));
    int[] fds = Cff.index(subset, (int) Cff.num(dict, 1236, 0));
    var fd = Cff.dict(subset, fds[0], fds[1]);
    int pAt = (int) Cff.num(fd, 18, 1), pSize = (int) Cff.num(fd, 18, 0);
    var pd = Cff.dict(subset, pAt, pAt + pSize);
    assertTrue(pd.containsKey(6));
    int[] local = Cff.index(subset, pAt + (int) Cff.num(pd, 19, 0));
    assertEquals(2, local.length);
    assertEquals(11, subset[local[0]]);
  }

  @Test
  public void cffOutlineFontsEmbedAsFontFile3() throws Exception {
    byte[] tamil = installed("Tamil Sangam MN.ttc");
    var font = new PdfFonts.TrueType(tamil);
    assertTrue(font.cff);
    var files = new TreeMap<String, byte[]>();
    files.put("/fonts/tamil.otf", tamil);
    SystemFonts.configure(folder(files), Collections.singletonList("/fonts"));
    var s = new Session(Document.empty());
    s.replace("x \u0b95\u0bca \u0ba4\u0bae\u0bbf\u0bb4\u0bcd");
    var pdf = new String(PdfExporter.write(s.document(),
        BundledFonts.provider(path -> PdfScriptsTest.class.getResourceAsStream(path))),
        "ISO-8859-1");
    assertTrue(pdf.contains("/Subtype /CIDFontType0C"));
    assertTrue(pdf.contains("/Subtype /CIDFontType0 "));
    assertTrue(pdf.contains("/FontFile3 "));
    // The subset is far smaller than the face.
    var cff = font.tables.get("CFF ");
    var glyphs = new ArrayList<Integer>();
    for (var g : shape(font, "\u0b95\u0bca")) glyphs.add(g.id);
    byte[] subset = Cff.subset(Arrays.copyOfRange(font.data, cff[0], cff[0] + cff[1]), glyphs);
    assertTrue(subset.length < cff[1] / 2);
  }

  // --------------------------------------------------------------------------- cursive

  @Test
  public void cursiveAttachmentJoinsExitToEntry() throws Exception {
    var base = new PdfFonts.TrueType(resource("SourceSerif4-Regular"));
    int a = base.glyph('a'), b = base.glyph('b');
    int lo = Math.min(a, b), hi = Math.max(a, b);
    var g = new ByteArrayOutputStream();
    u16(g, 1);
    u16(g, 0);
    u16(g, 10); // ScriptList
    u16(g, 30); // FeatureList
    u16(g, 44); // LookupList
    u16(g, 1);
    g.write("DFLT".getBytes("ISO-8859-1"));
    u16(g, 8);
    u16(g, 4);
    u16(g, 0);
    u16(g, 0);
    u16(g, 0xffff);
    u16(g, 1);
    u16(g, 0);
    u16(g, 1);
    g.write("curs".getBytes("ISO-8859-1"));
    u16(g, 8);
    u16(g, 0);
    u16(g, 1);
    u16(g, 0);
    u16(g, 1);
    u16(g, 4);
    u16(g, 3);
    u16(g, 0);
    u16(g, 1);
    u16(g, 8);
    // Cursive subtable: coverage at 14, anchors from 22 (entry, exit per covered glyph).
    u16(g, 1);
    u16(g, 14);
    u16(g, 2);
    for (int k = 0; k < 4; k++) u16(g, 22 + 6 * k);
    u16(g, 1);
    u16(g, 2);
    u16(g, lo);
    u16(g, hi);
    int[][] anchors = lo == a ? new int[][] {{0, 0}, {500, 100}, {50, 300}, {0, 0}}
        : new int[][] {{50, 300}, {0, 0}, {0, 0}, {500, 100}};
    for (int[] an : anchors) {
      u16(g, 1);
      u16(g, an[0]);
      u16(g, an[1]);
    }
    var tables = new TreeMap<String, byte[]>();
    for (var e : base.tables.entrySet())
      if (!e.getKey().equals("GPOS") && !e.getKey().equals("GDEF"))
        tables.put(e.getKey(), Arrays.copyOfRange(base.data, e.getValue()[0],
            e.getValue()[0] + e.getValue()[1]));
    tables.put("GPOS", g.toByteArray());
    var font = new PdfFonts.TrueType(FontSubset.write(tables));
    var glyphs = shape(font, "ab");
    assertEquals(500, glyphs.get(0).adv);
    assertEquals(-50, glyphs.get(1).dx);
    assertEquals(font.advance(b) - 50, glyphs.get(1).adv);
    assertEquals(-200, glyphs.get(1).dy);
  }

  // ------------------------------------------------------------------------ syllables

  @Test
  public void khmerAndMyanmarReorderPreBaseParts() throws Exception {
    var font = new PdfFonts.TrueType(resource("SourceSerif4-Regular"));
    // Khmer: pre-base vowel e, then coeng Ro, then the base.
    assertEquals("17c1 17d2 179a 1780", cps(shape(font, "\u1780\u17d2\u179a\u17c1")));
    // Myanmar: vowel e and medial Ra before the base; a kinzi goes after it.
    assertEquals("1031 103c 1000", cps(shape(font, "\u1000\u103c\u1031")));
    assertEquals("1000 1004 103a 1039", cps(shape(font, "\u1004\u103a\u1039\u1000")));
    // The syllable extracts as its logical text from the first glyph.
    var g = shape(font, "\u1000\u103c\u1031");
    assertEquals(0, g.get(0).start);
    assertEquals(3, g.get(0).end);
  }

  @Test
  public void thaiAndLaoSaraAmDecompose() throws Exception {
    var lao = new PdfFonts.TrueType(installed("Lao Sangam MN.ttf"));
    assertEquals("e81 ecd eb2", cps(shape(lao, "\u0e81\u0eb3")));
    var thai = new File(SUPPLEMENTAL + "Silom.ttf");
    if (thai.exists()) {
      var font = new PdfFonts.TrueType(installed("Silom.ttf"));
      assertEquals("e01 e4d e48 e32", cps(shape(font, "\u0e01\u0e48\u0e33")));
    }
  }

  @Test
  public void indicScriptsReorderPreBaseAndSplitMatras() throws Exception {
    var bengali = new PdfFonts.TrueType(installed("Bangla Sangam MN.ttc"));
    // Bengali o: split into e (drawn before ka) and aa (after).
    var ko = shape(bengali, "\u0995\u09cb");
    assertEquals(0x09c7, ko.get(0).cp);
    assertEquals(0x0995, ko.get(1).cp);
    assertEquals(0, ko.get(0).start);
    assertEquals(2, ko.get(0).end);
    var tamil = new PdfFonts.TrueType(installed("Tamil Sangam MN.ttc"));
    assertEquals(0x0bc6, shape(tamil, "\u0b95\u0bca").get(0).cp);
    String[][] cases = {
      {"Gurmukhi Sangam MN.ttc", "\u0a15\u0a3f", "a3f"},
      {"Gujarati Sangam MN.ttc", "\u0a95\u0abf", "abf"},
      {"Oriya Sangam MN.ttc", "\u0b15\u0b47", "b47"},
    };
    for (String[] c : cases) {
      var f = new File(SUPPLEMENTAL + c[0]);
      if (!f.exists()) continue;
      var font = new PdfFonts.TrueType(installed(c[0]));
      assertEquals(c[0], c[2], Integer.toHexString(shape(font, c[1]).get(0).cp));
    }
    // Below-base consonant forms in Kannada and Telugu ligate the virama away.
    for (String[] c : new String[][] {{"Kannada Sangam MN.ttc", "\u0c95\u0ccd\u0c95"},
        {"Telugu Sangam MN.ttc", "\u0c15\u0c4d\u0c15"}}) {
      if (!new File(SUPPLEMENTAL + c[0]).exists()) continue;
      var font = new PdfFonts.TrueType(installed(c[0]));
      assertTrue(c[0], shape(font, c[1]).size() < 3);
    }
  }

  @Test
  public void khmerAndMyanmarWithInstalledFonts() throws Exception {
    var khmer = new PdfFonts.TrueType(installed("Khmer Sangam MN.ttf"));
    var k = shape(khmer, "\u1780\u17d2\u179a\u17c1");
    assertEquals(0x17c1, k.get(0).cp);
    var myanmar = new PdfFonts.TrueType(installed("Myanmar Sangam MN.ttc"));
    var m = shape(myanmar, "\u1000\u103c\u1031");
    assertEquals(0x1031, m.get(0).cp);
  }

  private static SystemFonts.Files folder(Map<String, byte[]> files) {
    return new SystemFonts.Files() {
      public String[] list(String dir) {
        var out = new ArrayList<String>();
        for (var path : files.keySet())
          if (path.startsWith(dir + "/")) out.add(path.substring(dir.length() + 1));
        return out.toArray(new String[0]);
      }

      public boolean isDirectory(String path) {
        return path.equals("/fonts");
      }

      public InputStream open(String path) throws IOException {
        var b = files.get(path);
        if (b == null) throw new FileNotFoundException(path);
        return new ByteArrayInputStream(b);
      }
    };
  }
}
