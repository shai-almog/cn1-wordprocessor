package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class CellEditingTest {
  private Session sample() {
    Session s = new Session(Document.empty());
    s.replace("Body");
    s.insertTable(2, 2);
    s.setCell(1, 0, 0, "alpha beta");
    return s;
  }

  private Paragraph cell(Session s, int row, int col, int paragraph) {
    return ((Table) s.document().blocks.get(1)).cell(row, col).get(paragraph);
  }

  @Test
  public void richEditsKeepDocumentIdentityUntilChangedAndShareGlobalUndo() {
    Session s = sample();
    Document original = s.document();
    s.enterCell(1, 0, 0);
    assertSame(original, s.document());
    s.select(0, 6, 10);
    s.format("bold", "true");
    assertEquals("Body", s.document().paragraph(0).text.toString());
    assertFalse(cell(s, 0, 0, 0).text.styleAt(0).bold);
    assertTrue(cell(s, 0, 0, 0).text.styleAt(6).bold);
    s.leaveCell();
    s.select(0, 4, 4);
    s.replace("!");
    s.undo();
    assertEquals("Body", s.document().paragraph(0).text.toString());
    s.undo();
    assertTrue(s.inCell());
    assertSame(original, s.document());
    s.redo();
    assertTrue(cell(s, 0, 0, 0).text.styleAt(6).bold);
    s.redo();
    assertFalse(s.inCell());
    assertEquals("Body!", s.document().paragraph(0).text.toString());
  }

  @Test
  public void paragraphSplitClipboardAndCompositionPreserveRichCellContent() {
    Session s = sample();
    s.enterCell(1, 0, 0);
    s.select(0, 6, 6);
    s.enterParagraph();
    assertEquals("alpha ", cell(s, 0, 0, 0).text.toString());
    assertEquals("beta", cell(s, 0, 0, 1).text.toString());
    s.select(1, 0, 4);
    s.format("italic", "true");
    Document fragment = s.copySelection();
    s.nextCell(false);
    s.paste(fragment);
    assertTrue(cell(s, 0, 1, 0).text.styleAt(0).italic);
    s.select(0, 4, 4);
    s.compose("中", 1);
    assertEquals("beta中", cell(s, 0, 1, 0).text.toString());
    s.cancelComposition();
    assertEquals("beta", cell(s, 0, 1, 0).text.toString());
    s.compose("文", 1);
    s.finishComposition();
    s.undo();
    assertEquals("beta", cell(s, 0, 1, 0).text.toString());
    java.util.Set<String> ids = new java.util.HashSet<String>();
    Table t = (Table) s.document().blocks.get(1);
    for (int r = 0; r < t.rows(); r++)
      for (int c = 0; c < t.columns(); c++)
        for (Paragraph p : t.cell(r, c)) assertTrue(ids.add(p.id));
  }

  @Test
  public void tabSkipsMergedFollowersAndAppendsUndoableRow() {
    Session s = sample();
    s.mergeCells(1, 0, 0, 1, 2);
    s.enterCell(1, 0, 1);
    assertEquals(0, s.cellColumn());
    s.nextCell(false);
    assertEquals(1, s.cellRow());
    s.nextCell(false);
    assertEquals(1, s.cellColumn());
    s.nextCell(false);
    assertEquals(2, s.cellRow());
    assertEquals(3, ((Table) s.document().blocks.get(1)).rows());
    s.undo();
    assertEquals(2, ((Table) s.document().blocks.get(1)).rows());
    s.redo();
    assertEquals(3, ((Table) s.document().blocks.get(1)).rows());
  }

  @Test
  public void failedTransactionAndPreviewRestoreCellScopeAndCanonicalRoot() {
    Session s = sample();
    s.enterCell(1, 0, 0);
    Document original = s.document();
    try {
      s.transact(
          x -> {
            x.replace("lost");
            x.leaveCell();
            throw new IllegalArgumentException();
          });
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertTrue(s.inCell());
    assertSame(original, s.document());
    Session draft = s.fork();
    draft.select(0, 0, 5);
    draft.replace("changed");
    assertSame(original, s.document());
    s.applyPreview(draft, s.revision());
    assertEquals("changed beta", cell(s, 0, 0, 0).text.toString());
    s.undo();
    assertSame(original, s.document());
  }
}
