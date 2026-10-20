package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class BidiTest {
  private static final String SHALOM = "שלום", OLAM = "עולם";

  private LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String s, TextStyle t) {
            return s.codePointCount(0, s.length()) * 10;
          }

          public float height(TextStyle t) {
            return t.size;
          }
        });
  }

  private LayoutEngine.Line line(String text, boolean rtl) {
    var s = new Session(Document.empty());
    s.replace(text);
    if (rtl) s.paragraph("rtl", "true");
    return engine().layout(s.document(), 0).pages.get(0).lines.get(0);
  }

  private static String visual(LayoutEngine.Line line) {
    var b = new StringBuilder();
    for (LayoutEngine.Span s : line.spans) {
      if (b.length() > 0) b.append('|');
      b.append(s.rtl ? "<" : ">").append(s.text);
    }
    return b.toString();
  }

  @Test
  public void latinOnlyTextIsUntouched() {
    var line = line("plain text", false);
    assertEquals(1, line.spans.size());
    assertFalse(line.spans.get(0).rtl);
  }

  @Test
  public void hebrewInsideEnglishIsOneRightToLeftRun() {
    var line = line("say " + SHALOM + " " + OLAM + " now", false);
    assertEquals(">say |<" + SHALOM + " " + OLAM + "|> now", visual(line));
    float x = 0;
    for (LayoutEngine.Span s : line.spans) {
      assertEquals(x, s.x, .001);
      x += s.width;
    }
  }

  @Test
  public void numbersInHebrewRunLeftToRight() {
    byte[] levels = Bidi.levels(SHALOM + " 123", true);
    assertEquals(1, levels[0]);
    assertEquals(2, levels[5]);
    assertEquals(2, levels[7]);
    levels = Bidi.levels("abc 123", false);
    assertEquals(0, levels[4]);
  }

  @Test
  public void rightToLeftParagraphPutsFirstWordAtTheRight() {
    var line = line(SHALOM + " abc " + OLAM, true);
    var first = line.spans.get(line.spans.size() - 1);
    assertEquals(0, first.start);
    assertTrue(first.rtl);
    var last = line.spans.get(0);
    assertTrue(last.text.contains(OLAM));
    boolean latin = false;
    for (LayoutEngine.Span s : line.spans)
      if (s.text.equals("abc")) {
        latin = true;
        assertFalse(s.rtl);
      }
    assertTrue(latin);
  }
}
