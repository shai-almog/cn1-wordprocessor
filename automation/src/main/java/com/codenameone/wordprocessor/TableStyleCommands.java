package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Table style options and custom table styles, the border painter, the draw/erase pens, table
 * direction and cell text direction. A style is a small spec (header fill, band fill, border
 * color/width/sides) materialized into cell formatting according to the table's look options;
 * the applied style name and look are kept on the table so a look or style change re-applies it.
 * Custom styles are document settings named {@code tableStyle.<Name>}.
 */
final class TableStyleCommands implements Commands.Extension {
  static final String PREFIX = "tableStyle.";

  /** Built-in specs: name, header fill, band fill, border color, border width, sides. */
  static final String[][] BUILT_IN = {
    {"TableGrid", "none", "none", "000000", ".5", "all"},
    {"PlainTable", "none", "none", "000000", "0", "none"},
    {"GridTableLight", "none", "none", "bfbfbf", ".5", "all"},
    {"ListTable", "none", "f2f2f2", "7f7f7f", ".5", "horizontal"},
    {"GridTableAccent1", "4472c4", "d9e2f3", "8eaadb", ".5", "all"}
  };

  static final String DEFAULT_LOOK = "headerRow,firstColumn,bandedRows";

  public void register(Commands c) {
    c.register(
        "table.look",
        "Table style options: headerRow, firstColumn, lastRow, lastColumn, bandedRows, bandedColumns",
        "headerRow?:boolean,firstColumn?:boolean,lastRow?:boolean,lastColumn?:boolean,bandedRows?:boolean,bandedColumns?:boolean",
        true,
        (s, a) -> {
          Table t = table(s);
          var look = t.format.prop("look", DEFAULT_LOOK);
          var b = new StringBuilder();
          for (String k : TableFormat.LOOK) {
            boolean on = a.containsKey(k) ? Boolean.TRUE.equals(a.get(k)) : ("," + look + ",").contains("," + k + ",");
            if (on) b.append(b.length() == 0 ? "" : ",").append(k);
          }
          var f = t.format.withProp("look", b.length() == 0 ? "none" : b.toString());
          var style = f.prop("style", "");
          if (!style.isEmpty()) f = apply(spec(s.document(), style), f);
          s.formatTable(s.cellBlock(), f);
          return lookMap(f);
        });
    c.register(
        "table.newStyle",
        "Create a custom table style: header/band fills, border color, width and sides (all, horizontal, outside, none)",
        "name:string,header?:string,band?:string,border?:string,width?:number,sides?:string,basedOn?:string",
        true,
        (s, a) -> {
          String name = name(a);
          if (spec(s.document(), name, false) != null) throw new IllegalArgumentException("Style exists: " + name);
          String[] base = a.containsKey("basedOn") ? spec(s.document(), Commands.str(a, "basedOn")) : BUILT_IN[0];
          String[] spec = edit(base.clone(), a);
          spec[0] = name;
          save(s, name, spec);
          return describe(spec);
        });
    c.register(
        "table.modifyStyle",
        "Modify a table style (a built-in becomes a document override) and re-apply it to every table using it",
        "name:string,header?:string,band?:string,border?:string,width?:number,sides?:string",
        true,
        (s, a) -> {
          String name = name(a);
          String[] spec = edit(spec(s.document(), name).clone(), a);
          save(s, name, spec);
          reapply(s, name);
          return describe(spec);
        });
    c.register(
        "table.deleteStyle",
        "Delete a custom table style; tables keep their current formatting",
        "name:string",
        true,
        (s, a) -> {
          String name = name(a);
          if (!s.document().properties.settings().containsKey(key(name)))
            throw new IllegalArgumentException(
                Arrays.asList(names(null)).contains(name) ? "Built-in table styles cannot be deleted" : "Unknown table style: " + name);
          var d = s.document();
          Sequence<Block> blocks = d.blocks;
          for (int i = 0; i < blocks.size(); i++)
            if (blocks.get(i) instanceof Table && name.equals(((Table) blocks.get(i)).format.prop("style", ""))) {
              var t = (Table) blocks.get(i);
              blocks = blocks.set(i, t.format(t.format.withProp("style", "")));
            }
          s.updateBlocks(blocks);
          s.documentProperties(s.document().properties.withSetting(key(name), ""));
          return null;
        });
    c.register(
        "table.styles",
        "List built-in and custom table style names",
        "",
        false,
        (s, a) -> Arrays.asList(names(s.document())));
    c.register(
        "table.borderPainter",
        "Border painter: paint the pen (color, width, sides) onto the selected cells",
        "color?:string,width?:number,sides?:string",
        true,
        (s, a) -> {
          int color = a.containsKey("color") ? Integer.parseInt(hex(Commands.str(a, "color")), 16) : 0;
          float width = a.containsKey("width") ? ((Number) a.get("width")).floatValue() : .5f;
          if (width < 0 || width > 12) throw new IllegalArgumentException("Pen width must be 0-12pt");
          String sides = a.containsKey("sides") ? Commands.str(a, "sides") : "all";
          Table t = table(s);
          var r = s.cellSelection();
          int r0 = r == null ? s.cellRow() : r.firstRow, r1 = r == null ? s.cellRow() : r.lastRow;
          int c0 = r == null ? s.cellColumn() : r.firstColumn, c1 = r == null ? s.cellColumn() : r.lastColumn;
          var f = t.format;
          for (int row = r0; row <= r1; row++)
            for (int col = c0; col <= c1; col++) {
              var cell = f.cell(row, col);
              int mask = sides(sides);
              if ("outside".equals(sides)) {
                mask = (row == r0 ? TableFormat.Cell.TOP : 0) | (row == r1 ? TableFormat.Cell.BOTTOM : 0)
                    | (col == c0 ? TableFormat.Cell.LEFT : 0) | (col == c1 ? TableFormat.Cell.RIGHT : 0);
                mask |= cell.borderWidth > 0 ? cell.sides : 0;
              }
              f = f.cell(row, col, cell.borders(color, width, mask));
            }
          s.formatTable(s.cellBlock(), f);
          return null;
        });
    c.register(
        "table.draw",
        "Draw Table pen: a vertical line splits the cell into two columns, a horizontal line adds a row below; outside a table it draws a 1x1 table",
        "orientation?:string",
        true,
        (s, a) -> {
          if (s.cellBlock() < 0) {
            s.insertTable(1, 1);
            return "table";
          }
          String o = a.containsKey("orientation") ? Commands.str(a, "orientation") : "vertical";
          int block = s.cellBlock(), row = s.cellRow(), col = s.cellColumn();
          if ("vertical".equals(o)) s.splitCell(block, row, col, 2);
          else if ("horizontal".equals(o)) c.execute(s, "table.insertRowBelow", new LinkedHashMap<>());
          else throw new IllegalArgumentException("Orientation is vertical or horizontal");
          return o;
        });
    c.register(
        "table.eraser",
        "Eraser: remove the border between the current cell and its neighbor (right or down) by merging them",
        "direction?:string",
        true,
        (s, a) -> {
          Table t = table(s);
          int block = s.cellBlock();
          var span = t.span(s.cellRow(), s.cellColumn());
          String dir = a.containsKey("direction") ? Commands.str(a, "direction") : "right";
          if ("right".equals(dir)) {
            if (span.column + span.columns >= t.columns()) throw new IllegalArgumentException("No cell to the right");
            var next = t.span(span.row, span.column + span.columns);
            if (next.row != span.row || next.rows != span.rows)
              throw new IllegalArgumentException("Cells must have the same height to erase the border");
            s.mergeCells(block, span.row, span.column, span.rows, span.columns + next.columns);
          } else if ("down".equals(dir)) {
            if (span.row + span.rows >= t.rows()) throw new IllegalArgumentException("No cell below");
            var next = t.span(span.row + span.rows, span.column);
            if (next.column != span.column || next.columns != span.columns)
              throw new IllegalArgumentException("Cells must have the same width to erase the border");
            s.mergeCells(block, span.row, span.column, span.rows + next.rows, span.columns);
          } else throw new IllegalArgumentException("Direction is right or down");
          return null;
        });
    c.register(
        "table.rtl",
        "Right-to-left table: columns are laid out from the right",
        "value?:boolean",
        true,
        (s, a) -> {
          Table t = table(s);
          boolean on = !a.containsKey("value") || Boolean.TRUE.equals(a.get("value"));
          s.formatTable(s.cellBlock(), t.format.withProp("rtl", String.valueOf(on)));
          return on;
        });
    c.register(
        "table.textDirection",
        "Text direction of the selected cells: lrTb (horizontal), tbRl (rotate 90) or btLr (rotate 270)",
        "value:string",
        true,
        (s, a) -> {
          Table t = table(s);
          String dir = Commands.str(a, "value");
          var r = s.cellSelection();
          int r0 = r == null ? s.cellRow() : r.firstRow, r1 = r == null ? s.cellRow() : r.lastRow;
          int c0 = r == null ? s.cellColumn() : r.firstColumn, c1 = r == null ? s.cellColumn() : r.lastColumn;
          var f = t.format;
          for (int row = r0; row <= r1; row++)
            for (int col = c0; col <= c1; col++) f = f.direction(row, col, dir);
          s.formatTable(s.cellBlock(), f);
          return dir;
        });
  }

  static void applyStyle(Session s, String style) {
    Table t = table(s);
    String[] spec = spec(s.document(), style);
    s.formatTable(s.cellBlock(), apply(spec, t.format.withProp("style", spec[0])));
  }

  /** Materializes a spec into cell formatting according to the table's look options. */
  static TableFormat apply(String[] spec, TableFormat f) {
    var look = "," + f.prop("look", DEFAULT_LOOK) + ",";
    boolean header = look.contains(",headerRow,"), bandRows = look.contains(",bandedRows,"),
        bandCols = look.contains(",bandedColumns,"), first = look.contains(",firstColumn,"),
        last = look.contains(",lastColumn,"), total = look.contains(",lastRow,");
    int head = color(spec[1]), band = color(spec[2]), border = color(spec[3]);
    float width = Float.parseFloat(spec[4]);
    int mask = sides(spec[5]);
    int rows = f.rows(), cols = f.columns();
    for (int r = 0; r < rows; r++)
      for (int c = 0; c < cols; c++) {
        int fill = 0xffffff;
        int bodyRow = header ? r - 1 : r, bodyCol = first ? c - 1 : c;
        if (band >= 0 && bandRows && bodyRow >= 0 && bodyRow % 2 == 0) fill = band;
        if (band >= 0 && bandCols && bodyCol >= 0 && bodyCol % 2 == 0) fill = band;
        boolean emphasized =
            header && r == 0 || total && r == rows - 1 && rows > 1 || first && c == 0 && cols > 1
                || last && c == cols - 1 && cols > 1;
        if (emphasized && head >= 0) fill = header && r == 0 ? head : band >= 0 ? darker(band) : head;
        int sides = mask;
        if ("horizontal".equals(spec[5]) && total && r == rows - 1) sides |= TableFormat.Cell.TOP;
        f = f.cell(r, c, f.cell(r, c).background(fill).borders(border < 0 ? 0 : border, width, sides));
      }
    return header ? f.headerRows(Math.max(1, f.headerRows)) : f;
  }

  private static int darker(int rgb) {
    int r = (rgb >> 16 & 255) * 85 / 100, g = (rgb >> 8 & 255) * 85 / 100, b = (rgb & 255) * 85 / 100;
    return r << 16 | g << 8 | b;
  }

  static int sides(String s) {
    if ("all".equals(s) || "outside".equals(s)) return TableFormat.Cell.ALL;
    if ("horizontal".equals(s)) return TableFormat.Cell.TOP | TableFormat.Cell.BOTTOM;
    if ("vertical".equals(s)) return TableFormat.Cell.LEFT | TableFormat.Cell.RIGHT;
    if ("none".equals(s)) return 0;
    throw new IllegalArgumentException("Sides are all, outside, horizontal, vertical or none");
  }

  private static int color(String v) {
    return "none".equals(v) ? -1 : Integer.parseInt(v, 16);
  }

  private static void reapply(Session s, String name) {
    String[] spec = spec(s.document(), name);
    var d = s.document();
    Sequence<Block> blocks = d.blocks;
    for (int i = 0; i < blocks.size(); i++)
      if (blocks.get(i) instanceof Table && name.equals(((Table) blocks.get(i)).format.prop("style", ""))) {
        var t = (Table) blocks.get(i);
        blocks = blocks.set(i, t.format(apply(spec, t.format)));
      }
    if (blocks != d.blocks) s.updateBlocks(blocks);
  }

  static String[] spec(Document d, String name) {
    String[] spec = spec(d, name, true);
    if (spec == null) throw new IllegalArgumentException("Unknown table style: " + name + "; choose " + Arrays.toString(names(d)));
    return spec;
  }

  private static String[] spec(Document d, String name, boolean builtIns) {
    var custom = d.properties.setting(key(name), "");
    if (!custom.isEmpty()) {
      String[] p = TextUtil.split(custom, ',');
      if (p.length == 5) return new String[] {name, p[0], p[1], p[2], p[3], p[4]};
    }
    for (String[] b : BUILT_IN) if (b[0].equals(name)) return b;
    return null;
  }

  static String[] names(Document d) {
    var out = new ArrayList<String>();
    for (String[] b : BUILT_IN) out.add(b[0]);
    if (d != null)
      for (String k : d.properties.settings().keySet())
        if (k.startsWith(PREFIX)) {
          var n = k.substring(PREFIX.length()).replace('_', ' ');
          if (!out.contains(n)) out.add(n);
        }
    return out.toArray(new String[0]);
  }

  private static String key(String name) {
    return PREFIX + name.replace(' ', '_');
  }

  private static String name(Map<String, Object> a) {
    var name = Commands.str(a, "name").trim();
    if (name.isEmpty() || name.length() > 40) throw new IllegalArgumentException("Style name must be 1-40 characters");
    for (int i = 0; i < name.length(); i++) {
      char ch = name.charAt(i);
      if (ch > 127 || !(Character.isLetterOrDigit(ch) || ch == ' '))
        throw new IllegalArgumentException("Style names use letters, digits and spaces");
    }
    return name;
  }

  private static String[] edit(String[] spec, Map<String, Object> a) {
    if (a.containsKey("header")) spec[1] = fill(Commands.str(a, "header"));
    if (a.containsKey("band")) spec[2] = fill(Commands.str(a, "band"));
    if (a.containsKey("border")) spec[3] = hex(Commands.str(a, "border")).toLowerCase();
    if (a.containsKey("width")) {
      float w = ((Number) a.get("width")).floatValue();
      if (w < 0 || w > 12) throw new IllegalArgumentException("Border width must be 0-12pt");
      spec[4] = String.valueOf(w);
    }
    if (a.containsKey("sides")) {
      sides(Commands.str(a, "sides"));
      spec[5] = Commands.str(a, "sides");
    }
    return spec;
  }

  private static String fill(String v) {
    return "none".equals(v) ? "none" : hex(v).toLowerCase();
  }

  private static void save(Session s, String name, String[] spec) {
    var value = spec[1] + "," + spec[2] + "," + spec[3] + "," + spec[4] + "," + spec[5];
    s.documentProperties(s.document().properties.withSetting(key(name), value));
  }

  private static Map<String, Object> describe(String[] spec) {
    var out = new LinkedHashMap<String, Object>();
    out.put("name", spec[0]);
    out.put("header", spec[1]);
    out.put("band", spec[2]);
    out.put("border", spec[3]);
    out.put("width", Float.parseFloat(spec[4]));
    out.put("sides", spec[5]);
    return out;
  }

  private static Map<String, Object> lookMap(TableFormat f) {
    var out = new LinkedHashMap<String, Object>();
    var look = "," + f.prop("look", DEFAULT_LOOK) + ",";
    for (String k : TableFormat.LOOK) out.put(k, look.contains("," + k + ","));
    return out;
  }

  private static String hex(String v) {
    String h = v.startsWith("#") ? v.substring(1) : v;
    if (h.length() != 6) throw new IllegalArgumentException("Color must be RRGGBB");
    Integer.parseInt(h, 16);
    return h;
  }

  private static Table table(Session s) {
    int block = s.cellBlock();
    if (block < 0) throw new IllegalArgumentException("Place the caret in a table");
    return (Table) s.document().blocks.get(block);
  }
}
