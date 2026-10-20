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
}
