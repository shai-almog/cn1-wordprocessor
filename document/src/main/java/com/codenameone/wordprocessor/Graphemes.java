package com.codenameone.wordprocessor;

import static com.codenameone.wordprocessor.GraphemeProperties.*;

/** Unicode 16 default extended grapheme boundaries (UAX #29, revision 45).
 * Offsets are UTF-16. Operates directly on rich-text ropes, including style boundaries.
 * This defines logical character movement, not visual bidi navigation or shaping. */
public final class Graphemes {
  private Graphemes() {}

  public static int next(RichText text, int offset) {
    check(text, offset);
    if (offset == text.length()) return offset;
    int at = offset + 1;
    while (at < text.length() && !isBoundary(text, at)) at++;
    return at;
  }

  public static int previous(RichText text, int offset) {
    check(text, offset);
    if (offset == 0) return 0;
    int at = offset - 1;
    while (at > 0 && !isBoundary(text, at)) at--;
    return at;
  }

  public static boolean isBoundary(RichText text, int offset) {
    check(text, offset);
    if (offset == 0 || offset == text.length()) return true;
    if (Character.isLowSurrogate(text.charAt(offset))
        && Character.isHighSurrogate(text.charAt(offset - 1))) return false;
    int before = prior(text, offset), left = codePoint(text, before), right = codePoint(text, offset);
    int a = breaks(left), b = breaks(right);
    if (a == CR && b == LF) return false; // GB3
    if (control(a) || control(b)) return true; // GB4/5
    if (a == L && (b == L || b == V || b == LV || b == LVT)) return false; // GB6
    if ((a == LV || a == V) && (b == V || b == T)) return false; // GB7
    if ((a == LVT || a == T) && b == T) return false; // GB8
    if (b == EXTEND || b == ZWJ || b == SPACINGMARK || a == PREPEND) return false; // GB9/9a/9b
    if (conjunct(right) == 1) { // GB9c: consonant ... linker ... consonant
      boolean linker = false;
      int at = before;
      while (at >= 0) {
        int value = conjunct(codePoint(text, at));
        if (value == 3) linker = true;
        else if (value != 2) {
          if (value == 1 && linker) return false;
          break;
        }
        at = at == 0 ? -1 : prior(text, at);
      }
    }
    if (a == ZWJ && pictograph(right)) { // GB11
      int at = before;
      while (at > 0) {
        at = prior(text, at);
        int cp = codePoint(text, at);
        if (breaks(cp) == EXTEND) continue;
        if (pictograph(cp)) return false;
        break;
      }
    }
    if (a == REGIONAL_INDICATOR && b == REGIONAL_INDICATOR) { // GB12/13
      int count = 0, at = offset;
      while (at > 0) {
        at = prior(text, at);
        if (breaks(codePoint(text, at)) != REGIONAL_INDICATOR) break;
        count++;
      }
      return count % 2 == 0;
    }
    return true; // GB999
  }

  private static boolean control(int value) { return value == CR || value == LF || value == CONTROL; }
  private static void check(RichText text, int offset) {
    if (offset < 0 || offset > text.length()) throw new IndexOutOfBoundsException("Grapheme offset " + offset);
  }
  private static int prior(RichText text, int offset) {
    int at = offset - 1;
    if (at > 0 && Character.isLowSurrogate(text.charAt(at))
        && Character.isHighSurrogate(text.charAt(at - 1))) at--;
    return at;
  }
  private static int codePoint(RichText text, int at) {
    char ch = text.charAt(at);
    if (Character.isHighSurrogate(ch) && at + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(at + 1)))
      return Character.toCodePoint(ch, text.charAt(at + 1));
    return ch;
  }
}
