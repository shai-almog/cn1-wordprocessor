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
      POST = 128, LJMO = 256, VJMO = 512, TJMO = 1024, FIN2 = 2048, FIN3 = 4096,
      MED2 = 8192;
  private static final int MERGED = 8;

  /** Indic scripts by Unicode block (U+0900 + 0x80 k): OpenType tag, then the version 2 tag. */
  private static final String[] INDIC = {
    "deva", "dev2", "beng", "bng2", "guru", "gur2", "gujr", "gjr2", "orya", "ory2", "taml", "tml2",
    "telu", "tel2", "knda", "knd2", "mlym", "mlm2"
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
    if (cp >= 0x0700 && cp <= 0x074f || cp >= 0x0860 && cp <= 0x086f) return "syrc";
    if (cp >= 0x0900 && cp <= 0x0d7f) return INDIC[2 * ((cp - 0x0900) >> 7)];
    if (cp >= 0xa8e0 && cp <= 0xa8ff) return "deva";
    if (cp >= 0x0e00 && cp <= 0x0e7f) return "thai";
    if (cp >= 0x1100 && cp <= 0x11ff || cp >= 0x3130 && cp <= 0x318f || cp >= 0xa960 && cp <= 0xa97f
        || cp >= 0xac00 && cp <= 0xd7ff) return "hang";
    if (cp >= 0x0e80 && cp <= 0x0eff) return "lao ";
    if (cp >= 0x1780 && cp <= 0x17ff || cp >= 0x19e0 && cp <= 0x19ff) return "khmr";
    if (cp >= 0x1000 && cp <= 0x109f || cp >= 0xaa60 && cp <= 0xaa7f
        || cp >= 0xa9e0 && cp <= 0xa9ff) return "mymr";
    return UseShaper.script(cp);
  }

  private static boolean isUse(String script) {
    return !"arab".equals(script) && !"syrc".equals(script) && !"hebr".equals(script) && !"thai".equals(script)
        && !"lao ".equals(script) && !"hang".equals(script) && !"khmr".equals(script)
        && !"mymr".equals(script);
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
    if (cp == 0x0640 || cp == 0x200d || cp == 0x07fa || cp == 0x180a) return 'C';
    char other = joiningUse(cp);
    if (other != 0) return other;
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
   * 2 nukta, 3 virama, 4 matra, 5 vowel, 6 modifier, 7 ZWJ, 8 ZWNJ, 9 repha (Malayalam dot reph).
   */
  static int indic(int cp) {
    if (cp == 0x200d) return 7;
    if (cp == 0x200c) return 8;
    if (cp < 0x0900 || cp > 0x0d7f) return 0;
    int k = (cp - 0x0900) >> 7, o = cp & 0x7f;
    if (k == 1 && (o == 0x4e || o == 0x70 || o == 0x71)) return 1; // khanda ta, Assamese ra/wa
    if (k == 2 && (o == 0x70 || o == 0x71)) return 6; // tippi, addak
    if (k == 2 && o == 0x75) return 4; // yakash
    if (k == 8 && o == 0x4e) return 9; // dot reph, HarfBuzz's Repha category
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
    return shapeRun(font, ot, text, from, to, rtl, kerning, 0);
  }

  /** As above at {@code ptem} points (0 when unknown), which only AAT 'trak' tracking uses. */
  static List<OpenType.Glyph> shapeRun(PdfFonts.TrueType font, OpenType ot, String text,
      int from, int to, boolean rtl, boolean kerning, float ptem) throws java.io.IOException {
    return shapeRun(font, ot, text, from, to, rtl, kerning, ptem, 0);
  }

  /**
   * HarfBuzz's HB_BUFFER_FLAG_DO_NOT_INSERT_DOTTED_CIRCLE: broken clusters keep no dotted-circle
   * base.
   */
  static final int NO_DOTTED_CIRCLE = 1;

  /** As above with buffer {@code flags} ({@link #NO_DOTTED_CIRCLE}). */
  static List<OpenType.Glyph> shapeRun(PdfFonts.TrueType font, OpenType ot, String text,
      int from, int to, boolean rtl, boolean kerning, float ptem, int flags)
      throws java.io.IOException {
    try {
      return shapeChecked(font, ot, text, from, to, rtl, kerning, ptem, flags);
    } catch (java.io.IOException | RuntimeException malformed) {
      // A corrupt layout table (GSUB/GPOS/GDEF/morx...) must not abort the whole export: this
      // run degrades to unshaped nominal glyphs with their hmtx advances.
      return unshaped(font, text, from, to, rtl);
    }
  }

  /** One nominal glyph per code point, hmtx advances, in visual order; no layout tables read. */
  static List<OpenType.Glyph> unshaped(PdfFonts.TrueType font, String text, int from, int to,
      boolean rtl) throws java.io.IOException {
    var out = new ArrayList<OpenType.Glyph>();
    for (int i = from; i < to; ) {
      int cp = text.charAt(i), end = i + 1;
      if (cp >= 0xd800 && cp <= 0xdbff && end < to) {
        int low = text.charAt(end);
        if (low >= 0xdc00 && low <= 0xdfff) {
          cp = 0x10000 + ((cp - 0xd800) << 10) + low - 0xdc00;
          end++;
        }
      }
      var g = new OpenType.Glyph(cp < 32 ? 0 : font.glyph(cp), cp, i, end, GLOBAL);
      g.adv = font.advance(g.id);
      out.add(g);
      i = end;
    }
    if (rtl) java.util.Collections.reverse(out);
    return out;
  }

  private static List<OpenType.Glyph> shapeChecked(PdfFonts.TrueType font, OpenType ot,
      String text, int from, int to, boolean rtl, boolean kerning, float ptem, int flags)
      throws java.io.IOException {
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
    ot.manualJoiners = "khmr".equals(script) ? OpenType.KHMER_MANUAL_JOINERS
        : indicBlock(script) >= 0 ? OpenType.INDIC_MANUAL_JOINERS
        : java.util.Collections.<String>emptySet();
    // hb_ot_shape_normalize's reorder round: marks sorted by modified combining class.
    CombiningClass.reorder(buffer);
    String[] scripts;
    int block = indicBlock(script);
    if (AatShaper.applies(font))
      // With morx a script shaper becomes harfrust's dumber shaper (fallback_position off), so
      // only default-shaper runs (no complex script) may take the legacy 'kern' table.
      return aat(font, ot, buffer, rtl, kerning, ptem, gsubScripts(script, block), script == null);
    // Scripts of the Universal Shaping Engine use it when the font has their GSUB script, as
    // HarfBuzz; otherwise they take the default shaper.
    boolean use = script != null && block < 0 && isUse(script) && ot.hasScript(true, script);
    boolean complex = use || block >= 0 || "khmr".equals(script) || "mymr".equals(script);
    if ("arab".equals(script)) {
      scripts = new String[] {"arab"};
      if (ot.hasScript(true, "arab")) arabicMasks(buffer);
      else presentationForms(font, buffer);
    } else if ("syrc".equals(script)) {
      // HarfBuzz's Arabic shaper, with the Syriac fin2/fin3/med2 forms after Alaph.
      scripts = new String[] {"syrc"};
      arabicMasks(buffer, false);
    } else if ("hebr".equals(script)) scripts = new String[] {"hebr"};
    else if (block >= 0) {
      scripts = ot.hasScript(true, INDIC[2 * block + 1]) ? new String[] {INDIC[2 * block + 1]}
          : new String[] {INDIC[2 * block]};
      IndicShaper.substitute(font, ot, buffer, scripts, block, (flags & NO_DOTTED_CIRCLE) == 0);
    } else if ("hang".equals(script)) {
      scripts = new String[] {"hang"};
      hangul(font, buffer);
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
    } else if (use) {
      scripts = new String[] {script};
      UseShaper.setup(buffer, RPHF, new int[] {ISOL, INIT, MEDI, FINA});
      if (UseShaper.arabicJoining(script)) {
        // As harfrust: these scripts take Arabic joining masks instead of syllable topography.
        for (var g : buffer) g.mask &= ~(ISOL | INIT | MEDI | FINA);
        arabicMasks(buffer, false);
      }
    } else scripts = new String[] {"latn"};
    int all = -1;
    if (use) {
      // The features before reordering stay within a syllable (F_PER_SYLLABLE), as HarfBuzz.
      ot.perSyllable = true;
      gsub(ot, scripts, buffer, new String[] {"locl", "ccmp", "nukt", "akhn"}, all);
      for (var g : buffer) g.flags &= ~OpenType.SUBSTITUTED;
      gsub(ot, scripts, buffer, new String[] {"rphf"}, RPHF);
      UseShaper.recordRphf(buffer, RPHF);
      for (var g : buffer) g.flags &= ~OpenType.SUBSTITUTED;
      gsub(ot, scripts, buffer, new String[] {"pref"}, all);
      UseShaper.recordPref(buffer);
      gsub(ot, scripts, buffer, new String[] {"rkrf", "abvf", "blwf", "half", "pstf", "vatu",
          "cjct"}, all);
      ot.perSyllable = false;
      UseShaper.reorder(buffer, (flags & NO_DOTTED_CIRCLE) != 0 ? 0 : font.glyph(0x25cc));
      ot.apply(true, scripts, buffer, new String[] {"isol", "init", "medi", "fina"},
          new int[] {ISOL, INIT, MEDI, FINA});
      gsub(ot, scripts, buffer, new String[] {"abvs", "blws", "haln", "pres", "psts", "rlig",
          "calt", "clig", "liga", "rclt"}, all);
    } else if (block >= 0) {
      // Substituted by IndicShaper.
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
      if ("hang".equals(script)) {
        gsub(ot, scripts, buffer, new String[] {"ljmo"}, LJMO);
        gsub(ot, scripts, buffer, new String[] {"vjmo"}, VJMO);
        gsub(ot, scripts, buffer, new String[] {"tjmo"}, TJMO);
      }
      if ("arab".equals(script)) {
        gsub(ot, scripts, buffer, new String[] {"isol"}, ISOL);
        gsub(ot, scripts, buffer, new String[] {"fina"}, FINA);
        gsub(ot, scripts, buffer, new String[] {"medi"}, MEDI);
        gsub(ot, scripts, buffer, new String[] {"init"}, INIT);
      } else if ("syrc".equals(script)) {
        int[] order = {ISOL, FINA, FIN2, FIN3, MEDI, MED2, INIT};
        String[] tags = {"isol", "fina", "fin2", "fin3", "medi", "med2", "init"};
        for (int k = 0; k < order.length; k++)
          gsub(ot, scripts, buffer, new String[] {tags[k]}, order[k]);
      }
      gsub(ot, scripts, buffer, new String[] {"rlig"}, all);
      gsub(ot, scripts, buffer, new String[] {"calt", "liga", "clig"}, all);
    }
    boolean gdef = font.tables.containsKey("GDEF");
    int space = font.glyph(' ');
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      // Default ignorables GSUB left alone are hidden as harfrust's hide_default_ignorables: the
      // space glyph with no advance, or removed when the face has no space (so a CGJ or variation
      // selector the face lacks is never .notdef with an advance).
      if (OpenType.ignorable(g) && space != 0) {
        g.id = space;
        g.flags |= OpenType.HIDDEN;
      }
      if ((g.id == 0 && (g.cp == 0x200c || g.cp == 0x200d)
              || OpenType.ignorable(g) && space == 0)
          && buffer.size() > 1) {
        var keep = buffer.get(i > 0 ? i - 1 : i + 1);
        keep.start = Math.min(keep.start, g.start);
        keep.end = Math.max(keep.end, g.end);
        buffer.remove(i--);
        continue;
      }
      // HarfBuzz's Indic shaper keeps mark advances (zero_width_marks none).
      g.adv = (g.flags & OpenType.HIDDEN) != 0 ? 0
          : block < 0 && gdef && ot.glyphClass(g.id) == 3 ? 0 : font.advance(g.id);
    }
    clusters(buffer);
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
    // GPOS runs in logical order, as HarfBuzz, so pair and contextual lookups see the font's
    // sequences; the run is then put in visual order.
    ot.apply(false, scripts, buffer, f, masks);
    if (rtl) reverse(buffer, ot);
    // Legacy 'kern' table when the run's script has no GPOS 'kern', only for shapers with
    // fallback_position on (default, Arabic, Hebrew, Hangul), as harfrust's apply_kern.
    boolean fallbackPosition = !complex && !"thai".equals(script) && !"lao ".equals(script);
    if (kerning && fallbackPosition && ot.lookups(false, scripts, "kern").length == 0)
      for (int i = 0; i + 1 < buffer.size(); i++)
        buffer.get(i).adv += font.kern(buffer.get(i).id, buffer.get(i + 1).id);
    if (rtl && (use || "syrc".equals(script))) marksBeforeBase(buffer, ot);
    return buffer;
  }

  /**
   * HarfBuzz's visual order for an RTL run reverses glyph by glyph, so a mark comes before its
   * base; {@link #reverse} keeps clusters base-first for GPOS. This reorders each cluster as
   * HarfBuzz does and moves offsets so every glyph keeps its drawn position.
   */
  private static void marksBeforeBase(List<OpenType.Glyph> buffer, OpenType ot) {
    for (int i = 0; i < buffer.size(); ) {
      int j = i + 1;
      while (j < buffer.size()) {
        var g = buffer.get(j);
        if ((g.flags & MERGED) == 0 && ot.glyphClass(g.id) != 3 && !mark(g.cp)) break;
        j++;
      }
      if (j - i > 1) {
        int n = j - i, pen = 0;
        int[] x = new int[n];
        var group = new ArrayList<OpenType.Glyph>(buffer.subList(i, j));
        for (int k = 0; k < n; k++) {
          x[k] = pen + group.get(k).dx;
          pen += group.get(k).adv;
        }
        pen = 0;
        for (int k = n - 1; k >= 0; k--) {
          var g = group.get(k);
          g.dx = x[k] - pen;
          pen += g.adv;
          buffer.set(i + n - 1 - k, g);
        }
      }
      i = j;
    }
  }

  /** The OpenType script tags of a run, preferred first (GPOS beside 'morx' uses them). */
  private static String[] gsubScripts(String script, int block) {
    if (block >= 0) return new String[] {INDIC[2 * block + 1], INDIC[2 * block]};
    if ("mymr".equals(script)) return new String[] {"mym2", "mymr"};
    return new String[] {script == null ? "latn" : script};
  }

  /**
   * harfrust's AAT path: 'morx' instead of GSUB (with the dumber shaper, so no script shaper and
   * marks keep their advances); positioning by 'kerx', else GPOS when the face has it (its kern,
   * curs, dist, abvm, blwm, mark and mkmk), else the 'kern' table (default-shaper runs only);
   * then 'trak'. Default ignorables
   * the chains left alone are hidden as a zero-advance space, as HarfBuzz.
   */
  private static List<OpenType.Glyph> aat(PdfFonts.TrueType font, OpenType ot,
      List<OpenType.Glyph> buffer, boolean rtl, boolean kerning, float ptem, String[] scripts,
      boolean fallbackPosition) throws java.io.IOException {
    var aat = new AatShaper(font);
    aat.morx(buffer, rtl);
    int space = font.glyph(' ');
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      boolean hidden = OpenType.ignorable(g.cp) && g.id == font.glyph(g.cp);
      if (hidden && space == 0 && buffer.size() > 1) {
        var keep = buffer.get(i > 0 ? i - 1 : i + 1);
        keep.start = Math.min(keep.start, g.start);
        keep.end = Math.max(keep.end, g.end);
        buffer.remove(i--);
        continue;
      }
      if (hidden) {
        g.id = space;
        g.flags |= OpenType.HIDDEN;
      }
      // A deleted glyph keeps hmtx's last advance until it is dropped, as harfrust's (attachment
      // offsets across it include that advance; HarfBuzz's C library uses zero).
      g.adv = hidden ? 0 : font.advance(g.id);
    }
    clusters(buffer);
    boolean gpos = !aat.hasKerx() && ot.hasGpos();
    if (gpos) AatShaper.removeDeleted(buffer);
    if (gpos) {
      String[] f = kerning ? new String[] {"kern", "curs", "dist", "abvm", "blwm", "mark", "mkmk"}
          : new String[] {"curs", "dist", "abvm", "blwm", "mark", "mkmk"};
      int[] masks = new int[f.length];
      Arrays.fill(masks, -1);
      ot.rtl = rtl;
      ot.apply(false, scripts, buffer, f, masks);
    }
    if (rtl) reverse(buffer, ot);
    if (aat.hasKerx()) aat.kerx(buffer, kerning);
    else if (kerning && fallbackPosition && (!gpos || ot.lookups(false, scripts, "kern").length == 0))
      for (int i = 0; i + 1 < buffer.size(); i++)
        buffer.get(i).adv += font.kern(buffer.get(i).id, buffer.get(i + 1).id);
    if (!gpos) AatShaper.removeDeleted(buffer);
    for (var g : buffer) if ((g.flags & OpenType.HIDDEN) != 0) g.adv = 0;
    aat.track(buffer, ptem);
    return buffer;
  }

  private static void gsub(OpenType ot, String[] scripts, List<OpenType.Glyph> buffer,
      String[] features, int mask) {
    int[] masks = new int[features.length];
    Arrays.fill(masks, mask);
    ot.apply(true, scripts, buffer, features, masks);
  }

  /**
   * Joining types of Syriac, Mandaic, Manichaean, Psalter Pahlavi, Sogdian, Old Uyghur and
   * Chorasmian from harfrust's arabic table (Unicode 17 ArabicShaping): start, end, type, sorted
   * by start; 'A' is Syriac Alaph and 'G' the Dalath/Rish group, which take fin2/fin3/med2.
   */
  private static final int[] JOINING = {
      0x70f, 0x70f, 'T', 0x710, 0x710, 'A', 0x711, 0x711, 'T', 0x712, 0x714, 'D', 0x715, 0x716,
      'G', 0x717, 0x719, 'R', 0x71a, 0x71d, 'D', 0x71e, 0x71e, 'R', 0x71f, 0x727, 'D', 0x728,
      0x728, 'R', 0x729, 0x729, 'D', 0x72a, 0x72a, 'G', 0x72b, 0x72b, 'D', 0x72c, 0x72c, 'R',
      0x72d, 0x72e, 'D', 0x72f, 0x72f, 'G', 0x730, 0x74a, 'T', 0x74d, 0x74d, 'R', 0x74e, 0x74f,
      'D', 0x840, 0x840, 'R', 0x841, 0x845, 'D', 0x846, 0x847, 'R', 0x848, 0x848, 'D', 0x849,
      0x849, 'R', 0x84a, 0x853, 'D', 0x854, 0x854, 'R', 0x855, 0x855, 'D', 0x856, 0x858, 'R',
      0x859, 0x85b, 'T', 0x860, 0x860, 'D', 0x862, 0x865, 'D', 0x867, 0x867, 'R', 0x868, 0x868,
      'D', 0x869, 0x86a, 'R', 0x10ac0, 0x10ac4, 'D', 0x10ac5, 0x10ac5, 'R', 0x10ac7, 0x10ac7, 'R',
      0x10ac9, 0x10aca, 'R', 0x10acd, 0x10acd, 'L', 0x10ace, 0x10ad2, 'R', 0x10ad3, 0x10ad6, 'D',
      0x10ad7, 0x10ad7, 'L', 0x10ad8, 0x10adc, 'D', 0x10add, 0x10add, 'R', 0x10ade, 0x10ae0, 'D',
      0x10ae1, 0x10ae1, 'R', 0x10ae4, 0x10ae4, 'R', 0x10ae5, 0x10ae6, 'T', 0x10aeb, 0x10aee, 'D',
      0x10aef, 0x10aef, 'R', 0x10b80, 0x10b80, 'D', 0x10b81, 0x10b81, 'R', 0x10b82, 0x10b82, 'D',
      0x10b83, 0x10b85, 'R', 0x10b86, 0x10b88, 'D', 0x10b89, 0x10b89, 'R', 0x10b8a, 0x10b8b, 'D',
      0x10b8c, 0x10b8c, 'R', 0x10b8d, 0x10b8d, 'D', 0x10b8e, 0x10b8f, 'R', 0x10b90, 0x10b90, 'D',
      0x10b91, 0x10b91, 'R', 0x10ba9, 0x10bac, 'R', 0x10bad, 0x10bae, 'D', 0x10f30, 0x10f32, 'D',
      0x10f33, 0x10f33, 'R', 0x10f34, 0x10f44, 'D', 0x10f46, 0x10f50, 'T', 0x10f51, 0x10f53, 'D',
      0x10f54, 0x10f54, 'R', 0x10f70, 0x10f73, 'D', 0x10f74, 0x10f75, 'R', 0x10f76, 0x10f81, 'D',
      0x10f82, 0x10f85, 'T', 0x10fb0, 0x10fb0, 'D', 0x10fb2, 0x10fb3, 'D', 0x10fb4, 0x10fb6, 'R',
      0x10fb8, 0x10fb8, 'D', 0x10fb9, 0x10fba, 'R', 0x10fbb, 0x10fbc, 'D', 0x10fbd, 0x10fbd, 'R',
      0x10fbe, 0x10fbf, 'D', 0x10fc1, 0x10fc1, 'D', 0x10fc2, 0x10fc3, 'R', 0x10fc4, 0x10fc4, 'D',
      0x10fc9, 0x10fc9, 'R', 0x10fca, 0x10fca, 'D', 0x10fcb, 0x10fcb, 'L'
  };

  /**
   * Unicode joining types (D dual, R right, L left, T transparent) of the Arabic-joining USE
   * scripts: Mongolian, N'Ko, Phags-pa, Adlam and Hanifi Rohingya; 0 when non-joining or other.
   */
  private static char joiningUse(int cp) {
    if (cp >= 0x0700 && cp <= 0x074f || cp >= 0x0840 && cp <= 0x086f
        || cp >= 0x10ac0 && cp <= 0x10fdf) {
      int lo = 0, hi = JOINING.length / 3 - 1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        if (cp < JOINING[3 * mid]) hi = mid - 1;
        else if (cp > JOINING[3 * mid + 1]) lo = mid + 1;
        else return (char) JOINING[3 * mid + 2];
      }
      return 0;
    }
    if (cp >= 0x1800 && cp <= 0x18af) {
      if (cp >= 0x180b && cp <= 0x180d || cp == 0x180f || cp == 0x1885 || cp == 0x1886
          || cp == 0x18a9) return 'T';
      return cp == 0x1807 || cp >= 0x1820 && cp <= 0x1878 || cp >= 0x1887 && cp <= 0x18a8
          || cp == 0x18aa ? 'D' : 0;
    }
    if (cp >= 0x07c0 && cp <= 0x07ff) {
      if (cp >= 0x07eb && cp <= 0x07f3 || cp == 0x07fd) return 'T';
      return cp >= 0x07ca && cp <= 0x07ea ? 'D' : 0;
    }
    if (cp >= 0xa840 && cp <= 0xa873) return cp == 0xa872 ? 'L' : cp == 0xa873 ? 0 : 'D';
    if (cp >= 0x1e900 && cp <= 0x1e95f) {
      if (cp >= 0x1e944 && cp <= 0x1e94b) return 'T';
      return cp <= 0x1e943 ? 'D' : 0;
    }
    if (cp >= 0x10d00 && cp <= 0x10d3f) {
      if (cp >= 0x10d24 && cp <= 0x10d27) return 'T';
      return cp == 0x10d00 ? 'L' : cp == 0x10d22 ? 'R' : cp <= 0x10d23 ? 'D' : 0;
    }
    return 0;
  }

  /** Masks each Arabic letter with the joining form its neighbours call for. */
  private static void arabicMasks(List<OpenType.Glyph> buffer) {
    arabicMasks(buffer, true);
  }

  /**
   * Joining-form masks; {@code isolateNonJoining} also gives non-joining characters the isol mask
   * (HarfBuzz gives them none, which the USE path follows). Mongolian free variation selectors
   * take their base's form, as HarfBuzz.
   */
  private static void arabicMasks(List<OpenType.Glyph> buffer, boolean isolateNonJoining) {
    int prev = -1, state = 0;
    int[] action = new int[buffer.size()];
    Arrays.fill(action, NONE);
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      char t = joining(g.cp);
      if (t == 'T') continue;
      int column = t == 'L' ? 1 : t == 'R' ? 2 : t == 'D' || t == 'C' ? 3 : t == 'A' ? 4
          : t == 'G' ? 5 : 0;
      int[] entry = JOIN_STATES[state][column];
      if (entry[0] != NONE && prev >= 0) action[prev] = entry[0];
      action[i] = entry[1];
      prev = i;
      state = entry[2];
    }
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      int a = action[i];
      if (a == NONE && isolateNonJoining && joining(g.cp) == 0) a = ISOL;
      if (a == NONE && i > 0 && (g.cp >= 0x180b && g.cp <= 0x180d || g.cp == 0x180f))
        g.mask |= buffer.get(i - 1).mask & (ISOL | INIT | MEDI | FINA);
      if (a != NONE) g.mask |= a;
    }
  }

  private static final int NONE = 0;

  /**
   * HarfBuzz's Arabic joining state machine: per state, per joining class (U, L, R, D, Alaph,
   * Dalath/Rish), the action for the previous letter, the action for this one and the next state.
   */
  private static final int[][][] JOIN_STATES = {
    {{NONE, NONE, 0}, {NONE, ISOL, 2}, {NONE, ISOL, 1}, {NONE, ISOL, 2}, {NONE, ISOL, 1},
        {NONE, ISOL, 6}},
    {{NONE, NONE, 0}, {NONE, ISOL, 2}, {NONE, ISOL, 1}, {NONE, ISOL, 2}, {NONE, FIN2, 5},
        {NONE, ISOL, 6}},
    {{NONE, NONE, 0}, {NONE, ISOL, 2}, {INIT, FINA, 1}, {INIT, FINA, 3}, {INIT, FINA, 4},
        {INIT, FINA, 6}},
    {{NONE, NONE, 0}, {NONE, ISOL, 2}, {MEDI, FINA, 1}, {MEDI, FINA, 3}, {MEDI, FINA, 4},
        {MEDI, FINA, 6}},
    {{NONE, NONE, 0}, {NONE, ISOL, 2}, {MED2, ISOL, 1}, {MED2, ISOL, 2}, {MED2, FIN2, 5},
        {MED2, ISOL, 6}},
    {{NONE, NONE, 0}, {NONE, ISOL, 2}, {ISOL, ISOL, 1}, {ISOL, ISOL, 2}, {ISOL, FIN2, 5},
        {ISOL, ISOL, 6}},
    {{NONE, NONE, 0}, {NONE, ISOL, 2}, {NONE, ISOL, 1}, {NONE, ISOL, 2}, {NONE, FIN3, 5},
        {NONE, ISOL, 6}}
  };

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
  private static boolean leading(int cp) {
    return cp >= 0x1100 && cp <= 0x115f || cp >= 0xa960 && cp <= 0xa97c;
  }

  private static boolean vowel(int cp) {
    return cp >= 0x1160 && cp <= 0x11a7 || cp >= 0xd7b0 && cp <= 0xd7c6;
  }

  private static boolean trailing(int cp) {
    return cp >= 0x11a8 && cp <= 0x11ff || cp >= 0xd7cb && cp <= 0xd7fb;
  }

  /**
   * HarfBuzz's Hangul shaper: a leading/vowel(/trailing) jamo sequence of modern jamo becomes the
   * precomposed syllable when the font has it, as does an LV syllable followed by a trailing
   * jamo; otherwise the jamo (an LV syllable decomposed when the font has its jamo) are masked
   * for the font's 'ljmo', 'vjmo' and 'tjmo' features, so old Hangul is drawn by the font's
   * jamo positioning rather than as a row of separate jamo.
   */
  private static void hangul(PdfFonts.TrueType font, List<OpenType.Glyph> buffer)
      throws java.io.IOException {
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      int cp = g.cp;
      boolean lv = cp >= 0xac00 && cp <= 0xd7a3 && (cp - 0xac00) % 28 == 0;
      if (lv && i + 1 < buffer.size() && trailing(buffer.get(i + 1).cp)) {
        var t = buffer.get(i + 1);
        if (t.cp <= 0x11c2) {
          int lvt = cp + t.cp - 0x11a7;
          int id = font.glyph(lvt);
          if (id != 0) {
            replace(buffer, i, 2, id, lvt);
            continue;
          }
        }
        int l = 0x1100 + (cp - 0xac00) / 588, v = 0x1161 + (cp - 0xac00) % 588 / 28;
        if (font.glyph(l) != 0 && font.glyph(v) != 0 && font.glyph(t.cp) != 0) {
          var lg = new OpenType.Glyph(font.glyph(l), l, g.start, g.end, g.mask | LJMO);
          var vg = new OpenType.Glyph(font.glyph(v), v, g.start, g.end, g.mask | VJMO);
          buffer.set(i, lg);
          buffer.add(i + 1, vg);
          t.mask |= TJMO;
          syllable(buffer, i, i + 3, i + 1);
          i += 2;
        }
        continue;
      }
      if (!leading(cp) || i + 1 >= buffer.size() || !vowel(buffer.get(i + 1).cp)) continue;
      int e = i + 2;
      int v = buffer.get(i + 1).cp;
      boolean hasT = e < buffer.size() && trailing(buffer.get(e).cp);
      if (hasT) e++;
      int t = hasT ? buffer.get(i + 2).cp : 0;
      if (cp <= 0x1112 && v >= 0x1161 && v <= 0x1175 && (!hasT || t <= 0x11c2)) {
        int syl = 0xac00 + ((cp - 0x1100) * 21 + v - 0x1161) * 28 + (hasT ? t - 0x11a7 : 0);
        int id = font.glyph(syl);
        if (id != 0) {
          replace(buffer, i, e - i, id, syl);
          continue;
        }
      }
      buffer.get(i).mask |= LJMO;
      buffer.get(i + 1).mask |= VJMO;
      if (hasT) buffer.get(i + 2).mask |= TJMO;
      syllable(buffer, i, e, i + 1);
      i = e - 1;
    }
  }

  /** Replaces {@code count} glyphs at {@code i} with one glyph for {@code cp} spanning their text. */
  private static void replace(List<OpenType.Glyph> buffer, int i, int count, int id, int cp) {
    var first = buffer.get(i);
    int end = first.end;
    for (int k = 1; k < count; k++) end = Math.max(end, buffer.remove(i + 1).end);
    buffer.set(i, new OpenType.Glyph(id, cp, first.start, end, first.mask));
  }

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
