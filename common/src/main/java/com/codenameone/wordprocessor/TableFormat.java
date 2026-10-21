package com.codenameone.wordprocessor;

import java.util.*;

/** Immutable table geometry and per-cell appearance, independent of paragraph formatting. */
public final class TableFormat {
  public static final class Cell {
    public static final Cell DEFAULT = new Cell(0xffffff, 0xa6a6a6, .5f, 6, 4, "top");
    public final int background, borderColor;
    public final float borderWidth, horizontalPadding, verticalPadding;
    public final String vertical;

    /** Bit set of the drawn border sides; one color and width applies to all of them. */
    public static final int TOP = 1, LEFT = 2, BOTTOM = 4, RIGHT = 8, ALL = 15;

    public final int sides;

    public Cell(
        int background,
        int borderColor,
        float borderWidth,
        float horizontalPadding,
        float verticalPadding,
        String vertical) {
      this(background, borderColor, borderWidth, horizontalPadding, verticalPadding, vertical, ALL);
    }

    public Cell(
        int background,
        int borderColor,
        float borderWidth,
        float horizontalPadding,
        float verticalPadding,
        String vertical,
        int sides) {
      this.sides = sides & ALL;
      if (!valid(borderWidth, 0, 12)
          || !valid(horizontalPadding, 0, 72)
          || !valid(verticalPadding, 0, 72)
          || !("top".equals(vertical) || "center".equals(vertical) || "bottom".equals(vertical)))
        throw new IllegalArgumentException("Invalid cell formatting");
      this.background = background & 0xffffff;
      this.borderColor = borderColor & 0xffffff;
      this.borderWidth = borderWidth;
      this.horizontalPadding = horizontalPadding;
      this.verticalPadding = verticalPadding;
      this.vertical = vertical;
    }

    public boolean side(int side) {
      return borderWidth > 0 && (sides & side) != 0;
    }

    public Cell borders(int color, float width, int sides) {
      return new Cell(background, color, width, horizontalPadding, verticalPadding, vertical, sides);
    }

    public Cell background(int color) {
      return new Cell(color, borderColor, borderWidth, horizontalPadding, verticalPadding, vertical, sides);
    }

    public Cell padding(float horizontal, float vertical) {
      return new Cell(background, borderColor, borderWidth, horizontal, vertical, this.vertical, sides);
    }
  }

  /** Preferred width and placement in typographic points; auto uses measured contents. */
  public static final class Sizing {
    public static final Sizing DEFAULT = new Sizing("percent", 100, "left", 0, true);
    public final String unit, align;
    public final float width, indent;
    public final boolean fixed;

    public Sizing(String unit, float width, String align, float indent, boolean fixed) {
      if (!("auto".equals(unit) || "points".equals(unit) || "percent".equals(unit))
          || !valid(width, "auto".equals(unit) ? 0 : .01f, "percent".equals(unit) ? 100 : 10000)
          || !valid(indent, -1584, 1584)
          || !("left".equals(align) || "center".equals(align) || "right".equals(align)))
        throw new IllegalArgumentException("Invalid table size or alignment");
      this.unit = unit;
      this.width = "auto".equals(unit) ? 0 : width;
      this.align = align;
      this.indent = indent;
      this.fixed = fixed;
    }

    public float offset(float available, float actualWidth) {
      return "center".equals(align)
          ? (available - actualWidth) / 2
          : "right".equals(align) ? available - actualWidth : indent;
    }
  }

  /** Row height and pagination policy, retained independently of cell content. */
  public static final class Row {
    public static final Row DEFAULT = new Row(0, "auto", true);
    public final float height;
    public final String rule;
    public final boolean allowBreak;

    public Row(float height, String rule, boolean allowBreak) {
      if (!("auto".equals(rule) || "atLeast".equals(rule) || "exact".equals(rule))
          || !valid(height, "exact".equals(rule) ? .01f : 0, 10000))
        throw new IllegalArgumentException("Invalid row height or rule");
      this.height = "auto".equals(rule) ? 0 : height;
      this.rule = this.height == 0 ? "auto" : rule;
      this.allowBreak = allowBreak;
    }
  }

  public final Sizing sizing;
  private final Cell[][] cells;
  private final float[] columns;
  private final Row[] heights;
  public final int headerRows;
  public final String title, description;

  /** Table options: style (applied style name), look (comma list of {@link #LOOK}), rtl, directions. */
  private final java.util.TreeMap<String, String> props;

  public static final java.util.List<String> LOOK =
      java.util.Arrays.asList("headerRow", "firstColumn", "lastRow", "lastColumn", "bandedRows", "bandedColumns");
  public static final java.util.List<String> PROPS = java.util.Arrays.asList("style", "look", "rtl", "directions", "float");

  public TableFormat(int rows, int columns) {
    if (rows < 1 || rows > 1000 || columns < 1 || columns > 100)
      throw new IllegalArgumentException("Invalid table size");
    cells = new Cell[rows][columns];
    this.columns = new float[columns];
    heights = new Row[rows];
    Arrays.fill(heights, Row.DEFAULT);
    headerRows = 0;
    title = description = "";
    sizing = Sizing.DEFAULT;
    props = new java.util.TreeMap<>();
    Arrays.fill(this.columns, 1);
    for (Cell[] row : cells) Arrays.fill(row, Cell.DEFAULT);
  }

  private TableFormat(
      Cell[][] cells,
      float[] columns,
      Row[] heights,
      int headers,
      String title,
      String description,
      Sizing sizing,
      java.util.TreeMap<String, String> props) {
    this.props = props;
    this.cells = cells;
    this.columns = columns;
    this.heights = heights;
    this.headerRows = headers;
    this.title = title;
    this.description = description;
    this.sizing = sizing;
  }

  // Arrays are newly allocated by the package's geometry operations and never exposed.
  static TableFormat geometry(Cell[][] cells, float[] columns, Row[] heights, int headers) {
    return new TableFormat(cells, columns, heights, headers, "", "", Sizing.DEFAULT, new java.util.TreeMap<String, String>());
  }

  /** Empty or whitespace-only strings clear metadata; null is not accepted. */
  public TableFormat altText(String title, String description) {
    if (title == null || description == null)
      throw new IllegalArgumentException("Missing table alternative text");
    return keep(new TableFormat(
        cells, columns, heights, headerRows, alt(title), alt(description), sizing, props));
  }

  public TableFormat sizing(Sizing value) {
    if (value == null) throw new IllegalArgumentException("Missing table sizing");
    return keep(new TableFormat(cells, columns, heights, headerRows, title, description, value, props));
  }

  /** Preferred total width before content-driven adjustment. */
  public float preferredWidth(float available) {
    if ("percent".equals(sizing.unit)) return available * sizing.width / 100;
    if ("points".equals(sizing.unit)) return sizing.width;
    float total = 0;
    for (float column : columns) total += column;
    return total;
  }

  private static String alt(String value) {
    if (value.trim().isEmpty()) return "";
    int end = 0, count = 0;
    while (end < value.length() && count++ < 10000) {
      char ch = value.charAt(end++);
      if (Character.isHighSurrogate(ch)
          && end < value.length()
          && Character.isLowSurrogate(value.charAt(end))) end++;
    }
    return value.substring(0, end);
  }

  public int rows() {
    return cells.length;
  }

  public int columns() {
    return columns.length;
  }

  public Cell cell(int row, int column) {
    return cells[row][column];
  }

  public float column(int column) {
    return columns[column];
  }

  public float minHeight(int row) {
    return heights[row].height;
  }

  public Row row(int row) {
    return heights[row];
  }

  public float columnFraction(int column) {
    float sum = 0;
    for (float width : columns) sum += width;
    return columns[column] / sum;
  }

  public TableFormat cell(int row, int column, Cell value) {
    if (value == null) throw new IllegalArgumentException("Missing cell format");
    var copy = cells.clone();
    copy[row] = cells[row].clone();
    copy[row][column] = value;
    return keep(new TableFormat(copy, columns, heights, headerRows, title, description, sizing, props));
  }

  public TableFormat column(int column, float weight) {
    if (!valid(weight, .000001f, 10000)) throw new IllegalArgumentException("Invalid column width");
    var copy = columns.clone();
    copy[column] = weight;
    return keep(new TableFormat(cells, copy, heights, headerRows, title, description, sizing, props));
  }

  public TableFormat columnFraction(int column, float fraction) {
    if (!valid(fraction, .001f, 1) || columns() > 1 && fraction >= 1)
      throw new IllegalArgumentException("Column width must leave room for the other columns");
    if (columns() == 1) return this;
    float previous = columnFraction(column), total = 0;
    for (float width : columns) total += width;
    float[] copy = new float[columns()];
    for (int c = 0; c < columns(); c++)
      copy[c] =
          total * (c == column ? fraction : columnFraction(c) * (1 - fraction) / (1 - previous));
    return keep(new TableFormat(cells, copy, heights, headerRows, title, description, sizing, props));
  }

  public TableFormat minHeight(int row, float height) {
    return row(row, new Row(height, height == 0 ? "auto" : "atLeast", heights[row].allowBreak));
  }

  public TableFormat row(int row, Row value) {
    if (value == null) throw new IllegalArgumentException("Missing row formatting");
    var copy = heights.clone();
    copy[row] = value;
    return keep(new TableFormat(cells, columns, copy, headerRows, title, description, sizing, props));
  }

  public TableFormat distributeColumns() {
    float[] equal = new float[columns.length];
    float total = 0;
    for (float width : columns) total += width;
    Arrays.fill(equal, total / columns.length);
    return keep(new TableFormat(cells, equal, heights, headerRows, title, description, sizing, props));
  }

  public TableFormat distributeRows() {
    float height = 18;
    for (Row value : heights) height = Math.max(height, value.height);
    Row[] equal = new Row[heights.length];
    for (int r = 0; r < equal.length; r++)
      equal[r] =
          new Row(
              height, "exact".equals(heights[r].rule) ? "exact" : "atLeast", heights[r].allowBreak);
    return keep(new TableFormat(cells, columns, equal, headerRows, title, description, sizing, props));
  }

  public TableFormat headerRows(int count) {
    if (count < 0 || count > rows()) throw new IllegalArgumentException("Invalid header row count");
    return keep(new TableFormat(cells, columns, heights, count, title, description, sizing, props));
  }

  TableFormat change(boolean row, int at, boolean insert) {
    int nr = rows() + (row ? (insert ? 1 : -1) : 0),
        nc = columns() + (!row ? (insert ? 1 : -1) : 0);
    var out = new TableFormat(nr, nc);
    for (int r = 0; r < nr; r++) {
      int oldR = row ? original(r, at, insert) : r;
      if (oldR >= 0) out.heights[r] = heights[oldR];
      for (int c = 0; c < nc; c++) {
        int oldC = row ? c : original(c, at, insert);
        if (oldR >= 0 && oldC >= 0) out.cells[r][c] = cells[oldR][oldC];
      }
    }
    for (int c = 0; c < nc; c++) {
      int oldC = row ? c : original(c, at, insert);
      out.columns[c] = columns[oldC < 0 ? Math.min(at, columns() - 1) : oldC];
    }
    int headers = headerRows;
    if (row && at < headers) headers += insert ? 1 : -1;
    var result = out.headerRows(Math.min(headers, nr)).altText(title, description).sizing(sizing).props(props);
    // The tracked prior properties follow the same row/column change so they stay aligned.
    if (revision() != null && previous != null && previous.rows() == rows() && previous.columns() == columns()) {
      Revision[] rowRevs = new Revision[nr];
      Revision[][] cellRevs = new Revision[nr][nc];
      for (int r = 0; r < nr; r++) {
        int oldR = row ? original(r, at, insert) : r;
        if (oldR < 0) continue;
        rowRevs[r] = rowRevision(oldR);
        for (int c = 0; c < nc; c++) {
          int oldC = row ? c : original(c, at, insert);
          if (oldC >= 0) cellRevs[r][c] = cellRevision(oldR, oldC);
        }
      }
      return result.elementRevisions(revision, rowRevs, cellRevs, previous.change(row, at, insert));
    }
    return result;
  }

  /**
   * Tracked table property changes (w:tblPrChange, w:trPrChange, w:tcPrChange; Wordcraft
   * TableProps/RowProps/CellProps fmt_change): as Wordcraft, one revision per element -- the
   * table's own properties, each row's and each cell's -- and the whole table's properties
   * before the oldest of them ({@link #previous}). Rejecting one element's revision restores
   * only that element's prior properties.
   */
  public Revision revision() {
    if (revision != null) return revision;
    if (rowRevisions != null) for (Revision r : rowRevisions) if (r != null) return r;
    if (cellRevisions != null)
      for (Revision[] row : cellRevisions) for (Revision r : row) if (r != null) return r;
    return null;
  }

  /** The table-level (w:tblPrChange) revision. */
  public Revision tableRevision() {
    return revision;
  }

  /** The row's own (w:trPrChange) revision, null when the row's properties are unchanged. */
  public Revision rowRevision(int row) {
    return rowRevisions == null || row >= rowRevisions.length ? null : rowRevisions[row];
  }

  /** The cell's own (w:tcPrChange) revision, null when the cell's properties are unchanged. */
  public Revision cellRevision(int row, int column) {
    return cellRevisions == null || row >= cellRevisions.length || column >= cellRevisions[row].length
        ? null : cellRevisions[row][column];
  }

  /** The distinct revisions of the table, its rows and cells, in reading order. */
  public java.util.List<Revision> revisions() {
    var out = new java.util.ArrayList<Revision>();
    var ids = new java.util.HashSet<String>();
    if (revision != null && ids.add(revision.id)) out.add(revision);
    for (int r = 0; r < rows(); r++) {
      var row = rowRevision(r);
      if (row != null && ids.add(row.id)) out.add(row);
      for (int c = 0; c < columns(); c++) {
        var cell = cellRevision(r, c);
        if (cell != null && ids.add(cell.id)) out.add(cell);
      }
    }
    return out;
  }

  public TableFormat previous() {
    return previous;
  }

  private Revision revision;
  private Revision[] rowRevisions;
  private Revision[][] cellRevisions;
  private TableFormat previous;

  /**
   * The table with {@code value} recorded on every element (table, row, cell) whose properties
   * differ from {@code before}; on the table itself when none does. Null clears every revision.
   */
  public TableFormat formatRevision(Revision value, TableFormat before) {
    var out = new TableFormat(cells, columns, heights, headerRows, title, description, sizing, props);
    if (value == null || before == null) return out;
    out.previous = before.clean();
    out.assign(before, null, value);
    if (out.revision() == null) out.revision = value;
    return out;
  }

  /** Per-element revisions as read from a file (each may be null); prior is the old table. */
  TableFormat elementRevisions(Revision table, Revision[] rows, Revision[][] cells, TableFormat prior) {
    var out = new TableFormat(this.cells, columns, heights, headerRows, title, description, sizing, props);
    out.revision = table;
    out.rowRevisions = rows;
    out.cellRevisions = cells;
    out.previous = prior == null ? null : prior.clean();
    if (out.revision() == null || out.previous == null) return clean();
    return out;
  }

  private TableFormat clean() {
    return new TableFormat(cells, columns, heights, headerRows, title, description, sizing, props);
  }

  /**
   * Gives each element differing from {@code base} its revision from {@code old} (when the
   * grids match) or else {@code change}; elements equal to base get none.
   */
  private void assign(TableFormat base, TableFormat old, Revision change) {
    boolean same = old != null && old.rows() == rows() && old.columns() == columns();
    revision = tableKey(this).equals(tableKey(base)) ? null : same && old.revision != null ? old.revision : change;
    if (base.rows() != rows() || base.columns() != columns()) return;
    rowRevisions = new Revision[rows()];
    cellRevisions = new Revision[rows()][columns()];
    for (int r = 0; r < rows(); r++) {
      if (!rowKey(this, r).equals(rowKey(base, r)))
        rowRevisions[r] = same && old.rowRevision(r) != null ? old.rowRevision(r) : change;
      for (int c = 0; c < columns(); c++)
        if (!cellKey(this, r, c).equals(cellKey(base, r, c)))
          cellRevisions[r][c] = same && old.cellRevision(r, c) != null ? old.cellRevision(r, c) : change;
    }
  }

  private static String tableKey(TableFormat f) {
    var p = new java.util.TreeMap<String, String>(f.props);
    p.remove("directions");
    var s = f.sizing;
    return Arrays.toString(f.columns) + '|' + f.title + '|' + f.description + '|' + s.unit + '|' + s.width
        + '|' + s.align + '|' + s.indent + '|' + s.fixed + '|' + p;
  }

  private static String rowKey(TableFormat f, int r) {
    var h = f.heights[r];
    return h.height + "|" + h.rule + '|' + h.allowBreak + '|' + (r < f.headerRows);
  }

  private static String cellKey(TableFormat f, int r, int c) {
    var v = f.cells[r][c];
    return v.background + "|" + v.borderColor + '|' + v.borderWidth + '|' + v.horizontalPadding + '|'
        + v.verticalPadding + '|' + v.vertical + '|' + v.sides + '|' + f.direction(r, c);
  }

  /**
   * Accept keeps an element's current properties, reject restores its prior ones (id null: every
   * revision); the other elements' revisions stay.
   */
  public TableFormat review(String id, boolean accept) {
    if (revision() == null) return this;
    boolean any = false;
    TableFormat old = previous;
    boolean grid = old != null && old.rows() == rows() && old.columns() == columns();
    TableFormat out = this;
    Revision table = revision;
    Revision[] rows = rowRevisions == null ? null : rowRevisions.clone();
    Revision[][] cellRevs = null;
    if (cellRevisions != null) {
      cellRevs = new Revision[cellRevisions.length][];
      for (int r = 0; r < cellRevs.length; r++) cellRevs[r] = cellRevisions[r].clone();
    }
    if (matches(table, id)) {
      any = true;
      table = null;
      if (!accept && old != null)
        out = new TableFormat(cells, old.columns.length == columns.length ? old.columns : columns, heights,
            headerRows, old.title, old.description, old.sizing, directions(old.props, props));
    }
    for (int r = 0; r < rows(); r++) {
      if (rows != null && matches(rows[r], id)) {
        any = true;
        rows[r] = null;
        if (!accept && grid) {
          out = out.row(r, old.heights[r]);
          boolean header = r < old.headerRows;
          if (header != r < out.headerRows && (header ? r == out.headerRows : r == out.headerRows - 1))
            out = out.headerRows(header ? r + 1 : r);
        }
      }
      for (int c = 0; c < columns(); c++)
        if (cellRevs != null && matches(cellRevs[r][c], id)) {
          any = true;
          cellRevs[r][c] = null;
          if (!accept && grid) out = out.cell(r, c, old.cells[r][c]).direction(r, c, old.direction(r, c));
        }
    }
    if (!any) return this;
    return out.elementRevisions(table, rows, cellRevs, old);
  }

  private static boolean matches(Revision r, String id) {
    return r != null && (id == null || id.equals(r.id));
  }

  /** Table-level props from {@code old} with the cell directions of {@code now}. */
  private static java.util.TreeMap<String, String> directions(
      java.util.TreeMap<String, String> old, java.util.TreeMap<String, String> now) {
    var out = new java.util.TreeMap<String, String>(old);
    out.remove("directions");
    if (now.containsKey("directions")) out.put("directions", now.get("directions"));
    return out;
  }

  /**
   * Records a property edit as tracked changes: each element now differing from the properties
   * before the oldest change keeps its revision or gets {@code change}, as Wordcraft records a
   * fmt_change per edited table, row and cell.
   */
  public TableFormat tracked(TableFormat before, Revision change) {
    var base = before.previous != null && before.revision() != null ? before.previous : before.clean();
    var out = clean();
    out.previous = base;
    out.assign(base, before, change);
    return out.revision() == null ? clean() : out;
  }

  private TableFormat keep(TableFormat out) {
    out.revision = revision;
    out.rowRevisions = rowRevisions;
    out.cellRevisions = cellRevisions;
    out.previous = previous;
    return out;
  }

  private TableFormat props(java.util.TreeMap<String, String> value) {
    return keep(new TableFormat(cells, columns, heights, headerRows, title, description, sizing, value));
  }

  public String prop(String key, String fallback) {
    var v = props.get(key);
    return v == null ? fallback : v;
  }

  public java.util.Map<String, String> props() {
    return java.util.Collections.unmodifiableMap(props);
  }

  /**
   * The floating placement (w:tblpPr, Wordcraft TableFloat) from the "float" option, null for a
   * table in the text flow: horizontal anchor (column, margin, page), vertical anchor (text,
   * margin, page), x and y offsets in points, horizontal and vertical alignment ("" uses the
   * offset), then distances from text left, top, right and bottom, and "never" when it may not overlap
   * other floating tables (w:tblOverlap).
   */
  public String[] floating() {
    String v = prop("float", "");
    if (v.isEmpty()) return null;
    String[] p = TextUtil.split(v, ',');
    String[] out = {"column", "text", "0", "0", "", "", "0", "0", "0", "0", ""};
    for (int i = 0; i < Math.min(p.length, out.length); i++) out[i] = p[i];
    return out;
  }

  /** A float option number, 0 when malformed; clamped as Wordcraft clamps tblpPr measures. */
  public static float floatNumber(String v) {
    try {
      float f = Float.parseFloat(v);
      return Float.isNaN(f) ? 0 : Math.max(-1584, Math.min(1584, f));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Sets or clears (empty) a table option from {@link #PROPS}. */
  public TableFormat withProp(String key, String value) {
    if (!PROPS.contains(key)) throw new IllegalArgumentException("Unknown table option: " + key);
    if ("look".equals(key) && value != null)
      for (String v : TextUtil.split(value, ','))
        if (!v.isEmpty() && !"none".equals(v) && !LOOK.contains(v)) throw new IllegalArgumentException("Unknown table look: " + v);
    if (value != null && value.length() > 4096) throw new IllegalArgumentException("Table option too long");
    var next = new java.util.TreeMap<String, String>(props);
    if (value == null || value.isEmpty() || "rtl".equals(key) && "false".equals(value)) next.remove(key);
    else next.put(key, value);
    return props(next);
  }

  /** Cell text direction (lrTb, tbRl or btLr) from the "directions" option, encoded r:c=dir;... */
  public String direction(int row, int column) {
    var key = row + ":" + column + "=";
    for (String part : TextUtil.split(prop("directions", ""), ';'))
      if (part.startsWith(key)) return part.substring(key.length());
    return "lrTb";
  }

  public TableFormat direction(int row, int column, String dir) {
    if (!"lrTb".equals(dir) && !"tbRl".equals(dir) && !"btLr".equals(dir))
      throw new IllegalArgumentException("Text direction is lrTb, tbRl or btLr");
    var b = new StringBuilder();
    var key = row + ":" + column + "=";
    for (String part : TextUtil.split(prop("directions", ""), ';'))
      if (!part.isEmpty() && !part.startsWith(key)) b.append(b.length() == 0 ? "" : ";").append(part);
    if (!"lrTb".equals(dir)) b.append(b.length() == 0 ? "" : ";").append(key).append(dir);
    return withProp("directions", b.toString());
  }

  TableFormat reorder(int[] order) {
    Cell[][] copy = new Cell[rows()][];
    Row[] h = new Row[rows()];
    for (int r = 0; r < order.length; r++) {
      copy[r] = cells[order[r]];
      h[r] = heights[order[r]];
    }
    var out = new TableFormat(copy, columns, h, headerRows, title, description, sizing, props);
    if (revision() == null || previous == null || previous.rows() != rows()) return out;
    Revision[] rowRevs = new Revision[rows()];
    Revision[][] cellRevs = new Revision[rows()][];
    for (int r = 0; r < order.length; r++) {
      rowRevs[r] = rowRevision(order[r]);
      cellRevs[r] = new Revision[columns()];
      for (int c = 0; c < columns(); c++) cellRevs[r][c] = cellRevision(order[r], c);
    }
    return out.elementRevisions(revision, rowRevs, cellRevs, previous.reorder(order));
  }

  private static int original(int n, int at, boolean insert) {
    return insert ? n == at ? -1 : n > at ? n - 1 : n : n >= at ? n + 1 : n;
  }

  private static boolean valid(float x, float min, float max) {
    return !Float.isNaN(x) && !Float.isInfinite(x) && x >= min && x <= max;
  }
}
