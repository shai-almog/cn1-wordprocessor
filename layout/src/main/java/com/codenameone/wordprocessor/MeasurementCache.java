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
    final Map<String, Float> widths =
        new LinkedHashMap<String, Float>(256, .75f, true) {
          protected boolean removeEldestEntry(Map.Entry<String, Float> e) {
            return size() > 512;
          }
        };
  }

  MeasurementCache(LayoutEngine.Metrics delegate) {
    this.delegate = delegate;
  }

  private Values values(TextStyle style) {
    Values v = styles.get(style);
    if (v == null) {
      v = new Values();
      styles.put(style, v);
    }
    return v;
  }

  public float width(String text, TextStyle style) {
    // Do not pin large pastes or whole paragraphs in a measurement cache.
    if (text.length() > 256) return CharacterMetrics.width(delegate, text, style);
    Values v = values(style);
    Float width = v.widths.get(text);
    if (width == null) {
      width = CharacterMetrics.width(delegate, text, style);
      v.widths.put(text, width);
    }
    return width;
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
    styles.clear();
  }
}
