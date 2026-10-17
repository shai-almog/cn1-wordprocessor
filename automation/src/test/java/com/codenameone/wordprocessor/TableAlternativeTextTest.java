package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class TableAlternativeTextTest {
  private final Commands commands = new Commands();

  private Session fixture() {
    Session s = new Session(Document.empty());
    s.insertTable(3, 2);
    s.enterCell(1, 1, 0);
    return s;
  }

  private TableFormat f(Session s, int b) {
    return ((Table) s.document().blocks.get(b)).format;
  }

  @Test
  public void titleAndDescriptionCanBeEditedIndependentlyClearedAndUndone() {
    Session s = fixture();
    Document before = s.document();
    commands.execute(
        s, "table.altText", Json.object("title", "Quarterly sales", "text", "Revenue by region"));
    assertEquals("Quarterly sales", f(s, 1).title);
    assertEquals("Revenue by region", f(s, 1).description);
    assertEquals(1, s.cellRow());
    commands.execute(s, "table.altText", Json.object("title", ""));
    assertEquals("", f(s, 1).title);
    assertEquals("Revenue by region", f(s, 1).description);
    s.undo();
    assertEquals("Quarterly sales", f(s, 1).title);
    s.undo();
    assertSame(before, s.document());
    s.redo();
    assertEquals("Revenue by region", f(s, 1).description);
  }

  @Test
  public void structureFormattingClipboardAndSplittingKeepAlternativeText() {
    Session s = fixture();
    commands.execute(s, "table.altText", Json.object("title", "Sales", "text", "Numbers"));
    commands.execute(s, "table.insertRowAbove");
    commands.execute(s, "table.insertColumnRight");
    commands.execute(s, "table.distributeColumns");
    commands.execute(s, "table.distributeRows");
    commands.execute(s, "table.cellAlign", Json.object("value", "center"));
    commands.execute(s, "table.split", Json.object("columns", 3));
    assertEquals("Sales", f(s, 1).title);
    assertEquals("Numbers", f(s, 1).description);
    commands.execute(s, "table.splitTable");
    assertEquals("Sales", f(s, 1).title);
    assertEquals("Numbers", f(s, 3).description);
    s.enterCell(3, 0, 0);
    commands.execute(s, "table.selectTable");
    Document copy = s.copySelection();
    assertEquals("Sales", ((Table) copy.blocks.get(0)).format.title);
  }

  @Test
  public void invalidContextAndMissingValuesLeaveDocumentUnchanged() {
    Session s = fixture();
    Document before = s.document();
    try {
      commands.execute(s, "table.altText");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    s.leaveCell();
    try {
      commands.execute(s, "table.altText", Json.object("title", "No table"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
  }

  @Test
  public void unicodeLimitDoesNotSplitSurrogatePairsAndWhitespaceClears() {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 10002; i++) b.append("\ud83d\ude00");
    TableFormat f = new TableFormat(1, 1).altText(b.toString(), " \n\t ");
    assertEquals(20000, f.title.length());
    assertTrue(Character.isLowSurrogate(f.title.charAt(19999)));
    assertEquals("", f.description);
  }
}
