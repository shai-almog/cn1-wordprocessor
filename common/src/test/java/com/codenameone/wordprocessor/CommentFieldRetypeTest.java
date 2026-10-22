package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** Retyping a comment body maps plain-text offsets (a field shows its result) to RichText positions. */
public class CommentFieldRetypeTest {
  private static List<Paragraph> body() {
    var text = RichText.of("See page ")
        .concat(RichText.field(new InlineField("PAGE", "12", false, false), TextStyle.DEFAULT))
        .concat(RichText.of(" now"));
    return List.of(new Paragraph("c-p0", text));
  }

  private static int fields(RichText t) {
    int[] n = {0};
    t.visit(
        new RichText.Visitor() {
          public void run(int offset, String text, TextStyle style) {}

          public void field(int offset, InlineField f, TextStyle style) {
            n[0]++;
          }
        });
    return n[0];
  }

  @Test
  public void editAfterFieldKeepsFieldAndEditsTheRightPlace() {
    var out = ReviewComment.retyped(body(), "See page 12 later");
    assertEquals("See page 12 later", out.get(0).text.plainText());
    assertEquals(1, fields(out.get(0).text));
  }

  @Test
  public void editBeforeFieldKeepsField() {
    var out = ReviewComment.retyped(body(), "Look page 12 now");
    assertEquals("Look page 12 now", out.get(0).text.plainText());
    assertEquals(1, fields(out.get(0).text));
  }

  @Test
  public void editInsideFieldResultReplacesTheWholeField() {
    var out = ReviewComment.retyped(body(), "See page 13 now");
    assertEquals("See page 13 now", out.get(0).text.plainText());
    assertEquals(0, fields(out.get(0).text));
    out = ReviewComment.retyped(body(), "See page 1x2 now");
    assertEquals("See page 1x2 now", out.get(0).text.plainText());
  }
}
