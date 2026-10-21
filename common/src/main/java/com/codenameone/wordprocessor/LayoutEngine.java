package com.codenameone.wordprocessor;

import java.util.*;

/** Platform-independent pagination in typographic points, with identity-keyed paragraph caching. */
public final class LayoutEngine {
  public interface Metrics {
    float width(String text, TextStyle style);

    float height(TextStyle style);

    default float ascent(TextStyle style) {
      return height(style);
    }
  }

  public static final class Span {
    public final String text, displayText, rawDisplay;
    public final TextStyle style, glyphStyle;
    public final int start, end;
    public final float x, width, top;

    /** The span's text runs right to left: its first character is at the right edge. */
    public final boolean rtl;

    Span(String t, TextStyle s, int start, float x, float w) {
      this(t, s, start, x, w, 0);
    }

    Span(String t, TextStyle s, int start, float x, float w, float top) {
      this(t, s, start, x, w, top, "\t".equals(t) ? "" : t);
    }

    Span(String t, TextStyle s, int start, float x, float w, float top, String display) {
      rawDisplay = display;
      displayText = s.displayText(display);
      glyphStyle = s.markup().glyphStyle(display);
      this.top = top;
      text = t;
      style = s.markup();
      this.start = start;
      end = start + t.length();
      this.x = x;
      width = w;
      rtl = false;
    }

    private Span(Span s, float x, float top, boolean rtl) {
      rawDisplay = s.rawDisplay;
      displayText = s.displayText;
      glyphStyle = s.glyphStyle;
      this.top = top;
      text = s.text;
      style = s.style;
      start = s.start;
      end = s.end;
      width = s.width;
      this.x = x;
      this.rtl = rtl;
    }

    Span placed(float x, boolean rtl) {
      return new Span(this, x, top, rtl);
    }

    Span top(float top) {
      return new Span(this, x, top, rtl);
    }
  }

  static final int[] NO_PATH = new int[0];

  public static final class Line {
    public final int block, row, column, start, end, page;

    /**
     * For text of a table nested in cell (row, column) of {@code block}: the nested cell's path
     * inside that cell, as block, row, column triples (Session.cellPath without the first triple).
     */
    public int[] path = NO_PATH;

    Line at(int[] value) {
      path = value;
      return this;
    }

    /**
     * For text of a table cell inside a footnote/endnote story: the note's id and the cell's path
     * within that story (block, row, column triples, as Session.cellPath while the note is
     * entered), so a click enters that cell as Wordcraft's hit test finds cells in note parts.
     */
    public String note;

    public int[] noteCell;

    /** A story line's cell (row, column, then the nested path), or null: see storyCell. */
    int[] storyCell;

    Line storyCell(Line cell) {
      if (cell.row < 0) return this;
      storyCell = new int[2 + cell.path.length];
      storyCell[0] = cell.row;
      storyCell[1] = cell.column;
      System.arraycopy(cell.path, 0, storyCell, 2, cell.path.length);
      return this;
    }

    Line inNote(String id, int block) {
      return id == null || row < 0 ? this : inNoteCell(id, block, storyCell(this));
    }

    /** Marks this line as text of note {@code id}'s cell that story line {@code cell} is in. */
    Line inNoteCell(String id, int block, Line cell) {
      int[] c = cell.storyCell;
      if (id == null || block < 0 || c == null) return this;
      note = id;
      noteCell = new int[1 + c.length];
      noteCell[0] = block;
      System.arraycopy(c, 0, noteCell, 1, c.length);
      return this;
    }

    /**
     * Turned cell text (Wordcraft's turned cells, w:textDirection): 0 for horizontal, 1 for
     * top-to-bottom (tbRl, "down") and 2 for bottom-to-top (btLr, "up"). A turned line's x and y
     * are in its cell's unturned frame, whose origin is page point (turnX, turnY): frame point
     * (u, v) lands at (turnX - v, turnY + u) going down and (turnX + v, turnY - u) going up.
     */
    public int turn;

    public float turnX, turnY;

    Line turned(int value, float ox, float oy) {
      turn = value;
      turnX = ox;
      turnY = oy;
      return this;
    }

    /** The page point of frame point (u, v) of this line's frame (identity when not turned). */
    public float[] toPage(float u, float v) {
      if (turn == 1) return new float[] {turnX - v, turnY + u};
      if (turn == 2) return new float[] {turnX + v, turnY - u};
      return new float[] {u, v};
    }

    /** The inverse of {@link #toPage}: the frame point at page point (px, py). */
    public float[] toFrame(float px, float py) {
      if (turn == 1) return new float[] {py - turnY, turnX - px};
      if (turn == 2) return new float[] {turnY - py, px - turnX};
      return new float[] {px, py};
    }

    /** This line's page bounds as {x, y, w, h}. */
    public float[] pageBounds() {
      return pageRect(x, y, width, height);
    }

    /** The page rectangle {x, y, w, h} that frame rectangle (u, v, w, h) covers. */
    public float[] pageRect(float u, float v, float w, float h) {
      if (turn == 0) return new float[] {u, v, w, h};
      float[] a = toPage(u, v), b = toPage(u + w, v + h);
      return new float[] {
        Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1])
      };
    }
    public final String paragraphId, marker;
    public final float x, y, width, height;

    /** A right-to-left line: its list label sits right of the text (Wordcraft mirrors labels). */
    public final boolean rtl;

    /** Left edge of a list label {@code labelWidth} wide, 18pt before the line's start edge. */
    public float markerX(float labelWidth) {
      return rtl ? x + width + 18 - labelWidth : x - 18;
    }

    /** Vertical offset aligning the default-size list marker with the text baseline. */
    public final float markerOffset;

    public final List<Span> spans;
    public final boolean clipped;
    public final float clipHeight;

    /** Bar positions relative to x, independent of text alignment and first-line indentation. */
    public final List<Float> barTabs;

    Line(int b, int row, int col, String id, int page, float x, float y, RawLine r) {
      this(b, row, col, id, page, x, y, r, "");
    }

    Line(int b, int row, int col, String id, int page, float x, float y, RawLine r, String marker) {
      this(b, row, col, id, page, x, y, r, marker, Float.POSITIVE_INFINITY);
    }

    Line(
        int b,
        int row,
        int col,
        String id,
        int page,
        float x,
        float y,
        RawLine r,
        String marker,
        float visibleHeight) {
      this.marker = marker;
      markerOffset = r.markerOffset;
      rtl = r.mirrored;
      block = b;
      this.row = row;
      column = col;
      paragraphId = id;
      this.page = page;
      this.x = x;
      this.y = y;
      width = r.width;
      height = Math.min(r.height, visibleHeight);
      clipHeight = Math.min(visibleHeight, r.clipped ? r.height : Float.POSITIVE_INFINITY);
      clipped = clipHeight != Float.POSITIVE_INFINITY;
      start = r.start;
      end = r.end;
      spans = Collections.unmodifiableList(r.spans);
      barTabs =
          r.barTabs.isEmpty()
              ? Collections.<Float>emptyList()
              : Collections.unmodifiableList(r.barTabs);
    }
  }

  public static final class Box {
    public final int block, row, column, page;

    /** The nested cell's path inside cell (row, column), as in {@link Line#path}. */
    public int[] path = NO_PATH;

    Box at(int[] value) {
      path = value;
      return this;
    }
    public final float x, y, width, height;
    public final TableFormat.Cell format;

    Box(int b, int r, int c, int p, float x, float y, float w, float h, TableFormat.Cell format) {
      this.format = format;
      block = b;
      row = r;
      column = c;
      page = p;
      this.x = x;
      this.y = y;
      width = w;
      height = h;
    }
  }

  /**
   * Pictures in paint order: by drawing z, then document order. {@code front} selects the
   * in-front-of-text drawings painted after text; otherwise everything else.
   */
  public static List<PlacedPicture> byDepth(List<PlacedPicture> pictures, boolean front) {
    var out = new ArrayList<PlacedPicture>();
    for (PlacedPicture p : pictures) {
      if (front == "front".equals(p.picture.wrap())) out.add(p);
    }
    java.util.Collections.sort(
        out,
        new java.util.Comparator<PlacedPicture>() {
          public int compare(PlacedPicture a, PlacedPicture b) {
            return Float.compare(z(a), z(b));
          }
        });
    return out;
  }

  private static float z(PlacedPicture p) {
    var d = p.picture.drawing;
    return (d == null ? 0 : d.num("z", 0)) + ("behind".equals(p.picture.wrap()) ? -10000 : 0);
  }

  /**
   * A placed object. In a table cell {@code block} indexes the innermost cell's blocks and
   * {@code cell} is that cell's path from the body (block, row, column triples, as
   * Session.cellPath); {@code frame} is then the page position of its float reference
   * {originX, originY, cell content width}: the cell content's left and the anchor paragraph top.
   */
  public record PlacedPicture(
      Picture picture, int block, int page, float x, float y, float width, float height,
      int[] cell, float[] frame) {
    public PlacedPicture(Picture picture, int block, int page, float x, float y, float width, float height) {
      this(picture, block, page, x, y, width, height, NO_PATH, null);
    }

    public boolean inCell() {
      return cell.length > 0;
    }
  }

  /** One page's share of a bordered or shaded paragraph, in points. */
  public static final class Frame {
    public final float x, top, width, bottom;
    public final ParagraphBorders borders;
    public final int shading;

    /** False where the paragraph continues on another page or column (that side is open). */
    public final boolean first, last;

    Frame(float x, float top, float width, float bottom, ParagraphFormat f, boolean first, boolean last) {
      this(x, top, width, bottom, f.borders, f.shading, first, last);
    }

    public Frame(
        float x, float top, float width, float bottom, ParagraphBorders borders, int shading,
        boolean first, boolean last) {
      this.x = x;
      this.top = top;
      this.width = width;
      this.bottom = bottom;
      this.borders = borders;
      this.shading = shading;
      this.first = first;
      this.last = last;
    }
  }

  /** Section vertical alignment: moves a page's body content down by dy points. */
  private static List<Line> shiftLines(List<Line> lines, float dy) {
    return shiftLines(lines, 0, dy);
  }

  private static List<Line> shiftLines(List<Line> lines, float dx, float dy) {
    if (dx == 0 && dy == 0) return lines;
    var out = new ArrayList<Line>();
    for (Line line : lines) {
      var raw = new RawLine();
      raw.start = line.start;
      raw.end = line.end;
      raw.width = line.width;
      raw.height = line.height;
      raw.clipped = line.clipped;
      raw.markerOffset = line.markerOffset;
      raw.mirrored = line.rtl;
      raw.spans.addAll(line.spans);
      raw.barTabs = line.barTabs;
      out.add(
          new Line(line.block, line.row, line.column, line.paragraphId, line.page, line.x + dx,
              line.y + dy, raw, line.marker, line.clipHeight).at(line.path));
    }
    return out;
  }

  private static List<Box> shiftBoxes(List<Box> boxes, float dy) {
    return shiftBoxes(boxes, 0, dy);
  }

  private static List<Box> shiftBoxes(List<Box> boxes, float dx, float dy) {
    if (dx == 0 && dy == 0) return boxes;
    var out = new ArrayList<Box>();
    for (Box b : boxes)
      out.add(
          new Box(b.block, b.row, b.column, b.page, b.x + dx, b.y + dy, b.width, b.height, b.format)
              .at(b.path));
    return out;
  }

  private static float[] moved(float[] frame, float dx, float dy) {
    if (frame == null) return null;
    var out = frame.clone();
    out[0] += dx;
    if (out.length > 1) out[1] += dy;
    return out;
  }

  private static List<PlacedPicture> shiftPictures(List<PlacedPicture> pictures, float dy) {
    return shiftPictures(pictures, 0, dy);
  }

  private static List<PlacedPicture> shiftPictures(List<PlacedPicture> pictures, float dx, float dy) {
    if (dx == 0 && dy == 0) return pictures;
    var out = new ArrayList<PlacedPicture>();
    for (PlacedPicture p : pictures)
      out.add(
          new PlacedPicture(p.picture, p.block, p.page, p.x + dx, p.y + dy, p.width, p.height, p.cell,
              moved(p.frame, dx, dy)));
    return out;
  }

  public static final class Page {
    public final PageSpec spec;
    public final int section, sectionPage, number;
    public final List<Line> lines;
    public final List<Line> header, footer, notes;
    private final List<Line> all;

    public List<Line> allLines() {
      return all;
    }

    public final List<Box> boxes;
    public final List<PlacedPicture> pictures;

    /** Column separator lines as {x, top, bottom} in points. */
    public final List<float[]> columnRules = new ArrayList<>();

    /** Header and footer table cells and pictures; not hit-tested as body content. */
    public final List<Box> furnitureBoxes = new ArrayList<>();

    public final List<PlacedPicture> furniturePictures = new ArrayList<>();

    /** Body and header/footer cells, in paint order. */
    public List<Box> paintedBoxes() {
      if (furnitureBoxes.isEmpty()) return boxes;
      var out = new ArrayList<Box>(furnitureBoxes);
      out.addAll(boxes);
      return out;
    }

    /** Body and header/footer pictures; header/footer ones first, as Word paints them under. */
    public List<PlacedPicture> paintedPictures() {
      if (furniturePictures.isEmpty()) return pictures;
      var out = new ArrayList<PlacedPicture>(furniturePictures);
      out.addAll(pictures);
      return out;
    }

    /** Paragraph border/shading boxes, painted under the text. */
    public final List<Frame> frames = new ArrayList<>();

    /** Page color, watermark and page borders of this page's section. */
    public PageDecor decor = PageDecor.NONE;

    /** Margin line numbers as {y, number} in points, set from the section's line numbering. */
    public final List<float[]> lineNumbers = new ArrayList<>();

    Page(
        PageSpec spec,
        int section,
        int sectionPage,
        int number,
        List<Line> lines,
        List<Box> boxes,
        List<PlacedPicture> pictures) {
      this(
          spec,
          section,
          sectionPage,
          number,
          lines,
          boxes,
          pictures,
          Collections.<Line>emptyList(),
          Collections.<Line>emptyList(),
          Collections.<Line>emptyList());
    }

    Page(
        PageSpec spec,
        int section,
        int sectionPage,
        int number,
        List<Line> lines,
        List<Box> boxes,
        List<PlacedPicture> pictures,
        List<Line> header,
        List<Line> footer,
        List<Line> notes) {
      this.spec = spec;
      this.section = section;
      this.sectionPage = sectionPage;
      this.number = number;
      this.lines = Collections.unmodifiableList(lines);
      this.boxes = Collections.unmodifiableList(boxes);
      this.pictures = Collections.unmodifiableList(pictures);
      this.header = header;
      this.footer = footer;
      this.notes = Collections.unmodifiableList(notes);
      if (header.isEmpty() && footer.isEmpty() && notes.isEmpty()) all = this.lines;
      else {
        var merged = new ArrayList<Line>(header);
        merged.addAll(lines);
        merged.addAll(notes);
        merged.addAll(footer);
        all = Collections.unmodifiableList(merged);
      }
    }

    /** First line of each note area on this page, used for shared separator rendering. */
    public List<Line> noteAreas() {
      var result = new ArrayList<Line>();
      if (!notes.isEmpty()) result.add(notes.get(0));
      boolean inNotes = false;
      for (Line line : lines) {
        if (line.block == -2 && !inNotes) result.add(line);
        inNotes = line.block == -2;
      }
      return result;
    }
  }

  public static final class Result {
    public final List<Page> pages;
    public final int measuredParagraphs;
    public final long revision;

    /** Immutable view with page-dependent note labels resolved for this pagination. */
    public final Document document;

    public final List<String> warnings;
    private final float[] heights;
    public final float maxWidth;

    public float heightBefore(int page) {
      return heights[page];
    }

    Result(Document document, List<Page> p, int count, long rev, Set<String> warnings) {
      this.document = document;
      this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
      pages = Collections.unmodifiableList(p);
      measuredParagraphs = count;
      revision = rev;
      heights = new float[p.size() + 1];
      float width = 0;
      for (int i = 0; i < p.size(); i++) {
        heights[i + 1] = heights[i] + p.get(i).spec.height;
        width = Math.max(width, p.get(i).spec.width);
      }
      maxWidth = width;
    }
  }

  private static final String[] ASCII = new String[128];

  static {
    for (int i = 0; i < ASCII.length; i++) ASCII[i] = String.valueOf((char) i);
  }

  /**
   * The page rectangle {x, y, width, height} of frame rectangle (u, v, w, h) of a turned cell
   * whose frame origin is {@code at}: frame (u, v) lands at (ox - v, oy + u) going down (turn 1)
   * and (ox + v, oy - u) going up (turn 2).
   */
  static float[] turnRect(int turn, float[] at, float u, float v, float w, float h) {
    if (turn == 1) return new float[] {at[0] - v - h, at[1] + u, h, w};
    return new float[] {at[0] + v, at[1] - u - w, h, w};
  }

  /** The longest line turned cell text gets when its row height isn't exact (as Wordcraft). */
  static final float TURNED_MAX = 648;

  /** The longest line turned cell text ever gets (Word's largest page, as Wordcraft). */
  static final float TURNED_LIMIT = 1584;

  private static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static final class RawLine {
    final List<Span> spans = new ArrayList<>();
    List<Float> barTabs = Collections.emptyList();
    int start, end;
    float width, height, markerOffset;
    boolean clipped;

    /** Extra left offset beside a drop cap, and the baseline from the line top. */
    float lead, baseline;

    /**
     * Beside floating objects (Wordcraft's per-line exclusions): the right cut narrowing this line
     * (the left one is added to lead) and the gap above it where a float blocked the whole width.
     */
    float cutRight, skip;

    /**
     * Text on both sides of a float (Wordcraft's row spans): the character offsets where the line
     * jumps over a float and the x (from the line's text start) it resumes at. Null: one segment.
     */
    int[] gapAt;
    float[] gapX;

    /** The first line of a drop-capped paragraph carries the large letter and its x offset. */
    RawLine drop;

    float dropX;

    /** Laid out from the right edge (a right-to-left paragraph), so its list label sits right. */
    boolean mirrored;
  }

  private static final class Cached {
    final float width;
    final List<RawLine> lines;
    final boolean drop;

    Cached(float w, List<RawLine> l, boolean drop) {
      width = w;
      lines = l;
      this.drop = drop;
    }
  }

  private final MeasurementCache metrics;
  private final Map<String, Equation.Box> mathBoxes = new HashMap<>();

  /**
   * The built-up box of the inline equation field at {@code at}, or null when none is there. An
   * inline equation is one unbreakable object on the text line: its width is the box width and
   * its baseline sits on the line's baseline (as in Wordcraft, it never wraps internally).
   */
  Equation.Box inlineMath(Paragraph p, int at, TextStyle s) {
    var f = p.text.fieldAt(at);
    if (!Equation.inline(f) || f.showCode) return null;
    String key = s.size + "\u0001" + Equation.inlineText(f);
    var box = mathBoxes.get(key);
    if (box == null) {
      if (mathBoxes.size() > 512) mathBoxes.clear();
      box = Equation.layout(Equation.fromLinear(Equation.inlineText(f)), s.size, metrics);
      mathBoxes.put(key, box);
    }
    return box;
  }

  private Map<Paragraph, Cached> cache = new HashMap<>();
  private Map<Paragraph, Cached> nextCache;
  // Same lines keyed by content: a reopened or reverted document has new paragraph objects
  // with unchanged text and format, and these reuse their measured lines.
  private Map<Content, Cached> byContent = new HashMap<>();
  private Map<Content, Cached> nextByContent;

  /** What line breaking reads from a paragraph without fields, notes or tab stops. */
  private static final class Content {
    final String text, align, lineRule, dropCap, asianOff;
    final int[] starts;
    final TextStyle[] styles;
    final float indent, lineSpacing, rightIndent, firstIndent, tabInterval;
    final int listLevel;
    final boolean list, rtl;
    final int hash;

    Content(Paragraph p, String text) {
      this.text = text;
      align = p.align;
      lineRule = p.format.lineRule;
      dropCap = p.format.dropCap;
      asianOff = p.format.asianOff;
      indent = p.indent;
      lineSpacing = p.lineSpacing;
      rightIndent = p.format.rightIndent;
      firstIndent = p.format.firstIndent;
      tabInterval = p.format.tabInterval;
      listLevel = p.listLevel;
      list = !p.list.isEmpty();
      rtl = p.format.rtl;
      var at = new ArrayList<Integer>();
      var st = new ArrayList<TextStyle>();
      p.text.visit((offset, t, style) -> { at.add(offset); st.add(style); });
      starts = new int[at.size()];
      for (int i = 0; i < starts.length; i++) starts[i] = at.get(i);
      styles = st.toArray(new TextStyle[0]);
      int h = text.hashCode();
      h = 31 * h + Arrays.hashCode(starts);
      h = 31 * h + Arrays.hashCode(styles);
      h = 31 * h + Float.floatToIntBits(indent) + 7 * listLevel;
      hash = h;
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Content)) return false;
      var c = (Content) o;
      return hash == c.hash && text.equals(c.text) && Arrays.equals(starts, c.starts)
          && Arrays.equals(styles, c.styles) && Objects.equals(align, c.align)
          && Objects.equals(lineRule, c.lineRule) && Objects.equals(dropCap, c.dropCap)
          && Objects.equals(asianOff, c.asianOff) && indent == c.indent
          && lineSpacing == c.lineSpacing && rightIndent == c.rightIndent
          && firstIndent == c.firstIndent && tabInterval == c.tabInterval
          && listLevel == c.listLevel && list == c.list && rtl == c.rtl;
    }
  }
  /** Automatic hyphenation ("hyphenation" document setting) and its capitalized-word option. */
  private boolean hyphenate, hyphenateCaps = true;
  /** Shown hidden text (Display > Hidden text or formatting marks) is laid out with its width, as Wordcraft lays it out unless it is hidden. */
  private boolean showHidden;

  public void setShowHidden(boolean value) {
    if (value == showHidden) return;
    showHidden = value;
    metrics.clear();
    cache = new HashMap<>();
    byContent = new HashMap<>();
  }

  private boolean hides(TextStyle s) {
    return s.characters.hidden && !showHidden;
  }

  private int measured;

  public LayoutEngine(Metrics m) {
    metrics = new MeasurementCache(m);
  }

  public void clear() {
    cache.clear();
    byContent.clear();
    metrics.clear();
    numberedSource = numberedView = null;
  }

  /** Resolve the same column geometry used by painting, hit testing and PDF export. */
  public float[] tableWidths(Table table, float available) {
    var f = table.format;
    float[] widths = new float[table.columns()];
    float target = f.preferredWidth(available);
    if (f.sizing.fixed) {
      for (int c = 0; c < widths.length; c++) widths[c] = target * f.columnFraction(c);
      return widths;
    }
    float[] min = new float[widths.length], max = new float[widths.length];
    var spans = new ArrayList<float[]>();
    for (int r = 0; r < table.rows(); r++)
      for (int c = 0; c < table.columns(); c++) {
        if (table.covered(r, c)) continue;
        var span = table.span(r, c);
        float smallest = 1, largest = 1, padding = 2 * f.cell(r, c).horizontalPadding;
        for (Paragraph p : table.cell(r, c)) {
          float indents =
              p.indent
                  + p.format.rightIndent
                  + Math.max(0, p.format.firstIndent)
                  + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1));
          var text = p.text.toString();
          float word = 0, line = 0;
          for (int a = 0; a < text.length(); ) {
            int b = Graphemes.next(p.text, a);
            char ch = text.charAt(a);
            float advance =
                ch == '\n'
                    ? 0
                    : ch == '\t'
                        ? tabWidth(p, text, a, line, p.format.firstIndent)
                        : clusterWidth(p, text, a, b);
            if (Character.isWhitespace(ch)) {
              smallest = Math.max(smallest, word + indents);
              word = 0;
            } else word += advance;
            if (ch == '\n') {
              largest = Math.max(largest, line + indents);
              line = 0;
            } else line += advance;
            a = b;
          }
          largest = Math.max(largest, line + indents);
          smallest = Math.max(smallest, word + indents);
        }
        smallest = Math.min(10000, Math.max(1, smallest + padding));
        largest = Math.min(10000, Math.max(smallest, largest + padding));
        if (span.columns == 1) {
          min[c] = Math.max(min[c], smallest);
          max[c] = Math.max(max[c], largest);
        } else spans.add(new float[] {c, span.columns, smallest, largest});
      }
    for (float[] span : spans) {
      int start = (int) span[0], end = start + (int) span[1];
      float haveMin = 0, haveMax = 0;
      for (int c = start; c < end; c++) {
        haveMin += min[c];
        haveMax += max[c];
      }
      for (int c = start; c < end; c++) {
        min[c] += Math.max(0, span[2] - haveMin) / span[1];
        max[c] += Math.max(0, span[3] - haveMax) / span[1];
      }
    }
    float sumMin = 0, sumMax = 0;
    for (int c = 0; c < widths.length; c++) {
      min[c] = Math.max(1, min[c]);
      max[c] = Math.max(min[c], max[c]);
      sumMin += min[c];
      sumMax += max[c];
    }
    if ("auto".equals(f.sizing.unit))
      target = Math.min(sumMax, Math.max(1, available - Math.max(0, f.sizing.indent)));
    // Keep the table within its requested width even for unbreakable text. The shared
    // paragraph layout can wrap at grapheme boundaries when a word is wider than a cell.
    if (sumMin >= target) {
      for (int c = 0; c < widths.length; c++) widths[c] = target * min[c] / sumMin;
    } else if ("auto".equals(f.sizing.unit)) {
      float ratio = sumMax == sumMin ? 0 : Math.min(1, (target - sumMin) / (sumMax - sumMin));
      for (int c = 0; c < widths.length; c++) widths[c] = min[c] + ratio * (max[c] - min[c]);
    } else {
      // Retain the preferred grid where possible and redistribute only cells too narrow
      // for their words. Window AutoFit starts with equal preferred grid columns.
      float remaining = target, weights = 1;
      boolean[] assigned = new boolean[widths.length];
      boolean changed;
      do {
        changed = false;
        for (int c = 0; c < widths.length; c++)
          if (!assigned[c] && remaining * f.columnFraction(c) / weights < min[c]) {
            widths[c] = min[c];
            remaining -= min[c];
            weights -= f.columnFraction(c);
            assigned[c] = true;
            changed = true;
          }
      } while (changed);
      for (int c = 0; c < widths.length; c++)
        if (!assigned[c]) widths[c] = remaining * f.columnFraction(c) / weights;
    }
    return widths;
  }

  /**
   * The floats beside a body paragraph being broken into lines: for a line at top (from the
   * paragraph's first line) about height tall, {left cut, right cut, top} within the column, the
   * top moved down when a float leaves no room there. Null outside floats.
   */
  interface Band {
    float[] cut(float top, float height);
  }

  private Band band;

  /**
   * A float band seen from the right edge (Wordcraft mirrors exclusions into a right-to-left
   * paragraph): the cuts swap sides and each gap [s, e] becomes [width - e, width - s].
   */
  static float[] mirrorCut(float[] cut, float width) {
    var out = cut.clone();
    out[0] = cut[1];
    out[1] = cut[0];
    int g = (cut.length - 3) / 2;
    for (int k = 0; k < g; k++) {
      out[3 + k * 2] = width - cut[4 + (g - 1 - k) * 2];
      out[4 + k * 2] = width - cut[3 + (g - 1 - k) * 2];
    }
    return out;
  }

  private static boolean gapStart(RawLine line, int offset) {
    if (line.gapAt != null) for (int at : line.gapAt) if (at == offset) return true;
    return false;
  }

  private List<RawLine> lines(Paragraph p, float width) {
    return lines(p, width, false);
  }

  /** Body paragraphs honour drop caps; table cells, notes and frames lay the letter out inline. */
  private List<RawLine> lines(Paragraph p, float width, boolean allowDrop) {
    width =
        Math.max(
            1,
            width
                - p.indent
                - p.format.rightIndent
                - (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1)));
    var c = nextCache.get(p);
    if (c == null) c = cache.get(p);
    Content content = null;
    if (c == null && !p.text.hasObjects() && p.format.tabs.isEmpty()) {
      content = new Content(p, p.text.toString());
      c = nextByContent.get(content);
      if (c == null) c = byContent.get(content);
    }
    boolean dropped = allowDrop && !p.format.dropCap.isEmpty() && p.text.length() > 0
        && !Character.isWhitespace(p.text.charAt(0)) && !p.text.objectAt(0);
    if (c != null && c.width == width && c.drop == dropped && band == null) {
      nextCache.put(p, c);
      if (content != null) nextByContent.put(content, c);
      return c.lines;
    }
    measured++;
    var out = new ArrayList<RawLine>();
    var text = p.text.toString();
    int length = text.length();
    var st = new TextStyle[length];
    var obj = new boolean[length];
    p.text.fill(st, obj);
    int skip = 0, dropLines = 0;
    float dropWidth = 0, letterWidth = 0;
    TextStyle dropStyle = null;
    if (dropped) {
      skip = Graphemes.next(p.text, 0);
      var base = p.text.styleAt(0);
      dropLines = p.format.dropLines();
      float spacing = "auto".equals(p.format.lineRule) ? p.lineSpacing : 1;
      // Cap height (about 0.7 em) spans from the first line's top to the last dropped baseline.
      float size = base.size * ((dropLines - 1) * 1.2f * spacing + .9f) / .7f;
      dropStyle = base.with("size", String.valueOf(Math.round(size * 2) / 2f));
      if (!p.format.dropFont().isEmpty()) dropStyle = dropStyle.with("font", p.format.dropFont());
      letterWidth = metrics.width(text.substring(0, skip), dropStyle);
      dropWidth = "drop".equals(p.format.dropMode()) ? letterWidth + p.format.dropDistance() : 0;
    }
    byte[] levels = Bidi.needed(text, p.format.rtl) ? Bidi.levels(text, p.format.rtl) : null;
    int start = 0;
    float bandY = 0;
    do {
      var line = new RawLine();
      float[] gapS = null, gapE = null;
      if (out.size() < dropLines) line.lead = dropWidth;
      if (band != null) {
        float left = p.indent + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1)) + (start == 0 ? p.format.firstIndent : 0);
        float est = metrics.height(TextStyle.DEFAULT);
        for (int guard = 0; guard < 64; guard++) {
          float[] cut = band.cut(bandY, est);
          if (p.format.rtl) cut = mirrorCut(cut, width);
          if (cut[2] <= bandY) {
            line.lead += Math.max(0, cut[0] - left);
            line.cutRight = Math.max(0, cut[1] - p.format.rightIndent);
            if (cut.length > 3) {
              // Gaps in column coordinates, made relative to this line's text start.
              float origin = left + line.lead;
              int g = (cut.length - 3) / 2;
              gapS = new float[g];
              gapE = new float[g];
              for (int k = 0; k < g; k++) {
                gapS[k] = cut[3 + k * 2] - origin;
                gapE[k] = cut[4 + k * 2] - origin;
              }
            }
            break;
          }
          line.skip += cut[2] - bandY;
          bandY = cut[2];
        }
      }
      float available = Math.max(1, width - (start == 0 ? p.format.firstIndent : 0) - line.lead - line.cutRight);
      line.start = start;
      line.height = metrics.height(TextStyle.DEFAULT);
      int end = start == 0 ? skip : start, lastBreak = -1;
      boolean hyphenated = false;
      float x = 0, xBreak = 0, xSeg = 0;
      int seg = 0, segStart = end;
      int[] jumps = gapS == null ? null : new int[gapS.length];
      while (end < text.length()) {
        char first = text.charAt(end);
        // ASCII followed by ASCII is always its own cluster (CR LF aside): skip the rules.
        int count =
            first < 128 && first != '\r' && (end + 1 == length || text.charAt(end + 1) < 128)
                ? 1
                : Graphemes.next(p.text, end) - end;
        String ch =
            count == 1 && text.charAt(end) < 128
                ? ASCII[text.charAt(end)]
                : text.substring(end, end + count);
        if ("\n".equals(ch) && !hides(st[end])) {
          break;
        }
        float w =
            hides(st[end])
                ? 0
                : "\t".equals(ch)
                    ? tabWidth(p, text, end, x, start == 0 ? p.format.firstIndent : 0)
                    : clusterWidth(p, text, end, end + count, st, obj);
        if (jumps != null && seg < gapS.length && x + w > gapS[seg] && x + w <= available) {
          // Past this segment: the words since its last break move beyond the float.
          int from = lastBreak > segStart ? lastBreak : segStart;
          float fromX = lastBreak > segStart ? xBreak : xSeg;
          jumps[seg] = from;
          x = gapE[seg] + (x - fromX);
          xSeg = gapE[seg];
          segStart = from;
          seg++;
          continue;
        }
        if (x + w > available && end > start) {
          int soft = hyphenate ? hyphenPoint(p, text, start, Math.max(lastBreak, start), end, available) : -1;
          if (soft > 0) {
            end = soft;
            hyphenated = true;
          } else if (lastBreak > start) end = lastBreak;
          break;
        }
        x += w;
        end += count;
        if (Character.isWhitespace(ch.charAt(0))) {
          lastBreak = end;
          xBreak = x;
        }
      }
      if (jumps != null && seg > 0) {
        line.gapAt = new int[seg];
        line.gapX = new float[seg];
        for (int k = 0; k < seg; k++) {
          line.gapAt[k] = jumps[k];
          line.gapX[k] = gapE[k];
        }
      }
      if (end == start && end < text.length() && text.charAt(end) != '\n')
        end = Graphemes.next(p.text, end);
      line.end = end;
      float pos = 0;
      int a = start == 0 ? skip : start;
      int nextGap = 0;
      while (a < end) {
        var s = st[a];
        while (line.gapAt != null && nextGap < line.gapAt.length && line.gapAt[nextGap] <= a)
          pos = Math.max(pos, line.gapX[nextGap++]);
        int segStop = line.gapAt != null && nextGap < line.gapAt.length ? line.gapAt[nextGap] : end;
        int b = a + 1;
        while (b < end
            && b != segStop
            && !obj[a]
            && !obj[b]
            && text.charAt(a) != '\t'
            && text.charAt(b) != '\t'
            && (!s.characters.smallCaps
                || s.characters.allCaps
                || !Graphemes.isBoundary(p.text, b)
                || Character.isLowerCase(text.charAt(a))
                    == Character.isLowerCase(text.charAt(b)))
            && s.equals(st[b])
            && (levels == null || levels[a] == levels[b])) b++;
        var chunk = text.substring(a, b);
        var display = p.text.displayAt(a, chunk);
        float w =
            hides(s)
                ? 0
                : "\t".equals(chunk)
                    ? tabWidth(p, text, a, pos, start == 0 ? p.format.firstIndent : 0)
                    : obj[a] ? objectWidth(p, a, display, s) : metrics.width(display, s);
        var math = obj[a] ? inlineMath(p, a, s) : null;
        if (math != null) {
          display = "";
          if (!hides(s)) w = math.width;
        }
        if (hyphenated && b == end) {
          display += "-";
          w += metrics.width("-", s);
        }
        if ("\t".equals(chunk)) {
          var stop = nextTab(p, pos + (start == 0 ? p.format.firstIndent : 0));
          display = stop == null ? "" : leader(stop.glyph(), s, w);
        }
        var pieces = obj[a] && math == null && !hides(s) ? fieldPieces(p, a) : null;
        if (pieces == null) line.spans.add(new Span(chunk, s, a, pos, w, 0, display));
        else {
          // A field whose result carries formatted runs (a Zotero citation's italics, small caps,
          // superscripts) draws each run in its own style. The first span owns the field's offset
          // and its whole width (caret and selection); the rest are zero-length, drawing only.
          float px = pos;
          for (int k = 0; k < pieces.size(); k++) {
            var piece = pieces.get(k);
            var ps = piece.style(s);
            float pw = metrics.width(piece.text, ps);
            line.spans.add(k == 0 ? new Span(chunk, ps, a, px, w, 0, piece.text) : new Span("", ps, a + 1, px, pw, 0, piece.text));
            px += pw;
            line.height = Math.max(line.height, metrics.height(ps));
          }
        }
        pos += w;
        if (!hides(s)) line.height = Math.max(line.height, metrics.height(s));
        a = b;
      }
      line.width = pos;
      boolean softWrapped = end < text.length() && text.charAt(end) != '\n';
      if (line.gapAt != null) {
        // Segments around a float stay left-aligned in place (no justification across the gap).
      } else if (("both".equals(p.align) || "justify".equals(p.align)) && softWrapped)
        justify(line, available, false, p.text);
      else if ("distribute".equals(p.align) && end > start) justify(line, available, true, p.text);
      // UAX #9 L1: a tab is a segment separator at paragraph level, so each tab-delimited segment is
      // reordered within its own extent and the tabs (and their stops) stay put.
      // A right-to-left paragraph is laid out from its start edge as if mirrored (Wordcraft
      // para.rs break_lines): tabs, indents and alignment are measured from the right, so each
      // tab-delimited segment and each tab moves to its mirrored extent before reordering.
      // Lines beside floats (mirrored cuts above) and beside a drop cap mirror too, as Wordcraft
      // lays the whole paragraph out from its start edge.
      boolean mirror = p.format.rtl;
      line.mirrored = mirror;
      float lineW = line.width;
      if (mirror) for (Span sp : line.spans) lineW = Math.max(lineW, sp.x + sp.width);
      if (levels != null || mirror) {
        int from = 0;
        for (int i = 0; i <= line.spans.size(); i++)
          if (i == line.spans.size()
              || "\t".equals(line.spans.get(i).text)
              || mirror && i > from && gapStart(line, line.spans.get(i).start)) {
            if (i > from) {
              var segment = line.spans.subList(from, i);
              if (mirror) {
                var last = segment.get(segment.size() - 1);
                segment.set(0, segment.get(0).placed(lineW - (last.x + last.width), true));
                if (levels == null) {
                  float sx = segment.get(0).x;
                  for (int k = 0; k < segment.size(); k++) {
                    segment.set(k, segment.get(k).placed(sx, true));
                    sx += segment.get(k).width;
                  }
                }
              }
              if (levels != null) Bidi.reorder(segment, levels, p.format.rtl);
            }
            if (mirror && i < line.spans.size() && "\t".equals(line.spans.get(i).text)) {
              var tab = line.spans.get(i);
              line.spans.set(i, tab.placed(lineW - tab.x - tab.width, true));
            }
            // A float gap starts the next segment with this span; a tab is its own.
            from = i < line.spans.size() && !"\t".equals(line.spans.get(i).text) ? i : i + 1;
          }
        if (mirror) {
          java.util.Collections.sort(line.spans, (u, v) -> Float.compare(u.x, v.x));
          line.width = lineW;
        }
      }
      float textOffset = start == 0 ? p.format.firstIndent : 0;
      if (line.gapAt != null) {
      } else if ("center".equals(p.align)) textOffset += (available - line.width) / 2;
      else if ("right".equals(p.align)) textOffset += available - line.width;
      for (TabStop stop : p.format.tabs)
        if ("bar".equals(stop.alignment)) {
          if (line.barTabs.isEmpty()) line.barTabs = new ArrayList<>();
          line.barTabs.add(mirror ? lineW - (stop.position - textOffset) : stop.position - textOffset);
        }
      float baseline = metrics.ascent(TextStyle.DEFAULT);
      for (Span span : line.spans)
        if (!hides(span.style))
          baseline =
              Math.max(
                  baseline,
                  metrics.ascent(span.style.renderedStyle()) + span.style.baselineShift());
      // Inline equations rise above and hang below the text by their box's ascent and descent.
      float mathDescent = 0;
      for (Span span : line.spans)
        if (!hides(span.style) && InlineField.OBJECT.equals(span.text)) {
          var math = inlineMath(p, span.start, span.style);
          if (math == null) continue;
          baseline = Math.max(baseline, math.ascent);
          mathDescent = Math.max(mathDescent, math.descent);
        }
      for (int i = 0; i < line.spans.size(); i++) {
        var span = line.spans.get(i);
        float top = baseline - metrics.ascent(span.glyphStyle) - span.style.baselineShift();
        line.spans.set(i, span.top(top));
        if (!hides(span.style))
          line.height = Math.max(line.height, (top + metrics.height(span.glyphStyle)));
      }
      line.height = Math.max(line.height, baseline + mathDescent);
      line.markerOffset = baseline - metrics.ascent(TextStyle.DEFAULT);
      line.baseline = baseline;
      float natural = line.height;
      if ("auto".equals(p.format.lineRule)) line.height *= p.lineSpacing;
      else {
        line.height =
            "exact".equals(p.format.lineRule) ? p.lineSpacing : Math.max(natural, p.lineSpacing);
        line.clipped = "exact".equals(p.format.lineRule);
        // OOXML: center text when the fixed box is larger; bottom-align and
        // clip from the top when it is smaller than the natural text height.
        float offset = line.height < natural ? line.height - natural : (line.height - natural) / 2;
        line.markerOffset += offset;
        for (int i = 0; i < line.spans.size(); i++) {
          var span = line.spans.get(i);
          line.spans.set(i, span.top(span.top + offset));
        }
      }
      out.add(line);
      bandY += line.height;
      start = end;
      if (start < text.length() && text.charAt(start) == '\n') start++;
    } while (start < text.length());
    if (dropped) {
      int n = Math.min(dropLines, out.size());
      float y = 0;
      for (int i = 0; i < n - 1; i++) y += out.get(i).height;
      var letter = new RawLine();
      letter.start = 0;
      letter.end = skip;
      letter.width = letterWidth;
      float top = y + out.get(n - 1).baseline - metrics.ascent(dropStyle);
      letter.spans.add(new Span(text.substring(0, skip), dropStyle, 0, 0, letterWidth, top));
      var first = out.get(0);
      first.drop = letter;
      first.dropX = "margin".equals(p.format.dropMode()) ? -(letterWidth + p.format.dropDistance()) : 0;
      // A paragraph shorter than the drop still clears the letter.
      float bottom = top + metrics.height(dropStyle) * .8f;
      if (out.size() < dropLines && y + out.get(n - 1).height < bottom)
        out.get(n - 1).height = bottom - y;
    }
    if (band != null) return out;
    c = new Cached(width, out, dropped);
    nextCache.put(p, c);
    if (content != null) nextByContent.put(content, c);
    return out;
  }

  /**
   * The last hyphenation point of the word that overflows the line (it starts at {@code from},
   * {@code overflow} is the first cluster that did not fit) where the text plus a hyphen still
   * fits, or -1. Only plain letter runs are hyphenated.
   */
  private int hyphenPoint(Paragraph p, String text, int lineStart, int from, int overflow, float available) {
    int we = overflow;
    while (we < text.length() && Unicode.letter(text.charAt(we))) we++;
    int ws = from;
    if (overflow >= text.length() || !Unicode.letter(text.charAt(overflow))) return -1;
    for (int i = ws; i < we; i++) {
      if (!Unicode.letter(text.charAt(i)) && text.charAt(i) != '\'') return -1;
      if (p.text.objectAt(i) || hides(p.text.styleAt(i))) return -1;
    }
    var word = text.substring(ws, we);
    if (!hyphenateCaps && Character.isUpperCase(word.charAt(0))) return -1;
    int[] points = Hyphenator.points(word);
    for (int k = points.length - 1; k >= 0; k--) {
      int at = ws + points[k];
      if (at <= lineStart || at > overflow) continue;
      float w = 0;
      for (int a = lineStart; a < at; ) {
        int count = Graphemes.next(p.text, a) - a;
        w +=
            "\t".equals(text.substring(a, a + count))
                ? 0
                : clusterWidth(p, text, a, a + count);
        a += count;
      }
      if (text.indexOf('\t', lineStart) >= 0 && text.indexOf('\t', lineStart) < at) return -1;
      if (w + metrics.width("-", p.text.styleAt(at - 1)) <= available) return at;
    }
    return -1;
  }

  // Keep a cluster on one line even when a formatting boundary divides its glyphs.
  private float clusterWidth(Paragraph p, String text, int start, int end) {
    if (end == start + 1) {
      char c = text.charAt(start);
      if (c < 128 && !p.text.objectAt(start) && p.text.displayAt(start, ASCII[c]) == ASCII[c])
        return metrics.ascii(c, ASCII[c], p.text.styleAt(start));
    }
    float width = 0;
    for (int at = start; at < end; ) {
      var style = p.text.styleAt(at);
      int next = at + 1;
      while (next < end
          && !p.text.objectAt(at)
          && !p.text.objectAt(next)
          && style.equals(p.text.styleAt(next))) next++;
      width += p.text.objectAt(at) ? objectWidth(p, at, p.text.displayAt(at, text.substring(at, next)), style)
          : metrics.width(p.text.displayAt(at, text.substring(at, next)), style);
      at = next;
    }
    return width;
  }

  /** As above with the paragraph's per-character styles and object flags already resolved. */
  private float clusterWidth(
      Paragraph p, String text, int start, int end, TextStyle[] st, boolean[] obj) {
    if (end == start + 1) {
      char c = text.charAt(start);
      // Plain Latin characters: a per-style table, no substring or hash lookup.
      if (c < 128 && !obj[start]) return metrics.ascii(c, ASCII[c], st[start]);
    }
    float width = 0;
    for (int at = start; at < end; ) {
      var style = st[at];
      int next = at + 1;
      while (next < end && !obj[at] && !obj[next] && style.equals(st[next])) next++;
      var math = obj[at] ? inlineMath(p, at, style) : null;
      if (math != null) {
        width += math.width;
        at = next;
        continue;
      }
      width += p.text.objectAt(at) ? objectWidth(p, at, p.text.displayAt(at, text.substring(at, next)), style)
          : metrics.width(p.text.displayAt(at, text.substring(at, next)), style);
      at = next;
    }
    return width;
  }

  /** The pieces of a field at {@code at} with formatted runs inside its result, else null. */
  private static java.util.List<InlineField.Piece> fieldPieces(Paragraph p, int at) {
    var field = p.text.fieldAt(at);
    return field == null ? null : field.pieces();
  }

  /** An inline object's width: a formatted field sums its runs, each in its own style. */
  private float objectWidth(Paragraph p, int at, String display, TextStyle style) {
    var pieces = fieldPieces(p, at);
    if (pieces == null) return metrics.width(display, style);
    float w = 0;
    for (var piece : pieces) w += metrics.width(piece.text, piece.style(style));
    return w;
  }

    private TabStop nextTab(Paragraph p, float position) {
    for (TabStop tab : p.format.tabs)
      if (!"bar".equals(tab.alignment) && tab.position > position + .01f)
        return p.format.firstIndent < 0 && position < -.01f && tab.position > 0
            ? new TabStop(0, "left", "none") : tab;
    // A hanging first line has an implicit left stop at the paragraph indent, ahead of the default
    // stops (Wordcraft layout/para.rs next_tab hanging_at).
    return p.format.firstIndent < 0 && position < -.01f ? new TabStop(0, "left", "none") : null;
  }

  private float tabWidth(Paragraph p, String text, int offset, float x, float origin) {
    float position = x + origin;
    var tab = nextTab(p, position);
    if (tab == null)
      return p.format.tabInterval
          - ((position % p.format.tabInterval + p.format.tabInterval) % p.format.tabInterval);
    float following = 0;
    if (!"left".equals(tab.alignment)) {
      for (int i = offset + 1;
          i < text.length() && text.charAt(i) != '\t' && text.charAt(i) != '\n'; ) {
        if ("decimal".equals(tab.alignment) && text.charAt(i) == '.') break;
        int end = i + Character.charCount(Character.codePointAt(text, i));
        var field = p.text.fieldAt(i);
        String followingText = field == null ? text.substring(i, end) : field.display();
        boolean decimal = "decimal".equals(tab.alignment) && followingText.indexOf('.') >= 0;
        if (decimal) followingText = followingText.substring(0, followingText.indexOf('.'));
        following += field != null && !decimal ? objectWidth(p, i, followingText, p.text.styleAt(i))
            : metrics.width(followingText, p.text.styleAt(i));
        if (decimal) break;
        i = end;
      }
      if ("center".equals(tab.alignment)) following /= 2;
    }
    return Math.max(0, tab.position - position - following);
  }

  private String leader(String glyph, TextStyle style, float width) {
    if (glyph.isEmpty()) return "";
    float unit = metrics.width(glyph, style);
    if (unit <= 0) return "";
    int count = Math.min(4096, (int) (width / unit));
    var out = new StringBuilder(count);
    for (int i = 0; i < count; i++) out.append(glyph);
    return out.toString();
  }

  private void justify(RawLine line, float available, boolean distributed, RichText source) {
    for (Span span : line.spans)
      if ("\t".equals(span.text)
          || InlineField.OBJECT.equals(span.text) && !TextNavigation.isMark(source, span.start))
        return;
    var units = new ArrayList<Span>();
    int lastContent = line.start;
    for (Span span : line.spans) {
      if (InlineField.OBJECT.equals(span.text)) {
        // A zero-width range-field mark (citation start/end) stays in place and takes no gap.
        units.add(new Span(span.text, span.style, span.start, 0, span.width, span.top, span.rawDisplay));
        continue;
      }
      for (int i = 0; i < span.text.length(); ) {
        int count =
            Math.min(
                span.text.length() - i, Graphemes.next(source, span.start + i) - span.start - i);
        if (!distributed && span.text.charAt(i) != ' ' && span.text.charAt(i) != '\t')
          while (i + count < span.text.length()
              && span.text.charAt(i + count) != ' '
              && span.text.charAt(i + count) != '\t') count++;
        var text = span.text.substring(i, i + count);
        units.add(
            new Span(
                text,
                span.style,
                span.start + i,
                0,
                "\t".equals(text) ? span.width : metrics.width(text, span.style)));
        if (!Character.isWhitespace(text.charAt(0))) lastContent = span.start + i + count;
        i += count;
      }
    }
    int gaps = 0;
    float natural = 0;
    for (Span unit : units) {
      natural += unit.width;
      if (unit.end < lastContent
          && Graphemes.isBoundary(source, unit.end)
          && (distributed || " ".equals(unit.text))) gaps++;
    }
    if (gaps == 0 || natural >= available) return;
    float extra = (available - natural) / gaps, x = 0;
    line.spans.clear();
    for (Span unit : units) {
      float w = unit.width;
      if (unit.end < lastContent
          && Graphemes.isBoundary(source, unit.end)
          && (distributed || " ".equals(unit.text))) w += extra;
      line.spans.add(
          InlineField.OBJECT.equals(unit.text)
              ? new Span(unit.text, unit.style, unit.start, x, w, unit.top, unit.rawDisplay)
              : new Span(unit.text, unit.style, unit.start, x, w));
      x += w;
    }
    line.width = x;
  }

  private Document numberedSource, numberedView;

  private static boolean pageRestart(Document doc) {
    for (Section.Range section : doc.sectionRanges())
      if ("eachPage".equals(section.resolved.notes.footnote.numRestart)) return true;
    return false;
  }

  /** Narrow views keep numbering anchored to print pages, independent of viewport width. */
  public Result reflow(Document doc, long revision, float width) {
    Document numbered = pageRestart(doc) ? layout(doc, revision).document : doc;
    return layoutOnce(Reflow.document(numbered, width), revision);
  }

  /** The document being laid out: note stories lay out their tables and pictures against it. */
  private Document noteDocument;

  public Result layout(Document doc, long revision) {
    noteDocument = doc;
    if (!pageRestart(doc)) return layoutOnce(doc, revision);
    Document view = doc == numberedSource ? numberedView : Notes.numbered(doc, null);
    Result result = null;
    for (int pass = 0; pass < 8; pass++) {
      result = layoutOnce(view, revision);
      var byParagraph = new HashMap<String, List<Line>>();
      for (Page page : result.pages)
        for (Line line : page.lines) {
          if (line.block < 0) continue;
          var list = byParagraph.get(line.paragraphId);
          if (list == null) byParagraph.put(line.paragraphId, list = new ArrayList<Line>());
          list.add(line);
        }
      var pages = new HashMap<String, Integer>();
      for (Notes.Reference ref : Notes.references(view)) {
        Paragraph p = ref.paragraph(view);
        var lines = byParagraph.get(p.id);
        if (lines != null)
          for (Line line : lines)
            if (ref.offset >= line.start && ref.offset < line.end) {
              pages.put(ref.note.id, line.page);
              break;
            }
      }
      Document changed = Notes.numbered(view, pages);
      if (changed == view) {
        numberedSource = doc;
        numberedView = view;
        return result;
      }
      view = changed;
    }
    var warnings = new LinkedHashSet<String>(result.warnings);
    warnings.add("Page-restarted note numbering did not settle after eight pagination passes");
    return new Result(result.document, result.pages, result.measuredParagraphs, revision, warnings);
  }

  private Result layoutOnce(Document doc, long revision) {
    CharacterMetrics.showHidden = showHidden;
    nextCache = new HashMap<>();
    nextByContent = new HashMap<>();
    measured = 0;
    boolean auto = "automatic".equals(doc.properties.setting("hyphenation", ""));
    boolean caps = !"false".equals(doc.properties.setting("hyphenation.caps", ""));
    if (auto != hyphenate || caps != hyphenateCaps) {
      cache = new HashMap<>();
      byContent = new HashMap<>();
    }
    hyphenate = auto;
    hyphenateCaps = caps;
    List<Section.Range> ranges = doc.sectionRanges();
    var contexts = new ArrayList<Document>();
    float[][] heights = new float[ranges.size()][2];
    for (int i = 0; i < ranges.size(); i++) {
      var section = ranges.get(i).resolved;
      var context = doc.page(section.page).furniture(section.furniture);
      // Furniture fields read the section's page-number format from the context's last section.
      if (!context.lastSection.decor.equals(section.decor))
        context = context.sections(context.sections, context.lastSection.decor(section.decor));
      contexts.add(context);
      for (int side = 0; side < 2; side++)
        heights[i][side] =
            furnitureHeight(
                context,
                resolvedFurniture(
                    context, side == 1, 0, 1, section.pageStart == 0 ? 1 : section.pageStart));
    }
    Builder b = null;
    PageSpec[] bodies = new PageSpec[ranges.size()];
    // Resolve page-count fields, then repaginate if their results need more margin space.
    for (int pass = 0; pass < 8; pass++) {
      b = new Builder();
      for (int section = 0; section < ranges.size(); section++) {
        var range = ranges.get(section);
        bodies[section] = bodySpec(contexts.get(section), heights[section][0], heights[section][1]);
        b.beginSection(section, range.resolved, bodies[section]);
        for (int index = range.first; index <= range.last; index++) {
          var block = doc.blocks.get(index);
          if (block instanceof Paragraph paragraph)
            b.paragraph(
                paragraph,
                index,
                index < range.last && doc.blocks.get(index + 1) instanceof Paragraph
                    ? (Paragraph) doc.blocks.get(index + 1)
                    : null);
          else if (block instanceof Table table) b.table(table, index);
          else if (block instanceof Picture picture) b.picture(picture, index);
        }
      }
      b.finish();
      boolean grow = false;
      for (Page page : b.pages) {
        var context = contexts.get(page.section);
        for (int side = 0; side < 2; side++) {
          float actual =
              furnitureHeight(
                  context,
                  resolvedFurniture(
                      context, side == 1, page.sectionPage, b.pages.size(), page.number));
          if (actual > heights[page.section][side] + .01f) {
            heights[page.section][side] = actual;
            grow = true;
          }
        }
      }
      if (!grow) break;
    }
    var warnings = new LinkedHashSet<String>();
    int lineCount = 0;
    for (int i = 0; i < b.pages.size(); i++) {
      var page = b.pages.get(i);
      var context = contexts.get(page.section);
      var body = bodies[page.section];
      var vAlign = ranges.get(page.section).resolved.decor.layout("verticalAlign", "top");
      float dy = 0;
      if ("center".equals(vAlign) || "bottom".equals(vAlign)) {
        float bottom = page.spec.top;
        for (Line l : page.lines) bottom = Math.max(bottom, l.y + l.height);
        for (Box x : page.boxes) bottom = Math.max(bottom, x.y + x.height);
        for (PlacedPicture x : page.pictures) bottom = Math.max(bottom, x.y + x.height);
        float free = page.spec.height - page.spec.bottom - bottom;
        if (free > 0) dy = "center".equals(vAlign) ? free / 2 : free;
      }
      var header =
          furnitureLines(
              context,
              false,
              i,
              page.sectionPage,
              page.number,
              b.pages.size(),
              body.top - context.furniture.headerDistance,
              warnings);
      var footer =
          furnitureLines(
              context,
              true,
              i,
              page.sectionPage,
              page.number,
              b.pages.size(),
              body.bottom - context.furniture.footerDistance,
              warnings);
      // Mirrored margins (w:mirrorMargins), as Wordcraft's mirror_even_pages: even pages have the
      // inside margin (and gutter) on the right, so their header, text, notes and footer move to
      // start at the outside margin; page borders, measured from the page edge, stay.
      boolean mirrored =
          page.number % 2 == 0
              && "true".equals(ranges.get(page.section).resolved.decor.layout("mirrorMargins", "false"));
      float dx = mirrored ? page.spec.right - page.spec.left : 0;
      if (!(Math.abs(dx) < 1e6f) || Math.abs(dx) < .001f) dx = 0;
      var spec = dx == 0 ? page.spec
          : new PageSpec(page.spec.width, page.spec.height, page.spec.top, page.spec.left,
              page.spec.bottom, page.spec.right);
      var rebuilt =
          new Page(
              spec,
              page.section,
              page.sectionPage,
              page.number,
              shiftLines(page.lines, dx, dy),
              shiftBoxes(page.boxes, dx, dy),
              shiftPictures(page.pictures, dx, dy),
              Collections.unmodifiableList(shiftLines(header.lines, dx, 0)),
              Collections.unmodifiableList(shiftLines(footer.lines, dx, 0)),
              shiftLines(page.notes, dx, 0));
      rebuilt.furnitureBoxes.addAll(shiftBoxes(header.boxes, dx, 0));
      rebuilt.furnitureBoxes.addAll(shiftBoxes(footer.boxes, dx, 0));
      rebuilt.furniturePictures.addAll(shiftPictures(header.pictures, dx, 0));
      rebuilt.furniturePictures.addAll(shiftPictures(footer.pictures, dx, 0));
      for (float[] r : page.columnRules) rebuilt.columnRules.add(new float[] {r[0] + dx, r[1], r[2]});
      for (Frame f : page.frames)
        rebuilt.frames.add(
            dy == 0 && dx == 0
                ? f
                : new Frame(f.x + dx, f.top + dy, f.width, f.bottom + dy, f.borders, f.shading, f.first, f.last));
      rebuilt.decor = ranges.get(page.section).resolved.decor;
      var numbering = rebuilt.decor;
      if (numbering.lineCountBy > 0) {
        if ("page".equals(numbering.lineRestart)
            || "section".equals(numbering.lineRestart) && page.sectionPage == 0) lineCount = 0;
        for (Line line : page.lines) {
          if (line.block < 0 || line.row >= 0) continue;
          // Wordcraft: the shown number is the count plus lnNumType start - 1; countBy applies
          // to that number.
          int shown = ++lineCount + Integer.parseInt(numbering.layout("lineStart", "1")) - 1;
          if (shown % numbering.lineCountBy == 0)
            rebuilt.lineNumbers.add(new float[] {line.y, shown});
        }
      }
      b.pages.set(i, rebuilt);
    }
    cache = nextCache;
    byContent = nextByContent;
    return new Result(doc, b.pages, measured, revision, warnings);
  }

  private PageSpec bodySpec(Document doc, float header, float footer) {
    var original = doc.page;
    float top = Math.max(original.top, header == 0 ? 0 : header + doc.furniture.headerDistance);
    float bottom =
        Math.max(original.bottom, footer == 0 ? 0 : footer + doc.furniture.footerDistance);
    float extra = top - original.top + bottom - original.bottom;
    float budget = Math.max(0, original.height - original.top - original.bottom - 36);
    if (extra > budget) {
      float ratio = budget / extra;
      top = original.top + (top - original.top) * ratio;
      bottom = original.bottom + (bottom - original.bottom) * ratio;
    }
    return new PageSpec(
        original.width, original.height, top, original.right, bottom, original.left);
  }

  private Sequence<Block> resolvedFurniture(
      Document doc, boolean footer, int page, int count, int number) {
    // Fields resolve at any depth: Word headers often hold PAGE in a table cell.
    return PageFurniture.mapBlocks(
        doc.furniture.shown(doc.furniture.key(footer, page, number)),
        p -> {
          var resolved =
              p.text.mapFields(
                  (offset, field) ->
                      DocumentFields.resolve(
                          field, doc.title, number, count, Collections.<String, String>emptyMap(),
                          doc.lastSection.decor.numberFormat));
          return resolved == p.text ? p : p.text(resolved);
        });
  }

  /** A header or footer story laid out from y = 0 at the page's content margins. */
  private static final class StoryLayout {
    final List<Line> lines = new ArrayList<>();
    final List<Box> boxes = new ArrayList<>();
    final List<PlacedPicture> pictures = new ArrayList<>();
    /** Floats anchored to the page or its margins: already in page coordinates. */
    final List<PlacedPicture> anchored = new ArrayList<>();
    float height;
  }

  private StoryLayout story(Document doc, Sequence<Block> blocks, int page, float available) {
    var out = new StoryLayout();
    var spec = doc.page;
    // The story is laid out as one box like Wordcraft's layout_box: its floats wrap the text that
    // follows them per line, and paragraph/column floats sit at their position settings.
    var region = new PageSpec(spec.width, 100000, 0, spec.right, 0, spec.left);
    var scratch = new Builder();
    scratch.beginSection(
        0,
        doc.lastSection.page(region).furniture(PageFurniture.EMPTY).columns(Columns.ONE),
        region);
    for (int i = 0; i < blocks.size(); i++) {
      Block block = blocks.get(i);
      if (block instanceof Picture picture && !"inline".equals(picture.wrap())) {
        var pos = FloatPosition.parse(picture.position());
        if (pos != null && !"paragraph".equals(pos.vRel) && !"line".equals(pos.vRel)) {
          // As in Word, a header float anchored to the page or a margin is placed on the page.
          var paper = new Builder();
          paper.beginSection(
              0,
              doc.lastSection.page(spec).furniture(PageFurniture.EMPTY).columns(Columns.ONE),
              spec);
          paper.picture(picture, -1);
          for (PlacedPicture x : paper.pictures)
            out.anchored.add(
                new PlacedPicture(x.picture(), -1, page, x.x(), x.y(), x.width(), x.height()));
          continue;
        }
      }
      if (block instanceof Paragraph p)
        scratch.paragraph(p, -1, i + 1 < blocks.size() && blocks.get(i + 1) instanceof Paragraph n ? n : null);
      else if (block instanceof Table t) scratch.table(t, -1);
      else if (block instanceof Picture picture) scratch.picture(picture, -1);
    }
    float height = scratch.y;
    scratch.finish();
    var laid = scratch.pages.get(0);
    for (Line l : laid.lines)
      out.lines.add(new Line(-1, -1, -1, l.paragraphId, page, l.x, l.y, raw(l), l.marker).storyCell(l));
    for (Box x : laid.boxes) {
      out.boxes.add(new Box(-1, x.row, x.column, page, x.x, x.y, x.width, x.height, x.format).at(x.path));
      height = Math.max(height, x.y + x.height);
    }
    for (PlacedPicture x : laid.pictures)
      out.pictures.add(new PlacedPicture(x.picture(), -1, page, x.x(), x.y(), x.width(), x.height()));
    out.height = height;
    return out;
  }

  private static RawLine raw(Line line) {
    var raw = new RawLine();
    raw.start = line.start;
    raw.end = line.end;
    raw.width = line.width;
    raw.height = line.height;
    raw.clipped = line.clipped;
    raw.markerOffset = line.markerOffset;
    raw.mirrored = line.rtl;
    raw.spans.addAll(line.spans);
    raw.barTabs = line.barTabs;
    return raw;
  }

  private float furnitureHeight(Document doc, Sequence<Block> blocks) {
    return blocks.size() == 0 ? 0 : story(doc, blocks, 0, Float.MAX_VALUE).height;
  }

  private StoryLayout furnitureLines(
      Document doc,
      boolean footer,
      int page,
      int sectionPage,
      int number,
      int count,
      float available,
      Set<String> warnings) {
    var blocks = resolvedFurniture(doc, footer, sectionPage, count, number);
    var laid = story(doc, blocks, page, available);
    float y = laid.height;
    if (blocks.size() > 0 && y > available + .01f)
      warnings.add(
          (footer ? "Footer" : "Header")
              + " on page "
              + (page + 1)
              + " does not fit. Reduce its content, spacing or distance from the page"
              + " edge.");
    float start =
        footer ? doc.page.height - doc.furniture.footerDistance - y : doc.furniture.headerDistance;
    var placed = new StoryLayout();
    placed.height = y;
    for (Line line : laid.lines) {
      if (line.y + line.height > available + .01f) continue;
      placed.lines.add(
          new Line(-1, -1, -1, line.paragraphId, page, line.x, start + line.y, raw(line), line.marker));
    }
    for (Box x : laid.boxes)
      placed.boxes.add(
          new Box(-1, x.row, x.column, page, x.x, start + x.y, x.width, x.height, x.format).at(x.path));
    for (PlacedPicture x : laid.pictures)
      placed.pictures.add(
          new PlacedPicture(x.picture(), -1, page, x.x(), start + x.y(), x.width(), x.height()));
    placed.pictures.addAll(laid.anchored);
    return placed;
  }

  private final class Builder {
    PageSpec spec, paper, pagePaper;
    // Newspaper columns: spec is the current column's geometry inside body.
    PageSpec body;
    Columns columns = Columns.ONE;
    int column;
    float columnTop, regionBottom;
    final List<float[]> rules = new ArrayList<>();
    int section, sectionFirstPage, owner, ownerFirstPage, number = 1;
    final List<Page> pages = new ArrayList<>();
    List<Line> current = new ArrayList<>();
    List<Box> boxes = new ArrayList<>();
    List<PlacedPicture> pictures = new ArrayList<>();
    float y;
    final ListNumbers numbering = new ListNumbers();
    final List<Note> endnotes = new ArrayList<>();
    final List<Note> sectionEndnotes = new ArrayList<>();
    /** The endnote whose table (story block endnoteBlock) is being laid out, or null. */
    String endnote;

    int endnoteBlock;
    NoteOptions noteOptions = NoteOptions.DEFAULT;
    final Set<String> seenNotes = new HashSet<>();
    final LinkedList<NoteLine> pendingNotes = new LinkedList<>();
    List<NoteLine> footnotes = new ArrayList<>();
    float noteHeight;

    final class NoteLine {
      final String id;
      final RawLine line;
      final float x, before, after;
      final String marker;
      final boolean beneath;
      /** A table or picture of the note story, laid out as a header story's blocks are. */
      final StoryLayout story;
      /** The note story block the table or picture is (story notes only). */
      int block = -1;

      NoteLine(String id, StoryLayout story) {
        beneath = "beneathText".equals(noteOptions.footnote.pos);
        this.id = id;
        this.story = story;
        line = null;
        before = after = 0;
        marker = "";
        x = 0;
      }

      NoteLine(Paragraph p, RawLine line, String marker, float before, float after) {
        story = null;
        beneath = "beneathText".equals(noteOptions.footnote.pos);
        this.id = p.id;
        this.line = line;
        this.before = before;
        this.after = after;
        this.marker = marker;
        float indent = p.indent + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1));
        float first = line.start == 0 ? p.format.firstIndent : 0;
        float extra = spec.contentWidth() - indent - first - p.format.rightIndent - line.width;
        float lx =
            indent
                + first
                + ("center".equals(p.align) ? extra / 2 : "right".equals(p.align) ? extra : 0);
        // Right to left: mirrored from the note area's right edge.
        x = p.format.rtl ? spec.contentWidth() - lx - line.width : lx;
      }

      float height() {
        return before + (story != null ? story.height : line.height) + after;
      }
    }

    List<NoteLine> noteLines(Note note) {
      var result = new ArrayList<NoteLine>();
      var numbers = new ListNumbers();
      int index = -1;
      for (Block block : note.displayedBlocks()) {
        index++;
        if (!(block instanceof Paragraph p)) {
          if (noteDocument != null) {
            var line = new NoteLine(note.id, story(noteDocument, new Sequence<Block>().append(block), 0, Float.MAX_VALUE));
            line.block = index;
            result.add(line);
          }
          continue;
        }
        var measured = lines(p, spec.contentWidth());
        var marker = numbers.next(p);
        for (int i = 0; i < measured.size(); i++)
          result.add(
              new NoteLine(
                  p,
                  measured.get(i),
                  i == 0 ? marker : "",
                  i == 0 ? p.before : 0,
                  i == measured.size() - 1 ? p.after : 0));
      }
      return result;
    }

    void collectNotes(Paragraph paragraph, RawLine line) {
      var notes = new ArrayList<Note>();
      paragraph
          .text
          .slice(line.start, line.end)
          .mapNotes(
              (offset, note) -> {
                notes.add(note);
                return note;
              });
      reserveNotes(notes, line.height);
    }

    void reserveNotes(List<Note> notes, float anchorHeight) {
      var added = new ArrayList<NoteLine>();
      for (Note note : notes) {
        if (!seenNotes.add(note.id)) continue;
        if ("endnote".equals(note.kind)) {
          if ("sectEnd".equals(noteOptions.endnote.pos)) sectionEndnotes.add(note);
          else endnotes.add(note);
        } else added.addAll(noteLines(note));
      }
      if (added.isEmpty()) return;
      float needed = footnotes.isEmpty() ? 12 : 0;
      for (NoteLine value : added) needed += value.height();
      float pageHeight = spec.height - spec.top - spec.bottom;
      float minimum = (footnotes.isEmpty() ? 12 : 0) + added.get(0).height();
      if (y > spec.top
          && y + anchorHeight + noteHeight + needed > spec.height - spec.bottom
          && (anchorHeight + needed <= pageHeight
              || y + anchorHeight + noteHeight + minimum > spec.height - spec.bottom)) newPage();
      if (footnotes.isEmpty()) noteHeight = 12;
      for (NoteLine value : added) {
        if (value.height() > spec.height - spec.top - spec.bottom - 12)
          throw new IllegalArgumentException("Note line exceeds available page height");
        if (pendingNotes.isEmpty()
            && y + anchorHeight + noteHeight + value.height() <= spec.height - spec.bottom) {
          footnotes.add(value);
          noteHeight += value.height();
        } else pendingNotes.add(value);
      }
    }

    List<Line> placedNotes() {
      var result = new ArrayList<Line>();
      float top = spec.height - spec.bottom - noteHeight + 12;
      boolean beneath = !footnotes.isEmpty();
      for (NoteLine line : footnotes) beneath &= line.beneath;
      if (beneath) top = Math.min(top, y + 12);
      for (NoteLine value : footnotes) {
        if (value.story != null) {
          for (Line l : value.story.lines)
            result.add(
                new Line(-2, -1, -1, l.paragraphId, pages.size(), l.x, top + l.y, raw(l), l.marker)
                    .inNoteCell(value.id, value.block, l));
          for (Box x : value.story.boxes)
            boxes.add(new Box(-1, x.row, x.column, pages.size(), x.x, top + x.y, x.width, x.height, x.format).at(x.path));
          for (PlacedPicture x : value.story.pictures)
            pictures.add(new PlacedPicture(x.picture(), -1, pages.size(), x.x(), top + x.y(), x.width(), x.height()));
          top += value.story.height;
          continue;
        }
        top += value.before;
        result.add(
            new Line(
                -2,
                -1,
                -1,
                value.id,
                pages.size(),
                spec.left + value.x,
                top,
                value.line,
                value.marker));
        top += value.line.height + value.after;
      }
      return result;
    }

    void continuationNotes() {
      footnotes = new ArrayList<>();
      noteHeight = 0;
      while (!pendingNotes.isEmpty()) {
        var value = pendingNotes.get(0);
        float height = value.height() + (footnotes.isEmpty() ? 12 : 0);
        if (noteHeight + height > spec.height - spec.top - spec.bottom) break;
        footnotes.add(value);
        noteHeight += height;
        pendingNotes.removeFirst();
      }
    }

    /** Document grid line pitch in points for the current section, or 0. */
    float gridPitch;

    void beginSection(int index, Section value, PageSpec body) {
      var grid = value.decor.layout("grid", "default");
      gridPitch =
          "default".equals(grid) || "snapToChars".equals(grid)
              ? 0
              : Float.parseFloat(value.decor.layout("gridPitch", "18"));
      if (spec == null) {
        noteOptions = value.notes;
        spec = body;
        paper = pagePaper = value.page;
        if (value.pageStart > 0) number = value.pageStart;
        y = spec.top;
        startColumns(body, value.columns, y);
        return;
      }
      finishSectionNotes();
      closeRegion();
      boolean continuous =
          "continuous".equals(value.start)
              && paper.width == value.page.width
              && paper.height == value.page.height;
      int restart = value.pageStart;
      if (!continuous) {
        while (!pendingNotes.isEmpty()) newPage();
        newPage();
        boolean sided = "oddPage".equals(value.start) || "evenPage".equals(value.start);
        if (sided) {
          boolean odd = "oddPage".equals(value.start);
          if ((number % 2 == 1) != odd && (restart == 0 || value.furniture.differentEven))
            newPage();
          if (restart > 0 && (restart % 2 == 1) != odd) restart++;
        } else if (value.furniture.differentEven
            && restart > 0
            && restart % 2 != (pages.size() + 1) % 2) newPage();
      }
      noteOptions = value.notes;
      section = index;
      sectionFirstPage = pages.size();
      spec = body;
      paper = value.page;
      if (!continuous) {
        owner = section;
        ownerFirstPage = sectionFirstPage;
        pagePaper = paper;
        y = spec.top;
        if (restart > 0) number = restart;
      } else y = Math.max(Math.max(y, regionBottom), spec.top);
      startColumns(body, value.columns, y);
    }

    void startColumns(PageSpec area, Columns value, float top) {
      body = area;
      columns = value;
      column = 0;
      columnTop = regionBottom = top;
      spec = columnSpec(0);
    }

    PageSpec columnSpec(int index) {
      if (columns.count == 1) return body;
      var g = columns.layout(body.contentWidth())[index];
      return new PageSpec(
          body.width,
          body.height,
          body.top,
          Math.max(0, body.width - body.left - g[0] - g[1]),
          body.bottom,
          body.left + g[0]);
    }

    /** Continue in the next column of this page, or on a new page after the last one. */
    void nextColumn() {
      if (column + 1 >= columns.count) {
        newPage();
        return;
      }
      regionBottom = Math.max(regionBottom, y);
      column++;
      spec = columnSpec(column);
      y = columnTop;
    }

    /** Record separator rules for the column region ending here. */
    void closeRegion() {
      regionBottom = Math.max(regionBottom, y);
      if (!columns.separator || columns.count < 2 || regionBottom <= columnTop) return;
      var g = columns.layout(body.contentWidth());
      for (int i = 0; i + 1 < g.length; i++) {
        float x = body.left + (g[i][0] + g[i][1] + g[i + 1][0]) / 2;
        rules.add(new float[] {x, columnTop, regionBottom});
      }
    }

    Page page(Page value) {
      value.columnRules.addAll(rules);
      rules.clear();
      value.frames.addAll(frames);
      frames.clear();
      return value;
    }

    final List<Frame> frames = new ArrayList<>();

    /** Adds a frame to the page it belongs to, which may already be finished. */
    void frame(int page, Frame f) {
      if (page < pages.size()) pages.get(page).frames.add(f);
      else frames.add(f);
    }

    void newPage() {
      closeRegion();
      pages.add(
          page(new Page(
              pagePaper,
              owner,
              pages.size() - ownerFirstPage,
              number,
              current,
              boxes,
              pictures,
              Collections.<Line>emptyList(),
              Collections.<Line>emptyList(),
              placedNotes())));
      current = new ArrayList<>();
      boxes = new ArrayList<>();
      pictures = new ArrayList<>();
      y = spec.top;
      number++;
      owner = section;
      ownerFirstPage = sectionFirstPage;
      pagePaper = paper;
      if (body != null) startColumns(body, columns, y);
      continuationNotes();
    }

    void room(float height) {
      while (y + height + noteHeight > spec.height - spec.bottom
          && (y > spec.top || !footnotes.isEmpty() || column + 1 < columns.count)) nextColumn();
    }

    void paragraph(Paragraph p, int index, Paragraph following) {
      var marker = numbering.next(p);
      float indent = p.indent + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1));
      if (p.pageBreak && y > spec.top) newPage();
      else if (p.format.columnBreak && index >= 0) nextColumn();
      PageSpec unwrapped = null, wrapped = null;
      if (inWrap()) {
        unwrapped = spec;
        spec =
            wrapped =
                new PageSpec(
                    spec.width, spec.height, spec.top, spec.right + polygonCut(wrapRight), spec.bottom,
                    spec.left + polygonCut(wrapLeft));
      }
      List<RawLine> ls;
      final float base = y + p.before;
      if (floatsBelow(base)) {
        band = (top, h) -> {
          float[] c = cut(base + top, h);
          c[2] -= base;
          return c;
        };
        try {
          ls = lines(p, spec.contentWidth(), true);
        } finally {
          band = null;
        }
      } else ls = lines(p, spec.contentWidth(), index >= 0);
      if (gridPitch > 0 && index >= 0) {
        // Snap each line to whole grid pitches; copies keep the measured cache unchanged.
        var snapped = new ArrayList<RawLine>();
        for (RawLine l : ls) {
          var c = new RawLine();
          c.spans.addAll(l.spans);
          c.barTabs = l.barTabs;
          c.start = l.start;
          c.end = l.end;
          c.width = l.width;
          c.markerOffset = l.markerOffset;
          c.mirrored = l.mirrored;
          c.clipped = l.clipped;
          c.lead = l.lead;
          c.baseline = l.baseline;
          c.drop = l.drop;
          c.dropX = l.dropX;
          c.cutRight = l.cutRight;
          c.skip = l.skip;
          c.gapAt = l.gapAt;
          c.gapX = l.gapX;
          c.height = (float) Math.ceil(l.height / gridPitch - .01f) * gridPitch;
          snapped.add(c);
        }
        ls = snapped;
      }
      float pageHeight = spec.height - spec.top - spec.bottom;
      if (p.keepNext && following != null && !following.pageBreak) {
        var next = lines(following, spec.contentWidth());
        float group = p.before + p.after + following.before;
        for (RawLine line : ls) group += line.height;
        for (int i = 0; i < Math.min(2, next.size()); i++) group += next.get(i).height;
        if (group <= pageHeight) room(group);
      }
      if (p.format.keepLines) {
        float together = p.before + p.after;
        for (RawLine line : ls) together += line.height;
        if (together <= pageHeight) room(together);
      }
      float first = p.before + ls.get(0).height;
      if (p.format.widowControl && ls.size() > 1 && first + ls.get(1).height <= pageHeight)
        first += ls.get(1).height;
      var bd = p.format.borders;
      boolean decor = !bd.isEmpty() || p.format.shading >= 0;
      float topPad = bd.top.extent(), bottomPad = bd.bottom.extent();
      room(first + topPad);
      y += p.before;
      if (decor) y += topPad;
      int segPage = pages.size(), segColumn = column;
      float segTop = y - topPad, segBottom = y, segLeft = spec.left, segWidth = spec.contentWidth();
      boolean segFirst = true;
      int lineIndex = 0, segLines = 0;
      for (RawLine l : ls) {
        if (!pendingNotes.isEmpty()) newPage();
        collectNotes(p, l);
        if (footnotes.isEmpty()
            && p.format.widowControl
            && ls.size() - lineIndex == 2
            && l.height + ls.get(lineIndex + 1).height <= pageHeight)
          room(l.height + ls.get(lineIndex + 1).height);
        lineIndex++;
        y += l.skip;
        room(l.height);
        if (decor && (segPage != pages.size() || segColumn != column)) {
          // A paragraph pushed whole to the next page keeps its top border there.
          if (segLines > 0) {
            frame(segPage, frame(p, indent, segLeft, segWidth, segTop, segBottom, segFirst, false));
            segFirst = false;
            segTop = y;
          } else {
            segTop = y;
            y += topPad;
          }
          segLines = 0;
          segPage = pages.size();
          segColumn = column;
          segLeft = spec.left;
          segWidth = spec.contentWidth();
        }
        float firstIndent = l.start == 0 ? p.format.firstIndent : 0;
        float x = spec.left + indent + firstIndent + l.lead;
        if ("center".equals(p.align) && l.gapAt == null)
          x += (spec.contentWidth() - indent - p.format.rightIndent - firstIndent - l.lead - l.cutRight - l.width) / 2;
        if ("right".equals(p.align) && l.gapAt == null)
          x += spec.contentWidth() - indent - p.format.rightIndent - firstIndent - l.lead - l.cutRight - l.width;
        // Right to left: indents and alignment are measured from the right edge (mirrored).
        if (p.format.rtl) x = 2 * spec.left + spec.contentWidth() - x - l.width;
        current.add(new Line(index, -1, -1, p.id, pages.size(), x, y, l, marker));
        // After the first line, so caret lookups for offset 0 find the text line first.
        if (l.drop != null) {
          float dropX = spec.left + indent + l.dropX;
          // Right to left: the letter hangs at the start (right) edge, as in Wordcraft.
          if (p.format.rtl) dropX = 2 * spec.left + spec.contentWidth() - dropX - l.drop.width;
          current.add(new Line(index, -1, -1, p.id, pages.size(), dropX, y, l.drop, ""));
        }
        marker = "";
        y += l.height;
        segBottom = y;
        segLines++;
      }
      if (decor) {
        y += bottomPad;
        frame(
            segPage,
            frame(p, indent, segLeft, segWidth, segTop, segBottom + bottomPad, segFirst, true));
      }
      y += p.after;
      if (wrapped != null && spec == wrapped) spec = unwrapped;
    }

    /** The border box spans the indented text width plus each side's distance and width. */
    Frame frame(
        Paragraph p, float indent, float left, float width, float top, float bottom,
        boolean first, boolean last) {
      var bd = p.format.borders;
      float x = left + indent - bd.left.extent();
      float right = left + width - p.format.rightIndent + bd.right.extent();
      return new Frame(x, top, right - x, bottom, p.format, first, last);
    }

    /** A square/tight-wrapped object narrows paragraphs beside it until this y on its page. */
    float wrapBottom, wrapLeft, wrapRight;
    /** Tight/Through: the wrapped object's frame and polygon (unit coordinates), or null. */
    float wrapTop, wrapWidth, wrapHeight;
    boolean wrapRightSide;
    float[] wrapOutline;
    int wrapPage = -1, wrapColumn;

    boolean inWrap() {
      return wrapPage == pages.size() && wrapColumn == column && y < wrapBottom;
    }

    /**
     * The side cut for a paragraph starting at y: with a wrap polygon, the polygon's extent over
     * the paragraph's first line band (text follows the outline paragraph by paragraph), else
     * the frame cut.
     */
    float polygonCut(float frameCut) {
      if (frameCut == 0 || wrapOutline == null || wrapHeight <= 0) return frameCut;
      float a = (y - wrapTop) / wrapHeight, b = (y + 16 - wrapTop) / wrapHeight;
      float[] r = WrapOutline.extent(wrapOutline, a, b);
      if (r == null) return 0;
      float reach = wrapRightSide ? (1 - r[0]) * wrapWidth : r[1] * wrapWidth;
      return Math.max(0, Math.min(frameCut, reach + 6));
    }

    /** Tables and inline objects start below a wrapped object rather than beside it. */
    void clearWrap() {
      if (inWrap()) y = wrapBottom;
      wrapPage = -1;
      // As Wordcraft's below_floats: below the positioned floats in the way.
      for (int guard = 0; guard < 64; guard++) {
        float below = y;
        for (Excl e : excl)
          if (e.page == pages.size() && e.y0 <= y + .01f && e.y1 > y && e.x0 < spec.left + spec.contentWidth() && e.x1 > spec.left)
            below = Math.max(below, e.y1);
        if (below == y) break;
        y = below;
      }
    }

    /**
     * A positioned float text wraps around (Wordcraft's wrap areas): its frame grown by the
     * distance from text, the frame itself and, for Tight/Through, its outline.
     */
    final class Excl {
      final int page;
      final float x0, y0, x1, y1, fx, fy, fw, fh, dist;
      final boolean topBottom;
      final float[] outline;
      /** Wordcraft's WrapText: bothSides, left, right or largest. */
      String side = "bothSides";

      Excl(int page, float fx, float fy, float fw, float fh, float dist, boolean topBottom, float[] outline) {
        this.page = page;
        this.fx = fx;
        this.fy = fy;
        this.fw = fw;
        this.fh = fh;
        this.dist = dist;
        this.topBottom = topBottom;
        this.outline = outline;
        x0 = fx - dist;
        x1 = fx + fw + dist;
        y0 = fy - (topBottom ? dist : 0);
        y1 = fy + fh + (topBottom ? dist : 0);
      }
    }

    final List<Excl> excl = new ArrayList<>();

    /**
     * Per line, as Wordcraft lays text beside floats: the floats crossing the band [y, y + h]
     * cut the column on the side away from the larger free space (a Tight/Through float by its
     * outline's extent over the band); a Top and Bottom float, or one leaving under 18pt, sends
     * the line below it.
     */
    float[] cut(float yA, float h) {
      float left = spec.left, right = spec.left + spec.contentWidth();
      // Wordcraft's row_spans: what each float takes of the row (its side of the row too when
      // text flows on one side only), then the free spans left to right.
      float[] taken = new float[excl.size() * 2];
      float firstBottom = Float.MAX_VALUE;
      int n = 0;
      for (Excl e : excl) {
        if (e.page != pages.size() || e.y0 >= yA + h || e.y1 <= yA || e.x0 >= right || e.x1 <= left) continue;
        if (e.topBottom) return new float[] {0, 0, e.y1};
        float ox0 = e.x0, ox1 = e.x1;
        if (e.outline != null && e.fh > 0) {
          float[] r = WrapOutline.extent(e.outline, (yA - e.fy) / e.fh, (yA + h - e.fy) / e.fh);
          if (r == null) continue;
          ox0 = e.fx + r[0] * e.fw - e.dist;
          ox1 = e.fx + r[1] * e.fw + e.dist;
        }
        if (!"bothSides".equals(e.side)) {
          boolean textLeft = "left".equals(e.side) || (!"right".equals(e.side) && ox0 - left >= right - ox1);
          if (textLeft) ox1 = Math.max(right, ox1);
          else ox0 = Math.min(left, ox0);
        }
        taken[n++] = ox0;
        taken[n++] = ox1;
        firstBottom = Math.min(firstBottom, e.y1);
      }
      if (n == 0) return new float[] {0, 0, yA};
      List<float[]> spans = new ArrayList<>();
      spans.add(new float[] {left, right});
      for (int i = 0; i < n; i += 2) {
        List<float[]> next = new ArrayList<>();
        for (float[] sp : spans) {
          if (taken[i + 1] <= sp[0] || taken[i] >= sp[1]) next.add(sp);
          else {
            next.add(new float[] {sp[0], Math.min(taken[i], sp[1])});
            next.add(new float[] {Math.max(taken[i + 1], sp[0]), sp[1]});
          }
        }
        spans = next;
      }
      for (int i = spans.size() - 1; i >= 0; i--) if (spans.get(i)[1] - spans.get(i)[0] < 20) spans.remove(i);
      if (spans.isEmpty()) return new float[] {0, 0, firstBottom};
      // {left cut, right cut, top, then each gap between spans as start, end within the column}.
      float[] out = new float[3 + (spans.size() - 1) * 2];
      out[0] = spans.get(0)[0] - left;
      out[1] = right - spans.get(spans.size() - 1)[1];
      out[2] = yA;
      for (int i = 1; i < spans.size(); i++) {
        out[1 + i * 2] = spans.get(i - 1)[1] - left;
        out[2 + i * 2] = spans.get(i)[0] - left;
      }
      return out;
    }

    boolean floatsBelow(float from) {
      for (Excl e : excl) if (e.page == pages.size() && e.y1 > from) return true;
      return false;
    }

    /**
     * A float placed by its wp:positionH/V as Wordcraft's float placement: the offset or
     * alignment within the page, margin, column, margin areas, or from the anchor paragraph.
     */
    void positioned(Picture p, int index, FloatPosition pos, String wrap) {
      float scale = Math.min(1, Math.min(spec.width / p.width, spec.height / p.height));
      float w = p.width * scale, h = p.height * scale;
      PageSpec m = body == null ? spec : body;
      boolean odd = pages.size() % 2 == 0;
      float hs, he;
      switch (pos.hRel) {
        case "page": hs = 0; he = m.width; break;
        case "margin": case "insideMargin": case "outsideMargin": hs = m.left; he = m.contentWidth(); break;
        case "leftMargin": hs = 0; he = m.left; break;
        case "rightMargin": hs = m.width - m.right; he = m.right; break;
        default: hs = spec.left; he = spec.contentWidth();
      }
      float vs, ve;
      switch (pos.vRel) {
        case "page": vs = 0; ve = m.height; break;
        case "margin": vs = m.top; ve = m.height - m.top - m.bottom; break;
        case "topMargin": vs = 0; ve = m.top; break;
        case "bottomMargin": vs = m.height - m.bottom; ve = m.bottom; break;
        default: vs = y; ve = 0;
      }
      float x = FloatPosition.alignIn(hs, he, pos.x, w, pos.hAlign, odd);
      float top = ve == 0 && !pos.vAlign.isEmpty() ? vs : FloatPosition.alignIn(vs, ve, pos.y, h, pos.vAlign, odd);
      pictures.add(new PlacedPicture(p, index, pages.size(), x, top, w, h));
      boolean tight = "tight".equals(wrap) || "through".equals(wrap);
      if ("square".equals(wrap) || tight || "topBottom".equals(wrap)) {
        Excl e = new Excl(pages.size(), x, top, w, h, "topBottom".equals(wrap) ? 0 : 9, "topBottom".equals(wrap),
            tight ? WrapOutline.parse(p.wrapPolygon()) : null);
        e.side = pos.side;
        excl.add(e);
      }
    }

    void picture(Picture p, int index) {
      numbering.reset();
      String wrap = p.wrap();
      FloatPosition pos = "inline".equals(wrap) ? null : FloatPosition.parse(p.position());
      if (pos == null && ("square".equals(wrap) || "tight".equals(wrap) || "through".equals(wrap) || "topBottom".equals(wrap))) {
        // Aligned in its column at the anchor paragraph (Wordcraft's default float): placed like
        // any positioned float, with text beside it per line.
        float scale = Math.min(1, Math.min(spec.contentWidth() / p.width, (spec.height - spec.top - spec.bottom) / p.height));
        room(p.height * scale);
        String a = "center".equals(p.align) ? "center" : "right".equals(p.align) ? "end" : "start";
        pos = new FloatPosition("column", a, 0, "paragraph", "", 0);
      }
      if (pos != null) {
        positioned(p, index, pos, wrap);
        return;
      }
      boolean floating = "behind".equals(wrap) || "front".equals(wrap);
      boolean tight = "tight".equals(wrap) || "through".equals(wrap);
      boolean around = ("square".equals(wrap) || tight) && !"center".equals(p.align);
      if (!floating) clearWrap();
      float scale =
          Math.min(
              1,
              Math.min(
                  spec.contentWidth() / p.width,
                  (spec.height - spec.top - spec.bottom) / p.height));
      float width = p.width * scale, height = p.height * scale;
      room(height);
      float x =
          spec.left
              + ("center".equals(p.align)
                  ? (spec.contentWidth() - width) / 2
                  : "right".equals(p.align) ? spec.contentWidth() - width : 0);
      pictures.add(new PlacedPicture(p, index, pages.size(), x, y, width, height));
      if (floating) return;
      if (around && width < spec.contentWidth() * .75f) {
        float cut = width + (tight ? 6 : 12);
        wrapPage = pages.size();
        wrapColumn = column;
        wrapTop = y;
        wrapWidth = width;
        wrapHeight = height;
        wrapRightSide = "right".equals(p.align);
        wrapOutline = tight ? WrapOutline.parse(p.wrapPolygon()) : null;
        wrapBottom = y + height + 6;
        wrapLeft = "right".equals(p.align) ? 0 : cut;
        wrapRight = "right".equals(p.align) ? cut : 0;
        return;
      }
      y += height + 6;
    }

    /** Extra x of the table being laid out (a floating table's position). */
    float tableShift;

    /**
     * A floating table (w:tblpPr) as Wordcraft's float_table_rect: placed by its anchors and
     * offsets or alignments, taking no room in the flow; the text after it wraps around it.
     */
    void floatingTable(Table t, int index, String[] f) {
      // Wordcraft place_floating_table: the table is never split; one that does not fit below
      // the text before it moves to the next page with its anchor (once, not from a page top).
      measuring = true;
      try {
        mergedTable(t, index);
      } finally {
        measuring = false;
      }
      float tableHeight = measured;
      for (int attempt = 0; ; attempt++) {
        float[] r = floatingTableRect(t, f, tableHeight);
        boolean moves;
        if ("text".equals(f[1])) moves = r[1] + tableHeight > spec.height - spec.bottom + .01f;
        else {
          boolean covers = r[1] < y - .01f && r[1] + tableHeight > spec.top + .01f
              && r[0] < spec.left + spec.contentWidth() && r[0] + r[2] > spec.left;
          moves = covers && y + tableHeight > spec.height - spec.bottom + .01f;
        }
        if (attempt == 0 && moves && y > spec.top + .01f) {
          newPage();
          continue;
        }
        placeFloatingTable(t, index, f, r[0], r[1], r[2]);
        return;
      }
    }

    /** Set while {@link #mergedTable} only measures a table's height into {@link #measured}. */
    boolean measuring;

    float measured;

    /** Wrap areas {page, x0, y0, x1, y1} of the floating tables placed so far. */
    final List<float[]> floatTables = new ArrayList<>();

    /**
     * Where floating table {@code t} goes: {x, top, width}, moved below the floating tables in its
     * way unless it may overlap them (w:tblOverlap, Wordcraft clear_of_float_tables); one placed
     * by an offset in the margin or column keeps its distance from the text's left edge.
     */
    float[] floatingTableRect(Table t, String[] f, float h) {
      float total = 0;
      for (float width : tableWidths(t, spec.contentWidth())) total += width;
      PageSpec m = body == null ? spec : body;
      boolean odd = pages.size() % 2 == 0;
      float hs, he, vs, ve;
      switch (f[0]) {
        case "page": hs = 0; he = m.width; break;
        case "margin": hs = m.left; he = m.contentWidth(); break;
        default: hs = spec.left; he = spec.contentWidth();
      }
      switch (f[1]) {
        case "page": vs = 0; ve = m.height; break;
        case "margin": vs = m.top; ve = m.height - m.top - m.bottom; break;
        default: vs = y; ve = 0;
      }
      float x = FloatPosition.alignIn(hs, he, TableFormat.floatNumber(f[2]), total, f[4], odd);
      float top = ve == 0 && !f[5].isEmpty() ? vs : FloatPosition.alignIn(vs, ve, TableFormat.floatNumber(f[3]), 0, f[5], odd);
      if (ve == 0 && f[5].isEmpty()) top = y + TableFormat.floatNumber(f[3]);
      float dl = TableFormat.floatNumber(f[6]), dt = TableFormat.floatNumber(f[7]),
          dr = TableFormat.floatNumber(f[8]), db = TableFormat.floatNumber(f[9]);
      boolean keepsTextDistance = f[4].isEmpty() && TableFormat.floatNumber(f[2]) >= 0 && !"page".equals(f[0]);
      if (!"never".equals(f[10]))
        return new float[] {x, top, total};
      for (int guard = 0; guard < 64; guard++) {
        float below = Float.NaN;
        for (float[] o : floatTables)
          if (o[0] == pages.size() && o[1] < x + total + dr && o[3] > x - dl && o[2] < top + h + db && o[4] > top - dt)
            below = Float.isNaN(below) ? o[4] : Math.max(below, o[4]);
        if (Float.isNaN(below)) break;
        top = below + dt;
        if (keepsTextDistance) x = Math.max(x, hs + dl);
      }
      return new float[] {x, top, total};
    }

    void placeFloatingTable(Table t, int index, String[] f, float x, float top, float total) {
      float before = y;
      int page = pages.size();
      y = Math.max(0, top);
      tableShift = x - spec.left - t.format.sizing.offset(spec.contentWidth(), total);
      try {
        mergedTable(t, index);
      } finally {
        tableShift = 0;
      }
      if (pages.size() != page) return; // split across pages: it stays where it ended
      float height = y - Math.max(0, top);
      y = before;
      float dl = TableFormat.floatNumber(f[6]), dt = TableFormat.floatNumber(f[7]),
          dr = TableFormat.floatNumber(f[8]), db = TableFormat.floatNumber(f[9]);
      excl.add(new Excl(pages.size(), x - dl, Math.max(0, top) - dt, total + dl + dr, height + dt + db, 0, false, null));
      floatTables.add(new float[] {pages.size(), x - dl, Math.max(0, top) - dt, x + total + dr, Math.max(0, top) + height + db});
    }

    void table(Table t, int index) {
      String[] floating = t.format.floating();
      if (floating != null) {
        // Placed from where the next paragraph starts, not below other floats (Wordcraft).
        floatingTable(t, index, floating);
        return;
      }
      clearWrap();
      // As Wordcraft's below(): a table in the text flow starts below the floats in its way.
      for (boolean moved = true; moved; ) {
        moved = false;
        for (Excl e : excl)
          if (e.page == pages.size() && e.y0 <= y + .01f && e.y1 > y + .01f && e.x1 > spec.left && e.x0 < spec.left + spec.contentWidth()) {
            y = e.y1;
            moved = true;
          }
      }
      mergedTable(t, index);
    }

    /** A cell box of a nested table, relative to the outermost cell's content origin. */
    final class InnerBox {
      final int[] path;
      final float x, y, width, height;
      final TableFormat.Cell format;

      InnerBox(int[] path, float x, float y, float width, float height, TableFormat.Cell format) {
        this.path = path;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.format = format;
      }
    }

    final class CellLayout {
      final Table.Merge span;
      final List<RawLine> lines = new ArrayList<>();
      final List<Paragraph> paragraphs = new ArrayList<>();
      final List<String> ids = new ArrayList<String>(), markers = new ArrayList<String>();
      final List<Float> xs = new ArrayList<Float>(), ys = new ArrayList<Float>();
      final List<int[]> paths = new ArrayList<int[]>();
      final List<InnerBox> inner = new ArrayList<InnerBox>();
      /** Pictures and drawings in this cell (nested cells too) with their cell-relative frames. */
      final List<Picture> pics = new ArrayList<Picture>();
      /** {x, y, w, h, originX, originY, contentWidth} per picture, cell-relative. */
      final List<float[]> picBoxes = new ArrayList<float[]>();
      /** Each picture's index in its cell's blocks and that cell's path below this cell. */
      final List<Integer> picIndex = new ArrayList<Integer>();
      final List<int[]> picPaths = new ArrayList<int[]>();
      /** Tight/Through outlines of {@link #floats} (null for a rectangle). */
      final List<float[]> floatOutlines = new ArrayList<float[]>();
      /** The cell's floats text wraps around, as {x0, y0, x1, y1, topBottom, side} in cell space. */
      final List<float[]> floats = new ArrayList<float[]>();
      final List<String> floatSides = new ArrayList<String>();
      final TableFormat.Cell format;
      final float left, width;
      float height, shift;
      /**
       * Turned text (see {@link Line#turn}): its lines are laid out {@link #len} long in the
       * cell's unturned frame and reach {@link #across} across the cell.
       */
      int turn;

      float len, across;

      CellLayout(Table t, int row, int col, float[] edges) {
        this(t, row, col, edges, NO_PATH, -1);
      }

      CellLayout(Table t, int row, int col, float[] edges, int[] prefix) {
        this(t, row, col, edges, prefix, -1);
      }

      CellLayout(Table t, int row, int col, float[] edges, int[] prefix, float forcedLength) {
        span = t.span(row, col);
        format = t.format.cell(row, col);
        // A right-to-left table (bidiVisual) mirrors column positions within the table.
        left =
            "true".equals(t.format.prop("rtl", ""))
                ? edges[0] + edges[t.columns()] - edges[col + span.columns]
                : edges[col];
        this.width = edges[col + span.columns] - edges[col];
        height = format.verticalPadding;
        float width = Math.max(1, this.width - 2 * format.horizontalPadding);
        String dir = prefix.length == 0 ? t.format.direction(row, col) : "lrTb";
        turn = "tbRl".equals(dir) ? 1 : "btLr".equals(dir) ? 2 : 0;
        if (turn != 0) {
          // As Wordcraft: turned lines run along the cell's height. An exact row height sets
          // their length; otherwise they get the length the text needs unwrapped (at most a
          // Letter page's body height) and the row grows to fit.
          if (forcedLength > 0) width = Math.min(TURNED_LIMIT, forcedLength);
          else if ("exact".equals(t.format.row(row).rule) && t.format.minHeight(row) > 0)
            width = clamp(t.format.minHeight(row) - 2 * format.verticalPadding, 4, TURNED_LIMIT);
          else width = clamp(naturalLength(t.blocks(row, col)), 4, TURNED_MAX);
          len = width;
        }
        var numbers = new ListNumbers();
        var content = t.blocks(row, col);
        for (int k = 0; k < content.size(); k++) {
          if (content.get(k) instanceof Table nested) {
            nestedTable(nested, k, prefix, width);
            continue;
          }
          if (content.get(k) instanceof Picture pic) {
            picIndex.add(k);
            picPaths.add(prefix);
            picture(pic, width);
            continue;
          }
          var p = (Paragraph) content.get(k);
          var marker = numbers.next(p);
          height += p.before;
          List<RawLine> cellLines;
          if (floats.isEmpty()) cellLines = LayoutEngine.this.lines(p, width);
          else {
            // As Wordcraft wraps cell text per line around the cell's floats.
            final float base = height, innerWidth = width;
            band = (top, h) -> {
              float[] c = cellCut(base + top, h, innerWidth);
              c[2] -= base;
              return c;
            };
            try {
              cellLines = LayoutEngine.this.lines(p, width);
            } finally {
              band = null;
            }
          }
          for (RawLine line : cellLines) {
            height += line.skip;
            float indent = p.indent + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1));
            float first = line.start == 0 ? p.format.firstIndent : 0;
            float x = format.horizontalPadding + indent + first + line.lead;
            float extra = width - indent - first - p.format.rightIndent - line.lead - line.cutRight - line.width;
            if ("center".equals(p.align)) x += extra / 2;
            if ("right".equals(p.align)) x += extra;
            // Right to left: mirrored within the cell's content box, as body lines in the column.
            if (p.format.rtl) x = 2 * format.horizontalPadding + width - x - line.width;
            lines.add(line);
            paragraphs.add(p);
            ids.add(p.id);
            markers.add(marker);
            paths.add(prefix);
            marker = "";
            xs.add(x);
            ys.add(height);
            height += line.height;
          }
          height += p.after;
        }
        for (float[] f : floats) height = Math.max(height, f[3]);
        for (float[] r : picBoxes) height = Math.max(height, r[1] + r[3]);
        if (turn != 0) {
          across = height - format.verticalPadding;
          height = len + format.verticalPadding;
        }
        height += format.verticalPadding;
      }

      /** The line length turned text needs so that nothing wraps (Wordcraft's natural_length). */
      private float naturalLength(Sequence<Block> content) {
        float most = 0;
        for (Block b : content) {
          if (!(b instanceof Paragraph p)) continue;
          float indent = p.indent + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1));
          for (RawLine line : LayoutEngine.this.lines(p, TURNED_MAX)) {
            float first = line.start == 0 ? Math.max(0, p.format.firstIndent) : 0;
            most = Math.max(most, indent + first + line.lead + line.width + Math.max(0, p.format.rightIndent));
          }
        }
        return most;
      }

      /**
       * A picture or drawing in the cell: inline ones take their own line in the cell flow (aligned
       * as their paragraph); floats are placed in the cell (Word's layoutInCell) from the current
       * paragraph position and text in the cell wraps around them line by line.
       */
      private void picture(Picture pic, float available) {
        String wrap = pic.wrap();
        float w = Math.min(pic.width, Math.max(1, available)), h = pic.height * (pic.width > 0 ? w / pic.width : 1);
        FloatPosition f = "inline".equals(wrap) ? null : FloatPosition.parse(pic.position());
        float ox = format.horizontalPadding, oy = height;
        if ("inline".equals(wrap)) {
          float x = format.horizontalPadding;
          if ("center".equals(pic.align)) x += (available - w) / 2;
          else if ("right".equals(pic.align)) x += available - w;
          pics.add(pic);
          picBoxes.add(new float[] {x, height, w, h, ox, oy, available});
          height += h;
          return;
        }
        // As Wordcraft's float_rect without a page frame: horizontally the cell column is the
        // reference whatever the relation; vertically the anchor paragraph's top with no extent,
        // so Center hangs the object half above it and Bottom/End wholly above it.
        String ha = f == null ? ("right".equals(pic.align) ? "end" : "center".equals(pic.align) ? "center" : "") : f.hAlign;
        float x = ox, y = oy;
        if ("center".equals(ha)) x += (available - w) / 2;
        else if ("end".equals(ha) || "right".equals(ha) || "outside".equals(ha)) x += available - w;
        else if (f != null && ha.isEmpty()) x += f.x;
        String va = f == null ? "" : f.vAlign;
        if (f != null && va.isEmpty()) y += f.y;
        else if ("center".equals(va)) y -= h / 2;
        else if ("bottom".equals(va) || "end".equals(va) || "outside".equals(va)) y -= h;
        pics.add(pic);
        picBoxes.add(new float[] {x, y, w, h, ox, oy, available});
        boolean topBottom = "topBottom".equals(wrap) || "topAndBottom".equals(wrap);
        boolean tight = "tight".equals(wrap) || "through".equals(wrap);
        if (!"front".equals(wrap) && !"behind".equals(wrap)) {
          float[] outline = tight ? WrapOutline.parse(pic.wrapPolygon()) : null;
          // A rotated object keeps text clear of its rotated bounds (Wordcraft's wrap_area).
          float[] r = outline == null ? rotatedBounds(x, y, w, h, rotation(pic)) : new float[] {x, y, w, h};
          float d = topBottom ? 0 : 9;
          floats.add(new float[] {r[0] - d, r[1], r[0] + r[2] + d, r[1] + r[3], topBottom ? 1 : 0, x, y, w, h, d});
          floatSides.add(f == null ? "bothSides" : f.side);
          floatOutlines.add(outline);
        }
      }

      /** Clockwise rotation in degrees of a picture (its look) or drawing. */
      private float rotation(Picture p) {
        return p.drawing != null ? p.drawing.num("rotation", 0) : p.look == null ? 0 : p.look.rotation;
      }

      /** The bounds {x, y, w, h} of the w x h box at x, y turned by deg about its centre. */
      private float[] rotatedBounds(float x, float y, float w, float h, float deg) {
        if (deg % 360 == 0) return new float[] {x, y, w, h};
        double r = Math.toRadians(deg);
        float c = (float) Math.abs(Math.cos(r)), sn = (float) Math.abs(Math.sin(r));
        float bw = w * c + h * sn, bh = w * sn + h * c;
        return new float[] {x + (w - bw) / 2, y + (h - bh) / 2, bw, bh};
      }

      /** As {@link #cut} for the cell's own floats, in content coordinates of width {@code inner}. */
      float[] cellCut(float yA, float h, float inner) {
        float left = 0, right = inner, pad = format.horizontalPadding;
        var spans = new ArrayList<float[]>();
        spans.add(new float[] {left, right});
        float firstBottom = Float.MAX_VALUE;
        boolean any = false;
        for (int i = 0; i < floats.size(); i++) {
          float[] e = floats.get(i);
          if (e[1] >= yA + h || e[3] <= yA) continue;
          if (e[4] != 0) return new float[] {0, 0, e[3]};
          float x0 = e[0] - pad, x1 = e[2] - pad;
          float[] outline = floatOutlines.get(i);
          if (outline != null && e[8] > 0) {
            // Tight/Through: text follows the wrap outline's extent over the line band.
            float[] r = WrapOutline.extent(outline, (yA - e[6]) / e[8], (yA + h - e[6]) / e[8]);
            if (r == null) continue;
            x0 = e[5] + r[0] * e[7] - e[9] - pad;
            x1 = e[5] + r[1] * e[7] + e[9] - pad;
          }
          String side = floatSides.get(i);
          if (!"bothSides".equals(side)) {
            boolean textLeft = "left".equals(side) || (!"right".equals(side) && x0 - left >= right - x1);
            if (textLeft) x1 = Math.max(right, x1);
            else x0 = Math.min(left, x0);
          }
          any = true;
          firstBottom = Math.min(firstBottom, e[3]);
          var next = new ArrayList<float[]>();
          for (float[] sp : spans)
            if (x1 <= sp[0] || x0 >= sp[1]) next.add(sp);
            else {
              next.add(new float[] {sp[0], Math.min(x0, sp[1])});
              next.add(new float[] {Math.max(x1, sp[0]), sp[1]});
            }
          spans = next;
        }
        if (!any) return new float[] {0, 0, yA};
        for (int i = spans.size() - 1; i >= 0; i--) if (spans.get(i)[1] - spans.get(i)[0] < 20) spans.remove(i);
        if (spans.isEmpty()) return new float[] {0, 0, firstBottom};
        float[] out = new float[3 + (spans.size() - 1) * 2];
        out[0] = spans.get(0)[0] - left;
        out[1] = right - spans.get(spans.size() - 1)[1];
        out[2] = yA;
        for (int i = 1; i < spans.size(); i++) {
          out[1 + i * 2] = spans.get(i - 1)[1] - left;
          out[2 + i * 2] = spans.get(i)[0] - left;
        }
        return out;
      }

      /**
       * Lays out a table nested in this cell as a unit (Wordcraft nests the same table layout in
       * cells): its rows sit between the surrounding cell paragraphs and move with this cell.
       */
      private void nestedTable(Table n, int k, int[] prefix, float available) {
        float[] edges = new float[n.columns() + 1];
        var widths = tableWidths(n, available);
        float total = 0;
        for (float w : widths) total += w;
        edges[0] = format.horizontalPadding + n.format.sizing.offset(available, total) - legacyShift(n);
        for (int c = 0; c < n.columns(); c++) edges[c + 1] = edges[c] + widths[c];
        float[] heights = new float[n.rows()], tops = new float[n.rows() + 1];
        for (int r = 0; r < heights.length; r++) heights[r] = Math.max(8, n.format.minHeight(r));
        var subs = new ArrayList<CellLayout>();
        for (int r = 0; r < n.rows(); r++)
          for (int c = 0; c < n.columns(); c++) {
            if (n.covered(r, c)) continue;
            int[] path = Arrays.copyOf(prefix, prefix.length + 3);
            path[prefix.length] = k;
            path[prefix.length + 1] = r;
            path[prefix.length + 2] = c;
            var sub = new CellLayout(n, r, c, edges, path);
            subs.add(sub);
            if (sub.span.rows == 1 && !"exact".equals(n.format.row(r).rule))
              heights[r] = Math.max(heights[r], sub.height);
          }
        atLeastInsets(n, subs, heights);
        for (CellLayout sub : subs)
          if (sub.span.rows > 1) {
            float spanned = 0;
            int last = sub.span.row + sub.span.rows - 1;
            for (int i = sub.span.row; i <= last; i++) spanned += heights[i];
            heights[last] += Math.max(0, sub.height - spanned);
          }
        for (int i = 0; i < heights.length; i++) tops[i + 1] = tops[i] + heights[i];
        for (CellLayout sub : subs) {
          float top = height + tops[sub.span.row];
          float cellHeight = tops[sub.span.row + sub.span.rows] - tops[sub.span.row];
          float extra = Math.max(0, cellHeight - sub.height);
          float shift =
              "center".equals(sub.format.vertical)
                  ? extra / 2
                  : "bottom".equals(sub.format.vertical) ? extra : 0;
          int[] own = Arrays.copyOf(prefix, prefix.length + 3);
          own[prefix.length] = k;
          own[prefix.length + 1] = sub.span.row;
          own[prefix.length + 2] = sub.span.column;
          inner.add(new InnerBox(own, sub.left, top, sub.width, cellHeight, sub.format));
          for (InnerBox b : sub.inner)
            inner.add(new InnerBox(b.path, sub.left + b.x, top + shift + b.y, b.width, b.height, b.format));
          for (int i = 0; i < sub.pics.size(); i++) {
            float[] r = sub.picBoxes.get(i);
            pics.add(sub.pics.get(i));
            picIndex.add(sub.picIndex.get(i));
            picPaths.add(sub.picPaths.get(i));
            picBoxes.add(
                new float[] {sub.left + r[0], top + shift + r[1], r[2], r[3], sub.left + r[4], top + shift + r[5], r[6]});
          }
          for (int i = 0; i < sub.lines.size(); i++) {
            lines.add(sub.lines.get(i));
            paragraphs.add(sub.paragraphs.get(i));
            ids.add(sub.ids.get(i));
            markers.add(sub.markers.get(i));
            paths.add(sub.paths.get(i));
            xs.add(sub.left + sub.xs.get(i));
            ys.add(top + shift + sub.ys.get(i));
          }
        }
        height += tops[n.rows()];
      }
    }

    /**
     * Word adds the cells' top and bottom margins to an at-least row height, as Wordcraft's
     * table layout does (a 30pt row with 5pt margins is 40pt tall even when its text needs less).
     */
    /**
     * A start-aligned table of a document in DOCX compatibility mode below 15 stands out by its
     * first cell's left margin, so that cell's text (not its border) is at the indent, as
     * Wordcraft's legacy table placement (table.rs start_offset).
     */
    float legacyShift(Table t) {
      if (noteDocument == null || t.rows() == 0 || t.columns() == 0) return 0;
      String mode = noteDocument.properties.setting(Docx.COMPAT_SETTING, "");
      if (mode.isEmpty() || "center".equals(t.format.sizing.align) || "right".equals(t.format.sizing.align))
        return 0;
      try {
        if (Integer.parseInt(mode) >= 15) return 0;
      } catch (RuntimeException ex) {
        return 0;
      }
      return t.format.cell(0, 0).horizontalPadding;
    }

    void atLeastInsets(Table t, List<CellLayout> cells, float[] heights) {
      for (CellLayout cell : cells) {
        int r = cell.span.row;
        float min = t.format.minHeight(r);
        if (min > 0 && !"exact".equals(t.format.row(r).rule))
          heights[r] = Math.max(heights[r], min + 2 * cell.format.verticalPadding);
      }
    }

    void mergedTable(Table t, int index) {
      numbering.reset();
      float[] edges = new float[t.columns() + 1];
      var widths = tableWidths(t, spec.contentWidth());
      float totalWidth = 0;
      for (float width : widths) totalWidth += width;
      edges[0] = t.format.sizing.offset(spec.contentWidth(), totalWidth) + tableShift - legacyShift(t);
      for (int c = 0; c < t.columns(); c++) edges[c + 1] = edges[c] + widths[c];
      var cells = new ArrayList<CellLayout>();
      float[] heights = new float[t.rows()], tops = new float[t.rows() + 1];
      for (int r = 0; r < heights.length; r++)
        heights[r] =
            "exact".equals(t.format.row(r).rule)
                ? t.format.minHeight(r)
                : Math.max(8, t.format.minHeight(r));
      for (int row = 0; row < t.rows(); row++)
        for (int col = 0; col < t.columns(); col++) {
          if (t.covered(row, col)) continue;
          var cell = new CellLayout(t, row, col, edges);
          cells.add(cell);
          if (cell.span.rows == 1 && !"exact".equals(t.format.row(row).rule))
            heights[row] = Math.max(heights[row], cell.height);
        }
      atLeastInsets(t, cells, heights);
      for (CellLayout cell : cells)
        if (cell.span.rows > 1) {
          float available = 0;
          for (int i = cell.span.row; i < cell.span.row + cell.span.rows; i++)
            available += heights[i];
          for (int i = cell.span.row + cell.span.rows - 1; i >= cell.span.row; i--)
            if (!"exact".equals(t.format.row(i).rule)) {
              heights[i] += Math.max(0, cell.height - available);
              break;
            }
        }
      for (int i = 0; i < heights.length; i++) tops[i + 1] = tops[i] + heights[i];
      if (measuring) {
        measured = tops[t.rows()];
        return;
      }
      // Turned lines run the cell's whole height: laid out again when the row grew (Wordcraft).
      for (int k = 0; k < cells.size(); k++) {
        var cell = cells.get(k);
        if (cell.turn == 0) continue;
        float full =
            tops[cell.span.row + cell.span.rows] - tops[cell.span.row]
                - 2 * cell.format.verticalPadding;
        if (Math.abs(full - cell.len) > .01f && full >= 4)
          cells.set(k, new CellLayout(t, cell.span.row, cell.span.column, edges, NO_PATH, full));
      }
      for (CellLayout cell : cells) {
        if (cell.turn != 0) continue;
        float extra =
            Math.max(0, tops[cell.span.row + cell.span.rows] - tops[cell.span.row] - cell.height);
        cell.shift =
            "center".equals(cell.format.vertical)
                ? extra / 2
                : "bottom".equals(cell.format.vertical) ? extra : 0;
      }
      var headers = new ArrayList<CellLayout>();
      for (CellLayout cell : cells) if (cell.span.row < t.format.headerRows) headers.add(cell);
      float headerHeight = tops[t.format.headerRows];
      // Like desktop word processors, oversized headers are not repeated over the body.
      if (headerHeight > (spec.height - spec.top - spec.bottom) / 2) {
        headers.clear();
        headerHeight = 0;
      }
      var byRow = new ArrayList<List<CellLayout>>();
      for (int i = 0; i < t.rows(); i++) byRow.add(new ArrayList<CellLayout>());
      for (CellLayout cell : cells) byRow.get(cell.span.row).add(cell);
      int row = 0;
      while (row < t.rows()) {
        int endRow = row + 1;
        var group = new ArrayList<CellLayout>();
        for (int at = row; at < endRow; at++)
          for (CellLayout cell : byRow.get(at)) {
            group.add(cell);
            endRow = Math.max(endRow, at + cell.span.rows);
          }
        float first = tops[row], last = tops[endRow];
        boolean keepTogether = row < t.format.headerRows;
        for (int i = row; i < endRow; i++) keepTogether |= !t.format.row(i).allowBreak;
        for (CellLayout cell : group) keepTogether |= cell.turn != 0;
        float freshCapacity =
            spec.height - spec.top - spec.bottom - (row >= t.format.headerRows ? headerHeight : 0);
        // Oversized unsplittable rows must make progress even on a fresh page.
        if (keepTogether
            && last - first <= freshCapacity
            && y + last - first + noteHeight > spec.height - spec.bottom
            && y > spec.top) {
          newTablePage(index, row >= t.format.headerRows, headers, tops, headerHeight);
        }
        while (first < last - .01f) {
          if (!pendingNotes.isEmpty()) newTablePage(index, false, headers, tops, headerHeight);
          float available = spec.height - spec.bottom - y - noteHeight;
          while (available < 12 && (y > spec.top || !footnotes.isEmpty())) {
            newTablePage(index, row >= t.format.headerRows, headers, tops, headerHeight);
            available = spec.height - spec.bottom - y - noteHeight;
          }
          float end = Math.min(last, first + available);
          // End this fragment after the first line containing a new note. This makes
          // space reservation atomic with its anchor, including vertically merged cells.
          for (CellLayout cell : group)
            for (int i = 0; i < cell.lines.size(); i++) {
              var line = cell.lines.get(i);
              float top = tops[cell.span.row] + cell.shift + (cell.turn != 0 ? 0 : cell.ys.get(i));
              if (top < first - .01f || top >= end || top >= tops[cell.span.row + cell.span.rows])
                continue;
              final boolean[] hasNote = {false};
              cell.paragraphs
                  .get(i)
                  .text
                  .slice(line.start, line.end)
                  .mapNotes(
                      (offset, note) -> {
                        if (!seenNotes.contains(note.id)) hasNote[0] = true;
                        return note;
                      });
              if (hasNote[0]) end = Math.min(end, top + line.height);
            }
          boolean changed;
          do {
            changed = false;
            for (CellLayout cell : group)
              for (int i = 0; i < cell.lines.size() && cell.turn == 0; i++) {
                float top = tops[cell.span.row] + cell.shift + cell.ys.get(i),
                    bottom = top + cell.lines.get(i).height;
                if (top > first + .01f
                    && top < end - .01f
                    && bottom > end + .01f
                    && bottom <= tops[cell.span.row + cell.span.rows] + .01f) {
                  end = top;
                  changed = true;
                }
              }
          } while (changed);
          if (end <= first + .01f) end = Math.min(last, first + Math.max(1, available));
          var anchors = new ArrayList<Note>();
          for (CellLayout cell : group)
            for (int i = 0; i < cell.lines.size(); i++) {
              float top = tops[cell.span.row] + cell.shift + (cell.turn != 0 ? 0 : cell.ys.get(i));
              if (top < first - .01f
                  || top >= end - .01f
                  || top >= tops[cell.span.row + cell.span.rows]) continue;
              var line = cell.lines.get(i);
              cell.paragraphs
                  .get(i)
                  .text
                  .slice(line.start, line.end)
                  .mapNotes(
                      (offset, note) -> {
                        anchors.add(note);
                        return note;
                      });
            }
          reserveNotes(anchors, end - first);
          emitCells(group, index, tops, first, end);
          y += end - first;
          first = end;
          if (first < last - .01f)
            newTablePage(index, row >= t.format.headerRows, headers, tops, headerHeight);
        }
        row = endRow;
      }
      y += 6;
    }

    void newTablePage(
        int index, boolean repeat, List<CellLayout> headers, float[] tops, float headerHeight) {
      nextColumn();
      if (repeat && !headers.isEmpty()) {
        emitCells(headers, index, tops, 0, headerHeight);
        y += headerHeight;
      }
    }

    void emitCells(List<CellLayout> cells, int index, float[] tops, float first, float end) {
      for (CellLayout cell : cells) {
        float top = tops[cell.span.row], bottom = tops[cell.span.row + cell.span.rows];
        float visibleTop = Math.max(first, top), visibleBottom = Math.min(end, bottom);
        if (visibleBottom > visibleTop)
          boxes.add(
              new Box(
                  index,
                  cell.span.row,
                  cell.span.column,
                  pages.size(),
                  spec.left + cell.left,
                  y + visibleTop - first,
                  cell.width,
                  visibleBottom - visibleTop,
                  cell.format));
        // Turned cells (btLr/tbRl): as Wordcraft's TurnedCell, everything the cell holds --
        // nested tables' cells and pictures as well as text -- turns with it, about the same
        // frame origin as the cell's lines, on the page where the cell starts.
        float[] turnAt = cell.turn != 0 && top >= first - .01f && top < end - .01f ? turnOrigin(cell, top, first) : null;
        if (cell.turn != 0) {
          float pad = cell.format.horizontalPadding, vpad = cell.format.verticalPadding;
          for (InnerBox b : cell.inner) {
            if (turnAt == null) break;
            float[] q = turnRect(cell.turn, turnAt, b.x - pad, b.y - vpad, b.width, b.height);
            boxes.add(new Box(index, cell.span.row, cell.span.column, pages.size(), q[0], q[1], q[2], q[3], b.format).at(b.path));
          }
          for (int i = 0; i < cell.pics.size() && turnAt != null; i++) {
            float[] r = cell.picBoxes.get(i);
            float[] q = turnRect(cell.turn, turnAt, r[0] - pad, r[1] - vpad, r[2], r[3]);
            int[] inner = cell.picPaths.get(i), path = new int[3 + inner.length];
            path[0] = index;
            path[1] = cell.span.row;
            path[2] = cell.span.column;
            System.arraycopy(inner, 0, path, 3, inner.length);
            // The picture keeps its own size, centred on its turned place and turned a quarter.
            float cx = q[0] + q[2] / 2, cy = q[1] + q[3] / 2;
            var pic = cell.pics.get(i);
            int deg = ((pic.look.rotation + (cell.turn == 1 ? 90 : 270)) % 360 + 360) % 360;
            pic = pic.look(pic.look.rotate(deg, pic.look.flipH, pic.look.flipV));
            pictures.add(
                new PlacedPicture(
                    pic, cell.picIndex.get(i), pages.size(), cx - r[2] / 2, cy - r[3] / 2, r[2], r[3],
                    path, new float[] {q[0], q[1], r[6]}));
          }
        }
        for (InnerBox b : cell.inner) {
          if (cell.turn != 0) break;
          float boxTop = top + cell.shift + b.y;
          float shownTop = Math.max(first, boxTop), shownBottom = Math.min(end, boxTop + b.height);
          if (shownBottom > shownTop)
            boxes.add(
                new Box(
                        index,
                        cell.span.row,
                        cell.span.column,
                        pages.size(),
                        spec.left + cell.left + b.x,
                        y + shownTop - first,
                        b.width,
                        shownBottom - shownTop,
                        b.format)
                    .at(b.path));
        }
        for (int i = 0; i < cell.pics.size() && cell.turn == 0; i++) {
          float[] r = cell.picBoxes.get(i);
          float picTop = top + cell.shift + r[1], anchor = top + cell.shift + r[5];
          // Drawn on the page where its anchor paragraph shows.
          if (anchor >= first - .01f && anchor < end - .01f) {
            int[] inner = cell.picPaths.get(i), path = new int[3 + inner.length];
            path[0] = index;
            path[1] = cell.span.row;
            path[2] = cell.span.column;
            System.arraycopy(inner, 0, path, 3, inner.length);
            float ox = spec.left + cell.left;
            pictures.add(
                new PlacedPicture(
                    cell.pics.get(i), cell.picIndex.get(i), pages.size(), ox + r[0], y + picTop - first, r[2], r[3],
                    path, new float[] {ox + r[4], y + anchor - first, r[6]}));
          }
        }
        if (cell.turn != 0) {
          // Turned text is drawn whole on the page where its cell starts (Wordcraft keeps a
          // turned cell's row in one piece).
          if (turnAt == null) continue;
          float pad = cell.format.horizontalPadding, vpad = cell.format.verticalPadding;
          float ox = turnAt[0], oy = turnAt[1];
          for (int i = 0; i < cell.lines.size(); i++)
            current.add(
                new Line(
                        index,
                        cell.span.row,
                        cell.span.column,
                        cell.ids.get(i),
                        pages.size(),
                        cell.xs.get(i) - pad,
                        cell.ys.get(i) - vpad,
                        cell.lines.get(i),
                        cell.markers.get(i))
                    .at(cell.paths.get(i))
                    .turned(cell.turn, ox, oy));
          continue;
        }
        for (int i = 0; i < cell.lines.size(); i++) {
          float lineY = top + cell.shift + cell.ys.get(i);
          if (lineY >= first - .01f && lineY < end - .01f && lineY < bottom - .01f)
            current.add(
                new Line(
                    index,
                    cell.span.row,
                    cell.span.column,
                    cell.ids.get(i),
                    pages.size(),
                    spec.left + cell.left + cell.xs.get(i),
                    y + lineY - first,
                    cell.lines.get(i),
                    cell.markers.get(i),
                    Math.max(0, Math.min(bottom, end) - lineY))
                .at(cell.paths.get(i))
                .inNote(index == -2 ? endnote : null, endnoteBlock));
        }
      }
    }

    /** The page origin of a turned cell's frame (as its lines' turnX/turnY). */
    private float[] turnOrigin(CellLayout cell, float top, float first) {
      float pad = cell.format.horizontalPadding, vpad = cell.format.verticalPadding;
      float cw = Math.max(0, cell.width - 2 * pad);
      float off =
          "center".equals(cell.format.vertical)
              ? Math.max(0, (cw - cell.across) / 2)
              : "bottom".equals(cell.format.vertical) ? Math.max(0, cw - cell.across) : 0;
      float cellX = spec.left + cell.left, cellY = y + top - first;
      return new float[] {
        cell.turn == 2 ? cellX + pad + off : cellX + pad + cw - off,
        cell.turn == 2 ? cellY + vpad + cell.len : cellY + vpad
      };
    }

    void appendEndnotes(List<Note> notes) {
      if (notes.isEmpty()) return;
      while (!pendingNotes.isEmpty()) newPage();
      if (y > spec.top) y += 12;
      for (Note note : notes) {
        int index = -1;
        for (Block block : note.displayedBlocks()) {
          index++;
          if (block instanceof Paragraph p) paragraph(p, -2, null);
          else if (block instanceof Table t) {
            endnote = note.id;
            endnoteBlock = index;
            try {
              table(t, -2);
            } finally {
              endnote = null;
            }
          } else if (block instanceof Picture picture) picture(picture, -2);
        }
      }
      notes.clear();
    }

    void finishSectionNotes() {
      appendEndnotes(sectionEndnotes);
    }

    void finish() {
      finishSectionNotes();
      while (!pendingNotes.isEmpty()) newPage();
      appendEndnotes(endnotes);
      closeRegion();
      pages.add(
          page(new Page(
              pagePaper,
              owner,
              pages.size() - ownerFirstPage,
              number,
              current,
              boxes,
              pictures,
              Collections.<Line>emptyList(),
              Collections.<Line>emptyList(),
              placedNotes())));
    }
  }
}
