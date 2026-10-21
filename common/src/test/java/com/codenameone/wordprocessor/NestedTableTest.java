package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import com.codename1.ui.RichTextFormat;
import org.junit.Test;

/** Tables nested in cells, edited through the recursive cell context as Wordcraft's paths. */
public class NestedTableTest {
  static Session sample() {
    var s = new Session(Document.empty());
    s.replace("Body");
    s.insertTable(2, 2);
    s.replace("outer");
    s.insertTable(2, 2);
    s.replace("inner");
    return s;
  }

  static Table outer(Session s) {
    return (Table) s.document().blocks.get(1);
  }

  static Table inner(Session s) {
    for (Block b : outer(s).blocks(0, 0)) if (b instanceof Table) return (Table) b;
    throw new AssertionError("no nested table");
  }

  @Test
  public void insertTypeAndNavigateNestedCells() {
    var s = sample();
    assertEquals(2, s.cellDepth());
    assertArrayEquals(new int[] {1, 0, 0, 1, 0, 0}, s.cellPath());
    assertEquals("inner", inner(s).cell(0, 0).get(0).text.toString());
    assertEquals("outer", outer(s).cell(0, 0).get(0).text.toString());
    s.nextCell(false);
    s.replace("x");
    assertArrayEquals(new int[] {1, 0, 0, 1, 0, 1}, s.cellPath());
    assertEquals("x", inner(s).cell(0, 1).get(0).text.toString());
    s.tableStructure(s.cellBlock(), "insertRow", 2);
    assertEquals(3, inner(s).rows());
    assertEquals(2, outer(s).rows());
    // Structure edits leave the nested cell for the story holding its table (the outer cell).
    assertEquals(1, s.cellDepth());
    s.enterNestedCell(1, 1, 1);
    assertEquals(2, s.cellDepth());
    s.leaveCellLevel();
    assertEquals(1, s.cellDepth());
    s.leaveCell();
    assertFalse(s.inCell());
    s.enterCellPath(new int[] {1, 0, 0, 1, 0, 1});
    s.select(0, 1, 1);
    s.replace("y");
    assertEquals("xy", inner(s).cell(0, 1).get(0).text.toString());
    s.undo();
    assertEquals("x", inner(s).cell(0, 1).get(0).text.toString());
    s.undo();
    assertEquals(2, inner(s).rows());
  }

  @Test
  public void nestedTablesRoundTripNativeAndDocx() throws Exception {
    var d = sample().document();
    var xml = DocumentXml.write(d);
    assertEquals(xml, DocumentXml.write(DocumentXml.read(xml)));
    var back = DocumentXml.read(xml);
    assertTrue(((Table) back.blocks.get(1)).nested());
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    var docx = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    var t = (Table) docx.blocks.get(1);
    assertTrue(t.nested());
    Table in = null;
    for (Block b : t.blocks(0, 0)) if (b instanceof Table) in = (Table) b;
    assertEquals("inner", in.cell(0, 0).get(0).text.toString());
  }

  @Test
  public void nestedTableIsLaidOutInsideTheOuterCell() {
    var d = sample().document();
    var layout = new LayoutEngine(new PdfMetrics()).layout(d, 0);
    LayoutEngine.Line innerLine = null, outerLine = null;
    for (LayoutEngine.Page p : layout.pages)
      for (LayoutEngine.Line l : p.lines) {
        if (l.path.length == 3) innerLine = l;
        if (l.row == 0 && l.column == 0 && l.path.length == 0 && l.end > 0) outerLine = l;
      }
    assertNotNull(innerLine);
    assertNotNull(outerLine);
    assertEquals(1, innerLine.block);
    assertTrue(innerLine.y > outerLine.y);
    int boxes = 0;
    for (LayoutEngine.Box b : layout.pages.get(0).boxes) if (b.path.length == 3) boxes++;
    assertEquals(4, boxes);
  }

  static Table nestedIn(Document d) {
    for (Block b : d.blocks)
      if (b instanceof Table t)
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            for (Block x : t.blocks(r, c)) if (x instanceof Table n) return n;
    return null;
  }

  @Test
  public void nestedTablesRoundTripOdtHtmlAndRtf() throws Exception {
    var d = sample().document();
    var out = new ByteArrayOutputStream();
    Odt.write(d, out);
    var odt = Odt.read(new ByteArrayInputStream(out.toByteArray())).document;
    assertNotNull("odt", nestedIn(odt));
    assertEquals("inner", nestedIn(odt).cell(0, 0).get(0).text.toString());
    for (RichTextFormat f : new RichTextFormat[] {RichTextFormat.HTML, RichTextFormat.RTF}) {
      var src = TextFormats.write(d, f);
      var back = TextFormats.read(src, f);
      assertNotNull(f.toString(), nestedIn(back));
      assertEquals(f.toString(), "inner", nestedIn(back).cell(0, 0).get(0).text.toString());
    }
  }

  @Test
  public void rtfNestedTablesReadWordsForm() {
    // Word's RTF 1.8 nested table: \itap2 paragraphs, \nestcell, row properties at \nestrow.
    String rtf =
        "{\\rtf1\\ansi \\trowd\\cellx4000\\cellx8000\n"
            + "\\pard\\intbl before\\par\n"
            + "\\pard\\intbl\\itap2 a1\\nestcell \\pard\\intbl\\itap2 b1\\nestcell\n"
            + "{\\*\\nesttableprops\\trowd\\cellx2000\\cellx4000\\nestrow}{\\nonesttables\\par}\n"
            + "\\pard\\intbl\\itap2 a2\\nestcell \\pard\\intbl\\itap2 b2\\nestcell\n"
            + "{\\*\\nesttableprops\\trowd\\cellx2000\\cellx4000\\nestrow}{\\nonesttables\\par}\n"
            + "\\pard\\intbl after\\cell \\pard\\intbl right\\cell\\row\n}";
    var d = TextFormats.read(rtf, RichTextFormat.RTF);
    Table outer = null;
    for (Block b : d.blocks) if (b instanceof Table t) outer = t;
    assertNotNull(outer);
    assertEquals(2, outer.columns());
    var inner = nestedIn(d);
    assertNotNull(inner);
    assertEquals(2, inner.rows());
    assertEquals("b2", inner.cell(1, 1).get(0).text.toString());
    assertEquals("before", outer.cell(0, 0).get(0).text.toString());
    assertEquals("after", outer.cell(0, 0).get(1).text.toString());
    assertEquals("right", outer.cell(0, 1).get(0).text.toString());
    // Written back, the nested table survives a round trip.
    var back = TextFormats.read(TextFormats.write(d, RichTextFormat.RTF), RichTextFormat.RTF);
    assertEquals("b2", nestedIn(back).cell(1, 1).get(0).text.toString());
  }

  @Test
  public void footnotesInNestedCells() {
    var s = sample();
    s.select(0, 5, 5);
    s.insertNote("footnote", "Deep note", "");
    var refs = Notes.references(s.document());
    assertEquals(1, refs.size());
    assertArrayEquals(new int[] {1, 0, 0, 1, 0, 0}, refs.get(0).path);
    assertEquals("Deep note", Notes.all(s.document()).get(0).paragraphs.get(0).text.toString());
    s.leaveCell();
    s.nextNote(false);
    assertArrayEquals(new int[] {1, 0, 0, 1, 0, 0}, s.cellPath());
    assertEquals(5, s.selection().start());
    var layout = new LayoutEngine(new PdfMetrics()).layout(s.document(), 0);
    boolean shown = false;
    for (LayoutEngine.Page page : layout.pages)
      shown |= !page.notes.isEmpty();
    assertTrue("the nested cell's footnote is laid out on its page", shown);
    // Editing the note story writes back into the nested cell's reference.
    s.enterNote(Notes.all(s.document()).get(0).id);
    s.select(0, 0, 0);
    s.replace("Very ");
    s.leaveCell();
    assertEquals("Very Deep note", Notes.all(s.document()).get(0).paragraphs.get(0).text.toString());
  }

  @Test
  public void nestedTableSplitsAcrossPagesWithItsRow() {
    var s = new Session(Document.empty());
    s.insertTable(1, 1);
    s.insertTable(80, 1);
    for (int r = 0; r < 79; r++) {
      s.replace("row " + r);
      s.nextCell(false);
    }
    s.leaveCell();
    var layout = new LayoutEngine(new PdfMetrics()).layout(s.document(), 0);
    assertTrue(layout.pages.size() > 1);
    // Nested lines continue on the next page (the outer row splits between nested lines).
    boolean continued = false;
    for (LayoutEngine.Line line : layout.pages.get(1).lines) continued |= line.path.length == 3;
    assertTrue(continued);
  }
}
