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
        || cp >= 0xfe00 && cp <= 0xfe0f
        || thaiMark(cp);
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

  /** Indic scripts by Unicode block (U+0900 + 0x80 k): OpenType tag, then the version 2 tag. */
  private static final String[] INDIC = {
    "deva", "dev2", "beng", "bng2", "guru", "gur2", "gujr", "gjr2", "orya", "ory2", "taml", "tml2",
    "telu", "tel2", "knda", "knd2", "mlym", "mlm2"
  };

  /** Pre-base matras per Indic block, as offsets within the block. */
  private static final int[][] PREBASE = {
    {0x3f, 0x4e}, {0x3f, 0x47, 0x48}, {0x3f}, {0x3f}, {0x47}, {0x46, 0x47, 0x48}, {}, {},
    {0x46, 0x47, 0x48}
  };

  /** Two-part matras per Indic block: {matra, pre-base part, post-base part}..., block offsets. */
  private static final int[][] SPLITS = {
    {}, {0x4b, 0x47, 0x3e, 0x4c, 0x47, 0x57}, {}, {},
    {0x48, 0x47, 0x56, 0x4b, 0x47, 0x3e, 0x4c, 0x47, 0x57},
    {0x4a, 0x46, 0x3e, 0x4b, 0x47, 0x3e, 0x4c, 0x46, 0x57}, {}, {},
    {0x4a, 0x46, 0x3e, 0x4b, 0x47, 0x3e, 0x4c, 0x46, 0x57}
  };

  /**
   * Script of a code point: "arab", "hebr", an Indic tag ("deva", "beng", "guru", "gujr", "orya",
   * "taml", "telu", "knda", "mlym"), "thai", "lao ", "khmr", "mymr", or null for anything else.
   */
  static String script(int cp) {
    if (cp >= 0x0600 && cp <= 0x06ff || cp >= 0x0750 && cp <= 0x077f
        || cp >= 0x08a0 && cp <= 0x08ff || cp >= 0xfb50 && cp <= 0xfdff
        || cp >= 0xfe70 && cp <= 0xfeff) return "arab";
    if (cp >= 0x0590 && cp <= 0x05ff || cp >= 0xfb1d && cp <= 0xfb4f) return "hebr";
    if (cp >= 0x0900 && cp <= 0x0d7f) return INDIC[2 * ((cp - 0x0900) >> 7)];
    if (cp >= 0xa8e0 && cp <= 0xa8ff) return "deva";
    if (cp >= 0x0e00 && cp <= 0x0e7f) return "thai";
    if (cp >= 0x0e80 && cp <= 0x0eff) return "lao ";
    if (cp >= 0x1780 && cp <= 0x17ff || cp >= 0x19e0 && cp <= 0x19ff) return "khmr";
    if (cp >= 0x1000 && cp <= 0x109f || cp >= 0xaa60 && cp <= 0xaa7f
        || cp >= 0xa9e0 && cp <= 0xa9ff) return "mymr";
    return null;
  }

  /** Index k of an Indic script tag (block U+0900 + 0x80 k), or -1. */
  static int indicBlock(String script) {
    for (int k = 0; script != null && k < INDIC.length / 2; k++)
      if (INDIC[2 * k].equals(script)) return k;
    return -1;
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

  /**
   * Indic category (Devanagari through Malayalam, by offset within the block): 1 consonant,
   * 2 nukta, 3 virama, 4 matra, 5 vowel, 6 modifier, 7 ZWJ, 8 ZWNJ.
   */
  static int indic(int cp) {
    if (cp == 0x200d) return 7;
    if (cp == 0x200c) return 8;
    if (cp < 0x0900 || cp > 0x0d7f) return 0;
    int k = (cp - 0x0900) >> 7, o = cp & 0x7f;
    if (k == 1 && (o == 0x4e || o == 0x70 || o == 0x71)) return 1; // khanda ta, Assamese ra/wa
    if (k == 2 && (o == 0x70 || o == 0x71)) return 6; // tippi, addak
    if (k == 2 && o == 0x75) return 4; // yakash
    if (k == 8 && (o >= 0x54 && o <= 0x56 || o >= 0x7a)) return 1; // chillus
    if (k == 8 && o >= 0x58 && o <= 0x5e) return 0;
    if (o >= 0x15 && o <= 0x39 || o >= 0x58 && o <= 0x5f || o >= 0x78) return 1;
    if (o == 0x3c) return 2;
    if (o == 0x4d) return 3;
    if (o >= 0x3a && o <= 0x4c && o != 0x3d || o == 0x4e || o == 0x4f || o >= 0x55 && o <= 0x57
        || o == 0x62 || o == 0x63) return 4;
    if (o >= 0x04 && o <= 0x14 || o == 0x60 || o == 0x61 || o >= 0x72 && o <= 0x77) return 5;
    if (o <= 0x03 || o >= 0x51 && o <= 0x54) return 6;
    return 0;
  }

  /** Khmer category, in the Indic numbering: 1 base, 3 coeng, 4 vowel sign, 6 sign. */
  static int khmer(int cp) {
    if (cp == 0x200d) return 7;
    if (cp == 0x200c) return 8;
    if (cp >= 0x1780 && cp <= 0x17b3) return 1;
    if (cp == 0x17d2) return 3;
    if (cp >= 0x17b6 && cp <= 0x17c5) return 4;
    if (cp == 0x17b4 || cp == 0x17b5 || cp >= 0x17c6 && cp <= 0x17d1 || cp == 0x17d3
        || cp == 0x17dd) return 6;
    return 0;
  }

  /** Myanmar category, in the Indic numbering: 1 base, 3 stacker, 4 vowel or medial, 6 sign. */
  static int myanmar(int cp) {
    if (cp == 0x200d) return 7;
    if (cp == 0x200c) return 8;
    if (cp >= 0x1000 && cp <= 0x102a || cp == 0x103f || cp == 0x104e
        || cp >= 0x1050 && cp <= 0x1055 || cp >= 0x105a && cp <= 0x105d || cp == 0x1061
        || cp == 0x1065 || cp == 0x1066 || cp >= 0x106e && cp <= 0x1070
        || cp >= 0x1075 && cp <= 0x1081 || cp == 0x108e || cp >= 0xaa60 && cp <= 0xaa76
        || cp >= 0xa9e0 && cp <= 0xa9e4 || cp >= 0xa9e7 && cp <= 0xa9ef) return 1;
    if (cp == 0x1039) return 3;
    if (cp >= 0x102b && cp <= 0x1035 || cp >= 0x103b && cp <= 0x103e
        || cp >= 0x1056 && cp <= 0x1059 || cp >= 0x105e && cp <= 0x1060 || cp == 0x1062
        || cp == 0x1067 || cp == 0x1068 || cp >= 0x1071 && cp <= 0x1074
        || cp >= 0x1082 && cp <= 0x1086 || cp == 0x109c || cp == 0x109d) return 4;
    if (cp >= 0x1036 && cp <= 0x1038 || cp == 0x103a || cp == 0x1063 || cp == 0x1064
        || cp >= 0x1069 && cp <= 0x106d || cp >= 0x1087 && cp <= 0x108d || cp == 0x108f
        || cp == 0x109a || cp == 0x109b) return 6;
    return 0;
  }

  /** Thai or Lao above/below vowel, tone mark or other combining sign. */
  static boolean thaiMark(int cp) {
    return cp == 0x0e31 || cp >= 0x0e34 && cp <= 0x0e3a || cp >= 0x0e47 && cp <= 0x0e4e
        || cp == 0x0eb1 || cp >= 0x0eb4 && cp <= 0x0ebc || cp >= 0x0ec8 && cp <= 0x0ece;
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
    int block = indicBlock(script);
    boolean complex = block >= 0 || "khmr".equals(script) || "mymr".equals(script);
    if ("arab".equals(script)) {
      scripts = new String[] {"arab"};
      if (ot.hasScript(true, "arab")) arabicMasks(buffer);
      else presentationForms(font, buffer);
    } else if ("hebr".equals(script)) scripts = new String[] {"hebr"};
    else if (block >= 0) {
      scripts = ot.hasScript(true, INDIC[2 * block + 1]) ? new String[] {INDIC[2 * block + 1]}
          : new String[] {INDIC[2 * block]};
      int base = 0x0900 + 0x80 * block;
      for (int s = 0; s < SPLITS[block].length; s += 3)
        split(font, buffer, base + SPLITS[block][s], base + SPLITS[block][s + 1],
            base + SPLITS[block][s + 2]);
      indicSyllables(buffer, block);
    } else if ("thai".equals(script) || "lao ".equals(script)) {
      scripts = new String[] {script};
      saraAm(font, buffer, "lao ".equals(script));
    } else if ("khmr".equals(script)) {
      scripts = new String[] {"khmr"};
      for (int v : new int[] {0x17be, 0x17bf, 0x17c0, 0x17c4, 0x17c5}) split(font, buffer, v, 0x17c1, v);
      clusterSyllables(buffer, false);
    } else if ("mymr".equals(script)) {
      scripts = ot.hasScript(true, "mym2") ? new String[] {"mym2"} : new String[] {"mymr"};
      clusterSyllables(buffer, true);
    } else scripts = new String[] {"latn"};
    int all = -1;
    if (block >= 0) {
      gsub(ot, scripts, buffer, new String[] {"locl", "ccmp"}, all);
      gsub(ot, scripts, buffer, new String[] {"nukt"}, all);
      gsub(ot, scripts, buffer, new String[] {"akhn"}, all);
      gsub(ot, scripts, buffer, new String[] {"rphf"}, RPHF);
      gsub(ot, scripts, buffer, new String[] {"rkrf"}, all);
      gsub(ot, scripts, buffer, new String[] {"pref"}, POST);
      gsub(ot, scripts, buffer, new String[] {"blwf"}, POST);
      gsub(ot, scripts, buffer, new String[] {"abvf"}, POST);
      gsub(ot, scripts, buffer, new String[] {"half"}, HALF);
      gsub(ot, scripts, buffer, new String[] {"pstf"}, POST);
      gsub(ot, scripts, buffer, new String[] {"vatu"}, POST);
      gsub(ot, scripts, buffer, new String[] {"cjct"}, all);
      moveReph(buffer, 0x0930 + 0x80 * block);
      gsub(ot, scripts, buffer, new String[] {"pres", "abvs", "blws", "psts", "haln", "calt"}, all);
    } else if (complex) {
      gsub(ot, scripts, buffer, new String[] {"locl", "ccmp"}, all);
      gsub(ot, scripts, buffer, new String[] {"rphf"}, RPHF);
      gsub(ot, scripts, buffer, new String[] {"pref"}, POST);
      gsub(ot, scripts, buffer, new String[] {"blwf"}, POST);
      gsub(ot, scripts, buffer, new String[] {"abvf"}, POST);
      gsub(ot, scripts, buffer, new String[] {"pstf"}, POST);
      gsub(ot, scripts, buffer, new String[] {"cfar"}, POST);
      gsub(ot, scripts, buffer,
          new String[] {"pres", "abvs", "blws", "psts", "clig", "liga", "calt"}, all);
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
    features.add("curs");
    if (complex) {
      features.add("dist");
      features.add("abvm");
      features.add("blwm");
    }
    features.add("mark");
    features.add("mkmk");
    String[] f = features.toArray(new String[0]);
    int[] masks = new int[f.length];
    Arrays.fill(masks, all);
    ot.rtl = rtl;
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
   * Replaces each {@code cp} with its two parts {@code first} and {@code second} (both carrying
   * the original text span) when the face maps both, so a pre-base part can be reordered.
   */
  private static void split(PdfFonts.TrueType font, List<OpenType.Glyph> buffer, int cp,
      int first, int second) throws java.io.IOException {
    int a = font.glyph(first), b = font.glyph(second);
    if (a == 0 || b == 0) return;
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      if (g.cp != cp) continue;
      var tail = new OpenType.Glyph(b, second, g.start, g.end, g.mask);
      g.id = a;
      g.cp = first;
      buffer.add(++i, tail);
    }
  }

  /**
   * Thai and Lao sara am: decomposed into nikhahit (niggahita) and sara aa, with the nikhahit
   * moved before the above-base marks that precede it, as Wordcraft's shaper does.
   */
  private static void saraAm(PdfFonts.TrueType font, List<OpenType.Glyph> buffer, boolean lao)
      throws java.io.IOException {
    int am = lao ? 0x0eb3 : 0x0e33, nikhahit = lao ? 0x0ecd : 0x0e4d, aa = lao ? 0x0eb2 : 0x0e32;
    split(font, buffer, am, nikhahit, aa);
    for (int i = 0; i < buffer.size(); i++) {
      if (buffer.get(i).cp != nikhahit || i + 1 >= buffer.size() || buffer.get(i + 1).cp != aa)
        continue;
      int to = i;
      while (to > 0 && thaiMark(buffer.get(to - 1).cp)) to--;
      if (to < i) buffer.add(to, buffer.remove(i));
    }
  }

  /** Sets every glyph of [s, e) to one syllable spanning all their text. */
  private static void syllable(List<OpenType.Glyph> buffer, int s, int e, int syllable) {
    int start = Integer.MAX_VALUE, end = 0;
    for (int k = s; k < e; k++) {
      start = Math.min(start, buffer.get(k).start);
      end = Math.max(end, buffer.get(k).end);
    }
    for (int k = s; k < e; k++) {
      var g = buffer.get(k);
      g.syllable = syllable;
      g.start = start;
      g.end = end;
    }
  }

  /**
   * Indic syllables: masks for reph, half and below/post-base forms, the pre-base matras moved
   * before the consonant cluster, and every syllable merged into one extraction cluster.
   */
  private static void indicSyllables(List<OpenType.Glyph> buffer, int block) {
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
      syllable(buffer, s, e, ++syllable);
      if (cat == 1) consonantSyllable(buffer, s, e, block);
      s = e;
    }
  }

  private static void consonantSyllable(List<OpenType.Glyph> buffer, int s, int e, int block) {
    int ra = 0x0930 + 0x80 * block, consonants = 0;
    for (int k = s; k < e; k++) if (buffer.get(k).cat == 1) consonants++;
    // Tamil has no reph; Telugu forms one only with an explicit ZWJ, which is not modeled.
    boolean reph = block != 5 && block != 6 && consonants > 1 && e - s > 2
        && buffer.get(s).cp == ra && buffer.get(s + 1).cat == 3 && buffer.get(s + 2).cat == 1;
    int first = reph ? s + 2 : s, base = -1;
    for (int k = e - 1; k >= first; k--) {
      var g = buffer.get(k);
      if (g.cat != 1) continue;
      // A final Ra after a virama takes its below-base form; the consonant before it is the base.
      boolean belowRa = g.cp == ra && k - 1 > first && buffer.get(k - 1).cat == 3;
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
    // Pre-base matras go before the consonant cluster (after a reph), in their logical order.
    int at = first;
    for (int k = base + 1; k < e; k++) {
      int o = buffer.get(k).cp - (0x0900 + 0x80 * block);
      for (int p : PREBASE[block])
        if (o == p) {
          var m = buffer.remove(k);
          m.mask = GLOBAL;
          buffer.add(at++, m);
          break;
        }
    }
  }

  private static int lastConsonant(List<OpenType.Glyph> buffer, int s, int e) {
    for (int k = e - 1; k >= s; k--) if (buffer.get(k).cat == 1) return k;
    return -1;
  }

  /**
   * Khmer and Myanmar syllables (a base, coeng/stacker plus consonant pairs, then vowel signs,
   * medials and signs): post-base forms masked, the pre-base vowels and the pre-base consonant
   * (Khmer coeng Ro, Myanmar medial Ra) moved before the base, and a Myanmar kinzi masked for
   * 'rphf' and moved after the base.
   */
  private static void clusterSyllables(List<OpenType.Glyph> buffer, boolean myanmar) {
    for (var g : buffer) g.cat = myanmar ? myanmar(g.cp) : khmer(g.cp);
    int syllable = 0;
    for (int s = 0; s < buffer.size(); ) {
      int n = buffer.size(), e = s + 1, base = s;
      boolean kinzi = myanmar && s + 3 < n && (buffer.get(s).cp == 0x1004
              || buffer.get(s).cp == 0x101b || buffer.get(s).cp == 0x105a)
          && buffer.get(s + 1).cp == 0x103a && buffer.get(s + 2).cp == 0x1039
          && buffer.get(s + 3).cat == 1;
      if (kinzi) {
        base = s + 3;
        e = s + 4;
      }
      if (buffer.get(base).cat == 1) {
        while (e < n) {
          int c = buffer.get(e).cat;
          if (c == 3 && e + 1 < n && buffer.get(e + 1).cat == 1) e += 2;
          else if (c == 4 || c == 6 || c == 7 || c == 8 || c == 3) e++;
          else break;
        }
      }
      syllable(buffer, s, e, ++syllable);
      if (buffer.get(base).cat == 1) {
        var pre = new ArrayList<OpenType.Glyph>();
        var preConsonant = new ArrayList<OpenType.Glyph>();
        var rest = new ArrayList<OpenType.Glyph>();
        var kinziGlyphs = new ArrayList<OpenType.Glyph>();
        for (int k = s; k < base; k++) {
          buffer.get(k).mask |= RPHF;
          kinziGlyphs.add(buffer.get(k));
        }
        buffer.get(base).flags |= OpenType.BASE;
        for (int k = base + 1; k < e; k++) {
          var g = buffer.get(k);
          boolean pair = g.cat == 3 && k + 1 < e && buffer.get(k + 1).cat == 1;
          if (pair) {
            var c = buffer.get(k + 1);
            g.mask |= POST;
            c.mask |= POST;
            boolean ro = !myanmar && c.cp == 0x179a;
            (ro ? preConsonant : rest).add(g);
            (ro ? preConsonant : rest).add(c);
            k++;
          } else if (myanmar ? g.cp == 0x1031 || g.cp == 0x1084
              : g.cp >= 0x17c1 && g.cp <= 0x17c3) pre.add(g);
          else if (myanmar && g.cp == 0x103c) {
            g.mask |= POST;
            preConsonant.add(g);
          } else {
            if (g.cat == 4) g.mask |= POST;
            rest.add(g);
          }
        }
        var order = new ArrayList<OpenType.Glyph>(pre);
        order.addAll(preConsonant);
        order.add(buffer.get(base));
        order.addAll(kinziGlyphs);
        order.addAll(rest);
        for (int k = 0; k < order.size(); k++) buffer.set(s + k, order.get(k));
      }
      s = e;
    }
  }

  /** Moves each formed reph after its syllable's base consonant and below-base forms. */
  private static void moveReph(List<OpenType.Glyph> buffer, int ra) {
    for (int i = 0; i < buffer.size(); i++) {
      var r = buffer.get(i);
      if ((r.mask & RPHF) == 0 || (r.flags & OpenType.LIGATED) == 0 || r.cp != ra) continue;
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
