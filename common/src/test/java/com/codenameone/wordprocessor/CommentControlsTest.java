package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import org.junit.Test;

public class CommentControlsTest {
  private Session sample() {
    var s = new Session(Document.empty());
    s.replace("before");
    s.select(0, 6, 6); s.insertTable(2, 2); s.leaveCell(); s.select(2, 0, 0); s.replace("after");
    s.setCell(1, 0, 1, "first cell"); s.setCell(1, 1, 0, "last cell");
    // Deliberately create threads in a different order to their document positions.
    s.select(2, 1, 4); s.addComment("Ada", "After");
    s.enterCell(1, 1, 0); s.select(0, 1, 4); s.addComment("Grace", "Last");
    s.leaveCell(); s.select(0, 1, 3); s.addComment("Lin", "Before");
    s.enterCell(1, 0, 1); s.select(0, 2, 5); s.addComment("Sam", "First");
    return s;
  }
  @Test public void navigateUsesDocumentOrderAndWrapsAcrossCellStories() {
    var s = sample(); var d = s.document(); long revision = s.revision();
    s.leaveCell(); s.select(0, 0, 0);
    int[] order = {2, 3, 1, 0, 2};
    for (int index : order) {
      assertEquals(d.comments.get(index).id, s.navigateComment(false));
      assertTrue(s.selection().collapsed());
    }
    assertEquals(d.comments.get(0).id, s.navigateComment(true)); assertFalse(s.inCell());
    assertEquals(d.comments.get(1).id, s.navigateComment(true));
    assertEquals(1, s.cellRow()); assertEquals(0, s.cellColumn()); assertEquals(1, s.selection().caret);
    assertSame(d, s.document()); assertEquals(revision, s.revision());
  }
  @Test public void navigationUsesFocusIncludesResolvedAndDoesNotRequireComments() {
    var empty = new Session(Document.empty()); assertNull(empty.navigateComment(false));
    var s = sample(); var first = s.document().comments.get(2).id;
    s.updateComment(first, true, false); s.select(0, 5, 0);
    assertEquals(first, s.navigateComment(false));
    s.select(2, 4, 1); assertEquals(s.document().comments.get(1).id, s.navigateComment(true));
  }
  @Test public void editPreservesAuthorsRangeAndResolutionAndUndo() {
    var s = sample(); var original = s.document().comments.get(3);
    s.replyComment(original.id, "Reply author", "Old reply"); s.updateComment(original.id, true, false);
    var before = s.document(); s.selectComment(original.id);
    s.editComment(original.id, -1, "\nUpdated\n\ntext\n");
    var edited = s.document().comments.get(3);
    assertEquals(original.author, edited.author); assertEquals(original.startId, edited.startId);
    assertEquals(original.start, edited.start); assertEquals(original.end, edited.end); assertTrue(edited.resolved);
    assertEquals("\nUpdated\n\ntext\n", edited.text);
    s.editComment(original.id, 0, "Updated reply"); edited = s.document().comments.get(3);
    assertEquals("Reply author", edited.replies.get(0).author); assertEquals("Updated reply", edited.replies.get(0).text);
    assertTrue(edited.resolved); s.undo(); assertEquals("Old reply", s.document().comments.get(3).replies.get(0).text);
    s.undo(); assertSame(before, s.document()); s.redo(); assertEquals("\nUpdated\n\ntext\n", s.document().comments.get(3).text);
  }
  @Test public void invalidEditsLeaveCellSelectionDocumentAndRevisionAlone() {
    var s = sample(); var before = s.document(); var selection = s.selection(); long revision = s.revision();
    var id = before.comments.get(3).id;
    for (int index : new int[] {-2, 0, 99}) {
      try {s.editComment(id, index, "Text"); fail("invalid reply accepted");} catch (IllegalArgumentException expected) {}
    }
    try {s.editComment(id, -1, "  "); fail("empty text accepted");} catch (IllegalArgumentException expected) {}
    try {s.editComment("missing", -1, "Text"); fail("unknown id accepted");} catch (IllegalArgumentException expected) {}
    assertSame(before, s.document()); assertSame(selection, s.selection()); assertTrue(s.inCell()); assertEquals(revision, s.revision());
  }
}
