package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named table styles with conditional formatting, as Wordcraft's styles.rs TableStyleParts: whole
 * table borders and shading plus header row (firstRow), total row (lastRow), first and last column
 * and row/column bands, each with its own shading, borders and bold/colored text. A table names its
 * style in the "style" format option and picks regions with "look" (w:tblLook).
 *
 * <p>Definitions are document settings {@code tableStyleDef.<id>} holding {@code key=value;...}
 * (native format round trip), read from and written to DOCX styles.xml as w:style w:type="table"
 * with w:tblStylePr regions. Applying a style materializes it into cell formatting and cell text
 * formatting; direct cell formatting read from DOCX wins over the style, as in Word.
 */
final class TableStyles {
  static final String PREFIX = "tableStyleDef.";
  /** Keys of a definition (all optional except name). Colors are RRGGBB, widths points. */
  static final String[] KEYS = {
    "name", "basedOn", "border", "borderWidth", "sides", "fill", "headerFill", "headerBold",
    "headerColor", "headerBorder", "bandFill", "bandSize", "firstColFill", "firstColBold",
    "lastColFill", "lastColBold", "totalFill", "totalBold", "totalTop", "colBandFill"
  };

  private TableStyles() {}

  /** Wordcraft's built-in table styles (styles.rs table_styles). */
  static final Map<String, Map<String, String>> BUILT_INS = new LinkedHashMap<>();

  static {
    put("TableNormal", "name=Normal Table;sides=none");
    put("PlainTable1", "name=Plain Table 1;basedOn=TableNormal;border=bfbfbf;borderWidth=.5;sides=all;"
        + "headerBold=true;bandFill=f2f2f2;firstColBold=true");
    put("GridTable1Light", "name=Grid Table 1 Light;basedOn=TableNormal;border=999999;borderWidth=.5;sides=all;"
        + "headerBold=true;firstColBold=true");
    String[][] accents = {
      {"Blue", "156082", "c1e4f5"}, {"Orange", "e97132", "fbe2d5"},
      {"Green", "196b24", "c1f0c8"}, {"Purple", "712e8d", "e5d2ee"}
    };
    for (String[] a : accents) {
      put("GridTable4Accent" + a[0], "name=Grid Table 4 - " + a[0] + ";basedOn=TableNormal;border=" + a[2]
          + ";borderWidth=.5;sides=all;headerFill=" + a[1] + ";headerBold=true;headerColor=ffffff;bandFill="
          + a[2] + ";firstColBold=true");
      put("ListTable3Accent" + a[0], "name=List Table 3 - " + a[0] + ";basedOn=TableNormal;border=" + a[1]
          + ";borderWidth=.5;sides=outside;headerFill=" + a[1] + ";headerBold=true;headerColor=ffffff;totalTop="
          + a[1]);
    }
  }

  private static void put(String id, String def) {
    BUILT_INS.put(id, parse(def));
  }

  static Map<String, String> parse(String value) {
    var out = new LinkedHashMap<String, String>();
    for (String pair : TextUtil.split(value, ';')) {
      int eq = pair.indexOf('=');
      if (eq > 0) out.put(pair.substring(0, eq), pair.substring(eq + 1));
    }
    return out;
  }

  static String serialize(Map<String, String> def) {
    var b = new StringBuilder();
    for (Map.Entry<String, String> e : def.entrySet()) {
      if (b.length() > 0) b.append(';');
      b.append(e.getKey()).append('=').append(e.getValue().replace(';', ',').replace('=', '-'));
    }
    return b.toString();
  }

  /** A definition by id (the document's own first, then the built-ins); null when unknown. */
  static Map<String, String> own(Document d, String id) {
    String v = d == null ? "" : d.properties.setting(PREFIX + id, "");
    if (!v.isEmpty()) return parse(v);
    return BUILT_INS.get(id);
  }

  /** The style with its based-on chain merged, base first (cycle safe); null when unknown. */
  static Map<String, String> resolve(Document d, String id) {
    var chain = new ArrayList<Map<String, String>>();
    var seen = new java.util.HashSet<String>();
    for (String at = id; at != null && !at.isEmpty() && seen.add(at); ) {
      var def = own(d, at);
      if (def == null) break;
      chain.add(0, def);
      at = def.get("basedOn");
    }
    if (chain.isEmpty()) return null;
    var out = new LinkedHashMap<String, String>();
    for (Map<String, String> def : chain) out.putAll(def);
    out.put("id", id);
    return out;
  }

  /** Ids of the document's styles and the built-ins, for the Table Design gallery. */
  static List<String> ids(Document d) {
    var out = new ArrayList<String>();
    for (String id : BUILT_INS.keySet()) if (!"TableNormal".equals(id)) out.add(id);
    if (d != null)
      for (String k : d.properties.settings().keySet())
        if (k.startsWith(PREFIX) && !out.contains(k.substring(PREFIX.length()))) out.add(k.substring(PREFIX.length()));
    return out;
  }

  private static int color(Map<String, String> def, String key) {
    String v = def.get(key);
    if (v == null || v.isEmpty() || "auto".equals(v) || "none".equals(v)) return -1;
    try {
      return Integer.parseInt(v, 16);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static boolean on(Map<String, String> def, String key) {
    return "true".equals(def.get(key));
  }

  /**
   * Materializes the style into the table's cells for its look. With {@code keepDirect}, cells
   * whose shading or borders differ from the defaults keep them (direct formatting wins).
   */
  static Table apply(Map<String, String> def, Table t, boolean keepDirect) {
    TableFormat f = t.format;
    var look = "," + f.prop("look", TableStyleCommands.DEFAULT_LOOK) + ",";
    boolean header = look.contains(",headerRow,"), bandRows = look.contains(",bandedRows,"),
        bandCols = look.contains(",bandedColumns,"), first = look.contains(",firstColumn,"),
        last = look.contains(",lastColumn,"), total = look.contains(",lastRow,");
    int border = color(def, "border"), fill = color(def, "fill");
    float width = 0.5f;
    try {
      width = Float.parseFloat(def.getOrDefault("borderWidth", ".5"));
    } catch (NumberFormatException ignored) {
    }
    String sideName = def.getOrDefault("sides", border >= 0 ? "all" : "none");
    int bandSize = 1;
    try {
      bandSize = Math.max(1, Integer.parseInt(def.getOrDefault("bandSize", "1")));
    } catch (NumberFormatException ignored) {
    }
    int rows = f.rows(), cols = f.columns();
    for (int r = 0; r < rows; r++)
      for (int c = 0; c < cols; c++) {
        var cell = f.cell(r, c);
        boolean direct = keepDirect && (cell.background != TableFormat.Cell.DEFAULT.background);
        boolean directBorder = keepDirect && (cell.borderColor != TableFormat.Cell.DEFAULT.borderColor
            || cell.borderWidth != TableFormat.Cell.DEFAULT.borderWidth || cell.sides != TableFormat.Cell.DEFAULT.sides);
        boolean isHeader = header && r == 0, isTotal = total && r == rows - 1 && rows > 1,
            isFirst = first && c == 0 && cols > 1, isLast = last && c == cols - 1 && cols > 1;
        int bodyRow = header ? r - 1 : r, bodyCol = first ? c - 1 : c;
        int bg = fill;
        if (bandCols && bodyCol >= 0 && (bodyCol / bandSize) % 2 == 0 && color(def, "colBandFill") >= 0)
          bg = color(def, "colBandFill");
        if (bandRows && bodyRow >= 0 && (bodyRow / bandSize) % 2 == 0 && color(def, "bandFill") >= 0)
          bg = color(def, "bandFill");
        if (isFirst && color(def, "firstColFill") >= 0) bg = color(def, "firstColFill");
        if (isLast && color(def, "lastColFill") >= 0) bg = color(def, "lastColFill");
        if (isTotal && color(def, "totalFill") >= 0) bg = color(def, "totalFill");
        if (isHeader && color(def, "headerFill") >= 0) bg = color(def, "headerFill");
        int sides = TableStyleCommands.sides(sideName);
        if ("outside".equals(sideName)) {
          sides = 0;
          if (r == 0) sides |= TableFormat.Cell.TOP;
          if (r == rows - 1) sides |= TableFormat.Cell.BOTTOM;
          if (c == 0) sides |= TableFormat.Cell.LEFT;
          if (c == cols - 1) sides |= TableFormat.Cell.RIGHT;
        }
        int edge = border;
        if (isTotal && color(def, "totalTop") >= 0) {
          sides |= TableFormat.Cell.TOP;
          if (edge < 0) edge = color(def, "totalTop");
        }
        if (isHeader && color(def, "headerBorder") >= 0) {
          sides |= TableFormat.Cell.BOTTOM;
          edge = color(def, "headerBorder");
        }
        if (!direct) cell = cell.background(bg < 0 ? 0xffffff : bg);
        if (!directBorder) cell = cell.borders(edge < 0 ? 0 : edge, edge < 0 ? 0 : width, edge < 0 ? 0 : sides);
        f = f.cell(r, c, cell);
        boolean bold = isHeader && on(def, "headerBold") || isTotal && on(def, "totalBold")
            || isFirst && on(def, "firstColBold") || isLast && on(def, "lastColBold");
        int text = isHeader ? color(def, "headerColor") : -1;
        if (bold || text >= 0) t = formatText(t, r, c, bold, text);
      }
    if (header) f = f.headerRows(Math.max(1, f.headerRows));
    return t.format(f.withProp("style", def.getOrDefault("id", f.prop("style", ""))));
  }

  private static Table formatText(Table t, int r, int c, boolean bold, int color) {
    if (t.covered(r, c)) return t;
    Sequence<Block> blocks = t.blocks(r, c);
    for (int i = 0; i < blocks.size(); i++)
      if (blocks.get(i) instanceof Paragraph p && p.text.length() > 0) {
        RichText text = p.text;
        if (bold) text = text.format(0, text.length(), "bold", "true");
        if (color >= 0) text = text.format(0, text.length(), "color", Integer.toHexString(0x1000000 | (color & 0xffffff)).substring(1));
        blocks = blocks.set(i, p.text(text));
      }
    return t.setBlocks(r, c, blocks);
  }

  /** Reads w:style w:type="table" definitions from styles.xml (Wordcraft read/props.rs). */
  static Map<String, Map<String, String>> readDocx(Element root) {
    var out = new LinkedHashMap<String, Map<String, String>>();
    if (root == null) return out;
    for (Element s : Xml.children(root, "style")) {
      if (!"table".equals(Xml.attr(s, "type", ""))) continue;
      String id = Xml.attr(s, "styleId", "");
      if (id.isEmpty()) continue;
      var def = new LinkedHashMap<String, String>();
      Element name = Xml.child(s, "name"), based = Xml.child(s, "basedOn");
      def.put("name", name == null ? id : Xml.attr(name, "val", id));
      if (based != null) def.put("basedOn", Xml.attr(based, "val", ""));
      Element tblPr = Xml.child(s, "tblPr");
      if (tblPr != null) {
        Element band = Xml.child(tblPr, "tblStyleRowBandSize");
        if (band != null) def.put("bandSize", Xml.attr(band, "val", "1"));
        borders(Xml.child(tblPr, "tblBorders"), def);
        String shd = fill(tblPr);
        if (shd != null) def.put("fill", shd);
      }
      String whole = fill(Xml.child(s, "tcPr"));
      if (whole != null) def.put("fill", whole);
      for (Element region : Xml.children(s, "tblStylePr")) {
        String type = Xml.attr(region, "type", ""), prefix;
        switch (type) {
          case "firstRow" -> prefix = "header";
          case "lastRow" -> prefix = "total";
          case "firstCol" -> prefix = "firstCol";
          case "lastCol" -> prefix = "lastCol";
          case "band1Horz" -> prefix = "band";
          case "band1Vert" -> prefix = "colBand";
          default -> prefix = null;
        }
        if (prefix == null) continue;
        String f = fill(Xml.child(region, "tcPr"));
        if (f != null) def.put(prefix + "Fill", f);
        Element rPr = Xml.child(region, "rPr");
        if (rPr != null) {
          Element b = Xml.child(rPr, "b"), color = Xml.child(rPr, "color");
          if (b != null && !"0".equals(Xml.attr(b, "val", "1")) && !"false".equals(Xml.attr(b, "val", "1"))
              && !"band".equals(prefix) && !"colBand".equals(prefix))
            def.put(prefix + "Bold", "true");
          if (color != null && "header".equals(prefix) && !"auto".equals(Xml.attr(color, "val", "auto")))
            def.put("headerColor", Xml.attr(color, "val", "").toLowerCase());
        }
        Element tcPr = Xml.child(region, "tcPr"), tcBorders = tcPr == null ? null : Xml.child(tcPr, "tcBorders");
        if (tcBorders != null) {
          Element top = Xml.child(tcBorders, "top"), bottom = Xml.child(tcBorders, "bottom");
          if ("total".equals(prefix) && line(top) != null) def.put("totalTop", line(top));
          if ("header".equals(prefix) && line(bottom) != null) def.put("headerBorder", line(bottom));
        }
      }
      out.put(id, def);
    }
    return out;
  }

  private static String fill(Element props) {
    Element shd = props == null ? null : Xml.child(props, "shd");
    if (shd == null) return null;
    String v = Xml.attr(shd, "fill", "auto");
    return "auto".equals(v) || v.length() != 6 ? null : v.toLowerCase();
  }

  /** A border's color when it draws a line, else null. */
  private static String line(Element e) {
    if (e == null) return null;
    String val = Xml.attr(e, "val", "none");
    if ("none".equals(val) || "nil".equals(val)) return null;
    String c = Xml.attr(e, "color", "auto");
    return "auto".equals(c) || c.length() != 6 ? "000000" : c.toLowerCase();
  }

  private static void borders(Element b, Map<String, String> def) {
    if (b == null) return;
    String top = line(Xml.child(b, "top")), insideH = line(Xml.child(b, "insideH")),
        insideV = line(Xml.child(b, "insideV")), left = line(Xml.child(b, "left"));
    if (left == null) left = line(Xml.child(b, "start"));
    String any = top != null ? top : insideH != null ? insideH : left;
    if (any == null) {
      def.put("sides", "none");
      return;
    }
    def.put("border", any);
    Element sized = Xml.child(b, top != null ? "top" : insideH != null ? "insideH" : left != null && Xml.child(b, "left") != null ? "left" : "start");
    try {
      if (sized != null) def.put("borderWidth", String.valueOf(Integer.parseInt(Xml.attr(sized, "sz", "4")) / 8f));
    } catch (NumberFormatException ignored) {
    }
    def.put("sides", insideH != null && insideV != null ? "all" : insideH != null ? "horizontal"
        : insideV != null ? "vertical" : "outside");
  }

  /** Stores read definitions as document settings (those already present are kept). */
  static DocumentProperties store(DocumentProperties p, Map<String, Map<String, String>> defs) {
    for (Map.Entry<String, Map<String, String>> e : defs.entrySet())
      p = p.withSetting(PREFIX + e.getKey(), serialize(e.getValue()));
    return p;
  }

  /** Materializes each top-level table's named style after a DOCX import (direct formatting wins). */
  static Document applyAll(Document d) {
    Sequence<Block> blocks = d.blocks;
    for (int i = 0; i < blocks.size(); i++)
      if (blocks.get(i) instanceof Table t && !t.format.prop("style", "").isEmpty()) {
        var def = resolve(d, t.format.prop("style", ""));
        if (def != null) blocks = blocks.set(i, apply(def, t, true));
      }
    return blocks == d.blocks ? d : d.blocks(blocks, d.nextId);
  }

  /** The w:style elements for the document's table styles and the built-ins its tables use. */
  static void writeDocx(StringBuilder xml, Document d) {
    var ids = new java.util.LinkedHashSet<String>();
    for (String k : d.properties.settings().keySet())
      if (k.startsWith(PREFIX)) ids.add(k.substring(PREFIX.length()));
    for (Block b : d.blocks)
      if (b instanceof Table t && !t.format.prop("style", "").isEmpty()) ids.add(t.format.prop("style", ""));
    // Based-on styles are written too, so the chain resolves in Word.
    var all = new java.util.LinkedHashSet<String>();
    for (String id : ids)
      for (String at = id; at != null && own(d, at) != null && all.add(at); ) at = own(d, at).get("basedOn");
    for (String id : all) {
      var def = own(d, id);
      xml.append("<w:style w:type=\"table\" w:styleId=\"").append(Xml.escape(id)).append("\"><w:name w:val=\"")
          .append(Xml.escape(def.getOrDefault("name", id))).append("\"/>");
      if (def.containsKey("basedOn"))
        xml.append("<w:basedOn w:val=\"").append(Xml.escape(def.get("basedOn"))).append("\"/>");
      xml.append("<w:tblPr>");
      if (def.containsKey("bandSize"))
        xml.append("<w:tblStyleRowBandSize w:val=\"").append(Xml.escape(def.get("bandSize"))).append("\"/>");
      if (def.containsKey("border")) {
        String sides = def.getOrDefault("sides", "all");
        int sz = Math.round(Float.parseFloat(def.getOrDefault("borderWidth", ".5")) * 8);
        xml.append("<w:tblBorders>");
        for (String edge : new String[] {"top", "left", "bottom", "right", "insideH", "insideV"}) {
          boolean drawn = "all".equals(sides)
              || "outside".equals(sides) && !edge.startsWith("inside")
              || "horizontal".equals(sides) && (edge.equals("top") || edge.equals("bottom") || edge.equals("insideH"))
              || "vertical".equals(sides) && (edge.equals("left") || edge.equals("right") || edge.equals("insideV"));
          if (drawn)
            xml.append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"").append(sz)
                .append("\" w:space=\"0\" w:color=\"").append(Xml.escape(def.get("border"))).append("\"/>");
        }
        xml.append("</w:tblBorders>");
      }
      xml.append("</w:tblPr>");
      if (def.containsKey("fill"))
        xml.append("<w:tcPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(Xml.escape(def.get("fill")))
            .append("\"/></w:tcPr>");
      String[][] regions = {
        {"firstRow", "header"}, {"lastRow", "total"}, {"firstCol", "firstCol"}, {"lastCol", "lastCol"},
        {"band1Vert", "colBand"}, {"band1Horz", "band"}
      };
      for (String[] r : regions) {
        String fill = def.get(r[1] + "Fill");
        boolean bold = "true".equals(def.get(r[1] + "Bold"));
        String color = "header".equals(r[1]) ? def.get("headerColor") : null;
        String top = "total".equals(r[1]) ? def.get("totalTop") : null;
        String bottom = "header".equals(r[1]) ? def.get("headerBorder") : null;
        if (fill == null && !bold && color == null && top == null && bottom == null) continue;
        xml.append("<w:tblStylePr w:type=\"").append(r[0]).append("\">");
        if (bold || color != null) {
          xml.append("<w:rPr>");
          if (bold) xml.append("<w:b/>");
          if (color != null) xml.append("<w:color w:val=\"").append(Xml.escape(color)).append("\"/>");
          xml.append("</w:rPr>");
        }
        if (fill != null || top != null || bottom != null) {
          xml.append("<w:tcPr>");
          if (top != null || bottom != null)
            xml.append("<w:tcBorders><w:").append(top != null ? "top" : "bottom")
                .append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"")
                .append(Xml.escape(top != null ? top : bottom)).append("\"/></w:tcBorders>");
          if (fill != null)
            xml.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(Xml.escape(fill)).append("\"/>");
          xml.append("</w:tcPr>");
        }
        xml.append("</w:tblStylePr>");
      }
      xml.append("</w:style>");
    }
  }
}
