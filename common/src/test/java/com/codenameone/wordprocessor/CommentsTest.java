package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class CommentsTest {
  @Test
  public void repliesFollowAnchorsAndUndoWithTheirThread() {
    var s = new Session(Document.empty());
    s.replace("alpha beta");
    s.select(0, 6, 10);
    s.addComment("Ada", "Check beta");
    var id = s.document().comments.get(0).id;
    s.updateComment(id, true, false);
    s.replyComment(id, "Grace", "Confirmed");
    assertFalse(s.document().comments.get(0).resolved);
    assertEquals("Grace", s.document().comments.get(0).replies.get(0).author);
    s.select(0, 0, 0);
    s.replace("Prefix ");
    assertEquals("Confirmed", s.document().comments.get(0).replies.get(0).text);
    s.selectComment(id);
    assertEquals("beta", s.selectedText());
    s.undo();
    s.undo();
    assertTrue(s.document().comments.get(0).replies.isEmpty());
    assertTrue(s.document().comments.get(0).resolved);
    s.redo();
    assertEquals(1, s.document().comments.get(0).replies.size());
  }

  @Test
  public void anchorsFollowTypingSplitsAndUndo() {
    var s = new Session(Document.empty());
    s.replace("alpha beta gamma");
    s.select(0, 6, 10);
    s.addComment("Ada", "Check beta");
    var commented = s.document();
    var id = commented.comments.get(0).id;
    s.select(0, 0, 0);
    s.replace("A ");
    s.selectComment(id);
    assertEquals("beta", s.selectedText());
    assertEquals(8, s.selection().start());
    s.select(0, 8, 8);
    s.enterParagraph();
    s.selectComment(id);
    assertEquals("beta", s.selectedText());
    assertEquals(1, s.selection().startBlock());
    s.undo();
    s.undo();
    assertSame(commented, s.document());
    s.updateComment(id, true, false);
    assertTrue(s.document().comments.get(0).resolved);
    s.undo();
    assertFalse(s.document().comments.get(0).resolved);
  }

  @Test
  public void deletingCommentedTextRemovesCommentAndUndoRestoresIt() {
    var s = new Session(Document.empty());
    s.replace("alpha beta gamma");
    s.select(0, 6, 10);
    s.addComment("Ada", "Delete this");
    var before = s.document();
    s.replace("");
    assertTrue(s.document().comments.isEmpty());
    s.undo();
    assertSame(before, s.document());
  }

  @Test
  public void repeatedTextUsesActualEditRangeInsteadOfAmbiguousTextDiff() {
    var s = new Session(Document.empty());
    s.replace("aaaa");
    s.select(0, 1, 3);
    s.addComment("Ada", "Middle pair");
    var id = s.document().comments.get(0).id;
    s.select(0, 0, 0);
    s.replace("a");
    s.selectComment(id);
    assertEquals(2, s.selection().start());
    assertEquals(4, s.selection().end());
    var clipboard = new Session(Document.empty());
    clipboard.replace("a");
    clipboard.select(0, 0, 1);
    s.select(0, 0, 0);
    s.paste(clipboard.copySelection());
    s.selectComment(id);
    assertEquals(3, s.selection().start());
    assertEquals(5, s.selection().end());
    s.select(0, 0, 1);
    s.replace("");
    s.selectComment(id);
    assertEquals(2, s.selection().start());
    assertEquals(4, s.selection().end());
  }

  @Test
  public void formattingDoesNotMoveCommentRange() {
    var s = new Session(Document.empty());
    s.replace("first\nsecond");
    s.selectRange(0, 2, 1, 4);
    s.addComment("Ada", "Across paragraphs");
    var id = s.document().comments.get(0).id;
    s.selectRange(0, 0, 1, 6);
    s.format("bold", "true");
    s.selectComment(id);
    assertEquals("rst\nseco", s.selectedText());
  }
}
