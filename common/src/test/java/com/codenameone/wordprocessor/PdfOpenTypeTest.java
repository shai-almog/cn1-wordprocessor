package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import org.junit.After;
import org.junit.Test;

/** Font subsetting, installed-font fallback and OpenType shaping in the PDF writer. */
public class PdfOpenTypeTest {
  private static final PdfExporter.FontProvider FONTS =
      BundledFonts.provider(path -> PdfOpenTypeTest.class.getResourceAsStream(path));

  @After
  public void restoreSystemFonts() {
    SystemFonts.configure(null, null);
  }

  private static byte[] resource(String name) throws IOException {
    try (var in = PdfOpenTypeTest.class.getResourceAsStream("/" + name + ".ttf")) {
      return in.readAllBytes();
    }
  }

  private static int deflated(byte[] b) throws IOException {
    var out = new ByteArrayOutputStream();
    try (var z = new DeflaterOutputStream(out)) {
      z.write(b);
    }
    return out.size();
  }

  @Test
  public void subsetParsesBackWithOnlyUsedOutlines() throws Exception {
    var font = new PdfFonts.TrueType(resource("SourceSerif4-Regular"));
    var used = new ArrayList<Integer>();
    for (char c : "Hello".toCharArray()) used.add(font.glyph(c));
    byte[] subset = FontSubset.subset(font, used);
    var back = new PdfFonts.TrueType(subset);
    assertEquals(font.glyphs, back.glyphs);
    assertEquals(font.glyph('H'), back.glyph('H'));
    assertEquals(0, back.glyph('Z'));
    int[] h = FontSubset.glyphSpan(back, back.glyph('H'));
    int[] original = FontSubset.glyphSpan(font, font.glyph('H'));
    // Same outline bytes (the subset pads each glyph to four bytes).
    assertArrayEquals(
        Arrays.copyOfRange(font.data, original[0], original[1]),
        Arrays.copyOfRange(back.data, h[0], h[0] + original[1] - original[0]));
    int[] z = FontSubset.glyphSpan(back, font.glyph('Z'));
    assertEquals(0, z[1] - z[0]);
    assertEquals(font.advance(font.glyph('e')), back.advance(back.glyph('e')));
    assertTrue(subset.length * 4 < font.data.length);
  }

  @Test
  public void compositeGlyphComponentsAreKept() throws Exception {
    var font = new PdfFonts.TrueType(resource("NotoSansHebrew-Regular"));
    for (var name : new String[] {"SourceSerif4-Regular", "NotoSansHebrew-Regular",
        "JetBrainsMono-Regular", "NotoNaskhArabic-Regular"}) {
      font = new PdfFonts.TrueType(resource(name));
      for (int g = 1; g < font.glyphs; g++) {
        int[] span = FontSubset.glyphSpan(font, g);
        if (span[1] - span[0] < 14 || font.s16(span[0]) >= 0) continue;
        int component = font.u16(span[0] + 12);
        var kept = FontSubset.closure(font, Collections.singletonList(g));
        assertTrue(kept.contains(component));
        assertTrue(kept.contains(0));
        var back = new PdfFonts.TrueType(FontSubset.subset(font, Collections.singletonList(g)));
        int[] c = FontSubset.glyphSpan(back, component);
        assertTrue(c[1] > c[0]);
        return;
      }
    }
    fail("no composite glyph in the bundled fonts");
  }

  @Test
  public void exportedFontsAreSubsetAndStillExtractable() throws Exception {
    var s = new Session(Document.empty());
    s.replace("The quick brown fox jumps over the lazy dog. \u05e9\u05dc\u05d5\u05dd "
        + "\u0645\u0631\u062d\u0628\u0627 office fifty");
    byte[] pdf = PdfExporter.write(s.document(), FONTS);
    var text = new String(pdf, "ISO-8859-1");
    assertTrue(text.contains("/ToUnicode"));
    assertTrue(text.matches("(?s).*/BaseFont /[A-Z]{6}\\+SourceSerif4-Regular.*"));
    // What embedding the whole faces would have added instead of the subsets.
    int full = 0;
    for (var name : new String[] {"SourceSerif4-Regular", "NotoSansHebrew-Regular",
        "NotoNaskhArabic-Regular"}) full += deflated(resource(name));
    System.out.println("PDF bytes with subsets: " + pdf.length + ", fonts deflated whole: " + full);
    assertTrue(pdf.length * 3 < full);
  }

  @Test
  public void latinLigaturesAndKerningFromGsubAndGpos() throws Exception {
    var face = new PdfFonts(FONTS).face(TextStyle.DEFAULT);
    var font = face.font;
    var glyphs = PdfShaping.shapeRun(font, face.layout(), "office", 0, 6, false, true);
    // "ffi" becomes one glyph that extracts as all three letters.
    assertEquals(4, glyphs.size());
    assertEquals(1, glyphs.get(1).start);
    assertEquals(4, glyphs.get(1).end);
    assertNotEquals(font.glyph('f'), glyphs.get(1).id);
    var kerned = PdfShaping.shapeRun(font, face.layout(), "AV", 0, 2, false, true);
    assertTrue(kerned.get(0).adv < font.advance(font.glyph('A')));
  }

  @Test
  public void arabicJoiningFormsComeFromGsub() throws Exception {
    var font = new PdfFonts.TrueType(resource("NotoNaskhArabic-Regular"));
    var ot = new OpenType(font);
    var single = PdfShaping.shapeRun(font, ot, "\u0647", 0, 1, true, true);
    var triple = PdfShaping.shapeRun(font, ot, "\u0647\u0647\u0647", 0, 3, true, true);
    var ids = new ArrayList<Integer>();
    for (var g : triple) ids.add(g.id);
    // Visual order: final, medial, initial heh; each differs from the isolated form.
    assertEquals(3, ids.size());
    assertEquals(3, new HashSet<>(ids).size());
    assertFalse(ids.contains(single.get(0).id));
    assertEquals(2, triple.get(0).start);
    assertEquals(0, triple.get(2).start);
  }

  @Test
  public void hebrewMarksArePositionedOnTheirBase() throws Exception {
    var font = new PdfFonts.TrueType(resource("NotoSansHebrew-Regular"));
    var glyphs = PdfShaping.shapeRun(font, new OpenType(font), "\u05e9\u05b8", 0, 2, true, true);
    assertEquals(2, glyphs.size());
    assertEquals(0x05e9, glyphs.get(0).cp);
    var mark = glyphs.get(1);
    assertEquals(0, mark.adv);
    // Anchored below and within the shin's advance.
    assertTrue(mark.dy != 0 || mark.dx != 0);
    assertTrue(-mark.dx > 0 && -mark.dx <= glyphs.get(0).adv + 200);
    var shown = new PdfFonts(FONTS).show(TextStyle.DEFAULT, "\u05e9\u05b8", true, "12");
    assertTrue(shown, shown.contains("> "));
  }

  @Test
  public void devanagariReordersMatraAndReph() throws Exception {
    var file = new File("/System/Library/Fonts/Supplemental/Devanagari Sangam MN.ttc");
    org.junit.Assume.assumeTrue(file.exists());
    byte[] data;
    try (var in = new FileInputStream(file)) {
      data = in.readAllBytes();
    }
    var font = new PdfFonts.TrueType(FontSubset.face(data, 0));
    var ot = new OpenType(font);
    var ki = PdfShaping.shapeRun(font, ot, "\u0915\u093f", 0, 2, false, true);
    // The i-matra is drawn before its consonant; the syllable extracts in logical order.
    assertEquals(font.glyph(0x0915), ki.get(1).id);
    assertEquals(0, ki.get(0).start);
    assertEquals(2, ki.get(0).end);
    var rk = PdfShaping.shapeRun(font, ot, "\u0930\u094d\u0915", 0, 3, false, true);
    // Ra + virama forms a reph that moves after the base consonant.
    assertEquals(2, rk.size());
    assertEquals(font.glyph(0x0915), rk.get(0).id);
    var ksha = PdfShaping.shapeRun(font, ot, "\u0915\u094d\u0937", 0, 3, false, true);
    assertEquals(1, ksha.size());
  }

  /** In-memory font folder. */
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

  @Test
  public void installedFontsCoverWhatBundledFacesLack() throws Exception {
    var files = new TreeMap<String, byte[]>();
    files.put("/fonts/a-mono.ttf", resource("JetBrainsMono-Regular"));
    files.put("/fonts/hebrew.ttf", resource("NotoSansHebrew-Regular"));
    files.put("/fonts/naskh.ttf", resource("NotoNaskhArabic-Regular"));
    files.put("/fonts/readme.txt", new byte[] {1, 2, 3});
    files.put("/fonts/broken.ttf", new byte[] {0, 1, 0, 0, 0});
    SystemFonts.configure(folder(files), Collections.singletonList("/fonts"));
    assertEquals(3, SystemFonts.entries().size());
    assertEquals("Noto Naskh Arabic", SystemFonts.find(0x0628).family);
    assertEquals("Noto Sans Hebrew", SystemFonts.find(0x05d0).family);
    assertNull(SystemFonts.find(0x4e2d));
    // A provider with no fallback faces of its own.
    var bare =
        new PdfExporter.FontProvider() {
          public String name(TextStyle style) {
            return "SourceSerif4-Regular";
          }

          public byte[] bytes(String name) throws IOException {
            return resource(name);
          }
        };
    var shown = new PdfFonts(bare).show(TextStyle.DEFAULT, "a\u05d0\u0628", false, "12");
    assertEquals(3, shown.split(" Tf ").length - 1);
    var s = new Session(Document.empty());
    s.replace("x \u05e9\u05dc\u05d5\u05dd");
    var pdf = new String(PdfExporter.write(s.document(), bare), "ISO-8859-1");
    assertTrue(pdf.contains("+SysNotoSansHebrew-"));
  }

  @Test
  public void fallbackRankingFollowsScriptPreferences() {
    assertTrue(SystemFonts.rank(0x0915, "Kohinoor Devanagari")
        < SystemFonts.rank(0x0915, "Noto Sans Devanagari"));
    assertTrue(SystemFonts.rank(0x0b95, "Noto Sans Tamil") < SystemFonts.rank(0x0b95, "Arial"));
    assertTrue(SystemFonts.rank(0x0628, "Noto Naskh Arabic") < SystemFonts.rank(0x0628, "Arial"));
    assertTrue(SystemFonts.rank(0x4e2d, "PingFang SC") < SystemFonts.rank(0x4e2d, "Zapfino"));
    assertEquals(SystemFonts.rank('a', "Foo"), SystemFonts.rank('a', "Bar"));
  }
}
