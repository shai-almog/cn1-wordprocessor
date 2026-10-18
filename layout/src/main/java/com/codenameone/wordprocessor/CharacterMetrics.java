package com.codenameone.wordprocessor;

/** Character effects over platform font measurements. No source-text rewriting. */
public final class CharacterMetrics {
  private CharacterMetrics() {}

  public static float width(LayoutEngine.Metrics metrics, String text, TextStyle style) {
    if (style.characters.hidden || text.isEmpty()) return 0;
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
