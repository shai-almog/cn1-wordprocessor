package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
}
