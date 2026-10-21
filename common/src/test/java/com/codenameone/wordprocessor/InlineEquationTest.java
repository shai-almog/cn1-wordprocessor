package com.codenameone.wordprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Assume;
import org.junit.Test;

/** Inline equations (m:oMath in a paragraph), the built-in gallery and LaTeX math import. */
public class InlineEquationTest {
  private static final String M = "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"";

  private static Docx.Opened open(String body) throws Exception {
    var out = new ByteArrayOutputStream();
    var zip = new ZipOutputStream(out);
    zip.putNextEntry(new ZipEntry("word/document.xml"));
    zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + body + "</w:body></w:document>").getBytes("UTF-8"));
    zip.closeEntry();
    zip.close();
    return Docx.read(new ByteArrayInputStream(out.toByteArray()));
  }

  private static String documentXml(Document d) throws Exception {
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    for (ZipEntry e; (e = in.getNextEntry()) != null; )
      if (e.getName().equals("word/document.xml")) return new String(in.readAllBytes(), "UTF-8");
    throw new AssertionError();
  }

  private static int inlineCount(Document d) {
    int n = 0;
    for (Block b : d.blocks)
      if (b instanceof Paragraph p) for (InlineField f : p.text.fields()) if (Equation.inline(f)) n++;
    return n;
  }

  private static int displayCount(Document d) {
    int n = 0;
    for (Block b : d.blocks)
      if (b instanceof Picture p && p.drawing != null && "equation".equals(p.drawing.kind)) n++;
    return n;
  }

  private static final String INLINE_BODY =
      "<w:p><w:r><w:t xml:space=\"preserve\">Let </w:t></w:r><m:oMath " + M
          + "><m:f><m:num><m:r><m:t>a</m:t></m:r></m:num><m:den><m:r><m:t>b</m:t></m:r></m:den></m:f></m:oMath>"
          + "<w:r><w:t xml:space=\"preserve\"> be small.</w:t></w:r></w:p>"
          + "<w:p><m:oMathPara " + M + "><m:oMath><m:r><m:t>y=2</m:t></m:r></m:oMath></m:oMathPara></w:p>";

  @Test
  public void docxInlineMathStaysInTheParagraphAndRoundTrips() throws Exception {
    Document d = open(INLINE_BODY).document;
    var p = (Paragraph) d.blocks.get(0);
    assertEquals("Let " + InlineField.OBJECT + " be small.", p.text.toString());
    InlineField f = p.text.fieldAt(4);
    assertTrue(Equation.inline(f));
    assertEquals("a/b", Equation.inlineText(f).replace(" ", "").replace("(", "").replace(")", ""));
    assertEquals(1, inlineCount(d));
    assertEquals(1, displayCount(d));
    String xml = documentXml(d);
    int para = xml.indexOf("Let ");
    assertTrue(xml, xml.indexOf("<m:oMath ", para) > para && xml.indexOf("<m:f>", para) > para);
    assertTrue(xml, xml.indexOf("be small", para) > xml.indexOf("<m:f>", para));
    assertFalse(xml, xml.contains("OMATH"));
    Document back = open(xml.substring(xml.indexOf("<w:body>") + 8, xml.indexOf("</w:body>"))).document;
    assertEquals(1, inlineCount(back));
    assertEquals(1, displayCount(back));
    assertEquals(p.text.toString(), ((Paragraph) back.blocks.get(0)).text.toString());
  }

  @Test
  public void inlineMathIsOneUnbreakableBaselineAlignedObjectOnTheLine() throws Exception {
    Document d = open(INLINE_BODY).document;
    var engine = new LayoutEngine(new PdfMetrics());
    var result = engine.layout(d, 0);
    LayoutEngine.Line first = null;
    for (LayoutEngine.Line l : result.pages.get(0).allLines()) if (l.block == 0) { first = l; break; }
    assertNotNull(first);
    LayoutEngine.Span math = null;
    for (LayoutEngine.Span s : first.spans) if (InlineField.OBJECT.equals(s.text)) math = s;
    assertNotNull(math);
    assertEquals("", math.displayText);
    var box = Equation.layout(Equation.fromLinear(Equation.inlineText(((Paragraph) d.blocks.get(0)).text.fieldAt(4))), 12, new PdfMetrics());
    assertEquals(box.width, math.width, .5f);
    // A built-up fraction makes the line taller than plain text.
    var plain = engine.layout(new Document(new Sequence<Block>().append(new Paragraph("p1", RichText.of("Let x be small.", TextStyle.DEFAULT))), PageSpec.A4, "t", 2), 0);
    float plainHeight = plain.pages.get(0).allLines().get(0).height;
    assertTrue(first.height + " vs " + plainHeight, first.height > plainHeight);
    String pdf = new String(PdfExporter.write(PageSpec.A4, result), "ISO-8859-1");
    assertTrue(pdf.length() > 0);
  }

  @Test
  public void insertInTextIsInlineAndUndoable() {
    var s = new Session(Document.empty());
    s.typeText("ab", 0);
    var a = new LinkedHashMap<String, Object>();
    a.put("text", "x^2");
    new Commands().execute(s, "insert.equation", a);
    var p = (Paragraph) s.document().blocks.get(0);
    assertEquals("ab" + InlineField.OBJECT, p.text.toString());
    assertTrue(Equation.inline(p.text.fieldAt(2)));
    assertEquals(0, displayCount(s.document()));
    // Copy and paste carry the equation as one object.
    s.selectRange(0, 2, 0, 3);
    Document copied = s.copySelection();
    s.select(0, 0, 0);
    s.paste(copied);
    assertEquals(2, inlineCount(s.document()));
    s.undo();
    s.undo();
    assertEquals("ab", ((Paragraph) s.document().blocks.get(0)).text.toString());
    assertTrue(Latex.write(s.document()).length() > 0);
  }

  @Test
  public void builtInGalleryInsertsDisplayEquationsOnAnEmptyLine() {
    assertEquals(14, Equation.BUILT_INS.length);
    for (String[] b : Equation.BUILT_INS) {
      assertNotNull(b[0], Equation.fromLinear(b[2]));
      assertFalse(b[0], Equation.latex(Equation.fromLinear(b[2])).isEmpty());
    }
    var s = new Session(Document.empty());
    var a = new LinkedHashMap<String, Object>();
    a.put("builtin", "quadratic");
    new Commands().execute(s, "insert.equation", a);
    assertEquals(1, displayCount(s.document()));
    assertEquals(0, inlineCount(s.document()));
  }

  @Test
  public void latexMathImportsAsEquations() {
    Document d = Latex.read("Let $x^2$ and \\(y_1\\) hold.\n\n\\[a+b\\]\n\n$$c$$\n\n"
        + "\\begin{equation}e=mc^2\\end{equation}\n");
    assertEquals(2, inlineCount(d));
    assertEquals(3, displayCount(d));
    String tex = Latex.write(d);
    assertTrue(tex, tex.contains("$") && tex.contains("\\["));
  }

  @Test
  public void equationTextUsesTheEmbeddedFontsWithAMathFallback() throws Exception {
    // A math bold A, Greek and a relation: the base Helvetica/WinAnsi path printed '?' for them;
    // with embedded fonts they are shaped through the body font chain and fall back to an
    // installed math face (STIX Two Math on macOS).
    Assume.assumeTrue(new java.io.File("/System/Library/Fonts/Supplemental/STIXTwoMath.otf").exists());
    // Only the math faces are listed (unit tests have no platform file system).
    String dir = "/System/Library/Fonts/Supplemental";
    SystemFonts.configure(new SystemFonts.Files() {
      public String[] list(String path) {
        return new String[] {"STIXGeneral.otf", "STIXTwoMath.otf"};
      }

      public boolean isDirectory(String path) {
        return path.equals(dir);
      }

      public java.io.InputStream open(String path) throws java.io.IOException {
        return new java.io.FileInputStream(path);
      }
    }, java.util.Collections.singletonList(dir));
    try {
      equationPdf();
      equationBoxesFitEmbeddedWidths();
    } finally {
      SystemFonts.configure(null, null);
    }
  }

  private void equationPdf() throws Exception {
    Document d = open("<w:p><m:oMathPara " + M + "><m:oMath><m:r><m:t>\ud835\udc00+\u03b1\u2264\u03b2</m:t>"
        + "</m:r></m:oMath></m:oMathPara></w:p>").document;
    var fonts = BundledFonts.provider(path -> InlineEquationTest.class.getResourceAsStream(path));
    String pdf = new String(PdfExporter.write(d, fonts), "ISO-8859-1");
    assertTrue(pdf.contains("STIXTwoMath"));
    assertFalse(pdf.contains("(?+"));
    assertEquals(SystemFonts.MATH[0], SystemFonts.preferred(0x1d400)[0]);
    assertTrue(SystemFonts.math(0x2264));
  }

  /** Laid out with the embedded faces' advances, no equation run overlaps the next one. */
  private void equationBoxesFitEmbeddedWidths() throws Exception {
    var fonts = new PdfFonts(
        BundledFonts.provider(path -> InlineEquationTest.class.getResourceAsStream(path)));
    var drawing = Drawing.of("equation").with("text", "\ud835\udc00+\u03b1\u2264\u03b2\u2211x")
        .with("fontSize", "24").with("justify", "left");
    var ops = DrawingOps.build(drawing, 400, 80, PdfDrawings.metrics(fonts, TextStyle.DEFAULT));
    var texts = new java.util.ArrayList<>(ops.texts);
    texts.sort((a, b) -> Float.compare(a.x(), b.x()));
    assertTrue(texts.size() > 1);
    for (int i = 0; i + 1 < texts.size(); i++) {
      var t = texts.get(i);
      var style = TextStyle.DEFAULT.with("bold", t.bold() ? "true" : "false")
          .with("italic", t.italic() ? "true" : "false");
      float w = fonts.width(style, t.text(), t.size());
      if (Math.abs(texts.get(i + 1).y() - t.y()) < .5f)
        assertTrue(t.text() + " overflows", t.x() + w <= texts.get(i + 1).x() + .5f);
    }
  }
}
