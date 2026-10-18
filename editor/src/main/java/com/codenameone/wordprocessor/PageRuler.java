package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import com.codename1.ui.geom.Dimension;

/**
 * Word's horizontal inch ruler: white text area between gray margins, numbered outward from the
 * left margin, with the hourglass indent markers of the caret paragraph. Drag the gray/white
 * boundary (middle of the band) to change a page margin; drag the top triangle for the first
 * line, the bottom triangle for the hanging indent, the box under it to move both, and the
 * right triangle for the right indent.
 */
final class PageRuler extends Component {
  private static final int MARGIN = 1, RIGHT_MARGIN = 2, FIRST = 3, HANGING = 4, LEFT = 5, RIGHT = 6;
  private final EditorSurface editor;
  private final Session session;
  private final Runnable changed;
  private int handle;

  PageRuler(EditorSurface e, Session s, Runnable c) {
    editor = e;
    session = s;
    changed = c;
    OfficeTheme.surface(this, OfficeTheme.CANVAS);
  }

  protected Dimension calcPreferredSize() {
    return new Dimension(OfficeTheme.px(400), OfficeTheme.px(26));
  }

  private Paragraph paragraph() {
    int block = session.selection().block;
    Sequence<Block> blocks = session.editingDocument().blocks;
    Block b = block >= 0 && block < blocks.size() ? blocks.get(block) : null;
    return b instanceof Paragraph ? (Paragraph) b : null;
  }

  private int top() {
    return getY() + OfficeTheme.px(4);
  }

  private int band() {
    return OfficeTheme.px(16);
  }

  private Object[] shown;

  /** Repaints only when the margins, the caret paragraph's indents or the page moved. */
  void sync() {
    PageSpec p = session.activeSection().raw.page;
    Paragraph para = paragraph();
    Object[] now = {
      p.width, p.left, p.right, editor.pixelsPerPoint(),
      editor.getAbsoluteX() - getAbsoluteX() + editor.pageLeft(),
      para == null ? null : para.indent, para == null ? null : para.format.firstIndent,
      para == null ? null : para.format.rightIndent
    };
    if (java.util.Arrays.equals(now, shown)) return;
    shown = now;
    repaint();
  }

  public void paint(Graphics g) {
    super.paint(g);
    PageSpec p = session.activeSection().raw.page;
    float scale = editor.pixelsPerPoint();
    // The vertical ruler may sit left of the editor, so measure from the editor itself.
    int x = getX() + editor.getAbsoluteX() - getAbsoluteX() + editor.pageLeft(), y = top(), h = band(), w = Math.round(p.width * scale);
    int left = x + Math.round(p.left * scale), right = x + Math.round((p.width - p.right) * scale);
    boolean aa = g.isAntiAliased();
    g.setAntiAliased(true);
    g.setColor(OfficeTheme.chrome(0xd4d4d4));
    g.fillRoundRect(x, y, w, h, OfficeTheme.px(2), OfficeTheme.px(2));
    g.setColor(OfficeTheme.chrome(0xffffff));
    g.fillRect(left, y, right - left, h);
    g.setColor(OfficeTheme.chrome(0x707070));
    Font font = OfficeTheme.font(9, false);
    g.setFont(font);
    int mid = y + h / 2;
    // Eighths of an inch, counted outward from the left margin in both directions.
    for (int dir = -1; dir <= 1; dir += 2) {
      for (int eighth = dir > 0 ? 0 : 1; ; eighth++) {
        float at = p.left + dir * eighth * 9f;
        if (at < 0 || at > p.width) break;
        int tx = x + Math.round(at * scale);
        if (eighth % 8 == 0) {
          if (eighth == 0) continue;
          String n = Integer.toString(eighth / 8);
          g.drawString(n, tx - font.stringWidth(n) / 2, mid - font.getHeight() / 2);
        } else if (eighth % 4 == 0) {
          g.drawLine(tx, mid - OfficeTheme.px(3), tx, mid + OfficeTheme.px(3));
        } else if (eighth % 2 == 0) {
          g.drawLine(tx, mid - OfficeTheme.px(1), tx, mid + OfficeTheme.px(1));
        } else {
          g.fillRect(tx, mid, Math.max(1, OfficeTheme.px(1) / 2), Math.max(1, OfficeTheme.px(1) / 2));
        }
      }
    }
    Paragraph para = paragraph();
    float indent = para == null ? 0 : para.indent;
    float first = para == null ? 0 : para.format.firstIndent;
    float rightIndent = para == null ? 0 : para.format.rightIndent;
    int d = OfficeTheme.px(4);
    int hang = left + Math.round(indent * scale), firstX = left + Math.round((indent + first) * scale);
    int rightX = right - Math.round(rightIndent * scale);
    // Hourglass: first-line triangle hangs from the top edge, hanging triangle sits on the
    // bottom edge with the left-indent box beneath it; the right indent is a lone triangle.
    marker(g, firstX, y - 1, d, true);
    marker(g, hang, y + h - d, d, false);
    int box = OfficeTheme.px(3);
    g.setColor(OfficeTheme.chrome(0xffffff));
    g.fillRect(hang - d, y + h, 2 * d, box);
    g.setColor(OfficeTheme.chrome(0x606060));
    g.drawRect(hang - d, y + h, 2 * d, box);
    marker(g, rightX, y + h - d, d, false);
    g.setAntiAliased(aa);
  }

  private static void marker(Graphics g, int cx, int y, int d, boolean down) {
    int tip = down ? y + d : y, base = down ? y : y + d;
    g.setColor(OfficeTheme.chrome(0xffffff));
    g.fillTriangle(cx - d, base, cx + d, base, cx, tip);
    g.setColor(OfficeTheme.chrome(0x606060));
    g.drawLine(cx - d, base, cx + d, base);
    g.drawLine(cx - d, base, cx, tip);
    g.drawLine(cx + d, base, cx, tip);
  }

  public void pointerPressed(int x, int y) {
    PageSpec p = session.activeSection().raw.page;
    float s = editor.pixelsPerPoint();
    float value = (x - getAbsoluteX() - (editor.getAbsoluteX() - getAbsoluteX()) - editor.pageLeft()) / s;
    int ry = y - getAbsoluteY() - (top() - getY());
    Paragraph para = paragraph();
    float indent = para == null ? 0 : para.indent, first = para == null ? 0 : para.format.firstIndent;
    float rightIndent = para == null ? 0 : para.format.rightIndent;
    float near = OfficeTheme.px(6) / s;
    float leftEdge = p.left, rightEdge = p.width - p.right;
    handle = 0;
    if (ry < band() / 3) {
      if (Math.abs(value - (leftEdge + indent + first)) < near) handle = FIRST;
    } else if (ry > band() * 2 / 3) {
      if (Math.abs(value - (leftEdge + indent)) < near) handle = ry > band() ? LEFT : HANGING;
      else if (Math.abs(value - (rightEdge - rightIndent)) < near) handle = RIGHT;
    }
    if (handle == 0) {
      if (Math.abs(value - leftEdge) < near * 2) handle = MARGIN;
      else if (Math.abs(value - rightEdge) < near * 2) handle = RIGHT_MARGIN;
    }
    if (handle >= FIRST && para == null) handle = 0;
  }

  public void pointerReleased(int x, int y) {
    if (handle == 0) return;
    PageSpec p = session.activeSection().raw.page;
    float value = (x - getAbsoluteX() - (editor.getAbsoluteX() - getAbsoluteX()) - editor.pageLeft()) / editor.pixelsPerPoint();
    // Indents snap to sixteenths of an inch, as Word's ruler does without Alt.
    float snap = 4.5f;
    Paragraph para = paragraph();
    if (handle == MARGIN || handle == RIGHT_MARGIN) {
      float left = p.left, right = p.right;
      if (handle == MARGIN) left = Math.max(12, Math.min(p.width - right - 72, value));
      else right = Math.max(12, Math.min(p.width - left - 72, p.width - value));
      session.section(
          session.activeSection().raw.page(new PageSpec(p.width, p.height, p.top, right, p.bottom, left)));
    } else if (para != null) {
      float rel = Math.round((value - p.left) / snap) * snap;
      float max = p.contentWidth() - 36;
      if (handle == FIRST) {
        session.paragraph("firstIndent", String.valueOf(Math.max(-para.indent, Math.min(max, rel)) - para.indent));
      } else if (handle == HANGING) {
        // The hanging marker moves the body; the first line stays where it is.
        float firstAt = para.indent + para.format.firstIndent;
        float body = Math.max(0, Math.min(max, rel));
        session.transact(
            t -> {
              t.paragraph("indent", String.valueOf(body));
              t.paragraph("firstIndent", String.valueOf(firstAt - body));
            });
      } else if (handle == LEFT) {
        session.paragraph("indent", String.valueOf(Math.max(0, Math.min(max, rel))));
      } else if (handle == RIGHT) {
        float r = Math.round((p.width - p.right - value) / snap) * snap;
        session.paragraph("rightIndent", String.valueOf(Math.max(0, Math.min(max, r))));
      }
    }
    handle = 0;
    changed.run();
    editor.refresh();
    repaint();
  }
}
