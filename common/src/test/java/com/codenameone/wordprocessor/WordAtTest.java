package com.codenameone.wordprocessor;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class WordAtTest {
  private static final String EMOJI = "\uD83D\uDE00", BOLD_A = "\uD835\uDC00";

  @Test
  public void anEmojiIsSelectedWhole() {
    String text = "a " + EMOJI + " b";
    assertArrayEquals(new int[] {2, 4}, TextNavigation.wordAt(text, 2));
    // An offset inside the pair still selects the whole character, never half of it.
    assertArrayEquals(new int[] {2, 4}, TextNavigation.wordAt(text, 3));
  }

  @Test
  public void emojiClustersAreSelectedWhole() {
    String flag = "\uD83C\uDDEF\uD83C\uDDF5";
    String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
    String heart = "\u2764\uFE0F";
    assertArrayEquals(new int[] {2, 6}, TextNavigation.wordAt("a " + flag + " b", 2));
    assertArrayEquals(new int[] {2, 6}, TextNavigation.wordAt("a " + flag + " b", 4));
    assertArrayEquals(new int[] {2, 10}, TextNavigation.wordAt("a " + family + " b", 2));
    assertArrayEquals(new int[] {2, 10}, TextNavigation.wordAt("a " + family + " b", 5));
    assertArrayEquals(new int[] {2, 4}, TextNavigation.wordAt("a " + heart + " b", 2));
  }

  @Test
  public void supplementaryLettersArePartOfTheWord() {
    assertArrayEquals(new int[] {0, 4}, TextNavigation.wordAt("x" + BOLD_A + "y", 1));
  }

  @Test
  public void doubleClickOnAnEmojiSelectsAndTypingReplacesIt() {
    var s = new Session(Document.empty());
    s.replace("a " + EMOJI + " b");
    int[] w = TextNavigation.wordAt(s.document().paragraph(0).text.toString(), 2);
    s.select(0, w[0], w[1]);
    s.replace("Z");
    org.junit.Assert.assertEquals("a Z b", s.document().plainText().trim());
  }
}
