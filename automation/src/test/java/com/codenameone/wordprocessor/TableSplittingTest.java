package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TableSplittingTest {
  private final Commands commands = new Commands();

  private Session fixture() {
    Session s = new Session(Document.empty());
    s.insertTable(4, 3);
    for (int r = 0; r < 4; r++) for (int c = 0; c < 3; c++) s.setCell(1, r, c, "r" + r + "c" + c);
    s.enterCell(1, 1, 1);
    return s;
  }

  private Table table(Session s) {
    return (Table) s.document().blocks.get(1);
  }

  private void unique(Document d) {
    Set<String> ids = new HashSet<String>();
    for (Block b : d.blocks) {
      assertTrue(ids.add(b.id()));
      if (b instanceof Table) {
        Table t = (Table) b;
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            for (Paragraph p : t.cell(r, c)) assertTrue(p.id(), ids.add(p.id()));
      }
    }
  }

  @Test
  public void ordinaryCellSplitsAndOtherCellsKeepTheirPhysicalWidths() {
    Session s = fixture();
    s.formatTable(1, table(s).format.column(0, 2).column(1, 3).column(2, 5));
    Document before = s.document();
    commands.execute(s, "table.split", Json.object("columns", 3));
    Table t = table(s);
    assertEquals(5, t.columns());
    assertEquals(1, s.cellColumn());
    assertEquals(1, s.cellRow());
    for (int r = 0; r < 4; r++) {
      assertEquals("r" + r + "c0", Table.cellText(t.cell(r, 0)));
      assertEquals("r" + r + "c1", Table.cellText(t.cell(r, 1)));
      assertEquals("r" + r + "c2", Table.cellText(t.cell(r, 4)));
      assertEquals(r == 1 ? 1 : 3, t.span(r, 1).columns);
      assertEquals("", Table.cellText(t.cell(r, 2)));
      assertEquals("", Table.cellText(t.cell(r, 3)));
    }
    assertEquals(.2, t.format.columnFraction(0), .00001);
    assertEquals(.5, t.format.columnFraction(4), .00001);
    for (int c = 1; c <= 3; c++) assertEquals(.1, t.format.columnFraction(c), .00001);
    unique(s.document());
    Document after = s.document();
    s.undo();
    assertSame(before, s.document());
    assertEquals(1, s.cellColumn());
    s.redo();
    assertSame(after, s.document());
  }

  @Test
  public void mergedCellSplitsOnEqualWidthsAndRetainsVerticalSpans() {
    Session s = fixture();
    s.mergeCells(1, 1, 0, 2, 2);
    s.enterCell(1, 1, 0);
    s.formatTable(1, table(s).format.column(0, 1).column(1, 3));
    String content = Table.cellText(table(s).cell(1, 0));
    commands.execute(s, "table.split", Json.object("columns", 2));
    Table t = table(s);
    assertEquals(4, t.columns());
    assertEquals(2, t.span(1, 0).columns);
    assertEquals(2, t.span(1, 0).rows);
    assertEquals(1, t.span(1, 2).columns);
    assertEquals(2, t.span(1, 2).rows);
    assertEquals(content, Table.cellText(t.cell(1, 0)));
    assertEquals("", Table.cellText(t.cell(1, 2)));
    assertEquals(.4, t.format.columnFraction(0) + t.format.columnFraction(1), .00001);
    assertEquals(.4, t.format.columnFraction(2), .00001);
    unique(s.document());
  }

  @Test
  public void existingGridLinesAreReusedAndStylesSurvive() {
    Session s = fixture();
    s.mergeCells(1, 1, 0, 1, 2);
    s.enterCell(1, 1, 0);
    TableFormat.Cell style = new TableFormat.Cell(0x123456, 0xabcdef, 2, 7, 8, "bottom");
    s.formatTable(1, table(s).format.cell(1, 0, style).headerRows(1).minHeight(2, 57));
    commands.execute(s, "table.split", Json.object("columns", 2));
    assertEquals(3, table(s).columns());
    assertEquals(1, table(s).span(1, 0).columns);
    assertSame(style, table(s).format.cell(1, 0));
    assertSame(style, table(s).format.cell(1, 1));
    assertEquals(1, table(s).format.headerRows);
    assertEquals(57, table(s).format.minHeight(2), 0);
  }

  @Test
  public void invalidSplitRollsBackCellSelectionAndHistory() {
    Session s = fixture();
    s.selectCells(1, 1, 1, 2, 2);
    Document before = s.document();
    CellSelection range = s.cellSelection();
    long rev = s.revision();
    for (int n : new int[] {0, -1, 99, 101}) {
      try {
        commands.execute(s, "table.split", Json.object("columns", n));
        fail();
      } catch (IllegalArgumentException expected) {
      }
      assertSame(before, s.document());
      assertEquals(range.key(), s.cellSelection().key());
      assertEquals(rev, s.revision());
    }
    try {
      commands.execute(s, "table.split", Json.object("block", 1));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
  }

  @Test
  public void defaultSplitAndLegacyUnmergeRemainAvailable() {
    Session s = fixture();
    commands.execute(s, "table.split");
    assertEquals(4, table(s).columns());
    s.undo();
    s.mergeCells(1, 1, 0, 2, 2);
    commands.execute(s, "table.split", Json.object("block", 1, "row", 1, "column", 0));
    assertTrue(table(s).merges.isEmpty());
  }

  @Test
  public void splitTablePreservesIdsStylesAndUndoRedoCellContext() {
    Session s = fixture();
    s.formatTable(1, table(s).format.column(0, 4).minHeight(2, 63).headerRows(2));
    s.enterCell(1, 2, 1);
    Document before = s.document();
    Table old = table(s);
    commands.execute(s, "table.splitTable");
    Table head = table(s), tail = (Table) s.document().blocks.get(3);
    assertEquals(2, head.rows());
    assertEquals(2, tail.rows());
    assertEquals(old.id(), head.id());
    assertFalse(old.id().equals(tail.id()));
    assertEquals(2, head.format.headerRows);
    assertEquals(0, tail.format.headerRows);
    assertEquals(63, tail.format.minHeight(0), 0);
    assertEquals(4, tail.format.column(0), 0);
    assertEquals(2, s.selection().block);
    assertFalse(s.inCell());
    for (int r = 0; r < 4; r++)
      for (int c = 0; c < 3; c++) assertSame(old.cell(r, c), (r < 2 ? head : tail).cell(r % 2, c));
    unique(s.document());
    Document after = s.document();
    s.undo();
    assertSame(before, s.document());
    assertEquals(2, s.cellRow());
    s.redo();
    assertSame(after, s.document());
  }

  @Test
  public void splitTableClipsCrossingMergeWithoutDuplicatingItsText() {
    Session s = fixture();
    s.mergeCells(1, 0, 0, 4, 2);
    s.enterCell(1, 2, 2);
    TableFormat.Cell style = new TableFormat.Cell(0x123456, 0, 1, 6, 4, "center");
    s.formatTable(1, table(s).format.cell(0, 0, style));
    String text = Table.cellText(table(s).cell(0, 0));
    commands.execute(s, "table.splitTable");
    Table a = table(s), b = (Table) s.document().blocks.get(3);
    assertEquals(2, a.span(0, 0).rows);
    assertEquals(2, b.span(0, 0).rows);
    assertEquals(2, b.span(0, 0).columns);
    assertEquals(text, Table.cellText(a.cell(0, 0)));
    assertEquals("", Table.cellText(b.cell(0, 0)));
    assertSame(style, b.format.cell(0, 0));
    unique(s.document());
  }

  @Test
  public void firstRowSplitIsRejectedWithoutLeavingTheCell() {
    Session s = fixture();
    s.enterCell(1, 0, 1);
    Document before = s.document();
    try {
      commands.execute(s, "table.splitTable");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    assertEquals(0, s.cellRow());
    assertEquals(1, s.cellColumn());
  }

  @Test
  public void commentsFollowOriginalParagraphsAcrossBothSplits() {
    Session s = fixture();
    s.enterCell(1, 2, 1);
    s.select(0, 0, 4);
    s.addComment("Ada", "Keep anchor");
    String id = s.document().comments.get(0).id;
    commands.execute(s, "table.split", Json.object("columns", 3));
    s.selectComment(id);
    assertEquals("r2c1", s.selectedText());
    commands.execute(s, "table.splitTable");
    s.selectComment(id);
    assertEquals(3, s.cellBlock());
    assertEquals(0, s.cellRow());
    assertEquals("r2c1", s.selectedText());
  }
  @Test public void newBlankPartsDoNotDuplicateTheOriginalFormattingRevision() {
    Session s=fixture();s.trackChanges(true);s.trackFormatting(true);s.paragraph("align","right");
    assertNotNull(table(s).cell(1,1).get(0).formatRevision);
    commands.execute(s,"table.split",Json.object("columns",2));
    assertNotNull(table(s).cell(1,1).get(0).formatRevision);
    Paragraph blank=table(s).cell(1,2).get(0);assertEquals("right",blank.align);assertNull(blank.formatRevision);assertNull(blank.breakRevision);
  }
  @Test public void togglingTrackChangesKeepsTableRangeAndUndoContext() {
    Session s=fixture();s.selectCells(1,1,0,2,1);String range=s.cellSelection().key();Document before=s.document();
    s.trackChanges(true);assertEquals(range,s.cellSelection().key());assertTrue(s.document().trackChanges);assertTrue(s.editingDocument().trackChanges);
    assertEquals(2,s.cellRow());s.undo();assertSame(before,s.document());assertEquals(range,s.cellSelection().key());assertEquals(2,s.cellRow());
  }
}
