package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class TableFormatTest {
  private Session sample() {
    var s = new Session(Document.empty());
    s.insertTable(3, 3);
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) s.setCell(1, r, c, "" + (3 - r));
    return s;
  }

  private Table table(Session s) {
    return (Table) s.document().blocks.get(1);
  }

  private TableFormat.Cell blue = new TableFormat.Cell(0xddeeff, 0x123456, 2, 9, 5, "bottom");

  @Test
  public void formatTransactionPreservesCellSelectionAndUndo() {
    var s = sample();
    s.enterCell(1, 1, 1);
    s.select(0, 0, 1);
    var before = s.document();
    s.formatTable(
        1, table(s).format.cell(1, 1, blue).columnFraction(1, .6f).minHeight(1, 70).headerRows(1));
    assertSame(blue, table(s).format.cell(1, 1));
    assertTrue(s.inCell());
    assertEquals(1, s.selection().caret);
    s.replace("Edited");
    assertSame(blue, table(s).format.cell(1, 1));
    s.undo();
    s.undo();
    assertSame(before, s.document());
    assertTrue(s.inCell());
    assertEquals(1, s.selection().caret);
    s.redo();
    assertSame(blue, table(s).format.cell(1, 1));
  }

  @Test
  public void structuralChangesAndSortingCarryCellAppearance() {
    var s = sample();
    s.formatTable(1, table(s).format.cell(0, 0, blue).columnFraction(0, .5f).minHeight(0, 60));
    s.sortTable(1, 0, false, false, true);
    assertSame(blue, table(s).format.cell(2, 0));
    assertEquals(60, table(s).format.minHeight(2), 0);
    s.tableStructure(1, "insertRow", 0);
    assertSame(blue, table(s).format.cell(3, 0));
    s.tableStructure(1, "insertColumn", 0);
    assertSame(blue, table(s).format.cell(3, 1));
    s.tableStructure(1, "deleteRow", 0);
    s.tableStructure(1, "deleteColumn", 0);
    assertSame(blue, table(s).format.cell(2, 0));
    assertEquals(.5, table(s).format.columnFraction(0), .0001);
  }

  @Test
  public void deletingMergedOwnerPreservesItsAppearance() {
    var s = sample();
    s.formatTable(1, table(s).format.cell(0, 0, blue));
    s.mergeCells(1, 0, 0, 2, 2);
    s.tableStructure(1, "deleteRow", 0);
    s.tableStructure(1, "deleteColumn", 0);
    assertSame(blue, table(s).format.cell(0, 0));
  }

  @Test
  public void invalidHeaderBoundaryRollsBackWithoutLosingCellContext() {
    var s = sample();
    s.mergeCells(1, 0, 0, 2, 1);
    s.enterCell(1, 0, 0);
    var before = s.document();
    try {
      s.formatTable(1, table(s).format.headerRows(1));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    assertTrue(s.inCell());
  }
}
