package com.codenameone.wordprocessor;

import com.codename1.ui.Component;
import com.codename1.ui.Graphics;
import com.codename1.ui.plaf.Border;
import com.codename1.ui.plaf.Style;

/**
 * Rounded Office control frame. The fill is the component's current style background, so
 * hover, pressed and checked states (and their dark-mode mapping) keep working; the stroke is
 * a light-theme color mapped through {@link OfficeTheme#chrome(int)} when painted.
 */
final class OfficeBorder extends Border {
  private final int stroke;
  private final float radius;

  /** @param stroke light-theme stroke color, or -1 for none; radius in points */
  OfficeBorder(int stroke, float radius) {
    this.stroke = stroke;
    this.radius = radius;
  }

  static OfficeBorder of(int stroke, float radius) {
    return new OfficeBorder(stroke, radius);
  }

  @Override
  public boolean isBackgroundPainter() {
    return true;
  }

  @Override
  public void paintBorderBackground(Graphics g, Component c) {
    var s = c.getStyle();
    boolean aa = g.isAntiAliased();
    g.setAntiAliased(true);
    int alpha = g.getAlpha();
    int arc = arc(), x = c.getX(), y = c.getY(), w = c.getWidth(), h = c.getHeight();
    // The outline is a stroke-colored round rect with the fill inset one hairline inside it.
    // Graphics.drawRoundRect strokes through the Metal shape pipeline, which renders nothing
    // visible on the macOS port, while fillRoundRect is reliable there.
    int line = 0;
    if (stroke >= 0) {
      line = Math.max(1, OfficeTheme.px(1));
      g.setAlpha(255);
      g.setColor(OfficeTheme.chrome(stroke));
      g.fillRoundRect(x, y, w, h, arc, arc);
      int inner = Math.max(0, arc - line);
      g.setColor(behind(c));
      g.fillRoundRect(x + line, y + line, w - 2 * line, h - 2 * line, inner, inner);
    }
    if (s.getBgTransparency() != 0) {
      int inner = Math.max(0, arc - line);
      g.setAlpha(s.getBgTransparency() & 0xff);
      g.setColor(s.getBgColor());
      g.fillRoundRect(x + line, y + line, w - 2 * line, h - 2 * line, inner, inner);
    }
    g.setAlpha(alpha);
    g.setAntiAliased(aa);
  }

  /** The color showing through behind c: the nearest opaque ancestor background. */
  private static int behind(Component c) {
    for (var p = c.getParent(); p != null; p = p.getParent()) {
      var ps = p.getStyle();
      if ((ps.getBgTransparency() & 0xff) == 255) return ps.getBgColor();
    }
    return OfficeTheme.chrome(OfficeTheme.PAPER);
  }

  @Override
  public void paint(Graphics g, Component c) {}

  private int arc() {
    return OfficeTheme.px(radius * 2);
  }
}
