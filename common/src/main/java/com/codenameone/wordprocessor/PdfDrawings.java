package com.codenameone.wordprocessor;

import java.io.IOException;

/**
 * Paints {@link Drawing}s into a PDF content stream as vector paths and text. With embedded fonts the
 * text (equation runs, drawing labels) goes through the same shaped, subset, fallback-aware path as
 * body text, so math alphanumerics, Greek and operators print from the base face or an installed
 * math-capable face (STIX Two Math, Cambria Math...); without them it is base-font WinAnsi text.
 * PDF output has no soft masks here, so partial transparency is blended against a white page.
 */
final class PdfDrawings {
  private PdfDrawings() {}

  static void paint(StringBuilder c, LayoutEngine.PlacedPicture pic, float pageHeight) {
    paint(c, pic, pageHeight, null, null);
  }

  /**
   * Measures drawing text (equation runs, labels) with the faces {@link #paint} shows it in: the
   * base style's family with the run's size, bold and italic, through the embedded fonts and their
   * fallbacks (the math fallback face for operators and math alphanumerics), so each glyph fits
   * the box the equation layout gives it. A face that cannot measure falls back to the base-font
   * widths, as painting falls back to base-font text.
   */
  static LayoutEngine.Metrics metrics(PdfFonts fonts, TextStyle base) {
    var plain = new PdfMetrics();
    return new LayoutEngine.Metrics() {
      public float width(String text, TextStyle style) {
        var shown = (base == null ? TextStyle.DEFAULT : base)
            .with("bold", style.bold ? "true" : "false")
            .with("italic", style.italic ? "true" : "false");
        try {
          return fonts.width(shown, text, style.size);
        } catch (IOException | RuntimeException unusable) {
          return plain.width(text, style);
        }
      }

      public float height(TextStyle style) {
        return plain.height(style);
      }

      public float ascent(TextStyle style) {
        return plain.ascent(style);
      }
    };
  }

  /** Paints with {@code fonts} (null for the base-font path), text styled from {@code base}. */
  static void paint(StringBuilder c, LayoutEngine.PlacedPicture pic, float pageHeight,
      PdfFonts fonts, TextStyle base) {
    DrawingOps.Ops ops = DrawingOps.build(pic.picture().drawing, pic.width(), pic.height(),
        fonts == null ? null : metrics(fonts, base));
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
      // Bold and italic text uses the bold/italic face of the base font set.
      TextStyle style = fonts == null ? null : (base == null ? TextStyle.DEFAULT : base)
          .with("bold", t.bold() ? "true" : "false").with("italic", t.italic() ? "true" : "false");
      String shown = null;
      float width = t.text().length() * t.size() * (t.bold() ? .56f : .5f);
      if (fonts != null && !t.text().isEmpty()) {
        try {
          shown = fonts.show(style, t.text(), false, n(t.size()));
          width = fonts.width(style, t.text(), t.size());
        } catch (IOException | RuntimeException unusable) {
          shown = null;
        }
      }
      float x = ox + t.x(), baseline = oy - t.y() - t.size();
      if ("center".equals(t.align())) x += (t.width() - width) / 2;
      else if ("right".equals(t.align())) x += t.width() - width;
      if (t.spin() != 0) {
        // Letters turned with their rotated group (clockwise on the page) about the box centre.
        double a = Math.toRadians(-t.spin());
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        float cx = ox + t.x() + t.width() / 2, cy = oy - t.y() - t.size() / 2;
        c.append("q ").append(n(cos)).append(' ').append(n(sin)).append(' ').append(n(-sin)).append(' ').append(n(cos))
            .append(' ').append(n(cx - cx * cos + cy * sin)).append(' ').append(n(cy - cx * sin - cy * cos)).append(" cm ");
      }
      c.append("BT ").append(rgb(t.color())).append(" rg ");
      if (t.outline() >= 0) c.append(rgb(t.outline())).append(" RG 0.75 w 2 Tr ");
      if (shown == null) c.append(t.bold() ? "/F1 " : "/F0 ").append(n(t.size())).append(" Tf ");
      if (t.rotation() != 0) {
        double a = Math.toRadians(-t.rotation());
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        float rx = ox + t.x() + (t.rotation() == 90 ? t.size() : 0), ry = oy - t.y() - (t.rotation() == 90 ? 0 : t.width());
        c.append(n(cos)).append(' ').append(n(sin)).append(' ').append(n(-sin)).append(' ').append(n(cos))
            .append(' ').append(n(rx)).append(' ').append(n(ry));
      } else
        c.append(t.italic() && shown == null ? "1 0 0.2 1 " : "1 0 0 1 ").append(n(x)).append(' ')
            .append(n(baseline));
      if (shown != null) c.append(" Tm ").append(shown).append(" ET\n");
      else c.append(" Tm (").append(s).append(") Tj ET\n");
      if (t.spin() != 0) c.append("Q\n");
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
