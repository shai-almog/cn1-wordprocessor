package com.codenameone.wordprocessor;
import static org.junit.Assert.*;
import org.junit.Test;
public class CommentControlsCommandsTest {
  @Test public void commandsEditThreadsRepliesAndNavigateWithoutDocumentMutations() {
    var c = new Commands(); var s = new Session(Document.empty()); s.replace("alpha beta"); s.select(0, 6, 10); s.addComment("Ada", "Old");
    var id = s.document().comments.get(0).id;
    c.execute(s, "comment.edit", Json.object("id", id, "text", "New")); assertEquals("New", s.document().comments.get(0).text);
    c.execute(s, "comment.reply", Json.object("id", id, "author", "Lin", "text", "Reply"));
    c.execute(s, "comment.edit", Json.object("id", id, "reply", 0, "text", "Edited")); assertEquals("Edited", s.document().comments.get(0).replies.get(0).text);
    var d = s.document();
    try {c.execute(s, "comment.edit", Json.object("id", id, "reply", 0.5, "text", "Bad")); fail("fractional index accepted");} catch (IllegalArgumentException expected) {}
    s.select(0, 0, 0); assertEquals(id, c.execute(s, "comment.next")); assertEquals(6, s.selection().caret);
    assertEquals(id, c.execute(s, "comment.previous")); assertSame(d, s.document());
    s.undo(); assertEquals("Reply", s.document().comments.get(0).replies.get(0).text);
  }
}
