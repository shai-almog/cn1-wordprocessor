package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proofing languages, matching Wordcraft's set: English (the bundled word list), Polish and
 * Portuguese (Hunspell dictionaries installed on the system or supplied with {@link #install},
 * never bundled, plus per-language grammar rules), and everything else unproofed (no marks, so
 * French text is not flagged as English misspellings). A run's language is its w:lang tag
 * ({@code CharacterFormat.lang}); only the primary subtag counts and an empty tag is English.
 */
public final class ProofLanguages {
  public static final String EN = "en", PL = "pl", PT = "pt", NONE = "";

  /** Languages with spelling and grammar checks, in display order. */
  public static final List<String> PROOFED = Arrays.asList(EN, PL, PT);

  private static final Map<String, HunspellDict> DICTS = new HashMap<>();
  private static final Set<String> SEARCHED = new HashSet<>();
  private static String dictionaryDir;

  private ProofLanguages() {}

  /** The proofing language of a BCP 47 tag ("pl-PL", "pt_BR", "PL"): en, pl, pt or "" (unproofed). */
  public static String of(String tag) {
    if (tag == null) return EN;
    String t = tag.trim().toLowerCase();
    if (t.length() == 0) return EN;
    int cut = t.length();
    for (int i = 0; i < t.length(); i++)
      if (t.charAt(i) == '-' || t.charAt(i) == '_') {
        cut = i;
        break;
      }
    String p = t.substring(0, cut);
    if ("en".equals(p) || "eng".equals(p)) return EN;
    if ("pl".equals(p) || "pol".equals(p)) return PL;
    if ("pt".equals(p) || "por".equals(p)) return PT;
    return NONE;
  }

  /** Installs (or with null aff, removes) the dictionary of a language from .aff/.dic text. */
  public static synchronized void install(String lang, String aff, String dic) {
    if (aff == null) DICTS.remove(lang);
    else DICTS.put(lang, new HunspellDict(aff, dic));
    SEARCHED.add(lang);
  }

  /** A directory searched before the system locations (like WORDCRAFT_DICTIONARIES). */
  public static synchronized void setDictionaryDir(String dir) {
    dictionaryDir = dir;
    SEARCHED.clear();
  }

  /** The language's dictionary, read from the system on first use; null when none is installed. */
  public static synchronized HunspellDict dictionary(String lang) {
    if (!PL.equals(lang) && !PT.equals(lang)) return null;
    if (!SEARCHED.contains(lang)) {
      SEARCHED.add(lang);
      HunspellDict d = findInstalled(lang);
      if (d != null) DICTS.put(lang, d);
    }
    return DICTS.get(lang);
  }

  public static boolean hasDictionary(String lang) {
    return EN.equals(lang) || dictionary(lang) != null;
  }

  private static List<String> searchDirs() {
    var dirs = new ArrayList<String>();
    if (dictionaryDir != null) dirs.add(dictionaryDir);
    String home = System.getProperty("user.home");
    if (home != null) {
      dirs.add(home + "/Library/Spelling");
      dirs.add(home + "/.local/share/hunspell");
    }
    dirs.addAll(Arrays.asList(
        "/Library/Spelling", "/opt/homebrew/share/hunspell", "/usr/local/share/hunspell",
        "/usr/share/hunspell", "/usr/share/myspell", "/usr/share/myspell/dicts",
        "/usr/local/share/myspell"));
    return dirs;
  }

  private static HunspellDict findInstalled(String lang) {
    String[] names = PL.equals(lang) ? new String[] {"pl_PL", "pl"} : new String[] {"pt_BR", "pt_PT", "pt"};
    try {
      var fs = com.codename1.io.FileSystemStorage.getInstance();
      for (String dir : searchDirs())
        for (String n : names) {
          String aff = dir + "/" + n + ".aff", dic = dir + "/" + n + ".dic";
          if (fs.exists(aff) && fs.exists(dic)) return HunspellDict.fromBytes(read(fs, aff), read(fs, dic));
        }
    } catch (Throwable t) {
      // No file system (web, or tests without a platform): the language has no spelling check.
    }
    return null;
  }

  private static byte[] read(com.codename1.io.FileSystemStorage fs, String path) throws java.io.IOException {
    try (var in = fs.openInputStream(path)) {
      var out = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[65536];
      for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
      return out.toByteArray();
    }
  }

  // ---------------------------------------------------------------- grammar (Polish, Portuguese)

  private static final Set<String> PL_REPEATS = set(
      "to tak nie no ha he hi hej oj aj ej ach och ech puk hop bum cyk tik pa fiu la bardzo dawno daleko d\u0142ugo");
  private static final Set<String> PT_REPEATS = set(
      "sim n\u00e3o ora ha he hi bem pois muito l\u00e1 j\u00e1 tic tac bl\u00e1 ai oh");
  private static final Set<String> PL_ABBREV = set(
      "np itd itp tzn tj tzw ok ul al pl os in wg zob por str ss nr pkt ust art par rozdz tab rys il przyp red wyd"
          + " oprac t\u0142um cyt jw ds im ks \u015bw b\u0142 prof dr doc hab in\u017c mgr lek p\u0142k gen mjr kpt"
          + " godz min sek tys mln mld z\u0142 gr kg mies tyg pon wt \u015br czw pt sob niedz ang niem \u0142ac"
          + " ros lit dos\u0142 fot tel kom ew ur ca vs etc cdn dot");
  private static final Set<String> PT_ABBREV = set(
      "etc ex p\u00e1g p\u00e1gs s\u00e9c fl av vol p pp ed tel min max art par prof profa dr dra sr sra srta eng"
          + " arq op cit cf ibid id vide seg ref cod dept univ inst lat esp fr it al obs att");
  private static final Set<String> PL_OPENERS = set(
      "\u017ce poniewa\u017c gdy\u017c aby \u017ceby je\u015bli je\u017celi gdyby lecz kt\u00f3ry kt\u00f3ra"
          + " kt\u00f3re kt\u00f3rego kt\u00f3rej kt\u00f3remu kt\u00f3rym kt\u00f3rych kt\u00f3rymi kt\u00f3rzy kt\u00f3r\u0105");
  private static final Set<String> PL_NO_COMMA_AFTER = set(
      "i a oraz lub albo czy ani mimo chyba tak tylko zw\u0142aszcza nawet w we z ze do od na o po przy za"
          + " przez dla bez u nad pod przed mi\u0119dzy ku jak to by\u0107");

  private static Set<String> set(String words) {
    var s = new HashSet<String>();
    for (String w : TextUtil.split(words, ' ')) if (w.length() > 0) s.add(w);
    return s;
  }

  private static Proofing.Issue issue(int a, int b, String message, String fix) {
    var s = new ArrayList<String>();
    s.add(fix);
    return new Proofing.Issue(a, b, "grammar", message, s);
  }

  private static boolean alpha(String w) {
    for (int i = 0; i < w.length(); i++) if (!Unicode.letter(w.charAt(i))) return false;
    return w.length() > 0;
  }

  /** Grammar checks of Polish or Portuguese text (messages in that language). */
  public static List<Proofing.Issue> grammar(String lang, String text) {
    boolean pl = PL.equals(lang);
    var v = new ArrayList<Proofing.Issue>();
    var ws = Proofing.words(text);
    for (int k = 0; k + 1 < ws.size(); k++) {
      int[] x = ws.get(k), y = ws.get(k + 1);
      String w0 = text.substring(x[0], x[1]), w1 = text.substring(y[0], y[1]), gap = text.substring(x[1], y[0]);
      String l0 = w0.toLowerCase(), l1 = w1.toLowerCase();
      if (gap.trim().length() == 0 && l0.equals(l1) && alpha(w0) && !(pl ? PL_REPEATS : PT_REPEATS).contains(l0))
        v.add(issue(x[0], y[1], pl ? "Powt\u00f3rzone s\u0142owo: \u201e" + w1 + "\u201d" : "Palavra repetida: \u201c" + w1 + "\u201d", w0));
      if (pl && " ".equals(gap) && PL_OPENERS.contains(l1) && alpha(w0) && !PL_NO_COMMA_AFTER.contains(l0))
        v.add(issue(x[1], y[0], "Brak przecinka przed \u201e" + w1 + "\u201d", ", "));
    }
    for (String pattern : new String[] {" ,", " .", " ;", " :", " !", " ?"})
      for (int i = text.indexOf(pattern); i >= 0; i = text.indexOf(pattern, i + 1)) {
        int n = i + 2;
        if (n < text.length() && ".,)(".indexOf(text.charAt(n)) >= 0) continue; // ellipsis, emoticon
        if (pattern.charAt(1) == ':' && n < text.length() && text.charAt(n) != ' ') continue;
        v.add(issue(i, n, pl ? "Zb\u0119dna spacja przed znakiem interpunkcyjnym" : "Espa\u00e7o antes da pontua\u00e7\u00e3o", text.substring(i + 1, n)));
      }
    for (int i = text.indexOf("  "); i >= 0; i = text.indexOf("  ", i + 1)) {
      if (i > 0 && text.charAt(i - 1) == ' ') continue;
      v.add(issue(i, i + 2, pl ? "Podw\u00f3jna spacja" : "Espa\u00e7o duplo", " "));
    }
    for (String d : new String[] {",,", ";;", ".."}) {
      for (int i = text.indexOf(d); i >= 0; i = text.indexOf(d, i + 2)) {
        if (d.equals("..") && (i + 2 < text.length() && text.charAt(i + 2) == '.' || i > 0 && text.charAt(i - 1) == '.')) continue;
        v.add(issue(i, i + 2, pl ? "Podw\u00f3jny znak interpunkcyjny" : "Pontua\u00e7\u00e3o duplicada", d.substring(0, 1)));
      }
    }
    Set<String> abbrev = pl ? PL_ABBREV : PT_ABBREV;
    boolean afterEnd = true;
    for (int[] w : ws) {
      String word = text.substring(w[0], w[1]);
      if (afterEnd && alpha(word) && Character.isLowerCase(word.charAt(0)) && !dialogue(text, w[0]))
        v.add(issue(w[0], w[1], pl ? "Zdanie powinno zaczyna\u0107 si\u0119 wielk\u0105 liter\u0105" : "A frase deve come\u00e7ar com mai\u00fascula",
            Character.toUpperCase(word.charAt(0)) + word.substring(1)));
      int t = w[1];
      while (t < text.length() && "\"\u201d\u00bb)'".indexOf(text.charAt(t)) >= 0) t++;
      afterEnd = false;
      if (t < text.length() && ".!?".indexOf(text.charAt(t)) >= 0 && !text.startsWith("..", t)) {
        boolean abbr = text.charAt(t) == '.' && (abbrev.contains(word.toLowerCase()) || word.length() == 1 || isDigits(word));
        int u = t;
        while (u < text.length() && ".!?\"\u201d\u00bb)".indexOf(text.charAt(u)) >= 0) u++;
        afterEnd = !abbr && u < text.length() && text.charAt(u) == ' ';
      }
    }
    return v;
  }

  private static boolean isDigits(String w) {
    for (int i = 0; i < w.length(); i++) if (w.charAt(i) < '0' || w.charAt(i) > '9') return false;
    return true;
  }

  /** After a dialogue dash ("- Tak? - zapyta\u0142") lower case is correct. */
  private static boolean dialogue(String text, int at) {
    int i = at - 1;
    while (i >= 0 && text.charAt(i) == ' ') i--;
    return i >= 0 && "-\u2013\u2014".indexOf(text.charAt(i)) >= 0;
  }
}
