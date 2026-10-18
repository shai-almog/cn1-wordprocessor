package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class TableMergeTest {
  private Session sample() {
    var s = new Session(Document.empty());
    s.insertTable(3, 3);
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) s.setCell(1, r, c, r + ":" + c);
    return s;
  }

  @Test
  public void mergeSplitAndUndoPreserveEveryParagraph() {
    var s = sample();
    var before = s.document();
    s.mergeCells(1, 0, 0, 2, 2);
    var t = (Table) s.document().blocks.get(1);
    assertEquals("0:0\n0:1\n1:0\n1:1", Table.cellText(t.cell(0, 0)));
    assertTrue(t.covered(1, 1));
    assertEquals(2, t.span(1, 1).rows);
    var ids = new java.util.HashSet<String>();
    for (int r = 0; r < t.rows(); r++)
      for (int c = 0; c < t.columns(); c++)
        for (Paragraph p : t.cell(r, c)) assertTrue(ids.add(p.id));
    s.splitCell(1, 1, 1);
    assertTrue(((Table) s.document().blocks.get(1)).merges.isEmpty());
    assertEquals(
        "0:0\n0:1\n1:0\n1:1", Table.cellText(((Table) s.document().blocks.get(1)).cell(0, 0)));
    s.undo();
    assertTrue(((Table) s.document().blocks.get(1)).covered(1, 1));
    s.undo();
    assertSame(before, s.document());
  }

  @Test
  public void insertAndDeleteThroughMergeKeepOwnerContent() {
    var s = sample();
    s.mergeCells(1, 0, 0, 2, 2);
    s.tableStructure(1, "insertRow", 1);
    s.tableStructure(1, "insertColumn", 1);
    var t = (Table) s.document().blocks.get(1);
    assertEquals(3, t.span(0, 0).rows);
    assertEquals(3, t.span(0, 0).columns);
    s.tableStructure(1, "deleteRow", 0);
    s.tableStructure(1, "deleteColumn", 0);
    t = (Table) s.document().blocks.get(1);
    assertEquals(2, t.span(0, 0).rows);
    assertEquals(2, t.span(0, 0).columns);
    assertEquals("0:0\n0:1\n1:0\n1:1", Table.cellText(t.cell(0, 0)));
  }

  @Test
  public void partialMergeAndCoveredCellEditRollBack() {
    var s = sample();
    s.mergeCells(1, 0, 0, 2, 2);
    var before = s.document();
    try {
      s.mergeCells(1, 1, 1, 2, 2);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    try {
      s.setCell(1, 1, 1, "lost");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
  }
}
