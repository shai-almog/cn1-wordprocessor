package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class ProofingLayoutTest {
  private static LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String s, TextStyle t) {
            return s.codePointCount(0, s.length()) * t.size / 2;
          }

          public float height(TextStyle t) {
            return t.size;
          }
        });
  }

  private static String lines(Document d) {
    var out = new StringBuilder();
    for (LayoutEngine.Page page : engine().layout(d, 0).pages)
      for (LayoutEngine.Line line : page.lines) {
        for (LayoutEngine.Span span : line.spans) out.append(span.displayText);
        out.append('|');
      }
    return out.toString();
  }

  private static Document doc(boolean automatic) {
    var s = new Session(Document.empty());
    s.replace("an extraordinary documentation typography");
    var d = s.document().page(new PageSpec(150, 200, 10, 10, 10, 10));
    return automatic ? d.properties(d.properties.withSetting("hyphenation", "automatic")) : d;
  }

  @Test
  public void hyphenatesOnlyWhenTheSettingIsAutomatic() {
    String off = lines(doc(false));
    String on = lines(doc(true));
    assertFalse(off, off.indexOf('-') >= 0);
    assertTrue(on, on.indexOf("-|") > 0);
    assertEquals(off.replace("|", "").replace(" ", "").length() + 0, on.replace("|", "").replace(" ", "").replace("-", "").length());
  }
}
