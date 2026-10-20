package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-level bidirectional ordering (a compact subset of UAX #9: strong types, European and
 * Arabic numbers, neutrals and rule L2 reordering). Glyph shaping inside a run is left to the
 * platform text renderer, which receives each directional run in logical order.
 */
final class Bidi {
  private Bidi() {}

  private static final byte L = 0, R = 1, NUM = 2, NEUTRAL = 3;

  static boolean rtl(char c) {
    return (c >= 0x0590 && c <= 0x08FF)
        || (c >= 0xFB1D && c <= 0xFDFF)
        || (c >= 0xFE70 && c <= 0xFEFF)
        || c == 0x200F;
  }

  /** True when the text needs bidi processing in a paragraph of the given direction. */
  static boolean needed(String text, boolean rtlParagraph) {
    if (rtlParagraph) return true;
    for (int i = 0; i < text.length(); i++) if (rtl(text.charAt(i))) return true;
    return false;
  }

  private static byte type(char c) {
    if (rtl(c)) return Character.isDigit(c) ? NUM : R;
    if (Character.isDigit(c)) return NUM;
    if ((c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || c == 0x200E
        || (c >= 0xC0 && c != 0xD7 && c != 0xF7 && (c < 0x2000 || c > 0x2BFF) && (c < 0x3000 || c > 0x303F)
            && (c < 0xFE00 || c > 0xFE6F) && (c < 0xFF00 || c > 0xFF20))) return L;
    return NEUTRAL;
  }

  /** Embedding level of every char: even is left-to-right, odd right-to-left. */
  static byte[] levels(String text, boolean rtlParagraph) {
    int n = text.length();
    byte base = (byte) (rtlParagraph ? 1 : 0);
    byte[] types = new byte[n];
    for (int i = 0; i < n; i++) types[i] = type(text.charAt(i));
    // Numbers take the direction of the preceding strong text (W2/W7).
    byte strong = rtlParagraph ? R : L;
    for (int i = 0; i < n; i++)
      if (types[i] == L || types[i] == R) strong = types[i];
      else if (types[i] == NUM && strong == L) types[i] = L;
    // Neutrals between matching directions take that direction; otherwise the base (N1/N2).
    for (int i = 0; i < n; ) {
      if (types[i] != NEUTRAL) {
        i++;
        continue;
      }
      int j = i;
      while (j < n && types[j] == NEUTRAL) j++;
      byte before = i == 0 ? (rtlParagraph ? R : L) : direction(types[i - 1]);
      byte after = j == n ? (rtlParagraph ? R : L) : direction(types[j]);
      byte resolved = before == after ? before : (rtlParagraph ? R : L);
      for (int k = i; k < j; k++) types[k] = resolved == R ? R : L;
      i = j;
    }
    byte[] out = new byte[n];
    for (int i = 0; i < n; i++)
      out[i] =
          types[i] == NUM
              ? (byte) (base + (base % 2 == 0 ? 2 : 1))
              : types[i] == R ? (byte) (base % 2 == 0 ? base + 1 : base) : (byte) (base % 2 == 0 ? base : base + 1);
    return out;
  }

  private static byte direction(byte type) {
    return type == L ? L : R;
  }

  /**
   * Reorders a line's spans into visual order (L2) and lays them out left to right with their
   * widths. Trailing whitespace keeps the paragraph level (L1).
   */
  static void reorder(List<LayoutEngine.Span> spans, byte[] levels, boolean rtlParagraph) {
    int n = spans.size();
    if (n == 0) return;
    byte[] lv = new byte[n];
    byte max = 0, minOdd = Byte.MAX_VALUE;
    boolean trailing = true;
    for (int i = n - 1; i >= 0; i--) {
      var s = spans.get(i);
      trailing = trailing && s.text.trim().isEmpty();
      lv[i] = trailing ? (byte) (rtlParagraph ? 1 : 0) : levels[Math.min(s.start, levels.length - 1)];
      if (lv[i] > max) max = lv[i];
      if (lv[i] % 2 == 1 && lv[i] < minOdd) minOdd = lv[i];
    }
    var order = new ArrayList<Integer>();
    for (int i = 0; i < n; i++) order.add(i);
    for (int level = max; level >= minOdd && level > 0; level--)
      for (int i = 0; i < n; ) {
        if (lv[order.get(i)] < level) {
          i++;
          continue;
        }
        int j = i;
        while (j < n && lv[order.get(j)] >= level) j++;
        for (int a = i, b = j - 1; a < b; a++, b--) {
          Integer t = order.get(a);
          order.set(a, order.get(b));
          order.set(b, t);
        }
        i = j;
      }
    var visual = new ArrayList<LayoutEngine.Span>(n);
    float x = spans.get(0).x;
    for (int i = 0; i < n; i++) {
      int k = order.get(i);
      var s = spans.get(k);
      visual.add(s.placed(x, lv[k] % 2 == 1));
      x += s.width;
    }
    spans.clear();
    spans.addAll(visual);
  }
}
