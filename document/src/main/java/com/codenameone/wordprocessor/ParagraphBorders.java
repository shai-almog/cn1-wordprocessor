package com.codenameone.wordprocessor;

/** Paragraph border sides (Word's w:pBdr); a side with zero width is absent. */
public final class ParagraphBorders {
  public static final String[] STYLES = {"single", "double", "dotted", "dashed", "thick"};

  public static final class Side {
    public static final Side NONE = new Side(0, 0, 0, "single");

    /** Width and distance from the text in points; color RRGGBB. */
    public final float width, space;

    public final int color;
    public final String style;

    public Side(float width, int color, float space, String style) {
      if (!(width >= 0 && width <= 12) || !(space >= 0 && space <= 31) || color < 0
          || color > 0xffffff || !java.util.Arrays.asList(STYLES).contains(style))
        throw new IllegalArgumentException("Invalid paragraph border");
      this.width = width;
      this.color = color;
      this.space = space;
      this.style = style;
    }

    public boolean present() {
      return width > 0;
    }

    /** Distance the side takes from the paragraph's text box. */
    public float extent() {
      return present() ? width + space : 0;
    }

    String encode() {
      return width + "," + Integer.toHexString(0x1000000 | color).substring(1) + "," + space + "," + style;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Side && encode().equals(((Side) o).encode());
    }

    @Override
    public int hashCode() {
      return encode().hashCode();
    }
  }

  public static final ParagraphBorders NONE =
      new ParagraphBorders(Side.NONE, Side.NONE, Side.NONE, Side.NONE);

  public final Side top, left, bottom, right;

  public ParagraphBorders(Side top, Side left, Side bottom, Side right) {
    if (top == null || left == null || bottom == null || right == null)
      throw new IllegalArgumentException("Invalid paragraph border");
    this.top = top;
    this.left = left;
    this.bottom = bottom;
    this.right = right;
  }

  public boolean isEmpty() {
    return !top.present() && !left.present() && !bottom.present() && !right.present();
  }

  /** Word's Borders gallery kinds: none, all/box, outside, top, bottom, left, right. */
  public static ParagraphBorders preset(String kind, Side side, ParagraphBorders current) {
    Side n = Side.NONE;
    if ("none".equals(kind)) return NONE;
    if ("all".equals(kind) || "box".equals(kind) || "outside".equals(kind))
      return new ParagraphBorders(side, side, side, side);
    if ("top".equals(kind))
      return new ParagraphBorders(current.top.present() ? n : side, current.left, current.bottom, current.right);
    if ("bottom".equals(kind))
      return new ParagraphBorders(current.top, current.left, current.bottom.present() ? n : side, current.right);
    if ("left".equals(kind))
      return new ParagraphBorders(current.top, current.left.present() ? n : side, current.bottom, current.right);
    if ("right".equals(kind))
      return new ParagraphBorders(current.top, current.left, current.bottom, current.right.present() ? n : side);
    throw new IllegalArgumentException("Unknown border kind " + kind);
  }

  /** "top=w,RRGGBB,space,style;left=...", omitting absent sides; "" when empty. */
  public String encode() {
    StringBuilder b = new StringBuilder();
    String[] names = {"top", "left", "bottom", "right"};
    Side[] sides = {top, left, bottom, right};
    for (int i = 0; i < 4; i++)
      if (sides[i].present()) {
        if (b.length() > 0) b.append(';');
        b.append(names[i]).append('=').append(sides[i].encode());
      }
    return b.toString();
  }

  public static ParagraphBorders decode(String value) {
    if (value == null || value.isEmpty()) return NONE;
    Side[] sides = {Side.NONE, Side.NONE, Side.NONE, Side.NONE};
    for (String part : TextUtil.split(value, ';')) {
      int eq = part.indexOf('=');
      String[] v = TextUtil.split(part.substring(eq + 1), ',');
      int at = java.util.Arrays.asList("top", "left", "bottom", "right").indexOf(part.substring(0, eq));
      if (at < 0 || v.length != 4) throw new IllegalArgumentException("Invalid paragraph border");
      sides[at] =
          new Side(Float.parseFloat(v[0]), Integer.parseInt(v[1], 16), Float.parseFloat(v[2]), v[3]);
    }
    return new ParagraphBorders(sides[0], sides[1], sides[2], sides[3]);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ParagraphBorders && encode().equals(((ParagraphBorders) o).encode());
  }

  @Override
  public int hashCode() {
    return encode().hashCode();
  }
}
