package com.codenameone.wordprocessor;

/**
 * Per-section page options that do not change the body text flow: page color, a diagonal text
 * watermark, page borders, the page-number format and margin line numbering.
 */
public final class PageDecor {
  public static final java.util.List<String> NUMBER_FORMATS =
      java.util.Arrays.asList("decimal", "upperRoman", "lowerRoman", "upperLetter", "lowerLetter");
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
    return new PageDecor(value, watermark, watermarkColor, borders, numberFormat, lineCountBy, lineRestart);
  }

  public PageDecor watermark(String text, int color) {
    return new PageDecor(pageColor, text, color, borders, numberFormat, lineCountBy, lineRestart);
  }

  public PageDecor borders(ParagraphBorders value) {
    return new PageDecor(pageColor, watermark, watermarkColor, value, numberFormat, lineCountBy, lineRestart);
  }

  public PageDecor numberFormat(String value) {
    return new PageDecor(pageColor, watermark, watermarkColor, borders, value, lineCountBy, lineRestart);
  }

  public PageDecor lineNumbers(int countBy, String restart) {
    return new PageDecor(pageColor, watermark, watermarkColor, borders, numberFormat, countBy, restart);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof PageDecor)) return false;
    PageDecor d = (PageDecor) o;
    return pageColor == d.pageColor
        && watermark.equals(d.watermark)
        && watermarkColor == d.watermarkColor
        && borders.equals(d.borders)
        && numberFormat.equals(d.numberFormat)
        && lineCountBy == d.lineCountBy
        && lineRestart.equals(d.lineRestart);
  }

  @Override
  public int hashCode() {
    return pageColor ^ watermark.hashCode() * 31 ^ watermarkColor * 7 ^ borders.hashCode();
  }
}
