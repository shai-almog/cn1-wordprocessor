package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class TableSizingLayoutTest {
  private final LayoutEngine engine =
      new LayoutEngine(
          new LayoutEngine.Metrics() {
            public float width(String text, TextStyle style) {
              return text.length() * 6;
            }

            public float height(TextStyle style) {
              return 12;
            }
          });

  private Session fixture() {
    Session s = new Session(Document.empty().page(new PageSpec(240, 300, 20, 20, 20, 20)));
    s.insertTable(2, 2);
    s.setCell(1, 0, 0, "aa");
    s.setCell(1, 0, 1, "bbbb");
    return s;
  }

  private Table table(Session s) {
    return (Table) s.document().blocks.get(1);
  }

  private void size(
      Session s, String unit, float value, String align, float indent, boolean fixed) {
    s.formatTable(
        1, table(s).format.sizing(new TableFormat.Sizing(unit, value, align, indent, fixed)));
  }

  @Test
  public void preferredWidthsAndAlignmentDriveCellAndTextGeometry() {
    Session s = fixture();
    for (String align : new String[] {"left", "center", "right"}) {
      size(s, "percent", 50, align, 12, true);
      LayoutEngine.Page page = engine.layout(s.document(), s.revision()).pages.get(0);
      float expected = "left".equals(align) ? 32 : "center".equals(align) ? 70 : 120;
      assertEquals(expected, page.boxes.get(0).x, .001);
      assertEquals(50, page.boxes.get(0).width, .001);
      for (LayoutEngine.Line line : page.lines)
        if (line.block == 1 && line.column == 0) assertEquals(expected + 6, line.x, .001);
    }
    size(s, "points", 80, "left", -10, true);
    LayoutEngine.Box box = engine.layout(s.document(), s.revision()).pages.get(0).boxes.get(0);
    assertEquals(10, box.x, .001);
    assertEquals(40, box.width, .001);
  }

  @Test
  public void contentsFitsWordsAndReactsToLaterCellEdits() {
    Session s = fixture();
    size(s, "auto", 0, "left", 0, false);
    float[] widths = engine.tableWidths(table(s), 200);
    assertArrayEquals(new float[] {24, 36}, widths, .001f);
    s.setCell(1, 1, 0, "abcdefghij");
    assertArrayEquals(new float[] {72, 36}, engine.tableWidths(table(s), 200), .001f);
  }

  @Test
  public void mergedOwnersAndWrappedContentShareAvailableWidth() {
    Session s = fixture();
    s.mergeCells(1, 1, 0, 1, 1);
    s.setCell(1, 1, 0, "a b c d e f g h i j");
    size(s, "auto", 0, "left", 20, false);
    float[] widths = engine.tableWidths(table(s), 100);
    assertEquals(80, widths[0] + widths[1], .001);
    assertTrue(widths[0] > 0 && widths[1] > 0);
    s.setCell(1, 0, 0, "aaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    widths = engine.tableWidths(table(s), 100);
    assertEquals(80, widths[0] + widths[1], .001);
  }

  @Test
  public void windowAutofitRedistributesButFixedWidthsDoNot() {
    Session s = fixture();
    s.setCell(1, 0, 0, "abcdefghij");
    size(s, "percent", 100, "left", 0, false);
    float[] widths = engine.tableWidths(table(s), 100);
    assertTrue(widths[0] > widths[1]);
    assertEquals(100, widths[0] + widths[1], .001);
    size(s, "points", 100, "left", 0, true);
    assertArrayEquals(new float[] {50, 50}, engine.tableWidths(table(s), 200), .001f);
  }

  @Test
  public void phoneReflowScalesAbsoluteGeometryWithoutChangingOriginal() {
    Session s = fixture();
    size(s, "points", 100, "left", 20, true);
    Document original = s.document();
    Table adapted = (Table) Reflow.document(original, 124).blocks.get(1);
    assertEquals(50, adapted.format.sizing.width, .001);
    assertEquals(10, adapted.format.sizing.indent, .001);
    assertEquals(100, table(s).format.sizing.width, .001);
    assertSame(original, s.document());
  }
}
