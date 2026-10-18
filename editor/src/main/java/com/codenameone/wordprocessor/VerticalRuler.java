package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import com.codename1.ui.geom.Dimension;

/**
 * Word's vertical ruler beside the page: gray top and bottom margins around a white text band,
 * numbered in inches outward from the top margin, following the caret's page as it scrolls.
 * Drag the gray/white boundary to change the top or bottom margin.
 */
final class VerticalRuler extends Component {
  private final EditorSurface editor;
  private final Session session;
  private final Runnable changed;
  private int handle;

  VerticalRuler(EditorSurface e, Session s, Runnable c) {
    editor = e;
    session = s;
    changed = c;
    OfficeTheme.surface(this, OfficeTheme.CANVAS);
    e.addScrollListener((x, y, ox, oy) -> repaint());
  }

  protected Dimension calcPreferredSize() {
    return new Dimension(OfficeTheme.px(22), OfficeTheme.px(400));
  }

  private int origin() {
    // The editor sits beside this ruler in the same parent, so share its Y.
    return editor.getAbsoluteY() - getAbsoluteY() + getY() + editor.pageTop();
  }

  private Object[] shown;

  /** Repaints only when the page geometry or its position changed; edits rarely move it. */
  void sync() {
    PageSpec p = session.activeSection().raw.page;
    Object[] now = {p.width, p.height, p.top, p.bottom, editor.pixelsPerPoint(), origin()};
    if (java.util.Arrays.equals(now, shown)) return;
    shown = now;
    repaint();
  }

  public void paint(Graphics g) {
    super.paint(g);
    PageSpec p = session.activeSection().raw.page;
    float scale = editor.pixelsPerPoint();
    int y = origin(), x = getX() + OfficeTheme.px(3), w = OfficeTheme.px(16);
    int h = Math.round(p.height * scale);
    int top = y + Math.round(p.top * scale), bottom = y + Math.round((p.height - p.bottom) * scale);
    int[] clip = g.getClip();
    g.clipRect(getX(), getY(), getWidth(), getHeight());
    g.setColor(OfficeTheme.chrome(0xd4d4d4));
    g.fillRect(x, y, w, h);
    g.setColor(OfficeTheme.chrome(0xffffff));
    g.fillRect(x, top, w, bottom - top);
    g.setColor(OfficeTheme.chrome(0x707070));
    Font font = OfficeTheme.font(9, false);
    g.setFont(font);
    int mid = x + w / 2;
    for (int dir = -1; dir <= 1; dir += 2) {
      for (int eighth = 1; ; eighth++) {
        float at = p.top + dir * eighth * 9f;
        if (at < 0 || at > p.height) break;
        int ty = y + Math.round(at * scale);
        if (ty < getY() - 20 || ty > getY() + getHeight() + 20) continue;
        if (eighth % 8 == 0) {
          String n = Integer.toString(eighth / 8);
          g.drawString(n, mid - font.stringWidth(n) / 2, ty - font.getHeight() / 2);
        } else if (eighth % 4 == 0) {
          g.drawLine(mid - OfficeTheme.px(3), ty, mid + OfficeTheme.px(3), ty);
        } else if (eighth % 2 == 0) {
          g.drawLine(mid - OfficeTheme.px(1), ty, mid + OfficeTheme.px(1), ty);
        }
      }
    }
    g.setClip(clip);
  }

  public void pointerPressed(int x, int y) {
    PageSpec p = session.activeSection().raw.page;
    float s = editor.pixelsPerPoint();
    float value = (y - getAbsoluteY() - (origin() - getY())) / s;
    float near = OfficeTheme.px(8) / s;
    handle = Math.abs(value - p.top) < near ? 1 : Math.abs(value - (p.height - p.bottom)) < near ? 2 : 0;
  }

  public void pointerReleased(int x, int y) {
    if (handle == 0) return;
    PageSpec p = session.activeSection().raw.page;
    float value = (y - getAbsoluteY() - (origin() - getY())) / editor.pixelsPerPoint();
    float top = p.top, bottom = p.bottom;
    if (handle == 1) top = Math.max(0, Math.min(p.height - bottom - 72, value));
    else bottom = Math.max(0, Math.min(p.height - top - 72, p.height - value));
    session.section(
        session.activeSection().raw.page(new PageSpec(p.width, p.height, top, p.right, bottom, p.left)));
    handle = 0;
    changed.run();
    editor.refresh();
    repaint();
  }
}
