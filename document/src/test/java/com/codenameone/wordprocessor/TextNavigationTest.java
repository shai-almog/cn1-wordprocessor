package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import org.junit.Test;

public class TextNavigationTest {
  private Session text(String value) {
    var session = new Session(Document.empty());
    session.replace(value);
    return session;
  }

  @Test public void wordsWhitespacePunctuationAndUnicodeAreDistinct() {
    var s = text("one  two, three\u00a0cafe\u0301 \uD83D\uDE00 end");
    int[] offsets = {5, 8, 10, 16, 22, 25, 28};
    int at = 0;
    for (int offset : offsets) {
      int[] next = TextNavigation.word(s.document(), 0, at, true);
      assertArrayEquals(new int[] {0, offset}, next);
      at = next[1];
    }
    int[] reverse = {25, 22, 16, 10, 8, 5, 0};
    for (int offset : reverse) {
      int[] next = TextNavigation.word(s.document(), 0, at, false);
      assertArrayEquals(new int[] {0, offset}, next);
      at = next[1];
    }
  }

  @Test public void paragraphAndBlankParagraphBoundariesAreReachable() {
    var s = text("first\n\nlast");
    assertArrayEquals(new int[] {1, 0}, TextNavigation.word(s.document(), 0, 5, true));
    assertArrayEquals(new int[] {2, 0}, TextNavigation.word(s.document(), 1, 0, true));
    assertArrayEquals(new int[] {1, 0}, TextNavigation.word(s.document(), 2, 0, false));
    assertArrayEquals(new int[] {0, 5}, TextNavigation.word(s.document(), 1, 0, false));
    assertArrayEquals(new int[] {0, 0}, TextNavigation.boundary(s.document(), false));
    assertArrayEquals(new int[] {2, 4}, TextNavigation.boundary(s.document(), true));
  }

  @Test public void deleteWordPreservesFormattingAndSupportsUndoRedo() {
    var s = text("one two three");
    s.select(0, 8, 13); s.format("bold", "true");
    s.select(0, 4, 4);
    var original = s.document();
    TextNavigation.deleteWord(s, true);
    assertEquals("one three", s.document().plainText());
    assertTrue(s.document().paragraph(0).text.styleAt(4).bold);
    s.undo(); assertSame(original, s.document());
    s.redo(); assertEquals("one three", s.document().plainText());
    s.select(0, 4, 4); TextNavigation.deleteWord(s, false);
    assertEquals("three", s.document().plainText());
  }

  @Test public void deletionUsesExactReversedSelection() {
    var s = text("one two\nthree four");
    s.selectRange(1, 5, 0, 4);
    TextNavigation.deleteWord(s, false);
    assertEquals("one  four", s.document().plainText());
    s.undo(); assertEquals("one two\nthree four", s.document().plainText());
  }

  @Test public void paragraphJoinCanBeUndone() {
    var s = text("one\ntwo");
    s.select(1, 0, 0);
    var original = s.document();
    TextNavigation.deleteWord(s, false);
    assertEquals("onetwo", s.document().plainText());
    s.undo(); assertSame(original, s.document());
  }

  @Test public void boundariesAndEmbeddedObjectsAreNotDeletedImplicitly() {
    var s = text("before"); s.insertTable(1, 1); s.leaveCell();
    s.select(0, 6, 6);
    var original = s.document();
    TextNavigation.deleteWord(s, true);
    assertSame(original, s.document());
    s.select(0, 0, 0); TextNavigation.deleteWord(s, false);
    assertSame(original, s.document());
  }

  @Test public void cellNavigationAndDeletionStayInsideTheCell() {
    var s = text("outside"); s.insertTable(1, 2);
    s.setCell(1, 0, 0, "one two"); s.setCell(1, 0, 1, "untouched");
    s.enterCell(1, 0, 0); s.select(0, 4, 4);
    assertArrayEquals(new int[] {0, 7}, TextNavigation.boundary(s.editingDocument(), true));
    TextNavigation.deleteWord(s, true);
    assertEquals("one ", s.editingDocument().plainText());
    assertEquals("outside", s.document().paragraph(0).text.toString());
    assertEquals("untouched", Table.cellText(((Table) s.document().blocks.get(1)).cell(0, 1)));
    s.undo(); assertEquals("one two", Table.cellText(((Table) s.document().blocks.get(1)).cell(0, 0)));
  }

  @Test public void repeatedTrackedDeletionAdvancesOverAlreadyDeletedWords() {
    var s = text("one two three"); s.trackChanges(true); s.select(0, 0, 0);
    TextNavigation.deleteWord(s, true);
    s.select(0, 0, 0); TextNavigation.deleteWord(s, true);
    assertFalse(s.document().paragraph(0).text.styleAt(0).revision.inserted());
    assertFalse(s.document().paragraph(0).text.styleAt(4).revision.inserted());
    assertNull(s.document().paragraph(0).text.styleAt(8).revision);
    s.select(0, 13, 13); TextNavigation.deleteWord(s, false);
    assertFalse(s.document().paragraph(0).text.styleAt(8).revision.inserted());
    s.undo(); assertNull(s.document().paragraph(0).text.styleAt(8).revision);
  }

  @Test public void uncasedScriptsCombiningMarksAndNonbreakingSpacesStayConsistent() {
    var s = text("\u4e2d\u6587\u202f\u05d0\u05b7\u05d1\u2007\u0915\u093f");
    assertArrayEquals(new int[] {0, 3}, TextNavigation.word(s.document(), 0, 0, true));
    assertArrayEquals(new int[] {0, 7}, TextNavigation.word(s.document(), 0, 3, true));
    assertArrayEquals(new int[] {0, 9}, TextNavigation.word(s.document(), 0, 7, true));
    assertArrayEquals(new int[] {0, 3}, TextNavigation.word(s.document(), 0, 6, false));
  }

  @Test public void supplementaryLettersAreOneWordAndSurrogatesStayWhole() {
    var s = text("\uD801\uDC00\uD801\uDC01 x");
    assertArrayEquals(new int[] {0, 5}, TextNavigation.word(s.document(), 0, 0, true));
    assertArrayEquals(new int[] {0, 0}, TextNavigation.word(s.document(), 0, 4, false));
    s.select(0, 0, 0); TextNavigation.deleteWord(s, true);
    assertEquals("x", s.document().plainText());
  }
  @Test public void paragraphShortcutsReachStartsAndClampAtStoryEdges() {
    var s = text("first\nsecond\nlast");
    assertArrayEquals(new int[] {1,0},TextNavigation.paragraph(s.document(),1,3,false));
    assertArrayEquals(new int[] {0,0},TextNavigation.paragraph(s.document(),1,0,false));
    assertArrayEquals(new int[] {2,0},TextNavigation.paragraph(s.document(),1,3,true));
    assertArrayEquals(new int[] {2,4},TextNavigation.paragraph(s.document(),2,0,true));
    assertArrayEquals(new int[] {0,0},TextNavigation.paragraph(s.document(),0,0,false));
  }

  @Test public void macParagraphEndsAdvanceOnlyAtTheCurrentEnd() {
    var s=new Session(Document.empty());s.replace("first\nsecond");
    assertArrayEquals(new int[]{0,5}, TextNavigation.paragraphEnd(s.document(),0,2));
    assertArrayEquals(new int[]{1,6}, TextNavigation.paragraphEnd(s.document(),0,5));
    assertArrayEquals(new int[]{1,6}, TextNavigation.paragraphEnd(s.document(),1,6));
    s.select(0,5,5);s.insertTable(1,1);
    assertArrayEquals(new int[]{0,5}, TextNavigation.paragraphEnd(s.document(),0,5));
  }

}
