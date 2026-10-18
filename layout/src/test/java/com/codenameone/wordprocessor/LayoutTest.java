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
  public void mergedCellsHaveOneSpanningBoxAndPaginateWithoutDroppingText() {
    var s = new Session(Document.empty());
    s.insertTable(2, 2);
    s.setCell(
        1,
        0,
        0,
        """
        alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho\
         sigma tau""");
    s.setCell(1, 1, 0, "last cell");
    s.mergeCells(1, 0, 0, 2, 1);
    var d = s.document().page(new PageSpec(180, 130, 10, 10, 10, 10));
    var result = engine().layout(d, 0);
    assertTrue(result.pages.size() > 1);
    var text = new StringBuilder();
    int boxes = 0;
    for (LayoutEngine.Page page : result.pages) {
      for (LayoutEngine.Box box : page.boxes)
        if (box.column == 0) {
          boxes++;
          assertEquals(80, box.width, .01);
          assertEquals(0, box.row);
        }
      for (LayoutEngine.Line line : page.lines)
        if (line.block == 1 && line.column == 0)
          for (LayoutEngine.Span span : line.spans) text.append(span.text);
    }
    assertTrue(boxes >= 2);
    assertTrue(text.toString().contains("alpha beta"));
    assertTrue(text.toString().contains("tau"));
    assertTrue(text.toString().endsWith("last cell"));
  }

  @Test
  public void scriptGlyphsShrinkWithoutChangingLogicalTextOrBaseLineHeight() {
    var s = new Session(Document.empty());
    s.replace("x2");
    s.select(0, 1, 2);
    s.format("script", "superscript");
    var line = engine().layout(s.document(), 0).pages.get(0).lines.get(0);
    assertEquals(9.9, line.width, .001);
    assertEquals(2, line.end);
    assertEquals(14.4, line.height, .001);
    assertEquals(1, line.spans.get(1).start);
    assertEquals("superscript", line.spans.get(1).style.script);
    assertEquals(0, line.spans.get(1).top, .001);
    s.format("script", "subscript");
    line = engine().layout(s.document(), 1).pages.get(0).lines.get(0);
    assertEquals(6.6, line.spans.get(1).top, .001);
    assertEquals(9.9, line.width, .001);
  }

  @Test
  public void mixedFontSizesShareBaselineAndScriptsShiftRelativeToIt() {
    var s = new Session(Document.empty());
    s.replace("aB2");
    s.select(0, 1, 3);
    s.format("size", "24");
    s.select(0, 2, 3);
    s.format("script", "superscript");
    var line = engine().layout(s.document(), 0).pages.get(0).lines.get(0);
    assertEquals(12, line.spans.get(0).top, .001);
    assertEquals(0, line.spans.get(1).top, .001);
    assertEquals(0, line.spans.get(2).top, .001);
    assertEquals(8.4, 24 - (line.spans.get(2).top + 15.6), .001);
    s.format("script", "subscript");
    line = engine().layout(s.document(), 1).pages.get(0).lines.get(0);
    assertEquals(13.2, line.spans.get(2).top, .001);
    assertTrue(line.height >= 28.8);
  }

  @Test
  public void justifiedLinesReachRightEdgeButFinalAndHardBreakLinesDoNot() {
    var s = new Session(Document.empty());
    s.replace("one two three four five six seven eight nine ten");
    s.paragraph("align", "justify");
    var d = s.document().page(new PageSpec(150, 500, 10, 10, 10, 10));
    java.util.List<LayoutEngine.Line> lines = engine().layout(d, 0).pages.get(0).lines;
    assertTrue(lines.size() > 1);
    for (int i = 0; i < lines.size() - 1; i++) {
      var line = lines.get(i);
      assertEquals(140, line.x + line.width, .01);
      for (int j = 1; j < line.spans.size(); j++)
        assertEquals(
            line.spans.get(j - 1).x + line.spans.get(j - 1).width, line.spans.get(j).x, .01);
    }
    assertTrue(lines.get(lines.size() - 1).width < 130);
    s.select(0, 0, s.document().paragraph(0).text.length());
    s.replace("one two\nthree four");
    lines = engine().layout(s.document().page(d.page), 1).pages.get(0).lines;
    assertEquals(42, lines.get(0).width, .01);
  }

  @Test
  public void hangingAndRightIndentsAndCustomTabsAffectGeometry() {
    var s = new Session(Document.empty());
    s.replace("a\tb long words wrap onto a second line and then another");
    s.paragraph("indent", "24");
    s.paragraph("firstIndent", "-12");
    s.paragraph("rightIndent", "18");
    s.paragraph("tabInterval", "24");
    var d = s.document().page(new PageSpec(170, 500, 10, 10, 10, 10));
    java.util.List<LayoutEngine.Line> lines = engine().layout(d, 0).pages.get(0).lines;
    assertEquals(22, lines.get(0).x, .01);
    // The hanging first line begins 12pt before the paragraph origin; its tab lands at that origin.
    assertEquals(12, lines.get(0).spans.get(2).x, .01);
    assertEquals(34, lines.get(1).x, .01);
    for (LayoutEngine.Line line : lines) assertTrue(line.x + line.width <= 142.01);
    s.replace("z");
    assertEquals(-12, s.document().paragraph(0).format.firstIndent, .01);
    s.undo();
    assertEquals(-12, s.document().paragraph(0).format.firstIndent, .01);
  }

  @Test
  public void keepLinesMovesWholeParagraphWhenItFitsOnePage() {
    var s = new Session(Document.empty());
    s.replace("Start\nOne two three four five six seven eight nine ten eleven twelve");
    s.select(0, 0, 0);
    s.paragraph("after", "50");
    s.select(1, 0, 0);
    s.paragraph("keepLines", "true");
    var d = s.document().page(new PageSpec(180, 120, 10, 10, 10, 10));
    var result = engine().layout(d, 0);
    assertEquals(2, result.pages.size());
    assertEquals(1, result.pages.get(0).lines.size());
    for (LayoutEngine.Line line : result.pages.get(1).lines) assertEquals(1, line.block);
  }

  @Test
  public void tabsAdvanceToHalfInchStopsAndKeepNextMovesHeading() {
    var s = new Session(Document.empty());
    s.replace("a\tb");
    var result = engine().layout(s.document(), 0);
    var line = result.pages.get(0).lines.get(0);
    assertEquals(36, line.spans.get(2).x, .01);
    s = new Session(Document.empty());
    s.replace("Body\nHeading\nFollowing paragraph");
    s.select(0, 0, 0);
    s.paragraph("after", "48");
    s.select(1, 0, 0);
    s.paragraph("keepNext", "true");
    var d = s.document().page(new PageSpec(200, 110, 10, 10, 10, 10));
    result = engine().layout(d, 0);
    assertEquals(2, result.pages.size());
    assertEquals(0, result.pages.get(0).lines.get(0).block);
    assertEquals(1, result.pages.get(1).lines.get(0).block);
    assertEquals(2, result.pages.get(1).lines.get(1).block);
  }

  @Test
  public void repeatedGlyphsUseBoundedMeasurementsAcrossParagraphsAndEdits() {
    final int[] calls = {0};
    var e =
        new LayoutEngine(
            new LayoutEngine.Metrics() {
              public float width(String text, TextStyle s) {
                calls[0]++;
                return text.length() * 6;
              }

              public float height(TextStyle s) {
                return 12;
              }
            });
    var blocks = new Sequence<Block>();
    for (int i = 0; i < 1000; i++)
      blocks =
          blocks.append(new Paragraph("p" + i, RichText.of("A repeated sentence with words.")));
    var d = new Document(blocks, PageSpec.A4, "Test", 1001);
    e.layout(d, 0);
    assertTrue("Native measurements: " + calls[0], calls[0] < 50);
    var s = new Session(d);
    s.select(500, 0, 0);
    s.replace("x");
    assertEquals(1, e.layout(s.document(), s.revision()).measuredParagraphs);
    assertTrue(calls[0] < 55);
    e.clear();
    e.layout(d, 2);
    assertTrue(calls[0] > 30);
  }

  @Test
  public void listsNumberOncePerParagraphAndRestartAfterBodyText() {
    var s = new Session(Document.empty());
    s.replace("One long numbered paragraph that wraps onto many lines\nTwo\nBody\nRestart\nBullet");
    s.selectRange(0, 0, 1, 3);
    s.paragraph("list", "number");
    s.select(3, 0, 0);
    s.paragraph("list", "number");
    s.select(4, 0, 0);
    s.paragraph("list", "bullet");
    var d = s.document().page(new PageSpec(140, 300, 10, 10, 10, 10));
    var e = engine();
    var result = e.layout(d, 0);
    var markers = new java.util.ArrayList<String>();
    int wrapped = 0;
    for (LayoutEngine.Page page : result.pages)
      for (LayoutEngine.Line line : page.lines) {
        if (!line.marker.isEmpty()) markers.add(line.marker);
        if (line.block == 0) {
          wrapped++;
          assertEquals(28, line.x, 0.01);
        }
        assertTrue(line.x + line.width <= 130.01);
      }
    assertTrue(wrapped > 1);
    assertEquals(java.util.Arrays.asList("1.", "2.", "1.", "•"), markers);
    assertEquals(0, e.layout(d, 1).measuredParagraphs);
  }

  @Test
  public void paginationAndCacheInvalidation() {
    var s = new Session(Document.empty());
    var text = new StringBuilder();
    for (int i = 0; i < 1000; i++)
      text.append("Paragraph ").append(i).append(" with a sentence.\n");
    s.replace(text.toString());
    var e = engine();
    var a = e.layout(s.document(), s.revision());
    assertTrue(a.pages.size() > 10);
    assertEquals(1001, a.measuredParagraphs);
    assertEquals(0, e.layout(s.document(), s.revision()).measuredParagraphs);
    s.select(500, 0, 0);
    s.replace("Edited ");
    var b = e.layout(s.document(), s.revision());
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
    var s = new Session(Document.empty());
    s.replace("Supercalifragilisticexpialidocious😀");
    var d = s.document().page(new PageSpec(100, 100, 10, 40, 10, 40));
    var r = engine().layout(d, 0);
    var b = new StringBuilder();
    for (LayoutEngine.Page p : r.pages)
      for (LayoutEngine.Line l : p.lines) for (LayoutEngine.Span x : l.spans) b.append(x.text);
    assertEquals(d.plainText(), b.toString());
  }

  @Test
  public void tallTableSplitsAcrossPages() {
    var s = new Session(Document.empty());
    s.insertTable(1, 2);
    var b = new StringBuilder();
    for (int i = 0; i < 500; i++) b.append("long cell ");
    s.setCell(1, 0, 0, b.toString());
    var r = engine().layout(s.document(), s.revision());
    assertTrue(r.pages.size() > 1);
    var actual = new StringBuilder();
    for (LayoutEngine.Page p : r.pages)
      for (LayoutEngine.Line l : p.lines)
        if (l.block == 1 && l.column == 0)
          for (LayoutEngine.Span x : l.spans) actual.append(x.text);
    assertEquals(b.toString(), actual.toString());
  }
}
