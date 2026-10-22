package com.codenameone.wordprocessor;

/** Character effects over platform font measurements. No source-text rewriting. */
public final class CharacterMetrics {
  private CharacterMetrics() {}

  /**
   * The style to measure and spell a laid-out span's text with when painting it. Deletions are left
   * out only of the body story (Wordcraft's hides_deleted), so a header, footer, note or text box
   * span keeps its deleted text and width; painting runs after layout with the body's switch still
   * on, so a deleted span the layout gave room is measured without its deletion.
   */
  static TextStyle painted(LayoutEngine.Span span) {
    var style = span.style;
    return span.width > 0 && style.revision != null && !style.revision.inserted() ? style.revision(null) : style;
  }

  /**
   * Whether text in {@code style} is left out of a layout: hidden (unless that layout shows hidden
   * text), or a deletion when that layout shows deletions in balloons. The switches belong to each
   * layout engine (no process-wide state): a PDF export's layout must not change the editor's.
   */
  static boolean leftOut(TextStyle style, boolean showHidden, boolean hideDeleted) {
    return style.characters.hidden && !showHidden
        || hideDeleted && style.revision != null && !style.revision.inserted();
  }

  /**
   * Whether a laid-out span was left out of its layout: it took no room and is hidden or deleted
   * text. Painters ask the span, which carries its layout's decision, not a shared switch.
   */
  static boolean leftOut(LayoutEngine.Span span) {
    return span.width <= 0 && (span.style.characters.hidden || span.style.revision != null && !span.style.revision.inserted());
  }

  /** The width a painter measures {@code text} of a laid-out span at: 0 when it was left out. */
  static float painted(LayoutEngine.Metrics metrics, String text, LayoutEngine.Span span) {
    return leftOut(span) ? 0 : width(metrics, text, painted(span));
  }

  /** The width of {@code text} in {@code style}; callers decide whether it is left out. */
  public static float width(LayoutEngine.Metrics metrics, String text, TextStyle style) {
    if (text.isEmpty()) return 0;
    if (!style.characters.smallCaps && style.characters.spacing == 0)
      return metrics.width(style.displayText(text), style.renderedStyle())
          * style.characters.scale
          / 100;
    float result = 0;
    RichText source = RichText.of(text, style);
    for (int i = 0; i < text.length(); ) {
      int next = Graphemes.next(source, i);
      var glyph = text.substring(i, next);
      result +=
          Math.max(
              0,
              metrics.width(style.displayText(glyph), style.glyphStyle(glyph))
                      * style.characters.scale
                      / 100
                  + style.characters.spacing);
      i = next;
    }
    return result;
  }
}
