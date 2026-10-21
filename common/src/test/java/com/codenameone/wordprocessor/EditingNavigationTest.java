package com.codenameone.wordprocessor;

import static com.codenameone.wordprocessor.EditKeys.Move.*;
import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/**
 * Headless editing coverage: every logical move through Session + TextNavigation (mirroring
 * EditorSurface's key handling), word deletion with undo, IME composition sequences and the
 * double-click word helper.
 */
public class EditingNavigationTest {
  private static Session doc(String... paragraphs) {
    var s = new Session(Document.empty());
    for (int i = 0; i < paragraphs.length; i++) {
      if (i > 0) s.enterParagraph();
      s.replace(paragraphs[i]);
    }
    s.breakTypingGroup();
    return s;
  }

  /** Applies a logical move as EditorSurface does; Shift keeps the anchor. */
  private static void apply(Session s, EditKeys.Move move, boolean extend) {
    var x = s.selection();
    var d = s.editingDocument();
    int block = x.block, caret = x.caret;
    int[] t = null;
    switch (move) {
      case WORD_BACK -> t = TextNavigation.word(d, block, caret, false);
      case WORD_FORWARD -> t = TextNavigation.word(d, block, caret, true);
      case PARAGRAPH_UP -> t = TextNavigation.paragraph(d, block, caret, false);
      case PARAGRAPH_DOWN -> t = TextNavigation.paragraphEnd(d, block, caret);
      case PARAGRAPH_START -> t = new int[] {block, 0};
      case PARAGRAPH_END -> t = new int[] {block, d.paragraph(block).text.length()};
      case DOCUMENT_START -> t = TextNavigation.boundary(d, false);
      case DOCUMENT_END -> t = TextNavigation.boundary(d, true);
      case LINE_START, LINE_END -> {
        var line = lineAt(s, block, caret);
        t = new int[] {block, move == LINE_START ? line.start : line.end};
      }
      default -> fail("unsupported " + move);
    }
    if (extend) s.selectRange(x.anchorBlock, x.anchor, t[0], t[1]);
    else s.selectRange(t[0], t[1], t[0], t[1]);
  }

  private static LayoutEngine.Line lineAt(Session s, int block, int caret) {
    List<LayoutEngine.Line> lines =
        new LayoutEngine(new PdfMetrics()).layout(s.document(), 0).pages.get(0).allLines();
    LayoutEngine.Line found = null;
    for (var l : lines)
      if (l.block == block && l.row < 0 && caret >= l.start && caret <= l.end) {
        found = l;
        if (caret < l.end) break;
      }
    assertNotNull(found);
    return found;
  }

  private static void at(Session s, int block, int caret) {
    s.selectRange(block, caret, block, caret);
  }

  private static void expect(Session s, int anchorBlock, int anchor, int block, int caret) {
    var x = s.selection();
    assertEquals("anchorBlock", anchorBlock, x.anchorBlock);
    assertEquals("anchor", anchor, x.anchor);
    assertEquals("block", block, x.block);
    assertEquals("caret", caret, x.caret);
  }

  // "hello,  world!! x": punctuation, runs of spaces and a trailing one-letter word.
  private static final String[] TEXT = {"hello,  world!! x", "second", "third"};

  // {start block, start caret, move, end block, end caret}
  private static final Object[][] MOVES = {
    {0, 0, WORD_FORWARD, 0, 5},
    {0, 5, WORD_FORWARD, 0, 8},
    {0, 7, WORD_FORWARD, 0, 8},
    {0, 8, WORD_FORWARD, 0, 13},
    {0, 13, WORD_FORWARD, 0, 16},
    {0, 16, WORD_FORWARD, 0, 17},
    {0, 17, WORD_FORWARD, 1, 0},
    {2, 5, WORD_FORWARD, 2, 5},
    {0, 17, WORD_BACK, 0, 16},
    {0, 16, WORD_BACK, 0, 13},
    {0, 13, WORD_BACK, 0, 8},
    {0, 8, WORD_BACK, 0, 5},
    {0, 5, WORD_BACK, 0, 0},
    {0, 0, WORD_BACK, 0, 0},
    {1, 0, WORD_BACK, 0, 17},
    {1, 3, PARAGRAPH_UP, 1, 0},
    {1, 0, PARAGRAPH_UP, 0, 0},
    {0, 0, PARAGRAPH_UP, 0, 0},
    {0, 3, PARAGRAPH_DOWN, 0, 17},
    {0, 17, PARAGRAPH_DOWN, 1, 6},
    {2, 1, PARAGRAPH_DOWN, 2, 5},
    {1, 3, PARAGRAPH_START, 1, 0},
    {1, 3, PARAGRAPH_END, 1, 6},
    {1, 3, DOCUMENT_START, 0, 0},
    {1, 3, DOCUMENT_END, 2, 5},
  };

  @Test
  public void everyMoveCollapsesWithoutShift() {
    for (Object[] row : MOVES) {
      var s = doc(TEXT);
      at(s, (Integer) row[0], (Integer) row[1]);
      apply(s, (EditKeys.Move) row[2], false);
      String msg = java.util.Arrays.toString(row);
      assertEquals(msg, (int) (Integer) row[3], s.selection().block);
      assertEquals(msg, (int) (Integer) row[4], s.selection().caret);
      assertTrue(msg, s.selection().collapsed());
    }
  }

  @Test
  public void everyMoveKeepsAnchorWithShift() {
    for (Object[] row : MOVES) {
      var s = doc(TEXT);
      at(s, (Integer) row[0], (Integer) row[1]);
      apply(s, (EditKeys.Move) row[2], true);
      expect(s, (Integer) row[0], (Integer) row[1], (Integer) row[3], (Integer) row[4]);
    }
  }

  @Test
  public void shiftExtendsAcrossSuccessiveMoves() {
    var s = doc(TEXT);
    at(s, 0, 0);
    apply(s, WORD_FORWARD, true);
    apply(s, WORD_FORWARD, true);
    apply(s, PARAGRAPH_DOWN, true);
    expect(s, 0, 0, 0, 17);
    assertEquals(TEXT[0], s.selectedText());
    apply(s, DOCUMENT_END, true);
    expect(s, 0, 0, 2, 5);
  }

  @Test
  public void lineStartAndEndFollowWrappedLayoutLines() {
    var sb = new StringBuilder();
    for (int i = 0; i < 60; i++) sb.append("word").append(i).append(' ');
    var s = doc(sb.toString().trim());
    var lines = new LayoutEngine(new PdfMetrics()).layout(s.document(), 0).pages.get(0).allLines();
    LayoutEngine.Line second = null;
    int count = 0;
    for (var l : lines) if (l.block == 0 && ++count == 2) second = l;
    assertNotNull("paragraph must wrap", second);
    int mid = (second.start + second.end) / 2;
    at(s, 0, mid);
    apply(s, LINE_START, false);
    expect(s, 0, second.start, 0, second.start);
    at(s, 0, mid);
    apply(s, LINE_END, true);
    expect(s, 0, mid, 0, second.end);
    assertTrue(second.start > 0 && second.end < s.document().paragraph(0).text.length());
  }

  // ---- word deletion ----

  private static void wordDelete(Session s, boolean forward) {
    TextNavigation.deleteWord(s, forward);
  }

  @Test
  public void wordDeletionTableWithUndo() {
    // {block, caret, forward, expected plain text, expected block, expected caret}
    Object[][] cases = {
      {0, 14, false, "one two   \nfour", 0, 10},
      {0, 10, false, "one four\nfour", 0, 4},
      {0, 0, true, "two   four\nfour", 0, 0},
      {0, 4, true, "one four\nfour", 0, 4},
      {1, 0, false, "one two   fourfour", 0, 14},
      {0, 14, true, "one two   fourfour", 0, 14},
      {0, 0, false, "one two   four\nfour", 0, 0},
      {1, 4, true, "one two   four\nfour", 1, 4},
    };
    for (Object[] c : cases) {
      var s = doc("one two   four", "four");
      String before = s.document().plainText();
      at(s, (Integer) c[0], (Integer) c[1]);
      wordDelete(s, (Boolean) c[2]);
      String msg = java.util.Arrays.toString(c);
      assertEquals(msg, c[3], s.document().plainText());
      assertEquals(msg, (int) (Integer) c[4], s.selection().block);
      assertEquals(msg, (int) (Integer) c[5], s.selection().caret);
      if (!before.equals(s.document().plainText())) {
        s.undo();
        assertEquals(msg, before, s.document().plainText());
      }
    }
  }

  @Test
  public void wordDeletionWithSelectionDeletesSelection() {
    var s = doc("alpha beta gamma");
    s.select(0, 2, 8);
    wordDelete(s, false);
    assertEquals("alta gamma", s.document().plainText());
    s.undo();
    assertEquals("alpha beta gamma", s.document().plainText());
  }

  @Test
  public void editKeysWordDeleteModifiers() {
    assertTrue(EditKeys.wordDelete(com.codename1.ui.TextInputClient.MOD_ALT));
    assertTrue(EditKeys.wordDelete(com.codename1.ui.TextInputClient.MOD_CTRL));
    assertFalse(EditKeys.wordDelete(0));
    assertFalse(EditKeys.wordDelete(com.codename1.ui.TextInputClient.MOD_SHIFT));
  }

  // ---- IME composition ----

  @Test
  public void perKeystrokeComposeCommitKeepsOrder() {
    var s = doc("ab");
    at(s, 0, 1);
    for (String ch : new String[] {"x", "y", "z"}) {
      s.compose(ch, 1);
      s.finishComposition();
    }
    assertEquals("axyzb", s.document().plainText());
    assertEquals(4, s.selection().caret);
  }

  @Test
  public void composingRegionIsReplacedNotAppended() {
    var s = doc("say ");
    at(s, 0, 4);
    s.compose("ni", 2);
    s.compose("nih", 3);
    s.compose("\u4f60\u597d", 2);
    assertEquals("say \u4f60\u597d", s.document().plainText());
    assertEquals(6, s.selection().caret);
    s.finishComposition();
    s.undo();
    assertEquals("say ", s.document().plainText());
  }

  @Test
  public void composeOverSelectionReplacesIt() {
    var s = doc("one two three");
    s.select(0, 4, 7);
    s.compose("2", 1);
    s.finishComposition();
    assertEquals("one 2 three", s.document().plainText());
    assertEquals(5, s.selection().caret);
  }

  @Test
  public void cancelCompositionRestoresTextAndCaret() {
    var s = doc("hello");
    at(s, 0, 2);
    s.compose("abc", 3);
    assertEquals("heabcllo", s.document().plainText());
    s.cancelComposition();
    assertEquals("hello", s.document().plainText());
    assertEquals(2, s.selection().caret);
    assertFalse(s.canUndo() && "heabcllo".equals(s.document().plainText()));
  }

  @Test
  public void relativeCaretIsClampedInsideComposition() {
    var s = doc("");
    s.compose("abc", 99);
    assertEquals(3, s.selection().caret);
    s.compose("abc", -5);
    assertEquals(0, s.selection().caret);
    s.compose("abc", 1);
    assertEquals(1, s.selection().caret);
  }

  @Test
  public void compositionInRightToLeftTextIsLogical() {
    String hebrew = "\u05e9\u05dc\u05d5\u05dd";
    var s = doc(hebrew);
    at(s, 0, 2);
    String typed = "\u05d0\u05d1";
    for (int i = 1; i <= typed.length(); i++) s.compose(typed.substring(0, i), i);
    s.finishComposition();
    assertEquals("\u05e9\u05dc" + typed + "\u05d5\u05dd", s.document().plainText());
    assertEquals(4, s.selection().caret);
  }

  @Test
  public void compositionRejectsParagraphBreaks() {
    var s = doc("x");
    try {
      s.compose("a\nb", 3);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  // ---- multi-click word selection ----

  @Test
  public void doubleClickWordTable() {
    // {text, offset, start, end}
    Object[][] cases = {
      {"hello world", 0, 0, 5},
      {"hello world", 3, 0, 5},
      {"hello world", 5, 0, 5},
      {"hello world", 6, 6, 11},
      {"hello world", 11, 6, 11},
      {"a, b", 1, 0, 1},
      {"a,  b", 2, 2, 3},
      {"x...y", 2, 2, 3},
      {"", 0, 0, 0},
      {"abc123 d", 4, 0, 6},
      {"cafe\u0301 x", 5, 0, 5},
      {"\u05e9\u05dc\u05d5\u05dd \u05e2", 2, 0, 4},
      {"hi", 50, 0, 2},
    };
    for (Object[] c : cases) {
      int[] r = TextNavigation.wordAt((String) c[0], (Integer) c[1]);
      String msg = java.util.Arrays.toString(c);
      assertEquals(msg, (int) (Integer) c[2], r[0]);
      assertEquals(msg, (int) (Integer) c[3], r[1]);
    }
  }
}
