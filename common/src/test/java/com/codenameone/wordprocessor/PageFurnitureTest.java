package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class PageFurnitureTest {
  @Test
  public void styleUpdatesReachHeadersAndFieldWordsAreCounted() {
    var s = new Session(Document.empty());
    var style =
        new ParagraphStyle(
            "Report", "Report", new Paragraph("template", new RichText()), TextStyle.DEFAULT);
    s.defineStyle(style);
    s.furniture(
        PageFurniture.EMPTY.story(
            "header",
            new Sequence<Paragraph>()
                .append(new Paragraph("h", RichText.of("Header")).with("style", "Report"))));
    s.defineStyle(
        new ParagraphStyle(
            "Report", "Report", style.paragraph, TextStyle.DEFAULT.with("bold", "true")));
    assertTrue(s.document().furniture.get("header").get(0).text.styleAt(0).bold);
    s.deleteStyle("Report");
    assertEquals("Normal", s.document().furniture.get("header").get(0).style);
    s.insertField(new InlineField("TITLE", "Three whole words", false, false));
    assertEquals(3, new DocumentStats().words(s.document()));
  }

  @Test
  public void variantsUseFirstThenEvenAndChangesAreUndoableWithFreshIds() {
    var f =
        PageFurniture.EMPTY
            .options(true, true, 18, 20)
            .story(
                "header",
                new Sequence<Paragraph>().append(new Paragraph("p1", RichText.of("Header"))));
    assertEquals("firstHeader", f.key(false, 0));
    assertEquals("evenFooter", f.key(true, 1));
    assertEquals("header", f.key(false, 2));
    var s = new Session(Document.empty());
    var before = s.document();
    s.furniture(f);
    assertNotEquals("p1", s.document().furniture.get("header").get(0).id);
    assertEquals(3, s.document().nextId);
    s.replace("Body");
    assertSame(s.document().furniture, s.document().title("Changed").furniture);
    s.undo();
    s.undo();
    assertSame(before, s.document());
  }

  @Test
  public void distancesAndStoryKeysAreBounded() {
    try {
      PageFurniture.EMPTY.options(false, false, Float.NaN, 18);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      PageFurniture.EMPTY.story("unknown", new Sequence<>());
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
