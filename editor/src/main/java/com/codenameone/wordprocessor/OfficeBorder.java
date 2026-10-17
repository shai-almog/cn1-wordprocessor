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
    Style s = c.getStyle();
    if (s.getBgTransparency() == 0) return;
    boolean aa = g.isAntiAliased();
    g.setAntiAliased(true);
    int alpha = g.getAlpha();
    g.setAlpha(s.getBgTransparency() & 0xff);
    g.setColor(s.getBgColor());
    int arc = arc();
    g.fillRoundRect(c.getX(), c.getY(), c.getWidth(), c.getHeight(), arc, arc);
    g.setAlpha(alpha);
    g.setAntiAliased(aa);
  }

  @Override
  public void paint(Graphics g, Component c) {
    if (stroke < 0) return;
    boolean aa = g.isAntiAliased();
    g.setAntiAliased(true);
    g.setColor(OfficeTheme.chrome(stroke));
    int arc = arc();
    g.drawRoundRect(c.getX(), c.getY(), c.getWidth() - 1, c.getHeight() - 1, arc, arc);
    g.setAntiAliased(aa);
  }

  private int arc() {
    return OfficeTheme.px(radius * 2);
  }
}
