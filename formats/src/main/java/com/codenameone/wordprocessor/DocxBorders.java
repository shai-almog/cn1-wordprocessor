package com.codenameone.wordprocessor;

import java.util.Arrays;
import java.util.Set;
import com.codename1.xml.Element;

/** w:pBdr and w:shd for paragraphs. w:sz is eighths of a point; w:space is points. */
final class DocxBorders {
  private static final String[] SIDES = {"top", "left", "bottom", "right"};

  private DocxBorders() {}

  static ParagraphBorders read(Element pBdr, Set<String> warnings) {
    ParagraphBorders.Side[] sides = new ParagraphBorders.Side[4];
    for (int i = 0; i < 4; i++) {
      Element e = Xml.child(pBdr, SIDES[i]);
      if (e == null && "left".equals(SIDES[i])) e = Xml.child(pBdr, "start");
      if (e == null && "right".equals(SIDES[i])) e = Xml.child(pBdr, "end");
      sides[i] = side(e, warnings);
    }
    if (Xml.child(pBdr, "between") != null || Xml.child(pBdr, "bar") != null)
      warnings.add("Paragraph between/bar borders are not shown");
    return new ParagraphBorders(sides[0], sides[1], sides[2], sides[3]);
  }

  private static ParagraphBorders.Side side(Element e, Set<String> warnings) {
    if (e == null) return ParagraphBorders.Side.NONE;
    String val = Xml.attr(e, "val", "none");
    if ("none".equals(val) || "nil".equals(val)) return ParagraphBorders.Side.NONE;
    String style = val;
    if (!Arrays.asList(ParagraphBorders.STYLES).contains(val)) {
      style = "dotDash".equals(val) || "dotDotDash".equals(val) ? "dashed" : "single";
      warnings.add("Paragraph border style " + val + " shown as " + style);
    }
    String color = Xml.attr(e, "color", "auto");
    float width = Math.min(12, Math.max(.25f, Float.parseFloat(Xml.attr(e, "sz", "4")) / 8f));
    float space = Math.min(31, Math.max(0, Float.parseFloat(Xml.attr(e, "space", "0"))));
    return new ParagraphBorders.Side(
        width, "auto".equals(color) || color.length() != 6 ? 0 : Integer.parseInt(color, 16), space, style);
  }

  static int shading(Element shd) {
    String fill = Xml.attr(shd, "fill", "auto");
    return "auto".equals(fill) || fill.length() != 6 ? -1 : Integer.parseInt(fill, 16);
  }

  static void write(StringBuilder b, ParagraphFormat f) {
    if (!f.borders.isEmpty()) {
      b.append("<w:pBdr>");
      ParagraphBorders.Side[] sides = {f.borders.top, f.borders.left, f.borders.bottom, f.borders.right};
      for (int i = 0; i < 4; i++)
        if (sides[i].present())
          b.append("<w:")
              .append(SIDES[i])
              .append(" w:val=\"")
              .append(sides[i].style)
              .append("\" w:sz=\"")
              .append(Math.round(sides[i].width * 8))
              .append("\" w:space=\"")
              .append(Math.round(sides[i].space))
              .append("\" w:color=\"")
              .append(hex(sides[i].color))
              .append("\"/>");
      b.append("</w:pBdr>");
    }
    if (f.shading >= 0)
      b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(hex(f.shading)).append("\"/>");
  }

  private static String hex(int rgb) {
    return Integer.toHexString(0x1000000 | rgb).substring(1).toUpperCase();
  }
}
