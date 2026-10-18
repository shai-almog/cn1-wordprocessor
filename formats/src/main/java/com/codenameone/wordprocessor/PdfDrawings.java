package com.codenameone.wordprocessor;

import java.io.IOException;

/**
 * Paints {@link Drawing}s into a PDF content stream as vector paths and base-font text. PDF output
 * has no soft masks here, so partial transparency is blended against a white page.
 */
final class PdfDrawings {
  private PdfDrawings() {}

  static void paint(StringBuilder c, LayoutEngine.PlacedPicture pic, float pageHeight) {
    DrawingOps.Ops ops = DrawingOps.build(pic.picture().drawing, pic.width(), pic.height());
    float ox = pic.x(), oy = pageHeight - pic.y();
    c.append("q 1 j 1 J\n");
    for (DrawingOps.Path p : ops.paths) {
      if (p.points().length < 4) continue;
      for (int i = 0; i < p.points().length; i += 2)
        c.append(n(ox + p.points()[i])).append(' ').append(n(oy - p.points()[i + 1])).append(i == 0 ? " m " : " l ");
      boolean fill = p.closed() && p.fill() >= 0, stroke = p.stroke() >= 0 && p.width() > 0;
      if (p.closed()) c.append("h ");
      if (fill) c.append(rgb(blend(p.fill(), p.alpha()))).append(" rg ");
      if (stroke) c.append(rgb(blend(p.stroke(), p.alpha()))).append(" RG ").append(n(p.width())).append(" w ");
      c.append(fill && stroke ? "B" : fill ? "f" : stroke ? "S" : "n").append('\n');
    }
    for (DrawingOps.Text t : ops.texts) {
      String s = literal(t.text());
      float width = t.text().length() * t.size() * (t.bold() ? .56f : .5f);
      float x = ox + t.x(), base = oy - t.y() - t.size();
      if ("center".equals(t.align())) x += (t.width() - width) / 2;
      else if ("right".equals(t.align())) x += t.width() - width;
      c.append("BT ").append(rgb(t.color())).append(" rg ");
      if (t.outline() >= 0) c.append(rgb(t.outline())).append(" RG 0.75 w 2 Tr ");
      c.append("/F0 ").append(n(t.size())).append(" Tf ");
      if (t.rotation() != 0) {
        double a = Math.toRadians(-t.rotation());
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        float rx = ox + t.x() + (t.rotation() == 90 ? t.size() : 0), ry = oy - t.y() - (t.rotation() == 90 ? 0 : t.width());
        c.append(n(cos)).append(' ').append(n(sin)).append(' ').append(n(-sin)).append(' ').append(n(cos))
            .append(' ').append(n(rx)).append(' ').append(n(ry));
      } else c.append(t.italic() ? "1 0 0.2 1 " : "1 0 0 1 ").append(n(x)).append(' ').append(n(base));
      c.append(" Tm (").append(s).append(") Tj ET\n");
    }
    c.append("Q\n");
  }

  private static int blend(int rgb, int alpha) {
    if (alpha >= 255) return rgb;
    int r = (rgb >> 16 & 255) * alpha / 255 + 255 - alpha;
    int g = (rgb >> 8 & 255) * alpha / 255 + 255 - alpha;
    int b = (rgb & 255) * alpha / 255 + 255 - alpha;
    return r << 16 | g << 8 | b;
  }

  private static String rgb(int c) {
    return n((c >> 16 & 255) / 255f) + " " + n((c >> 8 & 255) / 255f) + " " + n((c & 255) / 255f);
  }

  private static String n(float f) {
    return String.valueOf(Math.round(f * 100) / 100f);
  }

  /** Base-font literal; characters outside WinAnsi print as '?'. */
  private static String literal(String s) {
    var b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      int c;
      try {
        c = PdfExporter.winAnsi(s.charAt(i));
      } catch (IOException unsupported) {
        c = '?';
      }
      if (c == '(' || c == ')' || c == '\\') b.append('\\').append((char) c);
      else if (c > 126) b.append('\\').append(TextUtil.padded(c, 8, 3));
      else b.append((char) c);
    }
    return b.toString();
  }
}
