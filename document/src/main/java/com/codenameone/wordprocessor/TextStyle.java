package com.codenameone.wordprocessor;

/** Immutable character style. Sizes are in points. */
public final class TextStyle {
  public static final TextStyle DEFAULT =
      new TextStyle("Serif", 12, 0x202833, false, false, false, false, 0xffffff, "");
  public final String font, link;
  public final int color, background;
  public final float size;
  public final boolean bold, italic, underline, strike;

  public TextStyle(
      String f, float s, int c, boolean b, boolean i, boolean u, boolean x, int bg, String link) {
    if (f == null || link == null || Float.isNaN(s) || s < 1 || s > 500)
      throw new IllegalArgumentException("Invalid font");
    font = f;
    size = s;
    color = c & 0xffffff;
    bold = b;
    italic = i;
    underline = u;
    strike = x;
    background = bg & 0xffffff;
    this.link = link;
  }

  public TextStyle with(String key, String value) {
    return new TextStyle(
        "font".equals(key) ? value : font,
        "size".equals(key) ? Float.parseFloat(value) : size,
        "color".equals(key) ? Integer.parseInt(value, 16) : color,
        "bold".equals(key) ? Boolean.parseBoolean(value) : bold,
        "italic".equals(key) ? Boolean.parseBoolean(value) : italic,
        "underline".equals(key) ? Boolean.parseBoolean(value) : underline,
        "strike".equals(key) ? Boolean.parseBoolean(value) : strike,
        "background".equals(key) ? Integer.parseInt(value, 16) : background,
        "link".equals(key) ? value : link);
  }

  public boolean equals(Object o) {
    if (!(o instanceof TextStyle)) return false;
    TextStyle t = (TextStyle) o;
    return font.equals(t.font)
        && link.equals(t.link)
        && size == t.size
        && color == t.color
        && background == t.background
        && bold == t.bold
        && italic == t.italic
        && underline == t.underline
        && strike == t.strike;
  }

  public int hashCode() {
    return font.hashCode() * 31
        + Float.floatToIntBits(size)
        + color
        + (bold ? 1 : 0)
        + (italic ? 2 : 0)
        + (underline ? 4 : 0)
        + (strike ? 8 : 0)
        + background
        + link.hashCode();
  }
}
