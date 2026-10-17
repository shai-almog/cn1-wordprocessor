package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import com.codename1.ui.accessibility.*;
import com.codename1.ui.editor.EditorHost;
import com.codename1.ui.geom.*;
import java.util.*;

/** Paginated CN1 canvas. Only intersecting pages are painted. */
public final class EditorSurface extends Container
    implements TextInputClient, TextInputGeometry.Provider {
  public interface CellEditor {
    void edit(int block, int row, int column);
  }

  public interface PictureEditor {
    void edit(int block);
  }

  private PictureEditor pictureEditor;
  private final TextEffects textEffects = new TextEffects();

  public void setPictureEditor(PictureEditor value) {
    pictureEditor = value;
  }

  private final Map<Picture, Image> pictures =
      new LinkedHashMap<Picture, Image>(4, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Picture, Image> entry) {
          int pixels = 0;
          for (Image image : values())
            if (image != null) pixels += image.getWidth() * image.getHeight();
          return size() > 1 && (size() > 3 || pixels > 8000000);
        }
      };

  private Image picture(Picture p) {
    Image image = pictures.get(p);
    if (!pictures.containsKey(p)) {
      try {
        byte[] bytes = p.bytes();
        image = Image.createImage(bytes, 0, bytes.length);
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest > 2048)
          image =
              image.scaled(
                  Math.max(1, image.getWidth() * 2048 / longest),
                  Math.max(1, image.getHeight() * 2048 / longest));
      } catch (RuntimeException invalidImage) {
        image = null;
      }
      pictures.put(p, image);
    }
    return image;
  }

  private final Map<LayoutEngine.Span, String> tabLeaderPaint =
      new LinkedHashMap<LayoutEngine.Span, String>(32, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<LayoutEngine.Span, String> entry) {
          return size() > 128;
        }
      };

  private String paintText(LayoutEngine.Span span, Font font, float scale) {
    if (!"\t".equals(span.text) || span.displayText.isEmpty()) return span.displayText;
    String cached = tabLeaderPaint.get(span);
    if (cached != null) return cached;
    // Native font rounding changes at each zoom. Fit the entire leader at the actual paint size.
    int width = Math.max(0, (int) (span.width * scale) - 1);
    char[] chars = new char[4096];
    java.util.Arrays.fill(chars, span.displayText.charAt(0));
    int low = 0, high = chars.length;
    while (low < high) {
      int mid = (low + high + 1) / 2;
      if (font.stringWidth(new String(chars, 0, mid)) * span.style.characters.scale / 100
              + mid * span.style.characters.spacing * scale
          <= width) low = mid;
      else high = mid - 1;
    }
    String text = new String(chars, 0, low);
    tabLeaderPaint.put(span, text);
    return text;
  }

  private final LayoutEngine.Metrics fontMetrics =
      new LayoutEngine.Metrics() {
        public float width(String text, TextStyle style) {
          return font(style, 1).stringWidth(text) / pointsToPixels();
        }

        public float ascent(TextStyle style) {
          return font(style, 1).getAscent() / pointsToPixels();
        }

        public float height(TextStyle style) {
          return font(style, 1).getHeight() / pointsToPixels();
        }
      };
  private final Session session;
  private final Commands commands;
  private final Runnable changed;
  private final CellEditor cellEditor;
  // RichTextArea exposes CN1's public EditorHost bridge; its own text view is not used.
  private final EditorHost host = new RichTextArea();
  private Object input;
  private InputBinding inputBinding;
  private final TextInputConfig config = new TextInputConfig();
  private final Map<TextStyle, Font> layoutFonts = fontCache(), paintFonts = fontCache();
  private final float pointScale = 1.333333f * OfficeTheme.ratio();
  private int scrollExtent = -1;

  private static Map<TextStyle, Font> fontCache() {
    return new LinkedHashMap<TextStyle, Font>(64, .75f, true) {
      protected boolean removeEldestEntry(Map.Entry<TextStyle, Font> e) {
        return size() > 64;
      }
    };
  }

  private final LayoutEngine engine;
  private LayoutEngine.Result layout;
  private Document laidOut, indexed, commentSource;
  private java.util.Map<String, ReviewComment.Anchor> commentAnchors;
  private DocumentText textIndex;
  // A soft wrap shares one offset between two lines. Retain the chosen visual side.
  private LayoutEngine.Line navigationLine;
  private LayoutEngine.Result navigationLayout;
  private Selection navigationSelection, verticalSelection;
  private float preferredX;

  private void rememberLine(LayoutEngine.Line line) {
    navigationLine = line;
    navigationLayout = layout();
    navigationSelection = session.selection();
  }

  private LayoutEngine.Line caretLine() {
    LayoutEngine.Result result = layout();
    Selection selection = session.selection();
    if (navigationLine != null && navigationLayout == result && navigationSelection == selection)
      return navigationLine;
    LayoutEngine.Line found = null;
    for (LayoutEngine.Page page : result.pages)
      for (LayoutEngine.Line line : page.lines)
        if (editingBlock(line) == selection.block
            && selection.caret >= line.start
            && selection.caret <= line.end) found = line;
    return found;
  }

  private LayoutEngine.Line verticalLine(LayoutEngine.Line current, boolean down, boolean page) {
    ArrayList<LayoutEngine.Line> lines = new ArrayList<LayoutEngine.Line>();
    for (LayoutEngine.Page p : layout().pages)
      for (LayoutEngine.Line line : p.lines) if (editingBlock(line) >= 0) lines.add(line);
    int index = lines.indexOf(current);
    if (index < 0) return current;
    if (!page) return lines.get(Math.max(0, Math.min(lines.size() - 1, index + (down ? 1 : -1))));
    float target =
        pageY(current.page)
            + current.y * scale()
            + (down ? 1 : -1)
                * Math.max(current.height * scale(), getHeight() - current.height * scale());
    LayoutEngine.Line best = current;
    float distance = Float.MAX_VALUE;
    for (int i = index; i >= 0 && i < lines.size(); i += down ? 1 : -1) {
      LayoutEngine.Line line = lines.get(i);
      float delta = Math.abs(pageY(line.page) + line.y * scale() - target);
      if (delta < distance) {
        best = line;
        distance = delta;
      }
    }
    return best;
  }

  private DocumentText textIndex() {
    if (indexed != session.editingDocument()) {
      indexed = session.editingDocument();
      textIndex = new DocumentText(indexed);
    }
    return textIndex;
  }

  private int viewportX() {
    return getAbsoluteX() + getScrollX();
  }

  private int viewportY() {
    return getAbsoluteY() + getScrollY();
  }

  private boolean selectParagraph(String id) {
    Document d = session.document();
    for (int i = 0; i < d.blocks.size(); i++) {
      Block block = d.blocks.get(i);
      if (block instanceof Paragraph && block.id().equals(id)) {
        session.leaveCell();
        session.select(i, 0, 0);
        return true;
      }
      if (block instanceof Table) {
        Table table = (Table) block;
        for (int row = 0; row < table.rows(); row++)
          for (int col = 0; col < table.columns(); col++) {
            if (table.covered(row, col)) continue;
            int index = 0;
            for (Paragraph p : table.cell(row, col)) {
              if (p.id.equals(id)) {
                session.enterCell(i, row, col);
                session.select(index, 0, 0);
                return true;
              }
              index++;
            }
          }
      }
    }
    return false;
  }

  private java.util.List<AccessibilityNode> accessibleParagraphs() {
    java.util.List<AccessibilityNode> nodes = new ArrayList<AccessibilityNode>();
    Set<String> seen = new HashSet<String>();
    for (LayoutEngine.Page page : layout().pages)
      for (LayoutEngine.Line line : page.lines) {
        if (line.block < 0) continue;
        int top = pageY(line.page) + Math.round(line.y * scale()) - getScrollY(),
            height = Math.round(line.height * scale());
        if (top + height < 0 || top > getHeight() || !seen.add(line.paragraphId)) continue;
        Paragraph p = lineParagraph(line);
        final String id = p.id;
        AccessibilityNode node =
            new AccessibilityNode(id)
                .setRole(AccessibilityRole.TEXT_FIELD)
                .setLabel(
                    line.row < 0
                        ? "Paragraph " + (line.block + 1)
                        : "Table row " + (line.row + 1) + ", column " + (line.column + 1))
                .setValue(p.text.plainText())
                .setMultiline(true)
                .setReadOnly(false)
                .setBounds(
                    new Rectangle(
                        pageX(line.page) + Math.round(line.x * scale()),
                        top,
                        Math.max(OfficeTheme.px(60), Math.round(line.width * scale())),
                        height));
        if (line.row >= 0) {
          TableFormat format = ((Table) session.document().blocks.get(line.block)).format;
          if (!format.title.isEmpty())
            node.setLabel(
                format.title + ", row " + (line.row + 1) + ", column " + (line.column + 1));
          if (!format.description.isEmpty()) node.setDescription(format.description);
        }
        node.addAction(
            new AccessibilityAction(
                AccessibilityAction.ACTIVATE,
                "Edit paragraph",
                (component, argument) -> {
                  finishComposing();
                  if (!selectParagraph(id)) return false;
                  startInput();
                  refresh();
                  changed.run();
                  return true;
                }));
        node.addAction(
            new AccessibilityAction(
                AccessibilityAction.SET_TEXT,
                "Set paragraph text",
                (component, argument) -> {
                  if (!(argument instanceof String)) return false;
                  finishComposing();
                  if (!selectParagraph(id)) return false;
                  int block = session.selection().block;
                  session.select(
                      block, 0, session.editingDocument().paragraph(block).text.length());
                  session.replace((String) argument);
                  didEdit();
                  return true;
                }));
        nodes.add(node);
      }
    for (LayoutEngine.Page page : layout().pages)
      for (LayoutEngine.Line line : page.allLines()) {
        if (line.block >= 0) continue;
        int top = pageY(line.page) + Math.round(line.y * scale()) - getScrollY();
        if (top + Math.round(line.height * scale()) < 0 || top > getHeight()) continue;
        StringBuilder value = new StringBuilder();
        for (LayoutEngine.Span span : line.spans) value.append(span.displayText);
        nodes.add(
            new AccessibilityNode("margin-" + line.page + "-" + line.paragraphId + "-" + line.start)
                .setRole(AccessibilityRole.STATIC_TEXT)
                .setLabel(
                    (line.block == -2 ? "Note" : page.header.contains(line) ? "Header" : "Footer")
                        + ", page "
                        + (line.page + 1))
                .setValue(value.toString())
                .setReadOnly(true)
                .setBounds(
                    new Rectangle(
                        pageX(line.page) + Math.round(line.x * scale()),
                        top,
                        Math.max(1, Math.round(line.width * scale())),
                        Math.round(line.height * scale()))));
      }
    for (LayoutEngine.Page page : layout().pages)
      for (LayoutEngine.PlacedPicture pic : page.pictures) {
        int top = pageY(pic.page) + Math.round(pic.y * scale()) - getScrollY(),
            height = Math.round(pic.height * scale());
        if (top + height < 0 || top > getHeight()) continue;
        AccessibilityNode node =
            new AccessibilityNode(pic.picture.id())
                .setRole(AccessibilityRole.IMAGE)
                .setLabel(pic.picture.alt.isEmpty() ? "Picture" : pic.picture.alt)
                .setBounds(
                    new Rectangle(
                        pageX(pic.page) + Math.round(pic.x * scale()),
                        top,
                        Math.round(pic.width * scale()),
                        height));
        node.addAction(
            new AccessibilityAction(
                AccessibilityAction.ACTIVATE,
                "Edit picture",
                (component, argument) -> {
                  stopInput();
                  if (pictureEditor != null) pictureEditor.edit(pic.block);
                  return true;
                }));
        nodes.add(node);
      }
    return nodes;
  }

  private float zoom = 1;
  private int composingStart = -1, composingEnd = -1;
  private int dragBlock = -1, dragAnchor;
  private int dragTable = -1, dragRow, dragColumn;
  private boolean reflow = OfficeTheme.mobile(), touchMoved, touchSelecting;
  private int touchX, touchY, dragHandle;
  private float pinchStart;
  private int layoutViewport = -1;

  public boolean isReflow() {
    return reflow;
  }

  public void setReflow(boolean value) {
    reflow = value;
    laidOut = null;
    scrollExtent = -1;
    if (value) setZoom(1);
    else fitWidth();
    revalidateView();
  }

  public EditorSurface(Session session, Commands commands, Runnable changed, CellEditor cells) {
    this.session = session;
    this.commands = commands;
    this.changed = changed;
    cellEditor = cells;
    setFocusable(true);
    setNativeDropTarget(true);
    setAcceptedDropMimeTypes(ACCEPTED_MIME_TYPES);
    setAcceptedDropActions(NativeDragOperation.ACTION_COPY | NativeDragOperation.ACTION_MOVE);
    setNativeDragSource(true);
    getSemantics()
        .setRole(AccessibilityRole.GENERIC)
        .setLabel("Document")
        .setGrouping(AccessibilityGrouping.GROUP)
        .setChildProvider(component -> accessibleParagraphs());
    for (final int direction : new int[] {-1, 1})
      getSemantics()
          .addAction(
              new AccessibilityAction(
                  direction < 0
                      ? AccessibilityAction.SCROLL_BACKWARD
                      : AccessibilityAction.SCROLL_FORWARD,
                  direction < 0 ? "Previous document page" : "Next document page",
                  (component, argument) -> {
                    setScrollY(
                        Math.max(
                            0,
                            Math.min(
                                getScrollDimension().getHeight() - getHeight(),
                                getScrollY() + direction * getHeight() * 4 / 5)));
                    refresh();
                    return true;
                  }));
    setScrollableY(true);
    setScrollableX(true);
    addScrollListener(
        (x, y, oldX, oldY) -> {
          if (input != null) host.updateTextInputState(input, getEditingState());
        });
    setUIID("DocumentCanvas");
    getAllStyles().setBgColor(0xe7e7e7);
    getAllStyles().setBgTransparency(255);
    engine = new LayoutEngine(fontMetrics);
    commands.setLayoutEngine(engine);
  }

  private Font font(TextStyle s, float zoom) {
    Map<TextStyle, Font> fonts = zoom == 1 ? layoutFonts : paintFonts;
    Font f = fonts.get(s);
    if (f == null) {
      int style = (s.bold ? Font.STYLE_BOLD : 0) | (s.italic ? Font.STYLE_ITALIC : 0);
      try {
        f = DocumentFonts.font(s, Math.max(1, s.renderedSize() * pointsToPixels() * zoom));
      } catch (RuntimeException e) {
        f = Font.createSystemFont(Font.FACE_PROPORTIONAL, style, Font.SIZE_MEDIUM);
      }
      fonts.put(s, f);
    }
    return f;
  }

  public void setZoom(float value) {
    if (reflow) laidOut = null;
    zoom = Math.max(.35f, Math.min(2.5f, value));
    paintFonts.clear();
    tabLeaderPaint.clear();
    scrollExtent = -1;
    revalidateView();
  }

  public float getZoom() {
    return zoom;
  }

  public void fitWidth() {
    if (reflow) {
      laidOut = null;
      revalidateView();
    } else setZoom((getWidth() - 48) / (layout().maxWidth * pointsToPixels()));
  }

  public LayoutEngine.Result layout() {
    if (laidOut != session.document() || reflow && layoutViewport != getWidth()) {
      layout =
          reflow
              ? engine.reflow(
                  session.document(),
                  session.revision(),
                  Math.max(120, (getWidth() - OfficeTheme.px(16)) / scale()))
              : engine.layout(session.document(), session.revision());
      layoutViewport = getWidth();
      laidOut = session.document();
    }
    return layout;
  }

  /** Print/export always uses document paper geometry, independent of phone reflow and zoom. */
  public LayoutEngine.Result printLayout() {
    return reflow ? engine.layout(session.document(), session.revision()) : layout();
  }

  /** Paginate generated reference content with the same fonts as this editor. */
  public LayoutEngine.Result printLayout(Document document) {
    return engine.layout(document, session.revision());
  }

  private float pointsToPixels() {
    return pointScale;
  }

  public float pixelsPerPoint() {
    return scale();
  }

  public int pageLeft() {
    return pageX();
  }

  public int currentPage() {
    LayoutEngine.Line line = caretLine();
    return line == null ? pageAt(getScrollY()) + 1 : line.page + 1;
  }

  private float scale() {
    return pointsToPixels() * zoom;
  }

  private int pageX() {
    return pageX(currentPage() - 1);
  }

  private int pageX(int page) {
    return Math.max(
            reflow ? OfficeTheme.px(8) : 24,
            (getWidth() - Math.round(layout().pages.get(page).spec.width * scale())) / 2)
        - getScrollX();
  }

  private int pageY(int page) {
    return 24 + Math.round(layout().heightBefore(page) * scale()) + page * 24;
  }

  private int pageAt(int y) {
    int lo = 0, hi = layout().pages.size() - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) / 2;
      if (pageY(mid) <= y) lo = mid;
      else hi = mid - 1;
    }
    return lo;
  }

  @Override
  protected Dimension calcPreferredSize() {
    return new Dimension(900, 700);
  }

  @Override
  protected Dimension calcScrollSize() {
    return new Dimension(
        Math.max(
            getWidth(),
            Math.round(layout().maxWidth * scale()) + (reflow ? OfficeTheme.px(16) : 48)),
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
    accessibilityChanged();
    if (input != null) host.updateTextInputState(input, getEditingState());
    repaint();
  }

  private void revalidateView() {
    int extent = pageY(layout().pages.size());
    if (extent != scrollExtent) {
      scrollExtent = extent;
      setShouldCalcPreferredSize(true);
      revalidate();
    }
    repaint();
  }

  /** Read-only geometry for the opt-in built-in MCP diagnostics. */
  public Map<String, Object> inspectLayout() {
    ArrayList<Object> cells = new ArrayList<Object>();
    CellSelection selection = session.cellSelection();
    for (int page = 0; page < layout().pages.size(); page++)
      for (LayoutEngine.Box box : layout().pages.get(page).boxes) {
        int x = viewportX() + pageX(page) + Math.round(box.x * scale());
        int y = viewportY() + pageY(page) - getScrollY() + Math.round(box.y * scale());
        int width = Math.round(box.width * scale()), height = Math.round(box.height * scale());
        if (y + height < viewportY()
            || y > viewportY() + getHeight()
            || x + width < viewportX()
            || x > viewportX() + getWidth()) continue;
        if (cells.size() == 1000) break;
        cells.add(
            Json.object(
                "block",
                box.block,
                "row",
                box.row,
                "column",
                box.column,
                "page",
                page,
                "bounds",
                Arrays.asList(x, y, width, height),
                "selected",
                selection != null && selection.contains(box.block, box.row, box.column)));
      }
    ArrayList<Object> lines = new ArrayList<Object>();
    for (int page = 0; page < layout().pages.size() && lines.size() < 1000; page++)
      for (LayoutEngine.Line line : layout().pages.get(page).allLines()) {
        int x = viewportX() + pageX(page) + Math.round(line.x * scale());
        int y = viewportY() + pageY(page) - getScrollY() + Math.round(line.y * scale());
        int height = Math.round(line.height * scale());
        if (y + height < viewportY() || y > viewportY() + getHeight()) continue;
        if (lines.size() == 1000) break;
        lines.add(
            Json.object(
                "block",
                line.block,
                "row",
                line.row,
                "column",
                line.column,
                "paragraphId",
                line.paragraphId,
                "page",
                page,
                "start",
                line.start,
                "end",
                line.end,
                "bounds",
                Arrays.asList(x, y, Math.round(line.width * scale()), height),
                "heightPoints",
                line.height,
                "clipped",
                line.clipped,
                "clipHeightPoints",
                line.clipped ? line.clipHeight : null));
      }
    return Json.object(
        "displayWidth",
        Display.getInstance().getDisplayWidth(),
        "displayHeight",
        Display.getInstance().getDisplayHeight(),
        "cells",
        cells,
        "lines",
        lines,
        "viewport",
        Arrays.asList(viewportX(), viewportY(), getWidth(), getHeight()));
  }

  @Override
  public void paint(Graphics g) {
    // Component.internalPaintImpl already translates by the scroll offsets.
    // Paint document coordinates here; hit testing/native geometry use screen coordinates.
    super.paint(g);
    LayoutEngine.Result result = layout();
    Document root = session.document();
    if (commentSource != root) {
      commentSource = root;
      commentAnchors =
          root.comments.isEmpty()
              ? java.util.Collections.<String, ReviewComment.Anchor>emptyMap()
              : ReviewComment.index(root);
    }
    float scale = scale();
    int baseY = getY();
    Selection selection = session.selection();
    LayoutEngine.Line caretLine = caretLine();
    for (int p = 0; p < result.pages.size(); p++) {
      LayoutEngine.Page page = result.pages.get(p);
      int x = getX() + pageX(p) + getScrollX(),
          y = baseY + pageY(p),
          pw = Math.round(page.spec.width * scale),
          ph = Math.round(page.spec.height * scale);
      if (y + ph < g.getClipY() || y > g.getClipY() + g.getClipHeight()) continue;
      g.setColor(0xd1d9e2);
      g.fillRect(x + 3, y + 4, pw, ph);
      g.setColor(0xffffff);
      g.fillRect(x, y, pw, ph);
      for (LayoutEngine.PlacedPicture pic : page.pictures) {
        Image image = picture(pic.picture);
        if (image == null) {
          g.setColor(0x9c2525);
          g.setFont(font(TextStyle.DEFAULT, zoom));
          g.drawRect(
              x + Math.round(pic.x * scale),
              y + Math.round(pic.y * scale),
              Math.round(pic.width * scale),
              Math.round(pic.height * scale));
          g.drawString(
              "Picture unavailable",
              x + Math.round(pic.x * scale) + 4,
              y + Math.round(pic.y * scale) + 4);
          continue;
        }
        g.drawImage(
            image,
            x + Math.round(pic.x * scale),
            y + Math.round(pic.y * scale),
            Math.round(pic.width * scale),
            Math.round(pic.height * scale));
      }
      for (LayoutEngine.Box box : page.boxes) {
        int bx = x + Math.round(box.x * scale),
            by = y + Math.round(box.y * scale),
            bw = Math.round(box.width * scale),
            bh = Math.round(box.height * scale);
        CellSelection selectedCells = session.cellSelection();
        g.setColor(
            selectedCells != null && selectedCells.contains(box.block, box.row, box.column)
                ? 0xc5e9e6
                : box.format.background);
        g.fillRect(bx, by, bw, bh);
        if (box.format.borderWidth > 0) {
          g.setColor(box.format.borderColor);
          int stroke = Math.max(1, Math.round(box.format.borderWidth * scale));
          g.fillRect(bx, by, bw, stroke);
          g.fillRect(bx, by + bh - stroke, bw, stroke);
          g.fillRect(bx, by, stroke, bh);
          g.fillRect(bx + bw - stroke, by, stroke, bh);
        }
      }
      for (LayoutEngine.Line area : page.noteAreas()) {
        g.setColor(0x242424);
        int nx = x + Math.round(page.spec.left * scale), ny = y + Math.round((area.y - 6) * scale);
        g.drawLine(
            nx, ny, nx + Math.round(Math.min(144, page.spec.contentWidth() / 3) * scale), ny);
      }
      for (LayoutEngine.Line line : page.allLines()) {
        int clipX = g.getClipX(),
            clipY = g.getClipY(),
            clipW = g.getClipWidth(),
            clipH = g.getClipHeight();
        if (line.clipped)
          g.clipRect(
              clipX,
              y + Math.round(line.y * scale),
              clipW,
              Math.max(1, Math.round(line.clipHeight * scale)));
        int lx = x + Math.round(line.x * scale), ly = y + Math.round(line.y * scale);
        if (!line.marker.isEmpty()) {
          g.setFont(font(TextStyle.DEFAULT, zoom));
          g.setColor(0x242424);
          g.drawString(
              line.marker, lx - Math.round(18 * scale), ly + Math.round(line.markerOffset * scale));
        }
        for (ReviewComment comment : session.document().comments) {
          if (comment.resolved) continue;
          ReviewComment.Anchor first = commentAnchors.get(comment.startId),
              last = commentAnchors.get(comment.endId),
              current = commentAnchors.get(line.paragraphId);
          if (first == null
              || !first.sameStory(last)
              || !first.sameStory(current)
              || current.paragraph < first.paragraph
              || current.paragraph > last.paragraph) continue;
          int a =
              current.paragraph == first.paragraph
                  ? Math.max(line.start, comment.start)
                  : line.start;
          int b = current.paragraph == last.paragraph ? Math.min(line.end, comment.end) : line.end;
          if (b > a) {
            g.setColor(0xffedaa);
            g.fillRect(
                lx + Math.round(offsetX(line, a) * scale),
                ly,
                Math.max(1, Math.round((offsetX(line, b) - offsetX(line, a)) * scale)),
                Math.round(line.height * scale));
          }
        }
        int selectedBlock = editingBlock(line);
        if (session.cellSelection() == null
            && selectedBlock >= 0
            && selectedBlock >= selection.startBlock()
            && selectedBlock <= selection.endBlock()
            && (selectedBlock > selection.startBlock() || selection.start() < line.end)
            && (selectedBlock < selection.endBlock() || selection.end() > line.start)) {
          int
              a =
                  selectedBlock == selection.startBlock()
                      ? Math.max(selection.start(), line.start)
                      : line.start,
              b =
                  selectedBlock == selection.endBlock()
                      ? Math.min(selection.end(), line.end)
                      : line.end;
          g.setColor(0xc5e9e6);
          g.fillRect(
              lx + Math.round(offsetX(line, a) * scale),
              ly,
              Math.max(1, Math.round((offsetX(line, b) - offsetX(line, a)) * scale)),
              Math.round(line.height * scale));
        }
        boolean revised = false;
        for (LayoutEngine.Span span : line.spans)
          revised |= span.style.revision != null || span.style.formatRevision != null;
        if (line.block >= 0) {
          Paragraph paragraph = lineParagraph(line);
          revised |= paragraph.formatRevision != null;
          if (paragraph.breakRevision != null && line.end == paragraph.text.length()) {
            revised = true;
            g.setFont(font(TextStyle.DEFAULT, zoom));
            g.setColor(paragraph.breakRevision.inserted() ? 0x1261a0 : 0xb32222);
            g.drawString("¶", lx + Math.round(line.width * scale), ly);
          }
        }
        if (revised) {
          g.setColor(0xb32222);
          g.fillRect(
              x + Math.round((page.spec.left - 12) * scale),
              ly,
              Math.max(1, Math.round(scale)),
              Math.round(line.height * scale));
        }
        for (LayoutEngine.Span span : line.spans) {
          if (span.style.characters.hidden) continue;
          Font f = font(span.glyphStyle, zoom);
          g.setFont(f);
          int sx = lx + Math.round(span.x * scale), sy = ly + Math.round(span.top * scale);
          if (session.mergeData().highlight
              && line.block >= 0
              && InlineField.OBJECT.equals(span.text)) {
            InlineField field = lineParagraph(line).text.fieldAt(span.start);
            if (field != null && MailMerge.placeholder(field.instruction) != null) {
              g.setColor(0xe2e8f0);
              g.fillRect(sx, sy, Math.max(2, Math.round(span.width * scale)), f.getHeight());
            }
          }
          if (span.style.background != 0xffffff) {
            g.setColor(span.style.background);
            g.fillRect(sx, sy, Math.round(span.width * scale), f.getHeight());
          }
          g.setColor(span.style.color);
          drawCharacters(g, span, f, scale, sx, sy);
          if (span.style.hasUnderline()) {
            GeneralPath path = new GeneralPath();
            TextDecorations.underline(
                span,
                fontMetrics,
                (x1, y1, x2, y2) -> {
                  path.moveTo(sx + x1 * scale, sy + f.getAscent() + y1 * scale);
                  path.lineTo(sx + x2 * scale, sy + f.getAscent() + y2 * scale);
                });
            g.setColor(
                span.style.characters.underlineColor < 0
                    ? span.style.color
                    : span.style.characters.underlineColor);
            g.drawShape(
                path,
                new Stroke(
                    Math.max(1, TextDecorations.weight(span.style) * scale),
                    Stroke.CAP_BUTT,
                    Stroke.JOIN_MITER,
                    1));
          }
          if (span.style.strike || span.style.characters.doubleStrike) {
            g.setColor(span.style.color);
            int decorationY = sy + f.getAscent() - Math.round(span.glyphStyle.size * .35f * scale);
            g.drawLine(sx, decorationY, sx + Math.round(span.width * scale), decorationY);
            if (span.style.characters.doubleStrike)
              g.drawLine(
                  sx,
                  decorationY - Math.max(2, Math.round(1.5f * scale)),
                  sx + Math.round(span.width * scale),
                  decorationY - Math.max(2, Math.round(1.5f * scale)));
          }
        }
        g.setColor(0x242424);
        for (float bar : line.barTabs)
          g.fillRect(
              lx + Math.round(bar * scale),
              ly,
              Math.max(1, Math.round(.5f * scale)),
              Math.round(line.height * scale));
        if (session.cellSelection() == null && line == caretLine && selection.collapsed()) {
          g.setColor(0x087f80);
          int cx = lx + Math.round(offsetX(line, selection.caret) * scale);
          g.fillRect(cx, ly, 2, Math.round(line.height * scale));
        }
        if (line.clipped) g.setClip(clipX, clipY, clipW, clipH);
      }
    }
    if (OfficeTheme.mobile() && session.cellSelection() == null && !selection.collapsed()) {
      g.setColor(OfficeTheme.BLUE);
      int radius = OfficeTheme.px(6);
      for (int side = 0; side < 2; side++) {
        int[] point = handlePoint(side == 0);
        if (point != null)
          g.fillArc(
              point[0] - viewportX() + getX() + getScrollX() - radius,
              point[1] - viewportY() + getY() + getScrollY() - radius,
              radius * 2,
              radius * 2,
              0,
              360);
      }
    }
  }

  public void selectAll() {
    int first = 0, last = session.editingDocument().blocks.size() - 1;
    while (first <= last && !(session.editingDocument().blocks.get(first) instanceof Paragraph))
      first++;
    while (last >= first && !(session.editingDocument().blocks.get(last) instanceof Paragraph))
      last--;
    if (first <= last)
      session.selectRange(first, 0, last, session.editingDocument().paragraph(last).text.length());
    refresh();
  }

  private void drawCharacters(
      Graphics g, LayoutEngine.Span span, Font f, float scale, int x, int y) {
    if (span.displayText.isEmpty()) return;
    if (span.style.characters.scale == 100
        && span.style.characters.spacing == 0
        && !span.style.characters.smallCaps) {
      textEffects.draw(g, paintText(span, f, scale), x, y, f, span.style, scale);
      return;
    }
    float stretch = span.style.characters.scale / 100;
    Transform original = g.getTransform();
    Transform transform = original.copy();
    transform.translate(x, y);
    transform.scale(stretch, 1);
    try {
      g.setTransform(transform);
      if (span.style.characters.spacing == 0 && !span.style.characters.smallCaps) {
        textEffects.draw(g, paintText(span, f, scale), 0, 0, f, span.style, scale);
      } else {
        String value = "\t".equals(span.text) ? paintText(span, f, scale) : span.rawDisplay;
        RichText source = RichText.of(value, span.style);
        float advance = 0;
        for (int at = 0; at < value.length(); ) {
          int end = Graphemes.next(source, at);
          String raw = value.substring(at, end);
          Font glyph = font(span.style.glyphStyle(raw), zoom);
          g.setFont(glyph);
          textEffects.draw(
              g,
              span.style.displayText(raw),
              Math.round(advance * scale / stretch),
              f.getAscent() - glyph.getAscent(),
              glyph,
              span.style,
              scale);
          advance += CharacterMetrics.width(fontMetrics, raw, span.style);
          at = end;
        }
      }
    } finally {
      g.setTransform(original);
      g.setFont(f);
    }
  }

  private float offsetX(LayoutEngine.Line line, int offset) {
    for (LayoutEngine.Span s : line.spans)
      if (offset <= s.end) {
        if (offset == s.end) return s.x + s.width;
        if ("\t".equals(s.text)) return s.x + (offset > s.start ? s.width : 0);
        return s.x
            + CharacterMetrics.width(
                fontMetrics, s.text.substring(0, Math.max(0, offset - s.start)), s.style);
      }
    return line.width;
  }

  private LayoutEngine.Line lineAt(int absX, int absY) {
    int page = pageAt(absY - viewportY() + getScrollY());
    float y = (absY - viewportY() + getScrollY() - pageY(page)) / scale();
    float x = (absX - viewportX() - pageX(page)) / scale();
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

  private int editingBlock(LayoutEngine.Line line) {
    if (!session.inCell()) return line.row < 0 ? line.block : -1;
    if (line.block != session.cellBlock()
        || line.row != session.cellRow()
        || line.column != session.cellColumn()) return -1;
    Document d = session.editingDocument();
    for (int i = 0; i < d.blocks.size(); i++)
      if (d.paragraph(i).id.equals(line.paragraphId)) return i;
    return -1;
  }

  private Paragraph lineParagraph(LayoutEngine.Line line) {
    if (line.row < 0) return session.document().paragraph(line.block);
    for (Paragraph p :
        ((Table) session.document().blocks.get(line.block)).cell(line.row, line.column))
      if (p.id.equals(line.paragraphId)) return p;
    throw new IllegalStateException("Missing cell paragraph");
  }

  private int offsetAt(LayoutEngine.Line line, int absX) {
    float x = (absX - viewportX() - pageX(line.page)) / scale() - line.x;
    int best = line.start;
    float distance = Float.MAX_VALUE;
    Paragraph p = lineParagraph(line);
    for (int i = line.start; i <= line.end; i++) {
      if (!Graphemes.isBoundary(p.text, i)) continue;
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
    if (OfficeTheme.mobile()) {
      touchX = x;
      touchY = y;
      touchMoved = touchSelecting = false;
      pinchStart = 0;
      dragHandle = hitHandle(x, y);
      if (dragHandle != 0) {
        touchSelecting = true;
        return;
      }
      super.pointerPressed(x, y);
      return;
    }
    placeCaret(x, y);
  }

  private void placeCaret(int x, int y) {
    finishComposing();
    LayoutEngine.Line line = lineAt(x, y);
    for (int page = 0; page < layout().pages.size(); page++) {
      float px = (x - viewportX() - pageX(page)) / scale();
      float py = (y - viewportY() + getScrollY() - pageY(page)) / scale();
      for (LayoutEngine.PlacedPicture pic : layout().pages.get(page).pictures) {
        if (px >= pic.x && px <= pic.x + pic.width && py >= pic.y && py <= pic.y + pic.height) {
          stopInput();
          if (pictureEditor != null) pictureEditor.edit(pic.block);
          return;
        }
      }
      for (LayoutEngine.Box box : layout().pages.get(page).boxes) {
        if (px < box.x || px > box.x + box.width || py < box.y || py > box.y + box.height) continue;
        float best = Float.MAX_VALUE;
        for (LayoutEngine.Line candidate : layout().pages.get(page).lines) {
          if (candidate.block != box.block
              || candidate.row != box.row
              || candidate.column != box.column) continue;
          float distance = Math.abs(py - candidate.y - candidate.height / 2);
          if (distance < best) {
            best = distance;
            line = candidate;
          }
        }
      }
    }
    if (line == null) return;
    stopInput();
    if (line.row >= 0) session.enterCell(line.block, line.row, line.column);
    else session.leaveCell();
    int block = editingBlock(line), offset = offsetAt(line, x);
    if (block < 0) return;
    session.select(block, offset, offset);
    rememberLine(line);
    dragBlock = block;
    dragAnchor = offset;
    dragTable = line.row < 0 ? -1 : line.block;
    dragRow = line.row;
    dragColumn = line.column;
    startInput();
    refresh();
    changed.run();
  }

  @Override
  public void pointerDragged(int x, int y) {
    if (OfficeTheme.mobile() && !touchSelecting) {
      if (Math.abs(x - touchX) + Math.abs(y - touchY) > OfficeTheme.px(6)) touchMoved = true;
      super.pointerDragged(x, y);
      return;
    }
    if (OfficeTheme.mobile() && dragHandle != 0) {
      LayoutEngine.Line target = lineAt(x, y);
      if (target != null && editingBlock(target) >= 0) {
        Selection old = session.selection();
        if (dragHandle < 0)
          session.selectRange(editingBlock(target), offsetAt(target, x), old.block, old.caret);
        else
          session.selectRange(
              old.anchorBlock, old.anchor, editingBlock(target), offsetAt(target, x));
        refresh();
      }
      return;
    }
    LayoutEngine.Line line = lineAt(x, y);
    if (line != null
        && dragBlock >= 0
        && dragTable >= 0
        && line.block == dragTable
        && line.row >= 0
        && (line.row != dragRow || line.column != dragColumn || session.cellSelection() != null)) {
      session.selectCells(dragTable, dragRow, dragColumn, line.row, line.column);
      rememberLine(line);
      refresh();
      changed.run();
      return;
    }
    if (line != null && dragBlock >= 0 && editingBlock(line) >= 0) {
      session.selectRange(dragBlock, dragAnchor, editingBlock(line), offsetAt(line, x));
      rememberLine(line);
      refresh();
    } else super.pointerDragged(x, y);
  }

  @Override
  public void pointerReleased(int x, int y) {
    if (OfficeTheme.mobile() && !touchMoved && !touchSelecting && pinchStart == 0) placeCaret(x, y);
    dragBlock = -1;
    dragTable = -1;
    dragHandle = 0;
    pinchStart = 0;
    super.pointerReleased(x, y);
  }

  @Override
  protected boolean pinch(float ratio) {
    if (pinchStart == 0) pinchStart = zoom;
    touchMoved = true;
    setZoom(pinchStart * ratio);
    return true;
  }

  @Override
  public void longPointerPress(int x, int y) {
    if (!OfficeTheme.mobile()) {
      super.longPointerPress(x, y);
      return;
    }
    LayoutEngine.Line line = lineAt(x, y);
    if (line == null) return;
    if (line.row >= 0) session.enterCell(line.block, line.row, line.column);
    else session.leaveCell();
    String text = lineParagraph(line).text.toString();
    int offset = Math.min(offsetAt(line, x), text.length());
    int a = offset, z = offset;
    while (a > 0 && isWordChar(text.charAt(a - 1))) a--;
    while (z < text.length() && isWordChar(text.charAt(z))) z++;
    if (a == z && z < text.length()) z++;
    session.select(editingBlock(line), a, z);
    dragBlock = editingBlock(line);
    dragAnchor = a;
    dragTable = line.row < 0 ? -1 : line.block;
    dragRow = line.row;
    dragColumn = line.column;
    touchSelecting = true;
    startInput();
    refresh();
  }

  // Character.isLetter/isLetterOrDigit are not in the Codename One class library. Cased
  // letters differ between upper and lower case; uncased scripts (CJK, Thai, Arabic,
  // Hebrew, Indic) start above Latin Extended and are treated as word characters.
  private static boolean isWordChar(char c) {
    return Character.isDigit(c)
        || Character.toUpperCase(c) != Character.toLowerCase(c)
        || (c >= 0x0590 && !Character.isWhitespace(c) && !Character.isHighSurrogate(c)
            && !Character.isLowSurrogate(c) && (c < 0x2000 || c > 0x2BFF) && (c < 0x3000 || c > 0x303F));
  }

  private int[] handlePoint(boolean anchor) {
    Selection s = session.selection();
    int block = anchor ? s.anchorBlock : s.block, offset = anchor ? s.anchor : s.caret;
    for (int page = 0; page < layout().pages.size(); page++)
      for (LayoutEngine.Line line : layout().pages.get(page).lines)
        if (editingBlock(line) == block && offset >= line.start && offset <= line.end)
          return new int[] {
            viewportX() + pageX(line.page) + Math.round((line.x + offsetX(line, offset)) * scale()),
            viewportY() - getScrollY() + pageY(page) + Math.round((line.y + line.height) * scale())
          };
    return null;
  }

  private int hitHandle(int x, int y) {
    if (session.cellSelection() != null || session.selection().collapsed()) return 0;
    for (int side = -1; side <= 1; side += 2) {
      int[] point = handlePoint(side < 0);
      if (point != null
          && Math.abs(x - point[0]) <= OfficeTheme.px(22)
          && Math.abs(y - point[1]) <= OfficeTheme.px(22)) return side;
    }
    return 0;
  }

  public void resumeInput() {
    if (input == null) startInput();
  }

  public void startInput() {
    requestFocus();
    // Explicit focus entry rebinds the editor after a native field or dialog
    // has owned the platform session. Per-keystroke refresh only pushes state.
    stopInput();
    if (host.isTextInputSupported()) {
      inputBinding = new InputBinding(this);
      input = host.startTextInput(inputBinding, config);
      if (input == null) {
        inputBinding.close();
        inputBinding = null;
      }
    }
    if (input == null) {
      Dialog.show(
          "Keyboard unavailable",
          "This port does not expose the low-level text input API required by the document canvas.",
          "OK",
          null);
    } else host.updateTextInputState(input, getEditingState());
  }

  public void stopInput() {
    session.breakTypingGroup();
    finishComposing();
    if (inputBinding != null) {
      inputBinding.close();
      inputBinding = null;
    }
    if (input != null) {
      Object old = input;
      input = null;
      host.stopTextInput(old);
    }
  }

  @Override
  protected void laidOut() {
    super.laidOut();
    if (input != null) host.updateTextInputState(input, getEditingState());
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
    int top = rect[1] - viewportY() + getScrollY();
    int bottom = top + rect[3] + 12;
    if (top < getScrollY()) setScrollY(Math.max(0, top - 12));
    else if (bottom > getScrollY() + getHeight()) setScrollY(bottom - getHeight());
    repaint();
  }

  static final String MIME_NATIVE = "application/x-cn1-wordprocessor+xml";
  // Richest first: our own model, then the formats other editors put on the pasteboard.
  static final String[] ACCEPTED_MIME_TYPES = {
    MIME_NATIVE,
    ClipboardContent.MIME_HTML,
    ClipboardContent.MIME_RTF,
    ClipboardContent.MIME_MARKDOWN,
    ClipboardContent.MIME_PNG,
    ClipboardContent.MIME_JPEG,
    ClipboardContent.MIME_GIF,
    ClipboardContent.MIME_FILE,
    ClipboardContent.MIME_URI_LIST,
    ClipboardContent.MIME_TEXT
  };

  /** Receives files dropped on the document that should open rather than be inserted. */
  public interface FileDropHandler {
    /** Returns true when the file was handled (for example opened as a new document). */
    boolean dropped(String path);
  }

  private FileDropHandler fileDropHandler;

  public void setFileDropHandler(FileDropHandler handler) {
    fileDropHandler = handler;
  }

  public void clipboard(String action) {
    session.breakTypingGroup();
    finishComposing();
    try {
      if ("paste".equals(action)) {
        ClipboardContent data = Display.getInstance().getClipboardContent();
        if (!importContent(data)) {
          Object plain = Display.getInstance().getPasteDataFromClipboard();
          if (plain instanceof String) {
            session.pasteText((String) plain);
            didEdit();
          }
        }
      } else {
        ClipboardContent content = selectionContent();
        if (content == null) return;
        Display.getInstance().copyToClipboard(content);
        if ("cut".equals(action)) {
          session.replace("");
          didEdit();
        }
      }
    } catch (java.io.IOException | IllegalArgumentException e) {
      Dialog.show("Clipboard", e.getMessage(), "OK", null);
    } finally {
      session.breakTypingGroup();
    }
  }

  /** The selection in every format other applications read: model XML, HTML, RTF, Markdown, text. */
  private ClipboardContent selectionContent() {
    Document fragment = session.copySelection();
    if (fragment == null) return null;
    ClipboardContent content =
        new ClipboardContent()
            .setData(ClipboardContent.MIME_TEXT, session.selectedText())
            .setData(ClipboardContent.MIME_HTML, TextFormats.html(fragment))
            .setData(MIME_NATIVE, DocumentXml.write(fragment));
    // RTF and Markdown cannot carry pictures; HTML and the native model still do.
    try {
      content.setData(ClipboardContent.MIME_RTF, TextFormats.write(fragment, RichTextFormat.RTF));
      content.setData(
          ClipboardContent.MIME_MARKDOWN, TextFormats.write(fragment, RichTextFormat.MARKDOWN));
    } catch (IllegalArgumentException pictures) {
    }
    return content;
  }

  private static String text(ClipboardContent data, String mime) throws java.io.IOException {
    Object value = data.getData(mime);
    if (value instanceof byte[]) return new String((byte[]) value, "UTF-8");
    return value instanceof String ? (String) value : null;
  }

  /**
   * Inserts clipboard or drag content at the caret using the richest format offered. Returns
   * false when nothing usable was present.
   */
  boolean importContent(ClipboardContent data) throws java.io.IOException {
    if (data == null) return false;
    String value;
    if ((value = text(data, MIME_NATIVE)) != null) session.paste(DocumentXml.read(value));
    else if ((value = text(data, ClipboardContent.MIME_HTML)) != null)
      session.paste(TextFormats.read(value, RichTextFormat.HTML));
    else if ((value = text(data, ClipboardContent.MIME_RTF)) != null)
      session.paste(TextFormats.read(value, RichTextFormat.RTF));
    else if (insertImage(data)) return true;
    else if (importFiles(data.getFiles())) return true;
    else if ((value = text(data, ClipboardContent.MIME_MARKDOWN)) != null)
      session.paste(TextFormats.read(value, RichTextFormat.MARKDOWN));
    else if ((value = text(data, ClipboardContent.MIME_TEXT)) != null) session.pasteText(value);
    else return false;
    didEdit();
    return true;
  }

  private boolean insertImage(ClipboardContent data) {
    for (String mime :
        new String[] {ClipboardContent.MIME_PNG, ClipboardContent.MIME_JPEG, ClipboardContent.MIME_GIF}) {
      byte[] bytes = data.getBytes(mime);
      if (bytes != null && bytes.length > 0) {
        insertPictureBytes(bytes, "Pasted picture");
        return true;
      }
    }
    return false;
  }

  private void insertPictureBytes(byte[] bytes, String alt) {
    if (session.inCell()) session.leaveCell();
    int[] size = Picture.shape(bytes);
    if ((long) size[0] * size[1] > 16000000 || bytes.length > 20 * 1024 * 1024)
      throw new IllegalArgumentException("Picture exceeds 16 megapixels or 20 MiB");
    float width = Math.min(size[0] * .75f, session.activeSection().raw.page.contentWidth());
    session.insertPicture(bytes, width, width * size[1] / size[0], alt);
    didEdit();
  }

  /** Images are inserted inline; documents go to the host (open) or are inserted as content. */
  private boolean importFiles(String[] files) throws java.io.IOException {
    if (files == null || files.length == 0) return false;
    boolean any = false;
    for (String path : files) {
      String lower = path.toLowerCase();
      if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
          || lower.endsWith(".gif")) {
        insertPictureBytes(readFile(path), path.substring(path.lastIndexOf('/') + 1));
        any = true;
      } else if (fileDropHandler != null && fileDropHandler.dropped(path)) {
        any = true;
      } else {
        RichTextFormat format =
            lower.endsWith(".html") || lower.endsWith(".htm") ? RichTextFormat.HTML
                : lower.endsWith(".rtf") ? RichTextFormat.RTF
                : lower.endsWith(".md") ? RichTextFormat.MARKDOWN
                : lower.endsWith(".txt") ? RichTextFormat.PLAIN_TEXT
                : null;
        if (format == null) continue;
        String body = new String(readFile(path), "UTF-8");
        if (format == RichTextFormat.PLAIN_TEXT) session.pasteText(body);
        else session.paste(TextFormats.read(body, format));
        any = true;
      }
    }
    return any;
  }

  private static byte[] readFile(String path) throws java.io.IOException {
    try (java.io.InputStream in =
        com.codename1.io.FileSystemStorage.getInstance().openInputStream(path)) {
      return com.codename1.io.Util.readInputStream(in);
    }
  }

  // ---- Native drag and drop (OS <-> document) ----

  @Override
  protected boolean canAcceptNativeDrop(ClipboardContent content) {
    return content != null && content.findPreferredMimeType(ACCEPTED_MIME_TYPES) != null;
  }

  @Override
  protected void nativeDragOver(com.codename1.ui.NativeDropEvent evt) {
    // The caret follows the pointer so the insertion point is visible before release.
    if (!evt.isLocal() || !insideSelection(evt.getX(), evt.getY())) {
      moveCaretTo(evt.getX(), evt.getY());
      evt.accept(evt.isLocal() ? NativeDragOperation.ACTION_MOVE : NativeDragOperation.ACTION_COPY);
    } else evt.reject();
  }

  @Override
  protected void nativeDrop(com.codename1.ui.NativeDropEvent evt) {
    boolean local = evt.isLocal();
    if (local && insideSelection(evt.getX(), evt.getY())) {
      evt.reject();
      return;
    }
    try {
      session.breakTypingGroup();
      if (local) {
        // An internal move is one undoable step: remove the source, then insert at the target.
        Document fragment = session.copySelection();
        int[] target = hitTest(evt.getX(), evt.getY());
        if (fragment == null || target == null) {
          evt.reject();
          return;
        }
        session.moveSelectionTo(target[0], target[1], fragment);
        didEdit();
        evt.accept(NativeDragOperation.ACTION_MOVE);
      } else {
        moveCaretTo(evt.getX(), evt.getY());
        if (importContent(evt.getContent())) evt.accept(NativeDragOperation.ACTION_COPY);
        else evt.reject();
      }
    } catch (java.io.IOException | IllegalArgumentException e) {
      evt.reject();
      Dialog.show("Drop", e.getMessage(), "OK", null);
    } finally {
      session.breakTypingGroup();
    }
  }

  @Override
  protected NativeDragOperation createNativeDragOperation(int x, int y) {
    // Only a press inside a non-empty selection starts a drag; elsewhere drag extends selection.
    if (session.selection().collapsed() || !insideSelection(x, y)) return null;
    ClipboardContent content = selectionContent();
    if (content == null) return null;
    return new NativeDragOperation(content)
        .setAllowedActions(NativeDragOperation.ACTION_COPY | NativeDragOperation.ACTION_MOVE)
        .setLabel(session.selectedText());
  }

  /** Block and offset under an absolute point, without changing the selection. */
  private int[] hitTest(int x, int y) {
    LayoutEngine.Line line = lineAt(x, y);
    if (line == null || line.row >= 0) return null;
    int block = editingBlock(line);
    return block < 0 ? null : new int[] {block, offsetAt(line, x)};
  }

  private boolean insideSelection(int x, int y) {
    Selection sel = session.selection();
    int[] at = hitTest(x, y);
    if (at == null || sel.collapsed()) return false;
    int sb = sel.startBlock(), eb = sel.endBlock();
    if (at[0] < sb || at[0] > eb) return false;
    if (at[0] == sb && at[1] < sel.start()) return false;
    return at[0] != eb || at[1] <= sel.end();
  }

  private void moveCaretTo(int x, int y) {
    int[] at = hitTest(x, y);
    if (at == null) return;
    session.leaveCell();
    session.select(at[0], at[1], at[1]);
    repaint();
  }

  @Override
  public void commitText(String text) {
    if (session.isComposing()) {
      session.compose(text, text.length());
      session.finishComposition();
    } else if ("\n".equals(text)) session.enterParagraph();
    else session.typeText(text, System.currentTimeMillis());
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
    if (session.cellSelection() != null || !x.collapsed()) {
      session.replace("");
      didEdit();
      return;
    }
    Paragraph p = session.editingDocument().paragraph(x.block);
    int a = Math.max(0, x.start() - Math.max(0, before)),
        b = Math.min(p.text.length(), x.end() + Math.max(0, after));
    if (a > 0 && a < p.text.length() && Character.isLowSurrogate(p.text.charAt(a))) a--;
    if (b < p.text.length() && b > 0 && Character.isLowSurrogate(p.text.charAt(b))) b++;
    session.select(x.block, a, b);
    session.replace("");
    didEdit();
  }

  private boolean moveCellSelection(int command, boolean extend) {
    if (!session.inCell()
        || (command != KEY_LEFT
            && command != KEY_RIGHT
            && command != KEY_UP
            && command != KEY_DOWN)) return false;
    CellSelection range = session.cellSelection();
    Selection text = session.selection();
    Document story = session.editingDocument();
    if (range == null) {
      if (!extend) return false;
      boolean start = text.block == 0 && text.caret == 0;
      boolean end =
          text.block == story.blocks.size() - 1
              && text.caret == story.paragraph(text.block).text.length();
      if (!((command == KEY_LEFT || command == KEY_UP) && start
          || (command == KEY_RIGHT || command == KEY_DOWN) && end)) return false;
    }
    int tableBlock = session.cellBlock();
    Table table = (Table) session.document().blocks.get(tableBlock);
    if (range != null && !extend) {
      boolean end = command == KEY_RIGHT || command == KEY_DOWN;
      session.enterCell(
          tableBlock,
          end ? range.lastRow : range.firstRow,
          end ? range.lastColumn : range.firstColumn);
      story = session.editingDocument();
      int block = end ? story.blocks.size() - 1 : 0;
      int offset = end ? story.paragraph(block).text.length() : 0;
      session.select(block, offset, offset);
    } else {
      int ar = range == null ? session.cellRow() : range.anchorRow;
      int ac = range == null ? session.cellColumn() : range.anchorColumn;
      int fr = range == null ? ar : range.focusRow, fc = range == null ? ac : range.focusColumn;
      Table.Merge span = table.span(fr, fc);
      if (command == KEY_LEFT) fc = Math.max(0, span.column - 1);
      if (command == KEY_RIGHT) fc = Math.min(table.columns() - 1, span.column + span.columns);
      if (command == KEY_UP) fr = Math.max(0, span.row - 1);
      if (command == KEY_DOWN) fr = Math.min(table.rows() - 1, span.row + span.rows);
      session.selectCells(tableBlock, ar, ac, fr, fc);
    }
    navigationLine = null;
    refresh();
    changed.run();
    ensureCaretVisible();
    return true;
  }

  @Override
  public void onKeyCommand(int command, int modifiers) {
    session.breakTypingGroup();
    finishComposing();
    Selection x = session.selection();
    Paragraph p = session.editingDocument().paragraph(x.block);
    boolean extend = (modifiers & MOD_SHIFT) != 0;
    if (moveCellSelection(command, extend)) return;
    boolean word = (modifiers & (MOD_CTRL | MOD_ALT)) != 0;
    boolean paragraphMove = word && (command == KEY_UP || command == KEY_DOWN);
    boolean vertical =
        !paragraphMove
            && (command == KEY_UP
                || command == KEY_DOWN
                || command == KEY_PAGE_UP
                || command == KEY_PAGE_DOWN);
    if (!vertical) verticalSelection = null;
    LayoutEngine.Line targetLine = null;
    int caret = x.caret, block = x.block;
    switch (command) {
      case KEY_BACKSPACE:
        if (word) TextNavigation.deleteWord(session, false);
        else session.deleteBackward();
        didEdit();
        return;
      case KEY_DELETE:
        if (word) TextNavigation.deleteWord(session, true);
        else session.deleteForward();
        didEdit();
        return;
      case KEY_TAB:
        if (session.inCell()) {
          session.nextCell(extend);
          didEdit();
          return;
        }
        if (!p.list.isEmpty()) {
          session.paragraph(
              "listLevel",
              String.valueOf(Math.max(0, Math.min(8, p.listLevel + (extend ? -1 : 1)))));
          didEdit();
        } else commitText("\t");
        return;
      case KEY_LEFT:
        if (!extend && !x.collapsed()) {
          block = x.startBlock();
          caret = x.start();
        } else if (word) {
          int[] target = TextNavigation.word(session.editingDocument(), block, caret, false);
          block = target[0];
          caret = target[1];
        } else if (caret > 0) {
          caret = Graphemes.previous(p.text, caret);
        } else if (block > 0
            && session.editingDocument().blocks.get(block - 1) instanceof Paragraph) {
          block--;
          caret = session.editingDocument().paragraph(block).text.length();
        }
        break;
      case KEY_RIGHT:
        if (!extend && !x.collapsed()) {
          block = x.endBlock();
          caret = x.end();
        } else if (word) {
          int[] target = TextNavigation.word(session.editingDocument(), block, caret, true);
          block = target[0];
          caret = target[1];
        } else if (caret < p.text.length()) caret = Graphemes.next(p.text, caret);
        else if (block + 1 < session.editingDocument().blocks.size()
            && session.editingDocument().blocks.get(block + 1) instanceof Paragraph) {
          block++;
          caret = 0;
        }
        break;
      case KEY_HOME:
      case KEY_END:
        if ((modifiers & MOD_CTRL) != 0) {
          int[] target = TextNavigation.boundary(session.editingDocument(), command == KEY_END);
          block = target[0];
          caret = target[1];
        } else if ((modifiers & MOD_ALT) != 0) {
          caret = command == KEY_HOME ? 0 : p.text.length();
        } else {
          targetLine = caretLine();
          caret =
              targetLine == null
                  ? (command == KEY_HOME ? 0 : p.text.length())
                  : command == KEY_HOME ? targetLine.start : targetLine.end;
        }
        break;
      case KEY_UP:
      case KEY_DOWN:
      case KEY_PAGE_UP:
      case KEY_PAGE_DOWN:
        {
          LayoutEngine.Line current = caretLine();
          if (paragraphMove) {
            int[] target =
                command == KEY_DOWN && (modifiers & MOD_ALT) != 0
                    ? TextNavigation.paragraphEnd(session.editingDocument(), block, caret)
                    : TextNavigation.paragraph(
                        session.editingDocument(), block, caret, command == KEY_DOWN);
            block = target[0];
            caret = target[1];
          } else if (current != null) {
            if (verticalSelection != x || navigationLayout != layout())
              preferredX = current.x + offsetX(current, caret);
            targetLine =
                verticalLine(
                    current,
                    command == KEY_DOWN || command == KEY_PAGE_DOWN,
                    command == KEY_PAGE_UP || command == KEY_PAGE_DOWN);
            block = editingBlock(targetLine);
            caret =
                offsetAt(
                    targetLine,
                    viewportX() + pageX(targetLine.page) + Math.round(preferredX * scale()));
          }
          break;
        }
      case KEY_SELECT_ALL:
        {
          selectAll();
          return;
        }
      case KEY_COPY:
      case KEY_CUT:
        clipboard(command == KEY_CUT ? "cut" : "copy");
        return;
      case KEY_PASTE:
        clipboard("paste");
        return;
      case KEY_UNDO:
        session.undo();
        didEdit();
        return;
      case KEY_REDO:
        session.redo();
        didEdit();
        return;
      case KEY_ESCAPE:
        stopInput();
        return;
      default:
        return;
    }
    session.selectRange(extend ? x.anchorBlock : block, extend ? x.anchor : caret, block, caret);
    rememberLine(targetLine);
    if (vertical) verticalSelection = session.selection();
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
        textIndex().text,
        textIndex().absolute(s.anchorBlock, s.anchor),
        textIndex().absolute(s.block, s.caret),
        composingStart < 0 ? -1 : textIndex().absolute(s.block, composingStart),
        composingEnd < 0 ? -1 : textIndex().absolute(s.block, composingEnd));
  }

  @Override
  public int[] getCaretRect() {
    return rectForOffset(
        textIndex().absolute(session.selection().block, session.selection().caret));
  }

  @Override
  public int[] rectForOffset(int offset) {
    int[] position = textIndex().position(offset);
    int block = position[0];
    offset = position[1];
    LayoutEngine.Line chosen = null;
    Selection selection = session.selection();
    if (selection.block == block && selection.caret == offset) chosen = caretLine();
    if (chosen == null)
      for (LayoutEngine.Page page : layout().pages)
        for (LayoutEngine.Line line : page.lines)
          if (editingBlock(line) == block && offset >= line.start && offset <= line.end)
            chosen = line;
    if (chosen != null)
      return new int[] {
        viewportX()
            + pageX(chosen.page)
            + Math.round((chosen.x + offsetX(chosen, offset)) * scale()),
        viewportY() - getScrollY() + pageY(chosen.page) + Math.round(chosen.y * scale()),
        2,
        Math.round(chosen.height * scale())
      };
    return new int[] {viewportX(), viewportY(), 2, 20};
  }

  @Override
  public int offsetAtPoint(int x, int y) {
    LayoutEngine.Line line = lineAt(x, y);
    return line != null && editingBlock(line) >= 0
        ? textIndex().absolute(editingBlock(line), offsetAt(line, x))
        : textIndex().absolute(session.selection().block, session.selection().caret);
  }

  @Override
  public int[] selectionRects(int a, int b) {
    ArrayList<Integer> values = new ArrayList<Integer>();
    for (LayoutEngine.Page page : layout().pages)
      for (LayoutEngine.Line l : page.lines)
        if (editingBlock(l) >= 0
            && a < textIndex().absolute(editingBlock(l), l.end)
            && b > textIndex().absolute(editingBlock(l), l.start)) {
          int base = textIndex().absolute(editingBlock(l), 0);
          int start = Math.max(a - base, l.start), end = Math.min(b - base, l.end);
          values.add(viewportX() + pageX(l.page) + Math.round((l.x + offsetX(l, start)) * scale()));
          values.add(viewportY() - getScrollY() + pageY(l.page) + Math.round(l.y * scale()));
          values.add(Math.round((offsetX(l, end) - offsetX(l, start)) * scale()));
          values.add(Math.round(l.height * scale()));
        }
    int[] out = new int[values.size()];
    for (int i = 0; i < out.length; i++) out[i] = values.get(i);
    return out;
  }

  @Override
  public TextInputGeometry getGeometrySnapshot() {
    ArrayList<Integer> points = new ArrayList<Integer>();
    DocumentText index = textIndex();
    float scale = scale();
    int top = viewportY() - getScrollY();
    for (LayoutEngine.Page page : layout().pages) {
      for (LayoutEngine.Line line : page.lines) {
        int y = top + pageY(line.page) + Math.round(line.y * scale);
        int height = Math.round(line.height * scale);
        if (editingBlock(line) < 0 || y + height < viewportY() || y > viewportY() + getHeight())
          continue;
        int base = index.absolute(editingBlock(line), 0);
        Paragraph paragraph = lineParagraph(line);
        for (int offset = line.start; offset <= line.end; offset++) {
          if (!Graphemes.isBoundary(paragraph.text, offset)) continue;
          points.add(base + offset);
          points.add(
              viewportX()
                  + pageX(line.page)
                  + Math.round((line.x + offsetX(line, offset)) * scale));
          points.add(y);
          points.add(2);
          points.add(height);
        }
      }
    }
    int[] geometry = new int[points.size()];
    for (int i = 0; i < geometry.length; i++) geometry[i] = points.get(i);
    TextInputState state = getEditingState();
    return new TextInputGeometry(
        geometry,
        state.getSelectionStart(),
        state.getSelectionEnd(),
        selectionRects(state.getSelectionStart(), state.getSelectionEnd()),
        getCaretRect());
  }

  @Override
  public TextInputConfig getConfig() {
    return config;
  }

  @Override
  public void inputFocusGained() {}

  @Override
  public void inputFocusLost() {
    session.breakTypingGroup();
    input = null;
    inputBinding = null;
    finishComposing();
  }

  @Override
  public int getTextLength() {
    return textIndex().text.length();
  }

  @Override
  public String getTextRange(int a, int b) {
    String text = textIndex().text;
    a = Math.max(0, Math.min(text.length(), a));
    b = Math.max(a, Math.min(text.length(), b));
    return text.substring(a, b);
  }

  private boolean matchesCellSelection(int a, int b) {
    if (session.cellSelection() == null) return false;
    TextInputState state = getEditingState();
    return Math.min(a, b) == state.getSelectionStart() && Math.max(a, b) == state.getSelectionEnd();
  }

  @Override
  public void replaceRange(int a, int b, String text) {
    if (matchesCellSelection(a, b)) {
      commitText(text);
      return;
    }
    int[] start = textIndex().position(a), end = textIndex().position(b);
    session.selectRange(start[0], start[1], end[0], end[1]);
    commitText(text);
  }

  @Override
  public void setSelectionRange(int a, int b) {
    // Native input may echo the focused cell's text range after a grid selection.
    if (matchesCellSelection(a, b)) return;
    int[] start = textIndex().position(a), end = textIndex().position(b);
    session.selectRange(start[0], start[1], end[0], end[1]);
    refresh();
    ensureCaretVisible();
    changed.run();
  }
}
