package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.junit.Test;

/** Font fallback, right-to-left and Arabic shaping, kerning, links, outline, metadata and tags. */
public class PdfParityTest {
  private static final PdfExporter.FontProvider FONTS =
      BundledFonts.provider(path -> PdfParityTest.class.getResourceAsStream(path));

  private static String pdf(Document d) throws IOException {
    return new String(PdfExporter.write(d, FONTS), "ISO-8859-1");
  }

  private static Session session(String text) {
    var s = new Session(Document.empty());
    s.replace(text);
    return s;
  }

  @Test
  public void missingGlyphsFallBackToNotoFacesAndUncoveredTextStillExports() throws Exception {
    var out = pdf(session("Hello \u05e9\u05dc\u05d5\u05dd \u0645\u0631\u062d\u0628\u0627 \u4e2d").document());
    assertTrue(out.contains("+SourceSerif4-Regular"));
    assertTrue(out.contains("+NotoSansHebrew-Regular"));
    assertTrue(out.contains("+NotoNaskhArabic-Regular"));
    var fonts = new PdfFonts(FONTS);
    var shown = fonts.show(TextStyle.DEFAULT, "a\u05d0\u4e2d", false, "12");
    // Latin face, Hebrew fallback, then back to the chosen face's .notdef for the CJK character.
    assertEquals(3, count(shown, " Tf "));
    var bold = fonts.show(TextStyle.DEFAULT.with("bold", "true"), "\u05d0", false, "12");
    assertTrue(out.length() > 0 && bold.startsWith("/F"));
  }

  @Test
  public void rightToLeftRunsAreReversedWithMirroredBrackets() {
    var units = PdfShaping.shape("\u05d0(\u05d1\u05b8)", true, cp -> true);
    int[] glyphs = new int[units.size()];
    for (int i = 0; i < glyphs.length; i++) glyphs[i] = units.get(i)[0];
    // Visual order: mirrored ")" shows "(", the point stays after its base letter.
    assertArrayEquals(new int[] {'(', 0x05d1, 0x05b8, ')', 0x05d0}, glyphs);
    assertEquals(1, units.get(3)[1]);
    var layout = PdfExporter.layoutEngine(FONTS).layout(session("\u05e9\u05dc\u05d5\u05dd").document(), 0);
    assertTrue(layout.pages.get(0).lines.get(0).spans.get(0).rtl);
  }

  @Test
  public void arabicUsesContextualPresentationFormsAndLamAlef() {
    var units = PdfShaping.shape("\u0628\u0628\u0628 \u0644\u0627 \u062f\u0628", false, cp -> true);
    int[] glyphs = new int[units.size()];
    for (int i = 0; i < glyphs.length; i++) glyphs[i] = units.get(i)[0];
    assertArrayEquals(
        new int[] {0xfe91, 0xfe92, 0xfe90, ' ', 0xfefb, ' ', 0xfea9, 0xfe8f}, glyphs);
    // The ligature extracts as both logical letters.
    assertEquals(4, units.get(4)[1]);
    assertEquals(6, units.get(4)[2]);
    var unshaped = PdfShaping.shape("\u0628\u0628", false, cp -> false);
    assertEquals(0x0628, unshaped.get(0)[0]);
  }

  @Test
  public void exportedArabicHasLogicalToUnicodeForShapedGlyphs() throws Exception {
    var out = pdf(session("\u0644\u0627\u0645").document());
    // Lam-alef extracts as both logical letters, whether the face ligates it or (as Noto Naskh
    // does through contextual GSUB) shows two joined glyphs.
    assertTrue(out.contains("<06440627>") || out.contains("<0644>") && out.contains("<0627>"));
  }

  @Test
  public void kerningPairsAdjustTjPositions() throws Exception {
    var face = new PdfFonts(FONTS).face(TextStyle.DEFAULT);
    var font = face.font;
    assertTrue(font.kern(font.glyph('A'), font.glyph('V')) < 0);
    assertEquals(0, font.kern(font.glyph('H'), font.glyph('H')));
    var shown = new PdfFonts(FONTS).show(TextStyle.DEFAULT, "AVA", false, "12");
    assertTrue(shown, Pattern.compile("> \\d+ <").matcher(shown).find());
  }

  @Test
  public void hyperlinksBecomeUriAndBookmarkAnnotations() throws Exception {
    var s = session("Visit site now");
    s.selectRange(0, 6, 0, 10);
    s.format("link", "https://example.com/a(b)");
    var out = pdf(s.document());
    assertTrue(out.contains("/Subtype /Link"));
    assertTrue(out.contains("/URI (https://example.com/a\\(b\\))"));
    assertTrue(Pattern.compile("/Annots \\[\\d+ 0 R \\]").matcher(out).find());
    s.selectRange(0, 0, 0, 5);
    s.format("link", "javascript:alert(1)");
    assertFalse(pdf(s.document()).contains("javascript"));
  }

  @Test
  public void headingsBuildOutlineAndPropertiesBecomeInfo() throws Exception {
    var s = session("Intro\nBody text\nDetails");
    s.select(0, 0, 0);
    s.paragraph("style", "Heading1");
    s.select(2, 0, 0);
    s.paragraph("style", "Heading2");
    var d = s.document();
    d = d.properties(d.properties.with("title", "Quarterly").with("author", "Ann").with("keywords", "pdf"));
    var out = pdf(d);
    assertTrue(out.contains("/Type /Outlines"));
    assertTrue(out.contains("/PageMode /UseOutlines"));
    assertTrue(out.contains("/Title " + PdfStructure.text("Intro")));
    assertTrue(out.contains("/Title " + PdfStructure.text("Details")));
    assertTrue(out.contains("/Title " + PdfStructure.text("Quarterly")));
    assertTrue(out.contains("/Author " + PdfStructure.text("Ann")));
    assertTrue(out.contains("/Keywords " + PdfStructure.text("pdf")));
    assertTrue(Pattern.compile("/CreationDate \\(D:\\d{14}Z\\)").matcher(out).find());
    assertTrue(out.contains("/S /H1"));
    assertTrue(out.contains("/S /H2"));
  }

  @Test
  public void taggedStructureCoversEveryMarkedContentId() throws Exception {
    var s = session("One\nTwo\nThree");
    for (int i = 0; i < 12; i++) s.replace("\nLine " + i + " with some more text to fill pages");
    var bytes = PdfExporter.write(s.document());
    var out = new String(bytes, "ISO-8859-1");
    assertTrue(out.contains("/MarkInfo << /Marked true >>"));
    var objects = new HashMap<Integer, String>();
    var m = Pattern.compile("(\\d+) 0 obj\n").matcher(out);
    while (m.find()) {
      int end = out.indexOf("\nendobj", m.end());
      objects.put(Integer.parseInt(m.group(1)), out.substring(m.end(), end));
    }
    for (String body : objects.values()) {
      var ref = Pattern.compile("(\\d+) 0 R").matcher(body);
      while (ref.find()) assertTrue(objects.containsKey(Integer.parseInt(ref.group(1))));
    }
    var root = find(objects, "/Type /StructTreeRoot");
    var tree = objects.get(ref(root, "/ParentTree"));
    int pages = 0;
    for (Map.Entry<Integer, String> e : objects.entrySet()) {
      if (!e.getValue().startsWith("<< /Type /Page ")) continue;
      int key = ref(e.getValue(), "/StructParents");
      var content = objects.get(ref(e.getValue(), "/Contents"));
      var mcids = Pattern.compile("<< /MCID (\\d+) >> BDC").matcher(content);
      int n = 0;
      while (mcids.find()) assertEquals(n++, Integer.parseInt(mcids.group(1)));
      assertEquals(count(content, "BDC") + count(content, "BMC"), count(content, "EMC"));
      var entry = Pattern.compile("(?:^|\\s|\\[)" + key + " \\[([^\\]]*)\\]").matcher(tree);
      assertTrue(entry.find());
      assertEquals(n, count(entry.group(1), " 0 R"));
      for (String r : entry.group(1).trim().split(" 0 R\\s*"))
        assertTrue(objects.get(Integer.parseInt(r.trim())).startsWith("<< /Type /StructElem"));
      pages++;
    }
    assertTrue(pages > 0);
  }

  @Test
  public void tablesAndPicturesAreTaggedWithAltText() throws Exception {
    var s = session("Before");
    s.insertTable(2, 2);
    s.setCell(1, 0, 0, "cell");
    s.select(0, 0, 0);
    s.insertPicture(PictureTest.rgba(), 120, 60, "Sales chart");
    var out = pdf(s.document());
    assertTrue(out.contains("/S /Table"));
    assertTrue(out.contains("/S /TR"));
    assertTrue(out.contains("/S /TD"));
    assertTrue(out.contains("/S /Figure /P"));
    assertTrue(out.contains("/Alt " + PdfStructure.text("Sales chart")));
  }

  private static String find(Map<Integer, String> objects, String marker) {
    for (String body : objects.values()) if (body.contains(marker)) return body;
    fail(marker);
    return null;
  }

  private static int ref(String body, String key) {
    var m = Pattern.compile(Pattern.quote(key) + " (\\d+)").matcher(body);
    assertTrue(key, m.find());
    return Integer.parseInt(m.group(1));
  }

  private static int count(String text, String part) {
    int n = 0;
    for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
    return n;
  }
}
