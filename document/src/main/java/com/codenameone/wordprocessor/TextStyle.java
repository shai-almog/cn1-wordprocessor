package com.codenameone.wordprocessor;

/** Immutable character style. Sizes are in points. */
public final class TextStyle {
  private static final java.util.Locale CASE_LOCALE = new java.util.Locale("", "");
  public static final TextStyle DEFAULT =
      new TextStyle("Serif", 12, 0x202833, false, false, false, false, 0xffffff, "");
  public final String font, link;
  public final int color, background;
  public final float size;
  public final boolean bold, italic, underline, strike;
  public final String script;
  public final CharacterFormat characters;
  public final Revision revision, formatRevision;
  public final TextStyle previousStyle;

  public TextStyle(
      String f, float s, int c, boolean b, boolean i, boolean u, boolean x, int bg, String link) {
    this(f, s, c, b, i, u, x, bg, link, "baseline");
  }

  public TextStyle(
      String f,
      float s,
      int c,
      boolean b,
      boolean i,
      boolean u,
      boolean x,
      int bg,
      String link,
      String script) {
    this(f, s, c, b, i, u, x, bg, link, script, null, null, null, CharacterFormat.DEFAULT);
  }

  private TextStyle(
      String f,
      float s,
      int c,
      boolean b,
      boolean i,
      boolean u,
      boolean x,
      int bg,
      String link,
      String script,
      Revision revision,
      Revision formatRevision,
      TextStyle previousStyle,
      CharacterFormat characters) {
    this.characters =
        u && "none".equals(characters.underlineStyle)
            ? characters.with("underlineStyle", "single")
            : characters;
    this.formatRevision = formatRevision;
    this.previousStyle = previousStyle;
    this.revision = revision;
    if (!"baseline".equals(script) && !"superscript".equals(script) && !"subscript".equals(script))
      throw new IllegalArgumentException("Invalid baseline alignment");
    this.script = script;
    if (f == null || link == null || Float.isNaN(s) || s < 1 || s > 500)
      throw new IllegalArgumentException("Invalid font");
    font = f;
    size = s;
    color = c & 0xffffff;
    bold = b;
    italic = i;
    underline = "single".equals(this.characters.underlineStyle);
    strike = x;
    background = bg & 0xffffff;
    this.link = link;
  }

  public TextStyle with(String key, String value) {
    CharacterFormat effects = characters.with(key, value);
    if ("underline".equals(key))
      effects = effects.with("underlineStyle", Boolean.parseBoolean(value) ? "single" : "none");
    return new TextStyle(
        "font".equals(key) ? value : font,
        "size".equals(key) ? Float.parseFloat(value) : size,
        "color".equals(key) ? Integer.parseInt(value, 16) : color,
        "bold".equals(key) ? Boolean.parseBoolean(value) : bold,
        "italic".equals(key) ? Boolean.parseBoolean(value) : italic,
        ("underline".equals(key) || "underlineStyle".equals(key) || "doubleUnderline".equals(key))
            ? false
            : underline,
        "strike".equals(key) ? Boolean.parseBoolean(value) : strike,
        "background".equals(key) ? Integer.parseInt(value, 16) : background,
        "link".equals(key) ? value : link,
        "script".equals(key) ? value : script,
        revision,
        formatRevision,
        previousStyle,
        effects);
  }

  public TextStyle revision(Revision value) {
    if (revision == value) return this;
    return new TextStyle(
        font,
        size,
        color,
        bold,
        italic,
        underline,
        strike,
        background,
        link,
        script,
        value,
        formatRevision,
        previousStyle,
        characters);
  }

  public TextStyle formatRevision(Revision value, TextStyle before) {
    if ((value == null) != (before == null))
      throw new IllegalArgumentException("Incomplete formatting revision");
    return new TextStyle(
        font,
        size,
        color,
        bold,
        italic,
        underline,
        strike,
        background,
        link,
        script,
        revision,
        value,
        before,
        characters);
  }

  public TextStyle characters(CharacterFormat value) {
    return new TextStyle(
        font,
        size,
        color,
        bold,
        italic,
        false,
        strike,
        background,
        link,
        script,
        revision,
        formatRevision,
        previousStyle,
        value);
  }

  /** Case effects preserve source text and selection offsets. */
  public String displayText(String text) {
    return characters.hidden
        ? ""
        : characters.allCaps || characters.smallCaps ? text.toUpperCase(CASE_LOCALE) : text;
  }

  public boolean smallGlyph(String text) {
    return characters.smallCaps
        && !characters.allCaps
        && !text.isEmpty()
        && Character.isLowerCase(Character.codePointAt(text, 0));
  }

  public TextStyle glyphStyle(String text) {
    TextStyle result = renderedStyle();
    return smallGlyph(text)
        ? result.with("size", Float.toString(Math.max(1, result.size * .8f)))
        : result;
  }

  public TextStyle trackedWith(String key, String value, Revision change) {
    return with(key, value).trackedProperties(this, change);
  }

  public TextStyle trackedProperties(TextStyle before, Revision change) {
    TextStyle next = this;
    if (next.clean().equals(before.clean())
        || change == null
        || revision != null && revision.inserted() && revision.author.equals(change.author))
      return next;
    return next.formatRevision(
        before.formatRevision == null ? change : before.formatRevision,
        before.formatRevision == null ? before.clean() : before.previousStyle);
  }

  public TextStyle reviewFormat(String id, boolean accept) {
    if (formatRevision == null || id != null && !id.equals(formatRevision.id)) return this;
    return accept ? formatRevision(null, null) : previousStyle.revision(revision);
  }

  public TextStyle clean() {
    return revision(null).formatRevision(null, null);
  }

  public boolean hasUnderline() {
    return !"none".equals(characters.underlineStyle);
  }

  public TextStyle markup() {
    if (revision == null) return this;
    TextStyle result = with("color", revision.inserted() ? "1261a0" : "b32222");
    return revision.inserted()
        ? (hasUnderline() ? result : result.with("underline", "true"))
        : result.with("strike", "true");
  }

  public float renderedSize() {
    return "baseline".equals(script) ? size : Math.max(1, size * .65f);
  }

  public TextStyle renderedStyle() {
    return "baseline".equals(script)
        ? this
        : new TextStyle(
                font, renderedSize(), color, bold, italic, underline, strike, background, link)
            .characters(characters);
  }

  /** Positive values raise the rendered glyph baseline; negative values lower it. */
  public float baselineShift() {
    return characters.position
        + ("superscript".equals(script)
            ? size * .35f
            : "subscript".equals(script) ? -size * .2f : 0);
  }

  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TextStyle)) return false;
    TextStyle t = (TextStyle) o;
    // Cheap fields first: layout looks styles up per cluster, mostly against equal styles.
    return hashCode() == t.hashCode()
        && size == t.size
        && color == t.color
        && bold == t.bold
        && italic == t.italic
        && (formatRevision == null
            ? t.formatRevision == null
            : formatRevision.equals(t.formatRevision))
        && (previousStyle == null ? t.previousStyle == null : previousStyle.equals(t.previousStyle))
        && (revision == null ? t.revision == null : revision.equals(t.revision))
        && font.equals(t.font)
        && link.equals(t.link)
        && characters.equals(t.characters)
        && script.equals(t.script)
        && size == t.size
        && color == t.color
        && background == t.background
        && bold == t.bold
        && italic == t.italic
        && underline == t.underline
        && strike == t.strike;
  }

  private int hash;

  public int hashCode() {
    int h = hash;
    if (h == 0) hash = h = computeHash();
    return h;
  }

  private int computeHash() {
    return characters.hashCode()
        + font.hashCode() * 31
        + Float.floatToIntBits(size)
        + color
        + (bold ? 1 : 0)
        + (italic ? 2 : 0)
        + (underline ? 4 : 0)
        + (strike ? 8 : 0)
        + background
        + link.hashCode()
        + script.hashCode() * 31
        + (revision == null ? 0 : revision.hashCode())
        + (formatRevision == null ? 0 : formatRevision.hashCode());
  }
}
