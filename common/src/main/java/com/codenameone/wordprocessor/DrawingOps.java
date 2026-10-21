package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Turns a {@link Drawing} into renderer-neutral primitives in points, relative to the top-left of
 * its frame. The canvas and PDF export both paint these, so every drawing kind looks the same on
 * screen and on paper.
 */
public final class DrawingOps {
  private DrawingOps() {}

  public static final List<String> SHAPES =
      Collections.unmodifiableList(
          Arrays.asList(
              "rect", "roundRect", "ellipse", "triangle", "rightTriangle", "diamond", "pentagon",
              "hexagon", "octagon", "star", "rightArrow", "leftArrow", "upArrow", "downArrow",
              "line", "arrowLine", "plus", "heart", "callout", "parallelogram", "trapezoid"));

  public static final List<String> CHARTS =
      Collections.unmodifiableList(Arrays.asList("column", "bar", "line", "pie", "area", "doughnut", "scatter"));

  public static final List<String> SMART_ART =
      Collections.unmodifiableList(
          Arrays.asList("list", "process", "cycle", "hierarchy", "pyramid", "radial", "matrix", "venn"));

  /** A layout id as stored: Wordcraft's "basicList" (any case) is our "list"; null when unknown. */
  public static String smartLayout(String id) {
    String t = id == null ? "" : id.trim();
    if (t.equalsIgnoreCase("basicList")) return "list";
    for (String l : SMART_ART) if (l.equalsIgnoreCase(t)) return l;
    return null;
  }

  public static final List<String> ICONS =
      Collections.unmodifiableList(
          Arrays.asList(
              "check", "cross", "star", "heart", "warning", "info", "home", "mail", "person",
              "arrow", "lightbulb", "flag"));

  public static final List<String> WORD_ART =
      Collections.unmodifiableList(Arrays.asList("none", "arch", "wave", "slant", "inflate"));

  static final int[] PALETTE = {
    0x4472c4, 0xed7d31, 0xa5a5a5, 0xffc000, 0x5b9bd5, 0x70ad47, 0x264478, 0x9e480e
  };

  /** A filled and/or stroked polygon or polyline. */
  public record Path(
      float[] points, boolean closed, int fill, int stroke, float width, int alpha) {}

  /** One line of text placed in a box; renderers align it with real font metrics. */
  public record Text(
      String text, float x, float y, float width, float size, int color, boolean bold,
      boolean italic, String align, int outline, int rotation) {}

  public static final class Ops {
    public final List<Path> paths = new ArrayList<>();
    public final List<Text> texts = new ArrayList<>();
    /**
     * SmartArt shapes as {prst, x, y, w, h, fill, line, textColor, text} in points, recorded beside
     * the paths so the DOCX writer can save them as the diagram's cached dsp drawing.
     */
    public final List<Object[]> shapes = new ArrayList<>();

    void shape(String prst, float x, float y, float w, float h, int fill, int line, int textColor, String text) {
      shapes.add(new Object[] {prst, x, y, w, h, fill, line, textColor, text});
    }

    /**
     * A straight connector drawn and recorded as a "line" shape (flipped when it runs up-right),
     * so the cached SmartArt drawing carries the hierarchy and spoke lines as Wordcraft's does.
     */
    void connector(float x0, float y0, float x1, float y1, int stroke, float width) {
      line(new float[] {x0, y0, x1, y1}, stroke, width, 255);
      shapes.add(new Object[] {"line", Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0), -1,
          stroke, -1, "", (x1 - x0) * (y1 - y0) < 0});
    }

    void fill(float[] pts, int fill, int stroke, float width) {
      paths.add(new Path(pts, true, fill, stroke, width, 255));
    }

    void line(float[] pts, int stroke, float width, int alpha) {
      paths.add(new Path(pts, false, -1, stroke, width, alpha));
    }

    void text(String s, float x, float y, float w, float size, int color, boolean bold, String align) {
      texts.add(new Text(s, x, y, w, size, color, bold, false, align, -1, 0));
    }
  }

  public static Ops build(Drawing d, float w, float h) {
    return build(d, w, h, null);
  }

  /** As {@link #build(Drawing, float, float)}, measuring equation glyphs with {@code metrics}. */
  public static Ops build(Drawing d, float w, float h, LayoutEngine.Metrics metrics) {
    var ops = new Ops();
    if (d.flag("hidden")) return ops;
    var k = d.kind;
    if ("shape".equals(k)) shape(ops, d, w, h, d.get("shape", "rect"), 0x4472c4, 0x2f528f, 0xffffff, "center");
    else if ("textBox".equals(k)) shape(ops, d, w, h, "rect", 0xffffff, 0x000000, 0x000000, "left");
    else if ("wordArt".equals(k)) wordArt(ops, d, w, h);
    else if ("chart".equals(k)) chart(ops, d, w, h);
    else if ("smartArt".equals(k)) smartArt(ops, d, w, h);
    else if ("equation".equals(k)) equation(ops, d, w, h, metrics == null ? new PdfMetrics() : metrics);
    else if ("ink".equals(k) || "canvas".equals(k)) {
      if ("canvas".equals(k)) {
        int fill = d.color("fill", -1);
        ops.fill(rect(0, 0, w, h), fill, d.color("line", 0xbfbfbf), d.num("lineWidth", .75f));
      }
      ink(ops, d.get("strokes", ""));
      for (String item : split(d.get("items", ""), '\u001e')) {
        var f = split(item, '\u001f');
        if (f.size() != 5) continue;
        try {
          float x = Float.parseFloat(f.get(0)), y = Float.parseFloat(f.get(1));
          Ops child = build(Drawing.decode(f.get(4)), Float.parseFloat(f.get(2)), Float.parseFloat(f.get(3)));
          for (Path p : child.paths) offset(p.points, x, y);
          for (Text t : child.texts)
            ops.texts.add(new Text(t.text, t.x + x, t.y + y, t.width, t.size, t.color, t.bold, t.italic, t.align, t.outline, t.rotation));
          ops.paths.addAll(child.paths);
        } catch (RuntimeException malformed) {
          // skip an unreadable group member
        }
      }
    } else if ("icon".equals(k)) icon(ops, d, w, h);
    else if ("signature".equals(k)) signature(ops, d, w, h);
    else if ("object".equals(k)) object(ops, d, w, h);
    effects(ops, d);
    return ops;
  }

  // ---------------------------------------------------------------- shapes and text boxes

  private static void shape(
      Ops ops, Drawing d, float w, float h, String geometry, int fill0, int line0, int text0,
      String align0) {
    int fill = d.color("fill", fill0), line = d.color("line", line0);
    float lw = d.num("lineWidth", 1);
    int transparency = (int) Math.max(0, Math.min(100, d.num("transparency", 0)));
    if ("custom".equals(geometry)) {
      // A freeform: each subpath filled when closed, stroked when open (Wordcraft's freeform).
      var closed = new ArrayList<Boolean>();
      var paths = Freeform.paths(d.get("path", ""), w, h, closed);
      int alpha = Math.round(255 * (100 - transparency) / 100f);
      for (int i = 0; i < paths.size(); i++)
        ops.paths.add(new Path(paths.get(i), closed.get(i), closed.get(i) ? fill : -1, line, lw, alpha));
    } else {
    float[] pts = geometry(geometry, w, h);
    if ("line".equals(geometry) || "arrowLine".equals(geometry)) {
      ops.line(pts, line < 0 ? line0 : line, Math.max(lw, .5f), 255);
      if ("arrowLine".equals(geometry))
        ops.fill(new float[] {w, h / 2, w - 10, h / 2 - 5, w - 10, h / 2 + 5}, line < 0 ? line0 : line, -1, 0);
    } else {
      ops.paths.add(
          new Path(pts, true, fill, line, lw, Math.round(255 * (100 - transparency) / 100f)));
    }
    }
    var text = d.get("text", "");
    if (text.isEmpty()) return;
    float inset = d.num("inset", 7.2f);
    float[] box = textBox(geometry, w, h);
    layoutText(
        ops, d, text, box[0] + inset, box[1] + inset, box[2] - 2 * inset, box[3] - 2 * inset,
        d.color("textColor", text0), d.get("align", align0));
  }

  /** The text rectangle (x, y, w, h) inside a geometry. */
  private static float[] textBox(String g, float w, float h) {
    if ("ellipse".equals(g) || "octagon".equals(g) || "hexagon".equals(g))
      return new float[] {w * .146f, h * .146f, w * .708f, h * .708f};
    if ("triangle".equals(g) || "star".equals(g) || "pentagon".equals(g))
      return new float[] {w * .25f, h * .4f, w * .5f, h * .5f};
    if ("diamond".equals(g)) return new float[] {w * .25f, h * .25f, w * .5f, h * .5f};
    if ("callout".equals(g)) return new float[] {0, 0, w, h * .75f};
    return new float[] {0, 0, w, h};
  }

  static void layoutText(
      Ops ops, Drawing d, String text, float x, float y, float w, float h, int color, String align) {
    float size = d.num("fontSize", 11);
    boolean bold = d.flag("bold");
    var direction = d.get("textDirection", "horizontal");
    List<String> lines;
    int rotation = 0;
    if ("stacked".equals(direction)) {
      lines = new ArrayList<>();
      for (int i = 0; i < text.length(); i++) if (text.charAt(i) != '\n') lines.add(text.substring(i, i + 1));
    } else if ("rotate90".equals(direction) || "rotate270".equals(direction)) {
      rotation = "rotate90".equals(direction) ? 90 : 270;
      lines = wrap(text, h, size, bold);
      float lineH = size * 1.2f, total = lineH * lines.size();
      float start = x + Math.max(0, (w - total) / 2);
      for (int i = 0; i < lines.size(); i++) {
        float lx = rotation == 90 ? start + total - (i + 1) * lineH : start + i * lineH;
        ops.texts.add(new Text(lines.get(i), lx, y, h, size, color, bold, false, align, -1, rotation));
      }
      return;
    } else lines = wrap(text, w, size, bold);
    float lineH = size * 1.2f, total = lineH * lines.size();
    var valign = d.get("valign", "middle");
    float top =
        "top".equals(valign) ? y : "bottom".equals(valign) ? y + h - total : y + (h - total) / 2;
    for (int i = 0; i < lines.size(); i++)
      ops.texts.add(
          new Text(lines.get(i), x, top + i * lineH, w, size, color, bold, d.flag("italic"), align, -1, 0));
  }

  /** Greedy word wrap with an average-advance estimate; renderers measure each line exactly. */
  static List<String> wrap(String text, float width, float size, boolean bold) {
    float advance = size * (bold ? .56f : .5f);
    int max = Math.max(1, (int) (width / advance));
    var out = new ArrayList<String>();
    for (String para : split(text, '\n')) {
      var line = new StringBuilder();
      for (String word : split(para, ' ')) {
        while (word.length() > max) {
          if (line.length() > 0) {
            out.add(line.toString());
            line.setLength(0);
          }
          out.add(word.substring(0, max));
          word = word.substring(max);
        }
        if (line.length() > 0 && line.length() + 1 + word.length() > max) {
          out.add(line.toString());
          line.setLength(0);
        }
        if (line.length() > 0) line.append(' ');
        line.append(word);
      }
      out.add(line.toString());
    }
    return out;
  }

  static List<String> split(String s, char c) {
    var out = new ArrayList<String>();
    int start = 0;
    for (int i = 0; i <= s.length(); i++)
      if (i == s.length() || s.charAt(i) == c) {
        out.add(s.substring(start, i));
        start = i + 1;
      }
    return out;
  }

  static float[] rect(float x, float y, float w, float h) {
    return new float[] {x, y, x + w, y, x + w, y + h, x, y + h};
  }

  static float[] ellipse(float cx, float cy, float rx, float ry) {
    int n = 48;
    float[] p = new float[n * 2];
    for (int i = 0; i < n; i++) {
      double a = 2 * Math.PI * i / n;
      p[i * 2] = cx + (float) Math.cos(a) * rx;
      p[i * 2 + 1] = cy + (float) Math.sin(a) * ry;
    }
    return p;
  }

  private static float[] regular(int sides, float w, float h, double start) {
    float[] p = new float[sides * 2];
    for (int i = 0; i < sides; i++) {
      double a = start + 2 * Math.PI * i / sides;
      p[i * 2] = w / 2 + (float) Math.cos(a) * w / 2;
      p[i * 2 + 1] = h / 2 + (float) Math.sin(a) * h / 2;
    }
    return p;
  }

  private static float[] scale(float[] unit, float w, float h) {
    float[] p = new float[unit.length];
    for (int i = 0; i < p.length; i += 2) {
      p[i] = unit[i] * w;
      p[i + 1] = unit[i + 1] * h;
    }
    return p;
  }

  static float[] geometry(String g, float w, float h) {
    if ("ellipse".equals(g)) return ellipse(w / 2, h / 2, w / 2, h / 2);
    if ("roundRect".equals(g)) {
      float r = Math.min(w, h) / 6;
      var p = new ArrayList<Float>();
      float[][] corners = {{w - r, r, -90}, {w - r, h - r, 0}, {r, h - r, 90}, {r, r, 180}};
      for (float[] c : corners)
        for (int i = 0; i <= 6; i++) {
          double a = Math.toRadians(c[2] + 15 * i);
          p.add(c[0] + (float) Math.cos(a) * r);
          p.add(c[1] + (float) Math.sin(a) * r);
        }
      float[] out = new float[p.size()];
      for (int i = 0; i < out.length; i++) out[i] = p.get(i);
      return out;
    }
    if ("triangle".equals(g)) return scale(new float[] {.5f, 0, 1, 1, 0, 1}, w, h);
    if ("rightTriangle".equals(g)) return scale(new float[] {0, 0, 1, 1, 0, 1}, w, h);
    if ("diamond".equals(g)) return scale(new float[] {.5f, 0, 1, .5f, .5f, 1, 0, .5f}, w, h);
    if ("pentagon".equals(g)) return regular(5, w, h, -Math.PI / 2);
    if ("hexagon".equals(g)) return regular(6, w, h, 0);
    if ("octagon".equals(g)) return regular(8, w, h, Math.PI / 8);
    if ("star".equals(g)) {
      float[] p = new float[20];
      for (int i = 0; i < 10; i++) {
        double a = -Math.PI / 2 + Math.PI * i / 5;
        float r = i % 2 == 0 ? .5f : .2f;
        p[i * 2] = w / 2 + (float) Math.cos(a) * r * w;
        p[i * 2 + 1] = h / 2 + (float) Math.sin(a) * r * h;
      }
      return p;
    }
    if ("rightArrow".equals(g))
      return scale(new float[] {0, .25f, .6f, .25f, .6f, 0, 1, .5f, .6f, 1, .6f, .75f, 0, .75f}, w, h);
    if ("leftArrow".equals(g))
      return scale(new float[] {1, .25f, .4f, .25f, .4f, 0, 0, .5f, .4f, 1, .4f, .75f, 1, .75f}, w, h);
    if ("upArrow".equals(g))
      return scale(new float[] {.25f, 1, .25f, .4f, 0, .4f, .5f, 0, 1, .4f, .75f, .4f, .75f, 1}, w, h);
    if ("downArrow".equals(g))
      return scale(new float[] {.25f, 0, .25f, .6f, 0, .6f, .5f, 1, 1, .6f, .75f, .6f, .75f, 0}, w, h);
    if ("line".equals(g) || "arrowLine".equals(g)) return new float[] {0, h / 2, w, h / 2};
    if ("plus".equals(g))
      return scale(
          new float[] {
            .33f, 0, .67f, 0, .67f, .33f, 1, .33f, 1, .67f, .67f, .67f, .67f, 1, .33f, 1, .33f, .67f,
            0, .67f, 0, .33f, .33f, .33f
          },
          w,
          h);
    if ("heart".equals(g)) {
      float[] p = new float[64];
      for (int i = 0; i < 32; i++) {
        double t = 2 * Math.PI * i / 32;
        double x = 16 * com.codename1.util.MathUtil.pow(Math.sin(t), 3);
        double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
        p[i * 2] = w / 2 + (float) (x / 34 * w);
        p[i * 2 + 1] = h * .45f - (float) (y / 34 * h);
      }
      return p;
    }
    if ("callout".equals(g))
      return scale(new float[] {0, 0, 1, 0, 1, .75f, .4f, .75f, .2f, 1, .25f, .75f, 0, .75f}, w, h);
    if ("parallelogram".equals(g)) return scale(new float[] {.25f, 0, 1, 0, .75f, 1, 0, 1}, w, h);
    if ("trapezoid".equals(g)) return scale(new float[] {.25f, 0, .75f, 0, 1, 1, 0, 1}, w, h);
    return rect(0, 0, w, h);
  }

  private static void effects(Ops ops, Drawing d) {
    int glow = d.color("glow", -1);
    boolean shadow = d.flag("shadow");
    if (!shadow && glow < 0) return;
    var extra = new ArrayList<Path>();
    for (Path p : ops.paths) {
      if (glow >= 0) extra.add(new Path(p.points, p.closed, -1, glow, p.width + 8, 90));
      if (shadow) {
        float[] s = new float[p.points.length];
        for (int i = 0; i < s.length; i++) s[i] = p.points[i] + 3;
        extra.add(
            new Path(s, p.closed, p.fill < 0 ? -1 : 0x404040, p.fill < 0 ? 0x404040 : -1, p.width, 90));
      }
    }
    ops.paths.addAll(0, extra);
  }

  // ---------------------------------------------------------------- WordArt

  private static void wordArt(Ops ops, Drawing d, float w, float h) {
    var text = d.get("text", "WordArt");
    var transform = d.get("transform", "none");
    int fill = d.color("textFill", 0x4472c4), outline = d.color("textOutline", -1);
    boolean bold = !"false".equals(d.get("bold", "true"));
    float size = Math.min(h * .7f, w / Math.max(1, text.length()) / (bold ? .56f : .5f));
    size = Math.max(4, Math.min(size, d.num("fontSize", 400)));
    float advance = w / Math.max(1, text.length());
    for (int i = 0; i < text.length(); i++) {
      float t = text.length() == 1 ? .5f : (float) i / (text.length() - 1);
      float dy = 0, s = size;
      if ("arch".equals(transform)) dy = -(float) Math.sin(Math.PI * t) * h * .2f + h * .1f;
      else if ("wave".equals(transform)) dy = (float) Math.sin(2 * Math.PI * t) * h * .12f;
      else if ("slant".equals(transform)) dy = (.5f - t) * h * .3f;
      else if ("inflate".equals(transform)) s = size * (.7f + .45f * (float) Math.sin(Math.PI * t));
      ops.texts.add(
          new Text(
              text.substring(i, i + 1), i * advance, (h - s * 1.2f) / 2 + dy, advance, s, fill, bold,
              d.flag("italic"), "center", outline, 0));
    }
  }

  // ---------------------------------------------------------------- charts

  /** Rows of the chart table: first row is the header (blank, series names...), then categories. */
  public static List<String[]> table(String data) {
    var rows = new ArrayList<String[]>();
    for (String line : split(data, '\n')) {
      if (line.trim().isEmpty()) continue;
      var cells = split(line, ',');
      String[] row = new String[cells.size()];
      for (int i = 0; i < row.length; i++) row[i] = cells.get(i).trim();
      rows.add(row);
    }
    return rows;
  }

  static float value(String[] row, int col) {
    if (col >= row.length) return 0;
    try {
      return Float.parseFloat(row[col]);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public static final String SAMPLE_CHART =
      """
      Category,Series 1,Series 2,Series 3
      Category 1,4.3,2.4,2
      Category 2,2.5,4.4,2
      Category 3,3.5,1.8,3
      Category 4,4.5,2.8,5""";

  private static void chart(Ops ops, Drawing d, float w, float h) {
    var type = d.get("chartType", "column");
    boolean pie = "pie".equals(type) || "doughnut".equals(type);
    var rows = table(d.get("data", SAMPLE_CHART));
    ops.fill(rect(0, 0, w, h), 0xffffff, 0xd9d9d9, .75f);
    var title = d.get("title", "Chart Title");
    float top = 6;
    if (!title.isEmpty()) {
      ops.text(title, 0, 6, w, 14, 0x595959, false, "center");
      top = 28;
    }
    if (rows.size() < 2 || rows.get(0).length < 2) return;
    var header = rows.get(0);
    int series = pie ? 1 : header.length - 1, cats = rows.size() - 1;
    boolean legend = !"false".equals(d.get("legend", "true"));
    float bottom = h - (legend ? 26 : 8);
    if (legend) {
      int entries = pie ? cats : series;
      float entryW = Math.min(90, w / Math.max(1, entries));
      float lx = (w - entryW * entries) / 2;
      for (int i = 0; i < entries; i++) {
        String name = pie ? rows.get(i + 1)[0] : header[i + 1];
        ops.fill(rect(lx + i * entryW, h - 17, 7, 7), PALETTE[i % PALETTE.length], -1, 0);
        ops.text(name, lx + i * entryW + 10, h - 21, entryW - 12, 9, 0x595959, false, "left");
      }
    }
    boolean labels = d.flag("dataLabels");
    if (pie) {
      float total = 0;
      for (int r = 1; r < rows.size(); r++) total += Math.max(0, value(rows.get(r), 1));
      if (total <= 0) return;
      float cx = w / 2, cy = (top + bottom) / 2, rad = Math.min(w, bottom - top) / 2 - 4;
      double angle = -Math.PI / 2;
      for (int r = 1; r < rows.size(); r++) {
        float v = Math.max(0, value(rows.get(r), 1));
        double sweep = 2 * Math.PI * v / total;
        int n = Math.max(2, (int) (sweep / (Math.PI / 24)) + 1);
        float[] p = new float[(n + 2) * 2];
        p[0] = cx;
        p[1] = cy;
        for (int i = 0; i <= n; i++) {
          double a = angle + sweep * i / n;
          p[2 + i * 2] = cx + (float) Math.cos(a) * rad;
          p[3 + i * 2] = cy + (float) Math.sin(a) * rad;
        }
        ops.fill(p, PALETTE[(r - 1) % PALETTE.length], 0xffffff, 1);
        if (labels) {
          double mid = angle + sweep / 2;
          ops.text(
              rows.get(r)[1], cx + (float) Math.cos(mid) * rad * .6f - 20,
              cy + (float) Math.sin(mid) * rad * .6f - 5, 40, 9, 0xffffff, true, "center");
        }
        angle += sweep;
      }
      if ("doughnut".equals(type)) ops.fill(ellipse(cx, cy, rad / 2, rad / 2), 0xffffff, -1, 0);
      return;
    }
    // Wordcraft's read/chart.rs groupings: stacked series add up per category, 100% stacked
    // ones are each category's share of its total (axis 0..100%); clustered side by side.
    String grouping = "scatter".equals(type) ? "clustered" : d.get("grouping", "clustered");
    boolean stacked = "stacked".equals(grouping) || "percentStacked".equals(grouping);
    boolean percent = "percentStacked".equals(grouping);
    float[][] vals = new float[rows.size()][series + 1];
    for (int r = 1; r < rows.size(); r++) {
      float total = 0;
      for (int s = 1; s <= series; s++) total += Math.abs(vals[r][s] = value(rows.get(r), s));
      if (percent)
        for (int s = 1; s <= series; s++) vals[r][s] = total == 0 ? 0 : vals[r][s] / total;
    }
    float max = 0;
    for (int r = 1; r < rows.size(); r++) {
      float sum = 0;
      for (int s = 1; s <= series; s++) {
        sum += Math.max(0, vals[r][s]);
        max = Math.max(max, stacked ? sum : vals[r][s]);
      }
    }
    max = percent ? 1 : nice(max);
    float left = 30, right = w - 10, plotTop = top + 4, plotBottom = bottom - 16;
    if ("scatter".equals(type)) {
      scatter(ops, rows, series, max, left, right, plotTop, plotBottom, labels);
      return;
    }
    boolean bar = "bar".equals(type);
    for (int i = 0; i <= 4; i++) {
      float f = i / 4f;
      String label = percent ? Math.round(100 * f) + "%" : trim(max * f);
      if (bar) {
        float x = left + (right - left) * f;
        ops.line(new float[] {x, plotTop, x, plotBottom}, 0xd9d9d9, .5f, 255);
        ops.text(label, x - 15, plotBottom + 2, 30, 8, 0x595959, false, "center");
      } else {
        float y = plotBottom - (plotBottom - plotTop) * f;
        ops.line(new float[] {left, y, right, y}, 0xd9d9d9, .5f, 255);
        ops.text(label, 0, y - 5, left - 3, 8, 0x595959, false, "right");
      }
    }
    float band = ((bar ? plotBottom - plotTop : right - left)) / cats;
    for (int r = 1; r < rows.size(); r++) {
      float start = (bar ? plotTop : left) + band * (r - 1);
      var name = rows.get(r)[0];
      if (bar) ops.text(name, 0, start + band / 2 - 5, left - 3, 8, 0x595959, false, "right");
      else ops.text(name, start, plotBottom + 2, band, 8, 0x595959, false, "center");
      if (!"column".equals(type) && !bar) continue;
      float slot = stacked ? band * .7f : band * .7f / series, base = 0;
      for (int s = 1; s <= series; s++) {
        float v = max <= 0 ? 0 : Math.max(stacked ? 0 : -Float.MAX_VALUE, vals[r][s]) / max;
        int c = PALETTE[(s - 1) % PALETTE.length];
        float at = stacked ? 0 : slot * (s - 1);
        float[] box =
            bar
                ? rect(left + (right - left) * base, start + band * .15f + at, (right - left) * v, slot)
                : rect(start + band * .15f + at, plotBottom - (plotBottom - plotTop) * (base + v), slot, (plotBottom - plotTop) * v);
        ops.fill(box, c, -1, 0);
        if (labels) {
          String l = percent ? Math.round(vals[r][s] * 100) + "%" : trim(value(rows.get(r), s));
          if (stacked)
            ops.text(l, box[0], box[1] + box[3] / 2 - 4, box[2], 8, 0xffffff, false, "center");
          else if (bar) ops.text(l, left + (right - left) * v + 2, box[1] + slot / 2 - 4, 30, 8, 0x404040, false, "left");
          else ops.text(l, box[0] - 10, box[1] - 11, slot + 20, 8, 0x404040, false, "center");
        }
        if (stacked) base += v;
      }
    }
    if ("line".equals(type) || "area".equals(type))
      for (int s = series; s >= 1; s--) {
        // Stacked lines and areas plot each series on top of the ones before it; a stacked
        // area fills down to the previous series' line.
        boolean fillDown = stacked && "area".equals(type) && s > 1;
        float[] p = new float[fillDown ? cats * 4 : "area".equals(type) ? cats * 2 + 4 : cats * 2];
        for (int r = 1; r <= cats; r++) {
          float sum = 0, below = 0;
          if (stacked) {
            for (int k = 1; k <= s; k++) sum += Math.max(0, vals[r][k]);
            below = sum - Math.max(0, vals[r][s]);
          } else sum = vals[r][s];
          float v = max <= 0 ? 0 : sum / max;
          if (fillDown) {
            p[cats * 4 - r * 2] = left + band * (r - .5f);
            p[cats * 4 - r * 2 + 1] = plotBottom - (plotBottom - plotTop) * (max <= 0 ? 0 : below / max);
          }
          p[(r - 1) * 2] = left + band * (r - .5f);
          p[(r - 1) * 2 + 1] = plotBottom - (plotBottom - plotTop) * v;
          if (labels)
            ops.text(percent ? Math.round(vals[r][s] * 100) + "%" : trim(value(rows.get(r), s)), p[(r - 1) * 2] - 15, p[(r - 1) * 2 + 1] - 12, 30, 8, 0x404040, false, "center");
        }
        int c = PALETTE[(s - 1) % PALETTE.length];
        if (fillDown) ops.paths.add(new Path(p, true, c, -1, 0, 200));
        else if ("area".equals(type)) {
          p[cats * 2] = left + band * (cats - .5f);
          p[cats * 2 + 1] = plotBottom;
          p[cats * 2 + 2] = left + band * .5f;
          p[cats * 2 + 3] = plotBottom;
          ops.paths.add(new Path(p, true, c, -1, 0, 200));
        } else ops.line(p, c, 2.25f, 255);
      }
    ops.line(new float[] {left, plotBottom, right, plotBottom}, 0xbfbfbf, .75f, 255);
  }

  /** Scatter: the first column is x, every other column one y series, both axes linear. */
  private static void scatter(
      Ops ops, List<String[]> rows, int series, float max, float left, float right, float top, float bottom, boolean labels) {
    float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
    for (int r = 1; r < rows.size(); r++) {
      float x = value(rows.get(r), 0);
      lo = Math.min(lo, x);
      hi = Math.max(hi, x);
    }
    if (lo > 0) lo = 0;
    hi = nice(hi);
    float span = hi - lo <= 0 ? 1 : hi - lo;
    for (int i = 0; i <= 4; i++) {
      float y = bottom - (bottom - top) * i / 4f, x = left + (right - left) * i / 4f;
      ops.line(new float[] {left, y, right, y}, 0xd9d9d9, .5f, 255);
      ops.text(trim(max * i / 4f), 0, y - 5, left - 3, 8, 0x595959, false, "right");
      ops.text(trim(lo + span * i / 4f), x - 15, bottom + 2, 30, 8, 0x595959, false, "center");
    }
    for (int s = 1; s <= series; s++) {
      int c = PALETTE[(s - 1) % PALETTE.length];
      float[] p = new float[(rows.size() - 1) * 2];
      for (int r = 1; r < rows.size(); r++) {
        float px = left + (right - left) * (value(rows.get(r), 0) - lo) / span;
        float py = bottom - (bottom - top) * (max <= 0 ? 0 : value(rows.get(r), s) / max);
        p[(r - 1) * 2] = px;
        p[(r - 1) * 2 + 1] = py;
        ops.fill(ellipse(px, py, 2.5f, 2.5f), c, -1, 0);
        if (labels) ops.text(trim(value(rows.get(r), s)), px - 15, py - 12, 30, 8, 0x404040, false, "center");
      }
      if (p.length >= 4) ops.line(p, c, 1.5f, 255);
    }
    ops.line(new float[] {left, bottom, right, bottom}, 0xbfbfbf, .75f, 255);
  }

  private static float nice(float max) {
    if (max <= 0) return 1;
    double mag = com.codename1.util.MathUtil.pow(10, Math.floor(com.codename1.util.MathUtil.log10(max)));
    for (double m : new double[] {1, 2, 2.5, 5, 10}) if (m * mag >= max) return (float) (m * mag);
    return (float) (10 * mag);
  }

  private static String trim(float v) {
    return v == Math.round(v) ? String.valueOf(Math.round(v)) : String.valueOf(Math.round(v * 10) / 10f);
  }

  // ---------------------------------------------------------------- SmartArt

  /** SmartArt nodes, one per line, indented by leading tabs or "level:" prefixes. */
  public static List<Object[]> nodes(String text) {
    var out = new ArrayList<Object[]>();
    for (String line : split(text, '\n')) {
      int level = 1;
      var t = line;
      while (t.startsWith("\t")) {
        level++;
        t = t.substring(1);
      }
      if (t.trim().isEmpty()) continue;
      out.add(new Object[] {Math.min(level, 9), t.trim()});
    }
    return out;
  }

  private static int smartColor(Drawing d, int i) {
    var scheme = d.get("colors", "accent1");
    if ("colorful".equals(scheme)) return PALETTE[i % PALETTE.length];
    int base = 0x4472c4;
    if (scheme.startsWith("accent") && scheme.length() == 7) {
      int k = scheme.charAt(6) - '1';
      if (k >= 0 && k < 6) base = PALETTE[k];
    }
    return base;
  }

  private static void smartArt(Ops ops, Drawing d, float w, float h) {
    String saved = d.get("shapes", "");
    if (!saved.isEmpty() && DocxCharts.smartKey(d).equals(d.get("dgmKey", ""))) {
      savedShapes(ops, saved, w, h);
      return;
    }
    var layout = d.get("layout", "list");
    var all = nodes(d.get("nodes", "Text\nText\nText"));
    var top = new ArrayList<String>();
    var kids = new ArrayList<List<String>>();
    for (Object[] n : all) {
      if ((Integer) n[0] == 1 || top.isEmpty()) {
        top.add((String) n[1]);
        kids.add(new ArrayList<String>());
      } else kids.get(kids.size() - 1).add((String) n[1]);
    }
    int n = top.size();
    if (n == 0) return;
    var label = Drawing.of("shape").with("fontSize", d.get("fontSize", "12"));
    if ("process".equals(layout)) {
      float gap = 18, bw = (w - gap * (n - 1)) / n, bh = Math.min(h, bw * .7f), by = (h - bh) / 2;
      for (int i = 0; i < n; i++) {
        float bx = i * (bw + gap);
        node(ops, label, top.get(i), kids.get(i), bx, by, bw, bh, smartColor(d, i));
        if (i + 1 < n)
          ops.fill(
              new float[] {bx + bw + 4, h / 2 - 5, bx + bw + gap - 4, h / 2, bx + bw + 4, h / 2 + 5},
              0xa5a5a5, -1, 0);
        if (i + 1 < n) ops.shape("rightArrow", bx + bw + 4, h / 2 - 5, gap - 8, 10, 0xa5a5a5, -1, 0, "");
      }
    } else if ("cycle".equals(layout)) {
      float cx = w / 2, cy = h / 2, rad = Math.min(w, h) * .35f;
      float bw = Math.min(w, h) * .3f, bh = bw * .6f;
      ops.line(ellipse(cx, cy, rad, rad), 0xa5a5a5, 1.5f, 255);
      // The ring behind the nodes is cached too, first so it stays under them.
      ops.shape("ellipse", cx - rad, cy - rad, rad * 2, rad * 2, -1, 0xa5a5a5, -1, "");
      for (int i = 0; i < n; i++) {
        double a = -Math.PI / 2 + 2 * Math.PI * i / n;
        float bx = cx + (float) Math.cos(a) * rad - bw / 2, by = cy + (float) Math.sin(a) * rad - bh / 2;
        node(ops, label, top.get(i), kids.get(i), bx, by, bw, bh, smartColor(d, i));
      }
    } else if ("hierarchy".equals(layout)) {
      float bw0 = Math.min(w * .3f, 140), bh = Math.min(h / 3.2f, 50);
      node(ops, label, top.get(0), Collections.<String>emptyList(), (w - bw0) / 2, 0, bw0, bh, smartColor(d, 0));
      var second = new ArrayList<String>(kids.get(0));
      for (int i = 1; i < n; i++) second.add(top.get(i));
      int m = second.size();
      if (m > 0) {
        float gap = 10, bw = Math.min(bw0, (w - gap * (m - 1)) / m);
        float total = bw * m + gap * (m - 1), sx = (w - total) / 2, by = h - bh;
        ops.connector(w / 2, bh, w / 2, (bh + by) / 2, 0x7f7f7f, 1);
        if (m > 1) ops.connector(sx + bw / 2, (bh + by) / 2, sx + total - bw / 2, (bh + by) / 2, 0x7f7f7f, 1);
        for (int i = 0; i < m; i++) {
          float bx = sx + i * (bw + gap);
          ops.connector(bx + bw / 2, (bh + by) / 2, bx + bw / 2, by, 0x7f7f7f, 1);
          node(ops, label, second.get(i), Collections.<String>emptyList(), bx, by, bw, bh, smartColor(d, i + 1));
        }
      }
    } else if ("pyramid".equals(layout)) {
      float lh = h / n;
      for (int i = 0; i < n; i++) {
        float t0 = (float) i / n, t1 = (float) (i + 1) / n;
        ops.fill(
            new float[] {w / 2 - w * t0 / 2, lh * i, w / 2 + w * t0 / 2, lh * i, w / 2 + w * t1 / 2, lh * (i + 1), w / 2 - w * t1 / 2, lh * (i + 1)},
            smartColor(d, i), 0xffffff, 1);
        ops.shape(i == 0 ? "triangle" : "trapezoid", w / 2 - w * t1 / 2, lh * i, w * t1, lh, smartColor(d, i), 0xffffff,
            0xffffff, top.get(i));
        layoutText(ops, label, top.get(i), w / 4, lh * i, w / 2, lh, 0xffffff, "center");
      }
    } else if ("radial".equals(layout)) {
      // The first top-level node in the middle, the rest around it on spokes.
      float cx = w / 2, cy = h / 2, s = Math.min(w, h), rad = s * .36f, r0 = s * .17f, r1 = s * .13f;
      int m = n - 1;
      for (int i = 0; i < m; i++) {
        double a = -Math.PI / 2 + 2 * Math.PI * i / m;
        ops.connector(cx, cy, cx + (float) Math.cos(a) * rad, cy + (float) Math.sin(a) * rad, 0xa5a5a5, 1);
      }
      ops.fill(ellipse(cx, cy, r0, r0), smartColor(d, 0), 0xffffff, 1);
      ops.shape("ellipse", cx - r0, cy - r0, r0 * 2, r0 * 2, smartColor(d, 0), 0xffffff, 0xffffff, top.get(0));
      layoutText(ops, label, top.get(0), cx - r0, cy - r0, r0 * 2, r0 * 2, 0xffffff, "center");
      for (int i = 0; i < m; i++) {
        double a = -Math.PI / 2 + 2 * Math.PI * i / m;
        float x = cx + (float) Math.cos(a) * rad, y = cy + (float) Math.sin(a) * rad;
        ops.fill(ellipse(x, y, r1, r1), smartColor(d, i + 1), 0xffffff, 1);
        ops.shape("ellipse", x - r1, y - r1, r1 * 2, r1 * 2, smartColor(d, i + 1), 0xffffff, 0xffffff, top.get(i + 1));
        layoutText(ops, label, top.get(i + 1), x - r1, y - r1, r1 * 2, r1 * 2, 0xffffff, "center");
      }
    } else if ("venn".equals(layout)) {
      // Overlapping translucent circles around the centre.
      float cx = w / 2, cy = h / 2, s = Math.min(w, h), off = n == 1 ? 0 : s * .17f, r = n == 1 ? s * .45f : s * .3f;
      for (int i = 0; i < n; i++) {
        double a = -Math.PI / 2 + 2 * Math.PI * i / n;
        float x = cx + (float) Math.cos(a) * off, y = cy + (float) Math.sin(a) * off;
        ops.paths.add(new Path(ellipse(x, y, r, r), true, smartColor(d, i), smartColor(d, i), 1, 128));
        ops.shape("ellipse", x - r, y - r, r * 2, r * 2, smartColor(d, i), smartColor(d, i), 0x262626, top.get(i));
      }
      for (int i = 0; i < n; i++) {
        double a = -Math.PI / 2 + 2 * Math.PI * i / n;
        float x = cx + (float) Math.cos(a) * off * 1.9f, y = cy + (float) Math.sin(a) * off * 1.9f;
        layoutText(ops, label, top.get(i), x - r * .7f, y - r * .4f, r * 1.4f, r * .8f, 0x262626, "center");
      }
    } else if ("matrix".equals(layout)) {
      int cols = n <= 1 ? 1 : 2, rowsN = (n + cols - 1) / cols;
      float bw = (w - 6) / cols, bh = (h - 6 * (rowsN - 1)) / rowsN;
      for (int i = 0; i < n; i++)
        node(ops, label, top.get(i), kids.get(i), (i % cols) * (bw + 6), (i / cols) * (bh + 6), bw, bh, smartColor(d, i));
    } else {
      float gap = 6, bh = (h - gap * (n - 1)) / n;
      for (int i = 0; i < n; i++) node(ops, label, top.get(i), kids.get(i), 0, i * (bh + gap), w, bh, smartColor(d, i));
    }
  }

  /** The shapes Word saved for an unedited imported SmartArt graphic (see DocxCharts.shapes). */
  static void savedShapes(Ops ops, String saved, float w, float h) {
    for (String line : split(saved, '\n')) {
      var f = new ArrayList<String>();
      int start = 0;
      for (int i = 0; i < line.length() && f.size() < 12; i++)
        if (line.charAt(i) == ',') {
          f.add(line.substring(start, i));
          start = i + 1;
        }
      if (f.size() < 12) continue;
      String text = line.substring(start);
      float[] v = new float[12];
      for (int i = 1; i < 12; i++) v[i] = Xml.integer(f.get(i), 0);
      float x = v[1] * w / 10000, y = v[2] * h / 10000, sw = Math.max(0, v[3] * w / 10000), sh = Math.max(0, v[4] * h / 10000);
      int fill = (int) v[5], stroke = (int) v[6], color = (int) v[7];
      boolean inv = "lineInv".equals(f.get(0));
      if ((inv || "line".equals(f.get(0))) && stroke >= 0 && sw + sh > 0) {
        // Cached SmartArt connectors: an open stroke corner to corner of the box.
        ops.line(inv ? new float[] {x, y + sh, x + sw, y} : new float[] {x, y, x + sw, y + sh}, stroke, 1, 255);
      } else if (sw > 0 && sh > 0 && (fill >= 0 || stroke >= 0)) {
        String g = SHAPES.contains(f.get(0)) || "roundRect".equals(f.get(0)) ? f.get(0) : "rect";
        float[] p = offset(geometry(g, sw, sh), x, y);
        if (fill >= 0) ops.fill(p, fill, stroke, stroke >= 0 ? .75f : 0);
        else ops.paths.add(new Path(p, true, -1, stroke, .75f, 255));
      }
      if (!text.trim().isEmpty()) {
        float tx = v[8] * w / 10000, ty = v[9] * h / 10000, tw = Math.max(4, v[10] * w / 10000), th = v[11] * h / 10000;
        var lines = split(text, DocxCharts.BREAK);
        float size = Math.max(6, Math.min(14, th / Math.max(2, lines.size() * 2.2f)));
        float top = ty + Math.max(0, (th - lines.size() * size * 1.2f) / 2);
        for (String l : lines) {
          ops.text(l, tx, top, tw, size, color, false, "center");
          top += size * 1.2f;
        }
      }
    }
  }

  private static void node(
      Ops ops, Drawing label, String text, List<String> children, float x, float y, float w, float h, int color) {
    ops.fill(geometry("roundRect", w, h), color, 0xffffff, 1);
    var label0 = new StringBuilder(text);
    for (String c : children) label0.append('\n').append(c);
    ops.shape("roundRect", x, y, w, h, color, 0xffffff, 0xffffff, label0.toString());
    for (int i = 0; i < ops.paths.get(ops.paths.size() - 1).points.length; i += 2) {
      ops.paths.get(ops.paths.size() - 1).points[i] += x;
      ops.paths.get(ops.paths.size() - 1).points[i + 1] += y;
    }
    var t = new StringBuilder(text);
    for (String c : children) t.append("\n\u2022 ").append(c);
    layoutText(ops, label, t.toString(), x + 4, y + 2, w - 8, h - 4, 0xffffff, "center");
  }

  // ---------------------------------------------------------------- equations

  private static final String[][] SYMBOLS = {
    {"\\alpha", "\u03b1"}, {"\\beta", "\u03b2"}, {"\\gamma", "\u03b3"}, {"\\delta", "\u03b4"},
    {"\\epsilon", "\u03b5"}, {"\\theta", "\u03b8"}, {"\\lambda", "\u03bb"}, {"\\mu", "\u03bc"},
    {"\\pi", "\u03c0"}, {"\\sigma", "\u03c3"}, {"\\phi", "\u03c6"}, {"\\omega", "\u03c9"},
    {"\\Delta", "\u0394"}, {"\\Sigma", "\u03a3"}, {"\\Omega", "\u03a9"}, {"\\infty", "\u221e"},
    {"\\pm", "\u00b1"}, {"\\times", "\u00d7"}, {"\\div", "\u00f7"}, {"\\cdot", "\u22c5"},
    {"\\le", "\u2264"}, {"\\ge", "\u2265"}, {"\\ne", "\u2260"}, {"\\approx", "\u2248"},
    {"\\sum", "\u2211"}, {"\\prod", "\u220f"}, {"\\int", "\u222b"}, {"\\partial", "\u2202"},
    {"\\sqrt", "\u221a"}, {"\\rightarrow", "\u2192"}, {"\\leftarrow", "\u2190"}, {"\\in", "\u2208"},
    {"\\forall", "\u2200"}, {"\\exists", "\u2203"}, {"\\nabla", "\u2207"}, {"\\degree", "\u00b0"}
  };

  public static List<String[]> symbols() {
    return Arrays.asList(SYMBOLS);
  }

  private static final String SUPER = "\u2070\u00b9\u00b2\u00b3\u2074\u2075\u2076\u2077\u2078\u2079";
  private static final String SUB = "\u2080\u2081\u2082\u2083\u2084\u2085\u2086\u2087\u2088\u2089";

  /**
   * Linear format to built-up display text: control words become symbols, {@code ^n}/{@code _n}
   * digits become super/subscripts and {@code (a)/(b)} keeps its parentheses as a slash fraction.
   */
  public static String professional(String linear) {
    var b = new StringBuilder();
    for (int i = 0; i < linear.length(); i++) {
      char c = linear.charAt(i);
      if (c == '\\') {
        int j = i + 1;
        while (j < linear.length() && Unicode.letter(linear.charAt(j))) j++;
        String word = linear.substring(i, j), sym = null;
        for (String[] s : SYMBOLS) if (s[0].equals(word)) sym = s[1];
        if (sym != null) {
          b.append(sym);
          i = j - 1;
          if (j < linear.length() && linear.charAt(j) == ' ') i = j;
          continue;
        }
      }
      if ((c == '^' || c == '_') && i + 1 < linear.length() && Character.isDigit(linear.charAt(i + 1))) {
        String table = c == '^' ? SUPER : SUB;
        int j = i + 1;
        while (j < linear.length() && Character.isDigit(linear.charAt(j)))
          b.append(table.charAt(linear.charAt(j++) - '0'));
        i = j - 1;
        continue;
      }
      b.append(c);
    }
    return b.toString();
  }

  private static void equation(Ops ops, Drawing d, float w, float h, LayoutEngine.Metrics metrics) {
    var linear = d.get("text", "");
    boolean professional = !"linear".equals(d.get("format", "professional"));
    float size = d.num("fontSize", 12);
    var justify = d.get("justify", "center");
    var number = d.get("number", "");
    float right = number.isEmpty() ? w : w - 40;
    int color = d.color("textColor", 0);
    if (!number.isEmpty())
      ops.texts.add(new Text("(" + number + ")", w - 40, (h - size * 1.2f) / 2, 40, size, 0, false, false, "right", -1, 0));
    if (!professional) {
      ops.texts.add(new Text(linear, 0, (h - size * 1.2f) / 2, right, size, color, false, false, justify, -1, 0));
      return;
    }
    // Built-up form: every glyph is measured with the renderer's own metrics and placed left-aligned.
    Equation.Box box = Equation.layout(Equation.of(d), size, metrics);
    float x = "left".equals(justify) ? 0 : "right".equals(justify) ? right - box.width : (right - box.width) / 2;
    x = Math.max(0, x);
    // An inline equation is placed with its origin on the text baseline.
    float base = d.flag("inline") ? 0 : (h - box.ascent - box.descent) / 2 + box.ascent;
    boolean upright = d.flag("normalText");
    for (Equation.Glyph g : box.glyphs)
      ops.texts.add(new Text(g.text(), x + g.x(), base + g.y() - g.size(), g.width() + 1, g.size(), color, g.bold(),
          g.italic() && !upright, "left", -1, 0));
    for (float[] l : box.lines) {
      float[] pts = new float[l.length - 1];
      for (int i = 0; i + 1 < pts.length; i += 2) {
        pts[i] = x + l[i];
        pts[i + 1] = base + l[i + 1];
      }
      ops.line(pts, color, l[l.length - 1], 255);
    }
  }

  // ---------------------------------------------------------------- ink, icons, signature, object

  /** Strokes: {@code tool,color,width:x,y x,y ...} separated by {@code |}. */
  static void ink(Ops ops, String strokes) {
    for (String stroke : split(strokes, '|')) {
      int colon = stroke.indexOf(':');
      if (colon < 0) continue;
      var head = split(stroke.substring(0, colon), ',');
      var pts = split(stroke.substring(colon + 1).trim(), ' ');
      float[] p = new float[pts.size() * 2];
      int n = 0;
      for (String pt : pts) {
        int comma = pt.indexOf(',');
        if (comma < 0) continue;
        try {
          p[n++] = Float.parseFloat(pt.substring(0, comma));
          p[n++] = Float.parseFloat(pt.substring(comma + 1));
        } catch (NumberFormatException e) {
          n -= n % 2;
        }
      }
      if (n < 2) continue;
      if (n == 2) p = new float[] {p[0], p[1], p[0] + .1f, p[1]};
      else p = Arrays.copyOf(p, n);
      var tool = head.get(0);
      int color = 0;
      float width = 1;
      try {
        if (head.size() > 1) color = Integer.parseInt(head.get(1).replace("#", ""), 16);
        if (head.size() > 2) width = Float.parseFloat(head.get(2));
      } catch (NumberFormatException ignored) {
        // keep defaults
      }
      int alpha = "highlighter".equals(tool) ? 100 : "pencil".equals(tool) ? 190 : 255;
      ops.line(p, color, "highlighter".equals(tool) ? width * 4 : width, alpha);
    }
  }

  /** Removes strokes passing within {@code radius} of (x, y); returns the remaining encoding. */
  public static String erase(String strokes, float x, float y, float radius) {
    var out = new StringBuilder();
    for (String stroke : split(strokes, '|')) {
      if (stroke.trim().isEmpty()) continue;
      boolean hit = false;
      int colon = stroke.indexOf(':');
      float px = Float.NaN, py = Float.NaN;
      for (String pt : split(stroke.substring(colon + 1).trim(), ' ')) {
        int comma = pt.indexOf(',');
        if (comma < 0) continue;
        try {
          float qx = Float.parseFloat(pt.substring(0, comma)), qy = Float.parseFloat(pt.substring(comma + 1));
          hit |= segment(x, y, Float.isNaN(px) ? qx : px, Float.isNaN(py) ? qy : py, qx, qy) <= radius;
          px = qx;
          py = qy;
        } catch (NumberFormatException ignored) {
          // malformed point: not a hit
        }
      }
      if (hit) continue;
      if (out.length() > 0) out.append('|');
      out.append(stroke);
    }
    return out.toString();
  }

  /** Distance from (x, y) to the segment (ax, ay)-(bx, by). */
  static float segment(float x, float y, float ax, float ay, float bx, float by) {
    float dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
    float t = len == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len));
    float cx = ax + t * dx - x, cy = ay + t * dy - y;
    return (float) Math.sqrt(cx * cx + cy * cy);
  }

  private static void icon(Ops ops, Drawing d, float w, float h) {
    var name = d.get("icon", "star");
    int c = d.color("fill", 0x404040);
    float s = Math.min(w, h), ox = (w - s) / 2, oy = (h - s) / 2;
    float[] p;
    if ("check".equals(name)) {
      ops.line(offset(scale(new float[] {.15f, .55f, .4f, .8f, .85f, .2f}, s, s), ox, oy), c, s / 9, 255);
      return;
    }
    if ("cross".equals(name)) {
      ops.line(offset(scale(new float[] {.2f, .2f, .8f, .8f}, s, s), ox, oy), c, s / 9, 255);
      ops.line(offset(scale(new float[] {.8f, .2f, .2f, .8f}, s, s), ox, oy), c, s / 9, 255);
      return;
    }
    if ("warning".equals(name)) {
      ops.fill(offset(geometry("triangle", s, s), ox, oy), c, -1, 0);
      ops.text("!", ox, oy + s * .35f, s, s * .5f, 0xffffff, true, "center");
      return;
    }
    if ("info".equals(name)) {
      ops.fill(offset(geometry("ellipse", s, s), ox, oy), c, -1, 0);
      ops.text("i", ox, oy + s * .2f, s, s * .55f, 0xffffff, true, "center");
      return;
    }
    if ("person".equals(name)) {
      ops.fill(offset(ellipse(s / 2, s * .3f, s * .18f, s * .18f), ox, oy), c, -1, 0);
      ops.fill(offset(geometry("roundRect", s * .7f, s * .4f), ox + s * .15f, oy + s * .55f), c, -1, 0);
      return;
    }
    if ("home".equals(name))
      p = scale(new float[] {.5f, .1f, .9f, .5f, .8f, .5f, .8f, .9f, .2f, .9f, .2f, .5f, .1f, .5f}, s, s);
    else if ("mail".equals(name)) {
      ops.fill(offset(rect(s * .1f, s * .25f, s * .8f, s * .5f), ox, oy), -1, c, s / 16);
      ops.line(offset(scale(new float[] {.1f, .25f, .5f, .55f, .9f, .25f}, s, s), ox, oy), c, s / 16, 255);
      return;
    } else if ("arrow".equals(name)) p = geometry("rightArrow", s, s);
    else if ("heart".equals(name)) p = geometry("heart", s, s);
    else if ("lightbulb".equals(name)) {
      ops.fill(offset(ellipse(s / 2, s * .4f, s * .28f, s * .28f), ox, oy), c, -1, 0);
      ops.fill(offset(rect(s * .38f, s * .66f, s * .24f, s * .2f), ox, oy), c, -1, 0);
      return;
    } else if ("flag".equals(name)) {
      ops.line(offset(new float[] {s * .2f, s * .1f, s * .2f, s * .9f}, ox, oy), c, s / 14, 255);
      p = scale(new float[] {.2f, .1f, .8f, .25f, .2f, .45f}, s, s);
    } else p = geometry("star", s, s);
    ops.fill(offset(p, ox, oy), c, -1, 0);
  }

  private static float[] offset(float[] p, float x, float y) {
    for (int i = 0; i < p.length; i += 2) {
      p[i] += x;
      p[i + 1] += y;
    }
    return p;
  }

  private static void signature(Ops ops, Drawing d, float w, float h) {
    float y = h * .55f;
    ops.text("X", 0, y - 22, 20, 18, 0x404040, false, "left");
    ops.line(new float[] {0, y, w, y}, 0x404040, 1, 255);
    String signer = d.get("signer", ""), title = d.get("signerTitle", "");
    if (!signer.isEmpty()) ops.text(signer, 0, y + 3, w, 10, 0x404040, false, "left");
    if (!title.isEmpty()) ops.text(title, 0, y + 16, w, 10, 0x404040, false, "left");
  }

  private static void object(Ops ops, Drawing d, float w, float h) {
    ops.fill(rect(0, 0, w, h), 0xf2f2f2, 0xa6a6a6, .75f);
    float s = Math.min(w, h) * .45f;
    float[] page = {w / 2 - s / 2, h * .12f, w / 2 + s / 4, h * .12f, w / 2 + s / 2, h * .12f + s / 4, w / 2 + s / 2, h * .12f + s, w / 2 - s / 2, h * .12f + s};
    ops.fill(page, 0xffffff, 0x7f7f7f, 1);
    ops.text(d.get("name", "Object"), 2, h * .12f + s + 4, w - 4, 9, 0x404040, false, "center");
  }
}
