package com.codenameone.wordprocessor;

import com.codename1.ui.TextInputClient;

/**
 * Maps a platform key command to the editor's navigation, following Codename One's editors:
 * Alt (Option) moves by word or paragraph, Ctrl (Cmd) jumps to the line edge horizontally and
 * the document edge vertically, and the spatial word commands macOS sends for Option+arrow
 * move by word.
 */
final class EditKeys {
  private EditKeys() {}

  /** Navigation targets {@link EditorSurface} implements. */
  enum Move {
    CHAR_BACK, CHAR_FORWARD, WORD_BACK, WORD_FORWARD, LINE_START, LINE_END, LINE_UP, LINE_DOWN,
    PARAGRAPH_UP, PARAGRAPH_DOWN, PARAGRAPH_START, PARAGRAPH_END, DOCUMENT_START, DOCUMENT_END,
    PAGE_UP, PAGE_DOWN, NONE
  }

  /**
   * The move for an arrow, Home/End or Page command; {@link Move#NONE} for anything else. In a
   * right-to-left paragraph the left and right arrows are spatial, so they swap logical
   * direction.
   */
  static Move move(int command, int modifiers, boolean rtl) {
    boolean ctrl = (modifiers & TextInputClient.MOD_CTRL) != 0;
    boolean alt = (modifiers & TextInputClient.MOD_ALT) != 0;
    switch (command) {
      case TextInputClient.KEY_WORD_LEFT:
        return rtl ? Move.WORD_FORWARD : Move.WORD_BACK;
      case TextInputClient.KEY_WORD_RIGHT:
        return rtl ? Move.WORD_BACK : Move.WORD_FORWARD;
      case TextInputClient.KEY_LEFT:
      case TextInputClient.KEY_RIGHT:
        {
          boolean back = (command == TextInputClient.KEY_LEFT) != rtl;
          if (ctrl) return back ? Move.LINE_START : Move.LINE_END;
          if (alt) return back ? Move.WORD_BACK : Move.WORD_FORWARD;
          return back ? Move.CHAR_BACK : Move.CHAR_FORWARD;
        }
      case TextInputClient.KEY_UP:
        return ctrl ? Move.DOCUMENT_START : alt ? Move.PARAGRAPH_UP : Move.LINE_UP;
      case TextInputClient.KEY_DOWN:
        return ctrl ? Move.DOCUMENT_END : alt ? Move.PARAGRAPH_DOWN : Move.LINE_DOWN;
      case TextInputClient.KEY_HOME:
        return ctrl ? Move.DOCUMENT_START : alt ? Move.PARAGRAPH_START : Move.LINE_START;
      case TextInputClient.KEY_END:
        return ctrl ? Move.DOCUMENT_END : alt ? Move.PARAGRAPH_END : Move.LINE_END;
      case TextInputClient.KEY_PAGE_UP:
        return Move.PAGE_UP;
      case TextInputClient.KEY_PAGE_DOWN:
        return Move.PAGE_DOWN;
      default:
        return Move.NONE;
    }
  }

  /** True when Backspace/Delete removes a whole word (Alt, or Ctrl as on Windows and Linux). */
  static boolean wordDelete(int modifiers) {
    return (modifiers & (TextInputClient.MOD_ALT | TextInputClient.MOD_CTRL)) != 0;
  }
}
