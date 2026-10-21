package com.codenameone.wordprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Headless tests of in-place equation editing (Wordcraft's math_edit equivalents). */
public class EquationEditorTest {
  private static EquationEditor typed(String... parts) {
    var e = new EquationEditor("");
    for (String p : parts) e.type(p, false);
    return e;
  }

  @Test
  public void typeAnEquationLikeWord() {
    // Wordcraft's type_an_equation_like_word sequence.
    var e = typed("x", "=", "(-b\\pm", " ", "\\sqrt", " ", "(b^2-4ac))/2a", " ");
    assertEquals("x=(-b\u00b1\u221a(b^2-4ac))/2a", e.linear());
    assertEquals("f", e.root.get(1).kind);
  }

  @Test
  public void autocorrectOnSpaceAndRelation() {
    var e = typed("\\alpha ");
    assertEquals("\u03b1", e.linear());
    e = typed("\\alpha=\\beta ");
    assertEquals("\u03b1=\u03b2", e.linear());
    e = typed("\\doubleR ");
    assertEquals("\ud835\udd3b".length(), 2);
    assertEquals("\u211d", e.linear());
    e = typed("\\scriptA ");
    assertEquals("\ud835\udc9c", e.linear());
    e = typed("\\nosuchname ");
    assertEquals("\\nosuchname ", e.linear());
    // Normal text mode converts nothing.
    e = new EquationEditor("");
    e.type("\\alpha a/b ", true);
    assertEquals(1, e.root.size());
    assertTrue(e.root.get(0).normal);
  }

  @Test
  public void buildUpFractionAndScripts() {
    var e = typed("a/b ");
    assertEquals(1, e.root.size());
    assertEquals("f", e.root.get(0).kind);
    assertEquals("a/b", e.linear());
    e = typed("x^2 ");
    assertEquals("sSup", e.root.get(0).kind);
    e = typed("x_i^2 ");
    assertEquals("sSubSup", e.root.get(0).kind);
    // Plain text does not build.
    e = typed("abc ");
    assertEquals("abc ", e.linear());
  }

  @Test
  public void unclosedBracketWaits() {
    var e = typed("(a+b ");
    for (var n : e.root) assertFalse("d".equals(n.kind) && n.b.isEmpty());
    e.type(") ", false);
    assertEquals("d", e.root.get(0).kind);
  }

  @Test
  public void templateCaretAndNavigation() {
    var e = new EquationEditor("");
    e.insertTemplate("\u2b1a/\u2b1a", "");
    assertEquals(2, e.pos.path.length);
    assertEquals(0, e.pos.lastSlot());
    e.type("1", false);
    assertTrue(e.moveVertical(false));
    assertEquals(1, e.pos.lastSlot());
    e.insertText("\u03c0", false);
    assertEquals("1/\u03c0", e.linear());
    assertTrue(e.moveRight());
    assertEquals(0, e.pos.depth());
    assertEquals(1, e.pos.off);
    e.insertTemplate("\u2211_\u2b1a^\u2b1a\u2592\u2b1a", "undOvr");
    e.type("k=1", false);
    assertTrue(e.nextPlaceholder(false));
    e.type("n", false);
    assertTrue(e.nextPlaceholder(false));
    e.type("k", false);
    assertEquals("1/\u03c0\u2211_(k=1)^n\u2592k", e.linear());
    assertFalse(e.nextPlaceholder(false));
    // Right past the end leaves the equation.
    int steps = 0;
    while (e.moveRight() && steps < 50) steps++;
    assertTrue(steps < 50);
    // And left from the end walks back to the start through every slot.
    steps = 0;
    while (e.moveLeft() && steps < 50) steps++;
    assertEquals(new EquationEditor.Pos(null, 0), e.pos);
  }

  @Test
  public void arrowsVisitEverySlotInReadingOrder() {
    var e = new EquationEditor("a/b+x^2+\u221a(3&y)");
    StringBuilder seen = new StringBuilder();
    do seen.append(e.pos.depth() == 0 ? "r" : String.valueOf(e.pos.lastSlot())).append(e.pos.off).append(' ');
    while (e.moveRight());
    // Fraction num, den; script base, sup; radical degree then base.
    assertEquals("r0 00 01 10 11 r1 r2 00 01 10 11 r3 r4 10 11 00 01 r5 ", seen.toString());
  }

  @Test
  public void verticalMoves() {
    var e = new EquationEditor("x_i^2");
    e.moveRight();
    assertEquals(0, e.pos.lastSlot());
    assertTrue(e.moveVertical(true));
    assertEquals(2, e.pos.lastSlot());
    assertTrue(e.moveVertical(false));
    assertEquals(1, e.pos.lastSlot());
    assertFalse(e.moveVertical(false));
    e = new EquationEditor("\u25a0(1&2@3&4)");
    e.moveRight();
    e.moveRight();
    e.moveRight();
    assertEquals(1, e.pos.lastSlot());
    assertTrue(e.moveVertical(false));
    assertEquals(3, e.pos.lastSlot());
  }

  @Test
  public void backspaceDissolvesAndRemoves() {
    var e = new EquationEditor("a/b");
    e.homeEnd(true);
    e.deleteBack();
    // Into the denominator's end.
    assertEquals(1, e.pos.lastSlot());
    e.deleteBack();
    assertEquals("a/\u2b1a", e.linear());
    e.deleteBack();
    assertEquals(0, e.pos.lastSlot());
    e.deleteBack();
    e.deleteBack();
    // Fraction gone with the last slot emptied.
    assertEquals("", e.linear());
    // Backspace at the start of the first slot dissolves the structure.
    e = new EquationEditor("x^2");
    e.moveRight();
    e.deleteBack();
    assertEquals("x2", e.linear());
    // Delete forward removes characters and enters structures.
    e = new EquationEditor("ab");
    e.deleteForward();
    assertEquals("b", e.linear());
  }

  @Test
  public void homeEndAndSurrogates() {
    var e = new EquationEditor("a/bc");
    e.moveRight();
    e.homeEnd(true);
    assertEquals(1, e.pos.depth());
    e.homeEnd(true);
    assertEquals(0, e.pos.depth());
    assertEquals(1, e.pos.off);
    e = typed("\\scriptA x");
    e.homeEnd(false);
    e.moveRight();
    assertEquals(2, e.pos.off);
    e.deleteBack();
    assertEquals("x", e.linear());
  }

  @Test
  public void everyGalleryTemplateInsertsAndRoundTrips() {
    int count = 0;
    var flat = new StringBuilder();
    String[] limits = {"", ""};
    for (String[] row : EquationGallery.STRUCTURES) {
      if (!"T".equals(row[0])) continue;
      count++;
      var e = new EquationEditor("");
      e.insertTemplate(row[3], row[4]);
      String linear = e.linear();
      assertFalse(row[1], linear.isEmpty());
      // The saved linear text parses back to the same tree.
      var back = EquationEditor.parse(linear);
      tidy(back);
      tidy(e.root);
      assertEquals(row[1], EquationEditor.describe(e.root), EquationEditor.describe(back));
      if (EquationEditor.describe(e.root).indexOf('{') < 0) flat.append(row[1]).append(' ');
      // OMML export works on it.
      assertTrue(Equation.omml(Equation.fromLinear(linear)).contains("m:oMath"));
      // And typing in the first slot keeps it valid.
      e.type("z", false);
      assertTrue(row[1], e.linear().contains("z"));
    }
    assertTrue(count > 200);
    // Symbol-only templates are plain text in Wordcraft too; the one-sided and bar brackets do
    // not parse as delimiters here yet and insert as linear text.
    assertEquals("int.dx int.dy int.dtheta brk.abs brk.norm brk.rightSquare brk.rightParen brk.rightSquareOnly"
        + " brk.rightBrace brk.leftBar op.colonEq op.eqeq op.plusEq op.minusEq op.defEq op.measured op.delta"
        + " mat.cdots mat.ldots mat.vdots mat.ddots mat.adots ", flat.toString());
    assertTrue(EquationGallery.SYMBOLS.length >= 8);
    assertNotNull(limits);
  }

  private static void tidy(java.util.List<EquationEditor.N> nodes) {
    var e = new EquationEditor("");
    e.root = nodes;
    e.tidy();
  }

  @Test
  public void builtInsSurviveEditorRoundTrip() {
    for (String[] b : Equation.BUILT_INS) {
      var e = new EquationEditor(b[2]);
      var back = EquationEditor.parse(e.linear());
      tidy(back);
      tidy(e.root);
      assertEquals(b[0], EquationEditor.describe(back), EquationEditor.describe(e.root));
      while (e.moveRight()) {}
    }
  }

  @Test
  public void caretGeometryFollowsSlots() {
    var e = new EquationEditor("a/b");
    float[] start = e.caret(12, new PdfMetrics());
    e.moveRight();
    float[] num = e.caret(12, new PdfMetrics());
    assertTrue(num[1] < start[1] + 0.01f);
    e.moveVertical(false);
    float[] den = e.caret(12, new PdfMetrics());
    assertTrue(den[1] > num[1]);
  }

  private static Object run(Session s, String id, Object... kv) {
    var a = new java.util.LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return new Commands().execute(s, id, a);
  }

  private static String docxXml(Document d) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    Docx.write(d, out);
    var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()));
    for (java.util.zip.ZipEntry z; (z = in.getNextEntry()) != null; )
      if (z.getName().equals("word/document.xml")) return new String(in.readAllBytes(), "UTF-8");
    throw new AssertionError();
  }

  private static Document reopen(Document d) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    Docx.write(d, out);
    return Docx.read(new java.io.ByteArrayInputStream(out.toByteArray())).document;
  }

  @Test
  public void inlineEquationEditsInPlaceWithOneUndoEach() throws Exception {
    var s = new Session(Document.empty());
    s.typeText("ab", 0);
    run(s, "insert.equation", "text", "x");
    s.select(0, 3, 3);
    run(s, "equation.edit", "steps", "end\ntype:+a/b ");
    var p = (Paragraph) s.document().blocks.get(0);
    assertEquals("x+a/b", Equation.inlineText(p.text.fieldAt(2)));
    run(s, "equation.edit", "steps", "template:\u2b1a^\u2b1a\ntype:y\nnext\ntype:2");
    p = (Paragraph) s.document().blocks.get(0);
    assertEquals("x+a/b y^2", Equation.inlineText(p.text.fieldAt(2)));
    // Saved as built-up inline Office Math and read back the same.
    String xml = docxXml(s.document());
    assertTrue(xml, xml.contains("<m:f>") && xml.contains("<m:sSup>") && !xml.contains("oMathPara"));
    var back = (Paragraph) reopen(s.document()).blocks.get(0);
    assertEquals("x+a/b y^2", Equation.inlineText(back.text.fieldAt(2)));
    s.undo();
    p = (Paragraph) s.document().blocks.get(0);
    assertEquals("x+a/b", Equation.inlineText(p.text.fieldAt(2)));
    s.undo();
    p = (Paragraph) s.document().blocks.get(0);
    assertEquals("x", Equation.inlineText(p.text.fieldAt(2)));
  }

  @Test
  public void displayEquationEditsInPlace() throws Exception {
    var s = new Session(Document.empty());
    run(s, "insert.equation", "text", "E=mc");
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++) if (s.document().blocks.get(i) instanceof Picture) block = i;
    run(s, "equation.edit", "block", block, "steps", "type:^2 ");
    var pic = (Picture) s.document().blocks.get(block);
    assertEquals("E=mc^2", pic.drawing.get("text", ""));
    // Navigation alone changes nothing (no undo step).
    run(s, "equation.edit", "block", block, "steps", "home\nright\nright");
    s.undo();
    assertEquals("E=mc", ((Picture) s.document().blocks.get(block)).drawing.get("text", ""));
    s.redo();
    String xml = docxXml(s.document());
    assertTrue(xml, xml.contains("oMathPara") && xml.contains("<m:sSup>"));
    // PDF renders the edited equation.
    var ops = DrawingOps.build(((Picture) s.document().blocks.get(block)).drawing, 400, 40, new PdfMetrics());
    assertFalse(ops.texts.isEmpty());
    var gallery = (java.util.Map<?, ?>) run(s, "equation.gallery");
    assertEquals(11, ((java.util.List<?>) gallery.get("structures")).size());
    assertEquals(8, ((java.util.List<?>) gallery.get("symbols")).size());
  }

  @Test
  public void sessionCaretGeometry() {
    var s = new Session(Document.empty());
    run(s, "insert.equation", "text", "a/b", "display", true);
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++) if (s.document().blocks.get(i) instanceof Picture) block = i;
    var e = EquationEditing.at(s, block);
    assertNotNull(e);
    var d = ((Picture) s.document().blocks.get(block)).drawing;
    float[] end = e.caret(d, 400, 40, new PdfMetrics());
    e.editor.homeEnd(false);
    float[] start = e.caret(d, 400, 40, new PdfMetrics());
    assertTrue(end[0] > start[0]);
    assertTrue(start[0] > 100);
  }
}
