package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class TableRowsCommandsTest {
  private final Commands commands = new Commands();

  private Session fixture() {
    var s = new Session(Document.empty());
    s.insertTable(3, 2);
    s.enterCell(1, 1, 0);
    return s;
  }

  private Table table(Session s) {
    return (Table) s.document().blocks.get(1);
  }

  @Test
  public void heightPropertiesAndUndoPreserveCaret() {
    var s = fixture();
    var before = s.document();
    var caret = s.selection();
    commands.execute(
        s,
        "table.properties",
        Json.object("rowHeight", 36, "rowHeightRule", "exact", "allowBreak", false));
    var row = table(s).format.row(1);
    assertEquals(36, row.height, 0);
    assertEquals("exact", row.rule);
    assertFalse(row.allowBreak);
    assertSame(caret, s.selection());
    s.undo();
    assertSame(before, s.document());
    s.redo();
    assertEquals("exact", table(s).format.row(1).rule);
    commands.execute(s, "table.properties", Json.object("allowBreak", true));
    assertEquals("exact", table(s).format.row(1).rule);
    commands.execute(s, "table.properties", Json.object("rowHeight", 0));
    assertEquals("auto", table(s).format.row(1).rule);
  }

  @Test
  public void rowHeightCommandValidatesAtomically() {
    var s = fixture();
    commands.execute(s, "table.rowHeight", Json.object("height", 32, "rule", "exact"));
    var before = s.document();
    for (Object[] bad :
        new Object[][] {{-1, "exact"}, {0, "exact"}, {Float.NaN, "atLeast"}, {22, "wrong"}}) {
      try {
        commands.execute(s, "table.rowHeight", Json.object("height", bad[0], "rule", bad[1]));
        fail();
      } catch (IllegalArgumentException expected) {
      }
      assertSame(before, s.document());
    }
    commands.execute(s, "table.rowHeight", Json.object("height", 25));
    assertEquals("atLeast", table(s).format.row(1).rule);
  }

  @Test
  public void rowRulesFollowInsertionCropSplitAndDistribution() {
    var s = fixture();
    commands.execute(
        s,
        "table.properties",
        Json.object("rowHeight", 36, "rowHeightRule", "exact", "allowBreak", false));
    commands.execute(s, "table.insertRowBelow");
    assertRow(table(s).format.row(2));
    var crop = table(s).crop(new CellSelection(1, table(s), 2, 0, 2, 1));
    assertRow(crop.format.row(0));
    s.splitCell(1, 2, 0, 2);
    assertRow(table(s).format.row(2));
    commands.execute(s, "table.distributeRows");
    assertRow(table(s).format.row(2));
    commands.execute(s, "table.splitTable");
    assertRow(((Table) s.document().blocks.get(3)).format.row(0));
  }

  private void assertRow(TableFormat.Row row) {
    assertEquals(36, row.height, 0);
    assertEquals("exact", row.rule);
    assertFalse(row.allowBreak);
  }
}
