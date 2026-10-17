package com.codenameone.wordprocessor;

/** Design tab page decoration: page color, a diagonal text watermark and page borders. */
public final class PageDecor {
  public static final PageDecor NONE = new PageDecor(-1, "", 0xc0c0c0, ParagraphBorders.NONE);

  /** Page fill RRGGBB, or -1 for white paper. */
  public final int pageColor;

  /** Watermark text ("" for none) drawn diagonally behind the body. */
  public final String watermark;

  public final int watermarkColor;

  /** Page border sides; each side's space is its distance from the page edge in points. */
  public final ParagraphBorders borders;

  public PageDecor(int pageColor, String watermark, int watermarkColor, ParagraphBorders borders) {
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
    return new PageDecor(value, watermark, watermarkColor, borders);
  }

  public PageDecor watermark(String text, int color) {
    return new PageDecor(pageColor, text, color, borders);
  }

  public PageDecor borders(ParagraphBorders value) {
    return new PageDecor(pageColor, watermark, watermarkColor, value);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof PageDecor)) return false;
    PageDecor d = (PageDecor) o;
    return pageColor == d.pageColor
        && watermark.equals(d.watermark)
        && watermarkColor == d.watermarkColor
        && borders.equals(d.borders);
  }

  @Override
  public int hashCode() {
    return pageColor ^ watermark.hashCode() * 31 ^ watermarkColor * 7 ^ borders.hashCode();
  }
}
