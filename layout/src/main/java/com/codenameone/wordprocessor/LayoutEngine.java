package com.codenameone.wordprocessor;

import java.util.*;

/** Platform-independent pagination in typographic points, with identity-keyed paragraph caching. */
public final class LayoutEngine {
  public interface Metrics {
    float width(String text, TextStyle style);

    float height(TextStyle style);
  }

  public static final class Span {
    public final String text;
    public final TextStyle style;
    public final int start, end;
    public final float x, width;

    Span(String t, TextStyle s, int start, float x, float w) {
      text = t;
      style = s;
      this.start = start;
      end = start + t.length();
      this.x = x;
      width = w;
    }
  }

  public static final class Line {
    public final int block, row, column, start, end, page;
    public final String paragraphId;
    public final float x, y, width, height;
    public final List<Span> spans;

    Line(int b, int row, int col, String id, int page, float x, float y, RawLine r) {
      block = b;
      this.row = row;
      column = col;
      paragraphId = id;
      this.page = page;
      this.x = x;
      this.y = y;
      width = r.width;
      height = r.height;
      start = r.start;
      end = r.end;
      spans = Collections.unmodifiableList(r.spans);
    }
  }

  public static final class Box {
    public final int block, row, column, page;
    public final float x, y, width, height;

    Box(int b, int r, int c, int p, float x, float y, float w, float h) {
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

  public static final class Page {
    public final List<Line> lines;
    public final List<Box> boxes;

    Page(List<Line> lines, List<Box> boxes) {
      this.lines = Collections.unmodifiableList(lines);
      this.boxes = Collections.unmodifiableList(boxes);
    }
  }

  public static final class Result {
    public final List<Page> pages;
    public final int measuredParagraphs;
    public final long revision;

    Result(List<Page> p, int count, long rev) {
      pages = Collections.unmodifiableList(p);
      measuredParagraphs = count;
      revision = rev;
    }
  }

  private static final class RawLine {
    final List<Span> spans = new ArrayList<Span>();
    int start, end;
    float width, height;
  }

  private static final class Cached {
    final float width;
    final List<RawLine> lines;

    Cached(float w, List<RawLine> l) {
      width = w;
      lines = l;
    }
  }

  private final Metrics metrics;
  private Map<Paragraph, Cached> cache = new HashMap<Paragraph, Cached>();
  private Map<Paragraph, Cached> nextCache;
  private int measured;

  public LayoutEngine(Metrics m) {
    metrics = m;
  }

  public void clear() {
    cache.clear();
  }

  private List<RawLine> lines(Paragraph p, float width) {
    width = Math.max(1, width - p.indent);
    Cached c = cache.get(p);
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
      line.start = start;
      line.height = metrics.height(TextStyle.DEFAULT) * p.lineSpacing;
      int end = start, lastBreak = -1;
      float x = 0;
      while (end < text.length()) {
        int count =
            Character.isHighSurrogate(text.charAt(end))
                    && end + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(end + 1))
                ? 2
                : 1;
        String ch = text.substring(end, end + count);
        if ("\n".equals(ch)) {
          break;
        }
        float w = metrics.width(ch, p.text.styleAt(end));
        if (x + w > width && end > start) {
          if (lastBreak > start) end = lastBreak;
          break;
        }
        x += w;
        end += count;
        if (Character.isWhitespace(ch.charAt(0))) lastBreak = end;
      }
      if (end == start && end < text.length() && text.charAt(end) != '\n')
        end += Character.charCount(Character.codePointAt(text, end));
      line.end = end;
      float pos = 0;
      int a = start;
      while (a < end) {
        TextStyle s = p.text.styleAt(a);
        int b = a + 1;
        while (b < end && s.equals(p.text.styleAt(b))) b++;
        String chunk = text.substring(a, b);
        float w = metrics.width(chunk, s);
        line.spans.add(new Span(chunk, s, a, pos, w));
        pos += w;
        line.height = Math.max(line.height, metrics.height(s) * p.lineSpacing);
        a = b;
      }
      line.width = pos;
      out.add(line);
      start = end;
      if (start < text.length() && text.charAt(start) == '\n') start++;
    } while (start < text.length());
    c = new Cached(width, out);
    nextCache.put(p, c);
    return out;
  }

  public Result layout(Document doc, long revision) {
    nextCache = new HashMap<Paragraph, Cached>();
    measured = 0;
    Builder b = new Builder(doc.page);
    int index = 0;
    for (Block block : doc.blocks) {
      if (block instanceof Paragraph) b.paragraph((Paragraph) block, index);
      else if (block instanceof Table) b.table((Table) block, index);
      index++;
    }
    b.finish();
    cache = nextCache;
    return new Result(b.pages, measured, revision);
  }

  private final class Builder {
    final PageSpec spec;
    final List<Page> pages = new ArrayList<Page>();
    List<Line> current = new ArrayList<Line>();
    List<Box> boxes = new ArrayList<Box>();
    float y;

    Builder(PageSpec p) {
      spec = p;
      y = p.top;
    }

    void newPage() {
      pages.add(new Page(current, boxes));
      current = new ArrayList<Line>();
      boxes = new ArrayList<Box>();
      y = spec.top;
    }

    void room(float height) {
      if (y + height > spec.height - spec.bottom && y > spec.top) newPage();
    }

    void paragraph(Paragraph p, int index) {
      if (p.pageBreak && y > spec.top) newPage();
      List<RawLine> ls = lines(p, spec.contentWidth());
      room(p.before + ls.get(0).height);
      y += p.before;
      for (RawLine l : ls) {
        room(l.height);
        float x = spec.left + p.indent;
        if ("center".equals(p.align)) x += (spec.contentWidth() - p.indent - l.width) / 2;
        if ("right".equals(p.align)) x += spec.contentWidth() - p.indent - l.width;
        current.add(new Line(index, -1, -1, p.id, pages.size(), x, y, l));
        y += l.height;
      }
      y += p.after;
    }

    void table(Table t, int index) {
      float width = spec.contentWidth() / t.columns();
      for (int r = 0; r < t.rows(); r++) {
        List<List<RawLine>> cells = new ArrayList<List<RawLine>>();
        List<List<String>> ids = new ArrayList<List<String>>();
        int maxLines = 0;
        for (int c = 0; c < t.columns(); c++) {
          List<RawLine> cell = new ArrayList<RawLine>();
          List<String> names = new ArrayList<String>();
          for (Paragraph p : t.cell(r, c))
            for (RawLine line : lines(p, width - 12)) {
              cell.add(line);
              names.add(p.id);
            }
          cells.add(cell);
          ids.add(names);
          maxLines = Math.max(maxLines, cell.size());
        }
        // Split rows at line boundaries so even a cell taller than one page remains reachable.
        int line = 0;
        while (line < maxLines) {
          float rowY = y, rowHeight = 8;
          int end = line;
          while (end < maxLines) {
            float h = 0;
            for (List<RawLine> cell : cells)
              if (end < cell.size()) h = Math.max(h, cell.get(end).height);
            if (rowY + rowHeight + h > spec.height - spec.bottom && end > line) break;
            if (rowY + rowHeight + h > spec.height - spec.bottom && y > spec.top) {
              newPage();
              rowY = y;
            }
            rowHeight += h;
            end++;
          }
          for (int c = 0; c < t.columns(); c++) {
            float cy = rowY + 4;
            for (int k = line; k < end; k++) {
              float h = 0;
              for (List<RawLine> cell : cells)
                if (k < cell.size()) h = Math.max(h, cell.get(k).height);
              if (k < cells.get(c).size())
                current.add(
                    new Line(
                        index,
                        r,
                        c,
                        ids.get(c).get(k),
                        pages.size(),
                        spec.left + c * width + 6,
                        cy,
                        cells.get(c).get(k)));
              cy += h;
            }
            boxes.add(
                new Box(index, r, c, pages.size(), spec.left + c * width, rowY, width, rowHeight));
          }
          y = rowY + rowHeight;
          line = end;
          if (line < maxLines) newPage();
        }
      }
      y += 6;
    }

    void finish() {
      pages.add(new Page(current, boxes));
    }
  }
}
