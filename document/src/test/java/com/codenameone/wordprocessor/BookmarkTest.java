package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class BookmarkTest {
  @Test
  public void rangeFollowsEditsAndSplitAndUndo() {
    Session s = new Session(Document.empty());
    s.replace("alpha beta gamma");
    s.select(0, 6, 10);
    s.addBookmark("Spot");
    Document original = s.document();
    s.select(0, 0, 0);
    s.replace("A ");
    s.goToBookmark("Spot");
    assertEquals("beta", s.selectedText());
    s.select(0, 8, 8);
    s.enterParagraph();
    s.goToBookmark("Spot");
    assertEquals(1, s.selection().block);
    assertEquals("beta", s.selectedText());
    s.undo();
    s.undo();
    assertSame(original, s.document());
  }

  @Test
  public void replacementDeleteAndRenameExistingAreUndoable() {
    Session s = new Session(Document.empty());
    s.replace("first second");
    s.select(0, 0, 5);
    s.addBookmark("Spot");
    s.select(0, 6, 12);
    s.addBookmark("Spot");
    assertEquals(1, s.document().bookmarks.size());
    assertEquals("second", s.document().bookmarks.get("Spot").text(s.document()));
    s.replace("");
    assertTrue(s.document().bookmarks.isEmpty());
    s.undo();
    s.deleteBookmark("Spot");
    s.undo();
    s.goToBookmark("Spot");
    assertEquals("second", s.selectedText());
  }

  @Test
  public void repeatedTextAndCaseExpansionPreserveExactAnchors() {
    Session s = new Session(Document.empty());
    s.replace("aaaa ßeta");
    s.select(0, 1, 3);
    s.addBookmark("Middle");
    s.select(0, 5, 9);
    s.addBookmark("Word");
    s.select(0, 0, 0);
    s.replace("a");
    s.goToBookmark("Middle");
    assertEquals(2, s.selection().start());
    assertEquals(4, s.selection().end());
    s.select(0, 0, 10);
    s.changeCase("upper");
    assertEquals("SSETA", s.document().bookmarks.get("Word").text(s.document()));
    s.goToBookmark("Middle");
    assertEquals("AA", s.selectedText());
  }

  @Test
  public void pointAndMultipleParagraphRangeHaveNoVisibleMarkers() {
    Session s = new Session(Document.empty());
    s.replace("one\ntwo");
    s.selectRange(0, 1, 1, 2);
    s.addBookmark("Across");
    assertEquals("ne\ntw", s.document().bookmarks.get("Across").text(s.document()));
    s.select(1, 1, 1);
    s.addBookmark("Point");
    assertEquals("", s.document().bookmarks.get("Point").text(s.document()));
    assertEquals("one\ntwo", s.document().plainText());
    s.replace("X");
    s.goToBookmark("Point");
    assertEquals(2, s.selection().caret);
    assertTrue(s.selection().collapsed());
  }

  @Test
  public void invalidNamesAndOffsetsDoNotChangeDocument() {
    Session s = new Session(Document.empty());
    s.replace("hello");
    Document before = s.document();
    for (String name : new String[] {"", "1bad", "with space", "a-b"}) {
      try {
        s.addBookmark(name);
        fail();
      } catch (IllegalArgumentException expected) {
        assertSame(before, s.document());
      }
    }
    assertTrue(Bookmark.validName("_Ref1"));
    assertTrue(Bookmark.validName("שם"));
  }

  @Test
  public void trackedEditsInsideRangesSurviveAcceptAndReject() {
    for (boolean accept : new boolean[] {true, false}) {
      Session s = new Session(Document.empty());
      s.replace("alpha beta omega");
      s.select(0, 6, 10);
      s.addBookmark("Spot");
      s.trackChanges(true);
      s.select(0, 7, 9);
      s.replace("XX");
      s.resolveRevision(null, accept);
      s.goToBookmark("Spot");
      assertEquals(accept ? "bXXa" : "beta", s.selectedText());
    }
  }
}
