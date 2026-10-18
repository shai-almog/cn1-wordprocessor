package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class DragMoveTest {
  @Test
  public void moveForwardWithinParagraphIsOneUndoStep() {
    var s = new Session(Document.empty());
    s.replace("alpha beta gamma");
    s.select(0, 0, 6);
    var before = s.document();
    s.moveSelectionTo(0, 11, s.copySelection());
    assertEquals("beta alpha gamma", s.document().plainText());
    s.undo();
    assertSame(before, s.document());
  }

  @Test
  public void moveBackwardAcrossParagraphsKeepsFormatting() {
    var s = new Session(Document.empty());
    s.replace("one\ntwo\nthree");
    s.select(2, 0, 5);
    s.format("bold", "true");
    s.moveSelectionTo(0, 0, s.copySelection());
    assertEquals("threeone\ntwo\n", s.document().plainText());
    assertTrue(s.document().paragraph(0).text.styleAt(0).bold);
    assertFalse(s.document().paragraph(0).text.styleAt(5).bold);
  }

  @Test
  public void moveMultiParagraphSelectionToLaterParagraph() {
    var s = new Session(Document.empty());
    s.replace("aa\nbb\ncc\ndd");
    s.selectRange(0, 1, 1, 1);
    s.moveSelectionTo(3, 1, s.copySelection());
    assertEquals("ab\ncc\nda\nbd", s.document().plainText());
  }
}
