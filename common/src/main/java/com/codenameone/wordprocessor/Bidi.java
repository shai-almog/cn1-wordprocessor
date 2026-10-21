package com.codenameone.wordprocessor;

import com.codename1.ui.editor.BidiBridge;
import java.util.ArrayList;
import java.util.List;

/**
 * Line-level bidirectional ordering through Codename One's built-in Unicode bidi algorithm. Glyph
 * shaping inside a run is left to the platform text renderer, which receives each directional run in
 * logical order.
 */
final class Bidi {
  private Bidi() {}

  /** True when the text needs bidi processing in a paragraph of the given direction. */
  static boolean needed(String text, boolean rtlParagraph) {
    return !BidiBridge.trivial(text, rtlParagraph);
  }

  /** Embedding level of every char: even is left-to-right, odd right-to-left. */
  static byte[] levels(String text, boolean rtlParagraph) {
    return BidiBridge.levels(text, rtlParagraph);
  }

  /**
   * Reorders a line's spans into visual order (L2) and lays them out left to right with their
   * widths. Trailing whitespace keeps the paragraph level (L1).
   */
  static void reorder(List<LayoutEngine.Span> spans, byte[] levels, boolean rtlParagraph) {
    int n = spans.size();
    if (n == 0) return;
    byte[] lv = new byte[n];
    boolean trailing = true;
    for (int i = n - 1; i >= 0; i--) {
      var s = spans.get(i);
      trailing = trailing && s.text.trim().isEmpty();
      lv[i] = trailing ? (byte) (rtlParagraph ? 1 : 0) : levels[Math.min(s.start, levels.length - 1)];
    }
    int[] order = BidiBridge.visualOrder(lv);
    var visual = new ArrayList<LayoutEngine.Span>(n);
    float x = spans.get(0).x;
    for (int k : order) {
      var s = spans.get(k);
      visual.add(s.placed(x, lv[k] % 2 == 1));
      x += s.width;
    }
    spans.clear();
    spans.addAll(visual);
  }
}
