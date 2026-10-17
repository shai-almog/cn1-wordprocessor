package com.codenameone.wordprocessor;

/** Immutable embedded PNG/JPEG picture, sized in typographic points. */
public final class Picture implements Block {
  /** Crop, border and orientation applied on top of the picture's bytes. */
  public static final class Look {
    public static final java.util.List<String> COLOR_MODES =
        java.util.Arrays.asList("color", "grayscale", "washout", "blackWhite");

    public static final Look NONE = new Look(0, 0, 0, 0, -1, 0, 0, false, false);

    /** Fractions of the source cut from each edge; their sum per axis stays below 0.95. */
    public final float cropLeft, cropTop, cropRight, cropBottom;

    /** Border color RRGGBB, or -1 for none; width in points. */
    public final int borderColor;

    public final float borderWidth;

    /** Clockwise rotation in degrees, 0-359. */
    public final int rotation;

    public final boolean flipH, flipV;

    /** Picture Format > Corrections, Transparency and Color. */
    public final int brightness, contrast, transparency;

    /** color, grayscale, washout or blackWhite. */
    public final String colorMode;

    public Look(
        float cropLeft,
        float cropTop,
        float cropRight,
        float cropBottom,
        int borderColor,
        float borderWidth,
        int rotation,
        boolean flipH,
        boolean flipV) {
      this(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, rotation, flipH, flipV, 0, 0, 0, "color");
    }

    public Look(
        float cropLeft,
        float cropTop,
        float cropRight,
        float cropBottom,
        int borderColor,
        float borderWidth,
        int rotation,
        boolean flipH,
        boolean flipV,
        int brightness,
        int contrast,
        int transparency,
        String colorMode) {
      if (brightness < -100 || brightness > 100 || contrast < -100 || contrast > 100
          || transparency < 0 || transparency > 100 || !COLOR_MODES.contains(colorMode))
        throw new IllegalArgumentException("Invalid picture adjustment");
      this.brightness = brightness;
      this.contrast = contrast;
      this.transparency = transparency;
      this.colorMode = colorMode;
      for (float f : new float[] {cropLeft, cropTop, cropRight, cropBottom})
        if (!(f >= 0 && f < .95f)) throw new IllegalArgumentException("Crop must be 0-95%");
      if (cropLeft + cropRight >= .95f || cropTop + cropBottom >= .95f)
        throw new IllegalArgumentException("Crop leaves too little of the picture");
      if (borderColor < -1 || borderColor > 0xffffff || !(borderWidth >= 0 && borderWidth <= 72))
        throw new IllegalArgumentException("Invalid picture border");
      this.cropLeft = cropLeft;
      this.cropTop = cropTop;
      this.cropRight = cropRight;
      this.cropBottom = cropBottom;
      this.borderColor = borderColor;
      this.borderWidth = borderColor < 0 ? 0 : borderWidth;
      this.rotation = ((rotation % 360) + 360) % 360;
      this.flipH = flipH;
      this.flipV = flipV;
    }

    public boolean cropped() {
      return cropLeft + cropTop + cropRight + cropBottom > 0;
    }

    public Look crop(float l, float t, float r, float b) {
      return new Look(l, t, r, b, borderColor, borderWidth, rotation, flipH, flipV, brightness, contrast, transparency, colorMode);
    }

    public Look border(int color, float width) {
      return new Look(cropLeft, cropTop, cropRight, cropBottom, color, width, rotation, flipH, flipV, brightness, contrast, transparency, colorMode);
    }

    public Look rotate(int degrees, boolean h, boolean v) {
      return new Look(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, degrees, h, v, brightness, contrast, transparency, colorMode);
    }

    /** Brightness and contrast -100..100, transparency 0..100 percent. */
    public Look adjust(int brightness, int contrast, int transparency, String colorMode) {
      return new Look(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, rotation, flipH, flipV, brightness, contrast, transparency, colorMode);
    }

    public boolean adjusted() {
      return brightness != 0 || contrast != 0 || transparency != 0 || !"color".equals(colorMode);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Look)) return false;
      Look k = (Look) o;
      return cropLeft == k.cropLeft
          && cropTop == k.cropTop
          && cropRight == k.cropRight
          && cropBottom == k.cropBottom
          && borderColor == k.borderColor
          && borderWidth == k.borderWidth
          && rotation == k.rotation
          && flipH == k.flipH
          && flipV == k.flipV
          && brightness == k.brightness
          && contrast == k.contrast
          && transparency == k.transparency
          && colorMode.equals(k.colorMode);
    }

    @Override
    public int hashCode() {
      return (int) (cropLeft * 1000 + cropTop * 100 + cropRight * 10 + cropBottom)
          ^ borderColor
          ^ rotation << 20
          ^ (flipH ? 1 << 29 : 0)
          ^ (flipV ? 1 << 30 : 0)
          ^ brightness * 31 ^ contrast * 131 ^ transparency * 7 ^ colorMode.hashCode();
    }
  }

  private final String id;
  private final byte[] data;
  public final String mime, alt, align;
  public final float width, height;
  public final int pixelWidth, pixelHeight, channels;
  public final Look look;

  public Picture(String id, byte[] data, float width, float height, String alt, String align) {
    this(id, data, width, height, alt, align, Look.NONE, true);
  }

  public Picture(
      String id, byte[] data, float width, float height, String alt, String align, Look look) {
    this(id, data, width, height, alt, align, look, true);
  }

  private Picture(
      String id,
      byte[] data,
      float width,
      float height,
      String alt,
      String align,
      Look look,
      boolean copy) {
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
        || look == null
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
    this.look = look;
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
    return new Picture(id, data, width, height, alt, align, look, false);
  }

  public Picture resize(float width, float height, String alt, String align) {
    return new Picture(id, data, width, height, alt, align, look, false);
  }

  public Picture look(Look value) {
    return new Picture(id, data, width, height, alt, align, value, false);
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
