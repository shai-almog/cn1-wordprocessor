package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class LayoutTest {
  private LayoutEngine engine() {
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

  @Test
  public void paginationAndCacheInvalidation() {
    Session s = new Session(Document.empty());
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 1000; i++)
      text.append("Paragraph ").append(i).append(" with a sentence.\n");
    s.replace(text.toString());
    LayoutEngine e = engine();
    LayoutEngine.Result a = e.layout(s.document(), s.revision());
    assertTrue(a.pages.size() > 10);
    assertEquals(1001, a.measuredParagraphs);
    assertEquals(0, e.layout(s.document(), s.revision()).measuredParagraphs);
    s.select(500, 0, 0);
    s.replace("Edited ");
    LayoutEngine.Result b = e.layout(s.document(), s.revision());
    assertEquals(1, b.measuredParagraphs);
    int lines = 0;
    for (LayoutEngine.Page page : b.pages)
      for (LayoutEngine.Line l : page.lines) {
        lines++;
        assertTrue(l.y >= s.document().page.top);
        assertTrue(l.y + l.height <= s.document().page.height - s.document().page.bottom + 0.01);
      }
    assertEquals(1001, lines);
  }

  @Test
  public void longWordMakesProgress() {
    Session s = new Session(Document.empty());
    s.replace("Supercalifragilisticexpialidocious😀");
    Document d = s.document().page(new PageSpec(100, 100, 10, 40, 10, 40));
    LayoutEngine.Result r = engine().layout(d, 0);
    StringBuilder b = new StringBuilder();
    for (LayoutEngine.Page p : r.pages)
      for (LayoutEngine.Line l : p.lines) for (LayoutEngine.Span x : l.spans) b.append(x.text);
    assertEquals(d.plainText(), b.toString());
  }

  @Test
  public void tallTableSplitsAcrossPages() {
    Session s = new Session(Document.empty());
    s.insertTable(1, 2);
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 500; i++) b.append("long cell ");
    s.setCell(1, 0, 0, b.toString());
    LayoutEngine.Result r = engine().layout(s.document(), s.revision());
    assertTrue(r.pages.size() > 1);
    StringBuilder actual = new StringBuilder();
    for (LayoutEngine.Page p : r.pages)
      for (LayoutEngine.Line l : p.lines)
        if (l.block == 1 && l.column == 0)
          for (LayoutEngine.Span x : l.spans) actual.append(x.text);
    assertEquals(b.toString(), actual.toString());
  }
}
