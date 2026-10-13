package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import com.codename1.ui.editor.EditorHost;
import com.codename1.ui.geom.*;
import java.util.*;

/** Paginated CN1 canvas. Only intersecting pages are painted. */
public final class EditorSurface extends Container implements TextInputClient {
  public interface CellEditor {
    void edit(int block, int row, int column);
  }

  private final Session session;
  private final Commands commands;
  private final Runnable changed;
  private final CellEditor cellEditor;
  // RichTextArea exposes CN1's public EditorHost bridge; its own text view is not used.
  private final EditorHost host = new RichTextArea();
  private Object input;
  private final TextInputConfig config = new TextInputConfig();
  private final Map<String, Font> fonts = new HashMap<String, Font>();
  private final LayoutEngine engine;
  private LayoutEngine.Result layout;
  private Document laidOut;
  private float zoom = 1;
  private int composingStart = -1, composingEnd = -1;
  private int dragBlock = -1, dragAnchor;

  public EditorSurface(Session session, Commands commands, Runnable changed, CellEditor cells) {
    this.session = session;
    this.commands = commands;
    this.changed = changed;
    cellEditor = cells;
    setFocusable(true);
    setScrollableY(true);
    setUIID("DocumentCanvas");
    getAllStyles().setBgColor(0xe8edf2);
    getAllStyles().setBgTransparency(255);
    engine =
        new LayoutEngine(
            new LayoutEngine.Metrics() {
              public float width(String text, TextStyle style) {
                return font(style, 1).stringWidth(text) / 1.333f;
              }

              public float height(TextStyle style) {
                return font(style, 1).getHeight() / 1.333f;
              }
            });
  }

  private Font font(TextStyle s, float zoom) {
    String key = s.font + "|" + s.size + "|" + s.bold + "|" + s.italic + "|" + zoom;
    Font f = fonts.get(key);
    if (f == null) {
      int style = (s.bold ? Font.STYLE_BOLD : 0) | (s.italic ? Font.STYLE_ITALIC : 0);
      try {
        f =
            Font.createTrueTypeFont(s.bold ? "native:MainBold" : "native:MainRegular")
                .derive(Math.max(1, s.size * 1.333f * zoom), style);
      } catch (RuntimeException e) {
        f = Font.createSystemFont(Font.FACE_PROPORTIONAL, style, Font.SIZE_MEDIUM);
      }
      fonts.put(key, f);
    }
    return f;
  }

  public void setZoom(float value) {
    zoom = Math.max(.35f, Math.min(2.5f, value));
    revalidateView();
  }

  public float getZoom() {
    return zoom;
  }

  public void fitWidth() {
    setZoom((getWidth() - 48) / (session.document().page.width * 1.333f));
  }

  public LayoutEngine.Result layout() {
    if (laidOut != session.document()) {
      layout = engine.layout(session.document(), session.revision());
      laidOut = session.document();
    }
    return layout;
  }

  private float scale() {
    return 1.333f * zoom;
  }

  private int pageX() {
    return Math.max(24, (getWidth() - Math.round(session.document().page.width * scale())) / 2);
  }

  private int pageY(int p) {
    return 24 + Math.round(p * (session.document().page.height * scale() + 24));
  }

  @Override
  protected Dimension calcPreferredSize() {
    return new Dimension(900, 700);
  }

  @Override
  protected Dimension calcScrollSize() {
    return new Dimension(
        Math.max(getWidth(), Math.round(session.document().page.width * scale()) + 48),
        pageY(layout().pages.size()));
  }

  public void resetScroll() {
    setScrollY(0);
    refresh();
  }

  public void revealSelection() {
    refresh();
    ensureCaretVisible();
  }

  public void refresh() {
    revalidateView();
    if (input != null) host.updateTextInputState(input, getEditingState());
    repaint();
  }

  private void revalidateView() {
    setShouldCalcPreferredSize(true);
    if (getParent() != null) getParent().revalidate();
    repaint();
  }

  @Override
  public void paint(Graphics g) {
    super.paint(g);
    LayoutEngine.Result result = layout();
    float scale = scale();
    int x = getX() + pageX(),
        baseY = getY() - getScrollY(),
        pw = Math.round(session.document().page.width * scale),
        ph = Math.round(session.document().page.height * scale);
    Selection selection = session.selection();
    for (int p = 0; p < result.pages.size(); p++) {
      int y = baseY + pageY(p);
      if (y + ph < g.getClipY() || y > g.getClipY() + g.getClipHeight()) continue;
      g.setColor(0xd1d9e2);
      g.fillRect(x + 3, y + 4, pw, ph);
      g.setColor(0xffffff);
      g.fillRect(x, y, pw, ph);
      LayoutEngine.Page page = result.pages.get(p);
      g.setColor(0xc6d0db);
      for (LayoutEngine.Box box : page.boxes)
        g.drawRect(
            x + Math.round(box.x * scale),
            y + Math.round(box.y * scale),
            Math.round(box.width * scale),
            Math.round(box.height * scale));
      for (LayoutEngine.Line line : page.lines) {
        int lx = x + Math.round(line.x * scale), ly = y + Math.round(line.y * scale);
        if (line.row < 0
            && line.block >= selection.startBlock()
            && line.block <= selection.endBlock()
            && (line.block > selection.startBlock() || selection.start() < line.end)
            && (line.block < selection.endBlock() || selection.end() > line.start)) {
          int
              a =
                  line.block == selection.startBlock()
                      ? Math.max(selection.start(), line.start)
                      : line.start,
              b =
                  line.block == selection.endBlock()
                      ? Math.min(selection.end(), line.end)
                      : line.end;
          g.setColor(0xc5e9e6);
          g.fillRect(
              lx + Math.round(offsetX(line, a) * scale),
              ly,
              Math.max(1, Math.round((offsetX(line, b) - offsetX(line, a)) * scale)),
              Math.round(line.height * scale));
        }
        for (LayoutEngine.Span span : line.spans) {
          Font f = font(span.style, zoom);
          g.setFont(f);
          int sx = lx + Math.round(span.x * scale);
          if (span.style.background != 0xffffff) {
            g.setColor(span.style.background);
            g.fillRect(sx, ly, Math.round(span.width * scale), f.getHeight());
          }
          g.setColor(span.style.color);
          g.drawString(span.text, sx, ly);
          if (span.style.underline)
            g.drawLine(
                sx,
                ly + f.getHeight() - 1,
                sx + Math.round(span.width * scale),
                ly + f.getHeight() - 1);
          if (span.style.strike)
            g.drawLine(
                sx,
                ly + f.getHeight() / 2,
                sx + Math.round(span.width * scale),
                ly + f.getHeight() / 2);
        }
        if (line.row < 0
            && line.block == selection.block
            && selection.caret >= line.start
            && selection.caret <= line.end
            && selection.collapsed()) {
          g.setColor(0x087f80);
          int cx = lx + Math.round(offsetX(line, selection.caret) * scale);
          g.fillRect(cx, ly, 2, Math.round(line.height * scale));
        }
      }
      g.setFont(font(TextStyle.DEFAULT.with("size", "9"), zoom));
      g.setColor(0x7a8795);
      g.drawString(Integer.toString(p + 1), x + pw / 2, y + ph - Math.round(30 * scale));
    }
  }

  private float offsetX(LayoutEngine.Line line, int offset) {
    for (LayoutEngine.Span s : line.spans)
      if (offset <= s.end)
        return s.x
            + font(s.style, 1).stringWidth(s.text.substring(0, Math.max(0, offset - s.start)))
                / 1.333f;
    return line.width;
  }

  private LayoutEngine.Line lineAt(int absX, int absY) {
    float py = (absY - getAbsoluteY() + getScrollY()) / scale();
    int page =
        Math.max(
            0,
            Math.min(
                layout().pages.size() - 1,
                (int)
                    ((absY - getAbsoluteY() + getScrollY() - 24)
                        / (session.document().page.height * scale() + 24))));
    float y = (absY - getAbsoluteY() + getScrollY() - pageY(page)) / scale();
    float x = (absX - getAbsoluteX() - pageX()) / scale();
    LayoutEngine.Line best = null;
    float distance = Float.MAX_VALUE;
    for (LayoutEngine.Line l : layout().pages.get(page).lines) {
      float dy = y < l.y ? l.y - y : y > l.y + l.height ? y - l.y - l.height : 0;
      float dx = x < l.x ? l.x - x : x > l.x + l.width ? x - l.x - l.width : 0;
      float d = dy * 1000 + dx;
      if (d < distance) {
        distance = d;
        best = l;
      }
    }
    return best;
  }

  private int offsetAt(LayoutEngine.Line line, int absX) {
    float x = (absX - getAbsoluteX() - pageX()) / scale() - line.x;
    int best = line.start;
    float distance = Float.MAX_VALUE;
    Paragraph p = line.row < 0 ? session.document().paragraph(line.block) : null;
    if (p == null) return line.start;
    for (int i = line.start; i <= line.end; i++) {
      if (i > line.start && i < p.text.length() && Character.isLowSurrogate(p.text.charAt(i)))
        continue;
      float d = Math.abs(offsetX(line, i) - x);
      if (d < distance) {
        distance = d;
        best = i;
      }
    }
    return best;
  }

  @Override
  public void pointerPressed(int x, int y) {
    finishComposing();
    LayoutEngine.Line line = lineAt(x, y);
    if (line == null) return;
    if (line.row >= 0) {
      stopInput();
      cellEditor.edit(line.block, line.row, line.column);
      return;
    }
    int offset = offsetAt(line, x);
    session.select(line.block, offset, offset);
    dragBlock = line.block;
    dragAnchor = offset;
    requestFocus();
    startInput();
    refresh();
  }

  @Override
  public void pointerDragged(int x, int y) {
    LayoutEngine.Line line = lineAt(x, y);
    if (line != null && dragBlock >= 0 && line.row < 0) {
      session.selectRange(dragBlock, dragAnchor, line.block, offsetAt(line, x));
      refresh();
    } else super.pointerDragged(x, y);
  }

  @Override
  public void pointerReleased(int x, int y) {
    dragBlock = -1;
    super.pointerReleased(x, y);
  }

  public void startInput() {
    if (input == null && host.isTextInputSupported()) input = host.startTextInput(this, config);
    if (input == null) {
      Dialog.show(
          "Keyboard unavailable",
          "This port does not expose the low-level text input API required by the document canvas.",
          "OK",
          null);
    } else host.updateTextInputState(input, getEditingState());
  }

  public void stopInput() {
    if (input != null) {
      host.stopTextInput(input);
      input = null;
    }
  }

  @Override
  protected void deinitialize() {
    stopInput();
    super.deinitialize();
  }

  private void didEdit() {
    refresh();
    changed.run();
    ensureCaretVisible();
  }

  private void ensureCaretVisible() {
    int[] rect = getCaretRect();
    int top = rect[1] - getAbsoluteY() + getScrollY();
    int bottom = top + rect[3] + 12;
    if (top < getScrollY()) setScrollY(Math.max(0, top - 12));
    else if (bottom > getScrollY() + getHeight()) setScrollY(bottom - getHeight());
    repaint();
  }

  @Override
  public void commitText(String text) {
    if (session.isComposing()) {
      session.compose(text, text.length());
      session.finishComposition();
    } else session.replace(text);
    composingStart = composingEnd = -1;
    didEdit();
  }

  @Override
  public void setComposingText(String text, int relativeCaret) {
    if (!session.isComposing()) composingStart = session.selection().start();
    session.compose(text, relativeCaret);
    composingEnd = composingStart + text.length();
    refresh();
  }

  @Override
  public void finishComposing() {
    if (session.isComposing()) {
      session.finishComposition();
      composingStart = composingEnd = -1;
      didEdit();
    }
  }

  @Override
  public void deleteSurroundingText(int before, int after) {
    finishComposing();
    Selection x = session.selection();
    if (!x.collapsed()) {
      session.replace("");
      didEdit();
      return;
    }
    Paragraph p = session.document().paragraph(x.block);
    int a = Math.max(0, x.start() - Math.max(0, before)),
        b = Math.min(p.text.length(), x.end() + Math.max(0, after));
    if (a > 0 && a < p.text.length() && Character.isLowSurrogate(p.text.charAt(a))) a--;
    if (b < p.text.length() && b > 0 && Character.isLowSurrogate(p.text.charAt(b))) b++;
    session.select(x.block, a, b);
    session.replace("");
    didEdit();
  }

  @Override
  public void onKeyCommand(int command, int modifiers) {
    finishComposing();
    Selection x = session.selection();
    Paragraph p = session.document().paragraph(x.block);
    boolean extend = (modifiers & MOD_SHIFT) != 0;
    int caret = x.caret, block = x.block;
    switch (command) {
      case KEY_BACKSPACE:
        session.deleteBackward();
        didEdit();
        return;
      case KEY_DELETE:
        if (!x.collapsed()) {
          session.replace("");
        } else if (caret < p.text.length()) {
          int n = Character.isHighSurrogate(p.text.charAt(caret)) ? 2 : 1;
          session.select(block, caret, Math.min(p.text.length(), caret + n));
          session.replace("");
        } else if (block + 1 < session.document().blocks.size()
            && session.document().blocks.get(block + 1) instanceof Paragraph) {
          session.select(block + 1, 0, 0);
          session.deleteBackward();
        }
        didEdit();
        return;
      case KEY_LEFT:
        if (!extend && !x.collapsed()) {
          block = x.startBlock();
          caret = x.start();
        } else if (caret > 0) {
          caret--;
          if (caret > 0 && Character.isLowSurrogate(p.text.charAt(caret))) caret--;
        } else if (block > 0 && session.document().blocks.get(block - 1) instanceof Paragraph) {
          block--;
          caret = session.document().paragraph(block).text.length();
        }
        break;
      case KEY_RIGHT:
        if (!extend && !x.collapsed()) {
          block = x.endBlock();
          caret = x.end();
        } else if (caret < p.text.length())
          caret += Character.isHighSurrogate(p.text.charAt(caret)) ? 2 : 1;
        else if (block + 1 < session.document().blocks.size()
            && session.document().blocks.get(block + 1) instanceof Paragraph) {
          block++;
          caret = 0;
        }
        break;
      case KEY_HOME:
        caret = 0;
        break;
      case KEY_END:
        caret = p.text.length();
        break;
      case KEY_UP:
      case KEY_DOWN:
        {
          int[] rect = getCaretRect();
          LayoutEngine.Line line =
              lineAt(rect[0], rect[1] + (command == KEY_DOWN ? rect[3] + 2 : -2));
          if (line != null && line.row < 0) {
            block = line.block;
            caret = offsetAt(line, rect[0]);
          }
          break;
        }
      case KEY_SELECT_ALL:
        {
          int first = 0, last = session.document().blocks.size() - 1;
          while (!(session.document().blocks.get(first) instanceof Paragraph)) first++;
          while (!(session.document().blocks.get(last) instanceof Paragraph)) last--;
          session.selectRange(first, 0, last, session.document().paragraph(last).text.length());
          refresh();
          return;
        }
      case KEY_COPY:
      case KEY_CUT:
        Display.getInstance().copyToClipboard(session.selectedText());
        if (command == KEY_CUT) {
          session.replace("");
          didEdit();
        }
        return;
      case KEY_PASTE:
        Object clipboard = Display.getInstance().getPasteDataFromClipboard();
        if (clipboard instanceof String) commitText((String) clipboard);
        return;
      case KEY_UNDO:
        session.undo();
        didEdit();
        return;
      case KEY_REDO:
        session.redo();
        didEdit();
        return;
      case KEY_TAB:
        commitText("\t");
        return;
      case KEY_ESCAPE:
        stopInput();
        return;
      default:
        return;
    }
    session.selectRange(extend ? x.anchorBlock : block, extend ? x.anchor : caret, block, caret);
    refresh();
    ensureCaretVisible();
  }

  @Override
  public void onEditorAction(int action) {
    commitText("\n");
  }

  @Override
  public TextInputState getEditingState() {
    Selection s = session.selection();
    return new TextInputState(
        session.document().paragraph(s.block).text.toString(),
        s.anchorBlock == s.block ? s.anchor : s.caret,
        s.caret,
        composingStart,
        composingEnd);
  }

  @Override
  public int[] getCaretRect() {
    return rectForOffset(session.selection().caret);
  }

  @Override
  public int[] rectForOffset(int offset) {
    int block = session.selection().block;
    for (LayoutEngine.Page page : layout().pages)
      for (LayoutEngine.Line line : page.lines)
        if (line.block == block && line.row < 0 && offset >= line.start && offset <= line.end)
          return new int[] {
            getAbsoluteX() + pageX() + Math.round((line.x + offsetX(line, offset)) * scale()),
            getAbsoluteY() - getScrollY() + pageY(line.page) + Math.round(line.y * scale()),
            2,
            Math.round(line.height * scale())
          };
    return new int[] {getAbsoluteX(), getAbsoluteY(), 2, 20};
  }

  @Override
  public int offsetAtPoint(int x, int y) {
    LayoutEngine.Line line = lineAt(x, y);
    return line != null && line.block == session.selection().block
        ? offsetAt(line, x)
        : session.selection().caret;
  }

  @Override
  public int[] selectionRects(int a, int b) {
    ArrayList<Integer> values = new ArrayList<Integer>();
    for (LayoutEngine.Page page : layout().pages)
      for (LayoutEngine.Line l : page.lines)
        if (l.row < 0 && l.block == session.selection().block && a < l.end && b > l.start) {
          int start = Math.max(a, l.start), end = Math.min(b, l.end);
          values.add(getAbsoluteX() + pageX() + Math.round((l.x + offsetX(l, start)) * scale()));
          values.add(getAbsoluteY() - getScrollY() + pageY(l.page) + Math.round(l.y * scale()));
          values.add(Math.round((offsetX(l, end) - offsetX(l, start)) * scale()));
          values.add(Math.round(l.height * scale()));
        }
    int[] out = new int[values.size()];
    for (int i = 0; i < out.length; i++) out[i] = values.get(i);
    return out;
  }

  @Override
  public TextInputConfig getConfig() {
    return config;
  }

  @Override
  public void inputFocusGained() {}

  @Override
  public void inputFocusLost() {
    input = null;
    finishComposing();
  }

  @Override
  public int getTextLength() {
    return session.document().paragraph(session.selection().block).text.length();
  }

  @Override
  public String getTextRange(int a, int b) {
    return session.document().paragraph(session.selection().block).text.slice(a, b).toString();
  }

  @Override
  public void replaceRange(int a, int b, String text) {
    session.select(session.selection().block, a, b);
    commitText(text);
  }

  @Override
  public void setSelectionRange(int a, int b) {
    session.select(session.selection().block, a, b);
    refresh();
  }
}
