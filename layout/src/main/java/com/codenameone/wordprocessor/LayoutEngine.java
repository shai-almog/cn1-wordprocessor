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
    }
  }

  public static final class Line {
    public final int block, row, column, start, end, page;
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
    List<PlacedPicture> out = new ArrayList<PlacedPicture>();
    for (PlacedPicture p : pictures) {
      Drawing d = p.picture.drawing;
      if (front == (d != null && "front".equals(d.get("wrap", "inline")))) out.add(p);
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
    Drawing d = p.picture.drawing;
    if (d == null) return 0;
    return d.num("z", 0) + ("behind".equals(d.get("wrap", "inline")) ? -10000 : 0);
  }

  public static final class PlacedPicture {
    public final Picture picture;
    public final int block, page;
    public final float x, y, width, height;

    PlacedPicture(
        Picture picture, int block, int page, float x, float y, float width, float height) {
      this.picture = picture;
      this.block = block;
      this.page = page;
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
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
    List<Line> out = new ArrayList<Line>();
    for (Line line : lines) {
      RawLine raw = new RawLine();
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
              line.y + dy, raw, line.marker, line.clipHeight));
    }
    return out;
  }

  private static List<Box> shiftBoxes(List<Box> boxes, float dy) {
    List<Box> out = new ArrayList<Box>();
    for (Box b : boxes)
      out.add(new Box(b.block, b.row, b.column, b.page, b.x, b.y + dy, b.width, b.height, b.format));
    return out;
  }

  private static List<PlacedPicture> shiftPictures(List<PlacedPicture> pictures, float dy) {
    List<PlacedPicture> out = new ArrayList<PlacedPicture>();
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
    public final List<float[]> columnRules = new ArrayList<float[]>();

    /** Paragraph border/shading boxes, painted under the text. */
    public final List<Frame> frames = new ArrayList<Frame>();

    /** Page color, watermark and page borders of this page's section. */
    public PageDecor decor = PageDecor.NONE;

    /** Margin line numbers as {y, number} in points, set from the section's line numbering. */
    public final List<float[]> lineNumbers = new ArrayList<float[]>();

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
        List<Line> merged = new ArrayList<Line>(header);
        merged.addAll(lines);
        merged.addAll(notes);
        merged.addAll(footer);
        all = Collections.unmodifiableList(merged);
      }
    }

    /** First line of each note area on this page, used for shared separator rendering. */
    public List<Line> noteAreas() {
      List<Line> result = new ArrayList<Line>();
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
    final List<Span> spans = new ArrayList<Span>();
    List<Float> barTabs = Collections.emptyList();
    int start, end;
    float width, height, markerOffset;
    boolean clipped;
  }

  private static final class Cached {
    final float width;
    final List<RawLine> lines;

    Cached(float w, List<RawLine> l) {
      width = w;
      lines = l;
    }
  }

  private final MeasurementCache metrics;
  private Map<Paragraph, Cached> cache = new HashMap<Paragraph, Cached>();
  private Map<Paragraph, Cached> nextCache;
  private int measured;

  public LayoutEngine(Metrics m) {
    metrics = new MeasurementCache(m);
  }

  public void clear() {
    cache.clear();
    metrics.clear();
    numberedSource = numberedView = null;
  }

  /** Resolve the same column geometry used by painting, hit testing and PDF export. */
  public float[] tableWidths(Table table, float available) {
    TableFormat f = table.format;
    float[] widths = new float[table.columns()];
    float target = f.preferredWidth(available);
    if (f.sizing.fixed) {
      for (int c = 0; c < widths.length; c++) widths[c] = target * f.columnFraction(c);
      return widths;
    }
    float[] min = new float[widths.length], max = new float[widths.length];
    List<float[]> spans = new ArrayList<float[]>();
    for (int r = 0; r < table.rows(); r++)
      for (int c = 0; c < table.columns(); c++) {
        if (table.covered(r, c)) continue;
        Table.Merge span = table.span(r, c);
        float smallest = 1, largest = 1, padding = 2 * f.cell(r, c).horizontalPadding;
        for (Paragraph p : table.cell(r, c)) {
          float indents =
              p.indent
                  + p.format.rightIndent
                  + Math.max(0, p.format.firstIndent)
                  + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1));
          String text = p.text.toString();
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
    width =
        Math.max(
            1,
            width
                - p.indent
                - p.format.rightIndent
                - (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1)));
    Cached c = nextCache.get(p);
    if (c == null) c = cache.get(p);
    if (c != null && c.width == width) {
      nextCache.put(p, c);
      return c.lines;
    }
    measured++;
    List<RawLine> out = new ArrayList<RawLine>();
    String text = p.text.toString();
    int start = 0;
    do {
      RawLine line = new RawLine();
      float available = Math.max(1, width - (start == 0 ? p.format.firstIndent : 0));
      line.start = start;
      line.height = metrics.height(TextStyle.DEFAULT);
      int end = start, lastBreak = -1;
      float x = 0;
      while (end < text.length()) {
        int count = Graphemes.next(p.text, end) - end;
        String ch =
            count == 1 && text.charAt(end) < 128
                ? ASCII[text.charAt(end)]
                : text.substring(end, end + count);
        if ("\n".equals(ch) && !p.text.styleAt(end).characters.hidden) {
          break;
        }
        float w =
            p.text.styleAt(end).characters.hidden
                ? 0
                : "\t".equals(ch)
                    ? tabWidth(p, text, end, x, start == 0 ? p.format.firstIndent : 0)
                    : clusterWidth(p, text, end, end + count);
        if (x + w > available && end > start) {
          if (lastBreak > start) end = lastBreak;
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
      int a = start;
      while (a < end) {
        TextStyle s = p.text.styleAt(a);
        int b = a + 1;
        while (b < end
            && !p.text.objectAt(a)
            && !p.text.objectAt(b)
            && text.charAt(a) != '\t'
            && text.charAt(b) != '\t'
            && (!s.characters.smallCaps
                || s.characters.allCaps
                || !Graphemes.isBoundary(p.text, b)
                || Character.isLowerCase(Character.codePointAt(text, a))
                    == Character.isLowerCase(Character.codePointAt(text, b)))
            && s.equals(p.text.styleAt(b))) b++;
        String chunk = text.substring(a, b);
        float w =
            s.characters.hidden
                ? 0
                : "\t".equals(chunk)
                    ? tabWidth(p, text, a, pos, start == 0 ? p.format.firstIndent : 0)
                    : metrics.width(p.text.displayAt(a, chunk), s);
        String display = p.text.displayAt(a, chunk);
        if ("\t".equals(chunk)) {
          TabStop stop = nextTab(p, pos + (start == 0 ? p.format.firstIndent : 0));
          display = stop == null ? "" : leader(stop.glyph(), s, w);
        }
        line.spans.add(new Span(chunk, s, a, pos, w, 0, display));
        pos += w;
        if (!s.characters.hidden) line.height = Math.max(line.height, metrics.height(s));
        a = b;
      }
      line.width = pos;
      boolean softWrapped = end < text.length() && text.charAt(end) != '\n';
      if (("both".equals(p.align) || "justify".equals(p.align)) && softWrapped)
        justify(line, available, false, p.text);
      else if ("distribute".equals(p.align) && end > start) justify(line, available, true, p.text);
      float textOffset = start == 0 ? p.format.firstIndent : 0;
      if ("center".equals(p.align)) textOffset += (available - line.width) / 2;
      else if ("right".equals(p.align)) textOffset += available - line.width;
      for (TabStop stop : p.format.tabs)
        if ("bar".equals(stop.alignment)) {
          if (line.barTabs.isEmpty()) line.barTabs = new ArrayList<Float>();
          line.barTabs.add(stop.position - textOffset);
        }
      float baseline = metrics.ascent(TextStyle.DEFAULT);
      for (Span span : line.spans)
        if (!span.style.characters.hidden)
          baseline =
              Math.max(
                  baseline,
                  metrics.ascent(span.style.renderedStyle()) + span.style.baselineShift());
      for (int i = 0; i < line.spans.size(); i++) {
        Span span = line.spans.get(i);
        float top = baseline - metrics.ascent(span.glyphStyle) - span.style.baselineShift();
        line.spans.set(
            i,
            new Span(span.text, span.style, span.start, span.x, span.width, top, span.rawDisplay));
        if (!span.style.characters.hidden)
          line.height = Math.max(line.height, (top + metrics.height(span.glyphStyle)));
      }
      line.markerOffset = baseline - metrics.ascent(TextStyle.DEFAULT);
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
          Span span = line.spans.get(i);
          line.spans.set(
              i,
              new Span(
                  span.text,
                  span.style,
                  span.start,
                  span.x,
                  span.width,
                  span.top + offset,
                  span.rawDisplay));
        }
      }
      out.add(line);
      start = end;
      if (start < text.length() && text.charAt(start) == '\n') start++;
    } while (start < text.length());
    c = new Cached(width, out);
    nextCache.put(p, c);
    return out;
  }

  // Keep a cluster on one line even when a formatting boundary divides its glyphs.
  private float clusterWidth(Paragraph p, String text, int start, int end) {
    float width = 0;
    for (int at = start; at < end; ) {
      TextStyle style = p.text.styleAt(at);
      int next = at + 1;
      while (next < end
          && !p.text.objectAt(at)
          && !p.text.objectAt(next)
          && style.equals(p.text.styleAt(next))) next++;
      width += metrics.width(p.text.displayAt(at, text.substring(at, next)), style);
      at = next;
    }
    return width;
  }

  private TabStop nextTab(Paragraph p, float position) {
    for (TabStop tab : p.format.tabs)
      if (!"bar".equals(tab.alignment) && tab.position > position + .01f) return tab;
    return null;
  }

  private float tabWidth(Paragraph p, String text, int offset, float x, float origin) {
    float position = x + origin;
    TabStop tab = nextTab(p, position);
    if (tab == null)
      return p.format.tabInterval
          - ((position % p.format.tabInterval + p.format.tabInterval) % p.format.tabInterval);
    float following = 0;
    if (!"left".equals(tab.alignment)) {
      for (int i = offset + 1;
          i < text.length() && text.charAt(i) != '\t' && text.charAt(i) != '\n'; ) {
        if ("decimal".equals(tab.alignment) && text.charAt(i) == '.') break;
        int end = i + Character.charCount(Character.codePointAt(text, i));
        InlineField field = p.text.fieldAt(i);
        String followingText = field == null ? text.substring(i, end) : field.display();
        boolean decimal = "decimal".equals(tab.alignment) && followingText.indexOf('.') >= 0;
        if (decimal) followingText = followingText.substring(0, followingText.indexOf('.'));
        following += metrics.width(followingText, p.text.styleAt(i));
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
    StringBuilder out = new StringBuilder(count);
    for (int i = 0; i < count; i++) out.append(glyph);
    return out.toString();
  }

  private void justify(RawLine line, float available, boolean distributed, RichText source) {
    for (Span span : line.spans)
      if ("\t".equals(span.text) || InlineField.OBJECT.equals(span.text)) return;
    List<Span> units = new ArrayList<Span>();
    int lastContent = line.start;
    for (Span span : line.spans)
      for (int i = 0; i < span.text.length(); ) {
        int count =
            Math.min(
                span.text.length() - i, Graphemes.next(source, span.start + i) - span.start - i);
        if (!distributed && span.text.charAt(i) != ' ' && span.text.charAt(i) != '\t')
          while (i + count < span.text.length()
              && span.text.charAt(i + count) != ' '
              && span.text.charAt(i + count) != '\t') count++;
        String text = span.text.substring(i, i + count);
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
      line.spans.add(new Span(unit.text, unit.style, unit.start, x, w));
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
      Map<String, List<Line>> byParagraph = new HashMap<String, List<Line>>();
      for (Page page : result.pages)
        for (Line line : page.lines) {
          if (line.block < 0) continue;
          List<Line> list = byParagraph.get(line.paragraphId);
          if (list == null) byParagraph.put(line.paragraphId, list = new ArrayList<Line>());
          list.add(line);
        }
      Map<String, Integer> pages = new HashMap<String, Integer>();
      for (Notes.Reference ref : Notes.references(view)) {
        Block block = view.blocks.get(ref.block);
        Paragraph p =
            block instanceof Paragraph
                ? (Paragraph) block
                : ((Table) block).cell(ref.row, ref.column).get(ref.paragraph);
        List<Line> lines = byParagraph.get(p.id);
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
    Set<String> warnings = new LinkedHashSet<String>(result.warnings);
    warnings.add("Page-restarted note numbering did not settle after eight pagination passes");
    return new Result(result.document, result.pages, result.measuredParagraphs, revision, warnings);
  }

  private Result layoutOnce(Document doc, long revision) {
    nextCache = new HashMap<Paragraph, Cached>();
    measured = 0;
    List<Section.Range> ranges = doc.sectionRanges();
    List<Document> contexts = new ArrayList<Document>();
    float[][] heights = new float[ranges.size()][2];
    for (int i = 0; i < ranges.size(); i++) {
      Section section = ranges.get(i).resolved;
      Document context = doc.page(section.page).furniture(section.furniture);
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
        Section.Range range = ranges.get(section);
        bodies[section] = bodySpec(contexts.get(section), heights[section][0], heights[section][1]);
        b.beginSection(section, range.resolved, bodies[section]);
        for (int index = range.first; index <= range.last; index++) {
          Block block = doc.blocks.get(index);
          if (block instanceof Paragraph)
            b.paragraph(
                (Paragraph) block,
                index,
                index < range.last && doc.blocks.get(index + 1) instanceof Paragraph
                    ? (Paragraph) doc.blocks.get(index + 1)
                    : null);
          else if (block instanceof Table) b.table((Table) block, index);
          else if (block instanceof Picture) b.picture((Picture) block, index);
        }
      }
      b.finish();
      boolean grow = false;
      for (Page page : b.pages) {
        Document context = contexts.get(page.section);
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
    Set<String> warnings = new LinkedHashSet<String>();
    int lineCount = 0;
    for (int i = 0; i < b.pages.size(); i++) {
      Page page = b.pages.get(i);
      Document context = contexts.get(page.section);
      PageSpec body = bodies[page.section];
      String vAlign = ranges.get(page.section).resolved.decor.layout("verticalAlign", "top");
      float dy = 0;
      if ("center".equals(vAlign) || "bottom".equals(vAlign)) {
        float bottom = page.spec.top;
        for (Line l : page.lines) bottom = Math.max(bottom, l.y + l.height);
        for (Box x : page.boxes) bottom = Math.max(bottom, x.y + x.height);
        for (PlacedPicture x : page.pictures) bottom = Math.max(bottom, x.y + x.height);
        float free = page.spec.height - page.spec.bottom - bottom;
        if (free > 0) dy = "center".equals(vAlign) ? free / 2 : free;
      }
      Page rebuilt =
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
      PageDecor numbering = rebuilt.decor;
      if (numbering.lineCountBy > 0) {
        if ("page".equals(numbering.lineRestart)
            || "section".equals(numbering.lineRestart) && page.sectionPage == 0) lineCount = 0;
        for (Line line : page.lines) {
          if (line.block < 0 || line.row >= 0) continue;
          if (++lineCount % numbering.lineCountBy == 0)
            rebuilt.lineNumbers.add(new float[] {line.y, lineCount});
        }
      }
      b.pages.set(i, rebuilt);
    }
    cache = nextCache;
    return new Result(doc, b.pages, measured, revision, warnings);
  }

  private PageSpec bodySpec(Document doc, float header, float footer) {
    PageSpec original = doc.page;
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
    Sequence<Paragraph> result = new Sequence<Paragraph>();
    for (Paragraph p : doc.furniture.get(doc.furniture.key(footer, page, number))) {
      RichText resolved =
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
    Sequence<Paragraph> paragraphs = resolvedFurniture(doc, footer, sectionPage, count, number);
    List<Line> result = new ArrayList<Line>();
    float y = 0;
    ListNumbers numbering = new ListNumbers();
    boolean overflow = false;
    story:
    for (Paragraph p : paragraphs) {
      y += p.before;
      String marker = numbering.next(p);
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
    List<Line> placed = new ArrayList<Line>();
    for (Line line : result) {
      RawLine raw = new RawLine();
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
    final List<float[]> rules = new ArrayList<float[]>();
    int section, sectionFirstPage, owner, ownerFirstPage, number = 1;
    final List<Page> pages = new ArrayList<Page>();
    List<Line> current = new ArrayList<Line>();
    List<Box> boxes = new ArrayList<Box>();
    List<PlacedPicture> pictures = new ArrayList<PlacedPicture>();
    float y;
    final ListNumbers numbering = new ListNumbers();
    final List<Note> endnotes = new ArrayList<Note>();
    final List<Note> sectionEndnotes = new ArrayList<Note>();
    NoteOptions noteOptions = NoteOptions.DEFAULT;
    final Set<String> seenNotes = new HashSet<String>();
    final LinkedList<NoteLine> pendingNotes = new LinkedList<NoteLine>();
    List<NoteLine> footnotes = new ArrayList<NoteLine>();
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
      List<NoteLine> result = new ArrayList<NoteLine>();
      ListNumbers numbers = new ListNumbers();
      for (Paragraph p : note.displayedParagraphs()) {
        List<RawLine> measured = lines(p, spec.contentWidth());
        String marker = numbers.next(p);
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
      List<Note> notes = new ArrayList<Note>();
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
      List<NoteLine> added = new ArrayList<NoteLine>();
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
      List<Line> result = new ArrayList<Line>();
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
      footnotes = new ArrayList<NoteLine>();
      noteHeight = 0;
      while (!pendingNotes.isEmpty()) {
        NoteLine value = pendingNotes.get(0);
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
      String grid = value.decor.layout("grid", "default");
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
      float[] g = columns.layout(body.contentWidth())[index];
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
      float[][] g = columns.layout(body.contentWidth());
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

    final List<Frame> frames = new ArrayList<Frame>();

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
      current = new ArrayList<Line>();
      boxes = new ArrayList<Box>();
      pictures = new ArrayList<PlacedPicture>();
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
      String marker = numbering.next(p);
      float indent = p.indent + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1));
      if (p.pageBreak && y > spec.top) newPage();
      else if (p.format.columnBreak && index >= 0) nextColumn();
      PageSpec unwrapped = null, wrapped = null;
      if (inWrap()) {
        unwrapped = spec;
        spec =
            wrapped =
                new PageSpec(
                    spec.width, spec.height, spec.top, spec.right + wrapRight, spec.bottom,
                    spec.left + wrapLeft);
      }
      List<RawLine> ls = lines(p, spec.contentWidth());
      if (gridPitch > 0 && index >= 0) {
        // Snap each line to whole grid pitches; copies keep the measured cache unchanged.
        List<RawLine> snapped = new ArrayList<RawLine>();
        for (RawLine l : ls) {
          RawLine c = new RawLine();
          c.spans.addAll(l.spans);
          c.barTabs = l.barTabs;
          c.start = l.start;
          c.end = l.end;
          c.width = l.width;
          c.markerOffset = l.markerOffset;
          c.clipped = l.clipped;
          c.height = (float) Math.ceil(l.height / gridPitch - .01f) * gridPitch;
          snapped.add(c);
        }
        ls = snapped;
      }
      float pageHeight = spec.height - spec.top - spec.bottom;
      if (p.keepNext && following != null && !following.pageBreak) {
        List<RawLine> next = lines(following, spec.contentWidth());
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
      ParagraphBorders bd = p.format.borders;
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
        float x = spec.left + indent + firstIndent;
        if ("center".equals(p.align))
          x += (spec.contentWidth() - indent - p.format.rightIndent - firstIndent - l.width) / 2;
        if ("right".equals(p.align))
          x += spec.contentWidth() - indent - p.format.rightIndent - firstIndent - l.width;
        current.add(new Line(index, -1, -1, p.id, pages.size(), x, y, l, marker));
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
      ParagraphBorders bd = p.format.borders;
      float x = left + indent - bd.left.extent();
      float right = left + width - p.format.rightIndent + bd.right.extent();
      return new Frame(x, top, right - x, bottom, p.format, first, last);
    }

    /** A square/tight-wrapped object narrows paragraphs beside it until this y on its page. */
    float wrapBottom, wrapLeft, wrapRight;
    int wrapPage = -1, wrapColumn;

    boolean inWrap() {
      return wrapPage == pages.size() && wrapColumn == column && y < wrapBottom;
    }

    /** Tables and inline objects start below a wrapped object rather than beside it. */
    void clearWrap() {
      if (inWrap()) y = wrapBottom;
      wrapPage = -1;
    }

    void picture(Picture p, int index) {
      numbering.reset();
      String wrap = p.drawing == null ? "inline" : p.drawing.get("wrap", "inline");
      boolean floating = "behind".equals(wrap) || "front".equals(wrap);
      boolean around =
          ("square".equals(wrap) || "tight".equals(wrap)) && !"center".equals(p.align);
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
        float cut = width + ("tight".equals(wrap) ? 6 : 12);
        wrapPage = pages.size();
        wrapColumn = column;
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

    final class CellLayout {
      final Table.Merge span;
      final List<RawLine> lines = new ArrayList<RawLine>();
      final List<Paragraph> paragraphs = new ArrayList<Paragraph>();
      final List<String> ids = new ArrayList<String>(), markers = new ArrayList<String>();
      final List<Float> xs = new ArrayList<Float>(), ys = new ArrayList<Float>();
      final TableFormat.Cell format;
      final float left, width;
      float height, shift;

      CellLayout(Table t, int row, int col, float[] edges) {
        span = t.span(row, col);
        format = t.format.cell(row, col);
        left = edges[col];
        this.width = edges[col + span.columns] - left;
        height = format.verticalPadding;
        float width = Math.max(1, this.width - 2 * format.horizontalPadding);
        ListNumbers numbers = new ListNumbers();
        for (Paragraph p : t.cell(row, col)) {
          String marker = numbers.next(p);
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
            marker = "";
            xs.add(x);
            ys.add(height);
            height += line.height;
          }
          height += p.after;
        }
        height += format.verticalPadding;
      }
    }

    void mergedTable(Table t, int index) {
      numbering.reset();
      float[] edges = new float[t.columns() + 1];
      float[] widths = tableWidths(t, spec.contentWidth());
      float totalWidth = 0;
      for (float width : widths) totalWidth += width;
      edges[0] = t.format.sizing.offset(spec.contentWidth(), totalWidth);
      for (int c = 0; c < t.columns(); c++) edges[c + 1] = edges[c] + widths[c];
      List<CellLayout> cells = new ArrayList<CellLayout>();
      float[] heights = new float[t.rows()], tops = new float[t.rows() + 1];
      for (int r = 0; r < heights.length; r++)
        heights[r] =
            "exact".equals(t.format.row(r).rule)
                ? t.format.minHeight(r)
                : Math.max(8, t.format.minHeight(r));
      for (int row = 0; row < t.rows(); row++)
        for (int col = 0; col < t.columns(); col++) {
          if (t.covered(row, col)) continue;
          CellLayout cell = new CellLayout(t, row, col, edges);
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
      List<CellLayout> headers = new ArrayList<CellLayout>();
      for (CellLayout cell : cells) if (cell.span.row < t.format.headerRows) headers.add(cell);
      float headerHeight = tops[t.format.headerRows];
      // Like desktop word processors, oversized headers are not repeated over the body.
      if (headerHeight > (spec.height - spec.top - spec.bottom) / 2) {
        headers.clear();
        headerHeight = 0;
      }
      List<List<CellLayout>> byRow = new ArrayList<List<CellLayout>>();
      for (int i = 0; i < t.rows(); i++) byRow.add(new ArrayList<CellLayout>());
      for (CellLayout cell : cells) byRow.get(cell.span.row).add(cell);
      int row = 0;
      while (row < t.rows()) {
        int endRow = row + 1;
        List<CellLayout> group = new ArrayList<CellLayout>();
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
              RawLine line = cell.lines.get(i);
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
          List<Note> anchors = new ArrayList<Note>();
          for (CellLayout cell : group)
            for (int i = 0; i < cell.lines.size(); i++) {
              float top = tops[cell.span.row] + cell.shift + cell.ys.get(i);
              if (top < first - .01f
                  || top >= end - .01f
                  || top >= tops[cell.span.row + cell.span.rows]) continue;
              RawLine line = cell.lines.get(i);
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
                    Math.max(0, Math.min(bottom, end) - lineY)));
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
