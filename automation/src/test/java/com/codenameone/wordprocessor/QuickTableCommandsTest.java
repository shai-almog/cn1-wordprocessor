package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class QuickTableCommandsTest {
  final Commands commands = new Commands();

  @Test
  public void templatesHaveEditableContentAndRepeatingHeaders() {
    String[] names = {"tabular", "matrix", "calendar"};
    int[] rows = {7, 4, 7}, columns = {2, 4, 7};
    for (int i = 0; i < names.length; i++) {
      var s = new Session(Document.empty());
      commands.execute(s, "table.quick", Json.object("kind", names[i]));
      var table = (Table) s.document().blocks.get(1);
      assertEquals(rows[i], table.rows());
      assertEquals(columns[i], table.columns());
      assertEquals(1, table.format.headerRows);
      assertEquals(1, s.cellBlock());
      assertEquals(0, s.cellRow());
      assertEquals(0, s.cellColumn());
      if (i == 0) {
        assertEquals("Item", Table.cellText(table.cell(0, 0)));
        assertEquals("2", Table.cellText(table.cell(6, 1)));
      }
      if (i == 1) {
        assertEquals("Column C", Table.cellText(table.cell(0, 3)));
        assertEquals("9", Table.cellText(table.cell(3, 3)));
      }
      if (i == 2) {
        assertEquals("M", Table.cellText(table.cell(0, 0)));
        assertEquals("1", Table.cellText(table.cell(1, 2)));
        assertEquals("31", Table.cellText(table.cell(5, 4)));
        assertEquals("", Table.cellText(table.cell(6, 6)));
      }
      s.replace("Edited ");
      assertTrue(
          Table.cellText(((Table) s.document().blocks.get(1)).cell(0, 0)).startsWith("Edited "));
    }
  }

  @Test
  public void replacesSelectionBetweenStyledPrefixAndSuffixWithOneUndoStep() {
    var s = new Session(Document.empty());
    s.replace("Before TARGET after");
    s.select(0, 0, 19);
    s.format("bold", "true");
    s.select(0, 7, 13);
    var before = s.document();
    commands.execute(s, "table.quick");
    assertEquals("Before ", s.document().paragraph(0).text.toString());
    assertEquals(" after", s.document().paragraph(2).text.toString());
    assertTrue(s.document().paragraph(2).text.styleAt(0).bold);
    var inserted = s.document();
    s.undo();
    assertSame(before, s.document());
    assertEquals("TARGET", s.selectedText());
    s.redo();
    assertSame(inserted, s.document());
    assertEquals(1, s.cellBlock());
  }

  @Test
  public void insertionReanchorsCommentsToTheSuffix() {
    var s = new Session(Document.empty());
    s.replace("Before after");
    s.select(0, 7, 12);
    s.addComment("Ada", "Keep this");
    var id = s.document().comments.get(0).id;
    s.select(0, 7, 7);
    commands.execute(s, "table.quick", Json.object("kind", "matrix"));
    s.selectComment(id);
    assertEquals("after", s.selectedText());
    assertEquals(2, s.selection().block);
  }

  @Test
  public void repeatedInsertionsHaveUniqueStableIds() {
    var s = new Session(Document.empty());
    commands.execute(s, "table.quick");
    s.leaveCell();
    s.select(2, 0, 0);
    commands.execute(s, "table.quick", Json.object("kind", "calendar"));
    var ids = new HashSet<String>();
    for (Block block : s.document().blocks) {
      assertTrue(ids.add(block.id()));
      if (block instanceof Table t) {
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            for (Paragraph p : t.cell(r, c)) assertTrue(ids.add(p.id));
      }
    }
  }

  @Test
  public void invalidKindAndNestedInsertionFailAtomically() {
    var s = new Session(Document.empty());
    s.replace("keep me");
    s.select(0, 0, 7);
    var before = s.document();
    try {
      commands.execute(s, "table.quick", Json.object("kind", "unknown"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    assertEquals("keep me", s.selectedText());
    commands.execute(s, "table.quick");
    before = s.document();
    try {
      commands.execute(s, "table.quick");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    assertTrue(s.inCell());
  }

  @Test
  public void trackedReplacementRetainsDeletedTextAndUndo() {
    var s = new Session(Document.empty());
    s.replace("Keep remove tail");
    s.trackChanges(true);
    s.select(0, 5, 11);
    var before = s.document();
    commands.execute(s, "table.quick");
    var deleted = s.document().paragraph(2);
    assertTrue(deleted.text.toString().contains("remove"));
    assertNotNull(deleted.text.styleAt(0).revision);
    s.undo();
    assertSame(before, s.document());
    assertEquals("remove", s.selectedText());
  }
}
