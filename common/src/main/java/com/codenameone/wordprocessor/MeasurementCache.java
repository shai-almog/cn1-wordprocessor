package com.codenameone.wordprocessor;

import java.util.*;

/** Bounded font measurements shared across paragraphs and revisions, never document roots. */
final class MeasurementCache implements LayoutEngine.Metrics {
  private final LayoutEngine.Metrics delegate;
  private final Map<TextStyle, Values> styles =
      new LinkedHashMap<>(32, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<TextStyle, Values> e) {
          return size() > 32;
        }
      };

  private static final class Values {
    Float height, ascent;
    float[] ascii;
    final Map<String, Float> widths =
        new LinkedHashMap<>(256, .75f, true) {
          protected boolean removeEldestEntry(Map.Entry<String, Float> e) {
            return size() > 2048;
          }
        };
    /**
     * Word widths for {@link #words}, open-addressed and probed by the characters in place so a
     * hit allocates nothing. Cleared when half full, which bounds it like the LRUs.
     */
    String[] wordKeys;
    float[] wordWidths;
    int wordCount;
    /** Line-length chunks, kept apart so they cannot evict the cluster widths. */
    final Map<String, Float> runs =
        new LinkedHashMap<>(64, .75f, true) {
          protected boolean removeEldestEntry(Map.Entry<String, Float> e) {
            return size() > 128;
          }
        };
  }

  MeasurementCache(LayoutEngine.Metrics delegate) {
    this.delegate = delegate;
  }

  private TextStyle lastStyle;
  private Values lastValues;

  private Values values(TextStyle style) {
    // Consecutive clusters of a run share one style object; skip the map for them.
    if (style == lastStyle) return lastValues;
    var v = styles.get(style);
    if (v == null) {
      v = new Values();
      styles.put(style, v);
    }
    lastStyle = style;
    lastValues = v;
    return v;
  }

  public float width(String text, TextStyle style) {
    // Do not pin large pastes or whole paragraphs in a measurement cache.
    if (text.length() > 256) return CharacterMetrics.width(delegate, text, style);
    var v = values(style);
    if (text.length() > 12 && text.indexOf(' ') > 0) return words(text, style);
    // Line chunks are mostly unique; sharing one LRU with clusters evicted the widths that
    // line breaking looks up for every grapheme.
    return cached(text, style, v);
  }

  private float cached(String text, TextStyle style, Values v) {
    Map<String, Float> cache = text.length() > 12 ? v.runs : v.widths;
    var width = cache.get(text);
    if (width == null) {
      width = CharacterMetrics.width(delegate, text, style);
      cache.put(text, width);
    }
    return width;
  }

  /**
   * A line chunk as the sum of its words, each cached with the spaces that follow it. Line
   * chunks rarely repeat but their words do. Fonts rarely kern across a space, so this
   * matches measuring the whole chunk to within rounding (as browser word-shape caches do)
   * with far fewer native calls. A space is never measured on its own: the Mac port reports
   * a whitespace-only string narrower than it draws, which shifted every following run.
   */
  private float words(String text, TextStyle style) {
    var v = values(style);
    float total = 0;
    int start = 0, n = text.length();
    while (start < n) {
      int end = start;
      while (end < n && text.charAt(end) == ' ') end++;
      while (end < n && text.charAt(end) != ' ') end++;
      while (end < n && text.charAt(end) == ' ') end++;
      total += word(text, start, end, style, v);
      start = end;
    }
    return total;
  }

  private static final int WORDS = 4096;

  private float word(String text, int start, int end, TextStyle style, Values v) {
    if (v.wordKeys == null) {
      v.wordKeys = new String[WORDS];
      v.wordWidths = new float[WORDS];
    }
    int hash = 0, length = end - start;
    for (int i = start; i < end; i++) hash = 31 * hash + text.charAt(i);
    int slot = (hash ^ (hash >>> 16)) & (WORDS - 1);
    for (String key; (key = v.wordKeys[slot]) != null; slot = (slot + 1) & (WORDS - 1))
      if (key.length() == length && same(key, text, start)) return v.wordWidths[slot];
    String word = start == 0 && end == text.length() ? text : text.substring(start, end);
    float width = CharacterMetrics.width(delegate, word, style);
    if (v.wordCount >= WORDS / 2) {
      java.util.Arrays.fill(v.wordKeys, null);
      v.wordCount = 0;
      slot = (hash ^ (hash >>> 16)) & (WORDS - 1);
    }
    v.wordKeys[slot] = word;
    v.wordWidths[slot] = width;
    v.wordCount++;
    return width;
  }

  private static boolean same(String key, String text, int start) {
    for (int i = 0, n = key.length(); i < n; i++) if (key.charAt(i) != text.charAt(start + i)) return false;
    return true;
  }

  /** Width of one ASCII character c (whose string is text), cached per style. */
  float ascii(char c, String text, TextStyle style) {
    var v = values(style);
    if (v.ascii == null) {
      v.ascii = new float[128];
      java.util.Arrays.fill(v.ascii, -1);
    }
    float w = v.ascii[c];
    if (w < 0) v.ascii[c] = w = CharacterMetrics.width(delegate, text, style);
    return w;
  }

  public float height(TextStyle style) {
    var v = values(style);
    if (v.height == null)
      v.height =
          delegate.height(
              "baseline".equals(style.script) ? style : style.with("script", "baseline"));
    return v.height;
  }

  public float ascent(TextStyle style) {
    var v = values(style);
    if (v.ascent == null) v.ascent = delegate.ascent(style.renderedStyle());
    return v.ascent;
  }

  void clear() {
    lastStyle = null;
    lastValues = null;
    styles.clear();
  }
}
