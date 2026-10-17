package com.codenameone.wordprocessor;

/** Immutable advanced character formatting. Distances are typographic points. */
public final class CharacterFormat {
  public static final String[] KEYS = {
    "doubleUnderline",
    "doubleStrike",
    "smallCaps",
    "allCaps",
    "hidden",
    "characterSpacing",
    "characterScale",
    "characterPosition",
    "underlineStyle",
    "underlineColor",
    "outline",
    "shadow",
    "emboss",
    "engrave"
  };
  public static final String[] UNDERLINES = {
    "none",
    "single",
    "words",
    "double",
    "thick",
    "dotted",
    "dottedHeavy",
    "dash",
    "dashedHeavy",
    "dashLong",
    "dashLongHeavy",
    "dotDash",
    "dashDotHeavy",
    "dotDotDash",
    "dashDotDotHeavy",
    "wave",
    "wavyHeavy",
    "wavyDouble"
  };
  public static final CharacterFormat DEFAULT =
      new CharacterFormat(false, false, false, false, false, 0, 100, 0);
  public final boolean doubleUnderline, doubleStrike, smallCaps, allCaps, hidden;
  public final float spacing, scale, position;
  public final String underlineStyle;

  /** -1 inherits the text color. */
  public final int underlineColor;

  public final boolean outline, shadow, emboss, engrave;

  public CharacterFormat(
      boolean u,
      boolean s,
      boolean small,
      boolean caps,
      boolean hidden,
      float spacing,
      float scale,
      float position) {
    this(
        u ? "double" : "none",
        -1,
        s,
        small,
        caps,
        hidden,
        spacing,
        scale,
        position,
        false,
        false,
        false,
        false);
  }

  private CharacterFormat(
      String underlineStyle,
      int underlineColor,
      boolean s,
      boolean small,
      boolean caps,
      boolean hidden,
      float spacing,
      float scale,
      float position,
      boolean outline,
      boolean shadow,
      boolean emboss,
      boolean engrave) {
    if (!java.util.Arrays.asList(UNDERLINES).contains(underlineStyle)
        || underlineColor < -1
        || underlineColor > 0xffffff)
      throw new IllegalArgumentException("Invalid underline style or color");
    this.underlineStyle = underlineStyle;
    this.underlineColor = underlineColor;
    this.outline = outline;
    this.shadow = shadow;
    this.emboss = emboss;
    this.engrave = engrave;
    if (!finite(spacing)
        || spacing < -100
        || spacing > 100
        || !finite(scale)
        || scale < 1
        || scale > 600
        || !finite(position)
        || position < -1584
        || position > 1584) throw new IllegalArgumentException("Invalid character geometry");
    doubleUnderline = "double".equals(underlineStyle);
    doubleStrike = s;
    smallCaps = small;
    allCaps = caps;
    this.hidden = hidden;
    this.spacing = spacing;
    this.scale = scale;
    this.position = position;
  }

  private static boolean finite(float f) {
    return !Float.isNaN(f) && !Float.isInfinite(f);
  }

  public String value(String key) {
    if ("doubleUnderline".equals(key)) return "" + doubleUnderline;
    if ("doubleStrike".equals(key)) return "" + doubleStrike;
    if ("smallCaps".equals(key)) return "" + smallCaps;
    if ("allCaps".equals(key)) return "" + allCaps;
    if ("hidden".equals(key)) return "" + hidden;
    if ("characterSpacing".equals(key)) return "" + spacing;
    if ("characterScale".equals(key)) return "" + scale;
    if ("characterPosition".equals(key)) return "" + position;
    if ("underlineStyle".equals(key)) return underlineStyle;
    if ("underlineColor".equals(key))
      return underlineColor < 0 ? "auto" : TextUtil.padded(underlineColor, 16, 6);
    if ("outline".equals(key)) return "" + outline;
    if ("shadow".equals(key)) return "" + shadow;
    if ("emboss".equals(key)) return "" + emboss;
    if ("engrave".equals(key)) return "" + engrave;
    throw new IllegalArgumentException("Unknown character property: " + key);
  }

  public CharacterFormat with(String k, String v) {
    boolean known = false;
    for (String key : KEYS)
      if (key.equals(k)) {
        known = true;
        break;
      }
    if (!known) return this;
    return new CharacterFormat(
        "underlineStyle".equals(k)
            ? v
            : "doubleUnderline".equals(k)
                ? (Boolean.parseBoolean(v) ? "double" : doubleUnderline ? "none" : underlineStyle)
                : underlineStyle,
        "underlineColor".equals(k)
            ? ("auto".equals(v) ? -1 : Integer.parseInt(v, 16))
            : underlineColor,
        "doubleStrike".equals(k) ? Boolean.parseBoolean(v) : doubleStrike,
        "smallCaps".equals(k) ? Boolean.parseBoolean(v) : smallCaps,
        "allCaps".equals(k) ? Boolean.parseBoolean(v) : allCaps,
        "hidden".equals(k) ? Boolean.parseBoolean(v) : hidden,
        "characterSpacing".equals(k) ? Float.parseFloat(v) : spacing,
        "characterScale".equals(k) ? Float.parseFloat(v) : scale,
        "characterPosition".equals(k) ? Float.parseFloat(v) : position,
        "outline".equals(k) ? Boolean.parseBoolean(v) : outline,
        "shadow".equals(k) ? Boolean.parseBoolean(v) : shadow,
        "emboss".equals(k) ? Boolean.parseBoolean(v) : emboss,
        "engrave".equals(k) ? Boolean.parseBoolean(v) : engrave);
  }

  public CharacterFormat merge(CharacterFormat before, CharacterFormat after) {
    CharacterFormat out = this;
    for (String k : KEYS)
      if (!"doubleUnderline".equals(k) && value(k).equals(before.value(k)))
        out = out.with(k, after.value(k));
    return out;
  }

  public boolean equals(Object o) {
    if (!(o instanceof CharacterFormat)) return false;
    CharacterFormat f = (CharacterFormat) o;
    return doubleUnderline == f.doubleUnderline
        && doubleStrike == f.doubleStrike
        && smallCaps == f.smallCaps
        && allCaps == f.allCaps
        && hidden == f.hidden
        && spacing == f.spacing
        && scale == f.scale
        && position == f.position
        && underlineStyle.equals(f.underlineStyle)
        && underlineColor == f.underlineColor
        && outline == f.outline
        && shadow == f.shadow
        && emboss == f.emboss
        && engrave == f.engrave;
  }

  public int hashCode() {
    return (doubleUnderline ? 1 : 0)
        + (doubleStrike ? 2 : 0)
        + (smallCaps ? 4 : 0)
        + (allCaps ? 8 : 0)
        + (hidden ? 16 : 0)
        + Float.floatToIntBits(spacing) * 31
        + Float.floatToIntBits(scale) * 17
        + Float.floatToIntBits(position)
        + underlineStyle.hashCode() * 13
        + underlineColor * 7
        + (outline ? 32 : 0)
        + (shadow ? 64 : 0)
        + (emboss ? 128 : 0)
        + (engrave ? 256 : 0);
  }
}
