package com.codenameone.wordprocessor;

/** Character effects over platform font measurements. No source-text rewriting. */
public final class CharacterMetrics {
  private CharacterMetrics() {}

  /**
   * Whether hidden text is measured (shown hidden text takes its width, as in Wordcraft). Set by
   * the layout engine for the layout it is computing.
   */
  static volatile boolean showHidden;

  /**
   * Whether tracked deletions take no room (Wordcraft's deletions_in_balloons: revisions shown in
   * balloons take deleted text out of the layout). Set by the layout engine as showHidden.
   */
  static volatile boolean hideDeleted;

  /** Whether text in {@code style} is left out of the layout: hidden, or a deletion in balloons. */
  static boolean leftOut(TextStyle style) {
    return style.characters.hidden && !showHidden
        || hideDeleted && style.revision != null && !style.revision.inserted();
  }

  public static float width(LayoutEngine.Metrics metrics, String text, TextStyle style) {
    if (leftOut(style) || text.isEmpty()) return 0;
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
