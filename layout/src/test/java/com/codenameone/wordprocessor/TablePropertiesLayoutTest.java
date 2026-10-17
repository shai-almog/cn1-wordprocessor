package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TablePropertiesLayoutTest {
  private LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String text, TextStyle style) {
            return text.length() * 6;
          }

          public float height(TextStyle style) {
            return 12;
          }
        });
  }

  @Test
  public void columnsPaddingAndVerticalAlignmentSetRealGeometry() {
    Session s = new Session(Document.empty());
    s.insertTable(1, 2);
    s.setCell(1, 0, 0, "Left");
    s.setCell(1, 0, 1, "Bottom");
    Table t = (Table) s.document().blocks.get(1);
    s.formatTable(
        1,
        t.format
            .columnFraction(0, .25f)
            .minHeight(0, 100)
            .cell(0, 1, new TableFormat.Cell(0xff0000, 0, 0, 10, 7, "bottom")));
    LayoutEngine.Page p =
        engine().layout(s.document().page(new PageSpec(240, 300, 20, 20, 20, 20)), 0).pages.get(0);
    assertEquals(50, p.boxes.get(0).width, .01);
    assertEquals(150, p.boxes.get(1).width, .01);
    assertEquals(100, p.boxes.get(0).height, .01);
    LayoutEngine.Line bottom = null;
    for (LayoutEngine.Line l : p.lines) if (l.block == 1 && l.column == 1) bottom = l;
    assertNotNull(bottom);
    assertEquals(80, bottom.x, .01);
    assertTrue(bottom.y > p.boxes.get(1).y + 60);
    assertEquals(0xff0000, p.boxes.get(1).format.background);
  }

  @Test
  public void repeatedHeadersDoNotDropOrDuplicateBodyRows() {
    Session s = new Session(Document.empty());
    s.insertTable(20, 2);
    for (int r = 0; r < 20; r++) for (int c = 0; c < 2; c++) s.setCell(1, r, c, "r" + r + "c" + c);
    Table t = (Table) s.document().blocks.get(1);
    s.formatTable(1, t.format.headerRows(1));
    LayoutEngine.Result result =
        engine().layout(s.document().page(new PageSpec(240, 180, 20, 20, 20, 20)), 0);
    assertTrue(result.pages.size() > 2);
    Map<String, Integer> seen = new HashMap<String, Integer>();
    int tablePages = 0;
    for (LayoutEngine.Page page : result.pages) {
      boolean hasTable = false, hasHeader = false;
      for (LayoutEngine.Line line : page.lines)
        if (line.block == 1) {
          hasTable = true;
          hasHeader |= line.row == 0;
          String key = line.row + ":" + line.column;
          seen.put(key, seen.containsKey(key) ? seen.get(key) + 1 : 1);
          assertTrue(line.y + line.height <= 160.01);
        }
      if (hasTable) {
        tablePages++;
        assertTrue("Every table page repeats header", hasHeader);
      }
    }
    for (int r = 1; r < 20; r++)
      for (int c = 0; c < 2; c++)
        assertEquals("Row " + r, Integer.valueOf(1), seen.get(r + ":" + c));
    assertEquals(Integer.valueOf(tablePages), seen.get("0:0"));
  }
}
