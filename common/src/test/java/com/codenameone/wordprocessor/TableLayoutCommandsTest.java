package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class TableLayoutCommandsTest {
  private final Commands commands = new Commands();

  private Session fixture() {
    var s = new Session(Document.empty());
    s.insertTable(3, 3);
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) s.setCell(1, r, c, "r" + r + "c" + c);
    s.enterCell(1, 1, 1);
    return s;
  }

  private Table table(Session s) {
    return (Table) s.document().blocks.get(1);
  }

  @Test
  public void insertionMovesCaretAndUndoRestoresOriginalCell() {
    for (String name : new String[] {"RowAbove", "RowBelow", "ColumnLeft", "ColumnRight"}) {
      var s = fixture();
      var before = s.document();
      commands.execute(s, "table.insert" + name);
      boolean row = name.startsWith("Row"),
          after = name.endsWith("Below") || name.endsWith("Right");
      int at = after ? 2 : 1;
      assertEquals(row ? 4 : 3, table(s).rows());
      assertEquals(row ? 3 : 4, table(s).columns());
      assertEquals(row ? at : 1, s.cellRow());
      assertEquals(row ? 1 : at, s.cellColumn());
      assertEquals("", Table.cellText(table(s).cell(s.cellRow(), s.cellColumn())));
      assertEquals(
          "r1c1", Table.cellText(table(s).cell(row && !after ? 2 : 1, !row && !after ? 2 : 1)));
      s.undo();
      assertSame(before, s.document());
      assertEquals(1, s.cellRow());
      assertEquals(1, s.cellColumn());
    }
  }

  @Test
  public void selectedRowInsertionAddsSameCountInOneUndoStep() {
    var s = fixture();
    s.selectCells(1, 0, 0, 1, 2);
    var before = s.document();
    commands.execute(s, "table.insertRowBelow");
    assertEquals(5, table(s).rows());
    assertEquals(2, s.cellRow());
    assertEquals("r2c0", Table.cellText(table(s).cell(4, 0)));
    s.undo();
    assertSame(before, s.document());
    assertEquals(1, s.cellSelection().lastRow);
  }

  @Test
  public void rightInsertionUsesMergedCellEdge() {
    var s = fixture();
    s.mergeCells(1, 1, 1, 1, 2);
    s.enterCell(1, 1, 1);
    commands.execute(s, "table.insertColumnRight");
    assertEquals(4, table(s).columns());
    assertEquals(3, s.cellColumn());
    assertEquals(2, table(s).span(1, 1).columns);
  }

  @Test
  public void rowAndColumnLimitsAreIndependentAndFailuresAreAtomic() {
    var s = new Session(Document.empty());
    s.insertTable(1, 30);
    for (int column = 30; column < 100; column++) s.tableStructure(1, "insertColumn", column);
    s.enterCell(1, 0, 50);
    commands.execute(s, "table.insertRowBelow");
    assertEquals(2, table(s).rows());
    var before = s.document();
    var selection = s.selection();
    try {
      commands.execute(s, "table.insertColumnRight");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    assertEquals(selection.caret, s.selection().caret);
    assertEquals(50, s.cellColumn());
  }

  @Test
  public void distributePreservesCellStylesAndRangeWithUndo() {
    var s = fixture();
    var shaded = new TableFormat.Cell(0xff0000, 0, 1, 8, 6, "bottom");
    s.formatTable(1, table(s).format.column(0, 5).minHeight(1, 64).cell(1, 1, shaded));
    s.selectCells(1, 0, 0, 1, 1);
    var before = s.document();
    commands.execute(s, "table.distributeColumns");
    for (int c = 0; c < 3; c++) assertEquals(1f / 3, table(s).format.columnFraction(c), .0001);
    assertSame(shaded, table(s).format.cell(1, 1));
    assertNotNull(s.cellSelection());
    s.undo();
    assertSame(before, s.document());
    commands.execute(s, "table.distributeRows");
    for (int r = 0; r < 3; r++) assertEquals(64, table(s).format.minHeight(r), .001);
    s.undo();
    assertSame(before, s.document());
    assertNotNull(s.cellSelection());
  }

  @Test
  public void allNineAlignmentsAffectEveryCellParagraphAndPreserveCaretSelection() {
    for (String vertical : new String[] {"top", "center", "bottom"})
      for (String horizontal : new String[] {"Left", "Center", "Right"}) {
        var s = fixture();
        s.setCell(1, 1, 1, "One\nTwo");
        s.enterCell(1, 1, 1);
        s.select(1, 1, 2);
        var before = s.document();
        var value = vertical + horizontal;
        if (value.equals("centerCenter")) value = "center";
        commands.execute(s, "table.cellAlign", Json.object("value", value));
        assertEquals(vertical, table(s).format.cell(1, 1).vertical);
        for (Paragraph p : table(s).cell(1, 1)) assertEquals(horizontal.toLowerCase(), p.align);
        assertEquals(1, s.selection().block);
        assertEquals(1, s.selection().anchor);
        assertEquals(2, s.selection().caret);
        assertNull(s.cellSelection());
        assertEquals("top", table(s).format.cell(0, 0).vertical);
        assertEquals("left", table(s).cell(0, 0).get(0).align);
        s.undo();
        assertSame(before, s.document());
      }
  }

  @Test
  public void rectangularAlignmentPreservesRangeAndInvalidValuesDoNothing() {
    var s = fixture();
    s.selectCells(1, 0, 0, 1, 1);
    var before = s.document();
    commands.execute(s, "table.cellAlign", Json.object("value", "bottomRight"));
    assertEquals(1, s.cellSelection().lastRow);
    assertEquals(1, s.cellSelection().lastColumn);
    for (int r = 0; r < 3; r++)
      for (int c = 0; c < 3; c++) {
        assertEquals(r < 2 && c < 2 ? "bottom" : "top", table(s).format.cell(r, c).vertical);
        assertEquals(r < 2 && c < 2 ? "right" : "left", table(s).cell(r, c).get(0).align);
      }
    var aligned = s.document();
    try {
      commands.execute(s, "table.cellAlign", Json.object("value", "garbage"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(aligned, s.document());
    s.undo();
    assertSame(before, s.document());
  }

  @Test
  public void insertedRowsInheritCaretRowHeightAndCellAppearance() {
    var s = fixture();
    var blue = new TableFormat.Cell(0x123456, 0xabcdef, 2, 12, 8, "center");
    s.formatTable(1, table(s).format.minHeight(1, 42).cell(1, 1, blue));
    var before = s.document();
    commands.execute(s, "table.insertRowBelow");
    assertEquals(42, table(s).format.minHeight(2), .001);
    assertSame(blue, table(s).format.cell(2, 1));
    assertEquals("", Table.cellText(table(s).cell(2, 1)));
    s.undo();
    assertSame(before, s.document());
  }
}
