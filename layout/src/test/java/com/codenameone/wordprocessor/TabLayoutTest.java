package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class TabLayoutTest {
  private LayoutEngine.Line line(String text, String stops, String indent) {
    Paragraph p =
        new Paragraph("p1", RichText.of(text, TextStyle.DEFAULT))
            .with("tabs", stops)
            .with("firstIndent", indent);
    Document d = new Document(new Sequence<Block>().append(p), PageSpec.A4, "Tabs", 2);
    return new LayoutEngine(
            new LayoutEngine.Metrics() {
              public float width(String t, TextStyle s) {
                return t.length() * 6;
              }

              public float height(TextStyle s) {
                return 12;
              }
            })
        .layout(d, 0)
        .pages
        .get(0)
        .lines
        .get(0);
  }

  @Test
  public void leftCenterRightDecimalStopsAlignFollowingText() {
    assertEquals(120, line("A\t1234", "120,left,none", "0").spans.get(2).x, .001);
    assertEquals(108, line("A\t1234", "120,center,none", "0").spans.get(2).x, .001);
    assertEquals(96, line("A\t1234", "120,right,none", "0").spans.get(2).x, .001);
    assertEquals(108, line("A\t12.34", "120,decimal,none", "0").spans.get(2).x, .001);
    assertEquals(96, line("A\t1234", "120,decimal,none", "0").spans.get(2).x, .001);
  }

  @Test
  public void leadersKeepSourceOffsetsAndFirstIndentDoesNotShiftStop() {
    LayoutEngine.Line l = line("A\t12", "120,right,dot", "18");
    LayoutEngine.Span gap = l.spans.get(1);
    assertEquals("\t", gap.text);
    assertEquals(1, gap.start);
    assertEquals(2, gap.end);
    assertEquals(84, gap.width, .001);
    assertEquals("..............", gap.displayText);
    assertEquals(90, l.spans.get(2).x, .001);
    assertEquals("A\t12", l.spans.get(0).text + gap.text + l.spans.get(2).text);
  }

  @Test
  public void nextStopAndPeriodicFallbackAreDeterministic() {
    LayoutEngine.Line l = line("A\tB\tC\tD", "60,left,hyphen;120,left,underscore", "0");
    assertEquals(60, l.spans.get(2).x, .001);
    assertEquals(120, l.spans.get(4).x, .001);
    assertEquals(144, l.spans.get(6).x, .001);
    assertEquals("---------", l.spans.get(1).displayText);
    assertEquals("_________", l.spans.get(3).displayText);
    assertTrue(l.spans.get(5).displayText.isEmpty());
  }
}
