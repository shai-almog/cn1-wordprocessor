package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ColumnsLayoutTest {
  private static LayoutEngine.Result layout(Document d) {
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

  private static Document text(int paragraphs, Columns columns) {
    var s = new Session(Document.empty());
    var b = new StringBuilder();
    for (int i = 0; i < paragraphs; i++) b.append(i == 0 ? "" : "\n").append("Line ").append(i);
    s.replace(b.toString());
    var d = s.document();
    return d.section(0, d.lastSection.columns(columns));
  }

  @Test
  public void textFlowsIntoTheSecondColumnBeforeANewPage() {
    Document d = text(120, Columns.equal(2, 36, true));
    LayoutEngine.Result r = layout(d);
    var first = r.pages.get(0);
    float left = d.page.left, mid = d.page.left + d.page.contentWidth() / 2;
    boolean leftColumn = false, rightColumn = false;
    for (LayoutEngine.Line l : first.lines) {
      if (l.x < mid) leftColumn = true;
      else rightColumn = true;
      assertTrue(l.x >= left - .01f);
    }
    assertTrue(leftColumn && rightColumn);
    assertEquals(1, first.columnRules.size());
    var rule = first.columnRules.get(0);
    assertEquals(d.page.left + d.page.contentWidth() / 2, rule[0], .5f);
    // Two columns hold about twice the lines of one.
    int single = layout(text(120, Columns.ONE)).pages.size();
    assertTrue(r.pages.size() < single);
  }

  @Test
  public void columnBreakStartsTheParagraphAtTheTopOfTheNextColumn() {
    Document d = text(3, Columns.equal(2, 36, false));
    var p = d.paragraph(1);
    d = d.blocks(d.blocks.set(1, p.with("columnBreak", "true")), d.nextId);
    LayoutEngine.Result r = layout(d);
    LayoutEngine.Line first = null, second = null;
    for (LayoutEngine.Line l : r.pages.get(0).lines) {
      if (l.block == 0) first = l;
      if (l.block == 1) second = l;
    }
    assertEquals(1, r.pages.size());
    assertEquals(first.y, second.y, .01f);
    assertTrue(second.x > first.x + 100);
  }

  @Test
  public void unequalColumnsUseTheirWidths() {
    float tw = PageSpec.A4.contentWidth();
    Columns c = Columns.preset("left", tw, 36);
    var g = c.layout(tw);
    assertEquals((tw - 36) / 3, g[0][1], .01f);
    assertEquals(g[0][1] + 36, g[1][0], .01f);
    assertEquals(tw, g[1][0] + g[1][1], .01f);
  }
}
