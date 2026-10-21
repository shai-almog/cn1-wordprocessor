package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/**
 * OpenDocument Text. Import translates content.xml and styles.xml into WordprocessingML and
 * reads that with the DOCX reader; export translates the DOCX writer's package into ODF, so
 * both directions share the DOCX model mapping.
 */
public final class Odt {
  static final String MIME = "application/vnd.oasis.opendocument.text";
  private static final String W =
      "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
          + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
          + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
          + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
          + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"";
  private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
  private static final String[][] HIGHLIGHTS = {
    {"yellow", "FFFF00"}, {"green", "00FF00"}, {"cyan", "00FFFF"}, {"magenta", "FF00FF"},
    {"blue", "0000FF"}, {"red", "FF0000"}, {"darkBlue", "000080"}, {"darkCyan", "008080"},
    {"darkGreen", "008000"}, {"darkMagenta", "800080"}, {"darkRed", "800000"},
    {"darkYellow", "808000"}, {"darkGray", "808080"}, {"lightGray", "C0C0C0"},
    {"black", "000000"}, {"white", "FFFFFF"}
  };

  private Odt() {}

  public static Docx.Opened read(InputStream input) throws IOException {
    Map<String, byte[]> parts;
    try {
      parts = ZipParts.read(input);
    } catch (RuntimeException e) {
      throw new IOException("Not an OpenDocument package: " + e.getMessage());
    }
    if (parts.get("content.xml") == null) throw new IOException("Not an OpenDocument text file");
    var mime = parts.get("mimetype");
    if (mime != null && new String(mime, "UTF-8").indexOf("opendocument.text") < 0)
      throw new IOException("Not an OpenDocument text file");
    try {
      var docx = new Importer(parts).convert();
      var bytes = new ByteArrayOutputStream();
      ZipParts.write(docx, bytes);
      return Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    } catch (RuntimeException e) {
      throw new IOException("Malformed OpenDocument text: " + e);
    }
  }

  public static void write(Document d, OutputStream out) throws IOException {
    var docx = new ByteArrayOutputStream();
    Docx.write(d, null, docx, true);
    Map<String, byte[]> parts = ZipParts.read(new ByteArrayInputStream(docx.toByteArray()));
    ZipParts.write(new Exporter(parts).convert(), out, "mimetype");
  }

  // ---- shared helpers ----

  /** An ODF length in points, or the fallback for percentages, junk and absurd values. */
  static float points(String v, float fallback) {
    if (v == null) return fallback;
    v = v.trim();
    int i = v.length();
    while (i > 0 && (v.charAt(i - 1) >= 'a' && v.charAt(i - 1) <= 'z' || v.charAt(i - 1) == '%')) i--;
    if (i == 0) return fallback;
    float n;
    try {
      n = Float.parseFloat(v.substring(0, i));
    } catch (NumberFormatException e) {
      return fallback;
    }
    String unit = v.substring(i);
    float scale =
        unit.equals("cm") ? 72 / 2.54f : unit.equals("mm") ? 72 / 25.4f : unit.equals("in") ? 72
            : unit.equals("pt") || unit.isEmpty() ? 1 : unit.equals("pc") ? 12
            : unit.equals("px") ? .75f : Float.NaN;
    float r = n * scale;
    return Float.isNaN(r) || Math.abs(r) > 100000 ? fallback : r;
  }

  static String num(double x) {
    long r = Math.round(x * 100);
    var b = new StringBuilder();
    if (r < 0) {
      b.append('-');
      r = -r;
    }
    b.append(r / 100);
    long f = r % 100;
    if (f != 0) b.append('.').append(f / 10).append(f % 10 == 0 ? "" : String.valueOf(f % 10));
    return b.toString();
  }

  private static String pt(double points) {
    return num(points) + "pt";
  }

  private static String a(String v) {
    return Xml.attribute(v);
  }

  /** An ODF border ("0.75pt solid #a0a0a0") as WordprocessingML edge attributes, or null. */
  static String odfBorder(String v) {
    if (v == null || v.isEmpty() || "none".equals(v) || v.indexOf("hidden") >= 0) return null;
    String width = "0.5pt", style = "single", color = "auto";
    var parts = new java.util.ArrayList<String>();
    for (String raw : v.trim().replace('\t', ' ').split(" ")) if (!raw.isEmpty()) parts.add(raw);
    for (String part : parts) {
      if (part.startsWith("#")) {
        String h = hex(part);
        if (h != null) color = h;
      } else if (part.endsWith("pt") || part.endsWith("in") || part.endsWith("cm") || part.endsWith("mm"))
        width = part;
      else if ("double".equals(part)) style = "double";
      else if ("dotted".equals(part)) style = "dotted";
      else if ("dashed".equals(part)) style = "dashed";
    }
    int eighths = Math.max(2, Math.min(96, Math.round(points(width, 0.5f) * 8)));
    return " w:val=\"" + style + "\" w:sz=\"" + eighths + "\" w:space=\"1\" w:color=\"" + color + "\"";
  }

  private static String hex(String color) {
    if (color == null || color.length() != 7 || color.charAt(0) != '#') return null;
    for (int i = 1; i < 7; i++)
      if (Character.digit(color.charAt(i), 16) < 0) return null;
    return color.substring(1).toUpperCase();
  }

  private static String attr(Element e, String name) {
    return e == null ? "" : Xml.attr(e, name, "");
  }

  private static List<Element> kids(Element e) {
    return e == null ? Collections.<Element>emptyList() : Xml.children(e, null);
  }

  private static String extension(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? "png" : path.substring(dot + 1).toLowerCase();
  }

  private static String imageType(String ext) {
    return ext.equals("jpg") || ext.equals("jpeg") ? "image/jpeg" : ext.equals("svg") ? "image/svg+xml"
        : "image/" + ext;
  }

  // ---- import ----

  private static final class Style {
    String name, family, parent, display, list, master;
    int outline;
    boolean automatic;
    Element text, para, cell, column;
  }

  private static final class Importer {
    final Map<String, byte[]> in;
    final Map<String, Style> common = new HashMap<>(), stylesAuto = new HashMap<>(),
        contentAuto = new HashMap<>();
    Map<String, Style> auto = contentAuto;
    final Map<String, Element> listStyles = new HashMap<>(), layouts = new HashMap<>();
    final Map<String, StringBuilder> rels = new LinkedHashMap<>();
    final Map<String, byte[]> out = new LinkedHashMap<>();
    final Map<String, Integer> bookmarks = new HashMap<>(), lastNum = new HashMap<>();
    final StringBuilder footnotes = new StringBuilder(), endnotes = new StringBuilder(),
        numbering = new StringBuilder(), nums = new StringBuilder();
    final Set<Integer> headings = new TreeSet<>();
    String story = "document", master;
    int relId, noteId, numCount, pictures;
    boolean space, pageBreak;

    Importer(Map<String, byte[]> in) {
      this.in = in;
    }

    Element parse(String name) throws IOException {
      var bytes = in.get(name);
      return bytes == null ? null : Xml.parse(bytes);
    }

    Map<String, byte[]> convert() throws IOException {
      Element content = parse("content.xml"), styles = parse("styles.xml");
      if (!"document-content".equals(Xml.local(content)))
        throw new IOException("Not an OpenDocument content part");
      if (styles != null) {
        collect(Xml.child(styles, "styles"), common, false);
        collect(Xml.child(styles, "automatic-styles"), stylesAuto, true);
      }
      collect(Xml.child(content, "automatic-styles"), contentAuto, true);
      Element body = Xml.child(content, "body"), text = body == null ? null : Xml.child(body, "text");
      if (text == null) throw new IOException("Not an OpenDocument text document");
      var doc = new StringBuilder("<w:document ").append(W).append("><w:body>");
      blocks(text, doc, 0, 0);
      doc.append(section(styles)).append("</w:body></w:document>");
      out.put("word/document.xml", ZipParts.utf8(doc));
      out.put("word/styles.xml", ZipParts.utf8(stylesXml(styles)));
      if (numCount > 0)
        out.put("word/numbering.xml", ZipParts.utf8(
            "<w:numbering " + W + ">" + numbering + nums + "</w:numbering>"));
      if (footnotes.length() > 0)
        out.put("word/footnotes.xml", ZipParts.utf8("<w:footnotes " + W + ">" + footnotes + "</w:footnotes>"));
      if (endnotes.length() > 0)
        out.put("word/endnotes.xml", ZipParts.utf8("<w:endnotes " + W + ">" + endnotes + "</w:endnotes>"));
      meta();
      for (var e : rels.entrySet())
        out.put("word/_rels/" + e.getKey() + ".xml.rels", ZipParts.utf8(
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + e.getValue() + "</Relationships>"));
      return out;
    }

    void collect(Element root, Map<String, Style> into, boolean automatic) {
      for (Element e : kids(root)) {
        String kind = Xml.local(e);
        if ("list-style".equals(kind)) listStyles.put(attr(e, "style:name"), e);
        else if ("page-layout".equals(kind)) layouts.put(attr(e, "style:name"), e);
        else if ("style".equals(kind) || "default-style".equals(kind)) {
          var s = new Style();
          s.name = "default-style".equals(kind) ? "" : attr(e, "style:name");
          s.family = attr(e, "style:family");
          s.parent = attr(e, "style:parent-style-name");
          s.display = attr(e, "style:display-name");
          s.list = attr(e, "style:list-style-name");
          s.master = attr(e, "style:master-page-name");
          s.outline = Xml.integer(attr(e, "style:default-outline-level"), 0);
          s.automatic = automatic;
          s.text = Xml.child(e, "text-properties");
          s.para = Xml.child(e, "paragraph-properties");
          s.cell = Xml.child(e, "table-cell-properties");
          s.column = Xml.child(e, "table-column-properties");
          into.put(s.family + ":" + s.name, s);
        }
      }
    }

    Style find(String family, String name) {
      if (name.isEmpty()) return null;
      var s = auto.get(family + ":" + name);
      return s != null ? s : common.get(family + ":" + name);
    }

    static String id(String name) {
      if (name == null || name.isEmpty() || "Standard".equals(name)) return "Normal";
      var b = new StringBuilder();
      String n = name.replace("_20_", "");
      for (int i = 0; i < n.length(); i++) {
        char c = n.charAt(i);
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') b.append(c);
      }
      return b.length() == 0 ? "S" + Math.abs(name.hashCode()) : b.toString();
    }

    String rel(String type, String target, boolean external) {
      var b = rels.get(story);
      if (b == null) rels.put(story, b = new StringBuilder());
      String id = "rIdO" + ++relId;
      b.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(REL).append(type)
          .append("\" Target=\"").append(a(target)).append('"')
          .append(external ? " TargetMode=\"External\"/>" : "/>");
      return id;
    }

    // -- blocks --

    void blocks(Element parent, StringBuilder b, int numId, int level) throws IOException {
      for (Element e : kids(parent)) {
        String k = Xml.local(e);
        if ("p".equals(k)) paragraph(e, b, numId, level, 0);
        else if ("h".equals(k)) paragraph(e, b, numId, level, Math.max(1, Math.min(9,
            Xml.integer(attr(e, "text:outline-level"), 1))));
        else if ("list".equals(k)) list(e, b, numId, level);
        else if ("table".equals(k)) table(e, b);
        else if (k.endsWith("-source") || k.endsWith("-decls") || "tracked-changes".equals(k)
            || "forms".equals(k) || "soft-page-break".equals(k) || "annotation".equals(k)) continue;
        else blocks(e, b, numId, level);
      }
    }

    void list(Element e, StringBuilder b, int numId, int level) throws IOException {
      if (level == 0 || numId == 0) {
        String style = attr(e, "text:style-name");
        boolean continues = "true".equals(attr(e, "text:continue-numbering"))
            || !attr(e, "text:continue-list").isEmpty();
        Integer last = lastNum.get(style);
        numId = continues && last != null ? last : number(style);
        lastNum.put(style, numId);
      }
      for (Element item : kids(e)) {
        boolean header = "list-header".equals(Xml.local(item));
        if (!header && !"list-item".equals(Xml.local(item))) continue;
        boolean first = !header;
        for (Element c : kids(item)) {
          String k = Xml.local(c);
          if ("list".equals(k)) list(c, b, numId, level + 1);
          else if ("p".equals(k) || "h".equals(k)) {
            int outline = "h".equals(k) ? Math.max(1, Math.min(9, Xml.integer(attr(c, "text:outline-level"), 1))) : 0;
            paragraph(c, b, first ? numId : 0, level, outline);
            first = false;
          } else if ("table".equals(k)) table(c, b);
        }
      }
    }

    int number(String styleName) {
      int id = ++numCount;
      Element style = listStyles.get(styleName);
      numbering.append("<w:abstractNum w:abstractNumId=\"").append(id).append("\">");
      var levels = new Element[10];
      for (Element l : kids(style)) {
        int n = Xml.integer(attr(l, "text:level"), 0);
        if (n >= 1 && n <= 9 && levels[n] == null) levels[n] = l;
      }
      for (int n = 1; n <= 9; n++) {
        Element l = levels[n];
        String kind = l == null ? "list-level-style-bullet" : Xml.local(l);
        String fmt, text;
        int start = 1;
        if ("list-level-style-number".equals(kind)) {
          String f = attr(l, "style:num-format");
          fmt = f.equals("1") ? "decimal" : f.equals("a") ? "lowerLetter" : f.equals("A") ? "upperLetter"
              : f.equals("i") ? "lowerRoman" : f.equals("I") ? "upperRoman" : f.isEmpty() ? "none" : "decimal";
          int shown = Math.max(1, Math.min(n, Xml.integer(attr(l, "text:display-levels"), 1)));
          var t = new StringBuilder(attr(l, "style:num-prefix"));
          for (int k = n - shown + 1; k <= n; k++) t.append(k > n - shown + 1 ? "." : "").append('%').append(k);
          text = "none".equals(fmt) ? attr(l, "style:num-prefix") + attr(l, "style:num-suffix")
              : t.append(attr(l, "style:num-suffix")).toString();
          start = Math.max(0, Xml.integer(attr(l, "text:start-value"), 1));
        } else {
          fmt = "bullet";
          text = l == null || attr(l, "text:bullet-char").isEmpty() ? "\u2022" : attr(l, "text:bullet-char");
        }
        float left = 36 * n, hanging = 18;
        Element props = l == null ? null : Xml.child(l, "list-level-properties");
        Element align = props == null ? null : Xml.child(props, "list-level-label-alignment");
        if (align != null) {
          left = points(attr(align, "fo:margin-left"), left);
          hanging = -points(attr(align, "fo:text-indent"), -hanging);
        } else if (props != null && !attr(props, "text:min-label-width").isEmpty()) {
          hanging = points(attr(props, "text:min-label-width"), hanging);
          left = points(attr(props, "text:space-before"), 0) + hanging;
        }
        numbering.append("<w:lvl w:ilvl=\"").append(n - 1).append("\"><w:start w:val=\"").append(start)
            .append("\"/><w:numFmt w:val=\"").append(fmt).append("\"/><w:lvlText w:val=\"")
            .append(a(text)).append("\"/><w:pPr><w:ind w:left=\"").append(Math.round(left * 20))
            .append("\" w:hanging=\"").append(Math.round(hanging * 20)).append("\"/></w:pPr></w:lvl>");
      }
      numbering.append("</w:abstractNum>");
      nums.append("<w:num w:numId=\"").append(id).append("\"><w:abstractNumId w:val=\"").append(id)
          .append("\"/></w:num>");
      return id;
    }

    void paragraph(Element e, StringBuilder b, int numId, int level, int outline) throws IOException {
      String name = attr(e, "text:style-name");
      Style s = find("paragraph", name), direct = null;
      String style = null;
      if (s != null && s.automatic) {
        direct = s;
        style = s.parent.isEmpty() ? null : s.parent;
        if (master == null && !s.master.isEmpty()) master = s.master;
      } else if (s != null) style = name;
      if (master == null && s != null && !s.automatic && !s.master.isEmpty()) master = s.master;
      String styleId = style == null ? null : id(style);
      if (outline > 0 && (styleId == null || !styleId.startsWith("Heading"))) {
        styleId = "Heading" + outline;
        headings.add(outline);
      }
      b.append("<w:p><w:pPr>");
      if (styleId != null) b.append("<w:pStyle w:val=\"").append(a(styleId)).append("\"/>");
      if (numId > 0)
        b.append("<w:numPr><w:ilvl w:val=\"").append(Math.min(8, level)).append("\"/><w:numId w:val=\"")
            .append(numId).append("\"/></w:numPr>");
      if (pageBreak) b.append("<w:pageBreakBefore/>");
      pageBreak = false;
      var base = new LinkedHashMap<String, String>();
      if (direct != null) {
        b.append(paragraphProperties(direct.para));
        textProperties(direct.text, base);
        if (direct.para != null && "page".equals(attr(direct.para, "fo:break-after"))) pageBreak = true;
      }
      b.append("</w:pPr>");
      space = true;
      inline(e, b, base, null);
      b.append("</w:p>");
    }

    static String paragraphProperties(Element p) {
      if (p == null) return "";
      var b = new StringBuilder();
      String align = attr(p, "fo:text-align");
      if (!align.isEmpty())
        b.append("<w:jc w:val=\"").append(align.equals("center") ? "center" : align.equals("justify") ? "both"
            : align.equals("end") || align.equals("right") ? "right" : "left").append("\"/>");
      String left = attr(p, "fo:margin-left"), right = attr(p, "fo:margin-right"),
          indent = attr(p, "fo:text-indent");
      if (!left.isEmpty() || !right.isEmpty() || !indent.isEmpty()) {
        b.append("<w:ind");
        if (!left.isEmpty()) b.append(" w:left=\"").append(Math.round(points(left, 0) * 20)).append('"');
        if (!right.isEmpty()) b.append(" w:right=\"").append(Math.round(points(right, 0) * 20)).append('"');
        float first = points(indent, 0);
        if (!indent.isEmpty())
          b.append(first < 0 ? " w:hanging=\"" : " w:firstLine=\"").append(Math.round(Math.abs(first) * 20)).append('"');
        b.append("/>");
      }
      String top = attr(p, "fo:margin-top"), bottom = attr(p, "fo:margin-bottom"),
          line = attr(p, "fo:line-height"), least = attr(p, "style:line-height-at-least");
      if (!top.isEmpty() || !bottom.isEmpty() || !line.isEmpty() || !least.isEmpty()) {
        b.append("<w:spacing");
        if (!top.isEmpty()) b.append(" w:before=\"").append(Math.round(points(top, 0) * 20)).append('"');
        if (!bottom.isEmpty()) b.append(" w:after=\"").append(Math.round(points(bottom, 0) * 20)).append('"');
        if (line.endsWith("%")) {
          float pct = points(line.substring(0, line.length() - 1), 100);
          b.append(" w:line=\"").append(Math.round(Math.max(6, Math.min(1000, pct)) * 2.4f)).append("\" w:lineRule=\"auto\"");
        } else if (!line.isEmpty() && !"normal".equals(line))
          b.append(" w:line=\"").append(Math.round(points(line, 12) * 20)).append("\" w:lineRule=\"exact\"");
        else if (!least.isEmpty())
          b.append(" w:line=\"").append(Math.round(points(least, 12) * 20)).append("\" w:lineRule=\"atLeast\"");
        b.append("/>");
      }
      String keep = attr(p, "fo:keep-with-next"), together = attr(p, "fo:keep-together");
      if (!keep.isEmpty()) b.append("always".equals(keep) ? "<w:keepNext/>" : "<w:keepNext w:val=\"0\"/>");
      if (!together.isEmpty())
        b.append("always".equals(together) ? "<w:keepLines/>" : "<w:keepLines w:val=\"0\"/>");
      if ("0".equals(attr(p, "fo:widows")) && "0".equals(attr(p, "fo:orphans")))
        b.append("<w:widowControl w:val=\"0\"/>");
      if ("page".equals(attr(p, "fo:break-before"))) b.append("<w:pageBreakBefore/>");
      // Paragraph borders: Wordcraft reads fo:border-bottom as a rule under the paragraph.
      var borders = new StringBuilder();
      for (String side : new String[] {"top", "left", "bottom", "right"}) {
        String v = attr(p, "fo:border-" + side);
        if (v.isEmpty()) v = attr(p, "fo:border");
        String edge = odfBorder(v);
        if (edge != null) borders.append("<w:").append(side).append(edge).append("/>");
      }
      if (borders.length() > 0) b.append("<w:pBdr>").append(borders).append("</w:pBdr>");
      String fill = hex(attr(p, "fo:background-color"));
      if (fill != null) b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>");
      if (attr(p, "style:writing-mode").startsWith("rl")) b.append("<w:bidi/>");
      return b.toString();
    }

    static void textProperties(Element t, Map<String, String> into) {
      if (t == null) return;
      String v = attr(t, "style:font-name");
      if (v.isEmpty()) v = attr(t, "fo:font-family");
      v = v.replace("'", "").replace("\"", "").trim();
      if (!v.isEmpty())
        into.put("rFonts", "<w:rFonts w:ascii=\"" + a(v) + "\" w:hAnsi=\"" + a(v) + "\" w:cs=\"" + a(v) + "\"/>");
      v = attr(t, "fo:font-weight");
      if (!v.isEmpty()) into.put("b", "bold".equals(v) || Xml.integer(v, 400) >= 600 ? "<w:b/>" : "<w:b w:val=\"0\"/>");
      v = attr(t, "fo:font-style");
      if (!v.isEmpty()) into.put("i", "normal".equals(v) ? "<w:i w:val=\"0\"/>" : "<w:i/>");
      v = attr(t, "style:text-underline-style");
      if (!v.isEmpty())
        into.put("u", "<w:u w:val=\"" + ("none".equals(v) ? "none"
            : "double".equals(attr(t, "style:text-underline-type")) ? "double" : "single") + "\"/>");
      v = attr(t, "style:text-line-through-style");
      if (!v.isEmpty()) {
        boolean on = !"none".equals(v), dbl = "double".equals(attr(t, "style:text-line-through-type"));
        into.put("strike", on && !dbl ? "<w:strike/>" : "<w:strike w:val=\"0\"/>");
        if (dbl || !on) into.put("dstrike", on ? "<w:dstrike/>" : "<w:dstrike w:val=\"0\"/>");
      }
      v = attr(t, "fo:font-size");
      if (!v.isEmpty() && !v.endsWith("%")) {
        float size = points(v, 0);
        if (size > 0 && size < 1640) into.put("sz", "<w:sz w:val=\"" + Math.round(size * 2) + "\"/>");
      }
      v = attr(t, "fo:color");
      if (hex(v) != null) into.put("color", "<w:color w:val=\"" + hex(v) + "\"/>");
      v = attr(t, "fo:background-color");
      if (hex(v) != null) into.put("shd", "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + hex(v) + "\"/>");
      v = attr(t, "fo:text-transform");
      if (!v.isEmpty()) into.put("caps", "uppercase".equals(v) ? "<w:caps/>" : "<w:caps w:val=\"0\"/>");
      v = attr(t, "fo:font-variant");
      if (!v.isEmpty()) into.put("smallCaps", "small-caps".equals(v) ? "<w:smallCaps/>" : "<w:smallCaps w:val=\"0\"/>");
      v = attr(t, "text:display");
      if (!v.isEmpty()) into.put("vanish", "none".equals(v) ? "<w:vanish/>" : "<w:vanish w:val=\"0\"/>");
      v = attr(t, "fo:letter-spacing");
      if (!v.isEmpty())
        into.put("spacing", "<w:spacing w:val=\"" + Math.round(points(v, 0) * 20) + "\"/>");
      v = attr(t, "style:text-position").trim();
      if (!v.isEmpty()) {
        String first = v.indexOf(' ') < 0 ? v : v.substring(0, v.indexOf(' '));
        float raise = "super".equals(first) ? 1 : "sub".equals(first) ? -1
            : points(first.endsWith("%") ? first.substring(0, first.length() - 1) : first, 0);
        into.put("vertAlign", "<w:vertAlign w:val=\""
            + (raise > 0 ? "superscript" : raise < 0 ? "subscript" : "baseline") + "\"/>");
      }
    }

    // -- inline content --

    String props(Map<String, String> p, String rStyle) {
      if (p.isEmpty() && rStyle == null) return "";
      var b = new StringBuilder("<w:rPr>");
      if (rStyle != null) b.append("<w:rStyle w:val=\"").append(a(rStyle)).append("\"/>");
      for (String v : p.values()) b.append(v);
      return b.append("</w:rPr>").toString();
    }

    void run(StringBuilder b, Map<String, String> p, String rStyle, String inner) {
      b.append("<w:r>").append(props(p, rStyle)).append(inner).append("</w:r>");
    }

    void inline(Element e, StringBuilder b, Map<String, String> p, String rStyle) throws IOException {
      for (int i = 0; i < e.getNumChildren(); i++) {
        Element c = e.getChildAt(i);
        if (c.isTextElement()) {
          var t = new StringBuilder();
          String s = c.getText();
          for (int j = 0; j < s.length(); j++) {
            char ch = s.charAt(j);
            boolean white = ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r';
            if (white) {
              if (!space) t.append(' ');
              space = true;
            } else {
              t.append(ch);
              space = false;
            }
          }
          if (t.length() > 0)
            run(b, p, rStyle, "<w:t xml:space=\"preserve\">" + Xml.escape(t.toString()) + "</w:t>");
          continue;
        }
        String k = Xml.local(c);
        if ("span".equals(k)) {
          String name = attr(c, "text:style-name");
          Style s = find("text", name);
          var merged = new LinkedHashMap<String, String>(p);
          String style = rStyle;
          if (s != null && s.automatic) {
            textProperties(s.text, merged);
            if (!s.parent.isEmpty()) style = id(s.parent);
          } else if (s != null) style = id(name);
          inline(c, b, merged, style);
        } else if ("s".equals(k)) {
          int n = Math.max(1, Math.min(1000, Xml.integer(attr(c, "text:c"), 1)));
          var t = new StringBuilder();
          for (int j = 0; j < n; j++) t.append(' ');
          run(b, p, rStyle, "<w:t xml:space=\"preserve\">" + t + "</w:t>");
          space = false;
        } else if ("tab".equals(k)) {
          run(b, p, rStyle, "<w:tab/>");
          space = false;
        } else if ("line-break".equals(k)) {
          run(b, p, rStyle, "<w:br/>");
          space = true;
        } else if ("a".equals(k)) {
          String href = attr(c, "xlink:href");
          if (href.startsWith("#")) b.append("<w:hyperlink w:anchor=\"").append(a(href.substring(1))).append("\">");
          else if (!href.isEmpty())
            b.append("<w:hyperlink r:id=\"").append(rel("hyperlink", href, true)).append("\">");
          inline(c, b, p, rStyle);
          if (!href.isEmpty()) b.append("</w:hyperlink>");
        } else if ("bookmark".equals(k) || "bookmark-start".equals(k)) {
          String name = attr(c, "text:name");
          int id = bookmarks.size() + 1;
          bookmarks.put(name, id);
          b.append("<w:bookmarkStart w:id=\"").append(id).append("\" w:name=\"").append(a(name)).append("\"/>");
          if ("bookmark".equals(k)) b.append("<w:bookmarkEnd w:id=\"").append(id).append("\"/>");
        } else if ("bookmark-end".equals(k)) {
          Integer id = bookmarks.get(attr(c, "text:name"));
          if (id != null) b.append("<w:bookmarkEnd w:id=\"").append(id).append("\"/>");
        } else if ("note".equals(k)) note(c, b, p, rStyle);
        else if ("frame".equals(k)) frame(c, b);
        else if ("annotation".equals(k) || "annotation-end".equals(k) || k.startsWith("change")
            || "soft-page-break".equals(k) || "note-citation".equals(k)) continue;
        else inline(c, b, p, rStyle);
      }
    }

    void note(Element c, StringBuilder b, Map<String, String> p, String rStyle) throws IOException {
      boolean end = "endnote".equals(attr(c, "text:note-class"));
      String kind = end ? "endnote" : "footnote";
      int id = ++noteId;
      var body = new StringBuilder();
      String saved = story;
      boolean savedSpace = space, savedBreak = pageBreak;
      story = kind + "s";
      pageBreak = false;
      blocks(Xml.child(c, "note-body"), body, 0, 0);
      story = saved;
      space = savedSpace;
      pageBreak = savedBreak;
      if (body.length() == 0) body.append("<w:p/>");
      (end ? endnotes : footnotes).append("<w:").append(kind).append(" w:id=\"").append(id).append("\">")
          .append(body).append("</w:").append(kind).append('>');
      var mark = new LinkedHashMap<String, String>(p);
      mark.put("vertAlign", "<w:vertAlign w:val=\"superscript\"/>");
      run(b, mark, rStyle, "<w:" + kind + "Reference w:id=\"" + id + "\"/>");
      space = false;
    }

    void frame(Element f, StringBuilder b) throws IOException {
      Element image = Xml.child(f, "image");
      if (image == null) return;
      String href = attr(image, "xlink:href");
      if (href.startsWith("./")) href = href.substring(2);
      byte[] data = in.get(href);
      if (data == null) return;
      String ext = extension(href);
      String media = "media/odt" + ++pictures + "." + ext;
      out.put("word/" + media, data);
      String rid = rel("image", media, false);
      float w = points(attr(f, "svg:width"), 72), h = points(attr(f, "svg:height"), 72);
      Element desc = Xml.child(f, "desc"), title = Xml.child(f, "title");
      String alt = desc != null ? Xml.text(desc) : title != null ? Xml.text(title) : "";
      long cx = Math.round(Math.max(1, w) * 12700), cy = Math.round(Math.max(1, h) * 12700);
      b.append("<w:r><w:drawing><wp:inline><wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy)
          .append("\"/><wp:docPr id=\"").append(pictures).append("\" name=\"Picture ").append(pictures)
          .append("\" descr=\"").append(a(alt)).append("\"/><a:graphic><a:graphicData")
          .append(" uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:blipFill>")
          .append("<a:blip r:embed=\"").append(rid).append("\"/></pic:blipFill><pic:spPr><a:xfrm>")
          .append("<a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy)
          .append("\"/></a:xfrm></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>");
      space = false;
    }

    // -- tables --

    void table(Element t, StringBuilder b) throws IOException {
      var widths = new ArrayList<Float>();
      var rows = new ArrayList<Element>();
      tableParts(t, widths, rows);
      int columns = widths.size();
      for (Element row : rows) {
        int n = 0;
        for (Element c : kids(row))
          if (Xml.local(c).endsWith("table-cell"))
            n += Math.max(1, Math.min(64, Xml.integer(attr(c, "table:number-columns-repeated"), 1)));
        columns = Math.max(columns, n);
      }
      if (columns == 0) return;
      b.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid>");
      for (int i = 0; i < columns; i++) {
        float w = i < widths.size() && widths.get(i) > 0 ? widths.get(i) : 468f / columns;
        b.append("<w:gridCol w:w=\"").append(Math.round(w * 20)).append("\"/>");
      }
      b.append("</w:tblGrid>");
      int[] remaining = new int[columns], width = new int[columns];
      for (Element row : rows) {
        b.append("<w:tr>");
        int col = 0, skip = 0;
        for (Element c : kids(row)) {
          String k = Xml.local(c);
          if (!k.endsWith("table-cell")) continue;
          int repeat = Math.max(1, Math.min(64, Xml.integer(attr(c, "table:number-columns-repeated"), 1)));
          for (int r = 0; r < repeat && col < columns; r++) {
            if ("covered-table-cell".equals(k)) {
              if (skip > 0) {
                skip--;
                continue;
              }
              if (remaining[col] > 0) {
                int span = width[col];
                remaining[col]--;
                b.append("<w:tc><w:tcPr>");
                if (span > 1) b.append("<w:gridSpan w:val=\"").append(span).append("\"/>");
                b.append("<w:vMerge/></w:tcPr><w:p/></w:tc>");
                skip = span - 1;
                col += span;
              } else col++;
              continue;
            }
            skip = 0;
            int span = Math.max(1, Math.min(columns - col, Xml.integer(attr(c, "table:number-columns-spanned"), 1)));
            int down = Math.max(1, Math.min(1000, Xml.integer(attr(c, "table:number-rows-spanned"), 1)));
            b.append("<w:tc><w:tcPr>");
            float w = 0;
            for (int i = col; i < col + span && i < widths.size(); i++) w += widths.get(i);
            if (w > 0) b.append("<w:tcW w:w=\"").append(Math.round(w * 20)).append("\" w:type=\"dxa\"/>");
            if (span > 1) b.append("<w:gridSpan w:val=\"").append(span).append("\"/>");
            if (down > 1) {
              b.append("<w:vMerge w:val=\"restart\"/>");
              remaining[col] = down - 1;
              width[col] = span;
            }
            Style s = find("table-cell", attr(c, "table:style-name"));
            String fill = s == null ? null : hex(attr(s.cell, "fo:background-color"));
            if (fill != null) b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>");
            b.append("</w:tcPr>");
            int before = b.length();
            blocks(c, b, 0, 0);
            if (b.length() == before || b.charAt(b.length() - 2) == 'l' && b.charAt(b.length() - 4) == 't' && b.charAt(b.length() - 5) == ':') b.append("<w:p/>");
            b.append("</w:tc>");
            skip = span - 1;
            col += span;
          }
        }
        b.append("</w:tr>");
      }
      b.append("</w:tbl>");
    }

    void tableParts(Element e, List<Float> widths, List<Element> rows) {
      for (Element c : kids(e)) {
        String k = Xml.local(c);
        if ("table-column".equals(k)) {
          Style s = find("table-column", attr(c, "table:style-name"));
          float w = s == null ? 0 : points(attr(s.column, "style:column-width"), 0);
          int repeat = Math.max(1, Math.min(64, Xml.integer(attr(c, "table:number-columns-repeated"), 1)));
          for (int i = 0; i < repeat && widths.size() < 64; i++) widths.add(w);
        } else if ("table-row".equals(k)) {
          int repeat = Math.max(1, Math.min(100, Xml.integer(attr(c, "table:number-rows-repeated"), 1)));
          for (int i = 0; i < repeat && rows.size() < 10000; i++) rows.add(c);
        } else if (k.startsWith("table-") && !"table-cell".equals(k)) tableParts(c, widths, rows);
      }
    }

    // -- page, styles, metadata --

    String section(Element styles) throws IOException {
      Element masters = styles == null ? null : Xml.child(styles, "master-styles"), page = null;
      for (Element m : kids(masters)) {
        if (!"master-page".equals(Xml.local(m))) continue;
        String name = attr(m, "style:name");
        if (page == null || name.equals(master) || master == null && "Standard".equals(name)
            && !attr(page, "style:name").equals(master)) page = m;
      }
      Element layout = page == null ? null : layouts.get(attr(page, "style:page-layout-name"));
      Element props = layout == null ? null : Xml.child(layout, "page-layout-properties");
      float width = points(attr(props, "fo:page-width"), 612), height = points(attr(props, "fo:page-height"), 792);
      width = Math.max(72, Math.min(1584, width));
      height = Math.max(72, Math.min(1584, height));
      float top = points(attr(props, "fo:margin-top"), 72), bottom = points(attr(props, "fo:margin-bottom"), 72),
          left = points(attr(props, "fo:margin-left"), 72), right = points(attr(props, "fo:margin-right"), 72);
      float headerAt = 36, footerAt = 36;
      var b = new StringBuilder("<w:sectPr>");
      auto = stylesAuto;
      for (String kind : new String[] {"header", "footer"}) {
        Element part = page == null ? null : Xml.child(page, kind);
        if (part == null || "false".equals(attr(part, "style:display"))) continue;
        Element style = layout == null ? null : Xml.child(layout, kind + "-style");
        Element hf = style == null ? null : Xml.child(style, "header-footer-properties");
        float extent = points(attr(hf, "fo:min-height"), points(attr(hf, "svg:height"), 0))
            + points(attr(hf, "header".equals(kind) ? "fo:margin-bottom" : "fo:margin-top"), 0);
        if ("header".equals(kind)) {
          headerAt = top;
          top += extent;
        } else {
          footerAt = bottom;
          bottom += extent;
        }
        String saved = story;
        story = kind + "1";
        var xml = new StringBuilder("<w:").append("header".equals(kind) ? "hdr " : "ftr ").append(W).append('>');
        int before = xml.length();
        blocks(part, xml, 0, 0);
        if (xml.length() == before) xml.append("<w:p/>");
        xml.append("</w:").append("header".equals(kind) ? "hdr>" : "ftr>");
        out.put("word/" + kind + "1.xml", ZipParts.utf8(xml));
        story = saved;
        b.append("<w:").append(kind).append("Reference w:type=\"default\" r:id=\"")
            .append(rel(kind, kind + "1.xml", false)).append("\"/>");
      }
      auto = contentAuto;
      b.append("<w:pgSz w:w=\"").append(Math.round(width * 20)).append("\" w:h=\"").append(Math.round(height * 20))
          .append('"');
      if ("landscape".equals(attr(props, "style:print-orientation"))) b.append(" w:orient=\"landscape\"");
      b.append("/><w:pgMar w:top=\"").append(Math.round(top * 20)).append("\" w:right=\"")
          .append(Math.round(right * 20)).append("\" w:bottom=\"").append(Math.round(bottom * 20))
          .append("\" w:left=\"").append(Math.round(left * 20)).append("\" w:header=\"")
          .append(Math.round(headerAt * 20)).append("\" w:footer=\"").append(Math.round(footerAt * 20))
          .append("\" w:gutter=\"0\"/></w:sectPr>");
      return b.toString();
    }

    StringBuilder stylesXml(Element styles) {
      var b = new StringBuilder("<w:styles ").append(W).append('>');
      Style defaults = common.get("paragraph:");
      if (defaults != null) {
        var t = new LinkedHashMap<String, String>();
        textProperties(defaults.text, t);
        b.append("<w:docDefaults><w:rPrDefault>").append(props(t, null)).append("</w:rPrDefault><w:pPrDefault><w:pPr>")
            .append(paragraphProperties(defaults.para)).append("</w:pPr></w:pPrDefault></w:docDefaults>");
      }
      var defined = new HashSet<String>();
      boolean normal = false;
      for (Style s : common.values()) {
        boolean paragraph = "paragraph".equals(s.family);
        if (s.name.isEmpty() || !paragraph && !"text".equals(s.family)) continue;
        String id = id(s.name);
        if (!defined.add(id)) continue;
        normal |= "Normal".equals(id);
        b.append("<w:style w:type=\"").append(paragraph ? "paragraph" : "character").append('"');
        if ("Normal".equals(id)) b.append(" w:default=\"1\"");
        b.append(" w:styleId=\"").append(a(id)).append("\"><w:name w:val=\"")
            .append(a("Normal".equals(id) ? "Normal" : s.display.isEmpty() ? s.name : s.display)).append("\"/>");
        if (!s.parent.isEmpty()) b.append("<w:basedOn w:val=\"").append(a(id(s.parent))).append("\"/>");
        else if (paragraph && !"Normal".equals(id)) b.append("<w:basedOn w:val=\"Normal\"/>");
        int outline = s.outline > 0 ? s.outline : id.startsWith("Heading") ? Xml.integer(id.substring(7), 0) : 0;
        if (paragraph) {
          b.append("<w:pPr>").append(paragraphProperties(s.para));
          if (outline > 0 && outline <= 9) b.append("<w:outlineLvl w:val=\"").append(outline - 1).append("\"/>");
          b.append("</w:pPr>");
        }
        var t = new LinkedHashMap<String, String>();
        textProperties(s.text, t);
        b.append(props(t, null)).append("</w:style>");
      }
      if (!normal) b.append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>");
      for (int level : headings)
        if (!defined.contains("Heading" + level))
          b.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(level).append("\"><w:name w:val=\"heading ")
              .append(level).append("\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:outlineLvl w:val=\"")
              .append(level - 1).append("\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"").append(Math.max(24, 36 - 4 * (level - 1)))
              .append("\"/></w:rPr></w:style>");
      return b.append("</w:styles>");
    }

    void meta() throws IOException {
      Element root = parse("meta.xml"), m = root == null ? null : Xml.child(root, "meta");
      if (m == null) return;
      var b = new StringBuilder("<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
          + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">");
      String[][] map = {{"title", "dc:title"}, {"subject", "dc:subject"}, {"creator", "dc:creator"},
        {"description", "dc:description"}};
      for (String[] f : map) {
        Element e = Xml.child(m, f[0]);
        if ("creator".equals(f[0]) && e == null) e = Xml.child(m, "initial-creator");
        if (e != null) b.append('<').append(f[1]).append('>').append(Xml.escape(Xml.text(e))).append("</").append(f[1]).append('>');
      }
      var words = new StringBuilder();
      for (Element k : Xml.children(m, "keyword")) words.append(words.length() > 0 ? ", " : "").append(Xml.text(k));
      if (words.length() > 0) b.append("<cp:keywords>").append(Xml.escape(words.toString())).append("</cp:keywords>");
      out.put("docProps/core.xml", ZipParts.utf8(b.append("</cp:coreProperties>")));
    }
  }

  // ---- export ----

  private static final class Sink {
    final StringBuilder xml = new StringBuilder();
    final Map<String, String> names = new HashMap<>();
    final String prefix;

    Sink(String prefix) {
      this.prefix = prefix;
    }

    String style(String family, String parent, String body) {
      String key = family + "|" + parent + "|" + body;
      String name = names.get(key);
      if (name == null) {
        name = prefix + (family.equals("paragraph") ? "P" : family.equals("text") ? "T" : "X") + (names.size() + 1);
        names.put(key, name);
        xml.append("<style:style style:name=\"").append(name).append("\" style:family=\"").append(family).append('"');
        if (parent != null) xml.append(" style:parent-style-name=\"").append(a(parent)).append('"');
        xml.append('>').append(body).append("</style:style>");
      }
      return name;
    }
  }

  private static final class Exporter {
    final Map<String, byte[]> in;
    final Map<String, byte[]> out = new LinkedHashMap<>();
    final Map<String, String> rels = new HashMap<>(), pictures = new LinkedHashMap<>(),
        bookmarks = new HashMap<>(), lists = new HashMap<>();
    final Map<String, Element> styles = new HashMap<>(), abstracts = new HashMap<>(), numbers = new HashMap<>(),
        footnotes = new HashMap<>(), endnotes = new HashMap<>();
    final Set<String> fonts = new TreeSet<>(), usedLists = new HashSet<>();
    final Sink content = new Sink(""), master = new Sink("M");
    Sink sink = content;
    String relPart = "word/_rels/document.xml.rels";
    int tables, notes, images;
    boolean space, stripSpace, breakNext;
    // Open list levels: numId and depth of the list structure being written.
    String openList;
    int depth;

    Exporter(Map<String, byte[]> in) {
      this.in = in;
    }

    Element parse(String name) throws IOException {
      var bytes = in.get(name);
      return bytes == null ? null : Xml.parse(bytes);
    }

    void readRels(String part) throws IOException {
      rels.clear();
      Element r = parse(part);
      for (Element e : kids(r)) {
        String target = attr(e, "Target");
        boolean external = "External".equals(attr(e, "TargetMode"));
        rels.put(attr(e, "Id"), external || target.startsWith("#") ? target
            : target.startsWith("/") ? target.substring(1) : "word/" + target);
      }
    }

    Map<String, byte[]> convert() throws IOException {
      Element doc = parse("word/document.xml");
      Element body = Xml.child(doc, "body");
      for (Element s : kids(parse("word/styles.xml")))
        if ("style".equals(Xml.local(s))) styles.put(attr(s, "w:styleId"), s);
      for (Element n : kids(parse("word/numbering.xml"))) {
        if ("abstractNum".equals(Xml.local(n))) abstracts.put(attr(n, "w:abstractNumId"), n);
        else if ("num".equals(Xml.local(n))) numbers.put(attr(n, "w:numId"), n);
      }
      for (String kind : new String[] {"footnote", "endnote"})
        for (Element n : kids(parse("word/" + kind + "s.xml")))
          ("footnote".equals(kind) ? footnotes : endnotes).put(attr(n, "w:id"), n);
      readRels("word/_rels/document.xml.rels");
      var text = new StringBuilder();
      blocks(body, text);
      closeLists(text, 0);
      Element sect = Xml.child(body, "sectPr");
      String page = pageLayout(sect);
      var commonStyles = commonStyles();
      out.put("mimetype", MIME.getBytes("UTF-8"));
      out.put("content.xml", ZipParts.utf8(
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content " + NS
              + " office:version=\"1.3\">" + fontDecls() + "<office:automatic-styles>" + content.xml
              + listStyles() + "</office:automatic-styles><office:body><office:text>" + text
              + "</office:text></office:body></office:document-content>"));
      out.put("styles.xml", ZipParts.utf8(
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles " + NS
              + " office:version=\"1.3\">" + fontDecls() + "<office:styles>" + commonStyles
              + "</office:styles><office:automatic-styles>" + master.xml + page
              + "</office:document-styles>"));
      out.put("meta.xml", ZipParts.utf8(meta()));
      var manifest = new StringBuilder(
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest"
              + " xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.3\">"
              + "<manifest:file-entry manifest:full-path=\"/\" manifest:version=\"1.3\" manifest:media-type=\""
              + MIME + "\"/>");
      for (String part : new String[] {"content.xml", "styles.xml", "meta.xml"})
        manifest.append("<manifest:file-entry manifest:full-path=\"").append(part)
            .append("\" manifest:media-type=\"text/xml\"/>");
      for (var p : pictures.entrySet()) {
        out.put(p.getValue(), in.get(p.getKey()));
        manifest.append("<manifest:file-entry manifest:full-path=\"").append(a(p.getValue()))
            .append("\" manifest:media-type=\"").append(imageType(extension(p.getValue()))).append("\"/>");
      }
      out.put("META-INF/manifest.xml", ZipParts.utf8(manifest.append("</manifest:manifest>")));
      return out;
    }

    static final String NS =
        "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
            + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
            + " xmlns:xlink=\"http://www.w3.org/1999/xlink\""
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
            + " xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
            + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\"";

    String fontDecls() {
      var b = new StringBuilder("<office:font-face-decls>");
      for (String f : fonts)
        b.append("<style:font-face style:name=\"").append(a(f)).append("\" svg:font-family=\"")
            .append(a("'" + f + "'")).append("\"/>");
      return b.append("</office:font-face-decls>").toString();
    }

    static String name(String id) {
      if (id == null || id.isEmpty() || "Normal".equals(id)) return "Standard";
      var b = new StringBuilder();
      for (int i = 0; i < id.length(); i++) {
        char c = id.charAt(i);
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-')
          b.append(c);
      }
      if (b.length() == 0 || b.charAt(0) >= '0' && b.charAt(0) <= '9' || b.charAt(0) == '-') b.insert(0, 'S');
      return b.toString();
    }

    int outline(String styleId) {
      for (int guard = 0; styleId != null && guard < 20; guard++) {
        if (styleId.length() == 8 && styleId.startsWith("Heading") && styleId.charAt(7) >= '1' && styleId.charAt(7) <= '9')
          return styleId.charAt(7) - '0';
        Element s = styles.get(styleId);
        if (s == null) return 0;
        Element p = Xml.child(s, "pPr"), o = p == null ? null : Xml.child(p, "outlineLvl");
        if (o != null) {
          int level = Xml.integer(attr(o, "w:val"), 9) + 1;
          return level >= 1 && level <= 9 ? level : 0;
        }
        Element based = Xml.child(s, "basedOn");
        styleId = based == null ? null : attr(based, "w:val");
      }
      return 0;
    }

    // -- blocks --

    void blocks(Element parent, StringBuilder b) throws IOException {
      for (Element e : kids(parent)) {
        String k = Xml.local(e);
        if ("p".equals(k)) paragraph(e, b);
        else if ("tbl".equals(k)) {
          closeLists(b, 0);
          table(e, b);
        } else if ("sdt".equals(k)) blocks(Xml.child(e, "sdtContent"), b);
        else if ("customXml".equals(k) || "ins".equals(k) || "moveTo".equals(k)) blocks(e, b);
      }
    }

    void closeLists(StringBuilder b, int keep) {
      while (depth > keep) {
        b.append("</text:list-item></text:list>");
        depth--;
      }
      if (depth == 0) openList = null;
    }

    void paragraph(Element p, StringBuilder b) throws IOException {
      Element pPr = Xml.child(p, "pPr");
      Element ps = pPr == null ? null : Xml.child(pPr, "pStyle"), numPr = pPr == null ? null : Xml.child(pPr, "numPr");
      String styleId = ps == null ? "Normal" : attr(ps, "w:val");
      String numId = null;
      int level = 0;
      if (numPr != null) {
        Element n = Xml.child(numPr, "numId"), l = Xml.child(numPr, "ilvl");
        numId = n == null ? null : attr(n, "w:val");
        level = l == null ? 0 : Math.max(0, Math.min(8, Xml.integer(attr(l, "w:val"), 0)));
        if (numId != null && ("0".equals(numId) || !numbers.containsKey(numId))) numId = null;
      }
      if (sink == content && b.length() >= 0) {
        if (numId == null) closeLists(b, 0);
        else {
          if (!numId.equals(openList)) closeLists(b, 0);
          int want = level + 1;
          if (depth >= want) {
            closeLists(b, want);
            b.append("</text:list-item><text:list-item>");
          } else {
            while (depth < want) {
              b.append("<text:list");
              if (depth == 0) {
                b.append(" text:style-name=\"L").append(a(numId)).append('"');
                if (!usedLists.add(numId)) b.append(" text:continue-numbering=\"true\"");
              }
              b.append("><text:list-item>");
              depth++;
            }
          }
          openList = numId;
        }
      }
      var props = new StringBuilder();
      paragraphAttributes(pPr, props);
      if (breakNext && props.toString().indexOf("fo:break-before") < 0) props.append(" fo:break-before=\"page\"");
      breakNext = false;
      String parent = name(styleId), style = parent;
      if (props.length() > 0) style = sink.style("paragraph", parent, "<style:paragraph-properties" + props + "/>");
      int heading = outline(styleId);
      String tag = heading > 0 ? "text:h" : "text:p";
      b.append('<').append(tag).append(" text:style-name=\"").append(a(style)).append('"');
      if (heading > 0) b.append(" text:outline-level=\"").append(heading).append('"');
      b.append('>');
      space = true;
      inline(p, b);
      b.append("</").append(tag).append('>');
    }

    static void paragraphAttributes(Element pPr, StringBuilder b) {
      for (Element e : kids(pPr)) {
        String k = Xml.local(e);
        if ("jc".equals(k)) {
          String v = attr(e, "w:val");
          b.append(" fo:text-align=\"").append(v.equals("center") ? "center" : v.equals("both")
              || v.equals("distribute") ? "justify" : v.equals("right") || v.equals("end") ? "end" : "start").append('"');
        } else if ("ind".equals(k)) {
          String left = Xml.attr(e, "w:left", attr(e, "w:start")), right = Xml.attr(e, "w:right", attr(e, "w:end"));
          if (!left.isEmpty()) b.append(" fo:margin-left=\"").append(pt(Xml.number(left) / 20f)).append('"');
          if (!right.isEmpty()) b.append(" fo:margin-right=\"").append(pt(Xml.number(right) / 20f)).append('"');
          String first = attr(e, "w:firstLine"), hanging = attr(e, "w:hanging");
          if (!hanging.isEmpty()) b.append(" fo:text-indent=\"").append(pt(-Xml.number(hanging) / 20f)).append('"');
          else if (!first.isEmpty()) b.append(" fo:text-indent=\"").append(pt(Xml.number(first) / 20f)).append('"');
        } else if ("spacing".equals(k)) {
          String before = attr(e, "w:before"), after = attr(e, "w:after"), line = attr(e, "w:line");
          if (!before.isEmpty()) b.append(" fo:margin-top=\"").append(pt(Xml.number(before) / 20f)).append('"');
          if (!after.isEmpty()) b.append(" fo:margin-bottom=\"").append(pt(Xml.number(after) / 20f)).append('"');
          if (!line.isEmpty()) {
            String rule = Xml.attr(e, "w:lineRule", "auto");
            float v = Xml.number(line);
            if ("auto".equals(rule)) b.append(" fo:line-height=\"").append(num(v / 2.4f)).append("%\"");
            else if ("exact".equals(rule)) b.append(" fo:line-height=\"").append(pt(v / 20f)).append('"');
            else b.append(" style:line-height-at-least=\"").append(pt(v / 20f)).append('"');
          }
        } else if ("pBdr".equals(k)) {
          // Paragraph borders, as Wordcraft writes its rule as fo:border-bottom.
          for (Element edge : Xml.children(e, null)) {
            String side = Xml.local(edge), val = attr(edge, "w:val");
            if (!("top".equals(side) || "left".equals(side) || "bottom".equals(side) || "right".equals(side))
                || val.isEmpty() || "none".equals(val) || "nil".equals(val)) continue;
            String color = attr(edge, "w:color");
            float width = Xml.number(Xml.attr(edge, "w:sz", "4")) / 8f;
            b.append(" fo:border-").append(side).append("=\"").append(pt(width)).append(' ')
                .append("double".equals(val) ? "double" : "dotted".equals(val) ? "dotted" : "dashed".equals(val) ? "dashed" : "solid")
                .append(" #").append(color.length() == 6 ? color : "000000").append('"');
          }
        } else if ("keepNext".equals(k)) b.append(" fo:keep-with-next=\"").append(on(e) ? "always" : "auto").append('"');
        else if ("keepLines".equals(k)) b.append(" fo:keep-together=\"").append(on(e) ? "always" : "auto").append('"');
        else if ("widowControl".equals(k)) {
          String n = on(e) ? "2" : "0";
          b.append(" fo:widows=\"").append(n).append("\" fo:orphans=\"").append(n).append('"');
        } else if ("pageBreakBefore".equals(k) && on(e)) b.append(" fo:break-before=\"page\"");
        else if ("shd".equals(k)) {
          String fill = fill(e);
          if (fill != null) b.append(" fo:background-color=\"#").append(fill).append('"');
        } else if ("bidi".equals(k) && on(e)) b.append(" style:writing-mode=\"rl-tb\"");
      }
    }

    static boolean on(Element e) {
      String v = attr(e, "w:val");
      return !("0".equals(v) || "false".equals(v) || "off".equals(v) || "none".equals(v));
    }

    static String fill(Element shd) {
      String f = attr(shd, "w:fill");
      if (f.length() != 6 || "auto".equals(f)) return null;
      for (int i = 0; i < 6; i++) if (Character.digit(f.charAt(i), 16) < 0) return null;
      return f.toUpperCase();
    }

    String textAttributes(Element rPr) {
      var b = new StringBuilder();
      for (Element e : kids(rPr)) {
        String k = Xml.local(e);
        if ("rFonts".equals(k)) {
          String f = Xml.attr(e, "w:ascii", attr(e, "w:hAnsi"));
          if (!f.isEmpty()) {
            fonts.add(f);
            b.append(" style:font-name=\"").append(a(f)).append('"');
          }
        } else if ("sz".equals(k)) b.append(" fo:font-size=\"").append(pt(Xml.number(Xml.attr(e, "w:val", "24")) / 2f)).append('"');
        else if ("color".equals(k)) {
          String c = attr(e, "w:val");
          if (c.length() == 6 && !"auto".equals(c)) b.append(" fo:color=\"#").append(c).append('"');
        } else if ("shd".equals(k)) {
          String fill = fill(e);
          if (fill != null) b.append(" fo:background-color=\"#").append(fill).append('"');
        } else if ("highlight".equals(k)) {
          for (String[] h : HIGHLIGHTS)
            if (h[0].equals(attr(e, "w:val"))) b.append(" fo:background-color=\"#").append(h[1]).append('"');
        } else if ("b".equals(k)) b.append(" fo:font-weight=\"").append(on(e) ? "bold" : "normal").append('"');
        else if ("i".equals(k)) b.append(" fo:font-style=\"").append(on(e) ? "italic" : "normal").append('"');
        else if ("u".equals(k)) {
          String v = attr(e, "w:val");
          if ("none".equals(v) || v.isEmpty() && !on(e)) b.append(" style:text-underline-style=\"none\"");
          else {
            b.append(" style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font-color\"");
            if (v.startsWith("double")) b.append(" style:text-underline-type=\"double\"");
          }
        } else if ("strike".equals(k)) b.append(" style:text-line-through-style=\"").append(on(e) ? "solid" : "none").append('"');
        else if ("dstrike".equals(k) && on(e)) b.append(" style:text-line-through-style=\"solid\" style:text-line-through-type=\"double\"");
        else if ("caps".equals(k)) b.append(" fo:text-transform=\"").append(on(e) ? "uppercase" : "none").append('"');
        else if ("smallCaps".equals(k)) b.append(" fo:font-variant=\"").append(on(e) ? "small-caps" : "normal").append('"');
        else if ("vanish".equals(k) && on(e)) b.append(" text:display=\"none\"");
        else if ("spacing".equals(k)) {
          float v = Xml.number(Xml.attr(e, "w:val", "0")) / 20f;
          if (v != 0) b.append(" fo:letter-spacing=\"").append(pt(v)).append('"');
        } else if ("vertAlign".equals(k)) {
          String v = attr(e, "w:val");
          b.append(" style:text-position=\"").append("superscript".equals(v) ? "super 58%"
              : "subscript".equals(v) ? "sub 58%" : "0% 100%").append('"');
        }
      }
      // ODF forbids a strike type before a style; a double strike replaces a plain strike.
      String r = b.toString();
      if (r.indexOf(" style:text-line-through-type") >= 0)
        r = r.replace(" style:text-line-through-style=\"none\"", "");
      return r;
    }

    // -- inline --

    void inline(Element e, StringBuilder b) throws IOException {
      for (Element c : kids(e)) {
        String k = Xml.local(c);
        if ("r".equals(k)) run(c, b);
        else if ("hyperlink".equals(k)) {
          String id = attr(c, "r:id"), anchor = attr(c, "w:anchor"), href = null;
          if (!id.isEmpty()) href = rels.get(id);
          if (href == null && !anchor.isEmpty()) href = "#" + anchor;
          if (href != null) b.append("<text:a xlink:type=\"simple\" xlink:href=\"").append(a(href)).append("\">");
          inline(c, b);
          if (href != null) b.append("</text:a>");
          space = false;
        } else if ("bookmarkStart".equals(k)) {
          String name = attr(c, "w:name");
          if (name.isEmpty() || "_GoBack".equals(name)) continue;
          bookmarks.put(attr(c, "w:id"), name);
          b.append("<text:bookmark-start text:name=\"").append(a(name)).append("\"/>");
        } else if ("bookmarkEnd".equals(k)) {
          String name = bookmarks.remove(attr(c, "w:id"));
          if (name != null) b.append("<text:bookmark-end text:name=\"").append(a(name)).append("\"/>");
        } else if ("ins".equals(k) || "moveTo".equals(k) || "smartTag".equals(k) || "customXml".equals(k)
            || "fldSimple".equals(k) || "sdtContent".equals(k) || "sdt".equals(k)) inline(c, b);
      }
    }

    void run(Element r, StringBuilder b) throws IOException {
      Element rPr = Xml.child(r, "rPr");
      Element rs = rPr == null ? null : Xml.child(rPr, "rStyle");
      String parent = rs == null ? null : name(attr(rs, "w:val"));
      String props = textAttributes(rPr), style = parent;
      if (props.length() > 0) style = sink.style("text", parent, "<style:text-properties" + props + "/>");
      var inner = new StringBuilder();
      for (Element c : kids(r)) {
        String k = Xml.local(c);
        if ("t".equals(k)) text(Xml.text(c), inner);
        else if ("tab".equals(k)) {
          inner.append("<text:tab/>");
          space = false;
        } else if ("br".equals(k)) {
          String type = attr(c, "w:type");
          if ("page".equals(type)) breakNext = true;
          else if (!"column".equals(type)) {
            inner.append("<text:line-break/>");
            space = true;
          }
        } else if ("footnoteRef".equals(k) || "endnoteRef".equals(k)) stripSpace = true;
        else if ("footnoteReference".equals(k) || "endnoteReference".equals(k)) note(c, inner, k.startsWith("end"));
        else if ("drawing".equals(k)) drawing(c, inner);
        else if ("sym".equals(k)) {
          int code = Xml.integer(String.valueOf(Integer.parseInt("0" + digits(attr(c, "w:char")), 16)), 0x20);
          text(String.valueOf((char) code), inner);
        }
      }
      if (inner.length() == 0) return;
      if (style == null) b.append(inner);
      else b.append("<text:span text:style-name=\"").append(a(style)).append("\">").append(inner).append("</text:span>");
    }

    static String digits(String hex) {
      var b = new StringBuilder();
      for (int i = 0; i < hex.length() && b.length() < 4; i++)
        if (Character.digit(hex.charAt(i), 16) >= 0) b.append(hex.charAt(i));
      return b.toString();
    }

    void text(String s, StringBuilder b) {
      if (stripSpace && s.startsWith(" ")) s = s.substring(1);
      stripSpace = false;
      int extra = 0;
      for (int i = 0; i < s.length(); i++) {
        char ch = s.charAt(i);
        if (ch == ' ') {
          if (space) extra++;
          else {
            b.append(' ');
            space = true;
          }
          continue;
        }
        if (extra > 0) {
          spaces(b, extra);
          extra = 0;
        }
        space = false;
        if (ch == '\t') b.append("<text:tab/>");
        else if (ch == '\n') {
          b.append("<text:line-break/>");
          space = true;
        } else if (ch == '&') b.append("&amp;");
        else if (ch == '<') b.append("&lt;");
        else if (ch == '>') b.append("&gt;");
        else if (ch >= 0x20 || ch == '\t') b.append(ch);
      }
      if (extra > 0) spaces(b, extra);
    }

    static void spaces(StringBuilder b, int n) {
      b.append("<text:s");
      if (n > 1) b.append(" text:c=\"").append(n).append('"');
      b.append("/>");
    }

    void note(Element ref, StringBuilder b, boolean end) throws IOException {
      Element n = (end ? endnotes : footnotes).get(attr(ref, "w:id"));
      if (n == null) return;
      int id = ++notes;
      String kind = end ? "endnote" : "footnote";
      b.append("<text:note text:id=\"").append(end ? "edn" : "ftn").append(id).append("\" text:note-class=\"")
          .append(kind).append("\"><text:note-citation>").append(id).append("</text:note-citation><text:note-body>");
      boolean saved = space, savedBreak = breakNext;
      String savedList = openList;
      int savedDepth = depth;
      openList = null;
      depth = 0;
      String savedPart = relPart;
      var savedRels = new HashMap<String, String>(rels);
      readRels("word/_rels/" + kind + "s.xml.rels");
      breakNext = false;
      var body = new StringBuilder();
      blocks(n, body);
      closeLists(body, 0);
      if (body.length() == 0) body.append("<text:p/>");
      b.append(body).append("</text:note-body></text:note>");
      rels.clear();
      rels.putAll(savedRels);
      relPart = savedPart;
      openList = savedList;
      depth = savedDepth;
      space = false;
      breakNext = savedBreak;
      stripSpace = false;
      if (saved) space = false;
    }

    void drawing(Element d, StringBuilder b) {
      Element blip = descendant(d, "blip"), extent = descendant(d, "extent"), pr = descendant(d, "docPr");
      if (blip == null) return;
      String target = rels.get(attr(blip, "r:embed"));
      if (target == null || in.get(target) == null) return;
      String path = pictures.get(target);
      if (path == null) {
        path = "Pictures/image" + (pictures.size() + 1) + "." + extension(target);
        pictures.put(target, path);
      }
      float w = Xml.number(Xml.attr(extent, "cx", "914400")) / 12700f,
          h = Xml.number(Xml.attr(extent, "cy", "914400")) / 12700f;
      String alt = pr == null ? "" : Xml.attr(pr, "descr", "");
      b.append("<draw:frame draw:name=\"Image").append(++images).append("\" text:anchor-type=\"as-char\" svg:width=\"")
          .append(pt(w)).append("\" svg:height=\"").append(pt(h)).append("\" draw:z-index=\"0\"><draw:image xlink:href=\"")
          .append(a(path)).append("\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/>");
      if (!alt.isEmpty()) b.append("<svg:desc>").append(Xml.escape(alt)).append("</svg:desc>");
      b.append("</draw:frame>");
      space = false;
    }

    static Element descendant(Element e, String name) {
      if (e == null) return null;
      for (Element c : kids(e)) {
        if (name.equals(Xml.local(c))) return c;
        Element d = descendant(c, name);
        if (d != null) return d;
      }
      return null;
    }

    // -- tables --

    void table(Element tbl, StringBuilder b) throws IOException {
      int id = ++tables;
      var widths = new ArrayList<Float>();
      for (Element g : kids(Xml.child(tbl, "tblGrid"))) widths.add(Xml.number(Xml.attr(g, "w:w", "0")) / 20f);
      var rows = Xml.children(tbl, "tr");
      // Grid position of every cell, to find how far each vertical merge reaches.
      var grid = new ArrayList<Map<Integer, Element>>();
      int columns = widths.size();
      for (Element tr : rows) {
        var cells = new LinkedHashMap<Integer, Element>();
        int col = 0;
        for (Element tc : Xml.children(tr, "tc")) {
          cells.put(col, tc);
          col += span(tc);
        }
        columns = Math.max(columns, col);
        grid.add(cells);
      }
      while (widths.size() < columns) widths.add(468f / Math.max(1, columns));
      float total = 0;
      for (float w : widths) total += w;
      String tname = "Table" + id;
      content.xml.append("<style:style style:name=\"").append(tname).append("\" style:family=\"table\"><style:table-properties style:width=\"")
          .append(pt(total)).append("\" table:align=\"left\"/></style:style>");
      b.append("<table:table table:name=\"").append(tname).append("\" table:style-name=\"").append(tname).append("\">");
      for (int i = 0; i < columns; i++) {
        String cname = tname + ".C" + (i + 1);
        content.xml.append("<style:style style:name=\"").append(cname).append("\" style:family=\"table-column\"><style:table-column-properties style:column-width=\"")
            .append(pt(widths.get(i))).append("\"/></style:style>");
        b.append("<table:table-column table:style-name=\"").append(cname).append("\"/>");
      }
      for (int r = 0; r < rows.size(); r++) {
        b.append("<table:table-row>");
        int col = 0;
        for (var entry : grid.get(r).entrySet()) {
          Element tc = entry.getValue();
          int start = entry.getKey(), span = span(tc);
          for (; col < start; col++) b.append("<table:covered-table-cell/>");
          Element pr = Xml.child(tc, "tcPr"), vm = pr == null ? null : Xml.child(pr, "vMerge");
          if (vm != null && !"restart".equals(attr(vm, "w:val"))) {
            for (int i = 0; i < span; i++) b.append("<table:covered-table-cell/>");
            col += span;
            continue;
          }
          int down = 1;
          if (vm != null)
            for (int n = r + 1; n < grid.size(); n++) {
              Element below = grid.get(n).get(start);
              Element bp = below == null ? null : Xml.child(below, "tcPr"), bv = bp == null ? null : Xml.child(bp, "vMerge");
              if (bv == null || "restart".equals(attr(bv, "w:val"))) break;
              down++;
            }
          Element shd = pr == null ? null : Xml.child(pr, "shd");
          String fill = shd == null ? null : fill(shd);
          String cs = content.style("table-cell", null, "<style:table-cell-properties fo:padding=\"0.05in\" fo:border=\"0.5pt solid #000000\""
              + (fill == null ? "" : " fo:background-color=\"#" + fill + "\"") + "/>");
          b.append("<table:table-cell table:style-name=\"").append(cs).append("\" office:value-type=\"string\"");
          if (span > 1) b.append(" table:number-columns-spanned=\"").append(span).append('"');
          if (down > 1) b.append(" table:number-rows-spanned=\"").append(down).append('"');
          b.append('>');
          String savedList = openList;
          int savedDepth = depth;
          openList = null;
          depth = 0;
          var cell = new StringBuilder();
          blocks(tc, cell);
          closeLists(cell, 0);
          openList = savedList;
          depth = savedDepth;
          if (cell.length() == 0) cell.append("<text:p/>");
          b.append(cell).append("</table:table-cell>");
          for (int i = 1; i < span; i++) b.append("<table:covered-table-cell/>");
          col += span;
        }
        for (; col < columns; col++) b.append("<table:table-cell office:value-type=\"string\"><text:p/></table:table-cell>");
        b.append("</table:table-row>");
      }
      b.append("</table:table>");
    }

    static int span(Element tc) {
      Element pr = Xml.child(tc, "tcPr"), g = pr == null ? null : Xml.child(pr, "gridSpan");
      return g == null ? 1 : Math.max(1, Math.min(64, Xml.integer(attr(g, "w:val"), 1)));
    }

    // -- styles, lists, page, metadata --

    String commonStyles() throws IOException {
      var b = new StringBuilder();
      Element defaults = null;
      Element root = parse("word/styles.xml");
      if (root != null) defaults = Xml.child(root, "docDefaults");
      if (defaults != null) {
        Element rd = Xml.child(defaults, "rPrDefault"), pd = Xml.child(defaults, "pPrDefault");
        var pp = new StringBuilder();
        paragraphAttributes(pd == null ? null : Xml.child(pd, "pPr"), pp);
        String tp = textAttributes(rd == null ? null : Xml.child(rd, "rPr"));
        b.append("<style:default-style style:family=\"paragraph\"><style:paragraph-properties").append(pp)
            .append("/><style:text-properties").append(tp).append("/></style:default-style>");
      }
      boolean standard = false;
      for (var e : styles.entrySet()) {
        Element s = e.getValue();
        String type = attr(s, "w:type");
        if (!"paragraph".equals(type) && !"character".equals(type)) continue;
        String n = name(e.getKey());
        if ("character".equals(type) && "Standard".equals(n)) continue;
        standard |= "Standard".equals(n);
        Element nm = Xml.child(s, "name"), based = Xml.child(s, "basedOn");
        b.append("<style:style style:name=\"").append(a(n)).append('"');
        if (nm != null && !attr(nm, "w:val").isEmpty()) b.append(" style:display-name=\"").append(a(attr(nm, "w:val"))).append('"');
        b.append(" style:family=\"").append("paragraph".equals(type) ? "paragraph" : "text").append('"');
        if (based != null && !"Standard".equals(n)) b.append(" style:parent-style-name=\"").append(a(name(attr(based, "w:val")))).append('"');
        int level = "paragraph".equals(type) ? outline(e.getKey()) : 0;
        if (level > 0) b.append(" style:default-outline-level=\"").append(level).append('"');
        b.append('>');
        if ("paragraph".equals(type)) {
          var pp = new StringBuilder();
          paragraphAttributes(Xml.child(s, "pPr"), pp);
          if (pp.length() > 0) b.append("<style:paragraph-properties").append(pp).append("/>");
        }
        String tp = textAttributes(Xml.child(s, "rPr"));
        if (tp.length() > 0) b.append("<style:text-properties").append(tp).append("/>");
        b.append("</style:style>");
      }
      if (!standard) b.append("<style:style style:name=\"Standard\" style:family=\"paragraph\"/>");
      return b.toString();
    }

    String listStyles() {
      var b = new StringBuilder();
      for (String numId : usedLists) {
        Element num = numbers.get(numId);
        Element abs = abstracts.get(attr(Xml.child(num, "abstractNumId"), "w:val"));
        var starts = new HashMap<String, Integer>();
        for (Element o : Xml.children(num, "lvlOverride")) {
          Element so = Xml.child(o, "startOverride");
          if (so != null) starts.put(attr(o, "w:ilvl"), Xml.integer(attr(so, "w:val"), 1));
        }
        b.append("<text:list-style style:name=\"L").append(a(numId)).append("\">");
        var levels = new Element[9];
        for (Element l : kids(abs))
          if ("lvl".equals(Xml.local(l))) {
            int i = Xml.integer(attr(l, "w:ilvl"), -1);
            if (i >= 0 && i < 9) levels[i] = l;
          }
        for (int i = 0; i < 9; i++) {
          Element l = levels[i];
          String fmt = l == null ? "bullet" : attr(Xml.child(l, "numFmt"), "w:val");
          String text = l == null ? "\u2022" : attr(Xml.child(l, "lvlText"), "w:val");
          Element ppr = l == null ? null : Xml.child(l, "pPr"), ind = ppr == null ? null : Xml.child(ppr, "ind");
          float left = ind == null ? 36 * (i + 1) : Xml.number(Xml.attr(ind, "w:left", Xml.attr(ind, "w:start", "" + 720 * (i + 1)))) / 20f;
          float hanging = ind == null ? 18 : Xml.number(Xml.attr(ind, "w:hanging", "0")) / 20f;
          String props = "<style:list-level-properties text:list-level-position-and-space-mode=\"label-alignment\">"
              + "<style:list-level-label-alignment text:label-followed-by=\"listtab\" text:list-tab-stop-position=\""
              + pt(left) + "\" fo:text-indent=\"" + pt(-hanging) + "\" fo:margin-left=\"" + pt(left) + "\"/></style:list-level-properties>";
          if ("bullet".equals(fmt) || fmt.isEmpty()) {
            String ch = text.isEmpty() ? "\u2022" : text.substring(0, 1);
            b.append("<text:list-level-style-bullet text:level=\"").append(i + 1).append("\" text:bullet-char=\"")
                .append(a(ch)).append("\">").append(props).append("</text:list-level-style-bullet>");
          } else {
            String f = fmt.equals("lowerLetter") ? "a" : fmt.equals("upperLetter") ? "A" : fmt.equals("lowerRoman") ? "i"
                : fmt.equals("upperRoman") ? "I" : fmt.equals("none") ? "" : "1";
            int first = text.indexOf('%'), last = text.lastIndexOf('%'), shown = 0;
            for (int j = 0; j < text.length(); j++) if (text.charAt(j) == '%') shown++;
            String prefix = first < 0 ? text : text.substring(0, first),
                suffix = last < 0 || last + 2 > text.length() ? "" : text.substring(last + 2);
            Integer start = starts.get(String.valueOf(i));
            Element st = l == null ? null : Xml.child(l, "start");
            int s = start != null ? start : st == null ? 1 : Xml.integer(attr(st, "w:val"), 1);
            b.append("<text:list-level-style-number text:level=\"").append(i + 1).append("\" style:num-format=\"").append(f)
                .append("\" style:num-prefix=\"").append(a(prefix)).append("\" style:num-suffix=\"").append(a(suffix))
                .append("\" text:start-value=\"").append(s).append('"');
            if (shown > 1) b.append(" text:display-levels=\"").append(shown).append('"');
            b.append('>').append(props).append("</text:list-level-style-number>");
          }
        }
        b.append("</text:list-style>");
      }
      return b.toString();
    }

    String pageLayout(Element sect) throws IOException {
      Element size = sect == null ? null : Xml.child(sect, "pgSz"), mar = sect == null ? null : Xml.child(sect, "pgMar");
      float w = Xml.number(Xml.attr(size, "w:w", "12240")) / 20f, h = Xml.number(Xml.attr(size, "w:h", "15840")) / 20f;
      float top = Xml.number(Xml.attr(mar, "w:top", "1440")) / 20f, bottom = Xml.number(Xml.attr(mar, "w:bottom", "1440")) / 20f,
          left = Xml.number(Xml.attr(mar, "w:left", "1440")) / 20f, right = Xml.number(Xml.attr(mar, "w:right", "1440")) / 20f,
          header = Xml.number(Xml.attr(mar, "w:header", "720")) / 20f, footer = Xml.number(Xml.attr(mar, "w:footer", "720")) / 20f;
      String[] parts = new String[2];
      String[] kinds = {"header", "footer"};
      for (Element ref : kids(sect)) {
        String k = Xml.local(ref);
        int i = "headerReference".equals(k) ? 0 : "footerReference".equals(k) ? 1 : -1;
        if (i < 0 || !"default".equals(Xml.attr(ref, "w:type", "default"))) continue;
        String target = rels.get(attr(ref, "r:id"));
        if (target == null || in.get(target) == null) continue;
        String savedPart = relPart;
        var savedRels = new HashMap<String, String>(rels);
        readRels("word/_rels/" + target.substring(5) + ".rels");
        sink = master;
        var b = new StringBuilder();
        blocks(parse(target), b);
        sink = content;
        rels.clear();
        rels.putAll(savedRels);
        relPart = savedPart;
        parts[i] = b.length() == 0 ? "<text:p/>" : b.toString();
      }
      var b = new StringBuilder("<style:page-layout style:name=\"pm1\"><style:page-layout-properties fo:page-width=\"")
          .append(pt(w)).append("\" fo:page-height=\"").append(pt(h)).append("\" style:print-orientation=\"")
          .append("landscape".equals(attr(size, "w:orient")) ? "landscape" : "portrait").append("\" fo:margin-top=\"")
          .append(pt(parts[0] != null ? header : top)).append("\" fo:margin-bottom=\"").append(pt(parts[1] != null ? footer : bottom))
          .append("\" fo:margin-left=\"").append(pt(left)).append("\" fo:margin-right=\"").append(pt(right)).append("\"/>");
      if (parts[0] != null)
        b.append("<style:header-style><style:header-footer-properties fo:min-height=\"").append(pt(Math.max(0, top - header)))
            .append("\" fo:margin-bottom=\"0pt\"/></style:header-style>");
      if (parts[1] != null)
        b.append("<style:footer-style><style:header-footer-properties fo:min-height=\"").append(pt(Math.max(0, bottom - footer)))
            .append("\" fo:margin-top=\"0pt\"/></style:footer-style>");
      b.append("</style:page-layout></office:automatic-styles><office:master-styles><style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\">");
      for (int i = 0; i < 2; i++)
        if (parts[i] != null) b.append("<style:").append(kinds[i]).append('>').append(parts[i]).append("</style:").append(kinds[i]).append('>');
      return b.append("</style:master-page></office:master-styles>").toString();
    }

    String meta() throws IOException {
      var b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-meta " + NS
          + " office:version=\"1.3\"><office:meta><meta:generator>CN1 Word Processor</meta:generator>");
      Element core = parse("docProps/core.xml");
      String[][] map = {{"title", "dc:title"}, {"subject", "dc:subject"}, {"creator", "dc:creator"},
        {"description", "dc:description"}};
      for (String[] f : map) {
        Element e = core == null ? null : Xml.child(core, f[0]);
        String v = e == null ? "" : Xml.text(e);
        if (!v.isEmpty()) {
          if ("creator".equals(f[0])) b.append("<meta:initial-creator>").append(Xml.escape(v)).append("</meta:initial-creator>");
          b.append('<').append(f[1]).append('>').append(Xml.escape(v)).append("</").append(f[1]).append('>');
        }
      }
      Element k = core == null ? null : Xml.child(core, "keywords");
      if (k != null)
        for (String word : Xml.text(k).split(",")) {
          if (!word.trim().isEmpty()) b.append("<meta:keyword>").append(Xml.escape(word.trim())).append("</meta:keyword>");
        }
      return b.append("</office:meta></office:document-meta>").toString();
    }
  }
}
