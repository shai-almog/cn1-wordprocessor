package com.codenameone.wordprocessor;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;

/** Headless desktop metrics adapter. Font-dependent output must be compared with pinned fonts. */
public final class DesktopLayout {
  private DesktopLayout() {}

  public static LayoutEngine.Result layout(Document d) {
    final FontRenderContext context = new FontRenderContext(new AffineTransform(), true, true);
    LayoutEngine engine =
        new LayoutEngine(
            new LayoutEngine.Metrics() {
              private Font font(TextStyle s) {
                return new Font(
                        "SansSerif", (s.bold ? Font.BOLD : 0) | (s.italic ? Font.ITALIC : 0), 12)
                    .deriveFont(s.size);
              }

              public float width(String text, TextStyle s) {
                return (float) font(s).getStringBounds(text, context).getWidth();
              }

              public float height(TextStyle s) {
                return font(s).getLineMetrics("Ag", context).getHeight();
              }
            });
    return engine.layout(d, 0);
  }
}
