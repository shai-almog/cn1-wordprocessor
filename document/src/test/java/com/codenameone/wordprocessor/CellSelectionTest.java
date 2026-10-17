package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class CellSelectionTest {
  private Session grid() {
    Session s = new Session(Document.empty());
    s.insertTable(3, 3);
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) s.setCell(1, r, c, "r" + r + "c" + c);
    return s;
  }

  private Table table(Session s) {
    return (Table) s.document().blocks.get(1);
  }

  private String text(Session s, int r, int c) {
    return table(s).cell(r, c).get(0).text.plainText();
  }

  @Test
  public void rectangularSelectionDoesNotChangeContentAndKeepsIntermediateCellsOut() {
    Session s = grid();
    Document original = s.document();
    long revision = s.revision();
    s.selectCells(1, 2, 1, 0, 1);
    assertSame(original, s.document());
    assertEquals(revision, s.revision());
    assertEquals("r0c1\nr1c1\nr2c1", s.selectedText());
    s.format("bold", "true");
    for (int r = 0; r < 3; r++)
      for (int c = 0; c < 3; c++)
        assertEquals(c == 1, table(s).cell(r, c).get(0).text.styleAt(0).bold);
    assertNotNull(s.cellSelection());
    s.paragraph("align", "center");
    for (int r = 0; r < 3; r++)
      for (int c = 0; c < 3; c++)
        assertEquals(c == 1 ? "center" : "left", table(s).cell(r, c).get(0).align);
    s.undo();
    s.undo();
    assertSame(original, s.document());
    assertEquals("r0c1\nr1c1\nr2c1", s.selectedText());
    s.redo();
    assertTrue(table(s).cell(1, 1).get(0).text.styleAt(0).bold);
  }

  @Test
  public void deletingAndTypingPreserveGridAndUndoSelection() {
    Session s = grid();
    Document original = s.document();
    s.selectCells(1, 0, 1, 2, 1);
    s.replace("new");
    assertEquals("new", text(s, 0, 1));
    assertEquals("", text(s, 1, 1));
    assertEquals("", text(s, 2, 1));
    assertEquals("r1c0", text(s, 1, 0));
    assertNull(s.cellSelection());
    s.undo();
    assertSame(original, s.document());
    assertNotNull(s.cellSelection());
    s.deleteBackward();
    assertEquals("", text(s, 0, 1));
    s.undo();
    assertSame(original, s.document());
    s.deleteForward();
    assertEquals("", text(s, 2, 1));
    assertEquals(3, table(s).columns());
  }

  @Test
  public void selectedEmptyFocusStillClearsAllPickedCells() {
    Session s = grid();
    s.setCell(1, 2, 1, "");
    s.selectCells(1, 0, 1, 2, 1);
    assertTrue(s.selection().collapsed());
    TextNavigation.deleteWord(s, true);
    assertEquals("", text(s, 0, 1));
    assertEquals("", text(s, 1, 1));
    assertEquals("r2c0", text(s, 2, 0));
  }

  @Test
  public void mergedSpansExpandAndCopyRetainsGeometryAndFormatting() {
    Session s = grid();
    s.mergeCells(1, 0, 0, 2, 2);
    s.selectCells(1, 1, 1, 2, 2);
    CellSelection x = s.cellSelection();
    assertEquals(0, x.firstRow);
    assertEquals(0, x.firstColumn);
    assertEquals(2, x.lastRow);
    Document copy = s.copySelection();
    Table t = (Table) copy.blocks.get(0);
    assertEquals(3, t.rows());
    assertEquals(2, t.span(1, 1).rows);
    assertEquals(2, t.span(1, 1).columns);
    s.format("italic", "true");
    assertTrue(table(s).cell(0, 0).get(0).text.styleAt(0).italic);
  }

  @Test
  public void richRectanglePasteAndTsvAreAtomicAndUndoable() {
    Session s = grid();
    s.selectCells(1, 0, 0, 1, 1);
    s.format("bold", "true");
    Document copy = s.copySelection();
    s.selectCells(1, 1, 1, 2, 2);
    Document before = s.document();
    s.paste(copy);
    assertEquals("r0c0", text(s, 1, 1));
    assertEquals("r1c1", text(s, 2, 2));
    assertTrue(table(s).cell(2, 2).get(0).text.styleAt(0).bold);
    s.undo();
    assertSame(before, s.document());
    assertNotNull(s.cellSelection());
    s.pasteText("A\tB\r\nC\tD\r\n");
    assertEquals("A", text(s, 1, 1));
    assertEquals("D", text(s, 2, 2));
    assertEquals("r0c2", text(s, 0, 2));
    Document applied = s.document();
    CellSelection selection = s.cellSelection();
    long rev = s.revision();
    try {
      s.pasteText("1\t2\t3");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(applied, s.document());
    assertSame(selection, s.cellSelection());
    assertEquals(rev, s.revision());
    s.undo();
    assertSame(before, s.document());
  }

  @Test
  public void compositionCancelCommitAndUndoPreserveCellContext() {
    Session s = grid();
    Document original = s.document();
    s.selectCells(1, 0, 1, 2, 1);
    s.compose("漢", 1);
    assertEquals("漢", text(s, 0, 1));
    assertEquals("", text(s, 1, 1));
    s.cancelComposition();
    assertSame(original, s.document());
    assertNotNull(s.cellSelection());
    s.compose("あ", 1);
    s.compose("漢字", 2);
    s.finishComposition();
    assertEquals("漢字", text(s, 0, 1));
    s.undo();
    assertSame(original, s.document());
    assertNotNull(s.cellSelection());
  }

  @Test
  public void trackedClearAndFormattingCanBeAcceptedOrRejected() {
    Session s = grid();
    Document original = s.document();
    s.trackChanges(true);
    s.selectCells(1, 0, 1, 2, 1);
    s.replace("");
    Document rejected = Revisions.resolve(s.document(), null, false);
    assertEquals(original.plainText(), rejected.plainText());
    Document accepted = Revisions.resolve(s.document(), null, true);
    Table t = (Table) accepted.blocks.get(1);
    assertEquals("", t.cell(1, 1).get(0).text.plainText());
    assertEquals("r1c0", t.cell(1, 0).get(0).text.plainText());
  }

  @Test
  public void invalidSelectionAndFailedFormattingPreserveStateAndForksRetainRectangles() {
    Session s = grid();
    s.selectCells(1, 0, 0, 2, 1);
    CellSelection x = s.cellSelection();
    Document d = s.document();
    try {
      s.selectCells(1, 0, 0, 3, 0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(x, s.cellSelection());
    assertSame(d, s.document());
    try {
      s.format("size", "invalid");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(x, s.cellSelection());
    assertSame(d, s.document());
    Session fork = s.fork();
    fork.format("bold", "true");
    s.applyPreview(fork, s.revision());
    assertNotNull(s.cellSelection());
    s.undo();
    assertSame(d, s.document());
    assertSame(x, s.cellSelection());
    s.select(0, 0, 0);
    assertNull(s.cellSelection());
  }
}
