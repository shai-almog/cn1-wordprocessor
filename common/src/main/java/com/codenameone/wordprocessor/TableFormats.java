package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.util.*;

/** Native and OOXML table formatting. Unsupported asymmetric borders/margins are reported. */
final class TableFormats {
  static void nativeXml(StringBuilder b, TableFormat f) {
    b.append("<tableFormat headers=\"").append(f.headerRows).append("\"");
    if (!f.title.isEmpty()) b.append(" title=\"").append(Xml.attribute(f.title)).append("\"");
    if (!f.description.isEmpty())
      b.append(" description=\"").append(Xml.attribute(f.description)).append("\"");
    for (Map.Entry<String, String> e : f.props().entrySet())
      b.append(" opt-").append(e.getKey()).append("=\"").append(Xml.attribute(e.getValue())).append('"');
    var size = f.sizing;
    if (!"percent".equals(size.unit)
        || size.width != 100
        || !"left".equals(size.align)
        || size.indent != 0
        || !size.fixed)
      b.append(" widthUnit=\"")
          .append(size.unit)
          .append("\" width=\"")
          .append(size.width)
          .append("\" align=\"")
          .append(size.align)
          .append("\" indent=\"")
          .append(size.indent)
          .append("\" fixed=\"")
          .append(size.fixed ? "true" : "false")
          .append("\"");
    RevisionFormats.attributes(b, f.revision(), "format-");
    b.append(">");
    if (f.revision() != null && f.previous() != null) {
      b.append("<previousFormat>");
      nativeXml(b, f.previous());
      b.append("</previousFormat>");
    }
    for (int c = 0; c < f.columns(); c++)
      b.append("<column width=\"").append(f.column(c)).append("\"/>");
    for (int r = 0; r < f.rows(); r++) {
      b.append("<row height=\"").append(f.minHeight(r)).append("\"");
      if ("exact".equals(f.row(r).rule)) b.append(" rule=\"exact\"");
      if (!f.row(r).allowBreak) b.append(" allowBreak=\"false\"");
      b.append(">");
      for (int c = 0; c < f.columns(); c++) {
        var v = f.cell(r, c);
        b.append("<cell background=\"")
            .append(hex(v.background))
            .append("\" borderColor=\"")
            .append(hex(v.borderColor))
            .append("\" borderWidth=\"")
            .append(v.borderWidth)
            .append(v.sides == TableFormat.Cell.ALL ? "" : "\" sides=\"" + v.sides)
            .append("\" horizontalPadding=\"")
            .append(v.horizontalPadding)
            .append("\" verticalPadding=\"")
            .append(v.verticalPadding)
            .append("\" vertical=\"")
            .append(v.vertical)
            .append("\"/>");
      }
      b.append("</row>");
    }
    b.append("</tableFormat>");
  }

  static TableFormat nativeRead(Element table, int rows, int columns) {
    var f = new TableFormat(rows, columns);
    Element props = Xml.child(table, "tableFormat");
    if (props == null) return f;
    if (Xml.children(props, "column").size() > columns || Xml.children(props, "row").size() > rows)
      throw new IllegalArgumentException("Table format dimensions exceed the table");
    for (Element row : Xml.children(props, "row"))
      if (Xml.children(row, "cell").size() > columns)
        throw new IllegalArgumentException("Table cell formats exceed the table width");
    int c = 0, r = 0;
    for (Element col : Xml.children(props, "column")) f = f.column(c++, number(col, "width", 1));
    for (Element row : Xml.children(props, "row")) {
      float height = number(row, "height", 0);
      f =
          f.row(
              r,
              new TableFormat.Row(
                  height,
                  Xml.attr(row, "rule", height == 0 ? "auto" : "atLeast"),
                  !"false".equals(Xml.attr(row, "allowBreak", "true"))));
      c = 0;
      for (Element cell : Xml.children(row, "cell"))
        f =
            f.cell(
                r,
                c++,
                new TableFormat.Cell(
                    color(cell, "background", 0xffffff),
                    color(cell, "borderColor", 0xa6a6a6),
                    number(cell, "borderWidth", .5f),
                    number(cell, "horizontalPadding", 6),
                    number(cell, "verticalPadding", 4),
                    Xml.attr(cell, "vertical", "top"),
                    Integer.parseInt(Xml.attr(cell, "sides", "15"))));
      r++;
    }
    f =
        f.sizing(
            new TableFormat.Sizing(
                Xml.attr(props, "widthUnit", "percent"),
                number(props, "width", 100),
                Xml.attr(props, "align", "left"),
                number(props, "indent", 0),
                !"false".equals(Xml.attr(props, "fixed", "true"))));
    for (String key : TableFormat.PROPS) f = f.withProp(key, Xml.attr(props, "opt-" + key, ""));
    f = f.headerRows(Integer.parseInt(Xml.attr(props, "headers", "0")))
        .altText(Xml.attr(props, "title", ""), Xml.attr(props, "description", ""));
    Revision change = RevisionFormats.nativeRead(props, "format-");
    Element prior = Xml.child(props, "previousFormat");
    return change == null || prior == null ? f : f.formatRevision(change, nativeRead(prior, rows, columns));
  }

  static void sizingXml(StringBuilder b, TableFormat f) {
    if ("true".equals(f.prop("rtl", ""))) b.append("<w:bidiVisual/>");
    var size = f.sizing;
    b.append("<w:tblW w:type=\"")
        .append("points".equals(size.unit) ? "dxa" : "percent".equals(size.unit) ? "pct" : "auto")
        .append("\" w:w=\"")
        .append(Math.round(size.width * ("percent".equals(size.unit) ? 50 : 20)))
        .append("\"/><w:jc w:val=\"")
        .append(size.align)
        .append("\"/><w:tblInd w:type=\"dxa\" w:w=\"")
        .append(Math.round(size.indent * 20))
        .append("\"/><w:tblLayout w:type=\"")
        .append(size.fixed ? "fixed" : "autofit")
        .append("\"/>");
  }

  static String css(TableFormat f) {
    var size = f.sizing;
    String width =
        "auto".equals(size.unit) ? "auto" : size.width + ("points".equals(size.unit) ? "pt" : "%");
    return "border-collapse:collapse;width:"
        + width
        + ";table-layout:"
        + (size.fixed ? "fixed" : "auto")
        + ";margin-left:"
        + ("left".equals(size.align) ? size.indent + "pt" : "auto")
        + ";margin-right:"
        + ("center".equals(size.align) ? "auto" : "0");
  }

  /** w:tblpPr as the "float" option (Wordcraft read/props.rs table_float). */
  static String readFloat(Element e) {
    String h = Xml.attr(e, "horzAnchor", "text"), v = Xml.attr(e, "vertAnchor", "text");
    String[] measures = {"tblpX", "tblpY", "", "", "leftFromText", "topFromText", "rightFromText", "bottomFromText"};
    String[] out = new String[10];
    out[0] = "page".equals(h) || "margin".equals(h) ? h : "column";
    out[1] = "page".equals(v) || "margin".equals(v) ? v : "text";
    out[4] = Xml.attr(e, "tblpXSpec", "");
    out[5] = Xml.attr(e, "tblpYSpec", "");
    for (int i = 0; i < measures.length; i++) {
      if (measures[i].isEmpty()) continue;
      float pt = 0;
      try {
        pt = Integer.parseInt(Xml.attr(e, measures[i], "0")) / 20f;
      } catch (NumberFormatException ignored) {
      }
      pt = Math.max(i >= 4 ? 0 : -1584, Math.min(1584, pt));
      out[i + 2] = pt == (int) pt ? String.valueOf((int) pt) : String.valueOf(pt);
    }
    var joined = new StringBuilder(out[0]);
    for (int i = 1; i < out.length; i++) joined.append(',').append(out[i]);
    return joined.toString();
  }

  /** The "float" option as w:tblpPr (it follows w:tblStyle in w:tblPr). */
  static void floatXml(StringBuilder b, TableFormat format) {
    if (!format.prop("style", "").isEmpty())
      b.append("<w:tblStyle w:val=\"").append(Xml.attribute(format.prop("style", ""))).append("\"/>");
    String[] f = format.floating();
    if (f == null) return;
    b.append("<w:tblpPr w:leftFromText=\"").append(Math.round(TableFormat.floatNumber(f[6]) * 20))
        .append("\" w:rightFromText=\"").append(Math.round(TableFormat.floatNumber(f[8]) * 20))
        .append("\" w:topFromText=\"").append(Math.round(TableFormat.floatNumber(f[7]) * 20))
        .append("\" w:bottomFromText=\"").append(Math.round(TableFormat.floatNumber(f[9]) * 20))
        .append("\" w:vertAnchor=\"").append("page".equals(f[1]) || "margin".equals(f[1]) ? f[1] : "text")
        .append("\" w:horzAnchor=\"").append("page".equals(f[0]) || "margin".equals(f[0]) ? f[0] : "text").append('"');
    if (!f[4].isEmpty()) b.append(" w:tblpXSpec=\"").append(Xml.attribute(f[4])).append('"');
    else b.append(" w:tblpX=\"").append(Math.round(TableFormat.floatNumber(f[2]) * 20)).append('"');
    if (!f[5].isEmpty()) b.append(" w:tblpYSpec=\"").append(Xml.attribute(f[5])).append('"');
    else b.append(" w:tblpY=\"").append(Math.round(TableFormat.floatNumber(f[3]) * 20)).append('"');
    b.append("/>");
  }

  static void alternativeXml(StringBuilder b, TableFormat format) {
    if (format.props().containsKey("look")) {
      var look = "," + format.prop("look", "") + ",";
      String[][] attrs = {
        {"headerRow", "firstRow"}, {"lastRow", "lastRow"}, {"firstColumn", "firstColumn"},
        {"lastColumn", "lastColumn"}, {"bandedRows", "noHBand"}, {"bandedColumns", "noVBand"}
      };
      b.append("<w:tblLook");
      for (String[] a : attrs) {
        boolean on = look.contains("," + a[0] + ",");
        if (a[1].startsWith("no")) on = !on;
        b.append(" w:").append(a[1]).append("=\"").append(on ? 1 : 0).append('"');
      }
      b.append("/>");
    }
    if (!format.title.isEmpty())
      b.append("<w:tblCaption w:val=\"").append(Xml.attribute(format.title)).append("\"/>");
    if (!format.description.isEmpty())
      b.append("<w:tblDescription w:val=\"")
          .append(Xml.attribute(format.description))
          .append("\"/>");
  }

  static void rowXml(StringBuilder b, TableFormat f, int r) {
    b.append("<w:trPr>");
    rowInner(b, f, r);
    var before = f.previous();
    if (f.revision() != null && before != null && r < before.rows()) {
      var now = new StringBuilder();
      var old = new StringBuilder();
      rowInner(now, f, r);
      rowInner(old, before, r);
      if (!now.toString().equals(old.toString()))
        change(b, "trPrChange", "trPr", f.revision(), old.toString());
    }
    b.append("</w:trPr>");
  }

  /** A tracked property change element (w:tblPrChange, w:trPrChange, w:tcPrChange). */
  static void change(StringBuilder b, String name, String props, Revision r, String inner) {
    b.append("<w:").append(name).append(" w:id=\"").append(Xml.escape(r.id))
        .append("\" w:author=\"").append(Xml.escape(r.author))
        .append("\" w:date=\"").append(Xml.escape(r.date)).append("\"><w:")
        .append(props).append('>').append(inner).append("</w:").append(props).append("></w:")
        .append(name).append('>');
  }

  /**
   * The prior table properties a tracked change carries: the table read again with each
   * w:tblPrChange/w:trPrChange/w:tcPrChange's old properties in place of the current ones
   * (merges kept from the current cell properties), null when there is none. Wordcraft reads
   * one revision per element; the first found becomes the table's.
   */
  static Table readTracked(Element tbl, Table read, Table skeleton) {
    var swaps = new ArrayList<Element[]>();
    Revision[] found = {null};
    collectChange(swaps, found, Xml.child(tbl, "tblPr"), "tblPrChange", "tblPr");
    for (Element tr : Xml.children(tbl, "tr")) {
      collectChange(swaps, found, Xml.child(tr, "trPr"), "trPrChange", "trPr");
      for (Element tc : Xml.children(tr, "tc")) collectChange(swaps, found, Xml.child(tc, "tcPr"), "tcPrChange", "tcPr");
    }
    if (found[0] == null) return read;
    var saved = new ArrayList<List<Element>>();
    for (Element[] swap : swaps) {
      var current = new ArrayList<Element>();
      for (int i = swap[0].getNumChildren() - 1; i >= 0; i--) {
        var child = (Element) swap[0].getChildAt(i);
        String n = Xml.local(child);
        if ("gridSpan".equals(n) || "vMerge".equals(n) || "hMerge".equals(n)) continue;
        current.add(0, child);
        swap[0].removeChildAt(i);
      }
      saved.add(current);
      var old = new ArrayList<Element>();
      for (int i = 0; i < swap[1].getNumChildren(); i++) old.add((Element) swap[1].getChildAt(i));
      for (Element e : old) swap[1].removeChildAt(swap[1].getChildIndex(e));
      for (Element e : old) swap[0].addChild(e);
    }
    Table previous;
    try {
      previous = readDocx(tbl, skeleton, new HashSet<String>());
    } finally {
      for (int k = 0; k < swaps.size(); k++) {
        Element[] swap = swaps.get(k);
        var old = new ArrayList<Element>();
        for (int i = swap[0].getNumChildren() - 1; i >= 0; i--) {
          var child = (Element) swap[0].getChildAt(i);
          String n = Xml.local(child);
          if ("gridSpan".equals(n) || "vMerge".equals(n) || "hMerge".equals(n)) continue;
          old.add(0, child);
          swap[0].removeChildAt(i);
        }
        for (Element e : old) swap[1].addChild(e);
        for (Element e : saved.get(k)) swap[0].addChild(e);
      }
    }
    return read.format(read.format.formatRevision(found[0], previous.format));
  }

  private static void collectChange(List<Element[]> out, Revision[] found, Element pr, String change, String props) {
    Element ch = pr == null ? null : Xml.child(pr, change);
    if (ch == null) return;
    if (found[0] == null)
      found[0] = new Revision(Xml.attr(ch, "id", "0"), "format", Xml.attr(ch, "author", ""), Xml.attr(ch, "date", ""));
    Element old = Xml.child(ch, props);
    if (old == null) {
      old = new Element("w:" + props);
      ch.addChild(old);
    }
    out.add(new Element[] {pr, old});
  }

  private static void rowInner(StringBuilder b, TableFormat f, int r) {
    if (r < f.headerRows) b.append("<w:tblHeader/>");
    if (f.minHeight(r) > 0)
      b.append("<w:trHeight w:val=\"")
          .append(Math.max(1, Math.round(f.minHeight(r) * 20)))
          .append("\" w:hRule=\"")
          .append(f.row(r).rule)
          .append("\"/>");
    if (!f.row(r).allowBreak) b.append("<w:cantSplit/>");
  }

  static void cellXml(StringBuilder b, TableFormat.Cell v, float width) {
    b.append("<w:tcW w:w=\"")
        .append(Math.round(width * 20))
        .append("\" w:type=\"dxa\"/>")
        .append("<w:shd w:val=\"clear\" w:fill=\"")
        .append(hex(v.background))
        .append("\"/>")
        .append("<w:vAlign w:val=\"")
        .append(v.vertical)
        .append("\"/><w:tcMar>");
    for (String side : new String[] {"top", "bottom", "left", "right"})
      b.append("<w:")
          .append(side)
          .append(" w:w=\"")
          .append(
              Math.round(
                  ("top".equals(side) || "bottom".equals(side)
                          ? v.verticalPadding
                          : v.horizontalPadding)
                      * 20))
          .append("\" w:type=\"dxa\"/>");
    b.append("</w:tcMar><w:tcBorders>");
    int[] masks = {TableFormat.Cell.TOP, TableFormat.Cell.BOTTOM, TableFormat.Cell.LEFT, TableFormat.Cell.RIGHT};
    String[] names = {"top", "bottom", "left", "right"};
    for (int i = 0; i < 4; i++)
      b.append("<w:")
          .append(names[i])
          .append(" w:val=\"")
          .append(v.side(masks[i]) ? "single" : "nil")
          .append("\" w:sz=\"")
          .append(Math.round(v.borderWidth * 8))
          .append("\" w:color=\"")
          .append(hex(v.borderColor))
          .append("\"/>");
    b.append("</w:tcBorders>");
  }

  static Table readDocx(Element element, Table table, Set<String> warnings) {
    var f = table.format;
    Element grid = Xml.child(element, "tblGrid"), props = Xml.child(element, "tblPr");
    int c = 0;
    if (grid != null)
      for (Element col : Xml.children(grid, "gridCol")) {
        if (c < f.columns()) {
          float width = number(col, "w", 20) / 20;
          if (width > 0) f = f.column(c, width);
        }
        c++;
      }
    var base = new TableFormat.Cell(0xffffff, 0xa6a6a6, 0, 6, 4, "top");
    if (props != null) {
      Element title = Xml.child(props, "tblCaption"),
          description = Xml.child(props, "tblDescription");
      f =
          f.altText(
              title == null ? "" : Xml.attr(title, "val", ""),
              description == null ? "" : Xml.attr(description, "val", ""));
      base = readCell(props, base, "tblBorders", "tblCellMar", warnings);
      Element bidi = Xml.child(props, "bidiVisual"), look = Xml.child(props, "tblLook");
      Element named = Xml.child(props, "tblStyle"), pos = Xml.child(props, "tblpPr");
      if (pos != null) f = f.withProp("float", readFloat(pos));
      if (named != null && !Xml.attr(named, "val", "").isEmpty()) f = f.withProp("style", Xml.attr(named, "val", ""));
      if (bidi != null && !"0".equals(Xml.attr(bidi, "val", "1")) && !"false".equals(Xml.attr(bidi, "val", "1")))
        f = f.withProp("rtl", "true");
      if (look != null) {
        int bits = 0;
        try {
          bits = Integer.parseInt(Xml.attr(look, "val", "0"), 16);
        } catch (NumberFormatException ignored) {
        }
        String[][] attrs = {
          {"headerRow", "firstRow", "0020"}, {"lastRow", "lastRow", "0040"},
          {"firstColumn", "firstColumn", "0080"}, {"lastColumn", "lastColumn", "0100"},
          {"bandedRows", "noHBand", "0200"}, {"bandedColumns", "noVBand", "0400"}
        };
        var on = new StringBuilder();
        for (String[] a : attrs) {
          String v = Xml.attr(look, a[1], "");
          boolean set =
              v.isEmpty() ? (bits & Integer.parseInt(a[2], 16)) != 0 : "1".equals(v) || "true".equals(v);
          if (a[1].startsWith("no")) set = !set;
          if (set) on.append(on.length() == 0 ? "" : ",").append(a[0]);
        }
        f = f.withProp("look", on.toString());
      }
      for (Element prop : Xml.children(props, null)) {
        String name = Xml.local(prop);
        if (!"tblW".equals(name)
            && !"tblBorders".equals(name)
            && !"tblCellMar".equals(name)
            && !"tblLayout".equals(name)
            && !"jc".equals(name)
            && !"tblInd".equals(name)
            && !"tblCaption".equals(name)
            && !"tblDescription".equals(name)
            && !"tblLook".equals(name)
            && !"bidiVisual".equals(name)
            && !"tblStyle".equals(name)
            && !"tblpPr".equals(name)
            && !"tblOverlap".equals(name)
            && !"tblPrChange".equals(name))
          warnings.add("Table property requires conversion: " + name);
      }
      Element width = Xml.child(props, "tblW");
      Element align = Xml.child(props, "jc"),
          indent = Xml.child(props, "tblInd"),
          layout = Xml.child(props, "tblLayout");
      String unit = width == null ? "auto" : Xml.attr(width, "type", "auto");
      float value = width == null ? 0 : number(width, "w", 0);
      if ("pct".equals(unit)) {
        unit = "percent";
        value /= 50;
      } else if ("dxa".equals(unit)) {
        unit = "points";
        value /= 20;
      } else {
        unit = "auto";
        value = 0;
      }
      if (value <= 0) {
        unit = "auto";
        value = 0;
      }
      if ("percent".equals(unit) && value > 100) {
        warnings.add("Table percentages above 100 are limited to the page width");
        value = 100;
      }
      String alignment = align == null ? "left" : Xml.attr(align, "val", "left");
      if ("start".equals(alignment)) alignment = "left";
      if ("end".equals(alignment)) alignment = "right";
      if (!("left".equals(alignment) || "center".equals(alignment) || "right".equals(alignment))) {
        warnings.add("Unsupported table alignment: " + alignment);
        alignment = "left";
      }
      float offset = indent == null ? 0 : number(indent, "w", 0) / 20;
      if (indent != null && !"dxa".equals(Xml.attr(indent, "type", "dxa"))) {
        warnings.add("Non-point table indentation requires conversion");
        offset = 0;
      }
      boolean fixed = layout != null && "fixed".equals(Xml.attr(layout, "type", "autofit"));
      f =
          f.sizing(
              new TableFormat.Sizing(
                  unit,
                  Math.min(10000, value),
                  alignment,
                  Math.max(-1584, Math.min(1584, offset)),
                  fixed));
    }
    int r = 0, headers = 0;
    for (Element row : Xml.children(element, "tr")) {
      if (r >= f.rows()) break;
      Element rp = Xml.child(row, "trPr"), h = rp == null ? null : Xml.child(rp, "trHeight");
      if (h != null) {
        String rule = Xml.attr(h, "hRule", "atLeast");
        if (!("exact".equals(rule) || "auto".equals(rule) || "atLeast".equals(rule))) {
          warnings.add("Unknown table row height rule: " + rule);
          rule = "atLeast";
        }
        float height = Math.max(0, Math.min(10000, number(h, "val", 0) / 20));
        if ("exact".equals(rule) && height == 0) rule = "auto";
        f = f.row(r, new TableFormat.Row(height, rule, true));
      }
      if (rp != null) {
        Element cantSplit = Xml.child(rp, "cantSplit");
        if (cantSplit != null) {
          String on = Xml.attr(cantSplit, "val", "true");
          f =
              f.row(
                  r,
                  new TableFormat.Row(
                      f.row(r).height,
                      f.row(r).rule,
                      "0".equals(on) || "false".equals(on) || "off".equals(on)));
        }
        Element header = Xml.child(rp, "tblHeader");
        if (header != null
            && !"0".equals(Xml.attr(header, "val", "1"))
            && !"false".equals(Xml.attr(header, "val", "1"))) {
          if (headers == r) headers++;
          else warnings.add("Non-contiguous repeated table headers require conversion");
        }
        for (Element property : Xml.children(rp, null))
          if (!"tblHeader".equals(Xml.local(property))
              && !"trHeight".equals(Xml.local(property))
              && !"cantSplit".equals(Xml.local(property))
              && !"trPrChange".equals(Xml.local(property)))
            warnings.add("Table row property: " + Xml.local(property));
      }
      c = 0;
      for (Element cell : Xml.children(row, "tc")) {
        Element cp = Xml.child(cell, "tcPr"), span = cp == null ? null : Xml.child(cp, "gridSpan");
        int count = span == null ? 1 : Integer.parseInt(Xml.attr(span, "val", "1"));
        TableFormat.Cell value =
            cp == null ? base : readCell(cp, base, "tcBorders", "tcMar", warnings);
        Element flow = cp == null ? null : Xml.child(cp, "textDirection");
        if (flow != null && c < f.columns()) {
          String dir = Xml.attr(flow, "val", "lrTb");
          if ("tbRl".equals(dir) || "btLr".equals(dir)) f = f.direction(r, c, dir);
          else if (!"lrTb".equals(dir)) warnings.add("Cell text direction " + dir + " shown horizontally");
        }
        for (int i = 0; i < count && c < f.columns(); i++) f = f.cell(r, c++, value);
      }
      r++;
    }
    for (Table.Merge m : table.merges)
      if (m.row < headers && m.row + m.rows > headers) {
        warnings.add("Merged cell crosses repeated table header boundary; headers not repeated");
        headers = 0;
        break;
      }
    return table.format(f.headerRows(headers));
  }

  private static TableFormat.Cell readCell(
      Element props,
      TableFormat.Cell base,
      String borderName,
      String marginName,
      Set<String> warnings) {
    Element shade = Xml.child(props, "shd"),
        align = Xml.child(props, "vAlign"),
        borders = Xml.child(props, borderName),
        margins = Xml.child(props, marginName);
    int background = shade == null ? base.background : color(shade, "fill", base.background),
        border = base.borderColor;
    int sides = base.sides;
    float stroke = base.borderWidth,
        horizontal = base.horizontalPadding,
        vertical = base.verticalPadding;
    if (shade != null
        && !"clear".equals(Xml.attr(shade, "val", "clear"))
        && !"nil".equals(Xml.attr(shade, "val", "clear")))
      warnings.add("Patterned table shading becomes a solid fill");
    if (shade != null
        && (shade.getAttribute("w:themeFill") != null || shade.getAttribute("themeFill") != null))
      warnings.add("Theme table shading uses its fallback color");
    if (borders != null) {
      String first = null;
      boolean any = false;
      for (Element edge : Xml.children(borders, null)) {
        String type = Xml.attr(edge, "val", "single"), name = Xml.local(edge);
        float width = "nil".equals(type) || "none".equals(type) ? 0 : number(edge, "sz", 4) / 8;
        int color = color(edge, "color", 0);
        int mask =
            "top".equals(name)
                ? TableFormat.Cell.TOP
                : "bottom".equals(name)
                    ? TableFormat.Cell.BOTTOM
                    : "left".equals(name) || "start".equals(name)
                        ? TableFormat.Cell.LEFT
                        : "right".equals(name) || "end".equals(name) ? TableFormat.Cell.RIGHT : 0;
        if (mask != 0) {
          if (!any) sides = 0;
          any = true;
          // Absent sides become a side mask; present sides share one color and width.
          if (width == 0) continue;
          sides |= mask;
        } else if (width == 0) continue;
        var signature = width + ":" + color;
        if (first == null) {
          first = signature;
          stroke = width;
          border = color;
        } else if (!first.equals(signature))
          warnings.add("Asymmetric table borders become uniform cell borders");
        if (!("single".equals(type) || "nil".equals(type) || "none".equals(type)))
          warnings.add("Decorative table borders become solid borders");
      }
    }
    if (margins != null) {
      Element left = Xml.child(margins, "left"),
          right = Xml.child(margins, "right"),
          top = Xml.child(margins, "top"),
          bottom = Xml.child(margins, "bottom");
      if (left == null) left = Xml.child(margins, "start");
      if (right == null) right = Xml.child(margins, "end");
      if (left != null) horizontal = number(left, "w", 120) / 20;
      else if (right != null) horizontal = number(right, "w", 120) / 20;
      if (top != null) vertical = number(top, "w", 80) / 20;
      else if (bottom != null) vertical = number(bottom, "w", 80) / 20;
      if (right != null && number(right, "w", 120) / 20 != horizontal
          || bottom != null && number(bottom, "w", 80) / 20 != vertical)
        warnings.add("Asymmetric cell margins become symmetric padding");
    }
    String valign = align == null ? base.vertical : Xml.attr(align, "val", "top");
    if (!("top".equals(valign) || "center".equals(valign) || "bottom".equals(valign))) {
      warnings.add("Unsupported vertical cell alignment: " + valign);
      valign = "top";
    }
    if (sides == 0) stroke = 0;
    return new TableFormat.Cell(
        background, border, stroke, horizontal, vertical, valign, sides == 0 ? TableFormat.Cell.ALL : sides);
  }

  static String css(TableFormat.Cell cell) {
    return "background:#"
        + hex(cell.background)
        + ";border:"
        + cell.borderWidth
        + "pt solid #"
        + hex(cell.borderColor)
        + ";padding:"
        + cell.verticalPadding
        + "pt "
        + cell.horizontalPadding
        + "pt;vertical-align:"
        + ("center".equals(cell.vertical) ? "middle" : cell.vertical);
  }

  private static String hex(int value) {
    return TextUtil.padded(value, 16, 6);
  }

  private static float number(Element e, String key, float fallback) {
    return Xml.number(Xml.attr(e, key, Float.toString(fallback)));
  }

  private static int color(Element e, String key, int fallback) {
    String value = Xml.attr(e, key, "");
    return value.length() == 6 ? Integer.parseInt(value, 16) : fallback;
  }
}
