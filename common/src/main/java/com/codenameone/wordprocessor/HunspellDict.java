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
 * suffix), continuation classes (twofold suffixes and a suffix inside a prefixed form, at most two
 * suffixes in a row as in Hunspell), FORBIDDENWORD, NEEDAFFIX, ONLYINCOMPOUND, compounding by
 * COMPOUNDFLAG / COMPOUNDBEGIN / COMPOUNDMIDDLE / COMPOUNDEND with COMPOUNDMIN and
 * COMPOUNDWORDMAX, ICONV/OCONV conversion tables, TRY, MAP and REP. Not supported: COMPOUNDRULE,
 * CHECKCOMPOUND* restrictions and morphological fields; words needing them are reported as
 * misspelled. Like Wordcraft, dictionaries are not bundled:
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

  private record Affix(String flag, boolean cross, String strip, String add, String[] cond, boolean prefix, String[] cont) {}

  private final Map<String, List<String[]>> roots = new HashMap<>();
  private final List<Affix> prefixes = new ArrayList<>(), suffixes = new ArrayList<>();
  private final List<String[]> rep = new ArrayList<>();
  private final List<String> map = new ArrayList<>();
  private String tryChars = "";
  private String flagMode = "";
  private String forbidden, needAffix, onlyInCompound;
  private String compoundFlag, compoundBegin, compoundMiddle, compoundEnd;
  private int compoundMin = 3, compoundWordMax = 0;
  private final List<String[]> iconv = new ArrayList<>(), oconv = new ArrayList<>();
  private Set<String> leadingWords = new HashSet<>(), trailingWords = new HashSet<>();
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
      else if ("ONLYINCOMPOUND".equals(k) && f.size() > 1) onlyInCompound = f.get(1);
      else if ("COMPOUNDFLAG".equals(k) && f.size() > 1) compoundFlag = f.get(1);
      else if ("COMPOUNDBEGIN".equals(k) && f.size() > 1) compoundBegin = f.get(1);
      else if ("COMPOUNDMIDDLE".equals(k) && f.size() > 1) compoundMiddle = f.get(1);
      else if ("COMPOUNDEND".equals(k) && f.size() > 1) compoundEnd = f.get(1);
      else if ("COMPOUNDMIN".equals(k) && f.size() > 1 && isNumber(f.get(1))) compoundMin = Math.max(1, Integer.parseInt(f.get(1)));
      else if ("COMPOUNDWORDMAX".equals(k) && f.size() > 1 && isNumber(f.get(1))) compoundWordMax = Integer.parseInt(f.get(1));
      else if (("ICONV".equals(k) || "OCONV".equals(k)) && f.size() > 2)
        ("ICONV".equals(k) ? iconv : oconv).add(new String[] {f.get(1).replace('_', ' '), f.get(2).replace('_', ' ')});
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
        String[] cont = new String[0];
        if (slash >= 0) {
          cont = flags(add.substring(slash + 1));
          add = add.substring(0, slash);
        }
        if ("0".equals(add)) add = "";
        String cond = f.size() > 4 ? f.get(4) : ".";
        boolean prefix = "PFX".equals(k);
        var a = new Affix(flag, cross != null && cross.booleanValue(), strip, add, conditions(cond), prefix, cont);
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

  /**
   * Does a root with the given affix flag(s) exist? flag null means "a bare root". need, when
   * set, is a flag the root must carry too (a compound part's COMPOUNDFLAG); ONLYINCOMPOUND
   * roots count only inside a compound.
   */
  private boolean root(String stem, String flagA, String flagB) {
    return root(stem, flagA, flagB, null, false);
  }

  private boolean root(String stem, String flagA, String flagB, String need, boolean inCompound) {
    List<String[]> list = roots.get(stem);
    if (list == null) return false;
    for (String[] fl : list) {
      if (has(fl, forbidden)) continue;
      if (!inCompound && has(fl, onlyInCompound)) continue;
      if (flagA == null && has(fl, needAffix)) continue;
      if (flagA != null && !has(fl, flagA)) continue;
      if (flagB != null && !has(fl, flagB)) continue;
      if (need != null && !has(fl, need)) continue;
      return true;
    }
    return false;
  }

  /**
   * Does form w carry affix flag bit: as a root, or as the form of a suffix rule whose
   * continuation classes include it (chained suffixation, at most depth more suffixes)?
   */
  private boolean formHasFlag(String w, String bit, int depth, String need, boolean inCompound) {
    if (root(w, bit, null, need, inCompound)) return true;
    if (depth == 0) return false;
    for (Affix s : suffixes) {
      if (!has(s.cont, bit)) continue;
      String stem = unSuffix(s, w);
      if (stem != null && formHasFlag(stem, s.flag, depth - 1, need, inCompound)) return true;
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
    return simple(w, null, false) || compound(w, 0, 0);
  }

  /** A root, or a root with one or two suffixes and/or one prefix (need: see root()). */
  private boolean simple(String w, String need, boolean inCompound) {
    if (root(w, null, null, need, inCompound)) return true;
    for (Affix s : suffixes) {
      String stem = unSuffix(s, w);
      if (stem == null) continue;
      if (formHasFlag(stem, s.flag, 1, need, inCompound)) return true;
      if (s.cross)
        for (Affix p : prefixes) {
          if (!p.cross) continue;
          String st2 = unPrefix(p, stem);
          if (st2 != null && root(st2, s.flag, p.flag, need, inCompound)) return true;
        }
    }
    for (Affix p : prefixes) {
      String stem = unPrefix(p, w);
      if (stem != null && formHasFlag(stem, p.flag, 1, need, inCompound)) return true;
    }
    return false;
  }

  /** A part of a compound at a position: 0 first, 1 middle, 2 last. */
  private boolean part(String p, int pos) {
    if (p.length() < compoundMin || isForbidden(p)) return false;
    String specific = pos == 0 ? compoundBegin : pos == 1 ? compoundMiddle : compoundEnd;
    return (compoundFlag != null && simple(p, compoundFlag, true)) || (specific != null && simple(p, specific, true));
  }

  /** Is w[from..] the rest of a compound whose parts before from number count? */
  private boolean compound(String w, int from, int count) {
    if (compoundFlag == null && compoundBegin == null) return false;
    if (compoundWordMax > 0 && count >= compoundWordMax) return false;
    for (int i = from + compoundMin; i <= w.length() - compoundMin; i++) {
      if (!part(w.substring(from, i), count == 0 ? 0 : 1)) continue;
      boolean lastOk = compoundWordMax == 0 || count + 2 <= compoundWordMax;
      if (lastOk && part(w.substring(i), 2)) return true;
      if (compound(w, i, count + 1)) return true;
    }
    return false;
  }

  /** Applies an ICONV/OCONV table: at each position the longest matching pattern is replaced. */
  private static String convert(List<String[]> table, String w) {
    if (table.isEmpty()) return w;
    var b = new StringBuilder();
    for (int i = 0; i < w.length(); ) {
      String[] best = null;
      for (String[] r : table)
        if (r[0].length() > 0 && w.startsWith(r[0], i) && (best == null || r[0].length() > best[0].length())) best = r;
      if (best == null) b.append(w.charAt(i++));
      else {
        b.append(best[1]);
        i += best[0].length();
      }
    }
    return b.toString();
  }

  /**
   * Short words that often run into the next word (leading) or the one before (trailing): a
   * missing space after or before one ranks before other splits, as in Wordcraft's Polish and
   * Portuguese suggesters.
   */
  public void setSplitWords(Set<String> leading, Set<String> trailing) {
    leadingWords = leading == null ? new HashSet<String>() : leading;
    trailingWords = trailing == null ? new HashSet<String>() : trailing;
  }

  /**
   * Is the word correct? Also accepts a lower-cased form of a capitalized or all-caps word
   * (sentence starts and headings), but not the reverse: "warszawa" is wrong when only
   * "Warszawa" is listed.
   */
  public boolean check(String word) {
    word = convert(iconv, word);
    if (word.length() == 0) return false;
    if (lookup(word)) return true;
    String lower = word.toLowerCase();
    if (!lower.equals(word)) {
      if (lookup(lower)) return true;
      String cap = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
      if (!cap.equals(word) && lookup(cap)) return true;
    }
    return false;
  }

  // Suggestion costs, from Wordcraft's Polish and Portuguese suggesters (crates/proof pl/suggest.rs
  // and pt/suggest.rs): lower ranks first.
  private static final int COST_CASE = 0, COST_ADD_DIACRITIC = 4, COST_OTHER_MAP = 7, COST_DROP_DIACRITIC = 11,
      COST_REP = 10, COST_SPLIT = 12, COST_SPLIT_RARE = 18, COST_SWAP = 14, COST_DROP_OR_ADD = 15,
      COST_MISTYPE = 16, COST_SCAN = 20;
  private static final int MAX_MAP_CHANGES = 4, MAX_MAP_VARIANTS = 4096, SCAN_STEMS = 48;

  /** Candidate collector: suggestion to {cost, order found}. */
  private final class Collector {
    final String input;
    final Set<String> tested = new HashSet<>();
    final Map<String, int[]> found = new HashMap<>();
    int seq;

    Collector(String input) {
      this.input = input;
    }

    /** Keeps a lower-case candidate (one word or two) when every word is correct, as written or capitalized. */
    void offer(String cand, int cost) {
      if (cand.length() == 0 || cand.equals(input) || !tested.add(cand)) return;
      var b = new StringBuilder();
      for (String part : TextUtil.split(cand, ' ')) {
        String w;
        if (part.length() > 0 && lookup(convert(iconv, part))) w = part;
        else {
          String c = capitalize(part);
          if (part.length() == 0 || !lookup(convert(iconv, c))) return;
          w = c;
          cost++; // a name ranks after a common word at the same distance
        }
        if (b.length() > 0) b.append(' ');
        b.append(w);
      }
      keep(b.toString(), cost);
    }

    void keep(String text, int cost) {
      seq++;
      int[] e = found.get(text);
      if (e == null || cost < e[0]) found.put(text, new int[] {cost, e == null ? seq : e[1]});
    }

    int countWithin(int cost) {
      int n = 0;
      for (int[] e : found.values()) if (e[0] <= cost) n++;
      return n;
    }
  }

  private static String capitalize(String w) {
    return w.length() == 0 ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
  }

  private static boolean ascii(char c) {
    return c < 128;
  }

  private static int mapCost(char from, char to) {
    if (ascii(from) && !ascii(to)) return COST_ADD_DIACRITIC;
    if (!ascii(from) && ascii(to)) return COST_DROP_DIACRITIC;
    return COST_OTHER_MAP;
  }

  /** The MAP relatives of a letter. */
  private String alternatives(char c) {
    var b = new StringBuilder();
    for (String g : map) {
      if (g.indexOf(c) < 0) continue;
      for (int i = 0; i < g.length(); i++) {
        char x = g.charAt(i);
        if (x != c && x != '(' && x != ')' && b.toString().indexOf(x) < 0) b.append(x);
      }
    }
    return b.toString();
  }

  private void mapVariants(char[] chars, String[] alts, int i, int changes, int cost, char[] buf, List<Object[]> out) {
    if (out.size() >= MAX_MAP_VARIANTS) return;
    if (i == chars.length) {
      if (changes > 0) out.add(new Object[] {new String(buf), Integer.valueOf(cost)});
      return;
    }
    buf[i] = chars[i];
    mapVariants(chars, alts, i + 1, changes, cost, buf, out);
    if (changes >= MAX_MAP_CHANGES) return;
    for (int k = 0; k < alts[i].length(); k++) {
      buf[i] = alts[i].charAt(k);
      mapVariants(chars, alts, i + 1, changes + 1, cost + mapCost(chars[i], buf[i]), buf, out);
    }
    buf[i] = chars[i];
  }

  /**
   * Suggestions, best first, in Wordcraft's rounds of rising cost: letter case; MAP slips
   * (adding a missing diacritic first, taking a typed one away after REP); the REP confusions; a
   * missing space (cheap next to a short leading/trailing word, see {@link #setSplitWords}); one
   * typo with the TRY letters; and when that finds too few, the roots starting like the word,
   * ranked by an edit distance in which a MAP (diacritic) slip costs half. OCONV is applied to
   * the results.
   */
  public List<String> suggest(String word, int max) {
    word = convert(iconv, word.trim());
    var res = new ArrayList<String>();
    int n = word.length();
    if (n == 0 || n > 40 || max <= 0 || word.indexOf(' ') >= 0) return res;
    String input = word.toLowerCase();
    boolean initial = n > 0 && Character.isUpperCase(word.charAt(0)) && word.substring(1).toLowerCase().equals(word.substring(1));
    boolean upper = n > 1 && word.toUpperCase().equals(word) && !input.equals(word);
    var col = new Collector(input);
    if (lookup(input) && !word.equals(input)) col.keep(input, COST_CASE);
    else if (lookup(capitalize(input)) && !word.equals(capitalize(input))) col.keep(capitalize(input), COST_CASE);
    char[] chars = input.toCharArray();
    String[] alts = new String[n];
    for (int i = 0; i < n; i++) alts[i] = alternatives(chars[i]);
    var variants = new ArrayList<Object[]>();
    mapVariants(chars, alts, 0, 0, 0, new char[n], variants);
    for (Object[] v : variants) col.offer((String) v[0], ((Integer) v[1]).intValue());
    for (String[] r : rep) {
      if (r[0].length() == 0) continue;
      for (int i = input.indexOf(r[0]); i >= 0; i = input.indexOf(r[0], i + 1))
        col.offer(input.substring(0, i) + r[1] + input.substring(i + r[0].length()), COST_REP);
    }
    for (int i = 1; i < n; i++) {
      String a = input.substring(0, i), b = input.substring(i);
      int cost;
      if ((leadingWords.contains(a) && b.length() >= 3) || (a.length() >= 3 && trailingWords.contains(b))) cost = COST_SPLIT;
      else if (a.length() >= 3 && b.length() >= 3) cost = COST_SPLIT_RARE;
      else if (leadingWords.isEmpty() && a.length() > 1 && b.length() > 1) cost = COST_SPLIT_RARE;
      else continue;
      col.offer(a + " " + b, cost);
    }
    var letters = new StringBuilder();
    for (int i = 0; i < tryChars.length(); i++)
      if (Character.isLowerCase(tryChars.charAt(i))) letters.append(tryChars.charAt(i));
    String t = letters.length() > 0 ? letters.toString() : "abcdefghijklmnopqrstuvwxyz";
    for (int i = 0; i + 1 < n; i++)
      if (chars[i] != chars[i + 1])
        col.offer(input.substring(0, i) + chars[i + 1] + chars[i] + input.substring(i + 2), COST_SWAP);
    for (int i = 0; i < n; i++) col.offer(input.substring(0, i) + input.substring(i + 1), COST_DROP_OR_ADD);
    for (int i = 0; i <= n; i++)
      for (int k = 0; k < t.length(); k++) col.offer(input.substring(0, i) + t.charAt(k) + input.substring(i), COST_DROP_OR_ADD);
    for (int i = 0; i < n; i++)
      for (int k = 0; k < t.length(); k++)
        if (chars[i] != t.charAt(k)) col.offer(input.substring(0, i) + t.charAt(k) + input.substring(i + 1), COST_MISTYPE);
    if (col.countWithin(COST_MISTYPE) < max) scan(col, input);
    var found = new ArrayList<Map.Entry<String, int[]>>(col.found.entrySet());
    found.sort((x, y) -> x.getValue()[0] != y.getValue()[0] ? x.getValue()[0] - y.getValue()[0] : x.getValue()[1] - y.getValue()[1]);
    for (var e : found) {
      String s = e.getKey();
      if (initial) s = capitalize(s);
      else if (upper) s = s.toUpperCase();
      s = convert(oconv, s);
      if (!s.equals(word) && !res.contains(s)) res.add(s);
      if (res.size() >= max) break;
    }
    return res;
  }

  /** A letter with MAP relatives folded to the group's first ASCII member (diacritics off). */
  private char fold(char c) {
    for (String g : map)
      if (g.indexOf(c) >= 0)
        for (int i = 0; i < g.length(); i++)
          if (ascii(g.charAt(i)) && g.charAt(i) != '(' && g.charAt(i) != ')') return g.charAt(i);
    return c;
  }

  /** Round 6: the roots starting like the word (folded), by shared letter pairs, within two typos. */
  private void scan(Collector col, String input) {
    char first = fold(input.charAt(0));
    var best = new ArrayList<Object[]>();
    for (String r : roots.keySet()) {
      if (r.length() == 0 || fold(Character.toLowerCase(r.charAt(0))) != first) continue;
      if (Math.abs(r.length() - input.length()) > 2) continue;
      int shared = 0;
      for (int i = 0; i + 1 < input.length(); i++) if (r.indexOf(input.substring(i, i + 2)) >= 0) shared++;
      best.add(new Object[] {r, Integer.valueOf(shared)});
    }
    best.sort((x, y) -> ((Integer) y[1]).intValue() - ((Integer) x[1]).intValue());
    for (int k = 0; k < best.size() && k < SCAN_STEMS; k++) {
      String r = (String) best.get(k)[0];
      int d = weightedDistance(input, r.toLowerCase(), 4);
      if (d <= 4) col.offer(r.toLowerCase().equals(r) ? r : r.toLowerCase(), COST_SCAN + d);
    }
  }

  /** Damerau distance in half steps: a typo costs 2, a MAP (diacritic) slip 1. */
  private int weightedDistance(String a, String b, int limit) {
    int m = a.length(), n = b.length();
    if (Math.abs(m - n) * 2 > limit) return limit + 1;
    int[][] d = new int[m + 1][n + 1];
    for (int i = 0; i <= m; i++) d[i][0] = 2 * i;
    for (int j = 0; j <= n; j++) d[0][j] = 2 * j;
    for (int i = 1; i <= m; i++)
      for (int j = 1; j <= n; j++) {
        char x = a.charAt(i - 1), y = b.charAt(j - 1);
        int sub = x == y ? 0 : fold(x) == fold(y) ? 1 : 2;
        int v = Math.min(Math.min(d[i - 1][j] + 2, d[i][j - 1] + 2), d[i - 1][j - 1] + sub);
        if (i > 1 && j > 1 && x == b.charAt(j - 2) && a.charAt(i - 2) == y) v = Math.min(v, d[i - 2][j - 2] + 2);
        d[i][j] = v;
      }
    return d[m][n];
  }

  /** Number of dictionary roots (for diagnostics and tests). */
  public int size() {
    return roots.size();
  }
}
