package com.codenameone.wordprocessor;

import java.util.*;

/** Bounded font measurements shared across paragraphs and revisions, never document roots. */
final class MeasurementCache implements LayoutEngine.Metrics {
  private final LayoutEngine.Metrics delegate;
  private final Map<TextStyle, Values> styles =
      new LinkedHashMap<TextStyle, Values>(32, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<TextStyle, Values> e) {
          return size() > 32;
        }
      };

  private static final class Values {
    Float height, ascent;
    float[] ascii;
    final Map<String, Float> widths =
        new LinkedHashMap<String, Float>(256, .75f, true) {
          protected boolean removeEldestEntry(Map.Entry<String, Float> e) {
            return size() > 2048;
          }
        };
    /** Line-length chunks, kept apart so they cannot evict the cluster widths. */
    final Map<String, Float> runs =
        new LinkedHashMap<String, Float>(64, .75f, true) {
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
    Values v = styles.get(style);
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
    Values v = values(style);
    // Line chunks are mostly unique; sharing one LRU with clusters evicted the widths that
    // line breaking looks up for every grapheme.
    Map<String, Float> cache = text.length() > 12 ? v.runs : v.widths;
    Float width = cache.get(text);
    if (width == null) {
      width = CharacterMetrics.width(delegate, text, style);
      cache.put(text, width);
    }
    return width;
  }

  /** Width of one ASCII character c (whose string is text), cached per style. */
  float ascii(char c, String text, TextStyle style) {
    Values v = values(style);
    if (v.ascii == null) {
      v.ascii = new float[128];
      java.util.Arrays.fill(v.ascii, -1);
    }
    float w = v.ascii[c];
    if (w < 0) v.ascii[c] = w = CharacterMetrics.width(delegate, text, style);
    return w;
  }

  public float height(TextStyle style) {
    Values v = values(style);
    if (v.height == null)
      v.height =
          delegate.height(
              "baseline".equals(style.script) ? style : style.with("script", "baseline"));
    return v.height;
  }

  public float ascent(TextStyle style) {
    Values v = values(style);
    if (v.ascent == null) v.ascent = delegate.ascent(style.renderedStyle());
    return v.ascent;
  }

  void clear() {
    lastStyle = null;
    lastValues = null;
    styles.clear();
  }
}
