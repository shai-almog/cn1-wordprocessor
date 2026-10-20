package com.codenameone.wordprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class EquationTest {
  private static final String M = "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"";

  private static final String SAMPLE =
      "<m:oMathPara " + M + "><m:oMath>"
          + "<m:r><m:t>y=</m:t></m:r>"
          + "<m:f><m:num><m:r><m:t>a</m:t></m:r></m:num><m:den><m:sSup><m:e><m:r><m:t>x</m:t></m:r></m:e>"
          + "<m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup></m:den></m:f>"
          + "<m:r><m:t>+</m:t></m:r>"
          + "<m:rad><m:radPr><m:degHide m:val=\"1\"/></m:radPr><m:deg/><m:e><m:r><m:t>b</m:t></m:r></m:e></m:rad>"
          + "<m:nary><m:naryPr><m:chr m:val=\"\u2211\"/><m:limLoc m:val=\"undOvr\"/></m:naryPr>"
          + "<m:sub><m:r><m:t>i=1</m:t></m:r></m:sub><m:sup><m:r><m:t>n</m:t></m:r></m:sup>"
          + "<m:e><m:sSub><m:e><m:r><m:t>c</m:t></m:r></m:e><m:sub><m:r><m:t>i</m:t></m:r></m:sub></m:sSub></m:e></m:nary>"
          + "<m:d><m:dPr><m:begChr m:val=\"[\"/><m:endChr m:val=\"]\"/></m:dPr><m:e><m:m>"
          + "<m:mr><m:e><m:r><m:t>1</m:t></m:r></m:e><m:e><m:r><m:t>0</m:t></m:r></m:e></m:mr>"
          + "<m:mr><m:e><m:r><m:t>0</m:t></m:r></m:e><m:e><m:r><m:t>1</m:t></m:r></m:e></m:mr></m:m></m:e></m:d>"
          + "<m:acc><m:accPr><m:chr m:val=\"\u0303\"/></m:accPr><m:e><m:r><m:t>v</m:t></m:r></m:e></m:acc>"
          + "</m:oMath></m:oMathPara>";

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

  private static Drawing equation(Document d) {
    for (Block b : d.blocks)
      if (b instanceof Picture p && p.drawing != null && "equation".equals(p.drawing.kind)) return p.drawing;
    throw new AssertionError("no equation");
  }

  private static String documentXml(Document d) throws Exception {
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    for (ZipEntry e; (e = in.getNextEntry()) != null; )
      if (e.getName().equals("word/document.xml")) return new String(in.readAllBytes(), "UTF-8");
    throw new AssertionError();
  }

  @Test
  public void importsStructure() throws Exception {
    Drawing d = equation(open("<w:p>" + SAMPLE + "</w:p>").document);
    Equation.Node n = Equation.of(d);
    String kinds = kinds(n);
    for (String k : new String[] {"f", "sSup", "rad", "nary", "sSub", "d", "m", "acc"})
      assertTrue(k + " in " + kinds, kinds.contains(" " + k + " "));
    String linear = d.get("text", "");
    assertTrue(linear, linear.startsWith("y=a/(x^2)+\u221ab\u2211_(i=1)^n\u2592(c_i)[\u25a0(1&0@0&1)]v\u0303"));
    // The linear form reads back to the same structure.
    assertEquals(kinds, kinds(Equation.fromLinear(linear)));
  }

  private static String kinds(Equation.Node n) {
    var b = new StringBuilder(" ");
    var stack = new java.util.ArrayList<Equation.Node>();
    stack.add(n);
    while (!stack.isEmpty()) {
      var x = stack.remove(stack.size() - 1);
      if (!"row".equals(x.kind) && !"run".equals(x.kind) && !"mr".equals(x.kind)) b.append(x.kind).append(' ');
      for (int i = x.args.size() - 1; i >= 0; i--) stack.add(x.args.get(i));
    }
    return b.toString();
  }

  @Test
  public void roundTripKeepsSourceXml() throws Exception {
    Document doc = open("<w:p>" + SAMPLE + "</w:p>").document;
    String xml = documentXml(doc);
    String inner = SAMPLE.substring(SAMPLE.indexOf("<m:oMath>") + 9, SAMPLE.indexOf("</m:oMath>"));
    // Self-closing elements are written open/close; everything else is the source verbatim.
    inner = inner.replaceAll("<(m:[A-Za-z]+)( [^>]*?)?/>", "<$1$2></$1>");
    assertTrue(xml, xml.contains(inner));
    Document again = open(xml.substring(xml.indexOf("<w:body>") + 8, xml.indexOf("</w:body>"))).document;
    assertEquals(equation(doc).get("text", ""), equation(again).get("text", ""));
  }

  @Test
  public void editedEquationIsRegeneratedAsOfficeMath() throws Exception {
    var s = new Session(Document.empty());
    var a = new LinkedHashMap<String, Object>();
    a.put("text", "(a+b)/c+x_1^2+sqrt(x)");
    Object r = new Commands().execute(s, "insert.equation", a);
    int block = ((Number) ((java.util.Map<?, ?>) r).get("block")).intValue();
    String xml = documentXml(s.document());
    assertTrue(xml, xml.contains("<m:f><m:num><m:r><m:t xml:space=\"preserve\">a</m:t></m:r>"));
    assertTrue(xml.contains("<m:sSubSup>"));
    assertTrue(xml.contains("<m:rad>"));
    Drawing back = equation(open(xml.substring(xml.indexOf("<w:body>") + 8, xml.indexOf("</w:body>"))).document);
    assertEquals(kinds(Equation.fromLinear("(a+b)/c+x_1^2+sqrt(x)")), kinds(Equation.of(back)));
    // Undo removes the inserted equation; redo restores it.
    int before = s.document().blocks.size();
    s.undo();
    assertTrue(s.document().blocks.size() < before || !(s.document().blocks.get(Math.min(block, s.document().blocks.size() - 1)) instanceof Picture));
    s.redo();
    assertEquals(before, s.document().blocks.size());
  }

  @Test
  public void layoutBoxesStack() {
    var metrics = new PdfMetrics();
    Equation.Box plain = Equation.layout(Equation.fromLinear("ab"), 12, metrics);
    Equation.Box frac = Equation.layout(Equation.fromLinear("a/b"), 12, metrics);
    assertTrue(frac.ascent > plain.ascent && frac.descent > plain.descent);
    assertEquals(1, frac.lines.size()); // the fraction bar
    Equation.Glyph num = frac.glyphs.get(0), den = frac.glyphs.get(1);
    assertTrue(num.y() < 0 && den.y() > 0);
    Equation.Box sup = Equation.layout(Equation.fromLinear("x^2"), 12, metrics);
    assertTrue(sup.glyphs.get(1).size() < 12 && sup.glyphs.get(1).y() < 0);
    assertTrue(sup.glyphs.get(1).x() >= sup.glyphs.get(0).width());
    Equation.Box rad = Equation.layout(Equation.fromLinear("sqrt(x)"), 12, metrics);
    assertEquals(1, rad.lines.size());
    Equation.Box matrix = Equation.layout(Equation.fromLinear("[(1&0@0&1)]"), 12, metrics);
    assertEquals(6, matrix.glyphs.size());
    // Drawing ops carry the same glyphs to the editor and PDF.
    Drawing d = Drawing.of("equation").with("text", "a/b");
    DrawingOps.Ops ops = DrawingOps.build(d, 200, 40);
    assertEquals(2, ops.texts.size());
    assertEquals(1, ops.paths.size());
  }

  @Test
  public void latexExportWritesMath() {
    assertEquals("\\frac{a}{b}", Equation.latex(Equation.fromLinear("a/b")));
    assertEquals("{x}_{1}^{2}", Equation.latex(Equation.fromLinear("x_1^2")));
    assertEquals("\\sqrt{x}", Equation.latex(Equation.fromLinear("sqrt(x)")));
    assertEquals("\\sum_{i=1}^{n} {i}", Equation.latex(Equation.fromLinear("\\sum_(i=1)^n i")));
    var s = new Session(Document.empty());
    var a = new LinkedHashMap<String, Object>();
    a.put("text", "x^2");
    new Commands().execute(s, "insert.equation", a);
    String tex = Latex.write(s.document());
    assertTrue(tex, tex.contains("\\[{x}^{2}\\]"));
    assertTrue(tex.contains("\\usepackage{amsmath}"));
  }

  @Test
  public void hostileNestingIsBounded() throws Exception {
    var b = new StringBuilder();
    for (int i = 0; i < 3000; i++) b.append("<m:f><m:num>");
    b.append("<m:r><m:t>z</m:t></m:r>");
    for (int i = 0; i < 3000; i++) b.append("</m:num><m:den/></m:f>");
    try {
      Docx.Opened o = open("<w:p><m:oMath " + M + ">" + b + "</m:oMath></w:p>");
      Equation.Node n = Equation.of(equation(o.document));
      Equation.layout(n, 12, new PdfMetrics());
      Equation.latex(n);
    } catch (java.io.IOException | IllegalArgumentException rejectedByXmlLimits) {
      // the XML reader's own depth limit is an acceptable outcome too
    }
    var linear = new StringBuilder();
    for (int i = 0; i < 5000; i++) linear.append("(");
    linear.append("x");
    Equation.Node n = Equation.fromLinear(linear.toString());
    Equation.layout(n, 12, new PdfMetrics());
    var deep = new StringBuilder("x");
    for (int i = 0; i < 5000; i++) deep.append("^x");
    Equation.layout(Equation.fromLinear(deep.toString()), 12, new PdfMetrics());
  }
}
