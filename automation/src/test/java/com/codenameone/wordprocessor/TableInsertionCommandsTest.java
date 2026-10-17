package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class TableInsertionCommandsTest {
  private final Commands commands = new Commands();

  @Test
  public void splitsStyledParagraphAtCaretAndTypesInFirstCell() {
    Session s = new Session(Document.empty());
    s.replace("Before after");
    s.select(0, 0, 12);
    s.format("bold", "true");
    s.select(0, 7, 7);
    Document before = s.document();
    commands.execute(s, "table.insert", Json.object("rows", 2, "columns", 3));
    assertEquals("Before ", s.document().paragraph(0).text.toString());
    assertEquals("after", s.document().paragraph(2).text.toString());
    assertTrue(s.document().paragraph(0).text.styleAt(0).bold);
    assertTrue(s.document().paragraph(2).text.styleAt(0).bold);
    assertEquals(1, s.cellBlock());
    assertEquals(0, s.cellRow());
    assertEquals(0, s.cellColumn());
    Document inserted = s.document();
    commands.execute(s, "text.insert", Json.object("text", "First cell"));
    assertEquals("First cell", Table.cellText(((Table) s.document().blocks.get(1)).cell(0, 0)));
    s.undo();
    assertSame(inserted, s.document());
    s.undo();
    assertSame(before, s.document());
    assertFalse(s.inCell());
    s.redo();
    assertSame(inserted, s.document());
    assertEquals(1, s.cellBlock());
  }

  @Test
  public void reversedSelectionAcrossParagraphsKeepsOutsideText() {
    Session s = new Session(Document.empty());
    s.replace("Before CUT\nREMOVE after");
    s.selectRange(1, 6, 0, 7);
    Document before = s.document();
    commands.execute(s, "table.insert", Json.object("rows", 1, "columns", 1));
    assertEquals(3, s.document().blocks.size());
    assertEquals("Before ", s.document().paragraph(0).text.toString());
    assertEquals(" after", s.document().paragraph(2).text.toString());
    s.undo();
    assertSame(before, s.document());
    assertEquals(1, s.selection().anchorBlock);
    assertEquals(0, s.selection().block);
  }

  @Test
  public void suffixCommentsRemainAttachedAfterInsertion() {
    Session s = new Session(Document.empty());
    s.replace("Before after");
    s.select(0, 7, 12);
    s.addComment("Ada", "Keep suffix");
    String id = s.document().comments.get(0).id;
    s.select(0, 7, 7);
    s.insertTable(2, 2);
    s.selectComment(id);
    assertEquals("after", s.selectedText());
    assertEquals(2, s.selection().block);
  }

  @Test
  public void invalidAndNestedInsertionLeaveDocumentAndCaretUntouched() {
    Session s = new Session(Document.empty());
    s.replace("Keep");
    s.select(0, 0, 4);
    Document before = s.document();
    try {
      s.insertTable(0, 2);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    assertEquals("Keep", s.selectedText());
    s.insertTable(1, 1);
    before = s.document();
    try {
      s.insertTable(1, 1);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    assertTrue(s.inCell());
  }

  @Test
  public void leavingEitherInsertedTableRestoresTheSuffixParagraphCaret() {
    for (boolean quick : new boolean[] {false, true}) {
      Session s = new Session(Document.empty());
      s.replace("Before after");
      s.select(0, 7, 7);
      if (quick) s.insertQuickTable("matrix");
      else s.insertTable(2, 2);
      s.leaveCell();
      assertEquals(2, s.selection().block);
      assertEquals(0, s.selection().caret);
      assertEquals("after", s.document().paragraph(s.selection().block).text.toString());
      s.replace("Suffix ");
      assertEquals("Suffix after", s.document().paragraph(2).text.toString());
      s.undo();
      s.undo();
      assertEquals("Before after", s.document().plainText());
    }
  }
}
