package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Polish/Portuguese proofing with tiny synthetic Hunspell dictionaries written for these tests. */
public class ProofLanguagesTest {
  private static final Proofing.Options NONE = Proofing.Options.none();

  static final String PL_AFF =
      "SET UTF-8\nTRY aioeznrwcysztkpdmljlub\nMAP 3\nMAP z\u017c\u017a\nMAP o\u00f3\nMAP l\u0142\n"
          + "REP 1\nREP rz \u017c\n"
          + "SFX A Y 1\nSFX A a ie a\n"
          + "SFX B Y 2\nSFX B y ego y\nSFX B y ej [^k]y\n"
          + "PFX N Y 1\nPFX N 0 nie .\n";
  static final String PL_DIC =
      "6\nkobieta/A\nWarszawa/A\n\u017c\u00f3\u0142w\nmi\u0142y/BN\nmo\u017ce\nma\u0142y/B\n";
  static final String PT_AFF = "SET ISO8859-1\nSFX S Y 1\nSFX S 0 s .\n";

  @Before
  public void install() {
    ProofLanguages.install("pl", PL_AFF, PL_DIC);
    // Latin-1 bytes, as many pt_BR dictionaries ship: "cora\u00e7\u00e3o/S".
    byte[] dic = {'2', '\n', 'c', 'a', 's', 'a', '/', 'S', '\n', 'c', 'o', 'r', 'a', (byte) 0xe7, (byte) 0xe3, 'o', '\n'};
    HunspellDict d = HunspellDict.fromBytes(PT_AFF.getBytes(), dic);
    assertTrue(d.check("cora\u00e7\u00e3o"));
    ProofLanguages.install("pt", PT_AFF, "2\ncasa/S\ncora\u00e7\u00e3o\n");
  }

  @After
  public void uninstall() {
    ProofLanguages.install("pl", null, null);
    ProofLanguages.install("pt", null, null);
  }

  @Test
  public void tagsResolveToProofingLanguages() {
    assertEquals("pl", ProofLanguages.of("pl-PL"));
    assertEquals("pt", ProofLanguages.of("pt_BR"));
    assertEquals("en", ProofLanguages.of(""));
    assertEquals("en", ProofLanguages.of("en-GB"));
    assertEquals("", ProofLanguages.of("fr-FR"));
  }

  @Test
  public void affixRulesAndCase() {
    HunspellDict d = ProofLanguages.dictionary("pl");
    assertTrue(d.check("kobieta"));
    assertTrue(d.check("kobiecie") == false); // strip "a", add "ie" gives "kobietie"
    assertTrue(d.check("kobietie"));
    assertTrue(d.check("mi\u0142ego"));
    assertTrue(d.check("niemi\u0142ego")); // cross product prefix + suffix
    assertTrue(d.check("mi\u0142ej"));
    assertTrue(d.check("Warszawie"));
    assertFalse(d.check("warszawa"));
    assertTrue(d.check("KOBIETA"));
    assertFalse(d.check("kobita"));
  }

  @Test
  public void polishSuggestionsRestoreDiacritics() {
    List<String> s = Proofing.suggest("zolw", 5, "pl-PL");
    assertEquals("\u017c\u00f3\u0142w", s.get(0));
    assertTrue(Proofing.suggest("morze", 5, "pl").contains("mo\u017ce"));
    assertTrue(Proofing.suggest("kobeta", 5, "pl").contains("kobieta"));
  }

  @Test
  public void eachRunIsCheckedInItsLanguage() {
    String text = "kobieta recieve casas bonjour";
    // pl for the first word, en for the second, pt for the third, fr for the last.
    Proofing.LangAt lang = off -> off < 8 ? "pl-PL" : off < 16 ? "" : off < 22 ? "pt-BR" : "fr-FR";
    List<Proofing.Issue> issues = Proofing.marks(text, NONE, lang);
    int spelling = 0;
    for (Proofing.Issue i : issues) if ("spelling".equals(i.kind())) spelling++;
    assertEquals(1, spelling);
    for (Proofing.Issue i : issues)
      if ("spelling".equals(i.kind())) assertEquals("recieve", text.substring(i.start(), i.end()));
    // Without a language map everything is English and the Polish word is flagged.
    assertTrue(Proofing.marks("kobieta", NONE).size() > 0);
    // A misspelled Polish word gets Polish suggestions through check().
    var c = Proofing.check("Zolw idzie.", NONE, off -> "pl");
    assertEquals("\u017b\u00f3\u0142w", c.get(0).suggestions().isEmpty() ? "" : capital(c.get(0).suggestions().get(0)));
  }

  private static String capital(String s) {
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  @Test
  public void noDictionaryMeansNoSpellingMarks() {
    ProofLanguages.install("pl", null, null);
    assertTrue(Proofing.marks("Zzzqx", NONE, off -> "pl").isEmpty());
  }

  @Test
  public void polishGrammar() {
    assertHas(ProofLanguages.grammar("pl", "Wiem \u017ce tak."), "Brak przecinka");
    assertNone(ProofLanguages.grammar("pl", "Wiem, \u017ce tak."), "Brak przecinka");
    assertNone(ProofLanguages.grammar("pl", "Mimo \u017ce tak."), "Brak przecinka");
    assertHas(ProofLanguages.grammar("pl", "On on idzie."), "Powt\u00f3rzone");
    assertNone(ProofLanguages.grammar("pl", "Tak tak, wiem."), "Powt\u00f3rzone");
    assertHas(ProofLanguages.grammar("pl", "Idzie. potem stoi."), "wielk\u0105");
    assertNone(ProofLanguages.grammar("pl", "Jest np. kot."), "wielk\u0105");
    assertHas(ProofLanguages.grammar("pl", "Tak ,nie"), "spacja przed");
    assertHas(ProofLanguages.grammar("pl", "Tak,, nie"), "Podw\u00f3jny");
  }

  @Test
  public void portugueseGrammar() {
    assertHas(ProofLanguages.grammar("pt", "Ele ele foi."), "repetida");
    assertNone(ProofLanguages.grammar("pt", "Sim sim, foi."), "repetida");
    assertHas(ProofLanguages.grammar("pt", "Foi. depois voltou."), "mai\u00fascula");
    assertNone(ProofLanguages.grammar("pt", "Veja a p\u00e1g. dez."), "mai\u00fascula");
    // English-only rules never apply.
    assertNone(ProofLanguages.grammar("pt", "Comi a ostra."), "an");
  }

  private static void assertHas(List<Proofing.Issue> l, String text) {
    for (Proofing.Issue i : l) if (i.message().indexOf(text) >= 0) return;
    fail("missing " + text + " in " + l);
  }

  private static void assertNone(List<Proofing.Issue> l, String text) {
    for (Proofing.Issue i : l) assertTrue(i.message(), i.message().indexOf(text) < 0);
  }
}
