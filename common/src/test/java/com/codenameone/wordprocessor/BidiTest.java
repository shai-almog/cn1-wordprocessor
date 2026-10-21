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

  private static String levelString(String text, boolean rtl) {
    byte[] lv = Bidi.levels(text, rtl);
    var b = new StringBuilder();
    for (int i = 0; i < lv.length; i++) {
      char c = text.charAt(i);
      boolean removed = c >= 0x202A && c <= 0x202E;
      b.append(removed ? 'x' : (char) ('0' + lv[i]));
    }
    return b.toString();
  }

  /** UAX #9 BidiCharacterTest-style known answers; x marks characters removed by rule X9. */
  @Test
  public void uax9KnownAnswers() {
    String[][] cases = {
      {"a (b) c", "rtl", "2222222"},
      {"\u05D0 (b) \u05D1", "rtl", "1112111"},
      {"a (\u05D0) b", "ltr", "0001000"},
      {"\u05D0 (\u05D1) c", "ltr", "1111100"},
      {"a (b", "rtl", "2222"},
      {"\u05D0 [1]", "rtl", "11121"},
      {"a\u202Bb c\u202Cd", "ltr", "0x222x0"},
      {"a\u202Ebc\u202Cd", "ltr", "0x11x0"},
      {"\u202D\u05D0\u05D1\u202C", "rtl", "x22x"},
      {"a \u2067b c\u2069 d", "ltr", "000222000"},
      {"a\u2068\u05D0 b\u2069c", "ltr", "0011200"},
      {"\u05D0\u2068b\u2069", "rtl", "1121"},
      {"\u05D0 \u2066b\u2069 \u05D1", "ltr", "1112111"},
      {"a\u2069b", "ltr", "000"},
      {"\u05D0 1.5", "ltr", "11222"},
      {"\u05D0 1,000.50", "ltr", "1122222222"},
      {"\u05D0 1+2", "ltr", "11222"},
      {"\u05D0 $12", "ltr", "11222"},
      {"\u0627 12", "ltr", "1122"},
      {"1-2 \u05D0", "rtl", "22211"},
      {"a\u0301 b", "rtl", "2222"},
      {"\u05D0\t\u05D1", "ltr", "101"},
      {"\u05D0\u200Ea", "ltr", "100"},
    };
    for (String[] c : cases)
      assertEquals(c[0], c[2], levelString(c[0], "rtl".equals(c[1])));
  }

  @Test
  public void explicitControlsAreNotTrivial() {
    assertTrue(Bidi.needed("a\u2067b\u2069", false));
    assertFalse(Bidi.needed("a (b) c", false));
  }

  @Test
  public void tabbedLineReordersEachSegment() {
    var line = line("\u05D0\u05D1 c\t\u05D2\u05D3", false);
    var v = visual(line);
    assertTrue(v, v.startsWith("<\u05D0\u05D1"));
    assertTrue(v, v.endsWith("\t|<\u05D2\u05D3"));
    float x = 0;
    for (LayoutEngine.Span s : line.spans) {
      assertEquals(x, s.x, .001);
      x += s.width;
    }
  }

  private LayoutEngine.Page rtlPage(String text, String... props) {
    var s = new Session(Document.empty());
    s.replace(text);
    s.paragraph("rtl", "true");
    for (int i = 0; i < props.length; i += 2) s.paragraph(props[i], props[i + 1]);
    return engine().layout(s.document(), 0).pages.get(0);
  }

  private static float right(LayoutEngine.Line l) {
    float r = l.x;
    for (LayoutEngine.Span s : l.spans) r = Math.max(r, l.x + s.x + s.width);
    return r;
  }

  @Test
  public void rightToLeftIndentIsMeasuredFromTheRight() {
    var page = rtlPage(SHALOM, "indent", "36");
    var spec = page.spec;
    var l = page.lines.get(0);
    assertEquals(spec.left + spec.contentWidth() - 36, right(l), .01);
    var end = rtlPage(SHALOM, "rightIndent", "20", "align", "right").lines.get(0);
    assertEquals(spec.left + 20, end.x, .01);
    var center = rtlPage(SHALOM, "align", "center").lines.get(0);
    assertEquals(spec.left + (spec.contentWidth() - 40) / 2, center.x, .01);
  }

  @Test
  public void rightToLeftTabStopsAreMeasuredFromTheRight() {
    var page = rtlPage("\u05D0\t\u05D1", "tabs", "100,left,none");
    var spec = page.spec;
    var l = page.lines.get(0);
    LayoutEngine.Span first = null, second = null;
    for (LayoutEngine.Span s : l.spans) {
      if ("\u05D0".equals(s.text)) first = s;
      if ("\u05D1".equals(s.text)) second = s;
    }
    float edge = spec.left + spec.contentWidth();
    assertEquals(edge, l.x + first.x + first.width, .01);
    assertEquals(edge - 100, l.x + second.x + second.width, .01);
    assertTrue(l.spans.get(0).x <= l.spans.get(l.spans.size() - 1).x);
  }
}
