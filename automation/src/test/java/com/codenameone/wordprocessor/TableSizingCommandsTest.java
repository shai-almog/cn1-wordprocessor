package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class TableSizingCommandsTest {
  private final Commands commands = new Commands();

  private Session fixture() {
    Session s = new Session(Document.empty());
    s.insertTable(3, 2);
    s.setCell(1, 0, 0, "short");
    s.setCell(1, 0, 1, "a longer phrase");
    s.enterCell(1, 1, 1);
    return s;
  }

  private Table table(Session s) {
    return (Table) s.document().blocks.get(1);
  }

  @Test
  public void propertiesAreAtomicAndPreserveCellSelection() {
    Session s = fixture();
    s.selectCells(1, 0, 0, 1, 1);
    Document before = s.document();
    CellSelection selection = s.cellSelection();
    commands.execute(
        s, "table.properties", Json.object("widthPct", 50, "align", "center", "indent", 12));
    assertEquals(50, table(s).format.sizing.width, 0);
    assertEquals("center", table(s).format.sizing.align);
    assertSame(selection, s.cellSelection());
    s.undo();
    assertSame(before, s.document());
    s.redo();
    assertEquals(50, table(s).format.sizing.width, 0);
  }

  @Test
  public void contentsAndFixedWidthsUseActiveSectionAndUndo() {
    Session s = fixture();
    Document before = s.document();
    commands.execute(s, "table.autofit", Json.object("mode", "contents"));
    assertEquals("auto", table(s).format.sizing.unit);
    assertFalse(table(s).format.sizing.fixed);
    float[] actual =
        commands
            .layoutEngine()
            .tableWidths(table(s), s.activeSection().resolved.page.contentWidth());
    commands.execute(s, "table.autofit", Json.object("mode", "fixed"));
    assertEquals("points", table(s).format.sizing.unit);
    assertTrue(table(s).format.sizing.fixed);
    assertArrayEquals(actual, commands.layoutEngine().tableWidths(table(s), 100), .001f);
    s.undo();
    assertEquals("auto", table(s).format.sizing.unit);
    s.undo();
    assertSame(before, s.document());
  }

  @Test
  public void structuralChangesRetainPlacement() {
    Session s = fixture();
    commands.execute(
        s, "table.properties", Json.object("width", 180, "align", "right", "indent", -12));
    commands.execute(s, "table.insertRowAbove");
    assertEquals(180, table(s).format.sizing.width, 0);
    commands.execute(s, "table.splitTable");
    for (Block b : s.document().blocks)
      if (b instanceof Table) {
        TableFormat.Sizing size = ((Table) b).format.sizing;
        assertEquals(180, size.width, 0);
        assertEquals("right", size.align);
        assertEquals(-12, size.indent, 0);
      }
    s.undo();
    s.enterCell(1, 0, 0);
    s.splitCell(1, 0, 0, 2);
    assertEquals("right", table(s).format.sizing.align);
  }

  @Test
  public void invalidSizingRollsBackWithoutUndoEntry() {
    Session s = fixture();
    Document before = s.document();
    for (java.util.Map<String, Object> args :
        java.util.Arrays.asList(
            Json.object("width", -1),
            Json.object("widthPct", 101),
            Json.object("width", Double.NaN),
            Json.object("align", "bad"))) {
      try {
        commands.execute(s, "table.properties", args);
        fail();
      } catch (IllegalArgumentException expected) {
      }
      assertSame(before, s.document());
    }
    try {
      commands.execute(s, "table.autofit", Json.object("mode", "bad"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
  }

  @Test
  public void automaticGridWidthsSurviveColumnEditsAndCellClipboard() {
    Session s = fixture();
    Table t = table(s);
    s.formatTable(
        1,
        t.format
            .column(0, 40)
            .column(1, 80)
            .sizing(new TableFormat.Sizing("auto", 0, "right", 12, true)));
    s.formatTable(1, table(s).format.columnFraction(0, .25f));
    assertEquals(120, table(s).format.preferredWidth(500), .001);
    commands.execute(s, "table.distributeColumns");
    assertEquals(60, table(s).format.column(0), .001);
    assertEquals(120, table(s).format.preferredWidth(500), .001);
    s.selectCells(1, 0, 0, 2, 1);
    Table copy = (Table) s.copySelection().blocks.get(0);
    assertEquals("auto", copy.format.sizing.unit);
    assertEquals("right", copy.format.sizing.align);
    assertEquals(120, copy.format.preferredWidth(500), .001);
    s.formatTable(
        1, table(s).format.sizing(new TableFormat.Sizing("points", 200, "center", 0, true)));
    s.selectCells(1, 0, 0, 2, 0);
    copy = (Table) s.copySelection().blocks.get(0);
    assertEquals(100, copy.format.sizing.width, .001);
  }

  @Test
  public void copyingAllSevenColumnsDoesNotRoundPercentPastOneHundred() {
    Session s = new Session(Document.empty());
    s.insertTable(2, 7);
    s.selectCells(1, 0, 0, 1, 6);
    Table copy = (Table) s.copySelection().blocks.get(0);
    assertEquals(100, copy.format.sizing.width, 0);
  }
}
