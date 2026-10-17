package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TextDecorationsTest {
  private final LayoutEngine.Metrics metrics =
      new LayoutEngine.Metrics() {
        public float width(String text, TextStyle s) {
          return text.length() * 6;
        }

        public float height(TextStyle s) {
          return 12;
        }
      };

  private List<float[]> lines(String value, String type) {
    Document d =
        Document.empty()
            .blocks(
                new Sequence<Block>()
                    .append(
                        new Paragraph(
                            "p1",
                            RichText.of(value, TextStyle.DEFAULT.with("underlineStyle", type)))),
                2);
    List<float[]> result = new ArrayList<float[]>();
    for (LayoutEngine.Span span :
        new LayoutEngine(metrics).layout(d, 0).pages.get(0).lines.get(0).spans)
      TextDecorations.underline(
          span,
          metrics,
          (x1, y1, x2, y2) -> result.add(new float[] {span.x + x1, y1, span.x + x2, y2}));
    return result;
  }

  @Test
  public void wordOnlyUnderlineLeavesWhitespaceUnpainted() {
    List<float[]> path = lines("ab  cd", "words");
    assertEquals(2, path.size());
    assertArrayEquals(new float[] {0, 1, 12, 1}, path.get(0), .001f);
    assertArrayEquals(new float[] {24, 1, 36, 1}, path.get(1), .001f);
  }

  @Test
  public void allPatternsStayWithinTextAdvanceAndUseFiniteSegments() {
    for (String type : CharacterFormat.UNDERLINES)
      for (float[] line : lines("abcdefghij", type)) {
        assertTrue(type, line[0] >= 0 && line[2] <= 60.001 && line[2] > line[0]);
        assertTrue(type, Math.abs(line[1]) < 6 && Math.abs(line[3]) < 6);
      }
  }

  @Test
  public void wordsAndNoneDoNotUnderlineAllWhitespace() {
    assertTrue(lines("  ", "words").isEmpty());
    assertTrue(lines("abc", "none").isEmpty());
  }

  @Test
  public void doubleAndWaveAreDistinctPaths() {
    assertEquals(2, lines("abc", "double").size());
    assertTrue(lines("abc", "wave").size() > 2);
    assertEquals(2 * lines("abc", "wave").size(), lines("abc", "wavyDouble").size());
    boolean diagonal = false;
    for (float[] line : lines("abc", "wave")) diagonal |= line[1] != line[3];
    assertTrue(diagonal);
  }
}
