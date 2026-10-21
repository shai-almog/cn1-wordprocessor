package com.codenameone.wordprocessor;

import java.util.*;

/**
 * Glyph sequence for PDF text: Arabic contextual forms (Presentation Forms-B, lam-alef
 * ligatures), then for right-to-left spans visual order with mirrored paired brackets. Each unit is
 * {glyph code point, logical start, logical end} so ToUnicode can keep the logical text.
 */
final class PdfShaping {
  private PdfShaping() {}

  interface Coverage {
    boolean covers(int cp);
  }

  // Presentation form base and form count (1 isolated, 2 isolated/final, 4 all) for U+0621..U+064A.
  private static final int[] FORMS = {
    0xfe80, 1, 0xfe81, 2, 0xfe83, 2, 0xfe85, 2, 0xfe87, 2, 0xfe89, 4, 0xfe8d, 2, 0xfe8f, 4,
    0xfe93, 2, 0xfe95, 4, 0xfe99, 4, 0xfe9d, 4, 0xfea1, 4, 0xfea5, 4, 0xfea9, 2, 0xfeab, 2,
    0xfead, 2, 0xfeaf, 2, 0xfeb1, 4, 0xfeb5, 4, 0xfeb9, 4, 0xfebd, 4, 0xfec1, 4, 0xfec5, 4,
    0xfec9, 4, 0xfecd, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xfed1, 4, 0xfed5, 4, 0xfed9, 4,
    0xfedd, 4, 0xfee1, 4, 0xfee5, 4, 0xfee9, 4, 0xfeed, 2, 0xfeef, 2, 0xfef1, 4
  };

  private static final String MIRRORS = "()<>[]{}\u00ab\u00bb\u2039\u203a";

  static boolean mark(int cp) {
    return cp >= 0x0591 && cp <= 0x05c7 && cp != 0x05be && cp != 0x05c0 && cp != 0x05c3
            && cp != 0x05c6
        || cp >= 0x0610 && cp <= 0x061a
        || cp >= 0x064b && cp <= 0x065f
        || cp == 0x0670
        || cp >= 0x06d6 && cp <= 0x06ed
        || cp >= 0x0300 && cp <= 0x036f
        || cp == 0x200d
        || cp >= 0xfe00 && cp <= 0xfe0f;
  }

  private static int forms(int cp) {
    return cp == 0x0640 ? 4 : cp >= 0x0621 && cp <= 0x064a ? FORMS[2 * (cp - 0x0621) + 1] : 0;
  }

  private static int form(int cp, int index) {
    return FORMS[2 * (cp - 0x0621)] + index;
  }

  static List<int[]> shape(String text, boolean rtl, Coverage coverage) {
    var cps = new ArrayList<int[]>();
    for (int i = 0; i < text.length(); ) {
      int cp = text.charAt(i), end = i + 1;
      if (cp >= 0xd800 && cp <= 0xdbff && end < text.length()) {
        int low = text.charAt(end);
        if (low >= 0xdc00 && low <= 0xdfff) {
          cp = 0x10000 + ((cp - 0xd800) << 10) + low - 0xdc00;
          end++;
        }
      }
      cps.add(new int[] {cp, i, end});
      i = end;
    }
    var units = new ArrayList<int[]>();
    for (int i = 0; i < cps.size(); i++) {
      int[] u = cps.get(i);
      int cp = u[0], n = forms(cp);
      if (n == 0 || cp == 0x0640) {
        units.add(u);
        continue;
      }
      int prev = neighbor(cps, i, -1), next = neighbor(cps, i, 1);
      boolean joinPrev = prev >= 0 && forms(cps.get(prev)[0]) == 4;
      if (cp == 0x0644 && next == i + 1) {
        int alef = cps.get(next)[0],
            lig =
                alef == 0x0622 ? 0xfef5 : alef == 0x0623 ? 0xfef7 : alef == 0x0625 ? 0xfef9
                    : alef == 0x0627 ? 0xfefb : 0;
        if (lig != 0 && coverage.covers(lig + (joinPrev ? 1 : 0))) {
          units.add(new int[] {lig + (joinPrev ? 1 : 0), u[1], cps.get(next)[2]});
          i++;
          continue;
        }
      }
      boolean joinNext = n == 4 && next >= 0 && forms(cps.get(next)[0]) >= 2;
      int index =
          n == 1 ? 0 : joinPrev && joinNext ? 3 : joinPrev ? 1 : joinNext ? 2 : 0;
      int shaped = form(cp, index);
      units.add(coverage.covers(shaped) ? new int[] {shaped, u[1], u[2]} : u);
    }
    if (rtl) {
      var clusters = new ArrayList<List<int[]>>();
      for (int[] u : units) {
        if (clusters.isEmpty() || !mark(u[0])) clusters.add(new ArrayList<int[]>());
        clusters.get(clusters.size() - 1).add(u);
      }
      units.clear();
      for (int c = clusters.size() - 1; c >= 0; c--)
        for (int[] u : clusters.get(c)) {
          int m = u[0] < 0x10000 ? MIRRORS.indexOf((char) u[0]) : -1;
          units.add(m < 0 ? u : new int[] {MIRRORS.charAt(m ^ 1), u[1], u[2]});
        }
    }
    return units;
  }

  /** Nearest non-mark code point index in a direction, or -1 when a non-Arabic letter intervenes. */
  private static int neighbor(List<int[]> cps, int i, int step) {
    for (int j = i + step; j >= 0 && j < cps.size(); j += step) {
      int cp = cps.get(j)[0];
      if (mark(cp)) continue;
      return forms(cp) > 0 ? j : -1;
    }
    return -1;
  }

  // ---------------------------------------------------------------- OpenType shaping

  private static final int GLOBAL = 1, ISOL = 2, FINA = 4, MEDI = 8, INIT = 16, RPHF = 32,
      HALF = 64, POST = 128;
  private static final int MERGED = 8;

  /** Script of a code point: "arab", "hebr", "deva", or null for anything else. */
  static String script(int cp) {
    if (cp >= 0x0600 && cp <= 0x06ff || cp >= 0x0750 && cp <= 0x077f
        || cp >= 0x08a0 && cp <= 0x08ff || cp >= 0xfb50 && cp <= 0xfdff
        || cp >= 0xfe70 && cp <= 0xfeff) return "arab";
    if (cp >= 0x0590 && cp <= 0x05ff || cp >= 0xfb1d && cp <= 0xfb4f) return "hebr";
    if (cp >= 0x0900 && cp <= 0x097f || cp >= 0xa8e0 && cp <= 0xa8ff) return "deva";
    return null;
  }

  /** Arabic joining type: 'D' dual, 'R' right, 'C' join-causing, 'T' transparent, 0 none. */
  static char joining(int cp) {
    if (mark(cp) && cp != 0x200d) return 'T';
    if (cp == 0x0640 || cp == 0x200d || cp == 0x07fa) return 'C';
    if (cp >= 0x0621 && cp <= 0x064a) {
      int n = forms(cp);
      return n == 4 ? 'D' : n == 2 ? 'R' : (char) 0;
    }
    if (cp == 0x0671 || cp == 0x0672 || cp == 0x0673 || cp >= 0x0675 && cp <= 0x0677
        || cp >= 0x0688 && cp <= 0x0699 || cp == 0x06c0 || cp >= 0x06c3 && cp <= 0x06cb
        || cp == 0x06cd || cp == 0x06cf || cp == 0x06d2 || cp == 0x06d3 || cp == 0x06d5
        || cp == 0x06ee || cp == 0x06ef || cp >= 0x0759 && cp <= 0x075b || cp == 0x076b
        || cp == 0x076c || cp == 0x0771 || cp == 0x0773 || cp == 0x0774 || cp == 0x0778
        || cp == 0x0779) return 'R';
    if (cp == 0x066e || cp == 0x066f || cp >= 0x0678 && cp <= 0x0687
        || cp >= 0x069a && cp <= 0x06bf || cp == 0x06c1 || cp == 0x06c2 || cp == 0x06cc
        || cp == 0x06ce || cp == 0x06d0 || cp == 0x06d1 || cp >= 0x06fa && cp <= 0x06fc
        || cp == 0x06ff || cp >= 0x0750 && cp <= 0x077f || cp >= 0x08a0 && cp <= 0x08c8)
      return 'D';
    return 0;
  }

  /** Devanagari category: 1 consonant, 2 nukta, 3 virama, 4 matra, 5 vowel, 6 modifier, 7 ZWJ, 8 ZWNJ. */
  static int indic(int cp) {
    if (cp >= 0x0915 && cp <= 0x0939 || cp >= 0x0958 && cp <= 0x095f
        || cp >= 0x0978 && cp <= 0x097f) return 1;
    if (cp == 0x093c) return 2;
    if (cp == 0x094d) return 3;
    if (cp >= 0x093a && cp <= 0x094c && cp != 0x093c && cp != 0x093d || cp == 0x094e
        || cp == 0x094f || cp >= 0x0955 && cp <= 0x0957 || cp == 0x0962 || cp == 0x0963) return 4;
    if (cp >= 0x0904 && cp <= 0x0914 || cp == 0x0960 || cp == 0x0961
        || cp >= 0x0972 && cp <= 0x0977) return 5;
    if (cp >= 0x0900 && cp <= 0x0903 || cp >= 0x0951 && cp <= 0x0954) return 6;
    if (cp == 0x200d) return 7;
    if (cp == 0x200c) return 8;
    return 0;
  }

  /**
   * Shapes {@code text[from, to)} (one script, one face) into positioned glyphs, in visual order
   * when {@code rtl}. Each glyph's [start, end) is the logical text it extracts as: the first glyph
   * of a cluster carries the whole cluster, later ones are empty (start == end).
   */
  static List<OpenType.Glyph> shapeRun(PdfFonts.TrueType font, OpenType ot, String text,
      int from, int to, boolean rtl, boolean kerning) throws java.io.IOException {
    var buffer = new ArrayList<OpenType.Glyph>();
    String script = null;
    for (int i = from; i < to; ) {
      int cp = text.charAt(i), end = i + 1;
      if (cp >= 0xd800 && cp <= 0xdbff && end < to) {
        int low = text.charAt(end);
        if (low >= 0xdc00 && low <= 0xdfff) {
          cp = 0x10000 + ((cp - 0xd800) << 10) + low - 0xdc00;
          end++;
        }
      }
      if (script == null) script = script(cp);
      int shown = cp;
      if (rtl && cp < 0x10000 && MIRRORS.indexOf((char) cp) >= 0)
        shown = MIRRORS.charAt(MIRRORS.indexOf((char) cp) ^ 1);
      buffer.add(new OpenType.Glyph(cp < 32 ? 0 : font.glyph(shown), cp, i, end, GLOBAL));
      i = end;
    }
    String[] scripts;
    if ("arab".equals(script)) {
      scripts = new String[] {"arab"};
      if (ot.hasScript(true, "arab")) arabicMasks(buffer);
      else presentationForms(font, buffer);
    } else if ("hebr".equals(script)) scripts = new String[] {"hebr"};
    else if ("deva".equals(script)) {
      scripts = ot.hasScript(true, "dev2") ? new String[] {"dev2"} : new String[] {"deva"};
      devanagari(buffer);
    } else scripts = new String[] {"latn"};
    int all = -1;
    if ("deva".equals(script)) {
      gsub(ot, scripts, buffer, new String[] {"locl", "ccmp"}, all);
      gsub(ot, scripts, buffer, new String[] {"nukt"}, all);
      gsub(ot, scripts, buffer, new String[] {"akhn"}, all);
      gsub(ot, scripts, buffer, new String[] {"rphf"}, RPHF);
      gsub(ot, scripts, buffer, new String[] {"rkrf"}, all);
      gsub(ot, scripts, buffer, new String[] {"blwf"}, POST);
      gsub(ot, scripts, buffer, new String[] {"half"}, HALF);
      gsub(ot, scripts, buffer, new String[] {"pstf"}, POST);
      gsub(ot, scripts, buffer, new String[] {"vatu"}, POST);
      gsub(ot, scripts, buffer, new String[] {"cjct"}, all);
      moveReph(buffer);
      gsub(ot, scripts, buffer, new String[] {"pres", "abvs", "blws", "psts", "haln", "calt"}, all);
    } else {
      gsub(ot, scripts, buffer, new String[] {"ccmp", "locl"}, all);
      if ("arab".equals(script)) {
        gsub(ot, scripts, buffer, new String[] {"isol"}, ISOL);
        gsub(ot, scripts, buffer, new String[] {"fina"}, FINA);
        gsub(ot, scripts, buffer, new String[] {"medi"}, MEDI);
        gsub(ot, scripts, buffer, new String[] {"init"}, INIT);
      }
      gsub(ot, scripts, buffer, new String[] {"rlig"}, all);
      gsub(ot, scripts, buffer, new String[] {"calt", "liga", "clig"}, all);
    }
    boolean gdef = font.tables.containsKey("GDEF");
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      if (g.id == 0 && (g.cp == 0x200c || g.cp == 0x200d) && buffer.size() > 1) {
        var keep = buffer.get(i > 0 ? i - 1 : i + 1);
        keep.start = Math.min(keep.start, g.start);
        keep.end = Math.max(keep.end, g.end);
        buffer.remove(i--);
        continue;
      }
      g.adv = gdef && ot.glyphClass(g.id) == 3 ? 0 : font.advance(g.id);
    }
    clusters(buffer);
    if (rtl) reverse(buffer, ot);
    var features = new ArrayList<String>();
    if (kerning) features.add("kern");
    if ("deva".equals(script)) {
      features.add("dist");
      features.add("abvm");
      features.add("blwm");
    }
    features.add("mark");
    features.add("mkmk");
    String[] f = features.toArray(new String[0]);
    int[] masks = new int[f.length];
    Arrays.fill(masks, all);
    ot.apply(false, scripts, buffer, f, masks);
    if (kerning && ot.lookups(false, scripts, "kern").length == 0)
      for (int i = 0; i + 1 < buffer.size(); i++)
        buffer.get(i).adv += font.kern(buffer.get(i).id, buffer.get(i + 1).id);
    return buffer;
  }

  private static void gsub(OpenType ot, String[] scripts, List<OpenType.Glyph> buffer,
      String[] features, int mask) {
    int[] masks = new int[features.length];
    Arrays.fill(masks, mask);
    ot.apply(true, scripts, buffer, features, masks);
  }

  /** Masks each Arabic letter with the joining form its neighbours call for. */
  private static void arabicMasks(List<OpenType.Glyph> buffer) {
    for (int i = 0; i < buffer.size(); i++) {
      char t = joining(buffer.get(i).cp);
      if (t != 'D' && t != 'R') {
        if (t != 'T') buffer.get(i).mask |= ISOL;
        continue;
      }
      boolean prev = joinsTo(buffer, i, -1), next = t == 'D' && joinsTo(buffer, i, 1);
      buffer.get(i).mask |= prev && next ? MEDI : prev ? FINA : next ? INIT : ISOL;
    }
  }

  /** Whether the nearest non-transparent neighbour in direction {@code step} joins toward i. */
  private static boolean joinsTo(List<OpenType.Glyph> buffer, int i, int step) {
    for (int j = i + step; j >= 0 && j < buffer.size(); j += step) {
      char t = joining(buffer.get(j).cp);
      if (t == 'T') continue;
      return t == 'D' || t == 'C' || step > 0 && t == 'R';
    }
    return false;
  }

  /** Fallback for faces without Arabic GSUB: Unicode presentation forms the cmap covers. */
  private static void presentationForms(PdfFonts.TrueType font, List<OpenType.Glyph> buffer)
      throws java.io.IOException {
    var sb = new StringBuilder();
    var index = new ArrayList<Integer>();
    for (int i = 0; i < buffer.size(); i++) {
      int cp = buffer.get(i).cp;
      if (cp >= 0x10000) {
        sb.append((char) (0xd800 + ((cp - 0x10000) >> 10)));
        sb.append((char) (0xdc00 + ((cp - 0x10000) & 0x3ff)));
        index.add(i);
      } else sb.append((char) cp);
      index.add(i);
    }
    var old = new ArrayList<>(buffer);
    var units =
        shape(
            sb.toString(),
            false,
            cp -> {
              try {
                return font.glyph(cp) != 0;
              } catch (java.io.IOException e) {
                return false;
              }
            });
    buffer.clear();
    for (int[] u : units) {
      var first = old.get(index.get(u[1]));
      var last = old.get(index.get(u[2] - 1));
      buffer.add(new OpenType.Glyph(font.glyph(u[0]), first.cp, first.start, last.end, GLOBAL));
    }
  }

  /**
   * Devanagari syllables: masks for reph, half and below/post-base forms, the pre-base matra moved
   * before the consonant cluster, and every syllable merged into one extraction cluster.
   */
  private static void devanagari(List<OpenType.Glyph> buffer) {
    int syllable = 0;
    for (var g : buffer) g.cat = indic(g.cp);
    for (int s = 0; s < buffer.size(); ) {
      int n = buffer.size(), e = s + 1, cat = buffer.get(s).cat;
      if (cat == 1) {
        e = s;
        while (e < n && buffer.get(e).cat == 1) {
          e++;
          if (e < n && buffer.get(e).cat == 2) e++;
          if (e < n && buffer.get(e).cat == 3) {
            e++;
            if (e < n && (buffer.get(e).cat == 7 || buffer.get(e).cat == 8)) e++;
            continue;
          }
          break;
        }
        while (e < n && (buffer.get(e).cat == 4 || buffer.get(e).cat == 2)) e++;
        while (e < n && buffer.get(e).cat == 6) e++;
      } else if (cat == 5) {
        while (e < n && (buffer.get(e).cat == 2 || buffer.get(e).cat == 4 || buffer.get(e).cat == 6))
          e++;
      }
      syllable++;
      int start = Integer.MAX_VALUE, end = 0;
      for (int k = s; k < e; k++) {
        start = Math.min(start, buffer.get(k).start);
        end = Math.max(end, buffer.get(k).end);
      }
      if (cat == 1) consonantSyllable(buffer, s, e);
      for (int k = s; k < e; k++) {
        var g = buffer.get(k);
        g.syllable = syllable;
        g.start = start;
        g.end = end;
      }
      s = e;
    }
  }

  private static void consonantSyllable(List<OpenType.Glyph> buffer, int s, int e) {
    int consonants = 0;
    for (int k = s; k < e; k++) if (buffer.get(k).cat == 1) consonants++;
    boolean reph = consonants > 1 && e - s > 2 && buffer.get(s).cp == 0x0930
        && buffer.get(s + 1).cat == 3 && buffer.get(s + 2).cat == 1;
    int first = reph ? s + 2 : s, base = -1;
    for (int k = e - 1; k >= first; k--) {
      var g = buffer.get(k);
      if (g.cat != 1) continue;
      // A final Ra after a virama takes its below-base form; the consonant before it is the base.
      boolean belowRa = g.cp == 0x0930 && k - 1 > first && buffer.get(k - 1).cat == 3;
      if (belowRa && base < 0 && k == lastConsonant(buffer, first, e)) continue;
      base = k;
      break;
    }
    if (base < 0) base = first;
    buffer.get(base).flags |= OpenType.BASE;
    if (reph) {
      buffer.get(s).mask |= RPHF;
      buffer.get(s + 1).mask |= RPHF;
    }
    for (int k = first; k < base; k++) buffer.get(k).mask |= HALF;
    for (int k = base + 1; k < e; k++) buffer.get(k).mask |= POST;
    // Pre-base matras (i, prishthamatra) go before the consonant cluster (after a reph).
    for (int k = base + 1; k < e; k++) {
      int cp = buffer.get(k).cp;
      if (cp == 0x093f || cp == 0x094e) {
        var m = buffer.remove(k);
        m.mask = GLOBAL;
        buffer.add(first, m);
      }
    }
  }

  private static int lastConsonant(List<OpenType.Glyph> buffer, int s, int e) {
    for (int k = e - 1; k >= s; k--) if (buffer.get(k).cat == 1) return k;
    return -1;
  }

  /** Moves each formed reph after its syllable's base consonant and below-base forms. */
  private static void moveReph(List<OpenType.Glyph> buffer) {
    for (int i = 0; i < buffer.size(); i++) {
      var r = buffer.get(i);
      if ((r.mask & RPHF) == 0 || (r.flags & OpenType.LIGATED) == 0 || r.cp != 0x0930) continue;
      int target = -1;
      for (int k = i + 1; k < buffer.size() && buffer.get(k).syllable == r.syllable; k++) {
        var g = buffer.get(k);
        if ((g.flags & OpenType.BASE) != 0) target = k;
        else if (target >= 0 && ((g.mask & POST) != 0 && g.cat != 4 && g.cat != 6 || g.cat == 2
            || g.cat == 3)) target = k;
        else if (target >= 0) break;
      }
      if (target < 0) continue;
      buffer.remove(i);
      buffer.add(target, r);
      r.mask &= ~RPHF;
      i = target;
    }
  }

  /** Merges overlapping glyph spans so each cluster's text is carried by its first glyph. */
  private static void clusters(List<OpenType.Glyph> buffer) {
    for (int i = 0; i < buffer.size(); ) {
      int start = buffer.get(i).start, end = buffer.get(i).end, j = i + 1;
      while (j < buffer.size() && buffer.get(j).start < end) {
        end = Math.max(end, buffer.get(j).end);
        start = Math.min(start, buffer.get(j).start);
        j++;
      }
      buffer.get(i).start = start;
      buffer.get(i).end = end;
      for (int k = i + 1; k < j; k++) {
        buffer.get(k).start = end;
        buffer.get(k).end = end;
        buffer.get(k).flags |= MERGED;
      }
      i = j;
    }
  }

  /** Visual order for right-to-left runs: clusters reversed, marks kept after their base. */
  private static void reverse(List<OpenType.Glyph> buffer, OpenType ot) {
    var groups = new ArrayList<List<OpenType.Glyph>>();
    for (var g : buffer) {
      boolean attach = (g.flags & MERGED) != 0 || ot.glyphClass(g.id) == 3 || mark(g.cp);
      if (groups.isEmpty() || !attach) groups.add(new ArrayList<OpenType.Glyph>());
      groups.get(groups.size() - 1).add(g);
    }
    buffer.clear();
    for (int c = groups.size() - 1; c >= 0; c--) buffer.addAll(groups.get(c));
  }
}
