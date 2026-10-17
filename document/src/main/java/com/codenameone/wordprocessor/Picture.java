package com.codenameone.wordprocessor;

/** Immutable embedded PNG/JPEG picture, sized in typographic points. */
public final class Picture implements Block {
  private final String id;
  private final byte[] data;
  public final String mime, alt, align;
  public final float width, height;
  public final int pixelWidth, pixelHeight, channels;

  public Picture(String id, byte[] data, float width, float height, String alt, String align) {
    this(id, data, width, height, alt, align, true);
  }

  private Picture(
      String id, byte[] data, float width, float height, String alt, String align, boolean copy) {
    if (id == null
        || data == null
        || data.length < 24
        || data.length > 20 * 1024 * 1024
        || alt == null
        || !finite(width)
        || !finite(height)
        || width <= 0
        || height <= 0
        || width > 10000
        || height > 10000
        || !("left".equals(align) || "center".equals(align) || "right".equals(align)))
      throw new IllegalArgumentException("Invalid picture");
    int[] shape = shape(data);
    pixelWidth = shape[0];
    pixelHeight = shape[1];
    channels = shape[2];
    if (pixelWidth <= 0 || pixelHeight <= 0 || (long) pixelWidth * pixelHeight > 16000000)
      throw new IllegalArgumentException("Picture exceeds 16 megapixels");
    mime = shape[3] == 1 ? "image/png" : "image/jpeg";
    this.id = id;
    this.data = copy ? data.clone() : data;
    this.width = width;
    this.height = height;
    this.alt = alt;
    this.align = align;
  }

  private boolean finite(float n) {
    return !Float.isNaN(n) && !Float.isInfinite(n);
  }

  public String id() {
    return id;
  }

  public byte[] bytes() {
    return data.clone();
  }

  public Picture copy(String id) {
    return new Picture(id, data, width, height, alt, align, false);
  }

  public Picture resize(float width, float height, String alt, String align) {
    return new Picture(id, data, width, height, alt, align, false);
  }

  public static int[] shape(byte[] b) {
    if (b != null
        && b.length >= 33
        && (b[0] & 255) == 137
        && b[1] == 80
        && b[2] == 78
        && b[3] == 71
        && b[4] == 13
        && b[5] == 10
        && b[6] == 26
        && b[7] == 10) {
      if (integer(b, 8) != 13 || b[12] != 73 || b[13] != 72 || b[14] != 68 || b[15] != 82)
        throw new IllegalArgumentException("Invalid PNG header");
      return new int[] {
        integer(b, 16), integer(b, 20), (b[25] & 255) == 6 ? 4 : (b[25] & 255) == 2 ? 3 : 1, 1
      };
    }
    if (b != null && b.length > 4 && (b[0] & 255) == 255 && (b[1] & 255) == 216) {
      int at = 2;
      while (at + 4 <= b.length) {
        if ((b[at++] & 255) != 255) break;
        while (at < b.length && (b[at] & 255) == 255) at++;
        if (at >= b.length) break;
        int marker = b[at++] & 255;
        if (marker == 217 || marker == 218) break;
        if (marker == 1 || marker >= 208 && marker <= 215) continue;
        if (at + 2 > b.length) break;
        int size = ((b[at] & 255) << 8) | (b[at + 1] & 255);
        if (size < 2 || at + size > b.length) break;
        if ((marker == 192 || marker == 193 || marker == 194) && size >= 8 && b[at + 2] == 8)
          return new int[] {
            ((b[at + 5] & 255) << 8) | (b[at + 6] & 255),
            ((b[at + 3] & 255) << 8) | (b[at + 4] & 255),
            b[at + 7] & 255,
            2
          };
        at += size;
      }
    }
    throw new IllegalArgumentException("Choose a valid PNG or JPEG picture");
  }

  private static int integer(byte[] b, int at) {
    return (b[at] & 255) << 24 | (b[at + 1] & 255) << 16 | (b[at + 2] & 255) << 8 | b[at + 3] & 255;
  }
}
