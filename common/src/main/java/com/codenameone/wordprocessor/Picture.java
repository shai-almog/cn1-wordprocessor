package com.codenameone.wordprocessor;

/**
 * Immutable embedded picture, sized in typographic points. GIF, BMP, TIFF and WebP payloads are
 * decoded once to PNG for display and PDF; the original bytes are kept for DOCX and native files.
 */
public final class Picture implements Block {
  /** Crop, border and orientation applied on top of the picture's bytes. */
  public static final class Look {
    public static final java.util.List<String> COLOR_MODES =
        java.util.Arrays.asList("color", "grayscale", "washout", "blackWhite");

    public static final java.util.List<String> EFFECTS =
        java.util.Arrays.asList("none", "blur", "pencilSketch", "posterize", "negative", "glowEdges", "sharpen");

    public static final java.util.List<String> WRAPS =
        java.util.Arrays.asList("inline", "square", "tight", "through", "topBottom", "behind", "front");
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

    /** Artistic effect (one of EFFECTS) and background-removal tolerance 0-100, or -1 for off. */
    public final String effect;

    public final int backgroundTolerance;


    /** Text wrapping and the wrap polygon (see the full constructor). */
    public final String wrap, wrapPolygon;
    /** See {@link #position(String)}. */
    public final String position;

    public Look wrap(String wrap, String polygon) {
      return new Look(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, rotation, flipH, flipV, brightness, contrast, transparency, colorMode, effect, backgroundTolerance, wrap, polygon, position);
    }

    /**
     * A float's position as Wordcraft's Float h_rel/h_align/x and v_rel/v_align/y:
     * "hRel,hAlign,x;vRel,vAlign,y" (points; an empty align places by the offset), or "" to
     * stand at the anchor paragraph aligned by the picture's align.
     */
    public Look position(String position) {
      return new Look(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, rotation, flipH, flipV, brightness, contrast, transparency, colorMode, effect, backgroundTolerance, wrap, wrapPolygon, position);
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
      this(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, rotation, flipH, flipV,
          brightness, contrast, transparency, colorMode, "none", -1);
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
        String colorMode,
        String effect,
        int backgroundTolerance) {
      this(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, rotation, flipH, flipV,
          brightness, contrast, transparency, colorMode, effect, backgroundTolerance, "inline", "");
    }

    /**
     * Wrapping as Wordcraft's Float: inline, square, tight, through, topBottom, behind (wrapNone
     * with behindDoc) or front; the polygon is "edited;x,y x,y ..." in the 21600 wrap space.
     */
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
        String colorMode,
        String effect,
        int backgroundTolerance,
        String wrap,
        String wrapPolygon) {
      this(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, rotation, flipH, flipV,
          brightness, contrast, transparency, colorMode, effect, backgroundTolerance, wrap, wrapPolygon, "");
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
        String colorMode,
        String effect,
        int backgroundTolerance,
        String wrap,
        String wrapPolygon,
        String position) {
      this.position = position == null ? "" : position;
      if (!WRAPS.contains(wrap)) throw new IllegalArgumentException("Invalid wrap");
      this.wrap = wrap;
      this.wrapPolygon = wrapPolygon == null ? "" : wrapPolygon;
      if (!EFFECTS.contains(effect) || backgroundTolerance < -1 || backgroundTolerance > 100)
        throw new IllegalArgumentException("Invalid picture effect");
      this.effect = effect;
      this.backgroundTolerance = backgroundTolerance;
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
      return new Look(l, t, r, b, borderColor, borderWidth, rotation, flipH, flipV, brightness, contrast, transparency, colorMode, effect, backgroundTolerance, wrap, wrapPolygon, position);
    }

    public Look border(int color, float width) {
      return new Look(cropLeft, cropTop, cropRight, cropBottom, color, width, rotation, flipH, flipV, brightness, contrast, transparency, colorMode, effect, backgroundTolerance, wrap, wrapPolygon, position);
    }

    public Look rotate(int degrees, boolean h, boolean v) {
      return new Look(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, degrees, h, v, brightness, contrast, transparency, colorMode, effect, backgroundTolerance, wrap, wrapPolygon, position);
    }

    /** Brightness and contrast -100..100, transparency 0..100 percent. */
    public Look adjust(int brightness, int contrast, int transparency, String colorMode) {
      return new Look(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, rotation, flipH, flipV, brightness, contrast, transparency, colorMode, effect, backgroundTolerance, wrap, wrapPolygon, position);
    }

    public Look effect(String name) {
      return new Look(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, rotation, flipH, flipV, brightness, contrast, transparency, colorMode, name, backgroundTolerance, wrap, wrapPolygon, position);
    }

    /** Tolerance 0-100, or -1 to keep the background. */
    public Look removeBackground(int tolerance) {
      return new Look(cropLeft, cropTop, cropRight, cropBottom, borderColor, borderWidth, rotation, flipH, flipV, brightness, contrast, transparency, colorMode, effect, tolerance, wrap, wrapPolygon, position);
    }

    public boolean adjusted() {
      return !"none".equals(effect) || backgroundTolerance >= 0 || brightness != 0 || contrast != 0 || transparency != 0 || !"color".equals(colorMode);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Look)) return false;
      var k = (Look) o;
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
          && colorMode.equals(k.colorMode)
          && effect.equals(k.effect)
          && backgroundTolerance == k.backgroundTolerance
          && wrap.equals(k.wrap)
          && wrapPolygon.equals(k.wrapPolygon)
          && position.equals(k.position);
    }

    @Override
    public int hashCode() {
      return (int) (cropLeft * 1000 + cropTop * 100 + cropRight * 10 + cropBottom)
          ^ borderColor
          ^ rotation << 20
          ^ (flipH ? 1 << 29 : 0)
          ^ (flipV ? 1 << 30 : 0)
          ^ brightness * 31 ^ contrast * 131 ^ transparency * 7 ^ colorMode.hashCode() ^ effect.hashCode() * 17 ^ backgroundTolerance;
    }
  }

  private final String id;
  private final byte[] data, original;
  public final String mime, alt, align;
  public final float width, height;
  public final int pixelWidth, pixelHeight, channels;
  public final Look look;
  /** Vector content drawn instead of the (placeholder) image, or null for a raster picture. */
  public final Drawing drawing;

  /** Text wrapping: a drawing's own "wrap", else the picture look's. */
  public String wrap() {
    return drawing != null ? drawing.get("wrap", "inline") : look == null ? "inline" : look.wrap;
  }

  /**
   * As Wordcraft's arrange.bounds drag: the object floating (Square when it was inline) with its
   * top left at page point x, y of its anchor's page.
   */
  public Picture movedTo(float x, float y) {
    String side = FloatPosition.sideOf(position());
    String pos = new FloatPosition("page", "", x, "page", "", y, side).toString();
    String w = "inline".equals(wrap()) ? "square" : wrap();
    if (drawing != null) return drawing(drawing.with("wrap", w).with("position", pos));
    return look(look.wrap(w, look.wrapPolygon).position(pos));
  }

  /**
   * Floats the object in its table cell at x, y: offsets from the cell's content left and the
   * anchor paragraph's top (Word's layoutInCell; Square wrapped if it was inline).
   */
  public Picture movedInCell(float x, float y) {
    String side = FloatPosition.sideOf(position());
    String pos = new FloatPosition("column", "", x, "paragraph", "", y, side).toString();
    String w = "inline".equals(wrap()) ? "square" : wrap();
    if (drawing != null) return drawing(drawing.with("wrap", w).with("position", pos));
    return look(look.wrap(w, look.wrapPolygon).position(pos));
  }

  /**
   * As Wordcraft's arrange.wrapText: text flows on {@code side} of the float. A one-sided wrap is
   * carried by the position, so a paragraph-placed float gets its own column/paragraph place.
   */
  public Picture wrapSide(String side) {
    FloatPosition f = FloatPosition.parse(position());
    if (f == null) {
      if ("bothSides".equals(side)) return this;
      String a = "right".equals(align) ? "end" : "center".equals(align) ? "center" : "start";
      f = new FloatPosition("column", a, 0, "paragraph", "", 0);
    }
    String pos = f.side(side).toString();
    if (drawing != null) return drawing(drawing.with("position", pos));
    return look(look.position(pos));
  }

  /**
   * As Wordcraft's move_object across pages: the float at body block {@code block} dropped at page
   * point x, y of {@code page} moves before the paragraph there (the last one starting at or above
   * y, else the page's first) and floats at that page point. Null when the page has no body
   * paragraph to anchor to.
   */
  static Document reanchored(Document doc, LayoutEngine.Result layout, int block, int page, float x, float y) {
    if (page < 0 || page >= layout.pages.size() || !(doc.blocks.get(block) instanceof Picture p)) return null;
    int anchor = -1, first = -1;
    for (LayoutEngine.Line l : layout.pages.get(page).lines) {
      if (l.block < 0 || l.row >= 0 || l.start != 0 || !(doc.blocks.get(l.block) instanceof Paragraph)) continue;
      if (first < 0) first = l.block;
      if (l.y <= y + .5f) anchor = l.block;
    }
    if (anchor < 0) anchor = first;
    if (anchor < 0) return null;
    var blocks = doc.blocks.remove(block);
    int at = anchor > block ? anchor - 1 : anchor;
    return doc.blocks(blocks.insert(at, p.movedTo(x, y)), doc.nextId);
  }

  /** As Wordcraft's arrange.nudge: a positioned float's offsets moved by dx, dy points. */
  public Picture nudged(float dx, float dy) {
    FloatPosition f = FloatPosition.parse(position());
    if (f == null || !f.hAlign.isEmpty() || !f.vAlign.isEmpty())
      throw new IllegalArgumentException("Drag the object to float it before nudging");
    String pos = new FloatPosition(f.hRel, "", f.x + dx, f.vRel, "", f.y + dy, f.side).toString();
    if (drawing != null) return drawing(drawing.with("position", pos));
    return look(look.position(pos));
  }

  /** A float's position ({@link Look#position(String)}): a drawing's own, else the look's. */
  public String position() {
    return drawing != null ? drawing.get("position", "") : look == null ? "" : look.position;
  }

  /** The wrap polygon ("edited;x,y x,y ..." in 21600 wrap space), or "". */
  public String wrapPolygon() {
    return drawing != null ? drawing.get("wrapPolygon", "") : look == null ? "" : look.wrapPolygon;
  }

  public Picture(String id, byte[] data, float width, float height, String alt, String align) {
    this(id, data, width, height, alt, align, Look.NONE, true);
  }

  public static Picture drawing(
      String id, Drawing drawing, float width, float height, String alt, String align) {
    if (drawing == null) throw new IllegalArgumentException("Invalid drawing");
    return new Picture(id, Drawing.PLACEHOLDER, width, height, alt, align, Look.NONE, drawing, false);
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
    this(id, data, width, height, alt, align, look, null, copy);
  }

  private Picture(
      String id,
      byte[] data,
      float width,
      float height,
      String alt,
      String align,
      Look look,
      Drawing drawing,
      boolean copy) {
    this(id, data, width, height, alt, align, look, drawing, copy, null);
  }

  private Picture(
      String id,
      byte[] data,
      float width,
      float height,
      String alt,
      String align,
      Look look,
      Drawing drawing,
      boolean copy,
      byte[] original) {
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
    if (original == null && PictureCodecs.mime(data) != null) {
      original = copy ? data.clone() : data;
      try {
        data = PictureCodecs.toPng(original);
      } catch (java.io.IOException ex) {
        throw new IllegalArgumentException(ex.getMessage(), ex);
      }
      copy = false;
    }
    this.original = original;
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
    this.drawing = drawing;
  }

  private boolean finite(float n) {
    return !Float.isNaN(n) && !Float.isInfinite(n);
  }

  public String id() {
    return id;
  }

  /** Displayable PNG or JPEG bytes. */
  public byte[] bytes() {
    return data.clone();
  }

  /** The bytes as inserted or imported: GIF/BMP/TIFF/WebP stay in their own format. */
  public byte[] originalBytes() {
    return (original != null ? original : data).clone();
  }

  public String originalMime() {
    return original != null ? PictureCodecs.mime(original) : mime;
  }

  public Picture copy(String id) {
    return new Picture(id, data, width, height, alt, align, look, drawing, false, original);
  }

  public Picture resize(float width, float height, String alt, String align) {
    return new Picture(id, data, width, height, alt, align, look, drawing, false, original);
  }

  public Picture look(Look value) {
    return new Picture(id, data, width, height, alt, align, value, drawing, false, original);
  }

  public Picture drawing(Drawing value) {
    if (value == null) throw new IllegalArgumentException("Invalid drawing");
    return new Picture(id, data, width, height, alt, align, look, value, false, original);
  }

  /** {width, height, channels, kind}; kind 1 PNG, 2 JPEG, 3 a format decoded to PNG. */
  public static int[] shape(byte[] b) {
    if (PictureCodecs.mime(b) != null) {
      try {
        var r = PictureCodecs.decode(b);
        return new int[] {r.width, r.height, 4, 3};
      } catch (java.io.IOException ex) {
        throw new IllegalArgumentException(ex.getMessage(), ex);
      }
    }
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
    throw new IllegalArgumentException("Choose a valid PNG, JPEG, GIF, BMP, TIFF, WebP, WMF or EMF picture");
  }

  private static int integer(byte[] b, int at) {
    return (b[at] & 255) << 24 | (b[at + 1] & 255) << 16 | (b[at + 2] & 255) << 8 | b[at + 3] & 255;
  }
}
