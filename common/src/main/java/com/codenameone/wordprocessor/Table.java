package com.codenameone.wordprocessor;

import java.util.*;

/**
 * Immutable rectangular table. Cells contain block sequences: paragraphs and nested tables, as
 * Wordcraft's cells do (its paths nest three indexes per table level). {@link #cell} is the
 * paragraph view kept for paragraph-only callers; {@link #blocks} is the full content.
 */
public final class Table implements Block {
  private final String id;
  private final List<List<Sequence<Block>>> cells;
  private final Sequence<Paragraph>[][] paragraphView;

  public static final class Merge {
    public final int row, column, rows, columns;

    public Merge(int row, int column, int rows, int columns) {
      if (row < 0
          || row >= 1000
          || column < 0
          || column >= 100
          || rows < 1
          || rows > 1000
          || columns < 1
          || columns > 100) throw new IllegalArgumentException("Invalid cell span");
      this.row = row;
      this.column = column;
      this.rows = rows;
      this.columns = columns;
    }

    public boolean contains(int r, int c) {
      return r >= row && r < row + rows && c >= column && c < column + columns;
    }
  }

  public final List<Merge> merges;
  public final TableFormat format;

  public Table(String id, List<List<Sequence<Paragraph>>> cells) {
    this(id, cells, Collections.<Merge>emptyList());
  }

  public Table(String id, List<List<Sequence<Paragraph>>> cells, List<Merge> merges) {
    this(
        id,
        cells,
        merges,
        new TableFormat(cells.size(), cells.isEmpty() ? 0 : cells.get(0).size()));
  }

  public Table(
      String id, List<List<Sequence<Paragraph>>> cells, List<Merge> merges, TableFormat format) {
    this(id, merges, format, widen(cells));
  }

  public static Table of(String id, List<List<Sequence<Block>>> cells, List<Merge> merges) {
    return of(
        id, cells, merges, new TableFormat(cells.size(), cells.isEmpty() ? 0 : cells.get(0).size()));
  }

  /** A table whose cells hold blocks (paragraphs and nested tables). */
  public static Table of(
      String id, List<List<Sequence<Block>>> cells, List<Merge> merges, TableFormat format) {
    return new Table(id, merges, format, cells);
  }

  private static List<List<Sequence<Block>>> widen(List<List<Sequence<Paragraph>>> cells) {
    var out = new ArrayList<List<Sequence<Block>>>();
    for (List<Sequence<Paragraph>> row : cells) {
      var copy = new ArrayList<Sequence<Block>>();
      for (Sequence<Paragraph> cell : row) copy.add(cell == null ? null : widen(cell));
      out.add(copy);
    }
    return out;
  }

  static Sequence<Block> widen(Sequence<Paragraph> cell) {
    var out = new Sequence<Block>();
    for (Paragraph p : cell) out = out.append(p);
    return out;
  }

  @SuppressWarnings("unchecked")
  private Table(
      String id, List<Merge> merges, TableFormat format, List<List<Sequence<Block>>> cells) {
    if (id == null || cells.isEmpty() || cells.get(0).isEmpty())
      throw new IllegalArgumentException("Empty table");
    if (format == null || format.rows() != cells.size() || format.columns() != cells.get(0).size())
      throw new IllegalArgumentException("Table format size mismatch");
    this.format = format;
    this.id = id;
    int cols = cells.get(0).size();
    var c = new ArrayList<List<Sequence<Block>>>();
    for (List<Sequence<Block>> row : cells) {
      if (row.size() != cols) throw new IllegalArgumentException("Ragged table");
      var copy = new ArrayList<Sequence<Block>>();
      for (Sequence<Block> cell : row) {
        if (cell == null || cell.size() == 0) throw new IllegalArgumentException("Empty cell");
        // Like Word and Wordcraft, a cell ends with a paragraph (the cell mark's paragraph).
        if (!(cell.get(cell.size() - 1) instanceof Paragraph))
          throw new IllegalArgumentException("A cell must end with a paragraph");
        copy.add(cell);
      }
      c.add(Collections.unmodifiableList(copy));
    }
    paragraphView = new Sequence[cells.size()][cols];
    this.cells = Collections.unmodifiableList(c);
    var spans = new ArrayList<Merge>();
    for (Merge m : merges) {
      if (m.row < format.headerRows && m.row + m.rows > format.headerRows)
        throw new IllegalArgumentException(
            "A merged cell cannot cross the repeated header boundary");
      if (m.row + m.rows > rows() || m.column + m.columns > columns())
        throw new IllegalArgumentException("Cell span exceeds table");
      for (Merge previous : spans)
        if (m.row < previous.row + previous.rows
            && m.row + m.rows > previous.row
            && m.column < previous.column + previous.columns
            && m.column + m.columns > previous.column)
          throw new IllegalArgumentException("Overlapping merged cells");
      if (m.rows > 1 || m.columns > 1) spans.add(m);
    }
    this.merges = Collections.unmodifiableList(spans);
  }

  public Table crop(CellSelection range) {
    var picked = new ArrayList<List<Sequence<Block>>>();
    var f =
        new TableFormat(
            range.lastRow - range.firstRow + 1, range.lastColumn - range.firstColumn + 1);
    for (int r = range.firstRow; r <= range.lastRow; r++) {
      var row = new ArrayList<Sequence<Block>>();
      for (int c = range.firstColumn; c <= range.lastColumn; c++) {
        row.add(blocks(r, c));
        f = f.cell(r - range.firstRow, c - range.firstColumn, format.cell(r, c));
      }
      f = f.row(r - range.firstRow, format.row(r));
      picked.add(row);
    }
    for (int c = range.firstColumn; c <= range.lastColumn; c++)
      f = f.column(c - range.firstColumn, format.column(c));
    f = f.headerRows(Math.max(0, Math.min(f.rows(), format.headerRows - range.firstRow)));
    var spans = new ArrayList<Merge>();
    for (Merge span : merges)
      if (range.contains(range.block, span.row, span.column))
        spans.add(
            new Merge(
                span.row - range.firstRow,
                span.column - range.firstColumn,
                span.rows,
                span.columns));
    float fraction = 0;
    for (int c = range.firstColumn; c <= range.lastColumn; c++)
      fraction += format.columnFraction(c);
    fraction =
        range.firstColumn == 0 && range.lastColumn == columns() - 1 ? 1 : Math.min(1, fraction);
    var size = format.sizing;
    f =
        f.sizing(
            new TableFormat.Sizing(
                size.unit,
                "auto".equals(size.unit) ? 0 : Math.max(.01f, size.width * fraction),
                size.align,
                size.indent,
                size.fixed));
    return new Table(id, spans, f.altText(format.title, format.description), picked);
  }

  public Table format(TableFormat value) {
    for (Merge m : merges)
      if (m.row < value.headerRows && m.row + m.rows > value.headerRows)
        throw new IllegalArgumentException(
            "A merged cell cannot cross the repeated header boundary");
    return new Table(id, merges, value, cells);
  }

  public String id() {
    return id;
  }

  public int rows() {
    return cells.size();
  }

  public int columns() {
    return cells.get(0).size();
  }

  /** The cell's paragraphs, skipping nested tables (see {@link #blocks}). */
  public Sequence<Paragraph> cell(int r, int c) {
    var view = paragraphView[r][c];
    if (view == null) {
      view = new Sequence<Paragraph>();
      for (Block b : cells.get(r).get(c)) if (b instanceof Paragraph) view = view.append((Paragraph) b);
      paragraphView[r][c] = view;
    }
    return view;
  }

  /** The cell's content: paragraphs and nested tables in order. */
  public Sequence<Block> blocks(int r, int c) {
    return cells.get(r).get(c);
  }

  /** True when any cell (at any depth) holds a table. */
  public boolean nested() {
    for (List<Sequence<Block>> row : cells)
      for (Sequence<Block> cell : row) for (Block b : cell) if (b instanceof Table) return true;
    return false;
  }

  /**
   * Replaces the cell's paragraphs. Nested tables stay in place: the i-th new paragraph takes the
   * i-th old paragraph's slot, so paragraph-mapping callers (fields, merge, notes) keep them.
   */
  public Table setCell(int r, int c, Sequence<Paragraph> value) {
    var old = cells.get(r).get(c);
    if (old.size() == cell(r, c).size()) return setBlocks(r, c, widen(value));
    // positional replacement below keeps nested tables between the paragraphs
    var out = new Sequence<Block>();
    int i = 0;
    for (Block b : old)
      if (b instanceof Table) out = out.append(b);
      else if (i < value.size()) out = out.append(value.get(i++));
    for (; i < value.size(); i++) out = out.append(value.get(i));
    if (!(out.get(out.size() - 1) instanceof Paragraph)) out = widen(value);
    return setBlocks(r, c, out);
  }

  public Table setBlocks(int r, int c, Sequence<Block> value) {
    if (covered(r, c)) throw new IllegalArgumentException("Edit the owning merged cell");
    var copy = new ArrayList<List<Sequence<Block>>>(cells);
    var row = new ArrayList<Sequence<Block>>(copy.get(r));
    row.set(c, value);
    copy.set(r, row);
    return new Table(id, merges, format, copy);
  }

  public Table insertRow(int at, List<Sequence<Paragraph>> row) {
    if (at < 0 || at > rows() || row.size() != columns())
      throw new IllegalArgumentException("Invalid row insertion");
    var copy = new ArrayList<List<Sequence<Block>>>(cells);
    var added = new ArrayList<Sequence<Block>>();
    for (Sequence<Paragraph> cell : row) added.add(widen(cell));
    copy.add(at, added);
    return new Table(id, changedSpans(true, at, true), format.change(true, at, true), copy);
  }

  public Table deleteRow(int at) {
    if (rows() == 1)
      throw new IllegalArgumentException("A table must keep at least one row; use Delete table");
    var copy = new ArrayList<List<Sequence<Block>>>(cells);
    for (Merge m : merges)
      if (m.row == at && m.rows > 1) {
        var next = new ArrayList<Sequence<Block>>(copy.get(at + 1));
        next.set(m.column, blocks(m.row, m.column));
        copy.set(at + 1, next);
      }
    copy.remove(at);
    var nextFormat = format.change(true, at, false);
    for (Merge m : merges)
      if (m.row == at && m.rows > 1)
        nextFormat = nextFormat.cell(at, m.column, format.cell(m.row, m.column));
    return new Table(id, changedSpans(true, at, false), nextFormat, copy);
  }

  public Table insertColumn(int at, List<Sequence<Paragraph>> column) {
    if (at < 0 || at > columns() || column.size() != rows())
      throw new IllegalArgumentException("Invalid column insertion");
    var copy = new ArrayList<List<Sequence<Block>>>();
    for (int r = 0; r < rows(); r++) {
      var row = new ArrayList<Sequence<Block>>(cells.get(r));
      row.add(at, widen(column.get(r)));
      copy.add(row);
    }
    return new Table(id, changedSpans(false, at, true), format.change(false, at, true), copy);
  }

  public Table deleteColumn(int at) {
    if (columns() == 1)
      throw new IllegalArgumentException("A table must keep at least one column; use Delete table");
    var copy = new ArrayList<List<Sequence<Block>>>();
    for (List<Sequence<Block>> original : cells) {
      var row = new ArrayList<Sequence<Block>>(original);
      for (Merge m : merges)
        if (m.row == copy.size() && m.column == at && m.columns > 1)
          row.set(at + 1, blocks(m.row, m.column));
      row.remove(at);
      copy.add(row);
    }
    var nextFormat = format.change(false, at, false);
    for (Merge m : merges)
      if (m.column == at && m.columns > 1)
        nextFormat = nextFormat.cell(m.row, at, format.cell(m.row, m.column));
    return new Table(id, changedSpans(false, at, false), nextFormat, copy);
  }

  public Table sorted(
      final int column, final boolean descending, boolean header, final boolean numeric) {
    if (!merges.isEmpty())
      throw new IllegalArgumentException("Split merged cells before sorting a table");
    if (column < 0 || column >= columns())
      throw new IllegalArgumentException("Invalid sort column");
    var copy = new ArrayList<List<Sequence<Block>>>(cells);
    Collections.sort(
        copy.subList(header ? 1 : 0, copy.size()),
        new Comparator<List<Sequence<Block>>>() {
          public int compare(List<Sequence<Block>> a, List<Sequence<Block>> b) {
            String x = blockText(a.get(column)), y = blockText(b.get(column));
            int c =
                numeric
                    ? Double.compare(Double.parseDouble(x.trim()), Double.parseDouble(y.trim()))
                    : x.compareToIgnoreCase(y);
            return descending ? -c : c;
          }
        });
    int[] order = new int[rows()];
    for (int r = 0; r < rows(); r++) order[r] = cells.indexOf(copy.get(r));
    return new Table(id, merges, format.reorder(order), copy);
  }

  public Merge span(int row, int column) {
    for (Merge m : merges) if (m.contains(row, column)) return m;
    return new Merge(row, column, 1, 1);
  }

  public boolean covered(int row, int column) {
    var m = span(row, column);
    return m.row != row || m.column != column;
  }

  private List<Merge> changedSpans(boolean vertical, int at, boolean insert) {
    var out = new ArrayList<Merge>();
    for (Merge m : merges) {
      int start = vertical ? m.row : m.column, count = vertical ? m.rows : m.columns;
      if (insert) {
        if (at <= start) start++;
        else if (at < start + count) count++;
      } else {
        if (at < start) start--;
        else if (at < start + count) count--;
      }
      if (count > 0)
        out.add(
            new Merge(
                vertical ? start : m.row,
                vertical ? m.column : start,
                vertical ? count : m.rows,
                vertical ? m.columns : count));
    }
    return out;
  }

  public Table merge(int row, int column, int rowCount, int columnCount, long[] nextId) {
    var area = new Merge(row, column, rowCount, columnCount);
    if (row + rowCount > rows() || column + columnCount > columns())
      throw new IllegalArgumentException("Merge exceeds table");
    var spans = new ArrayList<Merge>();
    for (Merge old : merges) {
      boolean overlap =
          row < old.row + old.rows
              && row + rowCount > old.row
              && column < old.column + old.columns
              && column + columnCount > old.column;
      if (!overlap) spans.add(old);
      else if (old.row < row
          || old.column < column
          || old.row + old.rows > row + rowCount
          || old.column + old.columns > column + columnCount)
        throw new IllegalArgumentException(
            "Merge must include each existing merged cell completely");
    }
    var copy = new ArrayList<List<Sequence<Block>>>();
    for (List<Sequence<Block>> original : cells) copy.add(new ArrayList<Sequence<Block>>(original));
    var content = new Sequence<Block>();
    for (int y = row; y < row + rowCount; y++)
      for (int x = column; x < column + columnCount; x++) {
        if (!covered(y, x)) for (Block b : blocks(y, x)) content = content.append(b);
        if (y != row || x != column)
          copy.get(y)
              .set(
                  x,
                  new Sequence<Block>().append(new Paragraph("p" + nextId[0]++, new RichText())));
      }
    copy.get(row).set(column, content);
    spans.add(area);
    return new Table(id, spans, format, copy);
  }

  public Table split(int row, int column) {
    var selected = span(row, column);
    var spans = new ArrayList<Merge>();
    for (Merge m : merges) if (m != selected) spans.add(m);
    return new Table(id, spans, format, cells);
  }

  /**
   * The paragraph {@code id} of cell (r, c), or of the nested cell at {@code path} inside it
   * (block, row, column triples as LayoutEngine.Line#path); null when absent.
   */
  public Paragraph paragraph(int r, int c, int[] path, String id) {
    var content = blocks(r, c);
    for (int i = 0; i + 2 < path.length; i += 3) {
      if (path[i] >= content.size() || !(content.get(path[i]) instanceof Table inner)) return null;
      content = inner.blocks(path[i + 1], path[i + 2]);
    }
    for (Block b : content) if (b instanceof Paragraph p && p.id.equals(id)) return p;
    return null;
  }

  /** A copy with fresh table and paragraph ids at every nesting level (for paste). */
  public Table renumbered(long[] id) {
    return renumbered("p", "t", id);
  }

  /** As {@link #renumbered(long[])} with the given paragraph and table id prefixes. */
  public Table renumbered(String paragraphPrefix, String tablePrefix, long[] id) {
    var out = new ArrayList<List<Sequence<Block>>>();
    for (int r = 0; r < rows(); r++) {
      var row = new ArrayList<Sequence<Block>>();
      for (int c = 0; c < columns(); c++) {
        var cell = new Sequence<Block>();
        for (Block b : blocks(r, c))
          cell =
              cell.append(
                  b instanceof Table inner
                      ? inner.renumbered(paragraphPrefix, tablePrefix, id)
                      : ((Paragraph) b).copy(paragraphPrefix + id[0]++, ((Paragraph) b).text));
        row.add(cell);
      }
      out.add(row);
    }
    return new Table(tablePrefix + id[0]++, merges, format, out);
  }

  /** Cell text with nested tables flattened in reading order. */
  public static String blockText(Sequence<Block> cell) {
    var b = new StringBuilder();
    for (Block block : cell) {
      if (b.length() > 0) b.append('\n');
      if (block instanceof Paragraph) b.append(((Paragraph) block).text);
      else if (block instanceof Table) {
        var t = (Table) block;
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++) {
            if (r + c > 0) b.append('\n');
            b.append(blockText(t.blocks(r, c)));
          }
      }
    }
    return b.toString();
  }

  public static String cellText(Sequence<Paragraph> cell) {
    var b = new StringBuilder();
    for (Paragraph p : cell) {
      if (b.length() > 0) b.append('\n');
      b.append(p.text);
    }
    return b.toString();
  }
}
