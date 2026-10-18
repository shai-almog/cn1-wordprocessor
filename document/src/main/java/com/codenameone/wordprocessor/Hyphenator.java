package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * English hyphenation: dictionary break points first (Moby Hyphenator II, with inflections that
 * inherit the stem's breaks), then Liang patterns, as in the reference proofing crate. Points are
 * char indices into the word; a hyphen goes before that char.
 */
public final class Hyphenator {
  private static Map<String, int[]> patterns;
  private static int maxPattern;

  private Hyphenator() {}

  private static synchronized Map<String, int[]> patterns() {
    if (patterns != null) return patterns;
    var map = new HashMap<String, int[]>(1 << 16);
    String text = ProofData.text("/proof/en-us.pat");
    int max = 0;
    for (String line : TextUtil.split(text, '\n')) {
      if (line.startsWith("%") || line.startsWith("#")) continue;
      for (String token : TextUtil.split(line, ' ')) {
        token = token.trim();
        if (token.length() == 0 || token.indexOf('-') >= 0 || token.charAt(0) == '\\' || token.charAt(0) == '{' || token.charAt(0) == '}') continue;
        var letters = new StringBuilder();
        int[] vals = new int[token.length() + 1];
        for (int i = 0; i < token.length(); i++) {
          char c = token.charAt(i);
          if (c >= '0' && c <= '9') vals[letters.length()] = c - '0';
          else letters.append(c);
        }
        if (letters.length() == 0) continue;
        map.put(letters.toString(), java.util.Arrays.copyOf(vals, letters.length() + 1));
        max = Math.max(max, letters.length());
      }
    }
    maxPattern = max;
    patterns = map;
    return map;
  }

  private static String normalize(String word) {
    var b = new StringBuilder(word.length());
    for (int i = 0; i < word.length(); i++) {
      char c = word.charAt(i);
      b.append(c == '\u2019' || c == '\u02bc' ? '\'' : Character.toLowerCase(c));
    }
    return b.toString();
  }

  /** Unrestricted break points of a lowercase word of letters and apostrophes. */
  static int[] rawPoints(String core) {
    int n = core.length();
    if (n < 2) return new int[0];
    WordList dict = WordList.get();
    int at = dict.index(core);
    if (at >= 0) return fromMask(dict.masks[at], n);
    String[] suffixes = {"'s", "s", "es", "ed", "d"};
    for (String suffix : suffixes)
      if (core.endsWith(suffix) && n - suffix.length() >= 2) {
        var stem = core.substring(0, n - suffix.length());
        int s = dict.index(stem);
        if (s >= 0) return fromMask(dict.masks[s], stem.length());
      }
    Map<String, int[]> map = patterns();
    if (map.isEmpty()) return new int[0];
    var padded = "." + core + ".";
    int[] values = new int[padded.length() + 1];
    for (int s = 0; s < padded.length(); s++)
      for (int len = 1; len <= maxPattern && s + len <= padded.length(); len++) {
        var v = map.get(padded.substring(s, s + len));
        if (v != null) for (int k = 0; k < v.length; k++) if (v[k] > values[s + k]) values[s + k] = v[k];
      }
    var out = new ArrayList<Integer>();
    for (int i = 1; i < n; i++) if (values[i + 1] % 2 == 1) out.add(Integer.valueOf(i));
    return toArray(out);
  }

  private static int[] fromMask(long mask, int len) {
    var out = new ArrayList<Integer>();
    for (int i = 1; i < Math.min(len, 64); i++) if ((mask & (1L << i)) != 0) out.add(Integer.valueOf(i));
    return toArray(out);
  }

  private static int[] toArray(List<Integer> l) {
    int[] r = new int[l.size()];
    for (int i = 0; i < r.length; i++) r[i] = l.get(i).intValue();
    return r;
  }

  /**
   * Allowed hyphenation points of one word (letters and apostrophes), with the default limits: at
   * least 5 letters, 2 before and 2 after a break. All-capital words are skipped.
   */
  public static int[] points(String word) {
    return points(word, 5, 2, 2);
  }

  public static int[] points(String word, int minWord, int afterFirst, int beforeLast) {
    int n = word.length();
    if (n < Math.max(2, minWord)) return new int[0];
    boolean allUpper = n > 1;
    for (int i = 0; i < n; i++) {
      char c = word.charAt(i);
      if (!Character.isLetter(c) && c != '\'' && c != '\u2019') return new int[0];
      if (!Character.isUpperCase(c)) allUpper = false;
    }
    if (allUpper) return new int[0];
    int[] raw = rawPoints(normalize(word));
    var out = new ArrayList<Integer>();
    for (int p : raw) if (p >= Math.max(1, afterFirst) && n - p >= Math.max(1, beforeLast)) out.add(Integer.valueOf(p));
    return toArray(out);
  }

  /** The word with '-' at each point (diagnostics and tests). */
  public static String hyphenate(String word) {
    int[] pts = points(word);
    var b = new StringBuilder();
    int k = 0;
    for (int i = 0; i < word.length(); i++) {
      if (k < pts.length && pts[k] == i) {
        b.append('-');
        k++;
      }
      b.append(word.charAt(i));
    }
    return b.toString();
  }
}
