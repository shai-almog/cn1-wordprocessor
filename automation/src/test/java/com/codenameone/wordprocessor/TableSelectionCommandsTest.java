package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TableSelectionCommandsTest {
  @Test
  public void contextualCommandsAndLiveTokensRespectCellRectangles() {
    var s = new Session(Document.empty());
    var c = new Commands();
    var live = new LiveCommands(c);
    s.insertTable(3, 3);
    s.enterCell(1, 1, 1);
    long revision = s.revision();
    c.execute(s, "table.selectColumn");
    assertEquals(0, s.cellSelection().firstRow);
    assertEquals(2, s.cellSelection().lastRow);
    assertEquals(1, s.cellSelection().firstColumn);
    var token = live.stateId(s);
    s.selectCells(1, 1, 1, 2, 1);
    assertFalse(token.equals(live.stateId(s)));
    assertEquals(revision, s.revision());
    try {
      live.execute(s, token, "table.clearCells", Collections.<String, Object>emptyMap());
      fail();
    } catch (IllegalStateException expected) {
    }
    c.execute(s, "table.selectTable");
    assertEquals(2, s.cellSelection().lastColumn);
    c.execute(s, "table.selectCell");
    assertEquals(2, s.cellSelection().firstRow);
    s.selectCells(1, 0, 0, 1, 1);
    c.execute(s, "table.mergeSelected");
    assertEquals(2, ((Table) s.document().blocks.get(1)).span(0, 0).rows);
    s.undo();
    assertNotNull(s.cellSelection());
    assertEquals(1, s.cellSelection().lastRow);
  }

  @Test
  public void deletingSelectedRowsOrColumnsIsOneUndoStepAndAllRemovesTable() {
    var s = new Session(Document.empty());
    var c = new Commands();
    s.insertTable(3, 3);
    s.setCell(1, 0, 0, "keep");
    s.selectCells(1, 1, 0, 2, 1);
    var before = s.document();
    c.execute(s, "table.deleteRows");
    assertEquals(1, ((Table) s.document().blocks.get(1)).rows());
    s.undo();
    assertSame(before, s.document());
    assertNotNull(s.cellSelection());
    c.execute(s, "table.deleteColumns");
    assertEquals(1, ((Table) s.document().blocks.get(1)).columns());
    s.undo();
    assertSame(before, s.document());
    c.execute(s, "table.selectTable");
    c.execute(s, "table.deleteRows");
    assertFalse(s.document().blocks.get(1) instanceof Table);
    s.undo();
    assertSame(before, s.document());
    assertNotNull(s.cellSelection());
  }
}
