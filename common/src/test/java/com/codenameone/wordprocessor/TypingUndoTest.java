package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import org.junit.Test;

public class TypingUndoTest {
  private long clock = 1000;
  private void type(Session s, String text) {
    for (int i = 0; i < text.length();) {
      int end = i + Character.charCount(Character.codePointAt(text, i));
      s.typeText(text.substring(i, end), clock += 20);
      i = end;
    }
  }

  @Test public void wordsUndoAsGroupsButEveryKeyAdvancesRecoveryRevision() {
    var s = new Session(Document.empty()); long revision = s.revision();
    type(s, "Hello world");
    assertEquals(revision + 11, s.revision());
    s.undo(); assertEquals("Hello ", s.document().plainText());
    s.undo(); assertEquals("", s.document().plainText());
    s.redo(); assertEquals("Hello ", s.document().plainText());
    s.redo(); assertEquals("Hello world", s.document().plainText());
  }

  @Test public void selectionReplacementAndFollowingCharactersAreOneUndo() {
    var s = new Session(Document.empty()); s.replace("before after");
    s.select(0, 6, 0); var original = s.document();
    type(s, "new"); assertEquals("new after", s.document().plainText());
    s.undo(); assertSame(original, s.document());
    assertEquals(6, s.selection().anchor); assertEquals(0, s.selection().caret);
  }

  @Test public void sameNativeSelectionEchoKeepsGroupAndInsertionStyle() {
    var s = new Session(Document.empty()); s.replace("start ");
    s.format("bold", "true");
    for (char c : "bold".toCharArray()) {
      var x = s.selection(); s.selectRange(x.anchorBlock, x.anchor, x.block, x.caret);
      s.typeText(String.valueOf(c), clock += 20);
      assertTrue(s.document().paragraph(0).text.styleAt(s.selection().caret - 1).bold);
    }
    s.undo(); assertEquals("start ", s.document().plainText());
  }

  @Test public void caretMovementPauseAndClockRollbackBreakGroups() {
    var s = new Session(Document.empty()); type(s, "ab");
    s.select(0, 1, 1); type(s, "X"); s.undo(); assertEquals("ab", s.document().plainText());
    s.select(0, 2, 2); type(s, "cd"); clock += 1600; type(s, "ef");
    s.undo(); assertEquals("abcd", s.document().plainText());
    s.redo(); clock = 0; type(s, "g");s.undo();assertEquals("abcdef",s.document().plainText());
  }

  @Test public void commandAndFormattingChangesBreakGroups() {
    var s = new Session(Document.empty()); type(s, "ab");
    s.format("bold", "true");type(s, "cd");
    s.undo();assertEquals("ab",s.document().plainText());
    s.redo();s.paragraph("align", "center");type(s, "ef");
    s.undo();assertEquals("abcd",s.document().plainText());
    assertEquals("center",s.document().paragraph(0).align);
  }

  @Test public void pasteCompositionAndParagraphBreakAreSeparateUndoSteps() {
    var s = new Session(Document.empty());type(s,"a");
    s.typeText("pasted",clock+=20);type(s,"b");
    s.undo();assertEquals("apasted",s.document().plainText());
    s.undo();assertEquals("a",s.document().plainText());
    s.compose("日本",2);s.finishComposition();type(s,"x");
    s.undo();assertEquals("a日本",s.document().plainText());
    s.undo();assertEquals("a",s.document().plainText());
    s.enterParagraph();type(s,"bc");s.undo();assertEquals("a\n",s.document().plainText());
    s.undo();assertEquals("a",s.document().plainText());
  }

  @Test public void focusBoundaryAndSingleCharacterPasteCanBeSeparatedExplicitly() {
    var s = new Session(Document.empty());type(s,"ab");s.breakTypingGroup();
    type(s,"X");s.breakTypingGroup();type(s,"cd");
    s.undo();assertEquals("abX",s.document().plainText());
    s.undo();assertEquals("ab",s.document().plainText());
  }

  @Test public void groupedTypingDoesNotEvictExtraEntriesAtTheHistoryLimit() {
    var s = new Session(Document.empty());
    for(int i=0;i<500;i++)s.replace("x");
    type(s,"abc");s.undo();assertEquals(500,s.document().plainText().length());
    for(int i=0;i<499;i++)s.undo();assertEquals("x",s.document().plainText());
    s.undo();assertEquals("x",s.document().plainText());
  }

  @Test public void longRunsHaveBoundedUndoGroupsAndEmojiStaysWhole() {
    var s = new Session(Document.empty());
    for(int i=0;i<129;i++)type(s,"😀");
    s.undo();assertEquals(256,s.document().plainText().length());
    s.undo();assertEquals("",s.document().plainText());
  }

  @Test public void cellTypingSharesOneUndoWithoutChangingAdjacentCells() {
    var s = new Session(Document.empty());s.insertTable(1,2);s.setCell(1,0,1,"keep");
    s.enterCell(1,0,0);type(s,"abc");s.undo();
    var table=(Table)s.document().blocks.get(1);
    assertEquals("",Table.cellText(table.cell(0,0)));assertEquals("keep",Table.cellText(table.cell(0,1)));
    s.redo();assertEquals("abc",Table.cellText(((Table)s.document().blocks.get(1)).cell(0,0)));
  }

  @Test public void trackedTypingGroupingPreservesReviewAndUndo() {
    var s=new Session(Document.empty());s.replace("original ");s.trackChanges(true);
    var before=s.document();type(s,"added");
    assertTrue(s.document().paragraph(0).text.styleAt(9).revision.inserted());
    s.undo();assertSame(before,s.document());s.redo();assertEquals("original added",s.document().plainText());
  }

  @Test public void newTypingAfterUndoDropsRedoWithoutMergingOlderHistory() {
    var s=new Session(Document.empty());type(s,"one two");s.undo();type(s,"new");
    s.redo();assertEquals("one new",s.document().plainText());
    s.undo();assertEquals("one ",s.document().plainText());
  }
  @Test public void authorChangesSeparateTrackedTyping() {
    var s=new Session(Document.empty());s.trackChanges(true);type(s,"ab");
    s.reviewAuthor("Second");type(s,"cd");s.undo();assertEquals("ab",s.document().plainText());
    s.redo();assertEquals("Second",s.document().paragraph(0).text.styleAt(3).revision.author);
  }

}
