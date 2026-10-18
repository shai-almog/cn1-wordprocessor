package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TableRowsLayoutTest {
  private final LayoutEngine engine =
      new LayoutEngine(
          new LayoutEngine.Metrics() {
            public float width(String t, TextStyle s) {
              return t.length() * 6;
            }

            public float height(TextStyle s) {
              return 12;
            }
          });

  private Session fixture() {
    var s = new Session(Document.empty());
    s.insertTable(2, 1);
    s.setCell(1, 0, 0, "one\ntwo\nthree\nfour\nfive\nsix");
    s.setCell(1, 1, 0, "next row");
    return s;
  }

  private Table table(Session s) {
    return (Table) s.document().blocks.get(1);
  }

  private LayoutEngine.Result layout(Session s) {
    return engine.layout(s.document().page(new PageSpec(240, 180, 20, 20, 20, 20)), 0);
  }

  @Test
  public void exactHeightClipsContentAndDoesNotMoveFollowingRows() {
    var s = fixture();
    s.formatTable(1, table(s).format.row(0, new TableFormat.Row(18, "exact", true)));
    var p = layout(s).pages.get(0);
    LayoutEngine.Box a = p.boxes.get(0), b = p.boxes.get(1);
    assertEquals(18, a.height, .001);
    assertEquals(a.y + 18, b.y, .001);
    int lines = 0;
    boolean clipped = false;
    for (LayoutEngine.Line l : p.lines)
      if (l.block == 1 && l.row == 0) {
        lines++;
        clipped |= l.clipped;
        assertTrue(l.y + l.height <= a.y + a.height + .001);
      }
    assertTrue(lines > 0 && lines < 6);
    assertTrue(clipped);
  }

  @Test
  public void tightLineSpacingStillClipsGlyphsToTheCellBoundary() {
    var s = fixture();
    var t = table(s);
    var cell =
        new Sequence<Paragraph>().append(t.cell(0, 0).get(0).with("spacing", "0.5"));
    t = t.setCell(0, 0, cell).format(t.format.row(0, new TableFormat.Row(12, "exact", true)));
    var d = s.document().blocks(s.document().blocks.set(1, t), s.document().nextId);
    var p = engine.layout(d, 0).pages.get(0);
    var box = p.boxes.get(0);
    for (LayoutEngine.Line line : p.lines)
      if (line.block == 1 && line.row == 0) {
        assertTrue(line.clipped);
        assertEquals(box.y + box.height, line.y + line.clipHeight, .001);
        assertTrue(line.clipHeight > line.height);
      }
  }

  @Test
  public void minimumHeightGrowsToFitAndAutomaticIgnoresPreferredHeight() {
    var s = fixture();
    s.formatTable(1, table(s).format.row(0, new TableFormat.Row(18, "atLeast", true)));
    float grown = layout(s).pages.get(0).boxes.get(0).height;
    assertTrue(grown > 18);
    s.formatTable(1, table(s).format.row(0, new TableFormat.Row(999, "auto", true)));
    assertEquals(grown, layout(s).pages.get(0).boxes.get(0).height, .001);
  }

  @Test
  public void breakPolicyChangesPaginationWithoutLosingText() {
    var s = fixture();
    s.leaveCell();
    s.selectRange(0, 0, 0, 0);
    s.replace("lead\nlead\nlead\nlead");
    int block = 4;
    var t = (Table) s.document().blocks.get(block);
    s.formatTable(block, t.format.row(0, new TableFormat.Row(0, "auto", true)));
    var split = layout(s);
    assertTrue(split.pages.get(0).boxes.size() > 0);
    s.formatTable(block, t.format.row(0, new TableFormat.Row(0, "auto", false)));
    var kept = layout(s);
    assertTrue(kept.pages.get(0).boxes.isEmpty());
    for (LayoutEngine.Result result : Arrays.asList(split, kept)) {
      int lines = 0;
      for (LayoutEngine.Page p : result.pages)
        for (LayoutEngine.Line l : p.lines)
          if (l.block == block && l.row == 0) {
            lines++;
            assertTrue(l.y + l.height <= 160.01);
          }
      assertEquals(6, lines);
    }
  }

  @Test
  public void oversizedNonBreakingRowsStillProgressWithRepeatedHeaders() {
    var s = fixture();
    s.setCell(1, 0, 0, "header");
    var text = new StringBuilder();
    for (int i = 0; i < 50; i++) text.append(i == 0 ? "" : "\n").append("line");
    s.setCell(1, 1, 0, text.toString());
    s.formatTable(1, table(s).format.headerRows(1).row(1, new TableFormat.Row(0, "auto", false)));
    var result = layout(s);
    assertTrue(result.pages.size() > 2 && result.pages.size() < 30);
    int lines = 0;
    for (LayoutEngine.Page p : result.pages)
      for (LayoutEngine.Line l : p.lines) if (l.block == 1 && l.row == 1) lines++;
    assertEquals(50, lines);
  }

  @Test
  public void mergedContentsOnlyGrowFlexibleRows() {
    var s = fixture();
    s.mergeCells(1, 0, 0, 2, 1);
    var f =
        table(s)
            .format
            .row(0, new TableFormat.Row(18, "exact", true))
            .row(1, new TableFormat.Row(18, "exact", true));
    s.formatTable(1, f);
    assertEquals(36, layout(s).pages.get(0).boxes.get(0).height, .001);
    s.formatTable(1, f.row(1, new TableFormat.Row(18, "atLeast", true)));
    assertTrue(layout(s).pages.get(0).boxes.get(0).height > 36);
  }
}
