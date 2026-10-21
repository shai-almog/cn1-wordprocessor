package com.codenameone.wordprocessor;

/**
 * Per-section page options that do not change the body text flow: page color, a diagonal text
 * watermark, page borders, the page-number format and margin line numbering.
 */
public final class PageDecor {
  /** Page-number formats: Wordcraft's NumFormat set (every list number format but bullets). */
  public static final java.util.List<String> NUMBER_FORMATS = pageFormats();

  private static java.util.List<String> pageFormats() {
    var out = new java.util.ArrayList<String>();
    for (String f : ListNumbers.BASES)
      if (!"outline".equals(f) && !"legal".equals(f) && !"bullet".equals(f)) out.add(f);
    return java.util.Collections.unmodifiableList(out);
  }
  public static final java.util.List<String> LINE_RESTARTS =
      java.util.Arrays.asList("page", "section", "continuous");
  public static final PageDecor NONE = new PageDecor(-1, "", 0xc0c0c0, ParagraphBorders.NONE);

  /** PAGE field format when the field has no \* switch. */
  public final String numberFormat;

  /** Number every lineCountBy-th body line; 0 turns line numbers off. */
  public final int lineCountBy;

  public final String lineRestart;

  /** Page fill RRGGBB, or -1 for white paper. */
  public final int pageColor;

  /** Watermark text ("" for none) drawn diagonally behind the body. */
  public final String watermark;

  public final int watermarkColor;

  /** Page border sides; each side's space is its distance from the page edge in points. */
  public final ParagraphBorders borders;

  /** Section layout keys: vertical alignment, direction, text flow and the document grid. */
  public static final java.util.List<String> LAYOUT_KEYS =
      java.util.Arrays.asList("verticalAlign", "rtl", "textDirection", "grid", "gridPitch", "charPitch",
          // Wordcraft SectionProps: gutter (points, already part of the page's left margin),
          // line-number start (first number) and distance from the text (points).
          "gutter", "lineStart", "lineDistance");

  public static final java.util.List<String> VERTICAL_ALIGN =
      java.util.Arrays.asList("top", "center", "both", "bottom");
  public static final java.util.List<String> TEXT_DIRECTIONS =
      java.util.Arrays.asList("lrTb", "tbRl", "btLr", "lrTbV", "tbRlV", "tbLrV");
  public static final java.util.List<String> GRIDS =
      java.util.Arrays.asList("default", "lines", "linesAndChars", "snapToChars");

  private final java.util.TreeMap<String, String> layout;

  public PageDecor(int pageColor, String watermark, int watermarkColor, ParagraphBorders borders) {
    this(pageColor, watermark, watermarkColor, borders, "decimal", 0, "page");
  }

  public PageDecor(
      int pageColor,
      String watermark,
      int watermarkColor,
      ParagraphBorders borders,
      String numberFormat,
      int lineCountBy,
      String lineRestart) {
    this(pageColor, watermark, watermarkColor, borders, numberFormat, lineCountBy, lineRestart,
        new java.util.TreeMap<>());
  }

  private PageDecor(
      int pageColor,
      String watermark,
      int watermarkColor,
      ParagraphBorders borders,
      String numberFormat,
      int lineCountBy,
      String lineRestart,
      java.util.TreeMap<String, String> layout) {
    this.layout = layout;
    if (!NUMBER_FORMATS.contains(numberFormat)
        || lineCountBy < 0
        || lineCountBy > 100
        || !LINE_RESTARTS.contains(lineRestart))
      throw new IllegalArgumentException("Invalid page numbering");
    this.numberFormat = numberFormat;
    this.lineCountBy = lineCountBy;
    this.lineRestart = lineRestart;
    if (pageColor < -1 || pageColor > 0xffffff || watermark == null || watermark.length() > 200
        || watermark.indexOf('\n') >= 0 || watermarkColor < 0 || watermarkColor > 0xffffff
        || borders == null) throw new IllegalArgumentException("Invalid page decoration");
    this.pageColor = pageColor;
    this.watermark = watermark;
    this.watermarkColor = watermarkColor;
    this.borders = borders;
  }

  public boolean isEmpty() {
    return pageColor < 0 && watermark.isEmpty() && borders.isEmpty();
  }

  public PageDecor pageColor(int value) {
    return new PageDecor(value, watermark, watermarkColor, borders, numberFormat, lineCountBy, lineRestart, layout);
  }

  public PageDecor watermark(String text, int color) {
    return new PageDecor(pageColor, text, color, borders, numberFormat, lineCountBy, lineRestart, layout);
  }

  public PageDecor borders(ParagraphBorders value) {
    return new PageDecor(pageColor, watermark, watermarkColor, value, numberFormat, lineCountBy, lineRestart, layout);
  }

  public PageDecor numberFormat(String value) {
    return new PageDecor(pageColor, watermark, watermarkColor, borders, value, lineCountBy, lineRestart, layout);
  }

  public PageDecor lineNumbers(int countBy, String restart) {
    return new PageDecor(pageColor, watermark, watermarkColor, borders, numberFormat, countBy, restart, layout);
  }

  public String layout(String key, String fallback) {
    var v = layout.get(key);
    return v == null ? fallback : v;
  }

  public java.util.Map<String, String> layout() {
    return java.util.Collections.unmodifiableMap(layout);
  }

  /** Sets (or with an empty value clears) one of {@link #LAYOUT_KEYS}. */
  public PageDecor withLayout(String key, String value) {
    if (!LAYOUT_KEYS.contains(key)) throw new IllegalArgumentException("Unknown section layout: " + key);
    if (value != null && !value.isEmpty()) {
      if ("verticalAlign".equals(key) && !VERTICAL_ALIGN.contains(value)
          || "textDirection".equals(key) && !TEXT_DIRECTIONS.contains(value)
          || "grid".equals(key) && !GRIDS.contains(value)
          || "rtl".equals(key) && !"true".equals(value) && !"false".equals(value))
        throw new IllegalArgumentException("Invalid " + key + ": " + value);
      if (key.endsWith("Pitch")) {
        float f = Float.parseFloat(value);
        if (!(f >= 1 && f <= 200)) throw new IllegalArgumentException("Invalid " + key);
      }
      if ("gutter".equals(key) || "lineDistance".equals(key)) {
        float f = Float.parseFloat(value);
        if (!(f >= 0 && f <= 1000)) throw new IllegalArgumentException("Invalid " + key);
      }
      if ("lineStart".equals(key)) {
        int n = Integer.parseInt(value);
        if (n < 1 || n > 32768) throw new IllegalArgumentException("Invalid " + key);
      }
    }
    var next = new java.util.TreeMap<String, String>(layout);
    if (value == null || value.isEmpty() || "top".equals(value) && "verticalAlign".equals(key)
        || "false".equals(value) || "lrTb".equals(value) || "default".equals(value)) next.remove(key);
    else next.put(key, value);
    return new PageDecor(pageColor, watermark, watermarkColor, borders, numberFormat, lineCountBy, lineRestart, next);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof PageDecor)) return false;
    var d = (PageDecor) o;
    return pageColor == d.pageColor
        && watermark.equals(d.watermark)
        && watermarkColor == d.watermarkColor
        && borders.equals(d.borders)
        && numberFormat.equals(d.numberFormat)
        && lineCountBy == d.lineCountBy
        && lineRestart.equals(d.lineRestart)
        && layout.equals(d.layout);
  }

  @Override
  public int hashCode() {
    return pageColor ^ watermark.hashCode() * 31 ^ watermarkColor * 7 ^ borders.hashCode();
  }
}
