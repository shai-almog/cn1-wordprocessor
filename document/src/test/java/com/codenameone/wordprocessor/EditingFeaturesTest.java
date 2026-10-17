package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class EditingFeaturesTest {
  @Test
  public void changeCasePreservesFormattingParagraphsSelectionAndUndo() {
    Session s = new Session(Document.empty());
    s.replace("Start mixed text\nSecond END");
    s.select(0, 6, 11);
    s.format("bold", "true");
    s.select(0, 7, 10);
    s.addComment("Reviewer", "Keep this range");
    s.selectRange(0, 6, 1, 6);
    Document before = s.document();
    s.changeCase(true);
    assertEquals("Start MIXED TEXT\nSECOND END", s.document().plainText());
    assertTrue(s.document().paragraph(0).text.styleAt(6).bold);
    assertFalse(s.document().paragraph(0).text.styleAt(12).bold);
    assertEquals(6, s.selection().start());
    assertEquals(6, s.selection().end());
    assertEquals(7, s.document().comments.get(0).start);
    assertEquals(10, s.document().comments.get(0).end);
    s.undo();
    assertSame(before, s.document());
    s.redo();
    assertEquals("Start MIXED TEXT\nSECOND END", s.document().plainText());
  }

  @Test
  public void tableStructureSortAndUndoKeepStableCellContent() {
    Session s = new Session(Document.empty());
    s.insertTable(3, 2);
    s.setCell(1, 0, 0, "Name");
    s.setCell(1, 0, 1, "Value");
    s.setCell(1, 1, 0, "B");
    s.setCell(1, 1, 1, "20");
    s.setCell(1, 2, 0, "A");
    s.setCell(1, 2, 1, "3");
    Document original = s.document();
    s.sortTable(1, 1, false, true, true);
    Table t = (Table) s.document().blocks.get(1);
    assertEquals("A", Table.cellText(t.cell(1, 0)));
    assertEquals("Name", Table.cellText(t.cell(0, 0)));
    s.undo();
    assertSame(original, s.document());
    s.redo();
    assertEquals("A", Table.cellText(((Table) s.document().blocks.get(1)).cell(1, 0)));
    s.tableStructure(1, "insertColumn", 1);
    s.tableStructure(1, "insertRow", 1);
    t = (Table) s.document().blocks.get(1);
    assertEquals(4, t.rows());
    assertEquals(3, t.columns());
    assertEquals("", Table.cellText(t.cell(1, 1)));
    s.tableStructure(1, "deleteColumn", 1);
    s.tableStructure(1, "deleteRow", 1);
    assertEquals(3, ((Table) s.document().blocks.get(1)).rows());
    Document before = s.document();
    s.tableToText(1, "\t");
    assertEquals("Name\tValue", s.document().paragraph(1).text.toString());
    assertEquals("A\t3", s.document().paragraph(2).text.toString());
    s.undo();
    assertSame(before, s.document());
  }

  @Test
  public void invalidNumericSortRollsBackWithoutLosingCells() {
    Session s = new Session(Document.empty());
    s.insertTable(2, 1);
    s.setCell(1, 0, 0, "2");
    s.setCell(1, 1, 0, "invalid");
    Document before = s.document();
    try {
      s.sortTable(1, 0, false, false, true);
      fail();
    } catch (NumberFormatException expected) {
    }
    assertSame(before, s.document());
  }

  @Test
  public void richPastePreservesRunsAndAllocatesFreshIds() {
    Session s = new Session(Document.empty());
    s.replace("Bold and normal\nSecond");
    s.select(0, 0, 4);
    s.format("bold", "true");
    s.selectRange(0, 0, 1, 6);
    Document fragment = s.copySelection();
    s.select(1, 6, 6);
    Document before = s.document();
    s.paste(fragment);
    assertEquals("Bold and normal\nSecondBold and normal\nSecond", s.document().plainText());
    assertTrue(s.document().paragraph(1).text.styleAt(7).bold);
    assertFalse(s.document().paragraph(1).text.styleAt(0).bold);
    Set<String> ids = new HashSet<String>();
    for (Block b : s.document().blocks) assertTrue(ids.add(b.id()));
    s.undo();
    assertSame(before, s.document());
    s.redo();
    assertEquals(3, s.document().blocks.size());
  }

  @Test
  public void richPasteIncludesTablesAndDoesNotAliasIds() {
    Session s = new Session(Document.empty());
    s.replace("before");
    s.insertTable(1, 1);
    s.setCell(1, 0, 0, "cell");
    s.select(2, 0, 0);
    s.replace("after");
    s.selectRange(0, 0, 2, 5);
    Document copy = s.copySelection();
    s.select(2, 5, 5);
    s.paste(copy);
    Set<String> ids = new HashSet<String>();
    int tables = 0;
    for (Block b : s.document().blocks) {
      assertTrue(ids.add(b.id()));
      if (b instanceof Table) {
        tables++;
        Table t = (Table) b;
        assertEquals("cell", Table.cellText(t.cell(0, 0)));
        assertTrue(ids.add(t.cell(0, 0).get(0).id));
      }
    }
    assertEquals(2, tables);
  }

  @Test
  public void emptyStyleSurvivesEnterAndListExitIsUndoable() {
    Session s = new Session(Document.empty());
    s.paragraph("style", "Heading1");
    s.format("size", "26");
    s.format("bold", "true");
    assertEquals(26, s.activeStyle().size, .01);
    s.replace("Title");
    assertTrue(s.document().paragraph(0).text.styleAt(0).bold);
    s.enterParagraph();
    assertEquals("Normal", s.document().paragraph(1).style);
    assertFalse(s.activeStyle().bold);
    s.paragraph("list", "number");
    s.replace("First");
    s.enterParagraph();
    assertEquals("number", s.document().paragraph(2).list);
    s.enterParagraph();
    assertEquals("", s.document().paragraph(2).list);
    s.undo();
    assertEquals("number", s.document().paragraph(2).list);
  }
}
