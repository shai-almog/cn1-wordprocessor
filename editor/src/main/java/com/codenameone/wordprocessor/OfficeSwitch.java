package com.codenameone.wordprocessor;

import com.codename1.ui.Button;
import com.codename1.ui.Graphics;
import com.codename1.ui.geom.Dimension;

/** Office pill switch (AutoSave): 30x16 points, accent when on, outlined when off. */
final class OfficeSwitch extends Button {
  private boolean on;

  OfficeSwitch(String accessibility) {
    super("");
    setAccessibilityText(accessibility);
    getAllStyles().setBgTransparency(0);
    getAllStyles().setBorder(com.codename1.ui.plaf.Border.createEmpty());
    OfficeTheme.space(this, 0, 0, 0, 0);
  }

  void setOn(boolean value) {
    if (on != value) {
      on = value;
      repaint();
    }
  }

  boolean isOn() {
    return on;
  }

  @Override
  protected Dimension calcPreferredSize() {
    return new Dimension(OfficeTheme.px(30), OfficeTheme.px(22));
  }

  @Override
  public void paint(Graphics g) {
    int w = OfficeTheme.px(30), h = OfficeTheme.px(16);
    int x = getX(), y = getY() + (getHeight() - h) / 2;
    boolean aa = g.isAntiAliased();
    g.setAntiAliased(true);
    // Outline as an inset fill: drawRoundRect strokes are invisible on the macOS Metal port.
    int line = Math.max(1, OfficeTheme.px(1));
    g.setColor(OfficeTheme.chrome(on ? OfficeTheme.ACCENT : OfficeTheme.INPUT_BORDER));
    g.fillRoundRect(x, y, w, h, h, h);
    g.setColor(OfficeTheme.chrome(on ? OfficeTheme.ACCENT : OfficeTheme.PAPER));
    g.fillRoundRect(x + line, y + line, w - 2 * line, h - 2 * line, h - 2 * line, h - 2 * line);
    int knob = OfficeTheme.px(10), cx = on ? x + w - OfficeTheme.px(8) : x + OfficeTheme.px(8);
    g.setColor(on ? 0xffffff : OfficeTheme.chrome(OfficeTheme.DIM));
    g.fillArc(cx - knob / 2, y + (h - knob) / 2, knob, knob, 0, 360);
    g.setAntiAliased(aa);
  }
}
