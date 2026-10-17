package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class SectionsLayoutTest {
  private LayoutEngine.Result layout(Document d) {
    return new LayoutEngine(
            new LayoutEngine.Metrics() {
              public float width(String text, TextStyle style) {
                return text.length() * 6;
              }

              public float height(TextStyle style) {
                return 12;
              }
            })
        .layout(d, 0);
  }

  @Test
  public void mixedPaperAndLinkedFieldsUseSectionPageNumbers() {
    Session s = new Session(Document.empty());
    s.furniture(
        PageFurniture.EMPTY.story(
            "footer",
            new Sequence<Paragraph>()
                .append(
                    new Paragraph(
                        "f",
                        RichText.field(
                            new InlineField("PAGE", "?", false, false), TextStyle.DEFAULT)))));
    s.replace("Portrait");
    s.sectionBreak("nextPage");
    s.replace("Landscape");
    s.section(s.activeSection().raw.page(new PageSpec(842, 595, 54, 54, 54, 54)).numbering(7));
    LayoutEngine.Result r = layout(s.document());
    assertTrue(r.warnings.toString(), r.warnings.isEmpty());
    assertEquals(2, r.pages.size());
    assertEquals(595.28, r.pages.get(0).spec.width, .01);
    assertEquals(842, r.pages.get(1).spec.width, .01);
    assertEquals(7, r.pages.get(1).number);
    assertEquals("1", r.pages.get(0).footer.get(0).spans.get(0).displayText);
    assertEquals("7", r.pages.get(1).footer.get(0).spans.get(0).displayText);
    assertEquals(1, r.pages.get(1).lines.get(0).page);
    assertEquals(841.89, r.heightBefore(1), .01);
    assertEquals(1436.89, r.heightBefore(2), .01);
    assertEquals(842, r.maxWidth, .01);
  }

  @Test
  public void continuousSectionsKeepTextOnPageWhileOddAndEvenBreaksPad() {
    for (String kind : new String[] {"continuous", "oddPage", "evenPage"}) {
      Session s = new Session(Document.empty());
      s.replace("First");
      s.sectionBreak(kind);
      s.replace("Second");
      LayoutEngine.Result r = layout(s.document());
      int count = "continuous".equals(kind) ? 1 : "oddPage".equals(kind) ? 3 : 2;
      assertEquals(kind, count, r.pages.size());
      assertEquals(
          "Second",
          r.pages.get(count - 1).lines.get("continuous".equals(kind) ? 1 : 0).spans.get(0).text);
      if ("oddPage".equals(kind)) assertTrue(r.pages.get(1).lines.isEmpty());
    }
  }

  @Test
  public void differentFirstHeaderRestartsAtSectionBoundary() {
    Session s = new Session(Document.empty());
    PageFurniture f =
        PageFurniture.EMPTY
            .options(true, false, 18, 18)
            .story(
                "firstHeader",
                new Sequence<Paragraph>().append(new Paragraph("h", RichText.of("First"))))
            .story(
                "header",
                new Sequence<Paragraph>().append(new Paragraph("h2", RichText.of("Other"))));
    s.furniture(f);
    s.replace("One");
    s.sectionBreak("nextPage");
    s.replace("Two\nThree");
    s.paragraph("pageBreak", "true");
    LayoutEngine.Result r = layout(s.document());
    assertEquals(3, r.pages.size());
    assertEquals("First", r.pages.get(0).header.get(0).spans.get(0).text);
    assertEquals("First", r.pages.get(1).header.get(0).spans.get(0).text);
    assertEquals("Other", r.pages.get(2).header.get(0).spans.get(0).text);
  }

  @Test
  public void oddBreaksFollowLogicalNumbersAndRestartsMatchRequestedParity() {
    Session s = new Session(Document.empty());
    s.replace("One");
    s.sectionBreak("nextPage");
    s.replace("Seven");
    s.section(s.activeSection().raw.numbering(7));
    s.sectionBreak("oddPage");
    s.replace("Nine");
    LayoutEngine.Result r = layout(s.document());
    assertEquals(4, r.pages.size());
    assertEquals(8, r.pages.get(2).number);
    assertTrue(r.pages.get(2).lines.isEmpty());
    assertEquals(9, r.pages.get(3).number);
    s.section(s.activeSection().raw.numbering(2));
    r = layout(s.document());
    assertEquals(3, r.pages.size());
    assertEquals(3, r.pages.get(2).number);
  }
}
