package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.util.*;

/**
 * DrawingML charts (c:chartSpace) and SmartArt diagrams (dgm data model plus the dsp drawing Word
 * saves beside it) as chart and smartArt drawings. Each import records the source relationship
 * and a key of the editable content, so an unedited object is saved by referencing its original
 * parts (lossless) while an edited chart is regenerated as a new chart part and an edited SmartArt
 * graphic is saved as its drawn shapes.
 */
final class DocxCharts {
  static final String CHART = "http://schemas.openxmlformats.org/drawingml/2006/chart";
  static final String DIAGRAM = "http://schemas.openxmlformats.org/drawingml/2006/diagram";
  static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
  static final int MAX_SERIES = 29, MAX_ROWS = 199, MAX_NODES = 100, MAX_SHAPES = 200;
  /** Separates paragraphs inside a fallback shape's text (the shape list itself is line based). */
  static final char BREAK = '\u001f';

  private DocxCharts() {}

  // ---------------------------------------------------------------- keys

  private static String hash(String s) {
    return Integer.toHexString(s.hashCode()) + ":" + s.length();
  }

  /** The editable content of a chart; it changes when the chart is edited. */
  static String key(Drawing d) {
    return hash(d.get("chartType", "column") + "|" + d.get("data", DrawingOps.SAMPLE_CHART) + "|"
        + d.get("title", "Chart Title") + "|" + d.get("legend", "true") + "|" + d.flag("dataLabels"));
  }

  /** The editable content of a SmartArt graphic. */
  static String smartKey(Drawing d) {
    return hash(d.get("layout", "list") + "|" + d.get("nodes", "") + "|" + d.get("colors", "accent1"));
  }

  // ---------------------------------------------------------------- chart import

  private static Element find(Element e, String local, int depth) {
    if (local.equals(Xml.local(e))) return e;
    if (depth > 12) return null;
    for (Element c : Xml.children(e, null)) {
      Element f = find(c, local, depth + 1);
      if (f != null) return f;
    }
    return null;
  }

  private static String clean(String s) {
    var b = new StringBuilder();
    for (int i = 0; i < s.length() && b.length() < 120; i++) {
      char c = s.charAt(i);
      b.append(c == ',' || c == '\n' || c == '\r' || c == '\t' ? ' ' : c);
    }
    return b.toString().trim();
  }

  /** The cached points of a c:cat/c:val/c:tx/c:xVal/c:yVal, indexed by c:pt idx. */
  private static String[] points(Element source, int cap) {
    if (source == null) return new String[0];
    Element cache = null;
    for (String name : new String[] {"strCache", "numCache", "strLit", "numLit", "lvl"}) {
      cache = find(source, name, 0);
      if (cache != null) break;
    }
    if (cache == null) {
      Element v = Xml.child(source, "v");
      return v == null ? new String[0] : new String[] {Xml.text(v)};
    }
    Element count = Xml.child(cache, "ptCount");
    int n = count == null ? 0 : Xml.integer(Xml.attr(count, "val", "0"), 0);
    var pts = Xml.children(cache, "pt");
    for (Element p : pts) n = Math.max(n, Xml.integer(Xml.attr(p, "idx", "0"), 0) + 1);
    String[] out = new String[Math.max(0, Math.min(n, cap))];
    for (Element p : pts) {
      int i = Xml.integer(Xml.attr(p, "idx", "-1"), -1);
      Element v = Xml.child(p, "v");
      if (i >= 0 && i < out.length && v != null) out[i] = Xml.text(v);
    }
    return out;
  }

  private static String number(String s) {
    if (s == null) return "";
    try {
      double v = Double.parseDouble(s.trim());
      if (Double.isNaN(v) || Double.isInfinite(v)) return "";
      return Math.abs(v) < 1e15 && v == (double) (long) v ? String.valueOf((long) v) : String.valueOf(v);
    } catch (NumberFormatException e) {
      return "";
    }
  }

  private static boolean on(Element e) {
    String v = Xml.attr(e, "val", "1");
    return "1".equals(v) || "true".equals(v);
  }

  /** A chart drawing from a c:chartSpace, or null when it has no supported plot. */
  static Drawing chart(Element space, Set<String> warnings) {
    Element chart = Xml.child(space, "chart");
    Element plot = chart == null ? null : Xml.child(chart, "plotArea");
    if (plot == null) return null;
    Element group = null;
    String type = null;
    int groups = 0;
    for (Element e : Xml.children(plot, null)) {
      String k = Xml.local(e), t = null;
      if ("barChart".equals(k) || "bar3DChart".equals(k)) {
        Element dir = Xml.child(e, "barDir");
        t = dir != null && "bar".equals(Xml.attr(dir, "val", "col")) ? "bar" : "column";
      } else if ("lineChart".equals(k) || "line3DChart".equals(k) || "stockChart".equals(k)
          || "radarChart".equals(k)) t = "line";
      else if ("pieChart".equals(k) || "pie3DChart".equals(k) || "ofPieChart".equals(k)) t = "pie";
      else if ("doughnutChart".equals(k)) t = "doughnut";
      else if ("areaChart".equals(k) || "area3DChart".equals(k) || "surfaceChart".equals(k)
          || "surface3DChart".equals(k)) t = "area";
      else if ("scatterChart".equals(k) || "bubbleChart".equals(k)) t = "scatter";
      if (t == null) continue;
      groups++;
      if (group == null) {
        group = e;
        type = t;
        if (!k.startsWith(t) && !("barChart".equals(k) || "bar3DChart".equals(k)))
          warnings.add("Chart drawn as the nearest supported type");
      }
    }
    if (group == null) return null;
    if (groups > 1) warnings.add("Combination chart drawn with its first chart type");
    boolean scatter = "scatter".equals(type);
    var sers = Xml.children(group, "ser");
    if ("pie".equals(type) || "doughnut".equals(type)) sers = sers.subList(0, Math.min(1, sers.size()));
    if (sers.size() > MAX_SERIES) {
      sers = sers.subList(0, MAX_SERIES);
      warnings.add("Chart series beyond " + MAX_SERIES + " are not drawn");
    }
    if (sers.isEmpty()) return null;
    String[] cats = points(Xml.child(sers.get(0), scatter ? "xVal" : "cat"), MAX_ROWS);
    var values = new ArrayList<String[]>();
    var names = new ArrayList<String>();
    int rows = cats.length;
    boolean labels = false;
    Element groupLabels = Xml.child(group, "dLbls");
    if (groupLabels != null && Xml.child(groupLabels, "showVal") != null)
      labels = on(Xml.child(groupLabels, "showVal"));
    for (int i = 0; i < sers.size(); i++) {
      Element s = sers.get(i);
      String[] name = points(Xml.child(s, "tx"), 1);
      names.add(clean(name.length > 0 && name[0] != null ? name[0] : "Series " + (i + 1)));
      String[] v = points(Xml.child(s, scatter ? "yVal" : "val"), MAX_ROWS);
      values.add(v);
      rows = Math.max(rows, v.length);
      Element dl = Xml.child(s, "dLbls");
      if (dl != null && Xml.child(dl, "showVal") != null && on(Xml.child(dl, "showVal"))) labels = true;
    }
    if (rows > MAX_ROWS) rows = MAX_ROWS;
    var data = new StringBuilder(scatter ? "X" : "Category");
    for (String n : names) data.append(',').append(n.isEmpty() ? " " : n);
    for (int r = 0; r < rows; r++) {
      String c = r < cats.length && cats[r] != null ? clean(cats[r]) : "";
      if (scatter) c = r < cats.length ? number(cats[r]) : String.valueOf(r + 1);
      if (c.isEmpty()) c = scatter ? String.valueOf(r + 1) : String.valueOf(r + 1);
      data.append('\n').append(c);
      for (String[] v : values) data.append(',').append(r < v.length ? number(v[r]) : "");
    }
    var d = Drawing.of("chart").with("chartType", type).with("data", data.toString());
    String title = "";
    Element t = Xml.child(chart, "title");
    Element deleted = Xml.child(chart, "autoTitleDeleted");
    if (t != null) {
      var b = new StringBuilder();
      text(t, b, 0);
      title = clean(b.toString());
      if (title.isEmpty() && sers.size() == 1) title = names.get(0);
    } else if (sers.size() == 1 && (deleted == null || !on(deleted)) && ("pie".equals(type) || "doughnut".equals(type)))
      title = names.get(0);
    d = d.with("title", title).with("titleSet", "true");
    d = d.with("legend", String.valueOf(Xml.child(chart, "legend") != null));
    if (labels) d = d.with("dataLabels", "true");
    return d;
  }

  private static void text(Element e, StringBuilder out, int depth) {
    if ("t".equals(Xml.local(e))) out.append(Xml.text(e));
    else if ("v".equals(Xml.local(e))) out.append(Xml.text(e));
    else if (depth < 12) {
      if ("p".equals(Xml.local(e)) && out.length() > 0) out.append(' ');
      for (Element c : Xml.children(e, null)) text(c, out, depth + 1);
    }
  }

  // ---------------------------------------------------------------- chart export

  private static void lit(StringBuilder b, String tag, List<String> v, boolean numeric) {
    b.append("<c:").append(tag).append('>').append(numeric ? "<c:numLit><c:formatCode>General</c:formatCode>" : "<c:strLit>")
        .append("<c:ptCount val=\"").append(v.size()).append("\"/>");
    for (int i = 0; i < v.size(); i++) {
      String s = v.get(i);
      if (numeric) s = number(s);
      if (s.isEmpty()) continue;
      b.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(Xml.escape(s)).append("</c:v></c:pt>");
    }
    b.append(numeric ? "</c:numLit>" : "</c:strLit>").append("</c:").append(tag).append('>');
  }

  /** A complete chart part for a chart drawing. */
  static String chartXml(Drawing d) {
    String type = d.get("chartType", "column");
    var rows = DrawingOps.table(d.get("data", DrawingOps.SAMPLE_CHART));
    if (rows.isEmpty()) rows.add(new String[] {"Category", "Series 1"});
    String[] header = rows.get(0);
    boolean pie = "pie".equals(type) || "doughnut".equals(type), scatter = "scatter".equals(type);
    int series = pie ? Math.min(1, header.length - 1) : header.length - 1;
    var cats = new ArrayList<String>();
    for (int r = 1; r < rows.size(); r++) cats.add(rows.get(r)[0]);
    boolean labels = d.flag("dataLabels");
    var b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
    b.append("<c:chartSpace xmlns:c=\"").append(CHART).append("\" xmlns:a=\"").append(A)
        .append("\" xmlns:r=\"").append(R).append("\"><c:roundedCorners val=\"0\"/><c:chart>");
    String title = d.get("title", "Chart Title");
    if (title.isEmpty()) b.append("<c:autoTitleDeleted val=\"1\"/>");
    else
      b.append("<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>").append(Xml.escape(title))
          .append("</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title><c:autoTitleDeleted val=\"0\"/>");
    b.append("<c:plotArea><c:layout/>");
    String tag = "column".equals(type) || "bar".equals(type) ? "barChart" : type + "Chart";
    b.append("<c:").append(tag).append('>');
    if ("barChart".equals(tag))
      b.append("<c:barDir val=\"").append("bar".equals(type) ? "bar" : "col").append("\"/><c:grouping val=\"clustered\"/>");
    else if ("line".equals(type) || "area".equals(type)) b.append("<c:grouping val=\"standard\"/>");
    else if (scatter) b.append("<c:scatterStyle val=\"lineMarker\"/>");
    b.append("<c:varyColors val=\"").append(pie ? 1 : 0).append("\"/>");
    for (int s = 1; s <= series; s++) {
      int color = DrawingOps.PALETTE[(s - 1) % DrawingOps.PALETTE.length];
      b.append("<c:ser><c:idx val=\"").append(s - 1).append("\"/><c:order val=\"").append(s - 1)
          .append("\"/><c:tx><c:v>").append(Xml.escape(header[s])).append("</c:v></c:tx>");
      if (!pie) {
        b.append("<c:spPr>");
        if (scatter || "line".equals(type))
          b.append("<a:ln w=\"28575\"><a:solidFill><a:srgbClr val=\"").append(hex(color)).append("\"/></a:solidFill></a:ln>");
        else b.append("<a:solidFill><a:srgbClr val=\"").append(hex(color)).append("\"/></a:solidFill>");
        b.append("</c:spPr>");
      }
      if ("barChart".equals(tag)) b.append("<c:invertIfNegative val=\"0\"/>");
      if (scatter) b.append("<c:marker><c:symbol val=\"none\"/></c:marker>");
      var vals = new ArrayList<String>();
      for (int r = 1; r < rows.size(); r++) vals.add(s < rows.get(r).length ? rows.get(r)[s] : "");
      lit(b, scatter ? "xVal" : "cat", cats, scatter);
      lit(b, scatter ? "yVal" : "val", vals, true);
      if ("line".equals(type) || scatter) b.append("<c:smooth val=\"0\"/>");
      b.append("</c:ser>");
    }
    b.append("<c:dLbls><c:showLegendKey val=\"0\"/><c:showVal val=\"").append(labels ? 1 : 0)
        .append("\"/><c:showCatName val=\"0\"/><c:showSerName val=\"0\"/><c:showPercent val=\"0\"/>")
        .append("<c:showBubbleSize val=\"0\"/></c:dLbls>");
    if ("barChart".equals(tag)) b.append("<c:gapWidth val=\"150\"/>");
    if ("line".equals(type)) b.append("<c:marker val=\"1\"/>");
    if (pie) b.append("<c:firstSliceAng val=\"0\"/>");
    if ("doughnut".equals(type)) b.append("<c:holeSize val=\"50\"/>");
    if (!pie) b.append("<c:axId val=\"111\"/><c:axId val=\"222\"/>");
    b.append("</c:").append(tag).append('>');
    if (!pie) {
      boolean bar = "bar".equals(type);
      if (scatter) axis(b, "valAx", 111, 222, "b", false);
      else axis(b, "catAx", 111, 222, bar ? "l" : "b", false);
      axis(b, "valAx", 222, 111, bar ? "b" : "l", true);
    }
    b.append("</c:plotArea>");
    if (!"false".equals(d.get("legend", "true")))
      b.append("<c:legend><c:legendPos val=\"b\"/><c:overlay val=\"0\"/></c:legend>");
    b.append("<c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart></c:chartSpace>");
    return b.toString();
  }

  private static void axis(StringBuilder b, String tag, int id, int cross, String pos, boolean grid) {
    b.append("<c:").append(tag).append("><c:axId val=\"").append(id)
        .append("\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"")
        .append(pos).append("\"/>");
    if (grid) b.append("<c:majorGridlines/>");
    b.append("<c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"").append(cross).append("\"/><c:crosses val=\"autoZero\"/>");
    b.append("</c:").append(tag).append('>');
  }

  static String hex(int rgb) {
    return Integer.toHexString(0x1000000 | (rgb & 0xffffff)).substring(1);
  }

  // ---------------------------------------------------------------- SmartArt import

  /** The text outline of a diagram data model: one node per line, tab-indented by level. */
  static String nodes(Element model) {
    Element ptLst = Xml.child(model, "ptLst"), cxnLst = Xml.child(model, "cxnLst");
    if (ptLst == null) return "";
    var text = new HashMap<String, String>();
    String doc = null;
    for (Element pt : Xml.children(ptLst, "pt")) {
      String type = Xml.attr(pt, "type", "node"), id = Xml.attr(pt, "modelId", "");
      if ("doc".equals(type)) doc = id;
      else if ("node".equals(type) && text.size() < 4 * MAX_NODES) {
        var b = new StringBuilder();
        Element t = Xml.child(pt, "t");
        if (t != null) text(t, b, 0);
        text.put(id, clean(b.toString()));
      }
    }
    var kids = new HashMap<String, List<String[]>>();
    if (cxnLst != null)
      for (Element c : Xml.children(cxnLst, "cxn")) {
        if (!"parOf".equals(Xml.attr(c, "type", "parOf"))) continue;
        kids.computeIfAbsent(Xml.attr(c, "srcId", ""), k -> new ArrayList<>())
            .add(new String[] {Xml.attr(c, "destId", ""), Xml.attr(c, "srcOrd", "0")});
      }
    for (List<String[]> l : kids.values())
      l.sort((x, y) -> Integer.compare(Xml.integer(x[1], 0), Xml.integer(y[1], 0)));
    var out = new StringBuilder();
    var seen = new HashSet<String>();
    int[] count = {0};
    if (doc != null) walk(doc, 0, kids, text, seen, out, count);
    if (count[0] == 0)
      for (String v : text.values()) // no hierarchy: every node at the top level
        if (!v.isEmpty() && count[0]++ < MAX_NODES) out.append(out.length() > 0 ? "\n" : "").append(v);
    return out.toString();
  }

  private static void walk(String id, int level, Map<String, List<String[]>> kids, Map<String, String> text,
      Set<String> seen, StringBuilder out, int[] count) {
    if (!seen.add(id) || count[0] >= MAX_NODES) return;
    String t = text.get(id);
    int next = level;
    if (t != null) {
      next = level + 1;
      if (out.length() > 0) out.append('\n');
      for (int i = 1; i < Math.min(level, 9); i++) out.append('\t');
      out.append(t.isEmpty() ? " " : t);
      count[0]++;
    }
    var l = kids.get(id);
    if (l != null && level < 9) for (String[] k : l) walk(k[0], Math.max(next, 1), kids, text, seen, out, count);
  }

  /** Our nearest layout for a diagram layout id such as urn:...layout/process1. */
  static String layout(String id) {
    String s = id.toLowerCase();
    int slash = s.lastIndexOf('/');
    if (slash >= 0) s = s.substring(slash + 1);
    if (s.contains("cycle") || s.contains("radial") || s.contains("venn")) return "cycle";
    if (s.contains("hierarchy") || s.contains("orgchart")) return "hierarchy";
    if (s.contains("pyramid")) return "pyramid";
    if (s.contains("matrix")) return "matrix";
    if (s.contains("process") || s.contains("chevron") || s.contains("arrow")) return "process";
    return "list";
  }

  private static int scheme(String v) {
    if (v.startsWith("accent") && v.length() == 7 && v.charAt(6) >= '1' && v.charAt(6) <= '6')
      return DrawingOps.PALETTE[v.charAt(6) - '1'];
    if ("lt1".equals(v) || "bg1".equals(v)) return 0xffffff;
    if ("lt2".equals(v) || "bg2".equals(v)) return 0xe7e6e6;
    if ("dk2".equals(v) || "tx2".equals(v)) return 0x44546a;
    return 0;
  }

  /** The color of a fill element (solidFill, ln, fillRef...), or -1. */
  private static int color(Element e) {
    if (e == null || Xml.child(e, "noFill") != null) return -1;
    Element solid = "solidFill".equals(Xml.local(e)) ? e : Xml.child(e, "solidFill");
    Element src = solid != null ? solid : e;
    Element rgb = Xml.child(src, "srgbClr"), sch = Xml.child(src, "schemeClr");
    if (rgb != null) {
      try {
        return Integer.parseInt(Xml.attr(rgb, "val", "000000"), 16) & 0xffffff;
      } catch (NumberFormatException ex) {
        return 0;
      }
    }
    if (sch != null) return scheme(Xml.attr(sch, "val", ""));
    return -1;
  }

  private static long emu(Element xfrm, String child, String attr) {
    Element c = xfrm == null ? null : Xml.child(xfrm, child);
    if (c == null) return 0;
    try {
      return Long.parseLong(Xml.attr(c, attr, "0").trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static int frac(long v, long total) {
    if (total <= 0) return 0;
    return (int) Math.max(-20000, Math.min(20000, v * 10000 / total));
  }

  /**
   * The shapes of a dsp:drawing part as lines of "prst,x,y,w,h,fill,line,textColor,tx,ty,tw,th,text"
   * with positions in 1/10000 of the frame; text paragraphs are separated by {@link #BREAK}.
   */
  static String shapes(Element drawing, long cx, long cy) {
    Element tree = find(drawing, "spTree", 0);
    if (tree == null) return "";
    var sps = new ArrayList<Element>();
    collectShapes(tree, sps, 0);
    var out = new StringBuilder();
    for (Element sp : sps) {
      Element pr = Xml.child(sp, "spPr"), style = Xml.child(sp, "style");
      Element xfrm = pr == null ? null : Xml.child(pr, "xfrm");
      if (xfrm == null) continue;
      long x = emu(xfrm, "off", "x"), y = emu(xfrm, "off", "y"), w = emu(xfrm, "ext", "cx"), h = emu(xfrm, "ext", "cy");
      Element geom = pr == null ? null : Xml.child(pr, "prstGeom");
      String prst = geom == null ? "rect" : Xml.attr(geom, "prst", "rect").replace(",", "");
      int fill = pr != null && (Xml.child(pr, "solidFill") != null || Xml.child(pr, "noFill") != null)
          ? color(Xml.child(pr, "solidFill") != null ? Xml.child(pr, "solidFill") : pr)
          : style == null ? -1 : color(Xml.child(style, "fillRef"));
      Element ln = pr == null ? null : Xml.child(pr, "ln");
      int line = ln != null ? color(ln) : style == null ? -1 : color(Xml.child(style, "lnRef"));
      int textColor = style == null ? 0 : Math.max(0, color(Xml.child(style, "fontRef")));
      Element tx = Xml.child(sp, "txXfrm");
      long tx0 = tx == null ? x : emu(tx, "off", "x"), ty0 = tx == null ? y : emu(tx, "off", "y");
      long tw = tx == null ? w : emu(tx, "ext", "cx"), th = tx == null ? h : emu(tx, "ext", "cy");
      var text = new StringBuilder();
      Element body = Xml.child(sp, "txBody");
      if (body != null)
        for (Element p : Xml.children(body, "p")) {
          var pb = new StringBuilder();
          text(p, pb, 0);
          if (text.length() > 0) text.append(BREAK);
          text.append(pb);
          Element rPr = find(p, "rPr", 0);
          if (rPr != null && Xml.child(rPr, "solidFill") != null) textColor = Math.max(0, color(Xml.child(rPr, "solidFill")));
          if (text.length() > 400) break;
        }
      String t = text.toString().replace('\n', ' ').replace('\r', ' ');
      if (t.length() > 400) t = t.substring(0, 400);
      out.append(out.length() > 0 ? "\n" : "").append(prst).append(',').append(frac(x, cx)).append(',')
          .append(frac(y, cy)).append(',').append(frac(w, cx)).append(',').append(frac(h, cy)).append(',')
          .append(fill).append(',').append(line).append(',').append(textColor).append(',')
          .append(frac(tx0, cx)).append(',').append(frac(ty0, cy)).append(',').append(frac(tw, cx)).append(',')
          .append(frac(th, cy)).append(',').append(t);
    }
    return out.toString();
  }

  private static void collectShapes(Element e, List<Element> out, int depth) {
    for (Element c : Xml.children(e, null)) {
      if (out.size() >= MAX_SHAPES) return;
      String k = Xml.local(c);
      if ("sp".equals(k)) out.add(c);
      else if ("grpSp".equals(k) && depth < 8) collectShapes(c, out, depth + 1);
    }
  }

  /** A SmartArt drawing from its data model, layout id and optional dsp drawing. */
  static Drawing smartArt(Element model, String layoutId, Element drawing, long cx, long cy) {
    String nodes = model == null ? "" : nodes(model);
    if (nodes.isEmpty()) nodes = "Text";
    if (layoutId.isEmpty() && model != null) {
      Element ptLst = Xml.child(model, "ptLst");
      if (ptLst != null)
        for (Element pt : Xml.children(ptLst, "pt"))
          if ("doc".equals(Xml.attr(pt, "type", ""))) {
            Element pr = Xml.child(pt, "prSet");
            if (pr != null) layoutId = Xml.attr(pr, "loTypeId", "");
          }
    }
    var d = Drawing.of("smartArt").with("layout", layout(layoutId)).with("nodes", nodes);
    if (drawing != null) {
      String s = shapes(drawing, cx, cy);
      if (!s.isEmpty()) d = d.with("shapes", s);
    }
    return d;
  }
}
