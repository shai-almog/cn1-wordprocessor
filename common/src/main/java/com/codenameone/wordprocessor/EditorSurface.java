package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import com.codename1.ui.accessibility.AccessibilityAction;
import com.codename1.ui.accessibility.AccessibilityGrouping;
import com.codename1.ui.accessibility.AccessibilityNode;
import com.codename1.ui.accessibility.AccessibilityRole;
import com.codename1.ui.editor.EditorHost;
import com.codename1.ui.geom.Dimension;
import com.codename1.ui.geom.GeneralPath;
import com.codename1.ui.geom.Rectangle;
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
      new LinkedHashMap<>(4, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Picture, Image> entry) {
          int pixels = 0;
          for (Image image : values())
            if (image != null) pixels += image.getWidth() * image.getHeight();
          return size() > 1 && (size() > 3 || pixels > 8000000);
        }
      };

  private Image picture(Picture p) {
    var image = pictures.get(p);
    if (!pictures.containsKey(p)) {
      try {
        var bytes = p.bytes();
        image = Image.createImage(bytes, 0, bytes.length);
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest > 2048)
          image =
              image.scaled(
                  Math.max(1, image.getWidth() * 2048 / longest),
                  Math.max(1, image.getHeight() * 2048 / longest));
        if (p.look.adjusted()) image = adjusted(image, p.look);
      } catch (RuntimeException invalidImage) {
        image = null;
      }
      pictures.put(p, image);
    }
    return image;
  }

  // ---------------------------------------------------------------- in-place equation editing

  /** The equation being edited in place, or null. */
  private EquationEditing equation;
  /** Paragraph offset of the inline equation being painted, or -1. */
  private int paintingInline = -1;

  /** Starts in-place editing of the equation at the selection; false when there is none. */
  public boolean editEquation() {
    if (readOnly()) return false;
    equation = EquationEditing.at(session);
    repaint();
    return equation != null;
  }

  /** The in-place equation edit in progress, or null. */
  EquationEditing equationEditing() {
    return equation;
  }

  /** Ends in-place equation editing; an inline equation leaves the caret beside it. */
  public void endEquation(boolean before) {
    if (equation == null) return;
    var e = equation;
    equation = null;
    e.leave(before);
    changed.run();
    repaint();
  }

  /** Inserts a Structures gallery template in the equation being edited. */
  public void equationTemplate(String linear) {
    equationTemplate(linear, "");
  }

  /** A Structures template with its forced n-ary limit placement ("" keeps the default). */
  public void equationTemplate(String linear, String limits) {
    if (equation == null && !editEquation()) return;
    equation.insertTemplate(linear, limits);
    didEdit();
  }

  /** Applies a structure action (Insert Row Below, Remove Brackets, ...) at the equation caret. */
  public boolean equationStructure(int level, String action) {
    if (equation == null || !equation.structure(level, action)) return false;
    didEdit();
    return true;
  }

  /**
   * A click on a display equation: in-place editing starts with the caret at the clicked slot
   * position, as Wordcraft's equation_hit. False when (px, py) (page points) is not on one.
   */
  private boolean clickEquation(LayoutEngine.PlacedPicture pic, float px, float py) {
    var d = pic.picture().drawing;
    if (readOnly() || d == null || !"equation".equals(d.kind)) return false;
    var e = EquationEditing.at(session, pic.block());
    if (e == null) return false;
    e.hit(d, pic.width(), pic.height(), fontMetrics, px - pic.x(), py - pic.y());
    equation = e;
    repaint();
    return true;
  }

  /**
   * A click on an inline equation: in-place editing starts with the caret at the clicked slot,
   * as a display equation does (Wordcraft's equation_hit for inline math). The drawing's origin
   * is the span's left edge on the text baseline, as it is painted.
   */
  private boolean clickInlineEquation(LayoutEngine.Line line, int x, int y) {
    if (readOnly() || !textLine(line)) return false;
    float px = (x - viewportX() - pageX(line.page)) / scale() - line.x;
    float py = (y - viewportY() + getScrollY() - pageY(line.page)) / scale() - line.y;
    for (LayoutEngine.Span span : line.spans) {
      if (!InlineField.OBJECT.equals(span.text) || !span.displayText.isEmpty()) continue;
      if (px < span.x || px > span.x + span.width) continue;
      // The line's paragraph in the story being edited (a cell or note story the caller entered).
      var e = EquationEditing.inline(session, editingBlock(line), span.start);
      if (e == null) continue;
      float baseline = span.top + font(span.glyphStyle, zoom).getAscent() / scale();
      var pic = Equation.placed(lineParagraph(line).text.fieldAt(span.start), span.glyphStyle, line.block, 0, 0, 0, span.width);
      e.hit(pic.picture().drawing, pic.width(), pic.height(), fontMetrics, px - span.x, py - baseline);
      equation = e;
      repaint();
      return true;
    }
    return false;
  }

  /** Test hook: a click at the screen point ({@code x}, {@code y}), through the editor's own caret placement. */
  void clickForTest(int x, int y) {
    placeCaret(x, y);
  }

  /** Test hook: the screen point of page-point offset ({@code dx}, {@code dy}) from the inline equation at {@code offset} of {@code block}. */
  int[] inlineEquationPointForTest(int block, int offset, float dx, float dy) {
    for (var page : layout().pages)
      for (LayoutEngine.Line line : page.lines)
      if (line.block == block && line.row < 0)
        for (LayoutEngine.Span span : line.spans)
          if (span.start == offset && InlineField.OBJECT.equals(span.text)) {
            float baseline = span.top + font(span.glyphStyle, zoom).getAscent() / scale();
            return new int[] {Math.round((line.x + span.x + dx) * scale() + viewportX() + pageX(line.page)),
              Math.round((line.y + baseline + dy) * scale() + viewportY() - getScrollY() + pageY(line.page))};
          }
    return null;
  }

  /** Test hook: a click at page-point ({@code px}, {@code py}) on the display equation in {@code block}. */
  /** Test hook: the pointer position over page point (px, py) of {@code page}. */
  int[] screenPointForTest(int page, float px, float py) {
    return new int[] {
      Math.round(viewportX() + pageX(page) + px * scale()),
      Math.round(viewportY() - getScrollY() + pageY(page) + py * scale())
    };
  }

  boolean clickEquationForTest(int block, float px, float py) {
    for (var page : layout().pages)
      for (LayoutEngine.PlacedPicture pic : page.pictures)
        if (pic.block() == block) return clickEquation(pic, pic.x() + px, pic.y() + py);
    return false;
  }

  /** Keys while an equation is edited in place; true when consumed. */
  private boolean equationKey(int command, int modifiers) {
    boolean shift = (modifiers & MOD_SHIFT) != 0;
    String dir = command == KEY_LEFT ? "left" : command == KEY_RIGHT ? "right" : command == KEY_UP ? "up"
        : command == KEY_DOWN ? "down" : command == KEY_HOME ? "home" : command == KEY_END ? "end"
        : command == KEY_TAB ? (shift ? "prev" : "next") : null;
    if (dir != null) {
      if (!equation.move(dir)) endEquation("left".equals(dir));
    } else if (command == KEY_BACKSPACE) equation.backspace();
    else if (command == KEY_DELETE) equation.delete();
    else if (command == KEY_ESCAPE) endEquation(false);
    else return false;
    didEdit();
    return true;
  }

  private boolean editingThisEquation(LayoutEngine.PlacedPicture pic) {
    return equation != null && pic.block() == equation.block
        && (equation.field < 0 ? paintingInline < 0 : paintingInline == equation.field);
  }

  /** The in-equation caret over an equation drawing painted at (px, py). */
  private void paintEquationCaret(Graphics g, LayoutEngine.PlacedPicture pic, int px, int py, float scale) {
    float[] c = equation.caret(pic.picture().drawing, pic.width(), pic.height(), fontMetrics);
    g.setColor(0x1f4e79);
    g.fillRect(px + Math.round(c[0] * scale), py + Math.round(c[1] * scale), Math.max(1, Math.round(scale)),
        Math.max(2, Math.round(c[2] * scale)));
  }

  /** Paints a vector drawing from the primitives shared with PDF and HTML export. */
  private void paintDrawing(Graphics g, LayoutEngine.PlacedPicture pic, int px, int py, float scale) {
    if (editingThisEquation(pic)) {
      paintDrawingOnly(g, pic, px, py, scale);
      paintEquationCaret(g, pic, px, py, scale);
    } else paintDrawingOnly(g, pic, px, py, scale);
  }

  private void paintDrawingOnly(Graphics g, LayoutEngine.PlacedPicture pic, int px, int py, float scale) {
    DrawingOps.Ops ops = DrawingOps.build(pic.picture().drawing, pic.width(), pic.height(), fontMetrics);
    boolean shapes = g.isShapeSupported();
    boolean aa = g.isAntiAliased();
    g.setAntiAliased(true);
    int alpha = g.getAlpha();
    for (DrawingOps.Path p : ops.paths) {
      int n = p.points().length / 2;
      if (n < 2) continue;
      int[] xs = new int[n], ys = new int[n];
      var path = new GeneralPath();
      for (int i = 0; i < n; i++) {
        float fx = px + p.points()[i * 2] * scale, fy = py + p.points()[i * 2 + 1] * scale;
        xs[i] = Math.round(fx);
        ys[i] = Math.round(fy);
        if (i == 0) path.moveTo(fx, fy);
        else path.lineTo(fx, fy);
      }
      if (p.closed()) path.closePath();
      g.setAlpha(p.alpha() * alpha / 255);
      if (p.closed() && p.fill() >= 0) {
        g.setColor(p.fill());
        if (shapes) g.fillShape(path);
        else g.fillPolygon(xs, ys, n);
      }
      if (p.stroke() >= 0 && p.width() > 0) {
        g.setColor(p.stroke());
        float w = Math.max(1, p.width() * scale);
        if (shapes) g.drawShape(path, new Stroke(w, Stroke.CAP_ROUND, Stroke.JOIN_ROUND, 4));
        else if (p.closed()) g.drawPolygon(xs, ys, n);
        else for (int i = 1; i < n; i++) g.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i]);
      }
    }
    g.setAlpha(alpha);
    for (int k = 0; k < ops.images.size(); k++) {
      DrawingOps.Image im = ops.images.get(k);
      Image image = groupImage(pic.picture().drawing, ops, k);
      if (image == null) continue;
      g.setAlpha(alpha);
      var look = Picture.Look.NONE.rotate(Math.round(im.rotation()), false, false);
      drawPicture(g, image, look, Math.round(px + im.x() * scale), Math.round(py + im.y() * scale),
          Math.max(1, Math.round(im.width() * scale)), Math.max(1, Math.round(im.height() * scale)), scale);
    }
    for (DrawingOps.Text t : ops.texts) {
      var style = TextStyle.DEFAULT.with("size", String.valueOf(t.size()));
      if (t.bold()) style = style.with("bold", "true");
      if (t.italic()) style = style.with("italic", "true");
      var f = font(style, zoom);
      g.setFont(f);
      int width = f.stringWidth(t.text());
      int tx = px + Math.round(t.x() * scale), ty = py + Math.round(t.y() * scale);
      int box = Math.round(t.width() * scale);
      float spin = t.spin();
      int scx = px + Math.round((t.x() + t.width() / 2) * scale), scy = py + Math.round((t.y() + t.size() / 2) * scale);
      if (spin != 0) g.rotate((float) Math.toRadians(spin), scx, scy);
      try {
      if (t.rotation() != 0) {
        float cx = px + t.x() * scale, cy = py + t.y() * scale;
        g.rotate((float) Math.toRadians(t.rotation()), Math.round(cx), Math.round(cy));
        int sx = Math.round(cx), sy = Math.round(cy) - Math.round(t.size() * 1.2f * scale);
        if (t.rotation() == 270) sy = Math.round(cy);
        g.setColor(t.color());
        g.drawString(t.text(), t.rotation() == 90 ? sx : sx - box, sy);
        g.rotate((float) -Math.toRadians(t.rotation()), Math.round(cx), Math.round(cy));
        continue;
      }
      if ("center".equals(t.align())) tx += (box - width) / 2;
      else if ("right".equals(t.align())) tx += box - width;
      if (t.outline() >= 0) {
        g.setColor(t.outline());
        for (int d = 0; d < 4; d++) g.drawString(t.text(), tx + (d % 2 == 0 ? -1 : 1), ty + (d < 2 ? -1 : 1));
      }
      g.setColor(t.color());
      g.drawString(t.text(), tx, ty);
      } finally {
        if (spin != 0) g.rotate((float) -Math.toRadians(spin), scx, scy);
      }
    }
    g.setAntiAliased(aa);
  }

  /** Corrections, color and transparency, applied once per picture to the decoded pixels. */
  static Image adjusted(Image image, Picture.Look look) {
    var px = image.getRGB();
    int bright = look.brightness, contrast = look.contrast;
    if ("washout".equals(look.colorMode)) {
      bright = 70;
      contrast = -70;
    }
    float gain = 1 + contrast / 100f, offset = bright * 2.55f;
    for (int i = 0; i < px.length; i++) {
      int v = px[i], a = v >>> 24, r = v >> 16 & 255, g = v >> 8 & 255, b = v & 255;
      if ("grayscale".equals(look.colorMode) || "blackWhite".equals(look.colorMode)) {
        int l = (r * 299 + g * 587 + b * 114) / 1000;
        if ("blackWhite".equals(look.colorMode)) l = l >= 128 ? 255 : 0;
        r = g = b = l;
      }
      r = clamp((r - 128) * gain + 128 + offset);
      g = clamp((g - 128) * gain + 128 + offset);
      b = clamp((b - 128) * gain + 128 + offset);
      a = a * (100 - look.transparency) / 100;
      px[i] = a << 24 | r << 16 | g << 8 | b;
    }
    px = PictureEffects.apply(px, image.getWidth(), image.getHeight(), look);
    return Image.createImage(px, image.getWidth(), image.getHeight());
  }

  private static int clamp(float v) {
    return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
  }

  private final Map<LayoutEngine.Span, String> tabLeaderPaint =
      new LinkedHashMap<>(32, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<LayoutEngine.Span, String> entry) {
          return size() > 128;
        }
      };

  private String paintText(LayoutEngine.Span span, Font font, float scale) {
    if (!"\t".equals(span.text) || span.displayText.isEmpty()) return span.displayText;
    var cached = tabLeaderPaint.get(span);
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
    var text = new String(chars, 0, low);
    tabLeaderPaint.put(span, text);
    return text;
  }

  private final LayoutEngine.Metrics fontMetrics =
      new LayoutEngine.Metrics() {
        public float width(String text, TextStyle style) {
          var f = measureFont(style);
          if (f == null) return font(style, 1).stringWidth(text) / pointsToPixels();
          return f.stringWidth(text) / (MEASURE_SCALE * pointsToPixels());
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
  private int across = 1, rowsAcross;
  private int[] rowTops;
  private LayoutEngine.Result rowsFor;
  private float rowsScale;

  private static Map<TextStyle, Font> fontCache() {
    return new LinkedHashMap<>(64, .75f, true) {
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
    var result = layout();
    var selection = session.selection();
    if (navigationLine != null && navigationLayout == result && navigationSelection == selection)
      return navigationLine;
    LayoutEngine.Line found = null;
    for (LayoutEngine.Line line : linesOf(selection.block))
      if (editingBlock(line) == selection.block
          && selection.caret + noteShift(line) >= line.start
          && selection.caret + noteShift(line) <= line.end) found = line;
    return found;
  }

  private LayoutEngine.Result linesLayout;
  private Map<Integer, java.util.List<LayoutEngine.Line>> linesByBlock;

  /**
   * The lines that can belong to editing block {@code block}, in layout order. Outside a cell
   * that is the block's own body lines, indexed once per layout; the caret lookups ask several
   * times per edit and a full scan of a long document per lookup dominated typing latency.
   */
  private java.util.List<LayoutEngine.Line> linesOf(int block) {
    var result = layout();
    if (session.noteStory() != null) {
      var all = new ArrayList<LayoutEngine.Line>();
      for (LayoutEngine.Page page : result.pages) all.addAll(page.allLines());
      return all;
    }
    if (session.inCell()) {
      var all = new ArrayList<LayoutEngine.Line>();
      for (LayoutEngine.Page page : result.pages)
        all.addAll(session.enclosingNote() != null ? page.allLines() : page.lines);
      return all;
    }
    if (linesLayout != result) {
      linesByBlock = new HashMap<>();
      for (LayoutEngine.Page page : result.pages)
        for (LayoutEngine.Line line : page.lines)
          if (line.row < 0) {
            var list = linesByBlock.get(line.block);
            if (list == null) linesByBlock.put(line.block, list = new ArrayList<>());
            list.add(line);
          }
      linesLayout = result;
    }
    var lines = linesByBlock.get(block);
    return lines == null ? java.util.Collections.emptyList() : lines;
  }

  private LayoutEngine.Line verticalLine(LayoutEngine.Line current, boolean down, boolean page) {
    var lines = new ArrayList<LayoutEngine.Line>();
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
    var best = current;
    float distance = Float.MAX_VALUE;
    for (int i = index; i >= 0 && i < lines.size(); i += down ? 1 : -1) {
      var line = lines.get(i);
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
    var d = session.document();
    for (int i = 0; i < d.blocks.size(); i++) {
      var block = d.blocks.get(i);
      if (block instanceof Paragraph && block.id().equals(id)) {
        session.leaveCell();
        session.select(i, 0, 0);
        return true;
      }
      if (block instanceof Table table) {
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
    var nodes = new ArrayList<AccessibilityNode>();
    var seen = new HashSet<String>();
    for (LayoutEngine.Page page : layout().pages)
      for (LayoutEngine.Line line : page.lines) {
        if (!textLine(line)) continue;
        int top = pageY(line.page) + Math.round(line.y * scale()) - getScrollY(),
            height = Math.round(line.height * scale());
        if (top + height < 0 || top > getHeight() || !seen.add(line.paragraphId)) continue;
        var p = lineParagraph(line);
        final var id = p.id;
        var node =
            new AccessibilityNode(id)
                .setRole(AccessibilityRole.TEXT_FIELD)
                .setLabel(
                    line.row >= 0
                        ? "Table row " + (line.row + 1) + ", column " + (line.column + 1)
                        : headingLevel(p) > 0
                            ? "Heading " + headingLevel(p)
                            : "Paragraph " + (line.block + 1))
                .setHeadingLevel(line.row < 0 ? headingLevel(p) : -1)
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
          var format = ((Table) session.document().blocks.get(line.block)).format;
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
        var value = new StringBuilder();
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
        int top = pageY(pic.page()) + Math.round(pic.y() * scale()) - getScrollY(),
            height = Math.round(pic.height() * scale());
        if (top + height < 0 || top > getHeight()) continue;
        var node =
            new AccessibilityNode(pic.picture().id())
                .setRole(AccessibilityRole.IMAGE)
                .setLabel(pic.picture().alt.isEmpty() ? "Picture" : pic.picture().alt)
                .setBounds(
                    new Rectangle(
                        pageX(pic.page()) + Math.round(pic.x() * scale()),
                        top,
                        Math.round(pic.width() * scale()),
                        height));
        node.addAction(
            new AccessibilityAction(
                AccessibilityAction.ACTIVATE,
                "Edit picture",
                (component, argument) -> {
                  stopInput();
                  if (pictureEditor != null && selectObject(pic)) pictureEditor.edit(pic.block());
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

  /** The drag's cell path minus its last triple: the story holding the dragged table. */
  private int[] dragPrefix = new int[0];

  /** Records the pressed cell (innermost table level) for cell-range dragging. */
  private void dragFrom(LayoutEngine.Line line) {
    int[] path = linePath(line);
    int n = path.length;
    dragTable = n == 0 ? -1 : path[n - 3];
    dragRow = n == 0 ? -1 : path[n - 2];
    dragColumn = n == 0 ? -1 : path[n - 1];
    dragPrefix = n == 0 ? path : java.util.Arrays.copyOf(path, n - 3);
    dragPath = path;
  }

  private MixedRange acrossFor;
  private Document acrossDocument;
  private Map<String, int[]> acrossLines;

  /** The selected part {from, to} of a line inside a cross-story selection, or null. */
  private int[] acrossRange(LayoutEngine.Line line) {
    var range = session.mixedSelection();
    var body = session.document();
    if (range != acrossFor || body != acrossDocument) {
      acrossFor = range;
      acrossDocument = body;
      acrossLines = new HashMap<>();
      for (MixedRange.Covered c : range.covered(body))
        acrossLines.put(java.util.Arrays.toString(c.path) + c.paragraph.id, new int[] {c.from, c.to});
    }
    return acrossLines.get(java.util.Arrays.toString(linePath(line)) + line.paragraphId);
  }

  /** The pressed line's story (Session.cellPath) where a drag across stories is anchored. */
  private int[] dragPath;

  /** The line's paragraph index in its story (body or cell), or -1. */
  private int storyBlock(LayoutEngine.Line line) {
    if (session.noteStory() != null) return -1;
    try {
      var story = session.storyAt(linePath(line));
      for (int i = 0; i < story.size(); i++)
        if (story.get(i) instanceof Paragraph p && p.id.equals(line.paragraphId)) return i;
    } catch (RuntimeException e) {
      // a line of another story (header, note) has no body path
    }
    return -1;
  }

  /**
   * Extends a drag into another story (from body text into a table, or out of a cell), as
   * Wordcraft's selection runs across containers in document order.
   */
  private boolean dragAcross(LayoutEngine.Line line, int x) {
    if (dragPath == null || dragBlock < 0 || session.noteStory() != null) return false;
    int[] path = linePath(line);
    boolean other = !java.util.Arrays.equals(path, dragPath);
    if (!other && session.mixedSelection() == null) return false;
    int block = storyBlock(line);
    if (block < 0) return false;
    session.selectAcross(dragPath, dragBlock, dragAnchor, path, block, offsetAt(line, x));
    return true;
  }

  /**
   * Moves the caret (or with Shift the focus) to the neighbouring paragraph in document order
   * across a story boundary: into, out of or past a table.
   */
  private boolean crossStory(boolean forward, boolean extend) {
    if (session.noteStory() != null || session.cellSelection() != null) return false;
    var x = session.selection();
    int[] here = session.cellPath();
    var to = MixedRange.adjacent(session.document(), here, x.block, forward);
    if (to == null) return false;
    var across = session.mixedSelection();
    int offset = forward ? 0 : ((Paragraph) session.storyAt(to.path).get(to.block)).text.length();
    if (!extend) {
      session.enterCellPath(to.path);
      session.select(to.block, offset, offset);
    } else if (across != null)
      session.selectAcross(
          across.anchor.path, across.anchor.block, across.anchor.offset, to.path, to.block, offset);
    else session.selectAcross(here, x.anchorBlock, x.anchor, to.path, to.block, offset);
    return true;
  }

  /** The line's cell in the dragged table as {row, column}, or null outside it. */
  private int[] dragCell(LayoutEngine.Line line) {
    int[] path = linePath(line);
    int n = path.length;
    if (n != dragPrefix.length + 3 || path[n - 3] != dragTable) return null;
    for (int i = 0; i < dragPrefix.length; i++) if (path[i] != dragPrefix[i]) return null;
    return new int[] {path[n - 2], path[n - 1]};
  }
  private boolean reflow = OfficeTheme.mobile(), touchMoved, touchSelecting, showMarks;
  private int touchX, touchY, dragHandle;
  private float pinchStart;
  private int layoutViewport = -1;

  /** Watermark and page borders; page color is filled by the caller. */
  private void paintDecor(Graphics g, LayoutEngine.Page page, int x, int y, float scale) {
    var d = page.decor;
    if (!d.watermark.isEmpty()) {
      var f = font(TextStyle.DEFAULT.with("size", "54"), zoom);
      int w = f.stringWidth(d.watermark), h = f.getHeight();
      int cx = x + Math.round(page.spec.width * scale / 2),
          cy = y + Math.round(page.spec.height * scale / 2);
      int alpha = g.getAlpha();
      g.setAlpha(96);
      g.setColor(d.watermarkColor);
      g.setFont(f);
      g.rotate((float) (-Math.PI / 4), cx, cy);
      g.drawString(d.watermark, cx - w / 2, cy - h / 2);
      g.rotate((float) (Math.PI / 4), cx, cy);
      g.setAlpha(alpha);
    }
    if (!d.borders.isEmpty()) {
      var b = d.borders;
      float pw = page.spec.width, ph = page.spec.height;
      paintFrame(
          g,
          new LayoutEngine.Frame(
              b.left.space, b.top.space, pw - b.left.space - b.right.space,
              ph - b.bottom.space, b, -1, true, true),
          x, y, scale);
    }
  }

  private static void paintFrame(Graphics g, LayoutEngine.Frame f, int px, int py, float scale) {
    int x = px + Math.round(f.x * scale), y = py + Math.round(f.top * scale);
    int w = Math.round(f.width * scale), h = Math.round((f.bottom - f.top) * scale);
    if (f.shading >= 0) {
      g.setColor(f.shading);
      g.fillRect(x, y, w, h);
    }
    var b = f.borders;
    if (f.first) side(g, b.top, x, y, w, true, scale);
    if (f.last) side(g, b.bottom, x, y + h - stroke(b.bottom, scale), w, true, scale);
    side(g, b.left, x, y, h, false, scale);
    side(g, b.right, x + w - stroke(b.right, scale), y, h, false, scale);
  }

  private static int stroke(ParagraphBorders.Side side, float scale) {
    return Math.max(1, Math.round(side.width * scale));
  }

  /** Solid, double (two thirds-width rules), dotted and dashed sides. */
  private static void side(
      Graphics g, ParagraphBorders.Side side, int x, int y, int length, boolean horizontal, float scale) {
    if (!side.present()) return;
    g.setColor(side.color);
    int t = stroke(side, scale);
    if ("double".equals(side.style) && t >= 3) {
      int line = Math.max(1, t / 3);
      bar(g, x, y, length, line, horizontal);
      bar(g, horizontal ? x : x + t - line, horizontal ? y + t - line : y, length, line, horizontal);
      return;
    }
    int on = "dotted".equals(side.style) ? t : "dashed".equals(side.style) ? 4 * t : length;
    int off = on == length ? 0 : "dotted".equals(side.style) ? t : 2 * t;
    for (int at = 0; at < length; at += on + off) {
      int run = Math.min(on, length - at);
      if (horizontal) g.fillRect(x + at, y, run, t);
      else g.fillRect(x, y + at, t, run);
    }
  }

  private static void bar(Graphics g, int x, int y, int length, int t, boolean horizontal) {
    if (horizontal) g.fillRect(x, y, length, t);
    else g.fillRect(x, y, t, length);
  }

  /** Decoded group pictures per drawing (drawings are immutable), in ops.images order. */
  private final java.util.Map<Drawing, Image[]> groupImages = new java.util.IdentityHashMap<>();

  private Image groupImage(Drawing d, DrawingOps.Ops ops, int index) {
    Image[] list = groupImages.get(d);
    if (list == null || list.length != ops.images.size()) {
      if (groupImages.size() > 32) groupImages.clear();
      list = new Image[ops.images.size()];
      groupImages.put(d, list);
    }
    if (list[index] == null) {
      byte[] data = ops.images.get(index).data();
      try {
        list[index] = Image.createImage(data, 0, data.length);
      } catch (RuntimeException bad) {
        return null;
      }
    }
    return list[index];
  }

  /** Crop maps the visible source fraction onto the frame; rotation turns about its center. */
  private static void drawPicture(
      Graphics g, Image image, Picture.Look k, int px, int py, int pw, int ph, float scale) {
    boolean rotate = (k.rotation != 0 || k.flipH || k.flipV) && g.isAffineSupported();
    float cx = px + pw / 2f, cy = py + ph / 2f;
    if (rotate) {
      g.translate((int) cx, (int) cy);
      if (k.rotation != 0) g.rotateRadians((float) Math.toRadians(k.rotation), 0, 0);
      if (k.flipH || k.flipV) g.scale(k.flipH ? -1 : 1, k.flipV ? -1 : 1);
      g.translate(-(int) cx, -(int) cy);
    }
    int cx0 = g.getClipX(), cy0 = g.getClipY(), cw0 = g.getClipWidth(), ch0 = g.getClipHeight();
    if (k.cropped()) {
      float fw = pw / (1 - k.cropLeft - k.cropRight), fh = ph / (1 - k.cropTop - k.cropBottom);
      g.clipRect(px, py, pw, ph);
      g.drawImage(
          image,
          px - Math.round(fw * k.cropLeft),
          py - Math.round(fh * k.cropTop),
          Math.round(fw),
          Math.round(fh));
      g.setClip(cx0, cy0, cw0, ch0);
    } else g.drawImage(image, px, py, pw, ph);
    if (k.borderColor >= 0 && k.borderWidth > 0) {
      g.setColor(k.borderColor);
      int t = Math.max(1, Math.round(k.borderWidth * scale));
      g.fillRect(px, py, pw, t);
      g.fillRect(px, py + ph - t, pw, t);
      g.fillRect(px, py, t, ph);
      g.fillRect(px + pw - t, py, t, ph);
    }
    if (rotate) g.resetAffine();
  }

  /** Word's Show/Hide formatting marks: pilcrows, space dots, tab arrows and hidden text. */
  public boolean isShowMarks() {
    return showMarks;
  }

  private boolean darkPage, gridlines, draft, viewOnly;
  private String markup = "all";

  /**
   * Review display: "all" shows revisions inline; "simple" and "none" show the document as if
   * every change were accepted, "original" as if every change were rejected. The last three are
   * previews, so editing is disabled while they are shown.
   */
  public void setMarkup(String mode) {
    if (!"all".equals(mode) && !"simple".equals(mode) && !"none".equals(mode) && !"original".equals(mode))
      throw new IllegalArgumentException("Markup must be all, simple, none or original");
    markup = mode;
    laidOut = null;
    repaint();
  }

  public String getMarkup() {
    return markup;
  }

  private String numeralMode;
  private Map<Paragraph, Paragraph> numeralViews = new HashMap<>();

  private Document shown() {
    var d = session.document();
    if (!"all".equals(markup)) {
      boolean accept = !"original".equals(markup);
      for (Revisions.Change c : Revisions.list(d)) d = Revisions.resolve(d, c.revision.id, accept);
    }
    d = OfficeCommands.shown(d);
    // Only a non-default numeral shape rewrites text: copying every paragraph defeats the
    // layout engine's per-paragraph measurement cache (keyed by identity) on each edit.
    String numeral = AppOptions.get("numeral");
    if (numeral != null && !numeral.isEmpty() && !"arabic".equals(numeral) && !"system".equals(numeral)) {
      // Each shaped copy is memoized per source paragraph (identity) so unchanged paragraphs
      // keep the same object across edits and stay in the layout cache; a mode change drops it.
      if (!numeral.equals(numeralMode)) {
        numeralMode = numeral;
        numeralViews = new HashMap<>();
      }
      var views = new HashMap<Paragraph, Paragraph>();
      var blocks = new Sequence<Block>();
      boolean changed = false;
      for (Block b : d.blocks) {
        if (b instanceof Paragraph p) {
          var view = numeralViews.get(p);
          if (view == null) {
            view = p;
            final boolean rtl = p.format.rtl;
            var text = p.text.toString();
            boolean digits = false;
            for (int i = 0; i < text.length() && !digits; i++) digits = text.charAt(i) >= '0' && text.charAt(i) <= '9';
            if (digits) {
              var mapped = p.text.mapText(t -> AppOptions.numerals(t, rtl));
              if (!mapped.toString().equals(text)) view = p.text(mapped);
            }
          }
          views.put(p, view);
          changed |= view != p;
          b = view;
        }
        blocks = blocks.append(b);
      }
      numeralViews = views;
      if (changed) d = d.blocks(blocks, d.nextId);
    }
    return outline == null ? d : outline.apply(d);
  }

  private boolean exporting;

  /** Renders one page at widthPx pixels wide, without caret or selection (Export as PNG). */
  public Image renderPage(int page, int widthPx) {
    var result = layout();
    if (page < 0 || page >= result.pages.size()) throw new IllegalArgumentException("No page " + (page + 1));
    float oldZoom = zoom;
    boolean oldDark = darkPage;
    try {
      exporting = true;
      darkPage = false;
      var spec = result.pages.get(page);
      zoom = Math.max(.1f, Math.min(10f, widthPx / (spec.spec.width * pointsToPixels())));
      paintFonts.clear();
      float scale = scale();
      int pw = Math.round(spec.spec.width * scale), ph = Math.round(spec.spec.height * scale);
      Image out = Image.createImage(pw, ph, 0xffffffff);
      var g = out.getGraphics();
      g.translate(-(getX() + pageX(page) + getScrollX()), -(getY() + pageY(page)));
      g.setClip(getX() + pageX(page) + getScrollX(), getY() + pageY(page), pw, ph);
      paint(g);
      return out;
    } finally {
      exporting = false;
      darkPage = oldDark;
      zoom = oldZoom;
      paintFonts.clear();
      tabLeaderPaint.clear();
      repaint();
    }
  }

  /** An application option that changes only the display (Hide Ink, numerals) changed. */
  public void displayChanged() {
    laidOut = null;
    repaint();
  }

  /** Outline view state, or null outside Outline view. */
  private OutlineView outline;

  public OutlineView getOutline() {
    return outline;
  }

  public void setOutline(OutlineView value) {
    outline = value;
    laidOut = null;
    repaint();
  }

  /** Word's "Switch Modes": a dark page with light text; colors in the document are unchanged. */
  public void setDarkPage(boolean value) {
    darkPage = value;
    repaint();
  }

  public boolean isDarkPage() {
    return darkPage;
  }

  public void setGridlines(boolean value) {
    gridlines = value;
    repaint();
  }

  /** Table View Gridlines (on by default, like Word): screen-only cell edge guides. */
  private boolean tableGridlines = true;

  public void setTableGridlines(boolean value) {
    tableGridlines = value;
    repaint();
  }

  public boolean isTableGridlines() {
    return tableGridlines;
  }

  public boolean isGridlines() {
    return gridlines;
  }

  /** Draft view: no headers, footers, watermark or page color. */
  public void setDraft(boolean value) {
    draft = value;
    repaint();
  }

  public boolean isDraft() {
    return draft;
  }

  /** Read mode: navigation and selection only. */
  public void setViewOnly(boolean value) {
    viewOnly = value;
  }

  public boolean isViewOnly() {
    return viewOnly;
  }

  /** Text color as painted: dark ink turns light on a dark page. */
  private int ink(int rgb) {
    if (!darkPage) return rgb;
    int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
    if ((r * 299 + g * 587 + b * 114) / 1000 >= 110) return rgb;
    // Invert HSL lightness only, so dark blue becomes light blue rather than orange.
    int shift = 255 - Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    return (r + shift) << 16 | (g + shift) << 8 | (b + shift);
  }

  /**
   * Hidden text is drawn (with its dotted underline) with formatting marks on or with the
   * separate Display option "Hidden text" (Wordcraft's prefs.marks.hidden).
   */
  public boolean isShowHidden() {
    return showMarks || AppOptions.flag("showHiddenText");
  }

  private void paintHiddenUnderline(Graphics g, LayoutEngine.Line line, int lx, int ly, float scale) {
    g.setColor(OfficeTheme.chrome(0x5b7fa6));
    for (LayoutEngine.Span span : line.spans) {
      if (!span.style.characters.hidden) continue;
      int sx = lx + Math.round(span.x * scale), sy = ly + Math.round(span.top * scale);
      var f = font(span.glyphStyle, zoom);
      for (int x = sx; x < sx + Math.round(span.width * scale); x += 3)
        g.fillRect(x, sy + f.getHeight() - 1, 1, 1);
    }
  }

  public void setShowMarks(boolean value) {
    showMarks = value;
    repaint();
  }

  private void paintMarks(Graphics g, LayoutEngine.Line line, int lx, int ly, float scale) {
    g.setFont(font(TextStyle.DEFAULT, zoom));
    g.setColor(OfficeTheme.chrome(0x5b7fa6));
    int baseline = ly + Math.round(line.height * scale) - g.getFont().getHeight();
    for (LayoutEngine.Span span : line.spans) {
      int sx = lx + Math.round(span.x * scale), sy = ly + Math.round(span.top * scale);
      if (span.style.characters.hidden) {
        var f = font(span.glyphStyle, zoom);
        for (int x = sx; x < sx + Math.round(span.width * scale); x += 3)
          g.fillRect(x, sy + f.getHeight() - 1, 1, 1);
      }
      for (int i = 0; i < span.text.length(); i++) {
        char ch = span.text.charAt(i);
        if (ch != ' ' && ch != '\t' && ch != '\u00a0') continue;
        int a = lx + Math.round(offsetX(line, span.start + i) * scale),
            b = lx + Math.round(offsetX(line, span.start + i + 1) * scale);
        String mark = ch == '\t' ? "\u2192" : ch == ' ' ? "\u00b7" : "\u00b0";
        g.drawString(mark, (a + b - g.getFont().stringWidth(mark)) / 2, baseline);
      }
    }
    if (textLine(line)) {
      var paragraph = lineParagraph(line);
      if (paragraph.breakRevision == null && line.end == paragraph.text.length())
        g.drawString(
            "\u00b6",
            lx + Math.round(line.width * scale) + 1,
            baseline);
    }
  }

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
    getAllStyles().setBgColor(OfficeTheme.CANVAS);
    getAllStyles().setBgTransparency(255);
    engine = new LayoutEngine(fontMetrics);
    commands.setLayoutEngine(engine);
  }

  /**
   * Fonts report whole-pixel widths. Layout measures at MEASURE_SCALE times the size so a line
   * built from separately cached word widths lands where the run is drawn, instead of drifting
   * up to a pixel per word (which shifted runs after long plain chunks).
   */
  private static final int MEASURE_SCALE = 4;

  private final Map<TextStyle, Font> measureFonts = fontCache();

  /** The scaled measuring font, or null when the style has no scalable document font. */
  private Font measureFont(TextStyle s) {
    if (measureFonts.containsKey(s)) return measureFonts.get(s);
    Font f;
    try {
      f = DocumentFonts.font(s, Math.max(1, s.renderedSize() * pointsToPixels() * MEASURE_SCALE));
    } catch (RuntimeException e) {
      f = null;
    }
    measureFonts.put(s, f);
    return f;
  }

  private Font font(TextStyle s, float zoom) {
    Map<TextStyle, Font> fonts = zoom == 1 ? layoutFonts : paintFonts;
    var f = fonts.get(s);
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
    across = 1;
    if (reflow) {
      laidOut = null;
      revalidateView();
    } else setZoom((getWidth() - 48) / (layout().maxWidth * pointsToPixels()));
  }

  private boolean layoutHidden;

  public LayoutEngine.Result layout() {
    if (laidOut != session.document() || reflow && layoutViewport != getWidth() || layoutHidden != isShowHidden()) {
      layoutHidden = isShowHidden();
      engine.setShowHidden(layoutHidden);
      layout =
          reflow
              ? engine.reflow(
                  shown(),
                  session.revision(),
                  Math.max(120, (getWidth() - OfficeTheme.px(16)) / scale()))
              : engine.layout(shown(), session.revision());
      layoutViewport = getWidth();
      laidOut = session.document();
    }
    return layout;
  }

  /** Print/export always uses document paper geometry, independent of phone reflow and zoom. */
  public LayoutEngine.Result printLayout() {
    Document printed = OfficeCommands.printable(session.document());
    // Print what Print Markup selects, never a display-only view (outline, Hide Ink, reflow).
    if (!reflow && printed == session.document() && shown() == printed && !isShowHidden()) return layout();
    // Hidden text takes no room on paper; the screen layout is redone with it shown.
    engine.setShowHidden(false);
    laidOut = null;
    return engine.layout(printed, session.revision());
  }

  /** Paginate generated reference content with the same fonts as this editor. */
  public LayoutEngine.Result printLayout(Document document) {
    if (isShowHidden()) {
      engine.setShowHidden(false);
      laidOut = null;
    }
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

  /** Top of the caret's page relative to this component, after scrolling (vertical ruler). */
  public int pageTop() {
    return pageY(currentPage() - 1) - getScrollY();
  }

  public int currentPage() {
    var line = caretLine();
    return line == null ? pageAt(getScrollY()) + 1 : line.page + 1;
  }

  private float scale() {
    return pointsToPixels() * zoom;
  }

  private int pageX() {
    return pageX(currentPage() - 1);
  }

  private int pageX(int page) {
    if (across > 1 && !reflow) {
      java.util.List<LayoutEngine.Page> pages = layout().pages;
      int first = page - page % across, last = Math.min(pages.size(), first + across);
      int row = 0, before = 0;
      for (int i = first; i < last; i++) {
        int w = Math.round(pages.get(i).spec.width * scale());
        if (i < page) before += w + 24;
        row += w + (i > first ? 24 : 0);
      }
      return Math.max(24, (getWidth() - row) / 2) + before - getScrollX();
    }
    // With balloons shown the page and its markup area are centred together (Wordcraft).
    int markup = balloonsShown() ? Math.round(Balloons.MARKUP_AREA * scale()) : 0;
    return Math.max(
            reflow ? OfficeTheme.px(8) : 24,
            (getWidth() - Math.round(layout().pages.get(page).spec.width * scale()) - markup) / 2)
        - getScrollX();
  }

  /**
   * Wordcraft's balloons_shown: markup shown in the page view (one page across, not reflowed or
   * draft), comments shown, the balloon mode not inline, and a balloon to show.
   */
  boolean balloonsShown() {
    if (reflow || draft || across > 1 || !"all".equals(markup) || "inline".equals(Balloons.mode())
        || !AppOptions.flag("showComments")) return false;
    for (java.util.List<Balloons.Card> cards : balloons()) if (!cards.isEmpty()) return true;
    return false;
  }

  private LayoutEngine.Result balloonSource;
  private String balloonMode;
  private java.util.List<java.util.List<Balloons.Card>> balloonCards;

  /** The balloon cards of the current layout, measured with the editor's fonts. */
  private java.util.List<java.util.List<Balloons.Card>> balloons() {
    var result = layout();
    if (balloonSource != result || !Balloons.mode().equals(balloonMode)) {
      balloonSource = result;
      balloonMode = Balloons.mode();
      final float zoom = this.zoom;
      balloonCards =
          Balloons.layout(
              session.document(),
              result,
              new LayoutEngine.Metrics() {
                public float width(String text, TextStyle style) {
                  return font(style, zoom).stringWidth(text) / scale();
                }

                public float height(TextStyle style) {
                  return font(style, zoom).getHeight() / scale();
                }
              },
              Balloons.mode());
    }
    return balloonCards;
  }

  /** Draws a page's balloons in the markup area right of the page at (x, y). */
  private void paintBalloons(Graphics g, int p, int x, int y, float scale) {
    var all = balloons();
    if (p >= all.size()) return;
    for (Balloons.Card card : all.get(p)) {
      int ax = x + Math.round(card.anchorX * scale), ay = y + Math.round(card.anchorY * scale);
      int edge = x + Math.round(balloonSource.pages.get(p).spec.width * scale);
      int cx = x + Math.round(card.x * scale), cy = y + Math.round(card.top * scale);
      int cw = Math.round(card.width * scale), ch = Math.round(card.height * scale);
      g.setColor(card.color);
      for (int dx = ax; dx < edge; dx += 6) g.drawLine(dx, ay, Math.min(edge, dx + 3), ay);
      g.drawLine(edge, ay, cx, cy + Math.round(8 * scale));
      g.setColor(darkPage ? 0x2b2b2b : 0xffffff);
      g.fillRect(cx, cy, cw, ch);
      g.setColor(card.color);
      g.drawRect(cx, cy, cw, ch);
      g.fillRect(cx, cy, Math.max(1, Math.round(Balloons.STRIPE * scale)), ch);
      for (Balloons.Item it : card.items) {
        int ix = cx + Math.round(it.x * scale), iy = cy + Math.round(it.y * scale);
        if (it.text == null) {
          g.setColor(it.color);
          g.drawLine(ix, iy, ix + Math.round(it.width * scale), iy);
          continue;
        }
        var style = TextStyle.DEFAULT.with("size", String.valueOf(it.size));
        if (it.bold) style = style.with("bold", "true");
        g.setFont(font(style, zoom));
        g.setColor(it.bold ? it.color : ink(it.color));
        g.drawString(it.text, ix, iy);
      }
    }
  }

  private int pageY(int page) {
    if (across > 1 && !reflow) {
      var tops = rowTops();
      int n = layout().pages.size();
      return page >= n ? tops[tops.length - 1] : tops[page / across];
    }
    return 24 + Math.round(layout().heightBefore(page) * scale()) + page * 24;
  }

  /** Top of each row of pages when several pages sit side by side; the last entry is the end. */
  private int[] rowTops() {
    var r = layout();
    float sc = scale();
    if (rowTops == null || rowsFor != r || rowsScale != sc || rowsAcross != across) {
      int n = r.pages.size(), rows = (n + across - 1) / across, y = 24;
      int[] tops = new int[rows + 1];
      for (int row = 0; row < rows; row++) {
        tops[row] = y;
        float h = 0;
        for (int i = row * across; i < Math.min(n, (row + 1) * across); i++)
          h = Math.max(h, r.pages.get(i).spec.height);
        y += Math.round(h * sc) + 24;
      }
      tops[rows] = y;
      rowTops = tops;
      rowsFor = r;
      rowsScale = sc;
      rowsAcross = across;
    }
    return rowTops;
  }

  /** Pages per row in print layout (View > One Page / Multiple Pages). */
  public int getPagesAcross() {
    return across;
  }

  public void setPagesAcross(int value) {
    across = Math.max(1, Math.min(6, value));
    scrollExtent = -1;
    revalidateView();
  }

  /** Zoom so a whole page (or a row of pages) fits the viewport. */
  public void fitPages(int count) {
    if (reflow) setReflow(false);
    setPagesAcross(count);
    var page = layout().pages.get(Math.max(0, currentPage() - 1));
    float ptp = pointsToPixels();
    float byWidth = (getWidth() - 24f * (count + 1)) / (page.spec.width * count * ptp);
    float byHeight = (getHeight() - 48f) / (page.spec.height * ptp);
    setZoom(Math.min(byWidth, byHeight));
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
    // Container.isScrollableX adds the horizontal padding to this width, so flooring at the
    // full width would leave a full-length horizontal scrollbar on a page that fits.
    return new Dimension(
        Math.max(
            getWidth() - getStyle().getHorizontalPadding(),
            Math.round(layout().maxWidth * scale()) * (reflow ? 1 : across)
                + (reflow ? OfficeTheme.px(16) : 24 * (across + 1))),
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
    syncInput();
    repaint();
  }

  private boolean wakePending;

  @Override
  public void repaint() {
    super.repaint();
    paintNextCycle();
  }

  /**
   * The EDT runs serial calls after it paints and then sleeps a frame interval, so an edit
   * delivered through callSerially (native text input, undo from a menu, a file load) waited
   * up to 15 ms before anything showed it. Skip exactly one idle sleep: the clearing call is
   * queued behind this one, so it runs after the next paint and the loop never spins.
   */
  private void paintNextCycle() {
    if (wakePending || !Display.isInitialized()) return;
    wakePending = true;
    final Display d = Display.getInstance();
    d.setNoSleep(true);
    d.callSerially(() -> {
      wakePending = false;
      d.setNoSleep(false);
    });
  }

  private Object[] sentInput;

  /**
   * The state carries the whole document text and its geometry measures every visible
   * grapheme. An edit asks several times (the editor, the host's change handler, the form
   * relayout), so skip the copies when nothing it reports has moved.
   */
  private void syncInput() {
    if (input == null) return;
    var x = session.selection();
    Object[] key = {
      input, session.editingDocument(), x, Integer.valueOf(composingStart), Integer.valueOf(composingEnd),
      Integer.valueOf(getScrollX()), Integer.valueOf(getScrollY()), Float.valueOf(zoom),
      Integer.valueOf(getAbsoluteX()), Integer.valueOf(getAbsoluteY()), Integer.valueOf(getWidth()),
      Integer.valueOf(getHeight()), layout()
    };
    if (Arrays.equals(key, sentInput)) return;
    sentInput = key;
    host.updateTextInputState(input, getEditingState());
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
    var cells = new ArrayList<Object>();
    var selection = session.cellSelection();
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
    var lines = new ArrayList<Object>();
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

  private boolean inputAfterPaint;

  /**
   * Focuses the editor and connects native text input once the document has painted.
   * Connecting it binds the input client to the native window, which can wait on the window
   * server; at launch that would otherwise delay the first frame.
   */
  public void startInputAfterPaint() {
    inputAfterPaint = true;
    repaint();
  }

  @Override
  public void paint(Graphics g) {
    // Component.internalPaintImpl already translates by the scroll offsets.
    // Paint document coordinates here; hit testing/native geometry use screen coordinates.
    super.paint(g);
    var result = layout();
    var root = session.document();
    if (commentSource != root) {
      commentSource = root;
      commentAnchors =
          root.comments.isEmpty()
              ? java.util.Collections.<String, ReviewComment.Anchor>emptyMap()
              : ReviewComment.index(root);
    }
    float scale = scale();
    int baseY = getY();
    var selection = session.selection();
    var caretLine = caretLine();
    for (int p = 0; p < result.pages.size(); p++) {
      var page = result.pages.get(p);
      int x = getX() + pageX(p) + getScrollX(),
          y = baseY + pageY(p),
          pw = Math.round(page.spec.width * scale),
          ph = Math.round(page.spec.height * scale);
      if (y + ph < g.getClipY() || y > g.getClipY() + g.getClipHeight()) continue;
      // Flat page shadow: 2pt down, 1.5pt larger, black at 13%.
      int grow = Math.max(1, Math.round(1.5f * scale)), drop = Math.round(2 * scale);
      int alpha = g.getAlpha();
      g.setAlpha(0x22);
      g.setColor(0);
      g.fillRect(x - grow, y + drop - grow, pw + 2 * grow, ph + 2 * grow);
      g.setAlpha(alpha);
      g.setColor(
          darkPage
              ? 0x1f1f1f
              : draft || page.decor.pageColor < 0 ? 0xffffff : page.decor.pageColor);
      g.fillRect(x, y, pw, ph);
      if (!draft) paintDecor(g, page, x, y, scale);
      if (balloonsShown()) paintBalloons(g, p, x, y, scale);
      if (gridlines) {
        g.setColor(darkPage ? 0x3a4654 : 0xd8e4f0);
        int left = x + Math.round(page.spec.left * scale),
            right = x + Math.round((page.spec.width - page.spec.right) * scale),
            top = y + Math.round(page.spec.top * scale),
            bottom = y + Math.round((page.spec.height - page.spec.bottom) * scale);
        for (float gy = page.spec.top; gy <= page.spec.height - page.spec.bottom; gy += 18)
          g.drawLine(left, y + Math.round(gy * scale), right, y + Math.round(gy * scale));
        for (float gx = page.spec.left; gx <= page.spec.width - page.spec.right; gx += 18)
          g.drawLine(x + Math.round(gx * scale), top, x + Math.round(gx * scale), bottom);
      }
      g.setColor(ink(0x242424));
      for (float[] rule : page.columnRules)
        g.drawLine(
            x + Math.round(rule[0] * scale),
            y + Math.round(rule[1] * scale),
            x + Math.round(rule[0] * scale),
            y + Math.round(rule[2] * scale));
      for (LayoutEngine.PlacedPicture pic : LayoutEngine.byDepth(page.paintedPictures(), false)) {
        if (pic.picture().drawing != null) {
          paintDrawing(g, pic, x + Math.round(pic.x() * scale), y + Math.round(pic.y() * scale), scale);
          continue;
        }
        var image = picture(pic.picture());
        if (image == null) {
          g.setColor(0x9c2525);
          g.setFont(font(TextStyle.DEFAULT, zoom));
          g.drawRect(
              x + Math.round(pic.x() * scale),
              y + Math.round(pic.y() * scale),
              Math.round(pic.width() * scale),
              Math.round(pic.height() * scale));
          g.drawString(
              "Picture unavailable",
              x + Math.round(pic.x() * scale) + 4,
              y + Math.round(pic.y() * scale) + 4);
          continue;
        }
        drawPicture(
            g,
            image,
            pic.picture().look,
            x + Math.round(pic.x() * scale),
            y + Math.round(pic.y() * scale),
            Math.round(pic.width() * scale),
            Math.round(pic.height() * scale),
            scale);
      }
      for (LayoutEngine.Box box : page.paintedBoxes()) {
        int bx = x + Math.round(box.x * scale),
            by = y + Math.round(box.y * scale),
            bw = Math.round(box.width * scale),
            bh = Math.round(box.height * scale);
        var selectedCells = session.cellSelection();
        g.setColor(
            selectedCells != null && selectedBox(selectedCells, box)
                ? 0xc5e9e6
                : box.format.background);
        g.fillRect(bx, by, bw, bh);
        if (box.format.borderWidth > 0) {
          g.setColor(box.format.borderColor);
          int stroke = Math.max(1, Math.round(box.format.borderWidth * scale));
          if (box.format.side(TableFormat.Cell.TOP)) g.fillRect(bx, by, bw, stroke);
          if (box.format.side(TableFormat.Cell.BOTTOM)) g.fillRect(bx, by + bh - stroke, bw, stroke);
          if (box.format.side(TableFormat.Cell.LEFT)) g.fillRect(bx, by, stroke, bh);
          if (box.format.side(TableFormat.Cell.RIGHT)) g.fillRect(bx + bw - stroke, by, stroke, bh);
        }
        if (tableGridlines) {
          // View Gridlines: dotted guides on cell edges that have no printed border.
          g.setColor(0x7fa7d9);
          for (int i = 0; i < Math.max(bw, bh); i += 4) {
            if (i < bw && !box.format.side(TableFormat.Cell.TOP)) g.fillRect(bx + i, by, 1, 1);
            if (i < bw && !box.format.side(TableFormat.Cell.BOTTOM)) g.fillRect(bx + i, by + bh - 1, 1, 1);
            if (i < bh && !box.format.side(TableFormat.Cell.LEFT)) g.fillRect(bx, by + i, 1, 1);
            if (i < bh && !box.format.side(TableFormat.Cell.RIGHT)) g.fillRect(bx + bw - 1, by + i, 1, 1);
          }
        }
      }
      for (LayoutEngine.Frame frame : page.frames) paintFrame(g, frame, x, y, scale);
      if (!page.lineNumbers.isEmpty()) {
        var lf = font(TextStyle.DEFAULT.with("size", "9"), zoom);
        g.setFont(lf);
        g.setColor(ink(0x6b6b6b));
        // Wordcraft: numbers end lnNumType distance (18pt by default) left of the text.
        float gap = Float.parseFloat(page.decor.layout("lineDistance", "0"));
        int right = x + Math.round((page.spec.left - (gap > 0 ? gap : 18)) * scale);
        for (float[] n : page.lineNumbers) {
          String label = String.valueOf((int) n[1]);
          g.drawString(label, right - lf.stringWidth(label), y + Math.round(n[0] * scale));
        }
      }
      for (LayoutEngine.Line area : page.noteAreas()) {
        g.setColor(0x242424);
        int nx = x + Math.round(page.spec.left * scale), ny = y + Math.round((area.y - 6) * scale);
        g.drawLine(
            nx, ny, nx + Math.round(Math.min(144, page.spec.contentWidth() / 3) * scale), ny);
      }
      for (LayoutEngine.Line line : page.allLines()) {
        if (draft && line.block != -2 && (page.header.contains(line) || page.footer.contains(line)))
          continue;
        int clipX = g.getClipX(),
            clipY = g.getClipY(),
            clipW = g.getClipWidth(),
            clipH = g.getClipHeight();
        // Lines outside the repaint area are skipped, with a line's height of slack for ink
        // that overhangs it. Some ports (Windows DirectWrite) lay out every string they are
        // asked to draw even when it is clipped away.
        float[] bounds = line.pageBounds();
        int top = y + Math.round(bounds[1] * scale), extent = Math.round(bounds[3] * scale);
        if (top + 2 * extent < clipY || top - extent > clipY + clipH) continue;
        // Turned cell text (btLr/tbRl) is drawn in its frame, turned onto the page about the
        // frame origin as Wordcraft's display list does.
        com.codename1.ui.Transform unturned = null;
        if (line.turn != 0 && g.isTransformSupported()) {
          unturned = g.getTransform();
          var turned = unturned.copy();
          turned.translate(x + line.turnX * scale, y + line.turnY * scale);
          turned.rotate((float) (line.turn == 1 ? Math.PI / 2 : -Math.PI / 2), 0, 0);
          turned.translate(-x, -y);
          g.setTransform(turned);
        }
        if (line.clipped)
          g.clipRect(
              clipX,
              y + Math.round(line.y * scale),
              clipW,
              Math.max(1, Math.round(line.clipHeight * scale)));
        int lx = x + Math.round(line.x * scale), ly = y + Math.round(line.y * scale);
        if (!line.marker.isEmpty()) {
          g.setFont(font(TextStyle.DEFAULT, zoom));
          g.setColor(ink(0x242424));
          int mx = lx - Math.round(18 * scale);
          if (line.rtl) mx = x + Math.round(line.markerX(g.getFont().stringWidth(line.marker) / scale) * scale);
          g.drawString(line.marker, mx, ly + Math.round(line.markerOffset * scale));
        }
        for (ReviewComment comment : session.document().comments) {
          if (comment.resolved) continue;
          ReviewComment.Anchor first = commentAnchors.get(comment.startId),
              last = commentAnchors.get(comment.endId),
              current = commentAnchors.get(line.paragraphId);
          // A range runs through the body stream (body text and table cells in reading order)
          // or within one note story.
          if (first == null
              || current == null
              || !first.sameFlow(last)
              || !first.sameFlow(current)
              || order(current, first) < 0
              || order(current, last) > 0) continue;
          // A note's first displayed line starts with its label and a space.
          int shift =
              current.note != null && current.paragraph == 0 ? current.note.label.length() + 1 : 0;
          int a =
              current == first
                  ? Math.max(line.start, comment.start + shift)
                  : line.start;
          int b = current == last ? Math.min(line.end, comment.end + shift) : line.end;
          if (b > a) {
            g.setColor(0xffedaa);
            fillRange(g, line, a, b, lx, ly, scale);
          }
        }
        int selectedBlock = editingBlock(line), shown = selectedBlock < 0 ? 0 : noteShift(line);
        if (!exporting && session.cellSelection() == null
            && selectedBlock >= 0
            && selectedBlock >= selection.startBlock()
            && selectedBlock <= selection.endBlock()
            && (selectedBlock > selection.startBlock() || selection.start() + shown < line.end)
            && (selectedBlock < selection.endBlock() || selection.end() + shown > line.start)) {
          int
              a =
                  selectedBlock == selection.startBlock()
                      ? Math.max(selection.start() + shown, line.start)
                      : line.start,
              b =
                  selectedBlock == selection.endBlock()
                      ? Math.min(selection.end() + shown, line.end)
                      : line.end;
          g.setColor(0xc5e9e6);
          fillRange(g, line, a, b, lx, ly, scale);
        }
        int[] across = exporting || session.mixedSelection() == null ? null : acrossRange(line);
        if (across != null && across[1] > line.start && across[0] < line.end
            || across != null && across[0] == across[1] && line.start == line.end) {
          g.setColor(0xc5e9e6);
          fillRange(g, line, Math.max(across[0], line.start), Math.min(across[1], line.end), lx, ly, scale);
        }
        // Object lines (pictures, charts, equations) have no paragraph to proof.
        if (!exporting && textLine(line) && AppOptions.flag("checkSpelling")) {
          // Proofing marks: red wavy for spelling, blue for grammar.
          int base = ly + Math.round(line.height * scale) - 2;
          for (Proofing.Issue issue : proofIssues(lineParagraph(line))) {
            if ("grammar".equals(issue.kind()) && !AppOptions.flag("checkGrammar")) continue;
            int a = Math.max(issue.start(), line.start), b = Math.min(issue.end(), line.end);
            if (a >= b) continue;
            int x0 = lx + Math.round(offsetX(line, a) * scale), x1 = lx + Math.round(offsetX(line, b) * scale);
            squiggle(g, Math.min(x0, x1), Math.max(x0, x1), base, "spelling".equals(issue.kind()) ? 0xd13438 : 0x2b579a);
          }
        }
        boolean revised = false;
        for (LayoutEngine.Span span : line.spans)
          revised |= span.style.revision != null || span.style.formatRevision != null;
        if (textLine(line)) {
          var paragraph = lineParagraph(line);
          revised |= paragraph.formatRevision != null;
          // A table with a tracked property change (Wordcraft's table fmt_change) is marked
          // beside each of its lines, as Word marks a table whose properties changed.
          if (line.row >= 0) revised |= tableRevised(line);
          if (paragraph.breakRevision != null && line.end == paragraph.text.length()) {
            revised = true;
            g.setFont(font(TextStyle.DEFAULT, zoom));
            g.setColor(paragraph.breakRevision.inserted() ? 0x1261a0 : 0xb32222);
            g.drawString("\u00b6", lx + Math.round(line.width * scale), ly);
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
          if (span.style.characters.hidden && !isShowHidden()) continue;
          var f = font(span.glyphStyle, zoom);
          g.setFont(f);
          int sx = lx + Math.round(span.x * scale), sy = ly + Math.round(span.top * scale);
          if (session.mergeData().highlight
              && textLine(line)
              && InlineField.OBJECT.equals(span.text)) {
            var field = lineParagraph(line).text.fieldAt(span.start);
            if (field != null && MailMerge.placeholder(field.instruction) != null) {
              g.setColor(0xe2e8f0);
              g.fillRect(sx, sy, Math.max(2, Math.round(span.width * scale)), f.getHeight());
            }
          }
          if (span.style.background != 0xffffff) {
            g.setColor(span.style.background);
            g.fillRect(sx, sy, Math.round(span.width * scale), f.getHeight());
          }
          g.setColor(ink(span.style.color));
          drawCharacters(g, span, f, scale, sx, sy);
          if (InlineField.OBJECT.equals(span.text) && span.displayText.isEmpty() && textLine(line)) {
            var field = lineParagraph(line).text.fieldAt(span.start);
            if (Equation.inline(field)) {
              paintingInline = span.start;
              paintDrawing(g, Equation.placed(field, span.glyphStyle, line.block, 0, 0, 0, span.width),
                  sx, sy + f.getAscent(), scale);
              paintingInline = -1;
            }
          }
          if (span.style.hasUnderline()) {
            var path = new GeneralPath();
            TextDecorations.underline(
                span,
                fontMetrics,
                (x1, y1, x2, y2) -> {
                  path.moveTo(sx + x1 * scale, sy + f.getAscent() + y1 * scale);
                  path.lineTo(sx + x2 * scale, sy + f.getAscent() + y2 * scale);
                });
            g.setColor(
                ink(
                    span.style.characters.underlineColor < 0
                        ? span.style.color
                        : span.style.characters.underlineColor));
            g.drawShape(
                path,
                new Stroke(
                    Math.max(1, TextDecorations.weight(span.style) * scale),
                    Stroke.CAP_BUTT,
                    Stroke.JOIN_MITER,
                    1));
          }
          if (span.style.strike || span.style.characters.doubleStrike) {
            g.setColor(ink(span.style.color));
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
        if (showMarks) paintMarks(g, line, lx, ly, scale);
        else if (isShowHidden()) paintHiddenUnderline(g, line, lx, ly, scale);
        g.setColor(ink(0x242424));
        for (float bar : line.barTabs)
          g.fillRect(
              lx + Math.round(bar * scale),
              ly,
              Math.max(1, Math.round(.5f * scale)),
              Math.round(line.height * scale));
        if (!exporting && session.cellSelection() == null && line == caretLine && selection.collapsed()) {
          g.setColor(0x087f80);
          int cx = lx + Math.round(offsetX(line, selection.caret + noteShift(line)) * scale);
          g.fillRect(cx, ly, 2, Math.round(line.height * scale));
        }
        if (line.clipped) g.setClip(clipX, clipY, clipW, clipH);
        if (unturned != null) g.setTransform(unturned);
      }
      for (LayoutEngine.PlacedPicture pic : LayoutEngine.byDepth(page.paintedPictures(), true))
        paintDrawing(g, pic, x + Math.round(pic.x() * scale), y + Math.round(pic.y() * scale), scale);
    }
    if (OfficeTheme.mobile() && !exporting && session.cellSelection() == null && !selection.collapsed()) {
      g.setColor(OfficeTheme.BLUE);
      int radius = OfficeTheme.px(6);
      for (int side = 0; side < 2; side++) {
        var point = handlePoint(side == 0);
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
    paintInk(g);
    if (moving && movePic != null) {
      // Wordcraft's drag outline (frame::paint_outline): the object's frame where it would land.
      int to = pageAt(moveX, moveY);
      float px = (moveX - viewportX() - pageX(to)) / scale() - moveDx;
      float py = (moveY - viewportY() + getScrollY() - pageY(to)) / scale() - moveDy;
      int gx = getX() + pageX(to) + getScrollX() + Math.round(px * scale()), gy = baseY + pageY(to) + Math.round(py * scale());
      int gw = Math.round(movePic.width() * scale()), gh = Math.round(movePic.height() * scale());
      g.setColor(0x2b579a);
      g.setAlpha(20);
      g.fillRect(gx, gy, gw, gh);
      g.setAlpha(255);
      g.drawRect(gx, gy, gw, gh);
    }
    if (inputAfterPaint) {
      inputAfterPaint = false;
      Display.getInstance().callSerially(() -> {
        requestFocus();
        startInput();
      });
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
    var original = g.getTransform();
    var transform = original.copy();
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
          var raw = value.substring(at, end);
          var glyph = font(span.style.glyphStyle(raw), zoom);
          g.setFont(glyph);
          float w = CharacterMetrics.width(fontMetrics, raw, span.style);
          textEffects.draw(
              g,
              span.style.displayText(raw),
              Math.round((span.rtl ? span.width - advance - w : advance) * scale / stretch),
              f.getAscent() - glyph.getAscent(),
              glyph,
              span.style,
              scale);
          advance += w;
          at = end;
        }
      }
    } finally {
      g.setTransform(original);
      g.setFont(f);
    }
  }

  private float offsetX(LayoutEngine.Line line, int offset) {
    for (LayoutEngine.Span s : line.spans) if (offset <= s.end && offset >= s.start) return spanX(s, offset);
    return line.width;
  }

  private float spanX(LayoutEngine.Span s, int offset) {
    float into =
        offset == s.end
            ? s.width
            : "\t".equals(s.text)
                ? (offset > s.start ? s.width : 0)
                : prefixWidth(s.text.substring(0, Math.max(0, offset - s.start)), s.style);
    return s.rtl ? s.x + s.width - into : s.x + into;
  }

  /** Fills [a, b) of a line, one rectangle per span so mixed-direction ranges stay contiguous. */
  private void fillRange(Graphics g, LayoutEngine.Line line, int a, int b, int lx, int ly, float scale) {
    for (LayoutEngine.Span s : line.spans) {
      int from = Math.max(a, s.start), to = Math.min(b, s.end);
      if (from >= to) continue;
      float p = spanX(s, from), q = spanX(s, to), left = Math.min(p, q);
      g.fillRect(
          lx + Math.round(left * scale),
          ly,
          Math.max(1, Math.round(Math.abs(q - p) * scale)),
          Math.round(line.height * scale));
    }
  }

  // Caret, selection and the text-input geometry measure every grapheme prefix of the visible
  // lines, several times per edit; unchanged lines hit this cache across edits.
  private final Map<TextStyle, Map<String, Float>> prefixWidths = new HashMap<>();
  private int prefixEntries;

  private float prefixWidth(String prefix, TextStyle style) {
    if (prefix.isEmpty()) return 0;
    var widths = prefixWidths.get(style);
    if (widths == null) prefixWidths.put(style, widths = new HashMap<String, Float>());
    var w = widths.get(prefix);
    if (w == null) {
      if (prefixEntries > 50000) {
        prefixWidths.clear();
        prefixEntries = 0;
        prefixWidths.put(style, widths = new HashMap<String, Float>());
      }
      w = Float.valueOf(CharacterMetrics.width(fontMetrics, prefix, style));
      widths.put(prefix, w);
      prefixEntries++;
    }
    return w.floatValue();
  }

  /** The last pointer y given to {@link #lineAt}: a turned line's offset depends on it. */
  private int hitY;

  private LayoutEngine.Line lineAt(int absX, int absY) {
    hitY = absY;
    int page = pageAt(absY - viewportY() + getScrollY());
    float y = (absY - viewportY() + getScrollY() - pageY(page)) / scale();
    float x = (absX - viewportX() - pageX(page)) / scale();
    LayoutEngine.Line best = null;
    float distance = Float.MAX_VALUE;
    var p = layout().pages.get(page);
    // An entered note story is hit-tested in the note area too.
    for (LayoutEngine.Line l : session.enclosingNote() != null ? p.allLines() : p.lines) {
      if (session.noteStory() != null && l.row < 0 && l.block < 0 && editingBlock(l) < 0) continue;
      // A turned cell line is hit in its unturned frame.
      float[] f = l.toFrame(x, y);
      float fx = f[0], fy = f[1];
      float dy = fy < l.y ? l.y - fy : fy > l.y + l.height ? fy - l.y - l.height : 0;
      float dx = fx < l.x ? l.x - fx : fx > l.x + l.width ? fx - l.x - l.width : 0;
      float d = dy * 1000 + dx;
      if (d < distance) {
        distance = d;
        best = l;
      }
    }
    return best;
  }

  private static int order(ReviewComment.Anchor a, ReviewComment.Anchor b) {
    return a.sameStory(b) ? Integer.compare(a.paragraph, b.paragraph) : a.compareTo(b);
  }

  /** Whether {@code box} is a cell of the selected cell range's (possibly nested) table. */
  private boolean selectedBox(CellSelection range, LayoutEngine.Box box) {
    int[] at = session.cellPath();
    int n = at.length;
    if (n < 3 || box.path.length + 3 != n) return false;
    int[] full = new int[n];
    full[0] = box.block;
    full[1] = box.row;
    full[2] = box.column;
    System.arraycopy(box.path, 0, full, 3, box.path.length);
    for (int i = 0; i < n - 3; i++) if (full[i] != at[i]) return false;
    return full[n - 3] == range.block && range.contains(range.block, full[n - 2], full[n - 1]);
  }

  /**
   * Offset of story text within a displayed note line: a note's first paragraph is displayed
   * after its label and a space. Zero outside an entered note story.
   */
  private int noteShift(LayoutEngine.Line line) {
    String id = session.noteStory();
    if (id == null || line.row >= 0) return 0;
    for (Note n : Notes.all(session.document()))
      if (n.id.equals(id))
        return n.paragraphs.size() > 0 && n.paragraphs.get(0).id.equals(line.paragraphId)
            ? n.label.length() + 1
            : 0;
    return 0;
  }

  /** The cell path (Session.cellPath) of a line: empty for body lines. */
  static int[] linePath(LayoutEngine.Line line) {
    return MixedRange.linePath(line);
  }

  private static boolean samePath(LayoutEngine.Box box, LayoutEngine.Line line) {
    return box.block == line.block
        && box.row == line.row
        && box.column == line.column
        && java.util.Arrays.equals(box.path, line.path);
  }

  private int editingBlock(LayoutEngine.Line line) {
    // A note story being edited is not drawn as the editing story (its first line carries the
    // note label), so body lines are not mistaken for it.
    if (session.noteStory() != null) {
      // While a note story is entered, its paragraphs' lines (in the note area or endnotes) are
      // the editing story, as Wordcraft draws the selection in the note part.
      if (line.row >= 0) return -1;
      var story = session.editingDocument();
      for (int i = 0; i < story.blocks.size(); i++)
        if (story.blocks.get(i) instanceof Paragraph p && p.id.equals(line.paragraphId)) return i;
      return -1;
    }
    if (!session.inCell()) return line.row < 0 ? line.block : -1;
    // A note table's cell is edited from its note's lines only (its path is note-relative).
    String note = session.enclosingNote();
    if (note == null ? line.noteCell != null : !note.equals(line.note)) return -1;
    int[] path = note == null ? linePath(line) : line.noteCell;
    if (!java.util.Arrays.equals(path, session.cellPath())) return -1;
    var d = session.editingDocument();
    for (int i = 0; i < d.blocks.size(); i++)
      if (d.blocks.get(i) instanceof Paragraph p && p.id.equals(line.paragraphId)) return i;
    return -1;
  }

  private DocumentProperties proofSource;

  private Proofing.Options proofOptions;

  private final Map<Paragraph, java.util.List<Proofing.Issue>> proofCache =
      new LinkedHashMap<>(64, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Paragraph, java.util.List<Proofing.Issue>> e) {
          return size() > 1024;
        }
      };

  /** Spelling and grammar issues of a paragraph, checked once per paragraph value. */
  private java.util.List<Proofing.Issue> proofIssues(Paragraph p) {
    var props = session.document().properties;
    if (props != proofSource) {
      proofSource = props;
      proofOptions = Proofing.Options.of(props);
      proofCache.clear();
    }
    var issues = proofCache.get(p);
    if (issues == null) {
      // Paint the page first; marks appear once the word list has loaded in the background.
      if (!Proofing.ready()) {
        Proofing.preload(
            () ->
                Display.getInstance().callSerially(() ->
                    repaint()));
        return java.util.Collections.emptyList();
      }
      issues = Proofing.marks(p.text.plainText(), proofOptions, off -> p.text.styleAt(off).characters.lang);
      proofCache.put(p, issues);
    }
    return issues;
  }

  /** Object lines (pictures, charts, equations) have no backing paragraph. */
  private boolean textLine(LayoutEngine.Line line) {
    return line.block >= 0
        && (line.row >= 0 || session.document().blocks.get(line.block) instanceof Paragraph);
  }

  /** Whether the line's table, or a table it is nested in, has a table property revision. */
  private boolean tableRevised(LayoutEngine.Line line) {
    var blocks = session.document().blocks;
    if (line.block < 0 || line.block >= blocks.size() || !(blocks.get(line.block) instanceof Table t))
      return false;
    int r = line.row, c = line.column;
    if (t.format.revision() != null) return true;
    for (int i = 0; i + 2 < line.path.length; i += 3) {
      if (r >= t.rows() || c >= t.columns()) return false;
      var inner = t.blocks(r, c);
      if (line.path[i] < 0 || line.path[i] >= inner.size() || !(inner.get(line.path[i]) instanceof Table n))
        return false;
      t = n;
      r = line.path[i + 1];
      c = line.path[i + 2];
      if (t.format.revision() != null) return true;
    }
    return false;
  }

  /** Enters the story a pressed line belongs to: a body cell, a note table's cell or the body. */
  private void enterLine(LayoutEngine.Line line) {
    if (line.noteCell != null) session.enterNoteCell(line.note, line.noteCell);
    else if (line.row >= 0) session.enterCellPath(linePath(line));
    else if (session.noteStory() == null || editingBlock(line) < 0) session.leaveCell();
  }

  /** Whether footnote-area {@code line} is text of footnote table cell {@code box}. */
  private static boolean noteCellOf(LayoutEngine.Box box, LayoutEngine.Line line) {
    int[] c = line.noteCell;
    if (c == null || box.block != -1 || line.y < box.y - .01f || line.y > box.y + box.height) return false;
    int n = c.length;
    if (c[n - 2] != box.row || c[n - 1] != box.column || box.path.length != n - 3) return false;
    // The box path is the nested cell's path inside the outermost note cell.
    for (int i = 0; i < box.path.length; i++) if (box.path[i] != c[3 + i]) return false;
    return true;
  }

  private Paragraph lineParagraph(LayoutEngine.Line line) {
    if (line.noteCell != null) {
      int story = editingBlock(line);
      if (story >= 0) return session.editingDocument().paragraph(story);
    }
    if (line.row < 0) return session.document().paragraph(line.block);
    var p =
        ((Table) session.document().blocks.get(line.block))
            .paragraph(line.row, line.column, line.path, line.paragraphId);
    if (p != null) return p;
    throw new IllegalStateException("Missing cell paragraph");
  }

  private int offsetAt(LayoutEngine.Line line, int absX) {
    float x = (absX - viewportX() - pageX(line.page)) / scale() - line.x;
    if (line.turn != 0) {
      float py = (hitY - viewportY() + getScrollY() - pageY(line.page)) / scale();
      x = line.toFrame(x + line.x, py)[0] - line.x;
    }
    int best = line.start;
    float distance = Float.MAX_VALUE;
    int shift = noteShift(line);
    int story = session.noteStory() == null && line.noteCell == null ? -1 : editingBlock(line);
    var p =
        story >= 0 ? session.editingDocument().paragraph(story) : lineParagraph(line);
    for (int i = Math.max(line.start, shift); i <= line.end; i++) {
      if (i - shift > p.text.length() || !Graphemes.isBoundary(p.text, i - shift)) continue;
      float d = Math.abs(offsetX(line, i) - x);
      if (d < distance) {
        distance = d;
        best = i;
      }
    }
    // Offsets in an entered note story exclude the label shown before its first paragraph.
    return Math.max(0, best - shift);
  }

  /** Receives a finished pen stroke: the ink block and its points in that object's points. */
  public interface InkSink {
    void stroke(int block, String points);
  }

  private InkSink ink;
  private int inkBlock = -1, inkPage;
  private LayoutEngine.PlacedPicture inkPicture;
  private final java.util.ArrayList<float[]> inkPoints = new java.util.ArrayList<>();
  private int inkColor = 0x000000;

  /** Draw mode: pointer drags over an ink object or drawing canvas become pen strokes. */
  public void setInk(InkSink sink, int color) {
    ink = sink;
    inkColor = color;
    inkPoints.clear();
    inkBlock = -1;
  }

  public boolean isInking() {
    return ink != null;
  }

  private boolean inkPress(int x, int y) {
    for (int page = 0; page < layout().pages.size(); page++) {
      float px = (x - viewportX() - pageX(page)) / scale();
      float py = (y - viewportY() + getScrollY() - pageY(page)) / scale();
      for (LayoutEngine.PlacedPicture pic : layout().pages.get(page).pictures) {
        if (px < pic.x() || px > pic.x() + pic.width() || py < pic.y() || py > pic.y() + pic.height()) continue;
        var d = pic.picture().drawing;
        if (d == null || !("ink".equals(d.kind) || "canvas".equals(d.kind))) continue;
        if (!selectObject(pic)) continue; // ink strokes go to the object in its cell story
        inkBlock = pic.block();
        inkPage = page;
        inkPicture = pic;
        inkPoints.clear();
        inkPoints.add(new float[] {px - pic.x(), py - pic.y()});
        repaint();
        return true;
      }
    }
    return false;
  }

  private void inkDrag(int x, int y) {
    if (inkBlock < 0) return;
    float px = (x - viewportX() - pageX(inkPage)) / scale() - inkPicture.x();
    float py = (y - viewportY() + getScrollY() - pageY(inkPage)) / scale() - inkPicture.y();
    px = Math.max(0, Math.min(inkPicture.width(), px));
    py = Math.max(0, Math.min(inkPicture.height(), py));
    var last = inkPoints.get(inkPoints.size() - 1);
    if (Math.abs(last[0] - px) + Math.abs(last[1] - py) < .75f) return;
    if (inkPoints.size() < 5000) inkPoints.add(new float[] {px, py});
    repaint();
  }

  private void inkRelease() {
    if (inkBlock < 0) return;
    var b = new StringBuilder();
    for (float[] pt : inkPoints) {
      if (b.length() > 0) b.append(' ');
      b.append(Math.round(pt[0] * 10) / 10f).append(',').append(Math.round(pt[1] * 10) / 10f);
    }
    int block = inkBlock;
    inkBlock = -1;
    inkPoints.clear();
    ink.stroke(block, b.toString());
    repaint();
  }

  private void paintInk(Graphics g) {
    if (inkBlock < 0 || inkPoints.size() < 1) return;
    float sc = scale();
    int ox = getX() + pageX(inkPage) + getScrollX(), oy = getY() + pageY(inkPage);
    boolean aa = g.isAntiAliased();
    g.setAntiAliased(true);
    g.setColor(inkColor);
    int w = Math.max(1, Math.round(sc * 1.5f));
    var prev = inkPoints.get(0);
    for (int i = 1; i < inkPoints.size(); i++) {
      var pt = inkPoints.get(i);
      int x1 = ox + Math.round((inkPicture.x() + prev[0]) * sc), y1 = oy + Math.round((inkPicture.y() + prev[1]) * sc);
      int x2 = ox + Math.round((inkPicture.x() + pt[0]) * sc), y2 = oy + Math.round((inkPicture.y() + pt[1]) * sc);
      for (int t = -w / 2; t <= w / 2; t++) {
        g.drawLine(x1 + t, y1, x2 + t, y2);
        g.drawLine(x1, y1 + t, x2, y2 + t);
      }
      prev = pt;
    }
    g.setAntiAliased(aa);
  }

  @Override
  public void pointerPressed(int x, int y) {
    if (equation != null) endEquation(false);
    if (ink != null) {
      inkPress(x, y);
      return;
    }
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

  private long lastClick;
  private int clicks, lastClickBlock = -1, lastClickOffset;

  private void placeCaret(int x, int y) {
    finishComposing();
    var line = lineAt(x, y);
    for (int page = 0; page < layout().pages.size(); page++) {
      float px = (x - viewportX() - pageX(page)) / scale();
      float py = (y - viewportY() + getScrollY() - pageY(page)) / scale();
      for (LayoutEngine.PlacedPicture pic : layout().pages.get(page).pictures) {
        if (px >= pic.x() && px <= pic.x() + pic.width() && py >= pic.y() && py <= pic.y() + pic.height()) {
          stopInput();
          if (!selectObject(pic)) continue;
          if (clickEquation(pic, px, py)) return;
          // Pressing an object may start dragging it (Wordcraft's arrange.bounds drag).
          movePic = pic;
          movePage = page;
          moveDx = px - pic.x();
          moveDy = py - pic.y();
          movePressX = x;
          movePressY = y;
          if (pictureEditor != null) pictureEditor.edit(pic.block());
          return;
        }
      }
      for (LayoutEngine.Box box : layout().pages.get(page).boxes) {
        if (px < box.x || px > box.x + box.width || py < box.y || py > box.y + box.height) continue;
        float best = Float.MAX_VALUE;
        for (LayoutEngine.Line candidate : layout().pages.get(page).lines) {
          // Nested cell boxes follow their outer cell's box, so the innermost box wins.
          if (!samePath(box, candidate)) continue;
          float distance = Math.abs(py - candidate.y - candidate.height / 2);
          if (distance < best) {
            best = distance;
            line = candidate;
          }
        }
        // A footnote table's cell: its lines are in the note area (Wordcraft's cell_at finds
        // cells in note parts too).
        for (LayoutEngine.Line candidate : layout().pages.get(page).notes) {
          if (!noteCellOf(box, candidate)) continue;
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
    enterLine(line);
    if (clickInlineEquation(line, x, y)) return;
    int block = editingBlock(line), offset = offsetAt(line, x);
    if (block < 0) return;
    session.select(block, offset, offset);
    rememberLine(line);
    dragBlock = block;
    dragAnchor = offset;
    // Double click (or tap) selects the word, triple selects the paragraph, as in the
    // framework's EditorView.
    long now = System.currentTimeMillis();
    clicks = now - lastClick < 400 && block == lastClickBlock && Math.abs(offset - lastClickOffset) <= 1
        ? clicks + 1 : 1;
    lastClick = now;
    lastClickBlock = block;
    lastClickOffset = offset;
    if (clicks >= 2) {
      var text = lineParagraph(line).text.toString();
      int a = clicks == 2 ? Math.min(offset, text.length()) : 0, z = clicks == 2 ? a : text.length();
      if (clicks == 2) {
        int[] word = TextNavigation.wordAt(text, a);
        a = word[0];
        z = word[1];
      }
      session.select(block, a, z);
      dragBlock = -1;
    }
    dragFrom(line);
    startInput();
    refresh();
    changed.run();
  }

  /**
   * Makes the story holding a placed object the edited one, as Wordcraft's object selection
   * carries its path: a table-cell object enters its cell (so picture commands, ink, delete and
   * replace address it by its index in the cell) with the caret after it; a body object leaves any
   * cell. False when the object cannot be selected (a cell of a header or footer table).
   */
  boolean selectObject(LayoutEngine.PlacedPicture pic) {
    if (!pic.inCell()) {
      session.leaveCell();
      return true;
    }
    int[] path = pic.cell();
    var body = session.document().blocks;
    if (path[0] >= body.size() || !(body.get(path[0]) instanceof Table)) return false;
    try {
      session.enterCellPath(path);
    } catch (RuntimeException ex) {
      return false;
    }
    var cell = session.editingDocument().blocks;
    if (pic.block() >= cell.size() || cell.get(pic.block()) != pic.picture()) return false;
    for (int j = pic.block() + 1; j < cell.size() && j <= pic.block() + 2; j++)
      if (cell.get(j) instanceof Paragraph) {
        session.select(j, 0, 0);
        break;
      }
    return true;
  }

  /** The object being dragged to a new position, its page and the grab offset in points. */
  private LayoutEngine.PlacedPicture movePic;
  private int movePage, movePressX, movePressY, moveX, moveY;
  private float moveDx, moveDy;
  private boolean moving;

  /** The page under screen point x, y (the nearest one by its vertical extent). */
  private int pageAt(int x, int y) {
    int best = movePage;
    float dist = Float.MAX_VALUE;
    for (int page = 0; page < layout().pages.size(); page++) {
      float py = (y - viewportY() + getScrollY() - pageY(page)) / scale();
      float h = session.document().page.height;
      float d = py < 0 ? -py : py > h ? py - h : 0;
      if (d < dist) {
        dist = d;
        best = page;
      }
    }
    return best;
  }

  /**
   * Drops a dragged object: as Wordcraft, it floats with its top left where it was dropped,
   * measured from its anchor page's top left (Square wrapped if it was inline). A press that
   * barely moved is a click.
   */
  private boolean dropMoved(int x, int y) {
    var pic = movePic;
    movePic = null;
    moving = false;
    if (pic == null || Math.abs(x - movePressX) + Math.abs(y - movePressY) < OfficeTheme.px(4)) return false;
    if (pic.inCell()) {
      // A cell object moves within its cell (Word's layoutInCell), measured from the cell's
      // content left and its anchor paragraph's top.
      if (!selectObject(pic)) return false;
      var cellBlock = session.editingDocument().blocks.get(pic.block());
      float[] f = pic.frame();
      float cx = (x - movePressX) / scale() + pic.x() - f[0], cy = (y - movePressY) / scale() + pic.y() - f[1];
      cx = Math.max(0, Math.min(Math.max(0, f[2] - pic.width()), cx));
      session.replacePicture(pic.block(), ((Picture) cellBlock).movedInCell(cx, cy));
      refresh();
      changed.run();
      return true;
    }
    var block = session.document().blocks.get(pic.block());
    if (!(block instanceof Picture)) return false;
    int to = pageAt(x, y);
    float px = (x - viewportX() - pageX(to)) / scale() - moveDx;
    float py = (y - viewportY() + getScrollY() - pageY(to)) / scale() - moveDy;
    var spec = session.document().page;
    px = Math.max(-pic.width() / 2, Math.min(spec.width - pic.width() / 2, px));
    py = Math.max(-pic.height() / 2, Math.min(spec.height - pic.height() / 2, py));
    // Dropped on another page: re-anchored to the paragraph there, as Wordcraft's move_object.
    Document moved = to == movePage ? null : Picture.reanchored(session.document(), layout(), pic.block(), to, px, py);
    if (moved != null) session.replaceDocument(moved);
    else session.replacePicture(pic.block(), ((Picture) block).movedTo(px, py));
    refresh();
    changed.run();
    return true;
  }

  @Override
  public void pointerDragged(int x, int y) {
    if (ink != null) {
      inkDrag(x, y);
      return;
    }
    if (movePic != null) {
      // Wordcraft's drag outline: where the object will land.
      moveX = x;
      moveY = y;
      moving = Math.abs(x - movePressX) + Math.abs(y - movePressY) >= OfficeTheme.px(4);
      repaint();
      return;
    }
    if (OfficeTheme.mobile() && !touchSelecting) {
      if (Math.abs(x - touchX) + Math.abs(y - touchY) > OfficeTheme.px(6)) touchMoved = true;
      super.pointerDragged(x, y);
      return;
    }
    if (OfficeTheme.mobile() && dragHandle != 0) {
      var target = lineAt(x, y);
      if (target != null && editingBlock(target) >= 0) {
        var old = session.selection();
        if (dragHandle < 0)
          session.selectRange(editingBlock(target), offsetAt(target, x), old.block, old.caret);
        else
          session.selectRange(
              old.anchorBlock, old.anchor, editingBlock(target), offsetAt(target, x));
        refresh();
      }
      return;
    }
    var line = lineAt(x, y);
    int[] over = line == null || dragTable < 0 ? null : dragCell(line);
    if (over != null
        && dragBlock >= 0
        && (over[0] != dragRow || over[1] != dragColumn || session.cellSelection() != null)) {
      session.selectCells(dragTable, dragRow, dragColumn, over[0], over[1]);
      rememberLine(line);
      refresh();
      changed.run();
      return;
    }
    if (line != null && dragAcross(line, x)) {
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
    if (ink != null) {
      inkRelease();
      return;
    }
    if (dropMoved(x, y)) {
      dragBlock = -1;
      return;
    }
    if (OfficeTheme.mobile() && !touchMoved && !touchSelecting && pinchStart == 0) placeCaret(x, y);
    // Touch places the caret on release: dragging an object is a desktop pointer gesture.
    movePic = null;
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
    var line = lineAt(x, y);
    if (line == null) return;
    enterLine(line);
    var text = lineParagraph(line).text.toString();
    int offset = Math.min(offsetAt(line, x), text.length());
    int[] word = TextNavigation.wordAt(text, offset);
    int a = word[0], z = word[1];
    session.select(editingBlock(line), a, z);
    dragBlock = editingBlock(line);
    dragAnchor = a;
    dragFrom(line);
    touchSelecting = true;
    startInput();
    refresh();
  }

  /** Outline level announced to screen readers for Heading1..Heading6 / Title styles. */
  private static int headingLevel(Paragraph p) {
    String style = p.style == null ? "" : p.style;
    if ("Title".equals(style)) return 1;
    if (style.startsWith("Heading") && style.length() == 8) {
      int level = style.charAt(7) - '0';
      if (level >= 1 && level <= 6) return level;
    }
    return -1;
  }

  private int[] handlePoint(boolean anchor) {
    var s = session.selection();
    int block = anchor ? s.anchorBlock : s.block, offset = anchor ? s.anchor : s.caret;
    for (int page = 0; page < layout().pages.size(); page++)
      for (LayoutEngine.Line line : layout().pages.get(page).lines)
        if (editingBlock(line) == block
            && offset + noteShift(line) >= line.start
            && offset + noteShift(line) <= line.end)
          return new int[] {
            viewportX()
                + pageX(line.page)
                + Math.round((line.x + offsetX(line, offset + noteShift(line))) * scale()),
            viewportY() - getScrollY() + pageY(page) + Math.round((line.y + line.height) * scale())
          };
    return null;
  }

  private int hitHandle(int x, int y) {
    if (session.cellSelection() != null || session.selection().collapsed()) return 0;
    for (int side = -1; side <= 1; side += 2) {
      var point = handlePoint(side < 0);
      if (point != null
          && Math.abs(x - point[0]) <= OfficeTheme.px(22)
          && Math.abs(y - point[1]) <= OfficeTheme.px(22)) return side;
    }
    return 0;
  }

  public void resumeInput() {
    if (input == null) startInput();
  }

  /** Test hook: the JUnit simulator has no text input API; skip the modal warning there. */
  static boolean quietWithoutInput;

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
      if (quietWithoutInput) return;
      Dialog.show(
          "Keyboard unavailable",
          "This port does not expose the low-level text input API required by the document canvas.",
          "OK",
          null);
    } else host.updateTextInputState(input, getEditingState());
  }

  /** True while a platform text input session delivers keys through {@link #onKeyCommand}. */
  boolean textInputActive() {
    return input != null;
  }

  @Override
  public void keyPressed(int keyCode) {
    if (!rawKey(keyCode)) super.keyPressed(keyCode);
  }

  @Override
  public void keyRepeated(int keyCode) {
    if (!rawKey(keyCode)) super.keyRepeated(keyCode);
  }

  /**
   * Raw key fallback, as Codename One's EditorView.handleNavKey: with no text input session bound
   * (the simulator, or a port without the API) arrows and Backspace/Delete still edit. When input
   * is active the platform delivers these through onKeyCommand, so they are not handled twice.
   */
  boolean rawKey(int keyCode) {
    if (textInputActive()) return false;
    Display d = Display.getInstance();
    int command = EditKeys.rawCommand(keyCode, d.getGameAction(keyCode));
    if (command < 0) return false;
    onKeyCommand(
        command,
        EditKeys.rawModifiers(d.isShiftKeyDown(), d.isAltKeyDown(), d.isMetaKeyDown(), d.isControlKeyDown()));
    return true;
  }

  public void stopInput() {
    session.breakTypingGroup();
    finishComposing();
    if (inputBinding != null) {
      inputBinding.close();
      inputBinding = null;
    }
    if (input != null) {
      var old = input;
      input = null;
      host.stopTextInput(old);
    }
  }

  @Override
  protected void laidOut() {
    super.laidOut();
    syncInput();
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
    var rect = getCaretRect();
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

  private static final int SQUIGGLE = 32;
  private final Map<Integer, Image> squiggles = new HashMap<>();

  /**
   * Proofing wave from x0 to x1 on base. A per-segment drawLine became thousands of separate
   * Metal draws per frame on a text full of flagged words; a cached tile is one per 32px.
   */
  private void squiggle(Graphics g, int x0, int x1, int base, int color) {
    var tile = squiggles.get(color);
    if (tile == null) {
      tile = Image.createImage(SQUIGGLE, 2, 0);
      var tg = tile.getGraphics();
      tg.setColor(color);
      for (int wx = 0; wx < SQUIGGLE; wx += 2)
        tg.drawLine(wx, wx / 2 % 2, wx + 2, (wx / 2 + 1) % 2);
      squiggles.put(color, tile);
    }
    g.pushClip();
    g.clipRect(x0, base, x1 - x0, 2);
    for (int wx = x0; wx < x1; wx += SQUIGGLE) g.drawImage(tile, wx, base);
    g.popClip();
  }

  public void clipboard(String action) {
    session.breakTypingGroup();
    finishComposing();
    try {
      if ("paste".equals(action) || "pasteText".equals(action)) {
        // pasteText is Word's Keep Text Only: skip the rich importers.
        ClipboardContent data =
            "paste".equals(action) ? Display.getInstance().getClipboardContent() : null;
        if (data == null || !importContent(data)) {
          var plain = Display.getInstance().getPasteDataFromClipboard();
          if (plain instanceof String string) {
            session.pasteText(string);
            didEdit();
          }
        }
      } else {
        var content = selectionContent();
        if (content == null) return;
        Display.getInstance().copyToClipboard(content);
        if ("cut".equals(action)) {
          session.cut();
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
    var fragment = session.copySelection();
    if (fragment == null) return null;
    var content =
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
    var value = data.getData(mime);
    if (value instanceof byte[] bytes) return new String(bytes, "UTF-8");
    return value instanceof String s ? s : null;
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
      var bytes = data.getBytes(mime);
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
      var lower = path.toLowerCase();
      if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
          || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".tif")
          || lower.endsWith(".tiff") || lower.endsWith(".webp")) {
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
        var body = new String(readFile(path), "UTF-8");
        if (format == RichTextFormat.PLAIN_TEXT) session.pasteText(body);
        else session.paste(TextFormats.read(body, format));
        any = true;
      }
    }
    return any;
  }

  private static byte[] readFile(String path) throws java.io.IOException {
    try (var in =
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
        var fragment = session.copySelection();
        var target = hitTest(evt.getX(), evt.getY());
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
    var content = selectionContent();
    if (content == null) return null;
    return new NativeDragOperation(content)
        .setAllowedActions(NativeDragOperation.ACTION_COPY | NativeDragOperation.ACTION_MOVE)
        .setLabel(session.selectedText());
  }

  /** Block and offset under an absolute point, without changing the selection. */
  private int[] hitTest(int x, int y) {
    var line = lineAt(x, y);
    if (line == null || line.row >= 0) return null;
    int block = editingBlock(line);
    return block < 0 ? null : new int[] {block, offsetAt(line, x)};
  }

  private boolean insideSelection(int x, int y) {
    var sel = session.selection();
    var at = hitTest(x, y);
    if (at == null || sel.collapsed()) return false;
    int sb = sel.startBlock(), eb = sel.endBlock();
    if (at[0] < sb || at[0] > eb) return false;
    if (at[0] == sb && at[1] < sel.start()) return false;
    return at[0] != eb || at[1] <= sel.end();
  }

  private void moveCaretTo(int x, int y) {
    var at = hitTest(x, y);
    if (at == null) return;
    session.leaveCell();
    session.select(at[0], at[1], at[1]);
    repaint();
  }

  /**
   * True when document protection rejects text edits (read-only or comments only). Input is
   * dropped here so the platform keyboard never sees a failed edit; navigation still works.
   */
  public boolean readOnly() {
    var mode = session.document().properties.protection;
    return viewOnly || !"all".equals(markup) || "readOnly".equals(mode) || "comments".equals(mode)
        || outline != null && outline.filters(session.document());
  }

  @Override
  public void commitText(String text) {
    if (readOnly()) return;
    if (equation != null) {
      // In-place equation editing: typing goes into the equation's current slot.
      if (!"\n".equals(text)) equation.type(text);
      else if (!equation.enter()) endEquation(false);
      didEdit();
      return;
    }
    if (session.isComposing()) {
      session.compose(text, text.length());
      session.finishComposition();
    } else {
      if (text.length() == 1 && " \n.,;:!?)".indexOf(text.charAt(0)) >= 0) autoCorrect();
      if ("\n".equals(text)) session.enterParagraph();
      else session.typeText(text, System.currentTimeMillis());
    }
    composingStart = composingEnd = -1;
    didEdit();
  }

  /** AutoCorrect as you type: fix the word before the caret when a word-ending key is typed. */
  private void autoCorrect() {
    var x = session.selection();
    if (!x.collapsed() || session.inCell()) return;
    var d = session.editingDocument();
    if (!(d.blocks.get(x.block) instanceof Paragraph)) return;
    var before = d.paragraph(x.block).text.plainText().substring(0, x.caret);
    String fixed = AppOptions.correct(before);
    if (fixed == null) return;
    int k = 0;
    while (k < before.length() && k < fixed.length() && before.charAt(k) == fixed.charAt(k)) k++;
    session.breakTypingGroup();
    session.select(x.block, k, x.caret);
    session.replace(fixed.substring(k));
  }

  @Override
  public void setComposingText(String text, int relativeCaret) {
    if (readOnly()) return;
    if (!session.isComposing()) composingStart = session.selection().start();
    // As in Codename One's EditorView, the caret sits at the end of the composition. The
    // relative caret follows each platform's own convention (Android's 1 means "after the
    // text"), and reading it as an offset put the caret mid-word, so Gboard typed out of order.
    session.compose(text, text.length());
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
    if (readOnly()) return;
    finishComposing();
    var x = session.selection();
    if (session.cellSelection() != null || !x.collapsed()) {
      session.replace("");
      didEdit();
      return;
    }
    var p = session.editingDocument().paragraph(x.block);
    int a = Math.max(0, x.start() - Math.max(0, before)),
        b = Math.min(p.text.length(), x.end() + Math.max(0, after));
    if (a > 0 && a < p.text.length() && Character.isLowSurrogate(p.text.charAt(a))) a--;
    if (b < p.text.length() && b > 0 && Character.isLowSurrogate(p.text.charAt(b))) b++;
    session.select(x.block, a, b);
    session.replace("");
    didEdit();
  }

  private boolean moveCellSelection(int command, boolean extend) {
    // A selection anchored outside the cell keeps running through text in document order.
    if (session.mixedSelection() != null) return false;
    if (!session.inCell()
        || (command != KEY_LEFT
            && command != KEY_RIGHT
            && command != KEY_UP
            && command != KEY_DOWN)) return false;
    var range = session.cellSelection();
    var text = session.selection();
    var story = session.editingDocument();
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
    var table = (Table) session.levelDocument().blocks.get(tableBlock);
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
      var span = table.span(fr, fc);
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
    // macOS sends Option+Page Up/Down as a viewport scroll without a caret move.
    if (command == KEY_SCROLL_PAGE_UP || command == KEY_SCROLL_PAGE_DOWN) {
      int page = Math.max(1, getHeight() - getStyle().getVerticalPadding());
      int y = getScrollY() + (command == KEY_SCROLL_PAGE_UP ? -page : page);
      setScrollY(Math.max(0, Math.min(y, Math.max(0, getScrollDimension().getHeight() - getHeight()))));
      repaint();
      return;
    }
    finishComposing();
    if (equation != null) {
      // Moving inside an equation keeps its typing undo group, as Wordcraft's pure equation.move.
      if (equationKey(command, modifiers)) return;
      endEquation(false);
    }
    session.breakTypingGroup();
    var x = session.selection();
    var p = session.editingDocument().paragraph(x.block);
    boolean extend = (modifiers & MOD_SHIFT) != 0;
    if (readOnly() && (command == KEY_BACKSPACE || command == KEY_DELETE || command == KEY_TAB))
      return;
    if (moveCellSelection(command, extend)) return;
    final var across = session.mixedSelection();
    // Resolve navigation keys the way Codename One's editors do (EditKeys), then express the
    // move in the logical commands below; left/right are already spatial-to-logical here.
    var move = EditKeys.move(command, modifiers, p.format.rtl);
    if (move != EditKeys.Move.NONE) {
      int shift = modifiers & MOD_SHIFT;
      switch (move) {
        case CHAR_BACK -> { command = KEY_LEFT; modifiers = shift; }
        case CHAR_FORWARD -> { command = KEY_RIGHT; modifiers = shift; }
        case WORD_BACK -> { command = KEY_LEFT; modifiers = shift | MOD_ALT; }
        case WORD_FORWARD -> { command = KEY_RIGHT; modifiers = shift | MOD_ALT; }
        case LINE_START -> { command = KEY_HOME; modifiers = shift; }
        case LINE_END -> { command = KEY_END; modifiers = shift; }
        case LINE_UP -> { command = KEY_UP; modifiers = shift; }
        case LINE_DOWN -> { command = KEY_DOWN; modifiers = shift; }
        case PARAGRAPH_UP -> { command = KEY_UP; modifiers = shift | MOD_ALT; }
        case PARAGRAPH_DOWN -> { command = KEY_DOWN; modifiers = shift | MOD_ALT; }
        case PARAGRAPH_START -> { command = KEY_HOME; modifiers = shift | MOD_ALT; }
        case PARAGRAPH_END -> { command = KEY_END; modifiers = shift | MOD_ALT; }
        case DOCUMENT_START -> { command = KEY_HOME; modifiers = shift | MOD_CTRL; }
        case DOCUMENT_END -> { command = KEY_END; modifiers = shift | MOD_CTRL; }
        default -> {}
      }
    }
    boolean word =
        move != EditKeys.Move.NONE
            ? (modifiers & MOD_ALT) != 0
            : EditKeys.wordDelete(modifiers);
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
        if (session.mixedSelection() != null) {
          // A selection spanning paragraphs indents them, as Wordcraft's text.tab does.
          session.stepIndent(extend);
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
        if (across != null && !extend) {
          collapseAcross(across.start());
          return;
        }
        if (!extend && !x.collapsed()) {
          block = x.startBlock();
          caret = x.start();
        } else if (word) {
          int[] target = TextNavigation.word(session.editingDocument(), block, caret, false);
          block = target[0];
          caret = target[1];
        } else if (TextNavigation.step(p.text, caret, false) != caret) {
          // Zero-width field marks are crossed with the character beyond them.
          caret = TextNavigation.step(p.text, caret, false);
        } else if (block > 0
            && session.editingDocument().blocks.get(block - 1) instanceof Paragraph) {
          block--;
          caret = session.editingDocument().paragraph(block).text.length();
        } else if (crossStory(false, extend)) {
          afterCross();
          return;
        }
        break;
      case KEY_RIGHT:
        if (across != null && !extend) {
          collapseAcross(across.end());
          return;
        }
        if (!extend && !x.collapsed()) {
          block = x.endBlock();
          caret = x.end();
        } else if (word) {
          int[] target = TextNavigation.word(session.editingDocument(), block, caret, true);
          block = target[0];
          caret = target[1];
        } else if (TextNavigation.step(p.text, caret, true) != caret)
          caret = TextNavigation.step(p.text, caret, true);
        else if (block + 1 < session.editingDocument().blocks.size()
            && session.editingDocument().blocks.get(block + 1) instanceof Paragraph) {
          block++;
          caret = 0;
        } else if (crossStory(true, extend)) {
          afterCross();
          return;
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
          var current = caretLine();
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
            if (targetLine == current
                && (command == KEY_UP || command == KEY_DOWN)
                && crossStory(command == KEY_DOWN, extend)) {
              afterCross();
              return;
            }
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
    if (extend && across != null)
      session.selectAcross(
          across.anchor.path, across.anchor.block, across.anchor.offset, session.cellPath(), block, caret);
    else
      session.selectRange(extend ? x.anchorBlock : block, extend ? x.anchor : caret, block, caret);
    rememberLine(targetLine);
    if (vertical) verticalSelection = session.selection();
    refresh();
    ensureCaretVisible();
  }

  private void collapseAcross(MixedRange.Pos at) {
    session.enterCellPath(at.path);
    session.select(at.block, at.offset, at.offset);
    afterCross();
  }

  private void afterCross() {
    rememberLine(null);
    refresh();
    ensureCaretVisible();
    changed.run();
  }

  @Override
  public void onEditorAction(int action) {
    commitText("\n");
  }

  @Override
  public TextInputState getEditingState() {
    var s = session.selection();
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
    var position = textIndex().position(offset);
    int block = position[0];
    offset = position[1];
    LayoutEngine.Line chosen = null;
    var selection = session.selection();
    if (selection.block == block && selection.caret == offset) chosen = caretLine();
    if (chosen == null)
      for (LayoutEngine.Line line : linesOf(block))
        if (editingBlock(line) == block
            && offset + noteShift(line) >= line.start
            && offset + noteShift(line) <= line.end) chosen = line;
    if (chosen != null && chosen.turn != 0) {
      float[] r =
          chosen.pageRect(
              chosen.x + offsetX(chosen, offset + noteShift(chosen)), chosen.y, 0, chosen.height);
      return new int[] {
        viewportX() + pageX(chosen.page) + Math.round(r[0] * scale()),
        viewportY() - getScrollY() + pageY(chosen.page) + Math.round(r[1] * scale()),
        Math.max(2, Math.round(r[2] * scale())),
        Math.max(2, Math.round(r[3] * scale()))
      };
    }
    if (chosen != null)
      return new int[] {
        viewportX()
            + pageX(chosen.page)
            + Math.round((chosen.x + offsetX(chosen, offset + noteShift(chosen))) * scale()),
        viewportY() - getScrollY() + pageY(chosen.page) + Math.round(chosen.y * scale()),
        2,
        Math.round(chosen.height * scale())
      };
    return new int[] {viewportX(), viewportY(), 2, 20};
  }

  @Override
  public int offsetAtPoint(int x, int y) {
    var line = lineAt(x, y);
    return line != null && editingBlock(line) >= 0
        ? textIndex().absolute(editingBlock(line), offsetAt(line, x))
        : textIndex().absolute(session.selection().block, session.selection().caret);
  }

  @Override
  public int[] selectionRects(int a, int b) {
    var values = new ArrayList<Integer>();
    for (LayoutEngine.Page page : layout().pages)
      for (LayoutEngine.Line l : page.lines)
        if (editingBlock(l) >= 0
            && a < textIndex().absolute(editingBlock(l), l.end)
            && b > textIndex().absolute(editingBlock(l), l.start)) {
          int base = textIndex().absolute(editingBlock(l), 0);
          int start = Math.max(a - base, l.start), end = Math.min(b - base, l.end);
          if (l.turn != 0) {
            float[] r =
                l.pageRect(l.x + offsetX(l, start), l.y, offsetX(l, end) - offsetX(l, start), l.height);
            values.add(viewportX() + pageX(l.page) + Math.round(r[0] * scale()));
            values.add(viewportY() - getScrollY() + pageY(l.page) + Math.round(r[1] * scale()));
            values.add(Math.round(r[2] * scale()));
            values.add(Math.round(r[3] * scale()));
            continue;
          }
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
    var points = new ArrayList<Integer>();
    var index = textIndex();
    float scale = scale();
    int top = viewportY() - getScrollY();
    var pages = layout().pages;
    for (int p = 0; p < pages.size(); p++) {
      // Only visible lines are reported; skip whole pages off screen without walking their lines.
      // Side-by-side pages share a row top, so only the one-column stack can bound by the next page.
      if ((across <= 1 || reflow) && top + pageY(p + 1) < viewportY()
          || top + pageY(p) > viewportY() + getHeight()) continue;
      for (LayoutEngine.Line line : pages.get(p).lines) {
        int y = top + pageY(line.page) + Math.round(line.y * scale);
        int height = Math.round(line.height * scale);
        if (editingBlock(line) < 0 || !textLine(line) || y + height < viewportY() || y > viewportY() + getHeight())
          continue;
        int base = index.absolute(editingBlock(line), 0);
        var paragraph = lineParagraph(line);
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
    var state = getEditingState();
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
    var text = textIndex().text;
    a = Math.max(0, Math.min(text.length(), a));
    b = Math.max(a, Math.min(text.length(), b));
    return text.substring(a, b);
  }

  private boolean matchesCellSelection(int a, int b) {
    if (session.cellSelection() == null) return false;
    var state = getEditingState();
    return Math.min(a, b) == state.getSelectionStart() && Math.max(a, b) == state.getSelectionEnd();
  }

  @Override
  public void replaceRange(int a, int b, String text) {
    if (readOnly()) return;
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
