package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import org.junit.Test;

/** Comment ranges in header/footer stories, as Wordcraft's CommentStart/CommentEnd in any story. */
public class HeaderCommentTest {
  private static Document withHeaderComment() {
    var d = Document.empty();
    d =
        d.furniture(
            PageFurniture.EMPTY.story(
                "header",
                new Sequence<Paragraph>().append(new Paragraph("h1", RichText.of("Header text")))));
    var c =
        new ReviewComment("c1", "Ann", "Check the header", "h1", 0, "h1", 6, false)
            .initials("A");
    return d.comments(Collections.singletonList(c));
  }

  @Test
  public void headerRangeIsAnAnchorInItsOwnStory() {
    var d = withHeaderComment();
    assertTrue(ReviewComment.validRange(d, "h1", 0, "h1", 6));
    var a = ReviewComment.locate(d, "h1");
    assertNotNull(a.furniture);
    assertTrue(a.sameStory(ReviewComment.locate(d, "h1")));
    // A range cannot run from the header into the body.
    String body = d.blocks.get(0).id();
    assertFalse(ReviewComment.validRange(d, "h1", 0, body, 0));
    assertTrue(ReviewComment.index(d).containsKey("h1"));
    // The editor highlights by the laid-out header line's paragraph id.
    var engine =
        new LayoutEngine(
            new LayoutEngine.Metrics() {
              public float width(String s, TextStyle t) {
                return s.length() * t.size / 2;
              }

              public float height(TextStyle t) {
                return t.size;
              }
            });
    boolean found = false;
    for (LayoutEngine.Line l : engine.layout(d, 0).pages.get(0).header)
      found |= "h1".equals(l.paragraphId);
    assertTrue(found);
  }

  @Test
  public void bodyEditsKeepTheHeaderRange() {
    var s = new Session(withHeaderComment());
    s.typeText("Body ", 0);
    assertEquals(1, s.document().comments.size());
    var c = s.document().comments.get(0);
    assertEquals("h1", c.startId);
    assertEquals(6, c.end);
    assertEquals("A", c.initials);
  }

  @Test
  public void docxRoundTripsTheHeaderRange() throws Exception {
    var out = new ByteArrayOutputStream();
    Docx.write(withHeaderComment(), out);
    var parts = ZipParts.read(new ByteArrayInputStream(out.toByteArray()));
    String header = null;
    for (var e : parts.entrySet())
      if (e.getKey().startsWith("word/cn1-header")) header = new String(e.getValue(), "UTF-8");
    assertTrue(header.contains("<w:commentRangeStart w:id=\"0\"/>"));
    assertTrue(header.contains("<w:commentRangeEnd w:id=\"0\"/>"));
    var opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    var back = opened.document;
    assertEquals(1, back.comments.size());
    var c = back.comments.get(0);
    assertEquals("Check the header", c.text);
    assertEquals(0, c.start);
    assertEquals(6, c.end);
    assertNotNull(ReviewComment.locate(back, c.startId).furniture);
    for (String w : opened.warnings) assertFalse(w, w.startsWith("Comment anchor"));
  }
}
