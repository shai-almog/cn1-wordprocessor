package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** Rich comment and reply bodies survive editing and saving, as Wordcraft's comment stories. */
public class RichCommentBodyTest {
  private static final TextStyle BOLD = TextStyle.DEFAULT.with("bold", "true");

  private static java.util.List<Paragraph> rich() {
    var text = RichText.of("plain ").concat(RichText.of("bold", BOLD)).concat(RichText.of(" tail"));
    return Arrays.asList(new Paragraph("b1", text), new Paragraph("b2", RichText.of("second")));
  }

  private static Document document() {
    var d = Document.empty();
    String id = d.blocks.get(0).id();
    var c =
        new ReviewComment("c1", "Ann", "x", id, 0, id, 0, false)
            .body(rich())
            .reply("Bob", "ok")
            .reply("Cy", "y");
    var replies = new java.util.ArrayList<>(c.replies);
    replies.set(1, replies.get(1).body(rich()));
    return d.comments(Collections.singletonList(c.replies(replies)));
  }

  private static boolean boldAt(Paragraph p, int at) {
    return p.text.styleAt(at).bold;
  }

  @Test
  public void editingKeepsTheRunsAroundTheChange() {
    var c = document().comments.get(0);
    assertSame(c, c.edited(-1, c.text));
    var edited = c.edited(-1, "plain bolder tail\nsecond line");
    assertEquals(2, edited.body.size());
    var p = edited.body.get(0);
    assertEquals("plain bolder tail", p.text.plainText());
    assertTrue(boldAt(p, 6));
    assertTrue(boldAt(p, 10));
    assertFalse(boldAt(p, 0));
    assertFalse(boldAt(p, 13));
    var reply = edited.edited(1, "plain bold tail!\nsecond").replies.get(1);
    assertEquals(2, reply.body.size());
    assertTrue(boldAt(reply.body.get(0), 7));
    assertEquals("plain bold tail!\nsecond", reply.text);
  }

  @Test
  public void docxKeepsRichReplyBodies() throws Exception {
    var out = new ByteArrayOutputStream();
    Docx.write(document(), out);
    var back = Docx.read(new ByteArrayInputStream(out.toByteArray())).document.comments.get(0);
    assertTrue(boldAt(back.body.get(0), 6));
    assertEquals("ok", back.replies.get(0).text);
    var reply = back.replies.get(1);
    assertEquals(2, reply.body.size());
    assertTrue(boldAt(reply.body.get(0), 6));
    assertEquals("plain bold tail\nsecond", reply.text);
  }

  @Test
  public void nativeFormatKeepsRichReplyBodies() throws Exception {
    var back = DocumentXml.read(DocumentXml.write(document())).comments.get(0);
    assertEquals("ok", back.replies.get(0).text);
    var reply = back.replies.get(1);
    assertTrue(boldAt(reply.body.get(0), 6));
    assertEquals("plain bold tail\nsecond", reply.text);
  }
}
