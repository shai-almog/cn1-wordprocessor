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
    public final String paragraphId, marker;
    public final float x, y, width, height;

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

  public record PlacedPicture(
      Picture picture, int block, int page, float x, float y, float width, float height) {}

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
    var out = new ArrayList<Line>();
    for (Line line : lines) {
      var raw = new RawLine();
      raw.start = line.start;
      raw.end = line.end;
      raw.width = line.width;
      raw.height = line.height;
      raw.clipped = line.clipped;
      raw.markerOffset = line.markerOffset;
      raw.spans.addAll(line.spans);
      raw.barTabs = line.barTabs;
      out.add(
          new Line(line.block, line.row, line.column, line.paragraphId, line.page, line.x,
              line.y + dy, raw, line.marker, line.clipHeight).at(line.path));
    }
    return out;
  }

  private static List<Box> shiftBoxes(List<Box> boxes, float dy) {
    var out = new ArrayList<Box>();
    for (Box b : boxes)
      out.add(
          new Box(b.block, b.row, b.column, b.page, b.x, b.y + dy, b.width, b.height, b.format)
              .at(b.path));
    return out;
  }

  private static List<PlacedPicture> shiftPictures(List<PlacedPicture> pictures, float dy) {
    var out = new ArrayList<PlacedPicture>();
    for (PlacedPicture p : pictures)
      out.add(new PlacedPicture(p.picture, p.block, p.page, p.x, p.y + dy, p.width, p.height));
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

  private static final class RawLine {
    final List<Span> spans = new ArrayList<>();
    List<Float> barTabs = Collections.emptyList();
    int start, end;
    float width, height, markerOffset;
    boolean clipped;

    /** Extra left offset beside a drop cap, and the baseline from the line top. */
    float lead, baseline;

    /** The first line of a drop-capped paragraph carries the large letter and its x offset. */
    RawLine drop;

    float dropX;
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
    if (c != null && c.width == width && c.drop == dropped) {
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
    do {
      var line = new RawLine();
      if (out.size() < dropLines) line.lead = dropWidth;
      float available = Math.max(1, width - (start == 0 ? p.format.firstIndent : 0) - line.lead);
      line.start = start;
      line.height = metrics.height(TextStyle.DEFAULT);
      int end = start == 0 ? skip : start, lastBreak = -1;
      boolean hyphenated = false;
      float x = 0;
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
        if ("\n".equals(ch) && !st[end].characters.hidden) {
          break;
        }
        float w =
            st[end].characters.hidden
                ? 0
                : "\t".equals(ch)
                    ? tabWidth(p, text, end, x, start == 0 ? p.format.firstIndent : 0)
                    : clusterWidth(p, text, end, end + count, st, obj);
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
        if (Character.isWhitespace(ch.charAt(0))) lastBreak = end;
      }
      if (end == start && end < text.length() && text.charAt(end) != '\n')
        end = Graphemes.next(p.text, end);
      line.end = end;
      float pos = 0;
      int a = start == 0 ? skip : start;
      while (a < end) {
        var s = st[a];
        int b = a + 1;
        while (b < end
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
            s.characters.hidden
                ? 0
                : "\t".equals(chunk)
                    ? tabWidth(p, text, a, pos, start == 0 ? p.format.firstIndent : 0)
                    : obj[a] ? objectWidth(p, a, display, s) : metrics.width(display, s);
        var math = obj[a] ? inlineMath(p, a, s) : null;
        if (math != null) {
          display = "";
          if (!s.characters.hidden) w = math.width;
        }
        if (hyphenated && b == end) {
          display += "-";
          w += metrics.width("-", s);
        }
        if ("\t".equals(chunk)) {
          var stop = nextTab(p, pos + (start == 0 ? p.format.firstIndent : 0));
          display = stop == null ? "" : leader(stop.glyph(), s, w);
        }
        var pieces = obj[a] && math == null && !s.characters.hidden ? fieldPieces(p, a) : null;
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
        if (!s.characters.hidden) line.height = Math.max(line.height, metrics.height(s));
        a = b;
      }
      line.width = pos;
      boolean softWrapped = end < text.length() && text.charAt(end) != '\n';
      if (("both".equals(p.align) || "justify".equals(p.align)) && softWrapped)
        justify(line, available, false, p.text);
      else if ("distribute".equals(p.align) && end > start) justify(line, available, true, p.text);
      // UAX #9 L1: a tab is a segment separator at paragraph level, so each tab-delimited segment is
      // reordered within its own extent and the tabs (and their stops) stay put.
      if (levels != null) {
        int from = 0;
        for (int i = 0; i <= line.spans.size(); i++)
          if (i == line.spans.size() || "\t".equals(line.spans.get(i).text)) {
            if (i > from) Bidi.reorder(line.spans.subList(from, i), levels, p.format.rtl);
            from = i + 1;
          }
      }
      float textOffset = start == 0 ? p.format.firstIndent : 0;
      if ("center".equals(p.align)) textOffset += (available - line.width) / 2;
      else if ("right".equals(p.align)) textOffset += available - line.width;
      for (TabStop stop : p.format.tabs)
        if ("bar".equals(stop.alignment)) {
          if (line.barTabs.isEmpty()) line.barTabs = new ArrayList<>();
          line.barTabs.add(stop.position - textOffset);
        }
      float baseline = metrics.ascent(TextStyle.DEFAULT);
      for (Span span : line.spans)
        if (!span.style.characters.hidden)
          baseline =
              Math.max(
                  baseline,
                  metrics.ascent(span.style.renderedStyle()) + span.style.baselineShift());
      // Inline equations rise above and hang below the text by their box's ascent and descent.
      float mathDescent = 0;
      for (Span span : line.spans)
        if (!span.style.characters.hidden && InlineField.OBJECT.equals(span.text)) {
          var math = inlineMath(p, span.start, span.style);
          if (math == null) continue;
          baseline = Math.max(baseline, math.ascent);
          mathDescent = Math.max(mathDescent, math.descent);
        }
      for (int i = 0; i < line.spans.size(); i++) {
        var span = line.spans.get(i);
        float top = baseline - metrics.ascent(span.glyphStyle) - span.style.baselineShift();
        line.spans.set(i, span.top(top));
        if (!span.style.characters.hidden)
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
      if (p.text.objectAt(i) || p.text.styleAt(i).characters.hidden) return -1;
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
      if (!"bar".equals(tab.alignment) && tab.position > position + .01f) return tab;
    return null;
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

  public Result layout(Document doc, long revision) {
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
                resolvedFurniture(
                    context, side == 1, 0, 1, section.pageStart == 0 ? 1 : section.pageStart),
                section.page.contentWidth());
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
                  resolvedFurniture(
                      context, side == 1, page.sectionPage, b.pages.size(), page.number),
                  context.page.contentWidth());
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
      var rebuilt =
          new Page(
              page.spec,
              page.section,
              page.sectionPage,
              page.number,
              dy == 0 ? page.lines : shiftLines(page.lines, dy),
              dy == 0 ? page.boxes : shiftBoxes(page.boxes, dy),
              dy == 0 ? page.pictures : shiftPictures(page.pictures, dy),
              furnitureLines(
                  context,
                  false,
                  i,
                  page.sectionPage,
                  page.number,
                  b.pages.size(),
                  body.top - context.furniture.headerDistance,
                  warnings),
              furnitureLines(
                  context,
                  true,
                  i,
                  page.sectionPage,
                  page.number,
                  b.pages.size(),
                  body.bottom - context.furniture.footerDistance,
                  warnings),
              page.notes);
      rebuilt.columnRules.addAll(page.columnRules);
      for (Frame f : page.frames)
        rebuilt.frames.add(
            dy == 0
                ? f
                : new Frame(f.x, f.top + dy, f.width, f.bottom + dy, f.borders, f.shading, f.first, f.last));
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

  private Sequence<Paragraph> resolvedFurniture(
      Document doc, boolean footer, int page, int count, int number) {
    var result = new Sequence<Paragraph>();
    for (Paragraph p : doc.furniture.get(doc.furniture.key(footer, page, number))) {
      var resolved =
          p.text.mapFields(
              (offset, field) ->
                  DocumentFields.resolve(
                      field, doc.title, number, count, Collections.<String, String>emptyMap(),
                      doc.lastSection.decor.numberFormat));
      result = result.append(resolved == p.text ? p : p.text(resolved));
    }
    return result;
  }

  private float furnitureHeight(Sequence<Paragraph> paragraphs, float width) {
    float height = 0;
    for (Paragraph p : paragraphs) {
      height += p.before + p.after;
      for (RawLine line : lines(p, width)) height += line.height;
    }
    return height;
  }

  private List<Line> furnitureLines(
      Document doc,
      boolean footer,
      int page,
      int sectionPage,
      int number,
      int count,
      float available,
      Set<String> warnings) {
    var paragraphs = resolvedFurniture(doc, footer, sectionPage, count, number);
    var result = new ArrayList<Line>();
    float y = 0;
    var numbering = new ListNumbers();
    boolean overflow = false;
    story:
    for (Paragraph p : paragraphs) {
      y += p.before;
      var marker = numbering.next(p);
      for (RawLine line : lines(p, doc.page.contentWidth())) {
        if (y + line.height > available + .01f) {
          overflow = true;
          break story;
        }
        float indent = p.indent + (line.start == 0 ? p.format.firstIndent : 0),
            x = doc.page.left + indent;
        float remaining = doc.page.contentWidth() - indent - p.format.rightIndent - line.width;
        if ("center".equals(p.align)) x += remaining / 2;
        else if ("right".equals(p.align)) x += remaining;
        result.add(new Line(-1, -1, -1, p.id, page, x, y, line, marker));
        marker = "";
        y += line.height;
      }
      y += p.after;
    }
    if (overflow || paragraphs.size() > 0 && y > available + .01f)
      warnings.add(
          (footer ? "Footer" : "Header")
              + " on page "
              + (page + 1)
              + " does not fit. Reduce its content, spacing or distance from the page"
              + " edge.");
    float start =
        footer ? doc.page.height - doc.furniture.footerDistance - y : doc.furniture.headerDistance;
    var placed = new ArrayList<Line>();
    for (Line line : result) {
      var raw = new RawLine();
      raw.start = line.start;
      raw.end = line.end;
      raw.width = line.width;
      raw.height = line.height;
      raw.clipped = line.clipped;
      raw.markerOffset = line.markerOffset;
      raw.spans.addAll(line.spans);
      raw.barTabs = line.barTabs;
      placed.add(
          new Line(-1, -1, -1, line.paragraphId, page, line.x, start + line.y, raw, line.marker));
    }
    return Collections.unmodifiableList(placed);
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

      NoteLine(Paragraph p, RawLine line, String marker, float before, float after) {
        beneath = "beneathText".equals(noteOptions.footnote.pos);
        this.id = p.id;
        this.line = line;
        this.before = before;
        this.after = after;
        this.marker = marker;
        float indent = p.indent + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1));
        float first = line.start == 0 ? p.format.firstIndent : 0;
        float extra = spec.contentWidth() - indent - first - p.format.rightIndent - line.width;
        x =
            indent
                + first
                + ("center".equals(p.align) ? extra / 2 : "right".equals(p.align) ? extra : 0);
      }

      float height() {
        return before + line.height + after;
      }
    }

    List<NoteLine> noteLines(Note note) {
      var result = new ArrayList<NoteLine>();
      var numbers = new ListNumbers();
      for (Paragraph p : note.displayedParagraphs()) {
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
      var ls = lines(p, spec.contentWidth(), index >= 0);
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
          c.clipped = l.clipped;
          c.lead = l.lead;
          c.baseline = l.baseline;
          c.drop = l.drop;
          c.dropX = l.dropX;
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
        if ("center".equals(p.align))
          x += (spec.contentWidth() - indent - p.format.rightIndent - firstIndent - l.lead - l.width) / 2;
        if ("right".equals(p.align))
          x += spec.contentWidth() - indent - p.format.rightIndent - firstIndent - l.lead - l.width;
        current.add(new Line(index, -1, -1, p.id, pages.size(), x, y, l, marker));
        // After the first line, so caret lookups for offset 0 find the text line first.
        if (l.drop != null)
          current.add(new Line(index, -1, -1, p.id, pages.size(), spec.left + indent + l.dropX, y, l.drop, ""));
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
    }

    void picture(Picture p, int index) {
      numbering.reset();
      String wrap = p.wrap();
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

    void table(Table t, int index) {
      clearWrap();
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
      final TableFormat.Cell format;
      final float left, width;
      float height, shift;

      CellLayout(Table t, int row, int col, float[] edges) {
        this(t, row, col, edges, NO_PATH);
      }

      CellLayout(Table t, int row, int col, float[] edges, int[] prefix) {
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
        var numbers = new ListNumbers();
        var content = t.blocks(row, col);
        for (int k = 0; k < content.size(); k++) {
          if (content.get(k) instanceof Table nested) {
            nestedTable(nested, k, prefix, width);
            continue;
          }
          var p = (Paragraph) content.get(k);
          var marker = numbers.next(p);
          height += p.before;
          for (RawLine line : LayoutEngine.this.lines(p, width)) {
            float indent = p.indent + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1));
            float first = line.start == 0 ? p.format.firstIndent : 0;
            float x = format.horizontalPadding + indent + first;
            float extra = width - indent - first - p.format.rightIndent - line.width;
            if ("center".equals(p.align)) x += extra / 2;
            if ("right".equals(p.align)) x += extra;
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
        height += format.verticalPadding;
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
        edges[0] = format.horizontalPadding + n.format.sizing.offset(available, total);
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

    void mergedTable(Table t, int index) {
      numbering.reset();
      float[] edges = new float[t.columns() + 1];
      var widths = tableWidths(t, spec.contentWidth());
      float totalWidth = 0;
      for (float width : widths) totalWidth += width;
      edges[0] = t.format.sizing.offset(spec.contentWidth(), totalWidth);
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
      for (CellLayout cell : cells) {
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
              float top = tops[cell.span.row] + cell.shift + cell.ys.get(i);
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
              for (int i = 0; i < cell.lines.size(); i++) {
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
              float top = tops[cell.span.row] + cell.shift + cell.ys.get(i);
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
        for (InnerBox b : cell.inner) {
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
                .at(cell.paths.get(i)));
        }
      }
    }

    void appendEndnotes(List<Note> notes) {
      if (notes.isEmpty()) return;
      while (!pendingNotes.isEmpty()) newPage();
      if (y > spec.top) y += 12;
      for (Note note : notes) for (Paragraph p : note.displayedParagraphs()) paragraph(p, -2, null);
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
