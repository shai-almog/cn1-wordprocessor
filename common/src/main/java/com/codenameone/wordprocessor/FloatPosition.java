package com.codenameone.wordprocessor;

/**
 * A floating object's position as Wordcraft's Float: h_rel/h_align/x and v_rel/v_align/y, kept
 * as "hRel,hAlign,x;vRel,vAlign,y" (offsets in points; an empty align places by the offset).
 * Anchors: page, margin, column, character, leftMargin, rightMargin, insideMargin, outsideMargin
 * horizontally; page, margin, paragraph, line, topMargin, bottomMargin vertically.
 */
final class FloatPosition {
  final String hRel, hAlign, vRel, vAlign;
  final float x, y;

  FloatPosition(String hRel, String hAlign, float x, String vRel, String vAlign, float y) {
    this.hRel = hRel;
    this.hAlign = hAlign;
    this.x = x;
    this.vRel = vRel;
    this.vAlign = vAlign;
    this.y = y;
  }

  /** The parsed position, or null for "" or a malformed value. */
  static FloatPosition parse(String s) {
    if (s == null || s.isEmpty()) return null;
    int semi = s.indexOf(';');
    if (semi < 0) return null;
    String[] h = TextUtil.split(s.substring(0, semi), ','), v = TextUtil.split(s.substring(semi + 1), ',');
    if (h.length != 3 || v.length != 3) return null;
    try {
      return new FloatPosition(h[0], h[1], Float.parseFloat(h[2]), v[0], v[1], Float.parseFloat(v[2]));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @Override
  public String toString() {
    return hRel + "," + hAlign + "," + num(x) + ";" + vRel + "," + vAlign + "," + num(y);
  }

  static String num(float f) {
    float r = Math.round(f * 100) / 100f;
    return r == (int) r ? String.valueOf((int) r) : String.valueOf(r);
  }

  /** Wordcraft's align_in: the start of a w-wide object in area {start, extent}. */
  static float alignIn(float start, float extent, float offset, float w, String align, boolean oddPage) {
    switch (align) {
      case "start": return start;
      case "center": return start + (extent - w) / 2;
      case "end": return start + extent - w;
      case "inside": return oddPage ? start : start + extent - w;
      case "outside": return oddPage ? start + extent - w : start;
      default: return start + offset;
    }
  }

  /** The wp:positionH/V children: a wp:align or a wp:posOffset in EMUs. */
  void writeDocx(StringBuilder b) {
    b.append("<wp:positionH relativeFrom=\"").append(hRel).append("\">");
    part(b, hAlign, x, true);
    b.append("</wp:positionH><wp:positionV relativeFrom=\"").append(vRel).append("\">");
    part(b, vAlign, y, false);
    b.append("</wp:positionV>");
  }

  private static void part(StringBuilder b, String align, float off, boolean horiz) {
    if (align.isEmpty()) {
      b.append("<wp:posOffset>").append(Math.round(off * 12700)).append("</wp:posOffset>");
      return;
    }
    String a = "start".equals(align) ? (horiz ? "left" : "top") : "end".equals(align) ? (horiz ? "right" : "bottom") : align;
    b.append("<wp:align>").append(a).append("</wp:align>");
  }
}
