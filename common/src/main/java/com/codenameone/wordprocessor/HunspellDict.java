package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Hunspell affix dictionary (an .aff/.dic pair), read for spelling checks and suggestions.
 * Supports SET (UTF-8, ISO8859-1, ISO8859-2), FLAG (single char, long, num, UTF-8), AF flag
 * aliases, PFX/SFX rules with strip/add/condition and cross product (one prefix plus one
 * suffix), FORBIDDENWORD, NEEDAFFIX, TRY, MAP and REP. Not supported: twofold suffixes
 * (continuation flags on affixes), compounding rules, ICONV/OCONV and morphological fields;
 * words needing them are reported as misspelled. Like Wordcraft, dictionaries are not bundled:
 * they are installed from the system ({@link ProofLanguages}) or supplied by the caller.
 */
public final class HunspellDict {
  private static final String LATIN2 =
      "\u00a0\u0104\u02d8\u0141\u00a4\u013d\u015a\u00a7\u00a8\u0160\u015e\u0164\u0179\u00ad\u017d\u017b"
          + "\u00b0\u0105\u02db\u0142\u00b4\u013e\u015b\u02c7\u00b8\u0161\u015f\u0165\u017a\u02dd\u017e\u017c"
          + "\u0154\u00c1\u00c2\u0102\u00c4\u0139\u0106\u00c7\u010c\u00c9\u0118\u00cb\u011a\u00cd\u00ce\u010e"
          + "\u0110\u0143\u0147\u00d3\u00d4\u0150\u00d6\u00d7\u0158\u016e\u00da\u0170\u00dc\u00dd\u0162\u00df"
          + "\u0155\u00e1\u00e2\u0103\u00e4\u013a\u0107\u00e7\u010d\u00e9\u0119\u00eb\u011b\u00ed\u00ee\u010f"
          + "\u0111\u0144\u0148\u00f3\u00f4\u0151\u00f6\u00f7\u0159\u016f\u00fa\u0171\u00fc\u00fd\u0163\u02d9";

  private record Affix(String flag, boolean cross, String strip, String add, String[] cond, boolean prefix) {}

  private final Map<String, List<String[]>> roots = new HashMap<>();
  private final List<Affix> prefixes = new ArrayList<>(), suffixes = new ArrayList<>();
  private final List<String[]> rep = new ArrayList<>();
  private final List<String> map = new ArrayList<>();
  private String tryChars = "";
  private String flagMode = "";
  private String forbidden, needAffix;
  private final List<String> aliases = new ArrayList<>();

  /** Decodes a file's bytes in the dictionary encoding named by the .aff SET line. */
  public static String decode(byte[] bytes, String set) {
    String s = set == null ? "" : set.toUpperCase();
    if (s.startsWith("UTF")) {
      try {
        return new String(bytes, "UTF-8");
      } catch (java.io.UnsupportedEncodingException e) {
        throw new IllegalStateException(e);
      }
    }
    boolean l2 = s.indexOf("8859-2") >= 0;
    char[] c = new char[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 0xff;
      c[i] = l2 && b >= 0xa0 ? LATIN2.charAt(b - 0xa0) : (char) b;
    }
    return new String(c);
  }

  /** The SET encoding named in raw .aff bytes (the line is ASCII), or "" when there is none. */
  public static String encodingOf(byte[] aff) {
    String head = decode(aff, "ISO8859-1");
    for (String line : TextUtil.split(head, '\n')) {
      String t = line.trim();
      if (t.startsWith("SET ")) return t.substring(4).trim();
    }
    return "";
  }

  /** Reads a dictionary from raw file bytes, decoding both by the .aff SET line. */
  public static HunspellDict fromBytes(byte[] aff, byte[] dic) {
    String set = encodingOf(aff);
    return new HunspellDict(decode(aff, set), decode(dic, set));
  }

  public HunspellDict(String aff, String dic) {
    parseAff(aff);
    parseDic(dic);
  }

  private static List<String> fields(String line) {
    var out = new ArrayList<String>();
    for (String f : TextUtil.split(line.replace('\t', ' '), ' ')) if (f.length() > 0) out.add(f);
    return out;
  }

  private void parseAff(String aff) {
    var pending = new HashMap<String, Boolean>();
    for (String raw : TextUtil.split(aff, '\n')) {
      String line = raw.trim();
      if (line.startsWith("\ufeff")) line = line.substring(1);
      if (line.length() == 0 || line.startsWith("#")) continue;
      var f = fields(line);
      String k = f.get(0);
      if ("FLAG".equals(k) && f.size() > 1) flagMode = f.get(1);
      else if ("TRY".equals(k) && f.size() > 1) tryChars = f.get(1);
      else if ("FORBIDDENWORD".equals(k) && f.size() > 1) forbidden = f.get(1);
      else if ("NEEDAFFIX".equals(k) && f.size() > 1) needAffix = f.get(1);
      else if ("AF".equals(k) && f.size() > 1 && !isNumber(f.get(1))) aliases.add(f.get(1));
      else if ("MAP".equals(k) && f.size() > 1 && !isNumber(f.get(1))) map.add(f.get(1));
      else if ("REP".equals(k) && f.size() > 2) rep.add(new String[] {f.get(1).replace('_', ' '), f.get(2).replace('_', ' ')});
      else if (("PFX".equals(k) || "SFX".equals(k)) && f.size() >= 4) {
        String flag = f.get(1);
        if (!pending.containsKey(flag + k) && f.size() == 4) {
          pending.put(flag + k, Boolean.valueOf("Y".equals(f.get(2))));
          continue;
        }
        Boolean cross = pending.get(flag + k);
        String strip = "0".equals(f.get(2)) ? "" : f.get(2);
        String add = f.get(3);
        int slash = add.indexOf('/');
        if (slash >= 0) add = add.substring(0, slash); // continuation flags: unsupported
        if ("0".equals(add)) add = "";
        String cond = f.size() > 4 ? f.get(4) : ".";
        boolean prefix = "PFX".equals(k);
        var a = new Affix(flag, cross != null && cross.booleanValue(), strip, add, conditions(cond), prefix);
        (prefix ? prefixes : suffixes).add(a);
      }
    }
  }

  private static boolean isNumber(String s) {
    if (s.length() == 0) return false;
    for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
    return true;
  }

  /** A condition as one entry per character: "." any, "abc" one of, "^abc" none of. */
  private static String[] conditions(String c) {
    var out = new ArrayList<String>();
    for (int i = 0; i < c.length(); i++) {
      char ch = c.charAt(i);
      if (ch == '[') {
        int j = c.indexOf(']', i);
        if (j < 0) j = c.length();
        out.add(c.substring(i + 1, j));
        i = j;
      } else {
        out.add(ch == '.' ? "." : String.valueOf(ch));
      }
    }
    return out.toArray(new String[0]);
  }

  private static boolean matches(String[] cond, String s, int from) {
    if (from < 0 || from + cond.length > s.length()) return false;
    for (int i = 0; i < cond.length; i++) {
      String c = cond[i];
      char ch = s.charAt(from + i);
      if (".".equals(c)) continue;
      boolean neg = c.startsWith("^");
      boolean in = (neg ? c.substring(1) : c).indexOf(ch) >= 0;
      if (in == neg) return false;
    }
    return true;
  }

  private String[] flags(String s) {
    if (aliases.size() > 0 && isNumber(s)) {
      int n = Integer.parseInt(s);
      if (n >= 1 && n <= aliases.size()) s = aliases.get(n - 1);
    }
    var out = new ArrayList<String>();
    if ("long".equals(flagMode)) {
      for (int i = 0; i + 1 < s.length(); i += 2) out.add(s.substring(i, i + 2));
    } else if ("num".equals(flagMode)) {
      for (String p : TextUtil.split(s, ',')) if (p.length() > 0) out.add(p);
    } else {
      for (int i = 0; i < s.length(); i++) out.add(String.valueOf(s.charAt(i)));
    }
    return out.toArray(new String[0]);
  }

  private void parseDic(String dic) {
    boolean first = true;
    for (String raw : TextUtil.split(dic, '\n')) {
      String line = raw.trim();
      if (line.startsWith("\ufeff")) line = line.substring(1);
      if (first) {
        first = false;
        if (isNumber(line)) continue;
      }
      if (line.length() == 0 || line.startsWith("#")) continue;
      int tab = line.indexOf('\t');
      int sp = line.indexOf(' ');
      int cut = tab < 0 ? sp : sp < 0 ? tab : Math.min(tab, sp);
      if (cut >= 0) line = line.substring(0, cut);
      int slash = -1;
      for (int i = 0; i < line.length(); i++)
        if (line.charAt(i) == '/' && (i == 0 || line.charAt(i - 1) != '\\')) {
          slash = i;
          break;
        }
      String word = slash < 0 ? line : line.substring(0, slash);
      String[] fl = slash < 0 ? new String[0] : flags(line.substring(slash + 1));
      word = word.replace("\\/", "/");
      if (word.length() == 0) continue;
      List<String[]> list = roots.get(word);
      if (list == null) roots.put(word, list = new ArrayList<>());
      list.add(fl);
    }
  }

  private static boolean has(String[] fl, String f) {
    if (f == null) return false;
    for (String x : fl) if (x.equals(f)) return true;
    return false;
  }

  /** Does a root with the given affix flag(s) exist? flag null means "a bare root". */
  private boolean root(String stem, String flagA, String flagB) {
    List<String[]> list = roots.get(stem);
    if (list == null) return false;
    for (String[] fl : list) {
      if (has(fl, forbidden)) continue;
      if (flagA == null && has(fl, needAffix)) continue;
      if (flagA != null && !has(fl, flagA)) continue;
      if (flagB != null && !has(fl, flagB)) continue;
      return true;
    }
    return false;
  }

  private boolean isForbidden(String w) {
    List<String[]> list = roots.get(w);
    if (list == null || forbidden == null) return false;
    for (String[] fl : list) if (has(fl, forbidden)) return true;
    return false;
  }

  /** The stem a suffix rule produces word from, or null when it doesn't apply. */
  private static String unSuffix(Affix a, String w) {
    if (!w.endsWith(a.add) || w.length() - a.add.length() + a.strip.length() == 0) return null;
    String stem = w.substring(0, w.length() - a.add.length()) + a.strip;
    return matches(a.cond, stem, stem.length() - a.cond.length) ? stem : null;
  }

  private static String unPrefix(Affix a, String w) {
    if (!w.startsWith(a.add) || w.length() - a.add.length() + a.strip.length() == 0) return null;
    String stem = a.strip + w.substring(a.add.length());
    return matches(a.cond, stem, 0) ? stem : null;
  }

  /** Is this exact form (case as given) in the dictionary or derivable by its affix rules? */
  public boolean lookup(String w) {
    if (w.length() == 0 || isForbidden(w)) return false;
    if (root(w, null, null)) return true;
    for (Affix s : suffixes) {
      String stem = unSuffix(s, w);
      if (stem == null) continue;
      if (root(stem, s.flag, null)) return true;
      if (s.cross)
        for (Affix p : prefixes) {
          if (!p.cross) continue;
          String st2 = unPrefix(p, stem);
          if (st2 != null && root(st2, s.flag, p.flag)) return true;
        }
    }
    for (Affix p : prefixes) {
      String stem = unPrefix(p, w);
      if (stem != null && root(stem, p.flag, null)) return true;
    }
    return false;
  }

  /**
   * Is the word correct? Also accepts a lower-cased form of a capitalized or all-caps word
   * (sentence starts and headings), but not the reverse: "warszawa" is wrong when only
   * "Warszawa" is listed.
   */
  public boolean check(String word) {
    if (lookup(word)) return true;
    String lower = word.toLowerCase();
    if (!lower.equals(word)) {
      if (lookup(lower)) return true;
      String cap = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
      if (!cap.equals(word) && lookup(cap)) return true;
    }
    return false;
  }

  /**
   * Suggestions, best first: MAP variants (missing diacritics), REP confusions, a missing
   * space, then one-edit typos using the TRY characters.
   */
  public List<String> suggest(String word, int max) {
    if (word.length() > 1 && Character.isUpperCase(word.charAt(0)) && word.substring(1).toLowerCase().equals(word.substring(1))) {
      var out = new ArrayList<String>();
      for (String s : suggest(word.toLowerCase(), max)) {
        String c = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        if (!out.contains(c)) out.add(c);
      }
      if (!out.isEmpty()) return out;
    }
    var out = new LinkedHashSet<String>();
    if (word.length() == 0 || word.length() > 40) return new ArrayList<>(out);
    mapVariants(word, 0, out, max);
    for (String[] r : rep)
      for (int i = word.indexOf(r[0]); i >= 0 && out.size() < max; i = word.indexOf(r[0], i + 1)) {
        String c = word.substring(0, i) + r[1] + word.substring(i + r[0].length());
        if (c.indexOf(' ') >= 0 ? bothParts(c) : check(c)) out.add(c);
      }
    for (int i = 1; i < word.length() && out.size() < max; i++) {
      String a = word.substring(0, i), b = word.substring(i);
      if (a.length() > 1 && b.length() > 1 && check(a) && check(b)) out.add(a + " " + b);
    }
    String t = tryChars.length() > 0 ? tryChars : "abcdefghijklmnopqrstuvwxyz";
    var edits = new ArrayList<String>();
    for (int i = 0; i < word.length(); i++) {
      edits.add(word.substring(0, i) + word.substring(i + 1));
      if (i + 1 < word.length())
        edits.add(word.substring(0, i) + word.charAt(i + 1) + word.charAt(i) + word.substring(i + 2));
    }
    for (int i = 0; i <= word.length(); i++)
      for (int k = 0; k < t.length(); k++) {
        char ch = t.charAt(k);
        if (i < word.length() && word.charAt(i) != ch) edits.add(word.substring(0, i) + ch + word.substring(i + 1));
        edits.add(word.substring(0, i) + ch + word.substring(i));
      }
    for (String e : edits) {
      if (out.size() >= max) break;
      if (!e.equals(word) && check(e)) out.add(e);
    }
    var list = new ArrayList<String>(out);
    return list.size() > max ? list.subList(0, max) : list;
  }

  private boolean bothParts(String c) {
    for (String p : TextUtil.split(c, ' ')) if (p.length() == 0 || !check(p)) return false;
    return true;
  }

  /** Every MAP-equivalent spelling (at most a few thousand), keeping correct ones. */
  private void mapVariants(String word, int from, Set<String> out, int max) {
    var seen = new HashSet<String>();
    var frontier = new ArrayList<String>();
    frontier.add(word);
    seen.add(word);
    for (int i = 0; i < word.length() && seen.size() < 4000; i++) {
      var next = new ArrayList<String>();
      for (String w : frontier) {
        next.add(w);
        char ch = w.charAt(i);
        for (String group : map) {
          if (group.indexOf(ch) < 0) continue;
          for (int g = 0; g < group.length(); g++) {
            char alt = group.charAt(g);
            if (alt == ch || group.charAt(g) == '(') continue;
            String v = w.substring(0, i) + alt + w.substring(i + 1);
            if (seen.add(v)) next.add(v);
          }
        }
      }
      frontier = next;
    }
    for (String v : frontier) {
      if (out.size() >= max) return;
      if (!v.equals(word) && check(v)) out.add(v);
    }
  }

  /** Number of dictionary roots (for diagnostics and tests). */
  public int size() {
    return roots.size();
  }
}
