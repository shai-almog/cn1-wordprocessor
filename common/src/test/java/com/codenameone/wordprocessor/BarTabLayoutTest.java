package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class BarTabLayoutTest {
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

  private Paragraph paragraph(String text) {
    return new Paragraph("p1", RichText.of(text, TextStyle.DEFAULT))
        .with("tabs", "24,bar,none;120,left,dot");
  }

  private Document document(Paragraph p) {
    return new Document(
        new Sequence<Block>().append(p), new PageSpec(240, 300, 20, 20, 20, 20), "Bars", 2);
  }

  @Test
  public void barsDoNotConsumeTabsOrGenerateLeaders() {
    var line =
        engine.layout(document(paragraph("A\tB")), 0).pages.get(0).lines.get(0);
    assertEquals(120, line.spans.get(2).x, .001);
    assertEquals(44, line.x + line.barTabs.get(0), .001);
    assertEquals("...................", line.spans.get(1).displayText);
    var p = paragraph("A\tB").with("tabs", "24,bar,dot");
    line = engine.layout(document(p), 1).pages.get(0).lines.get(0);
    assertEquals(36, line.spans.get(2).x, .001);
    assertEquals("", line.spans.get(1).displayText);
  }

  @Test
  public void barsStayFixedAcrossWrappingAlignmentAndFirstIndent() {
    for (String align : new String[] {"left", "center", "right", "both", "distribute"}) {
      var p =
          paragraph("First line\nSecond line\nThird line")
              .with("align", align)
              .with("indent", "15")
              .with("firstIndent", "18")
              .with("rightIndent", "12");
      var page = engine.layout(document(p), 0).pages.get(0);
      assertEquals(3, page.lines.size());
      for (LayoutEngine.Line line : page.lines)
        assertEquals(59, line.x + line.barTabs.get(0), .001);
    }
    var p =
        paragraph("A long paragraph that wraps onto several lines with a vertical rule")
            .with("tabs", "24,bar,none");
    var page = engine.layout(document(p), 0).pages.get(0);
    assertTrue(page.lines.size() > 1);
    for (LayoutEngine.Line line : page.lines) assertEquals(44, line.x + line.barTabs.get(0), .001);
  }

  @Test
  public void emptyParagraphAndCachedLayoutRetainBars() {
    var d = document(paragraph(""));
    LayoutEngine.Result first = engine.layout(d, 0), second = engine.layout(d, 1);
    assertEquals(0, second.measuredParagraphs);
    assertEquals(first.pages.get(0).lines.get(0).barTabs, second.pages.get(0).lines.get(0).barTabs);
    assertEquals(1, second.pages.get(0).lines.get(0).barTabs.size());
  }

  @Test
  public void barsFollowCellPaddingAndMarginStoryOrigins() {
    var s = new Session(document(paragraph("Body")));
    s.insertTable(1, 1);
    s.enterCell(1, 0, 0);
    s.paragraph("tabs", "24,bar,none");
    s.paragraph("align", "right");
    s.paragraph("firstIndent", "9");
    var d =
        s.document()
            .furniture(
                PageFurniture.EMPTY.story(
                    "header",
                    new Sequence<Paragraph>()
                        .append(
                            paragraph("Header")
                                .with("align", "center")
                                .with("firstIndent", "12"))));
    var page = engine.layout(d, 0).pages.get(0);
    LayoutEngine.Line cell = null;
    for (LayoutEngine.Line line : page.lines) if (line.block == 1) cell = line;
    assertNotNull(cell);
    var table = (Table) d.blocks.get(1);
    assertEquals(
        44 + table.format.cell(0, 0).horizontalPadding, cell.x + cell.barTabs.get(0), .001);
    assertEquals(44, page.header.get(0).x + page.header.get(0).barTabs.get(0), .001);
  }
}
