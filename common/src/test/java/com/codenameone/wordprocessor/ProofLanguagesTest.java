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

  @Test
  public void polishWeZe() {
    String t = "Spotkamy si\u0119 w wtorek w Wroc\u0142awiu z szko\u0142y z mn\u0105.";
    int n = 0;
    for (Proofing.Issue i : ProofLanguages.grammar("pl", t)) if (i.message().startsWith("Przed tym s\u0142owem")) n++;
    assertEquals(4, n);
    List<Proofing.Issue> z = ProofLanguages.grammar("pl", "Z szko\u0142y wracam.");
    assertEquals("Ze", z.get(0).suggestions().get(0));
    assertNone(ProofLanguages.grammar("pl", "By\u0142em w wodzie, w wieku dziesi\u0119ciu lat, z szaf\u0105, z siostr\u0105 i z zamkiem."), "Przed tym");
    assertNone(ProofLanguages.grammar("pl", "Wracam ze szko\u0142y we wtorek ze mn\u0105."), "Przed tym");
  }

  @Test
  public void twofoldSuffixesAndPrefixContinuation() {
    // walk -> walker (suffix R continues with S) -> walkers; un+walker via a prefix whose stem gets R.
    HunspellDict d = new HunspellDict(
        "SFX R Y 1\nSFX R 0 er/S .\nSFX S Y 1\nSFX S 0 s .\nPFX U Y 1\nPFX U 0 un/R .\n", "2\nwalk/RU\ntalk/S\n");
    assertTrue(d.check("walker"));
    assertTrue(d.check("walkers"));
    assertTrue(d.check("talks"));
    assertFalse(d.check("talkers"));
    assertFalse(d.check("walkss"));
    assertTrue(d.check("unwalk"));
  }

  @Test
  public void compounding() {
    HunspellDict d = new HunspellDict(
        "COMPOUNDFLAG X\nCOMPOUNDMIN 3\nONLYINCOMPOUND O\nCOMPOUNDWORDMAX 3\nSFX S Y 1\nSFX S 0 s .\n",
        "5\nfoot/X\nball/XS\nnet/X\ncat\nzz/X\nwerk/XO\n");
    assertTrue(d.check("football"));
    assertTrue(d.check("footballs"));
    assertTrue(d.check("footballnet"));
    assertFalse(d.check("footballnetfoot")); // COMPOUNDWORDMAX
    assertFalse(d.check("footcat")); // cat lacks the compound flag
    assertFalse(d.check("footzz")); // shorter than COMPOUNDMIN
    assertFalse(d.check("werk")); // ONLYINCOMPOUND
    assertTrue(d.check("footwerk"));
    HunspellDict be = new HunspellDict("COMPOUNDBEGIN B\nCOMPOUNDEND E\n", "2\nhaus/B\ntuer/E\n");
    assertTrue(be.check("haustuer"));
    assertFalse(be.check("tuerhaus"));
  }

  @Test
  public void iconvAndOconv() {
    // Typographic apostrophes are folded to ASCII on input; suggestions come out typographic.
    HunspellDict d = new HunspellDict("ICONV 1\nICONV \u2019 '\nOCONV 1\nOCONV ' \u2019\n", "1\ndon't\n");
    assertTrue(d.check("don\u2019t"));
    assertTrue(d.check("don't"));
    assertEquals("don\u2019t", d.suggest("dont'", 3).get(0));
  }

  @Test
  public void suggestionTuningRanksLikeWordcraft() {
    HunspellDict pl = ProofLanguages.dictionary("pl");
    // A missing diacritic (cost 4) outranks one typo (cost 15/16).
    assertEquals("mo\u017ce", pl.suggest("moze", 5).get(0));
    ProofLanguages.install("pl", PL_AFF, PL_DIC + "na\npewno\n");
    assertEquals("na pewno", ProofLanguages.dictionary("pl").suggest("napewno", 5).get(0));
    // Round 6: two typos away, found by the root scan.
    assertTrue(ProofLanguages.dictionary("pl").suggest("kobiyrta", 5).contains("kobieta"));
  }

  private static void assertHas(List<Proofing.Issue> l, String text) {
    for (Proofing.Issue i : l) if (i.message().indexOf(text) >= 0) return;
    fail("missing " + text + " in " + l);
  }

  private static void assertNone(List<Proofing.Issue> l, String text) {
    for (Proofing.Issue i : l) assertTrue(i.message(), i.message().indexOf(text) < 0);
  }
}
