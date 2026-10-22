package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

/** A page-positioned float wraps the text on its page before its anchor too, as Word does. */
public class FloatAnchorLayoutTest {
  private static LayoutEngine engine() {
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

  private static Paragraph para(String id, String text) {
    return new Paragraph(id, RichText.of(text, TextStyle.DEFAULT));
  }

  private static String words(int n) {
    var b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append("word ");
    return b.toString().trim();
  }

  @Test public void textBeforeTheAnchorWrapsAFloatAboveIt() throws Exception {
    var seq = new Sequence<Block>();
    for (int i = 0; i < 10; i++) seq = seq.append(para("p" + i, words(40)));
    var pic = new Picture("i1", PictureTest.png(8, 0, 1, 1, new byte[] {0, 0}, null, null), 120, 90, "a", "left").movedTo(300, 120);
    seq = seq.append(pic).append(para("after", words(10)));
    var r = engine().layout(new Document(seq, PageSpec.A4, "t", 100), 1);
    var page = r.pages.get(0);
    LayoutEngine.PlacedPicture placed = page.pictures.get(0);
    assertEquals("the anchor comes after the covered text", 10, placed.block());
    int beside = 0;
    for (LayoutEngine.Line l : page.lines) {
      if (l.block >= 10 || l.y + l.height <= placed.y() + .5f || l.y >= placed.y() + placed.height() - .5f) continue;
      beside++;
      // Text may run on both sides of the float; no word may sit under it.
      for (LayoutEngine.Span s : l.spans) {
        float x0 = l.x + s.x, x1 = x0 + s.width;
        if (s.text.isBlank()) continue;
        assertTrue("\"" + s.text + "\" of block " + l.block + " at " + l.y + " runs under the float: " + x0 + ".." + x1,
            x1 <= placed.x() + .5f || x0 >= placed.x() + placed.width() - .5f);
      }
    }
    assertTrue("some earlier lines are beside the float", beside > 0);
  }
}
