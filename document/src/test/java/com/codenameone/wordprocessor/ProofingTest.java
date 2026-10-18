package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class ProofingTest {
  private static final Proofing.Options NONE = Proofing.Options.none();

  @Test
  public void misspellingsAreFlaggedWithSuggestions() {
    long t = System.currentTimeMillis();
    assertFalse(Proofing.isCorrect("recieve", NONE));
    System.out.println("proofing first-use load ms: " + (System.currentTimeMillis() - t));
    assertTrue(Proofing.suggest("recieve", 5).contains("receive"));
    assertTrue(Proofing.suggest("Wrold", 3).contains("World"));
    var issues = Proofing.checkSpelling("Hello wrold", NONE);
    assertEquals(1, issues.size());
    assertEquals(6, issues.get(0).start);
  }

  @Test
  public void correctWordsAndInflections() {
    for (String w : new String[] {"hello", "running", "studios", "don't", "Thursday", "NASA", "2026", "co-operate", "unhappy", "stopped", "desktops", "autosaved"})
      assertTrue(w, Proofing.isCorrect(w, NONE));
    assertFalse(Proofing.isCorrect("sentense", NONE));
  }

  @Test
  public void userDictionaryAndIgnoreList() {
    var p = Document.empty().properties.withSetting(Proofing.DICTIONARY, "wordcraftz").withSetting(Proofing.IGNORE, "qzx");
    Proofing.Options o = Proofing.Options.of(p);
    assertTrue(Proofing.isCorrect("Wordcraftz", o));
    assertTrue(Proofing.isCorrect("wordcraftzes", o));
    assertTrue(Proofing.isCorrect("qzx", o));
    assertFalse(Proofing.isCorrect("wordcraftz", NONE));
  }

  @Test
  public void grammar() {
    var g = Proofing.checkGrammar("This is a apple. the the cat  sat , here");
    var all = new StringBuilder();
    for (Proofing.Issue i : g) all.append(i.message).append('|');
    var s = all.toString();
    assertTrue(s, s.indexOf("Use \"an\"") >= 0);
    assertTrue(s, s.indexOf("Repeated word") >= 0);
    assertTrue(s, s.indexOf("Capitalize") >= 0);
    assertTrue(s, s.indexOf("Extra space") >= 0);
    assertTrue(s, s.indexOf("Remove the space") >= 0);
  }

  @Test
  public void thesaurus() {
    assertTrue(Thesaurus.synonyms("Good").contains("excellent"));
    assertTrue(Thesaurus.synonyms("zzz").isEmpty());
  }

  @Test
  public void hyphenationPoints() {
    long t = System.currentTimeMillis();
    assertEquals("ty-pog-ra-phy", Hyphenator.hyphenate("typography"));
    System.out.println("hyphenator first-use load ms: " + (System.currentTimeMillis() - t));
    assertTrue(Hyphenator.hyphenate("information").indexOf('-') > 0);
    assertEquals("cat", Hyphenator.hyphenate("cat"));
    assertEquals("NASAUS", Hyphenator.hyphenate("NASAUS"));
    // Unknown word: patterns.
    assertTrue(Hyphenator.hyphenate("zorbulatification").indexOf('-') > 0);
  }
}
