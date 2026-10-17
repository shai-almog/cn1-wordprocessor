package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class LineSpacingLayoutTest {
  private final LayoutEngine engine =
      new LayoutEngine(
          new LayoutEngine.Metrics() {
            public float width(String text, TextStyle style) {
              return text.length() * 6;
            }

            public float height(TextStyle style) {
              return style.size;
            }
          });

  private Paragraph paragraph(String rule, float value) {
    return new Paragraph("p1", RichText.of("first\nsecond", TextStyle.DEFAULT.with("size", "24")))
        .with("lineRule", rule)
        .with("spacing", "" + value)
        .with("after", "0");
  }

  private LayoutEngine.Result layout(Paragraph p) {
    Document d = Document.empty();
    return engine.layout(d.blocks(new Sequence<Block>().append(p), 2), 0);
  }

  @Test
  public void exactHeightClipsAndPositionsTextAtBottomOfShortLine() {
    LayoutEngine.Page page = layout(paragraph("exact", 10)).pages.get(0);
    assertEquals(2, page.lines.size());
    for (LayoutEngine.Line line : page.lines) {
      assertEquals(10, line.height, 0);
      assertEquals(10, line.clipHeight, 0);
      assertTrue(line.clipped);
      assertEquals(-14, line.spans.get(0).top, 0);
    }
    assertEquals(10, page.lines.get(1).y - page.lines.get(0).y, .001);
  }

  @Test
  public void minimumHeightGrowsForLargerTextAndCentersExtraSpace() {
    LayoutEngine.Line small = layout(paragraph("atLeast", 10)).pages.get(0).lines.get(0);
    assertEquals(24, small.height, 0);
    assertFalse(small.clipped);
    assertEquals(0, small.spans.get(0).top, 0);
    LayoutEngine.Line large = layout(paragraph("atLeast", 40)).pages.get(0).lines.get(0);
    assertEquals(40, large.height, 0);
    assertEquals(8, large.spans.get(0).top, 0);
  }

  @Test
  public void multiplesKeepNaturalGlyphSizeAndScaleLineAdvance() {
    for (float value : new float[] {.5f, 1, 1.5f, 2}) {
      LayoutEngine.Page page = layout(paragraph("auto", value)).pages.get(0);
      assertEquals(24 * value, page.lines.get(0).height, .001);
      assertFalse(page.lines.get(0).clipped);
      assertEquals(24 * value, page.lines.get(1).y - page.lines.get(0).y, .001);
    }
  }

  @Test
  public void exactSpacingControlsPaginationWithoutLosingLines() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 70; i++) text.append(i == 0 ? "" : "\n").append("line");
    Paragraph p = paragraph("exact", 30).text(RichText.of(text.toString(), TextStyle.DEFAULT));
    LayoutEngine.Result result = layout(p);
    assertTrue(result.pages.size() > 2);
    int count = 0;
    for (LayoutEngine.Page page : result.pages)
      for (LayoutEngine.Line line : page.lines) {
        count++;
        assertEquals(30, line.height, 0);
      }
    assertEquals(70, count);
  }

  @Test
  public void listMarkersFollowTheLineTextOffset() {
    LayoutEngine.Line line =
        layout(paragraph("exact", 10).with("list", "number")).pages.get(0).lines.get(0);
    assertFalse(line.marker.isEmpty());
    assertEquals(10 - TextStyle.DEFAULT.size, line.markerOffset, 0);
  }

  @Test
  public void tableCellBoundaryAndExactLineClipsAreIntersected() {
    Session s = new Session(Document.empty());
    s.insertTable(1, 1);
    s.setCell(1, 0, 0, "alpha\nbeta");
    s.enterCell(1, 0, 0);
    s.paragraph("lineRule", "exact");
    s.paragraph("spacing", "10");
    Table t = (Table) s.document().blocks.get(1);
    s.formatTable(1, t.format.row(0, new TableFormat.Row(20, "exact", true)));
    LayoutEngine.Page page = engine.layout(s.document(), 0).pages.get(0);
    for (LayoutEngine.Line line : page.lines)
      if (line.block == 1) {
        assertTrue(line.clipped);
        assertTrue(line.clipHeight <= 10);
        assertTrue(line.y + line.clipHeight <= page.boxes.get(0).y + 20 + .001);
      }
  }
}
