package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class WordListTest {
  @Test
  public void packedWordsAreSortedAndEachFindsItself() {
    var d = WordList.get();
    assertTrue(d.size() > 150000);
    String previous = null;
    boolean accented = false;
    for (int k = 0; k < d.size(); k += 7) {
      String w = d.word(k);
      if (previous != null) assertTrue(previous + " < " + w, previous.compareTo(w) < 0);
      assertEquals(w, k, d.index(w));
      for (int i = 0; i < w.length(); i++) accented |= w.charAt(i) > 0x7f;
      previous = w;
    }
    assertTrue("Latin-1 entries decode", accented);
    assertEquals(-1, d.index("zzzznotaword"));
  }

  @Test
  public void hyphenationMasksSurvivePacking() {
    var d = WordList.get();
    int k = d.index("hyphenation");
    assertTrue(k >= 0);
    assertNotEquals(0L, d.masks[k]);
  }
}
