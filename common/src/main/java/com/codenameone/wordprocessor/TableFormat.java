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
    if (revision != null && previous != null && previous.rows() == rows() && previous.columns() == columns()) {
      result.revision = revision;
      result.previous = previous.change(row, at, insert);
    }
    return result;
  }

  /**
   * Tracked table property change (w:tblPrChange, w:trPrChange, w:tcPrChange; Wordcraft
   * TableProps/RowProps/CellProps fmt_change): the revision and the whole table's properties
   * before it. One revision covers the table, its rows and cells (Wordcraft keeps one per
   * element; ids of separate row/cell changes are merged into the table's on read).
   */
  public Revision revision() {
    return revision;
  }

  public TableFormat previous() {
    return previous;
  }

  private Revision revision;
  private TableFormat previous;

  public TableFormat formatRevision(Revision value, TableFormat before) {
    var out = new TableFormat(cells, columns, heights, headerRows, title, description, sizing, props);
    if (value != null && before != null) {
      out.revision = value;
      out.previous = before.revision == null ? before : before.formatRevision(null, null);
    }
    return out;
  }

  /** Accept keeps the current properties, reject restores the prior ones (id null: any). */
  public TableFormat review(String id, boolean accept) {
    if (revision == null || id != null && !id.equals(revision.id)) return this;
    return accept ? formatRevision(null, null) : previous;
  }

  /** Records a property edit as a tracked change, keeping the oldest prior properties. */
  public TableFormat tracked(TableFormat before, Revision change) {
    if (before.revision != null) return formatRevision(before.revision, before.previous);
    return formatRevision(change, before);
  }

  private TableFormat keep(TableFormat out) {
    out.revision = revision;
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
   * offset), then distances from text left, top, right and bottom.
   */
  public String[] floating() {
    String v = prop("float", "");
    if (v.isEmpty()) return null;
    String[] p = TextUtil.split(v, ',');
    String[] out = {"column", "text", "0", "0", "", "", "0", "0", "0", "0"};
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
    return keep(new TableFormat(copy, columns, h, headerRows, title, description, sizing, props));
  }

  private static int original(int n, int at, boolean insert) {
    return insert ? n == at ? -1 : n > at ? n - 1 : n : n >= at ? n + 1 : n;
  }

  private static boolean valid(float x, float min, float max) {
    return !Float.isNaN(x) && !Float.isInfinite(x) && x >= min && x <= max;
  }
}
