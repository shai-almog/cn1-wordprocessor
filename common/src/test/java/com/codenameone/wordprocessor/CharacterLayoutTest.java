package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class CharacterLayoutTest {
  private final LayoutEngine.Metrics metrics =
      new LayoutEngine.Metrics() {
        public float width(String text, TextStyle s) {
          return text.codePointCount(0, text.length()) * s.size / 2;
        }

        public float height(TextStyle s) {
          return s.size;
        }
      };

  private LayoutEngine.Line line(String text, TextStyle style) {
    Document d = Document.empty();
    d = d.blocks(new Sequence<Block>().append(new Paragraph("p1", RichText.of(text, style))), 2);
    return new LayoutEngine(metrics).layout(d, 0).pages.get(0).lines.get(0);
  }

  @Test
  public void capsExpansionPreservesSourceOffsetsAndMeasuresDisplayedGlyphs() {
    var l = line("aßZ", TextStyle.DEFAULT.with("allCaps", "true"));
    assertEquals("ASSZ", l.spans.get(0).displayText);
    assertEquals("aßZ", l.spans.get(0).text);
    assertEquals(3, l.end);
    assertEquals(24, l.width, 0);
  }

  @Test
  public void smallCapsShareBaselineAndKeepUppercaseFullSize() {
    var l = line("Aa", TextStyle.DEFAULT.with("smallCaps", "true"));
    assertEquals(2, l.spans.size());
    LayoutEngine.Span a = l.spans.get(0), b = l.spans.get(1);
    assertEquals(12, a.glyphStyle.size, 0);
    assertEquals(9.6, b.glyphStyle.size, .001);
    assertEquals(a.top + a.glyphStyle.size, b.top + b.glyphStyle.size, .001);
    assertEquals(10.8, l.width, .001);
  }

  @Test
  public void spacingScalingAndLoweringCombineInPoints() {
    var l =
        line(
            "Ab",
            TextStyle.DEFAULT
                .with("characterSpacing", "2")
                .with("characterScale", "150")
                .with("characterPosition", "-4"));
    assertEquals(22, l.width, .001);
    assertEquals(4, l.spans.get(0).top, .001);
    assertEquals(16 * 1.2f, l.height, .001);
  }

  @Test
  public void hiddenTextConsumesNoHorizontalSpaceAndHugeHiddenFontsDoNotGrowLines() {
    var l =
        line("Invisible", TextStyle.DEFAULT.with("hidden", "true").with("size", "200"));
    assertEquals(0, l.width, 0);
    assertEquals("", l.spans.get(0).displayText);
    assertEquals(12 * 1.2f, l.height, .001);
    assertEquals(9, l.end);
  }

  @Test
  public void condensedAdvanceCannotRunBackwards() {
    assertEquals(0, line("abc", TextStyle.DEFAULT.with("characterSpacing", "-100")).width, 0);
  }

  @Test
  public void combiningMarksAreNotSplitBySmallCapsCaseBoundary() {
    var l = line("a\u0301B", TextStyle.DEFAULT.with("smallCaps", "true"));
    assertEquals("a\u0301", l.spans.get(0).text);
    assertEquals(2, l.spans.size());
  }

  @Test
  public void wideCharactersReflowInsteadOfOverflowing() {
    var b = new StringBuilder();
    for (int i = 0; i < 120; i++) b.append('a');
    var plain = line(b.toString(), TextStyle.DEFAULT);
    var wide = line(b.toString(), TextStyle.DEFAULT.with("characterScale", "200"));
    assertTrue(wide.end < plain.end);
    assertTrue(wide.width <= PageSpec.A4.contentWidth());
  }

  @Test
  public void hiddenTabsAndManualBreaksDoNotAdvanceOrSplitTheLine() {
    var l = line("a\tb\nc", TextStyle.DEFAULT.with("hidden", "true"));
    assertEquals(0, l.width, 0);
    assertEquals(5, l.end);
    for (LayoutEngine.Span span : l.spans) assertEquals("", span.displayText);
  }

  @Test
  public void fieldSmallCapsRetainRawResultAndAtomicSourceOffsets() {
    var style = TextStyle.DEFAULT.with("smallCaps", "true").with("characterSpacing", "2");
    var d =
        Document.empty()
            .blocks(
                new Sequence<Block>()
                    .append(
                        new Paragraph(
                            "p1",
                            RichText.field(
                                new InlineField("MERGEFIELD Name", "aB", true, false), style))),
                2);
    var span =
        new LayoutEngine(metrics).layout(d, 0).pages.get(0).lines.get(0).spans.get(0);
    assertEquals("aB", span.rawDisplay);
    assertEquals("AB", span.displayText);
    assertEquals(1, span.end);
    assertEquals(9.6, span.glyphStyle.size, .001);
    assertEquals(14.8, span.width, .001);
  }
}
