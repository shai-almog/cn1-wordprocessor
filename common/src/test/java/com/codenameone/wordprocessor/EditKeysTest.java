package com.codenameone.wordprocessor;

import static com.codename1.ui.TextInputClient.*;
import static com.codenameone.wordprocessor.EditKeys.Move.*;
import static org.junit.Assert.*;

import org.junit.Test;

/** Navigation shortcuts resolve as in Codename One's editors on every port. */
public class EditKeysTest {
  private static void check(EditKeys.Move expected, int command, int modifiers, boolean rtl) {
    assertEquals(command + "/" + modifiers + "/" + rtl, expected, EditKeys.move(command, modifiers, rtl));
    // Shift only extends the selection; it never changes the move.
    assertEquals(expected, EditKeys.move(command, modifiers | MOD_SHIFT, rtl));
  }

  @Test
  public void plainArrowsMoveByCharacterAndLine() {
    check(CHAR_BACK, KEY_LEFT, 0, false);
    check(CHAR_FORWARD, KEY_RIGHT, 0, false);
    check(LINE_UP, KEY_UP, 0, false);
    check(LINE_DOWN, KEY_DOWN, 0, false);
    check(LINE_START, KEY_HOME, 0, false);
    check(LINE_END, KEY_END, 0, false);
    check(PAGE_UP, KEY_PAGE_UP, 0, false);
    check(PAGE_DOWN, KEY_PAGE_DOWN, 0, false);
  }

  @Test
  public void altMovesByWordAndParagraph() {
    check(WORD_BACK, KEY_LEFT, MOD_ALT, false);
    check(WORD_FORWARD, KEY_RIGHT, MOD_ALT, false);
    check(PARAGRAPH_UP, KEY_UP, MOD_ALT, false);
    check(PARAGRAPH_DOWN, KEY_DOWN, MOD_ALT, false);
    check(PARAGRAPH_START, KEY_HOME, MOD_ALT, false);
    check(PARAGRAPH_END, KEY_END, MOD_ALT, false);
  }

  @Test
  public void macOptionArrowWordCommands() {
    check(WORD_BACK, KEY_WORD_LEFT, 0, false);
    check(WORD_FORWARD, KEY_WORD_RIGHT, 0, false);
  }

  @Test
  public void ctrlOrCmdJumpsToLineAndDocumentEdges() {
    check(LINE_START, KEY_LEFT, MOD_CTRL, false);
    check(LINE_END, KEY_RIGHT, MOD_CTRL, false);
    check(DOCUMENT_START, KEY_UP, MOD_CTRL, false);
    check(DOCUMENT_END, KEY_DOWN, MOD_CTRL, false);
    check(DOCUMENT_START, KEY_HOME, MOD_CTRL, false);
    check(DOCUMENT_END, KEY_END, MOD_CTRL, false);
  }

  @Test
  public void rightToLeftParagraphSwapsHorizontalMoves() {
    check(CHAR_FORWARD, KEY_LEFT, 0, true);
    check(CHAR_BACK, KEY_RIGHT, 0, true);
    check(WORD_FORWARD, KEY_LEFT, MOD_ALT, true);
    check(WORD_BACK, KEY_WORD_RIGHT, 0, true);
    check(LINE_END, KEY_LEFT, MOD_CTRL, true);
    check(LINE_UP, KEY_UP, 0, true);
  }

  @Test
  public void editingKeysAreNotMoves() {
    check(NONE, KEY_BACKSPACE, 0, false);
    check(NONE, KEY_TAB, 0, false);
    assertTrue(EditKeys.wordDelete(MOD_ALT));
    assertTrue(EditKeys.wordDelete(MOD_CTRL));
    assertFalse(EditKeys.wordDelete(MOD_SHIFT));
  }
}
