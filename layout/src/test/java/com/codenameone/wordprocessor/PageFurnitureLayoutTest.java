package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class PageFurnitureLayoutTest {
  private LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String t, TextStyle s) {
            return t.length() * 6;
          }

          public float height(TextStyle s) {
            return 12;
          }
        });
  }

  private PageFurniture story(PageFurniture f, String key, String text) {
    return f.story(
        key,
        new Sequence<Paragraph>().append(new Paragraph(key, RichText.of(text)).with("after", "0")));
  }

  @Test
  public void firstEvenAndDefaultStoriesRenderAtMarginsWithPageSpecificFields() {
    PageFurniture f =
        story(
                story(story(PageFurniture.EMPTY, "firstHeader", "First"), "evenHeader", "Even"),
                "header",
                "Default")
            .options(true, true, 18, 18);
    RichText page =
        RichText.of("Page ")
            .concat(RichText.field(new InlineField("PAGE", "?", false, false), TextStyle.DEFAULT))
            .concat(RichText.of(" of "))
            .concat(
                RichText.field(new InlineField("NUMPAGES", "?", false, false), TextStyle.DEFAULT));
    for (String key : new String[] {"firstFooter", "evenFooter", "footer"})
      f =
          f.story(
              key, new Sequence<Paragraph>().append(new Paragraph(key, page).with("after", "0")));
    Sequence<Block> blocks = new Sequence<Block>();
    for (int i = 0; i < 3; i++)
      blocks = blocks.append(new Paragraph("p" + i, RichText.of("Body")).with("pageBreak", "true"));
    LayoutEngine.Result result =
        engine().layout(new Document(blocks, PageSpec.A4, "Title", 4).furniture(f), 0);
    assertEquals(3, result.pages.size());
    String[] names = {"First", "Even", "Default"};
    for (int i = 0; i < 3; i++) {
      LayoutEngine.Page p = result.pages.get(i);
      assertEquals(names[i], p.header.get(0).spans.get(0).displayText);
      assertEquals(18, p.header.get(0).y, .01);
      StringBuilder text = new StringBuilder();
      for (LayoutEngine.Span span : p.footer.get(0).spans) text.append(span.displayText);
      assertEquals("Page " + (i + 1) + " of 3", text.toString());
      assertTrue(p.footer.get(0).y > PageSpec.A4.height - 54);
      assertEquals(-1, p.footer.get(0).block);
      assertSame(p.allLines(), p.allLines());
    }
  }

  @Test
  public void existingLargeMarginsArePreservedAndLongHeadersReserveBodySpace() {
    PageSpec spec = new PageSpec(600, 800, 400, 50, 50, 50);
    Document d =
        new Document(
            new Sequence<Block>().append(new Paragraph("p1", RichText.of("Body"))),
            spec,
            "Title",
            2);
    assertEquals(400, engine().layout(d, 0).pages.get(0).lines.get(0).y, .01);
    PageFurniture f = story(PageFurniture.EMPTY, "header", "Header\nSecond\nThird\nFourth");
    LayoutEngine.Page page = engine().layout(d.page(PageSpec.A4).furniture(f), 0).pages.get(0);
    LayoutEngine.Line last = page.header.get(page.header.size() - 1);
    assertTrue(page.lines.get(0).y >= last.y + last.height);
  }

  @Test
  public void tallHeaderUsesAvailablePageSpaceInsteadOfArbitraryFortyPercentCap() {
    StringBuilder text = new StringBuilder("Header");
    for (int i = 1; i < 30; i++) text.append("\nLine ").append(i);
    Document d = Document.empty().furniture(story(PageFurniture.EMPTY, "header", text.toString()));
    LayoutEngine.Result result = engine().layout(d, 0);
    assertTrue(result.warnings.toString(), result.warnings.isEmpty());
    LayoutEngine.Page page = result.pages.get(0);
    assertEquals(30, page.header.size());
    LayoutEngine.Line last = page.header.get(29);
    assertTrue(page.lines.get(0).y >= last.y + last.height - .01f);
  }

  @Test
  public void actualTitleFieldResultsReserveSpaceBeforeBodyPagination() {
    RichText field = RichText.field(new InlineField("TITLE", "?", false, false), TextStyle.DEFAULT);
    PageFurniture furniture =
        PageFurniture.EMPTY.story(
            "header",
            new Sequence<Paragraph>()
                .append(
                    new Paragraph("h", field.concat(RichText.of(" ")).concat(field))
                        .with("after", "0")));
    Document d =
        Document.empty()
            .title("Long title")
            .page(new PageSpec(200, 300, 20, 40, 20, 40))
            .furniture(furniture);
    LayoutEngine.Result result = engine().layout(d, 0);
    assertTrue(result.warnings.toString(), result.warnings.isEmpty());
    LayoutEngine.Page page = result.pages.get(0);
    assertEquals(2, page.header.size());
    LayoutEngine.Line last = page.header.get(1);
    assertTrue(page.lines.get(0).y >= last.y + last.height - .01f);
  }

  @Test
  public void impossibleStoriesReportOverflowWhileKeepingBodyEditable() {
    StringBuilder text = new StringBuilder("Header");
    for (int i = 0; i < 100; i++) text.append("\nToo much");
    Document d = Document.empty().furniture(story(PageFurniture.EMPTY, "header", text.toString()));
    LayoutEngine.Result result = engine().layout(d, 0);
    assertEquals(1, result.warnings.size());
    assertTrue(result.warnings.get(0).contains("Header on page 1 does not fit"));
    assertEquals(1, result.pages.get(0).lines.size());
    assertTrue(result.pages.get(0).lines.get(0).y < d.page.height - d.page.bottom);
  }
}
