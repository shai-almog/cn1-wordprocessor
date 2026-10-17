package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import com.codename1.ui.geom.Dimension;

/** Horizontal inch ruler. Drag either margin marker to change the page's margins. */
final class PageRuler extends Component {
  private final EditorSurface editor;
  private final Session session;
  private final Runnable changed;
  private int handle;

  PageRuler(EditorSurface e, Session s, Runnable c) {
    editor = e;
    session = s;
    changed = c;
    OfficeTheme.surface(this, 0xe7e7e7);
  }

  protected Dimension calcPreferredSize() {
    return new Dimension(OfficeTheme.px(400), OfficeTheme.px(28));
  }

  public void paint(Graphics g) {
    super.paint(g);
    PageSpec p = session.activeSection().raw.page;
    float scale = editor.pixelsPerPoint();
    int x = getX() + editor.pageLeft(),
        y = getY() + OfficeTheme.px(5),
        h = OfficeTheme.px(17),
        w = Math.round(p.width * scale);
    g.setColor(0xd1d1d1);
    g.fillRect(x, y, w, h);
    g.setColor(0xffffff);
    g.fillRect(x + Math.round(p.left * scale), y, Math.round(p.contentWidth() * scale), h);
    g.setColor(0x646464);
    g.setFont(OfficeTheme.font(9, false));
    for (int eighth = 0; eighth * 9 < p.contentWidth(); eighth++) {
      int tx = x + Math.round((p.left + eighth * 9) * scale);
      if (eighth % 8 == 0) g.drawString(Integer.toString(eighth / 8), tx + 2, y);
      else g.drawLine(tx, y + h - OfficeTheme.px(eighth % 4 == 0 ? 7 : 4), tx, y + h);
    }
    int left = x + Math.round(p.left * scale), right = x + Math.round((p.width - p.right) * scale);
    g.setColor(OfficeTheme.BLUE);
    int d = OfficeTheme.px(4);
    g.fillTriangle(left - d, y + h, left + d, y + h, left, y + h + d);
    g.fillTriangle(right - d, y + h, right + d, y + h, right, y + h + d);
  }

  public void pointerPressed(int x, int y) {
    float value = (x - getAbsoluteX() - editor.pageLeft()) / editor.pixelsPerPoint();
    PageSpec p = session.activeSection().raw.page;
    handle = Math.abs(value - p.left) < 12 ? 1 : Math.abs(value - (p.width - p.right)) < 12 ? 2 : 0;
  }

  public void pointerReleased(int x, int y) {
    if (handle == 0) return;
    PageSpec p = session.activeSection().raw.page;
    float value = (x - getAbsoluteX() - editor.pageLeft()) / editor.pixelsPerPoint();
    float left = p.left, right = p.right;
    if (handle == 1) left = Math.max(12, Math.min(p.width - right - 72, value));
    else right = Math.max(12, Math.min(p.width - left - 72, p.width - value));
    session.section(
        session
            .activeSection()
            .raw
            .page(new PageSpec(p.width, p.height, p.top, right, p.bottom, left)));
    handle = 0;
    changed.run();
    editor.refresh();
    repaint();
  }
}
