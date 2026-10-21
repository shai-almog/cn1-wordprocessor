package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

/** Replays an Android IME (Gboard-like) composing sequence; the caret must stay at the end. */
public class AndroidImeCaretTest {
  @Test
  public void typingWordsByComposingLandsInOrder() {
    var s = new Session(Document.empty());
    String typed = "the quick brown fox";
    for (String word : typed.split(" ", -1)) {
      if (s.document().plainText().length() > 0) s.typeText(" ", 0);
      for (int i = 1; i <= word.length(); i++) {
        String part = word.substring(0, i);
        // EditorSurface composes with the caret at the end, whatever the platform's relative caret
        s.compose(part, part.length());
        assertEquals(s.document().plainText().length(), s.selection().caret);
      }
      s.finishComposition();
    }
    assertEquals(typed, s.document().plainText());
    assertEquals(typed.length(), s.selection().caret);
  }
}
