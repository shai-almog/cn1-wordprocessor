package com.codenameone.wordprocessor;

import java.util.*;

/** Immutable rectangular table; cells contain paragraph sequences. */
public final class Table implements Block {
  private final String id;
  private final List<List<Sequence<Paragraph>>> cells;

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
    if (id == null || cells.isEmpty() || cells.get(0).isEmpty())
      throw new IllegalArgumentException("Empty table");
    if (format == null || format.rows() != cells.size() || format.columns() != cells.get(0).size())
      throw new IllegalArgumentException("Table format size mismatch");
    this.format = format;
    this.id = id;
    int cols = cells.get(0).size();
    var c = new ArrayList<List<Sequence<Paragraph>>>();
    for (List<Sequence<Paragraph>> row : cells) {
      if (row.size() != cols) throw new IllegalArgumentException("Ragged table");
      var copy = new ArrayList<Sequence<Paragraph>>();
      for (Sequence<Paragraph> cell : row) {
        if (cell == null || cell.size() == 0) throw new IllegalArgumentException("Empty cell");
        copy.add(cell);
      }
      c.add(Collections.unmodifiableList(copy));
    }
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
    var picked = new ArrayList<List<Sequence<Paragraph>>>();
    var f =
        new TableFormat(
            range.lastRow - range.firstRow + 1, range.lastColumn - range.firstColumn + 1);
    for (int r = range.firstRow; r <= range.lastRow; r++) {
      var row = new ArrayList<Sequence<Paragraph>>();
      for (int c = range.firstColumn; c <= range.lastColumn; c++) {
        row.add(cell(r, c));
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
    return new Table(id, picked, spans, f.altText(format.title, format.description));
  }

  public Table format(TableFormat value) {
    for (Merge m : merges)
      if (m.row < value.headerRows && m.row + m.rows > value.headerRows)
        throw new IllegalArgumentException(
            "A merged cell cannot cross the repeated header boundary");
    return new Table(id, cells, merges, value);
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

  public Sequence<Paragraph> cell(int r, int c) {
    return cells.get(r).get(c);
  }

  public Table setCell(int r, int c, Sequence<Paragraph> value) {
    if (covered(r, c)) throw new IllegalArgumentException("Edit the owning merged cell");
    var copy = new ArrayList<List<Sequence<Paragraph>>>(cells);
    var row = new ArrayList<Sequence<Paragraph>>(copy.get(r));
    row.set(c, value);
    copy.set(r, row);
    return new Table(id, copy, merges, format);
  }

  public Table insertRow(int at, List<Sequence<Paragraph>> row) {
    if (at < 0 || at > rows() || row.size() != columns())
      throw new IllegalArgumentException("Invalid row insertion");
    var copy = new ArrayList<List<Sequence<Paragraph>>>(cells);
    copy.add(at, row);
    return new Table(id, copy, changedSpans(true, at, true), format.change(true, at, true));
  }

  public Table deleteRow(int at) {
    if (rows() == 1)
      throw new IllegalArgumentException("A table must keep at least one row; use Delete table");
    var copy = new ArrayList<List<Sequence<Paragraph>>>(cells);
    for (Merge m : merges)
      if (m.row == at && m.rows > 1) {
        var next = new ArrayList<Sequence<Paragraph>>(copy.get(at + 1));
        next.set(m.column, cell(m.row, m.column));
        copy.set(at + 1, next);
      }
    copy.remove(at);
    var nextFormat = format.change(true, at, false);
    for (Merge m : merges)
      if (m.row == at && m.rows > 1)
        nextFormat = nextFormat.cell(at, m.column, format.cell(m.row, m.column));
    return new Table(id, copy, changedSpans(true, at, false), nextFormat);
  }

  public Table insertColumn(int at, List<Sequence<Paragraph>> column) {
    if (at < 0 || at > columns() || column.size() != rows())
      throw new IllegalArgumentException("Invalid column insertion");
    var copy = new ArrayList<List<Sequence<Paragraph>>>();
    for (int r = 0; r < rows(); r++) {
      var row = new ArrayList<Sequence<Paragraph>>(cells.get(r));
      row.add(at, column.get(r));
      copy.add(row);
    }
    return new Table(id, copy, changedSpans(false, at, true), format.change(false, at, true));
  }

  public Table deleteColumn(int at) {
    if (columns() == 1)
      throw new IllegalArgumentException("A table must keep at least one column; use Delete table");
    var copy = new ArrayList<List<Sequence<Paragraph>>>();
    for (List<Sequence<Paragraph>> original : cells) {
      var row = new ArrayList<Sequence<Paragraph>>(original);
      for (Merge m : merges)
        if (m.row == copy.size() && m.column == at && m.columns > 1)
          row.set(at + 1, cell(m.row, m.column));
      row.remove(at);
      copy.add(row);
    }
    var nextFormat = format.change(false, at, false);
    for (Merge m : merges)
      if (m.column == at && m.columns > 1)
        nextFormat = nextFormat.cell(m.row, at, format.cell(m.row, m.column));
    return new Table(id, copy, changedSpans(false, at, false), nextFormat);
  }

  public Table sorted(
      final int column, final boolean descending, boolean header, final boolean numeric) {
    if (!merges.isEmpty())
      throw new IllegalArgumentException("Split merged cells before sorting a table");
    if (column < 0 || column >= columns())
      throw new IllegalArgumentException("Invalid sort column");
    var copy = new ArrayList<List<Sequence<Paragraph>>>(cells);
    Collections.sort(
        copy.subList(header ? 1 : 0, copy.size()),
        new Comparator<List<Sequence<Paragraph>>>() {
          public int compare(List<Sequence<Paragraph>> a, List<Sequence<Paragraph>> b) {
            String x = cellText(a.get(column)), y = cellText(b.get(column));
            int c =
                numeric
                    ? Double.compare(Double.parseDouble(x.trim()), Double.parseDouble(y.trim()))
                    : x.compareToIgnoreCase(y);
            return descending ? -c : c;
          }
        });
    int[] order = new int[rows()];
    for (int r = 0; r < rows(); r++) order[r] = cells.indexOf(copy.get(r));
    return new Table(id, copy, merges, format.reorder(order));
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
    var copy = new ArrayList<List<Sequence<Paragraph>>>();
    for (List<Sequence<Paragraph>> original : cells)
      copy.add(new ArrayList<Sequence<Paragraph>>(original));
    var content = new Sequence<Paragraph>();
    for (int y = row; y < row + rowCount; y++)
      for (int x = column; x < column + columnCount; x++) {
        if (!covered(y, x)) for (Paragraph p : cell(y, x)) content = content.append(p);
        if (y != row || x != column)
          copy.get(y)
              .set(
                  x,
                  new Sequence<Paragraph>()
                      .append(new Paragraph("p" + nextId[0]++, new RichText())));
      }
    copy.get(row).set(column, content);
    spans.add(area);
    return new Table(id, copy, spans, format);
  }

  public Table split(int row, int column) {
    var selected = span(row, column);
    var spans = new ArrayList<Merge>();
    for (Merge m : merges) if (m != selected) spans.add(m);
    return new Table(id, cells, spans, format);
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
