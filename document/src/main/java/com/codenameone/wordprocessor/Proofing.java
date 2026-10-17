package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * English proofing: spelling with inflections, a user dictionary and ignore list (document settings
 * {@code proof.dictionary} and {@code proof.ignore}, comma lists), edit-distance suggestions and a
 * few cheap grammar checks (repeated words, a/an, sentence capital, space before punctuation,
 * doubled spaces). Mirrors the reference proofing crate.
 */
public final class Proofing {
  public static final String DICTIONARY = "proof.dictionary";
  public static final String IGNORE = "proof.ignore";

  private static final String COMMON =
      "a an the and or but if of to in on at by for with from as is am are was were be been being it its we us our you your he him his she her they them their i me my mine this that these those there here what which who whom whose when where why how all any both each few more most other some such no nor not only own same so than too very can will just should now do does did done has have had having may might must shall would could up down out over under again further then once off into onto upon about above below between through during before after until while because per via vs etc ok yes yeah oh ah hi hey bye one two three four five six seven eight nine ten first last next new old get got go goes went gone see saw seen say said make made take took come came give gave know knew think thought look want need use used find found tell told ask asked feel felt try left keep let put mean set run show turn move live play pay hear hold bring begin seem help talk start";
  private static Set<String> extra;

  private Proofing() {}

  /** A spelling or grammar finding in one paragraph's text (char offsets, end exclusive). */
  public static final class Issue {
    public final int start, end;
    public final String kind, message;
    public final List<String> suggestions;

    Issue(int start, int end, String kind, String message, List<String> suggestions) {
      this.start = start;
      this.end = end;
      this.kind = kind;
      this.message = message;
      this.suggestions = suggestions;
    }
  }

  /** User dictionary and ignore list (lowercase). */
  public static final class Options {
    public final Set<String> dictionary, ignore;

    public Options(Set<String> dictionary, Set<String> ignore) {
      this.dictionary = dictionary;
      this.ignore = ignore;
    }

    public static Options none() {
      return new Options(new HashSet<String>(), new HashSet<String>());
    }

    public static Options of(DocumentProperties p) {
      return new Options(list(p.setting(DICTIONARY, "")), list(p.setting(IGNORE, "")));
    }
  }

  public static Set<String> list(String commaList) {
    Set<String> out = new LinkedHashSet<String>();
    for (String w : TextUtil.split(commaList == null ? "" : commaList, ','))
      if (w.trim().length() > 0) out.add(w.trim().toLowerCase());
    return out;
  }

  public static String join(Set<String> words) {
    StringBuilder b = new StringBuilder();
    for (String w : words) b.append(b.length() == 0 ? "" : ",").append(w);
    return b.toString();
  }

  private static synchronized Set<String> extra() {
    if (extra == null) {
      extra = new HashSet<String>();
      for (String line : TextUtil.split(ProofData.text("/proof/en-extra.txt"), '\n')) {
        String w = line.trim();
        if (w.length() > 0 && !w.startsWith("#")) extra.add(w.toLowerCase());
      }
      for (String w : TextUtil.split(COMMON, ' ')) extra.add(w);
    }
    return extra;
  }

  private static boolean base(String w) {
    return WordList.get().contains(w) || extra().contains(w);
  }

  private static boolean known(String w, Options o) {
    if (base(w) || o.dictionary.contains(w) || o.ignore.contains(w)) return true;
    String[][] stems = {
      {"'s", ""}, {"s", ""}, {"es", "", "e"}, {"ies", "y"}, {"ed", "", "e"}, {"ied", "y"},
      {"ing", "", "e"}, {"ly", "", "le"}, {"ily", "y"}, {"er", "", "e"}, {"est", "", "e"}, {"ness", ""}
    };
    for (String[] row : stems) {
      String suf = row[0];
      if (!w.endsWith(suf) || w.length() - suf.length() < 2) continue;
      String stem = w.substring(0, w.length() - suf.length());
      for (int i = 1; i < row.length; i++) {
        String s = stem + row[i];
        if (base(s) || o.dictionary.contains(s)) return true;
      }
      int n = stem.length();
      if (("ing".equals(suf) || "ed".equals(suf) || "er".equals(suf) || "est".equals(suf))
          && n >= 3
          && stem.charAt(n - 1) == stem.charAt(n - 2)) {
        String s = stem.substring(0, n - 1);
        if (base(s) || o.dictionary.contains(s)) return true;
      }
    }
    String[] prefixes = {"un", "re", "non", "pre", "over", "under", "co", "mis"};
    for (String pre : prefixes)
      if (w.startsWith(pre) && w.length() - pre.length() >= 3) {
        String rest = w.substring(pre.length());
        if (base(rest) || o.dictionary.contains(rest)) return true;
      }
    return false;
  }

  private static String trimmed(String word) {
    int a = 0, b = word.length();
    while (a < b && !Character.isLetterOrDigit(word.charAt(a))) a++;
    while (b > a && !Character.isLetterOrDigit(word.charAt(b - 1)) && word.charAt(b - 1) != '\'') b--;
    String w = word.substring(a, b);
    while (w.startsWith("'")) w = w.substring(1);
    while (w.endsWith("'")) w = w.substring(0, w.length() - 1);
    return w;
  }

  /** Is the word spelled correctly (numbers, single letters and ALL-CAPS acronyms are accepted)? */
  public static boolean isCorrect(String word, Options o) {
    String w = trimmed(word);
    if (w.length() <= 1) return true;
    boolean letters = false, lower = false, digit = false;
    for (int i = 0; i < w.length(); i++) {
      char c = w.charAt(i);
      if (Character.isLetter(c)) letters = true;
      if (Character.isLowerCase(c)) lower = true;
      if (c >= '0' && c <= '9') digit = true;
    }
    if (!letters || digit || !lower) return true;
    String l = w.toLowerCase().replace('\u2019', '\'');
    if (known(l, o)) return true;
    if (l.indexOf('-') >= 0) {
      for (String part : TextUtil.split(l, '-')) if (part.length() > 0 && !known(part, o)) return false;
      return true;
    }
    return false;
  }

  private static boolean wordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '\'' || c == '\u2019' || c == '-';
  }

  /** Word spans (start, end) of the text, surrounding apostrophes and hyphens trimmed. */
  public static List<int[]> words(String text) {
    List<int[]> out = new ArrayList<int[]>();
    int i = 0;
    while (i < text.length()) {
      if (!wordChar(text.charAt(i))) {
        i++;
        continue;
      }
      int j = i;
      while (j < text.length() && wordChar(text.charAt(j))) j++;
      int a = i, b = j;
      while (a < b && "'\u2019-".indexOf(text.charAt(a)) >= 0) a++;
      while (b > a && "'\u2019-".indexOf(text.charAt(b - 1)) >= 0) b--;
      if (a < b) out.add(new int[] {a, b});
      i = j;
    }
    return out;
  }

  /** Spelling issues (without suggestions; ask {@link #suggest}) of a paragraph. */
  public static List<Issue> checkSpelling(String text, Options o) {
    List<Issue> out = new ArrayList<Issue>();
    for (int[] w : words(text)) {
      String word = text.substring(w[0], w[1]);
      int ts = w[0], te = w[1];
      while (ts > 0 && !Character.isWhitespace(text.charAt(ts - 1))) ts--;
      while (te < text.length() && !Character.isWhitespace(text.charAt(te))) te++;
      String token = text.substring(ts, te);
      if (token.indexOf("://") >= 0 || token.indexOf('@') >= 0 || token.startsWith("www.")) continue;
      if (!isCorrect(word, o))
        out.add(new Issue(w[0], w[1], "spelling", "Possible spelling mistake", new ArrayList<String>()));
    }
    return out;
  }

  /** Spelling plus grammar issues sorted by position. */
  public static List<Issue> check(String text, Options o) {
    List<Issue> out = checkSpelling(text, o);
    for (Issue i : out) i.suggestions.addAll(suggest(text.substring(i.start, i.end), 5));
    out.addAll(checkGrammar(text));
    Collections.sort(
        out,
        new Comparator<Issue>() {
          public int compare(Issue a, Issue b) {
            return a.start != b.start ? (a.start < b.start ? -1 : 1) : (a.end < b.end ? -1 : a.end == b.end ? 0 : 1);
          }
        });
    return out;
  }

  /** Words within two edits of the misspelling, nearest first. */
  public static List<String> suggest(String word, int max) {
    String lower = trimmed(word).toLowerCase().replace('\u2019', '\'');
    int n = lower.length();
    List<String> out = new ArrayList<String>();
    if (n == 0 || n > 40) return out;
    final List<Object[]> scored = new ArrayList<Object[]>();
    WordList d = WordList.get();
    int total = d.words.length;
    Set<String> ex = extra();
    java.util.Iterator<String> more = ex.iterator();
    for (int k = 0; k < total + ex.size(); k++) {
      String w = k < total ? d.words[k] : more.next();
      int wn = w.length();
      if (wn + 2 < n || wn > n + 2) continue;
      int dist = distance(lower, w);
      if (dist <= 2)
        scored.add(new Object[] {Integer.valueOf(dist), Integer.valueOf(w.charAt(0) == lower.charAt(0) ? 0 : 1), w});
    }
    final int len = n;
    Collections.sort(
        scored,
        new Comparator<Object[]>() {
          public int compare(Object[] a, Object[] b) {
            for (int i = 0; i < 2; i++) {
              int c = ((Integer) a[i]).compareTo((Integer) b[i]);
              if (c != 0) return c;
            }
            int la = Math.abs(((String) a[2]).length() - len), lb = Math.abs(((String) b[2]).length() - len);
            if (la != lb) return la < lb ? -1 : 1;
            return ((String) a[2]).compareTo((String) b[2]);
          }
        });
    boolean cap = Character.isUpperCase(word.charAt(0));
    boolean upper = n > 1;
    for (int i = 0; i < word.length(); i++) if (Character.isLowerCase(word.charAt(i))) upper = false;
    for (int i = 0; i < scored.size() && out.size() < max; i++) {
      String w = (String) scored.get(i)[2];
      if (upper) w = w.toUpperCase();
      else if (cap) w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
      if (!out.contains(w)) out.add(w);
    }
    return out;
  }

  /** Optimal string alignment distance, early exit at 3. */
  static int distance(String a, String b) {
    int n = a.length(), m = b.length();
    int[] prev2 = new int[m + 1], prev = new int[m + 1], cur = new int[m + 1];
    for (int j = 0; j <= m; j++) prev[j] = j;
    for (int i = 1; i <= n; i++) {
      cur[0] = i;
      int rowMin = i;
      for (int j = 1; j <= m; j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
        if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
          v = Math.min(v, prev2[j - 2] + 1);
        cur[j] = v;
        rowMin = Math.min(rowMin, v);
      }
      if (rowMin > 2) return 3;
      int[] t = prev2;
      prev2 = prev;
      prev = cur;
      cur = t;
    }
    return prev[m];
  }

  private static boolean consonantSound(String w) {
    String l = w.toLowerCase();
    String[] p = {"uni", "use", "usu", "eu", "one", "once", "ubiq", "uti", "ure", "uro"};
    for (String s : p) if (l.startsWith(s)) return true;
    return false;
  }

  private static Issue grammar(int a, int b, String message, String fix) {
    List<String> s = new ArrayList<String>();
    s.add(fix);
    return new Issue(a, b, "grammar", message, s);
  }

  /** Cheap grammar checks of a paragraph. */
  public static List<Issue> checkGrammar(String text) {
    List<Issue> v = new ArrayList<Issue>();
    List<int[]> ws = words(text);
    for (int k = 0; k + 1 < ws.size(); k++) {
      int[] x = ws.get(k), y = ws.get(k + 1);
      String w0 = text.substring(x[0], x[1]), w1 = text.substring(y[0], y[1]), gap = text.substring(x[1], y[0]);
      boolean letters0 = true;
      for (int i = 0; i < w0.length(); i++) if (!Character.isLetter(w0.charAt(i))) letters0 = false;
      String l0 = w0.toLowerCase();
      if (gap.trim().length() == 0 && w0.equalsIgnoreCase(w1) && letters0
          && !"had".equals(l0) && !"that".equals(l0) && !"bye".equals(l0) && !"so".equals(l0))
        v.add(grammar(x[0], y[1], "Repeated word: \"" + w1 + "\"", w0));
      char c1 = w1.charAt(0);
      if (" ".equals(gap) && ("a".equals(w0) || "A".equals(w0)) && "aeiouAEIOU".indexOf(c1) >= 0 && !consonantSound(w1))
        v.add(grammar(x[0], x[1], "Use \"an\" before a vowel sound", "A".equals(w0) ? "An" : "an"));
      boolean allUpper = true;
      for (int i = 0; i < w1.length(); i++) if (!Character.isUpperCase(w1.charAt(i))) allUpper = false;
      if (" ".equals(gap) && ("an".equals(w0) || "An".equals(w0)) && Character.isLetter(c1)
          && "aeiouAEIOUhH".indexOf(c1) < 0 && !allUpper)
        v.add(grammar(x[0], x[1], "Use \"a\" before a consonant sound", "An".equals(w0) ? "A" : "a"));
    }
    String[] spaced = {" ,", " .", " ;"};
    for (String pattern : spaced)
      for (int i = text.indexOf(pattern); i >= 0; i = text.indexOf(pattern, i + 1)) {
        if (i + 2 < text.length() && (text.charAt(i + 2) == '.' || text.charAt(i + 2) == ',')) continue;
        v.add(grammar(i, i + 2, "Remove the space before the punctuation", text.substring(i + 1, i + 2)));
      }
    for (int i = text.indexOf("  "); i >= 0; i = text.indexOf("  ", i + 1)) {
      if (i > 0 && text.charAt(i - 1) == ' ') continue;
      v.add(grammar(i, i + 2, "Extra space", " "));
    }
    boolean afterEnd = true;
    for (int[] w : ws) {
      String word = text.substring(w[0], w[1]);
      boolean alpha = true;
      for (int i = 0; i < word.length(); i++) if (!Character.isLetter(word.charAt(i))) alpha = false;
      if (afterEnd && alpha && Character.isLowerCase(word.charAt(0)))
        v.add(grammar(w[0], w[1], "Capitalize the first word of a sentence", Character.toUpperCase(word.charAt(0)) + word.substring(1)));
      int t = w[1];
      while (t < text.length() && "\"\u201d)'".indexOf(text.charAt(t)) >= 0) t++;
      afterEnd = false;
      if (t < text.length() && ".!?".indexOf(text.charAt(t)) >= 0 && !(text.startsWith("..", t))) {
        int u = t;
        while (u < text.length() && ".!?\"\u201d)".indexOf(text.charAt(u)) >= 0) u++;
        afterEnd = u < text.length() && text.charAt(u) == ' ';
      }
    }
    return v;
  }
}
