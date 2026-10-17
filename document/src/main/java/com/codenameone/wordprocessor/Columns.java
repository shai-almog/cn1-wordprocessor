package com.codenameone.wordprocessor;

import java.util.Arrays;

/**
 * Newspaper columns of a section, as DOCX w:cols. Equal columns share one gap; unequal
 * columns list each width and the gap after it, in points.
 */
public final class Columns {
  public static final Columns ONE = new Columns(1, 36, false, new float[0]);

  public final int count;
  public final float space;
  public final boolean separator;
  private final float[] widths;

  public Columns(int count, float space, boolean separator, float[] widths) {
    if (widths == null) widths = new float[0];
    if (count < 1 || count > 12 || !ok(space) || space > 300 || widths.length % 2 != 0
        || (widths.length > 0 && widths.length / 2 != count))
      throw new IllegalArgumentException("Columns must be 1-12 with non-negative spacing");
    for (float w : widths) if (!ok(w)) throw new IllegalArgumentException("Invalid column width");
    for (int i = 0; i < widths.length; i += 2)
      if (widths[i] < 18) throw new IllegalArgumentException("Columns must be at least 18pt wide");
    this.count = count;
    this.space = space;
    this.separator = separator;
    this.widths = widths.clone();
  }

  /** Equal-width columns. */
  public static Columns equal(int count, float space, boolean separator) {
    return new Columns(count, space, separator, new float[0]);
  }

  /** Word's Left/Right presets: two columns, the narrow one a third of the text width. */
  public static Columns preset(String name, float textWidth, float space) {
    float narrow = (textWidth - space) / 3, wide = (textWidth - space) * 2 / 3;
    if ("left".equals(name)) return new Columns(2, space, false, new float[] {narrow, space, wide, 0});
    if ("right".equals(name)) return new Columns(2, space, false, new float[] {wide, space, narrow, 0});
    int n = "two".equals(name) ? 2 : "three".equals(name) ? 3 : 1;
    return equal(n, space, false);
  }

  public boolean equalWidth() {
    return widths.length == 0;
  }

  /** Width and gap-after pairs for unequal columns; empty for equal columns. */
  public float[] widths() {
    return widths.clone();
  }

  /**
   * Column geometry within a text area: {offset, width} per column. Unequal widths are
   * scaled to fit when the page is narrower than the widths they were defined for.
   */
  public float[][] layout(float textWidth) {
    float[][] result = new float[count][2];
    if (equalWidth()) {
      float gap = count == 1 ? 0 : Math.min(space, textWidth / (count * 2));
      float w = (textWidth - gap * (count - 1)) / count;
      for (int i = 0; i < count; i++) result[i] = new float[] {i * (w + gap), w};
      return result;
    }
    float total = 0;
    for (int i = 0; i < widths.length - 2; i++) total += widths[i];
    total += widths[widths.length - 2];
    float scale = total > textWidth ? textWidth / total : 1;
    float x = 0;
    for (int i = 0; i < count; i++) {
      float w = widths[i * 2] * scale;
      if (i == count - 1) w = Math.max(1, textWidth - x);
      result[i] = new float[] {x, w};
      x += w + widths[i * 2 + 1] * scale;
    }
    return result;
  }

  private static boolean ok(float x) {
    return !Float.isNaN(x) && !Float.isInfinite(x) && x >= 0;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Columns)) return false;
    Columns c = (Columns) o;
    return count == c.count && space == c.space && separator == c.separator
        && Arrays.equals(widths, c.widths);
  }

  @Override
  public int hashCode() {
    return count * 31 + Arrays.hashCode(widths) + (separator ? 1 : 0) + Float.floatToIntBits(space);
  }
}
