package com.codename1.ui.editor;

/**
 * Exposes Codename One's built-in Unicode bidirectional algorithm (UAX #9, used by the framework's
 * own editors) to the word processor. {@code BidiUtil} is package-private, so this class lives in its
 * package.
 */
public final class BidiBridge {
  private BidiBridge() {}

  /** Resolved embedding level of every char; even is left-to-right, odd right-to-left. */
  public static byte[] levels(String text, boolean rtlParagraph) {
    return BidiUtil.resolveLevels(text, rtlParagraph);
  }

  /** Rule L2: element v is the logical index of the item drawn at visual position v. */
  public static int[] visualOrder(byte[] levels) {
    return BidiUtil.reorderVisual(levels);
  }

  /** True when the text is plain left-to-right and needs no bidi processing. */
  public static boolean trivial(String text, boolean rtlParagraph) {
    return BidiUtil.isTrivialLtr(text, rtlParagraph);
  }
}
