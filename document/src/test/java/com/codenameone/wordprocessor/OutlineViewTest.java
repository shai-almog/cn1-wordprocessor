package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class OutlineViewTest {
  private static Document doc() {
    var s = new Session(Document.empty());
    s.replace("Chapter");
    s.applyStyle("Heading1");
    s.enterParagraph();
    s.applyStyle("Normal");
    s.replace("Body text that is long enough to be cut when only the first line is shown in outline view mode ok");
    s.enterParagraph();
    s.applyStyle("Heading2");
    s.replace("Section");
    s.enterParagraph();
    s.applyStyle("Normal");
    s.replace("More");
    s.enterParagraph();
    s.applyStyle("Heading1");
    s.replace("Next");
    return s.document();
  }

  @Test
  public void levelsCollapseAndTruncate() {
    Document d = doc();
    assertEquals(5, d.blocks.size());
    assertFalse(OutlineView.ALL.filters(d));
    var all = OutlineView.ALL.apply(d);
    assertEquals(5, all.blocks.size());
    assertEquals(18, all.paragraph(1).indent, 0);
    assertEquals(18, all.paragraph(2).indent, 0);
    assertEquals(36, all.paragraph(3).indent, 0);
    assertEquals(2, OutlineView.ALL.showLevel(1).apply(d).blocks.size());
    assertEquals(3, OutlineView.ALL.showLevel(2).apply(d).blocks.size());
    var collapsed = OutlineView.ALL.collapse(d.blocks.get(0).id(), true);
    var c = collapsed.apply(d);
    assertEquals(2, c.blocks.size());
    assertEquals("Next", c.paragraph(1).text.plainText());
    assertEquals("wave", c.paragraph(0).text.styleAt(0).characters.underlineStyle);
    assertTrue(collapsed.filters(d));
    var first = OutlineView.ALL.firstLineOnly(true).apply(d).paragraph(1).text.plainText();
    assertTrue(first.length() < 92 && first.endsWith("…"));
    var plain = OutlineView.ALL.showFormatting(false).apply(d);
    assertEquals(TextStyle.DEFAULT.font, plain.paragraph(0).text.styleAt(0).font);
    assertEquals(d, d);
  }
}
