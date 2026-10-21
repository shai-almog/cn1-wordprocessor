package com.codenameone.wordprocessor;

import java.util.Set;
import com.codename1.xml.Element;

/**
 * DOCX output and input for {@link Drawing}s. Every drawing keeps its exact encoding in a
 * {@code wp:docPr} extension so this app round-trips it unchanged. Word sees an editable
 * shape (preset geometry plus text box) for shapes, text boxes and WordArt, an Office Math
 * paragraph for equations, and a group of custom-geometry shapes generated from {@link DrawingOps}
 * for charts, SmartArt, ink, icons, canvases, signature lines and objects.
 */
final class DocxDrawings {
  static final String EXT = "{6E3B0C55-CD1E-4F0B-9A4C-57A1DBE0C0DE}";
  private static final String CN = "urn:codenameone:wordprocessor:drawing";
  private static final String WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape";
  private static final String WPG = "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup";
  private static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";

  /** Em space separating an equation from its "(n)" number inside the math zone. */
  private static final String NUMBER = "\u2003\u2003";

  private DocxDrawings() {}

  static void write(StringBuilder b, Picture p, int docId) {
    write(b, p, docId, null);
  }

  static void write(StringBuilder b, Picture p, int docId, DocxPictures parts) {
    var d = p.drawing;
    if ("equation".equals(d.kind)) {
      equation(b, p);
      return;
    }
    long cx = emu(p.width), cy = emu(p.height);
    b.append("<w:p><w:pPr><w:jc w:val=\"").append(p.align).append("\"/></w:pPr><w:r><w:drawing>")
        .append("<wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"")
        .append(" distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"").append(cx)
        .append("\" cy=\"").append(cy).append("\"/><wp:docPr id=\"").append(docId)
        .append("\" name=\"").append(d.kind).append(' ').append(docId).append("\" descr=\"")
        .append(Xml.attribute(p.alt)).append("\"><a:extLst xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">")
        .append("<a:ext uri=\"").append(EXT).append("\"><cn:drawing xmlns:cn=\"").append(CN)
        .append("\" value=\"").append(Xml.attribute(d.encode())).append("\"/></a:ext></a:extLst></wp:docPr>")
        .append("<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">");
    String[] dgm = parts != null && "smartArt".equals(d.kind) ? parts.diagramRels(d, p.width, p.height) : null;
    if (parts != null && "chart".equals(d.kind)) {
      // A real chart part: the original one while unedited, otherwise one generated from the data.
      b.append("<a:graphicData uri=\"").append(DocxCharts.CHART).append("\"><c:chart xmlns:c=\"")
          .append(DocxCharts.CHART).append("\" xmlns:r=\"").append(DocxCharts.R).append("\" r:id=\"")
          .append(Xml.attribute(parts.chartRel(d))).append("\"/>");
    } else if (dgm != null) {
      // Unedited SmartArt keeps its diagram data, layout, style and color parts; created or edited
      // SmartArt gets newly generated ones (with a cached drawing), as Wordcraft writes it.
      b.append("<a:graphicData uri=\"").append(DocxCharts.DIAGRAM).append("\"><dgm:relIds xmlns:dgm=\"")
          .append(DocxCharts.DIAGRAM).append("\" xmlns:r=\"").append(DocxCharts.R).append("\" r:dm=\"")
          .append(Xml.attribute(dgm[0])).append("\" r:lo=\"").append(Xml.attribute(dgm[1])).append("\" r:qs=\"")
          .append(Xml.attribute(dgm[2])).append("\" r:cs=\"").append(Xml.attribute(dgm[3])).append("\"/>");
    } else if ("shape".equals(d.kind) || "textBox".equals(d.kind)) {
      b.append("<a:graphicData uri=\"").append(WPS).append("\">");
      shape(b, d, cx, cy);
    } else {
      b.append("<a:graphicData uri=\"").append(WPG).append("\"><wpg:wgp xmlns:wpg=\"").append(WPG)
          .append("\"><wpg:cNvGrpSpPr/><wpg:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"")
          .append(cx).append("\" cy=\"").append(cy).append("\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"")
          .append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm></wpg:grpSpPr>");
      DrawingOps.Ops ops = DrawingOps.build(d, p.width, p.height);
      for (DrawingOps.Path path : ops.paths) path(b, path);
      for (DrawingOps.Text text : ops.texts) text(b, text);
      b.append("</wpg:wgp>");
    }
    b.append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
  }

  private static long emu(float points) {
    return Math.max(1, Math.round(points * 12700));
  }

  private static String hex(int rgb) {
    return Integer.toHexString(0x1000000 | (rgb & 0xffffff)).substring(1);
  }

  private static String prst(String shape) {
    if ("rect".equals(shape)) return "rect";
    if ("ellipse".equals(shape)) return "ellipse";
    if ("triangle".equals(shape)) return "triangle";
    if ("rightTriangle".equals(shape)) return "rtTriangle";
    if ("star".equals(shape)) return "star5";
    if ("line".equals(shape) || "arrowLine".equals(shape)) return "line";
    if ("callout".equals(shape)) return "wedgeRectCallout";
    if ("heart".equals(shape) || "plus".equals(shape) || "diamond".equals(shape)
        || "pentagon".equals(shape) || "hexagon".equals(shape) || "octagon".equals(shape)
        || "parallelogram".equals(shape) || "trapezoid".equals(shape))
      return shape;
    return shape;
  }

  private static String shapeName(String prst) {
    if ("rtTriangle".equals(prst)) return "rightTriangle";
    if ("star5".equals(prst)) return "star";
    if ("wedgeRectCallout".equals(prst)) return "callout";
    if ("straightConnector1".equals(prst)) return "line";
    return DrawingOps.SHAPES.contains(prst) ? prst : "rect";
  }

  private static void fill(StringBuilder b, int color, int alpha) {
    if (color < 0) b.append("<a:noFill/>");
    else {
      b.append("<a:solidFill><a:srgbClr val=\"").append(hex(color)).append('"');
      if (alpha < 255) b.append("><a:alpha val=\"").append(alpha * 100000 / 255).append("\"/></a:srgbClr>");
      else b.append("/>");
      b.append("</a:solidFill>");
    }
  }

  private static void line(StringBuilder b, int color, float width, int alpha) {
    if (color < 0) b.append("<a:ln><a:noFill/></a:ln>");
    else {
      b.append("<a:ln w=\"").append(emu(width)).append("\">");
      fill(b, color, alpha);
      b.append("</a:ln>");
    }
  }

  private static void shape(StringBuilder b, Drawing d, long cx, long cy) {
    boolean box = "textBox".equals(d.kind);
    String geometry = box ? "rect" : d.get("shape", "rect");
    b.append("<wps:wsp xmlns:wps=\"").append(WPS).append("\"><wps:cNvSpPr")
        .append(box ? " txBox=\"1\"" : "").append("/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"")
        .append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"")
        .append(prst(geometry)).append("\"><a:avLst/></a:prstGeom>");
    int alpha = Math.round(255 * (100 - Math.max(0, Math.min(100, d.num("transparency", 0)))) / 100f);
    fill(b, d.color("fill", box ? 0xffffff : 0x4472c4), alpha);
    line(b, d.color("line", box ? 0 : 0x2f528f), d.num("lineWidth", 1), 255);
    b.append("</wps:spPr>");
    var text = d.get("text", "");
    if (!text.isEmpty()) {
      b.append("<wps:txbx><w:txbxContent>");
      for (String para : DrawingOps.split(text, '\n')) {
        var align = d.get("align", box ? "left" : "center");
        b.append("<w:p><w:pPr><w:jc w:val=\"").append("right".equals(align) ? "right" : "center".equals(align) ? "center" : "left")
            .append("\"/></w:pPr><w:r><w:rPr>");
        if (d.flag("bold")) b.append("<w:b/>");
        if (d.flag("italic")) b.append("<w:i/>");
        b.append("<w:color w:val=\"").append(hex(d.color("textColor", box ? 0 : 0xffffff)))
            .append("\"/><w:sz w:val=\"").append(Math.round(d.num("fontSize", 11) * 2))
            .append("\"/></w:rPr><w:t xml:space=\"preserve\">").append(Xml.escape(para)).append("</w:t></w:r></w:p>");
      }
      b.append("</w:txbxContent></wps:txbx>");
    }
    var valign = d.get("valign", "middle");
    var direction = d.get("textDirection", "horizontal");
    b.append("<wps:bodyPr anchor=\"").append("top".equals(valign) ? "t" : "bottom".equals(valign) ? "b" : "ctr").append('"');
    if ("rotate90".equals(direction)) b.append(" vert=\"vert\"");
    else if ("rotate270".equals(direction)) b.append(" vert=\"vert270\"");
    else if ("stacked".equals(direction)) b.append(" vert=\"wordArtVert\"");
    b.append("/></wps:wsp>");
  }

  private static void path(StringBuilder b, DrawingOps.Path p) {
    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    for (int i = 0; i < p.points().length; i += 2) {
      minX = Math.min(minX, p.points()[i]);
      maxX = Math.max(maxX, p.points()[i]);
      minY = Math.min(minY, p.points()[i + 1]);
      maxY = Math.max(maxY, p.points()[i + 1]);
    }
    long w = emu(maxX - minX), h = emu(maxY - minY);
    b.append("<wps:wsp xmlns:wps=\"").append(WPS).append("\"><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x=\"")
        .append(Math.round(Math.max(0, minX) * 12700)).append("\" y=\"").append(Math.round(Math.max(0, minY) * 12700))
        .append("\"/><a:ext cx=\"").append(w).append("\" cy=\"").append(h)
        .append("\"/></a:xfrm><a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l=\"0\" t=\"0\" r=\"r\" b=\"b\"/><a:pathLst><a:path w=\"")
        .append(w).append("\" h=\"").append(h).append('"').append(p.fill() < 0 ? " fill=\"none\"" : "").append('>');
    for (int i = 0; i < p.points().length; i += 2) {
      b.append(i == 0 ? "<a:moveTo>" : "<a:lnTo>").append("<a:pt x=\"").append(Math.round((p.points()[i] - minX) * 12700))
          .append("\" y=\"").append(Math.round((p.points()[i + 1] - minY) * 12700)).append("\"/>")
          .append(i == 0 ? "</a:moveTo>" : "</a:lnTo>");
    }
    if (p.closed()) b.append("<a:close/>");
    b.append("</a:path></a:pathLst></a:custGeom>");
    fill(b, p.fill(), p.alpha());
    line(b, p.stroke(), p.width(), p.alpha());
    b.append("</wps:spPr><wps:bodyPr/></wps:wsp>");
  }

  private static void text(StringBuilder b, DrawingOps.Text t) {
    b.append("<wps:wsp xmlns:wps=\"").append(WPS).append("\"><wps:cNvSpPr txBox=\"1\"/><wps:spPr><a:xfrm");
    if (t.rotation() != 0) b.append(" rot=\"").append(t.rotation() * 60000L).append('"');
    b.append("><a:off x=\"").append(Math.round(Math.max(0, t.x()) * 12700)).append("\" y=\"")
        .append(Math.round(Math.max(0, t.y()) * 12700)).append("\"/><a:ext cx=\"").append(emu(t.width()))
        .append("\" cy=\"").append(emu(t.size() * 1.3f))
        .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>")
        .append("<wps:txbx><w:txbxContent><w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/><w:jc w:val=\"")
        .append("right".equals(t.align()) ? "right" : "center".equals(t.align()) ? "center" : "left")
        .append("\"/></w:pPr><w:r><w:rPr>");
    if (t.bold()) b.append("<w:b/>");
    if (t.italic()) b.append("<w:i/>");
    b.append("<w:color w:val=\"").append(hex(t.color())).append("\"/><w:sz w:val=\"")
        .append(Math.max(2, Math.round(t.size() * 2))).append("\"/></w:rPr><w:t xml:space=\"preserve\">")
        .append(Xml.escape(t.text()))
        .append("</w:t></w:r></w:p></w:txbxContent></wps:txbx><wps:bodyPr lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" wrap=\"none\"/></wps:wsp>");
  }

  private static void equation(StringBuilder b, Picture p) {
    var d = p.drawing;
    var justify = d.get("justify", "center");
    String source = d.get("omml", "");
    boolean verbatim = !source.isEmpty() && Equation.key(d).equals(d.get("ommlKey", ""));
    b.append("<w:p><w:pPr><w:jc w:val=\"").append("left".equals(justify) ? "left" : "right".equals(justify) ? "right" : "center")
        .append("\"/></w:pPr>");
    if (verbatim) {
      boolean para = source.startsWith("<m:oMathPara");
      if (!para) b.append("<m:oMathPara xmlns:m=\"").append(M).append("\">");
      b.append(source);
      if (!para) b.append("</m:oMathPara>");
      b.append("</w:p>");
      return;
    }
    if (!"linear".equals(d.get("format", "professional")) && d.get("number", "").isEmpty() && !d.flag("normalText")) {
      // Edited or typed equations are written as built-up Office Math from their linear form.
      String xml = Equation.omml(Equation.fromLinear(d.get("text", "")));
      b.append("<m:oMathPara xmlns:m=\"").append(M).append("\">").append(xml).append("</m:oMathPara></w:p>");
      return;
    }
    b.append("<m:oMathPara xmlns:m=\"").append(M).append("\"><m:oMath><m:r>");
    if (d.flag("normalText")) b.append("<m:rPr><m:nor/></m:rPr>");
    var number = d.get("number", "");
    b.append("<m:t xml:space=\"preserve\">").append(Xml.escape(DrawingOps.professional(d.get("text", ""))))
        .append(number.isEmpty() ? "" : NUMBER + "(" + Xml.escape(number) + ")")
        .append("</m:t></m:r></m:oMath></m:oMathPara></w:p>");
  }

  /** The exact drawing saved by this app, or null. */
  static Drawing extension(Element container) {
    Element docPr = Xml.child(container, "docPr");
    if (docPr == null) return null;
    Element mark = DocxPictures.descendant(docPr, "drawing");
    if (mark == null) return null;
    try {
      return Drawing.decode(Xml.attr(mark, "value", ""));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** A Word shape or text box (wps:wsp outside a group), or null. */
  static Drawing shape(Element container, Set<String> warnings) {
    Element wsp = DocxPictures.descendant(container, "wsp");
    if (wsp == null || DocxPictures.descendant(container, "wgp") != null) return null;
    Element nv = Xml.child(wsp, "cNvSpPr");
    boolean box = nv != null && "1".equals(Xml.attr(nv, "txBox", ""));
    Drawing d = Drawing.of(box ? "textBox" : "shape");
    Element spPr = Xml.child(wsp, "spPr");
    if (spPr != null) {
      Element geom = Xml.child(spPr, "prstGeom");
      if (!box && geom != null) d = d.with("shape", shapeName(Xml.attr(geom, "prst", "rect")));
      if (Xml.child(spPr, "noFill") != null) d = d.with("fill", "none");
      Element solid = Xml.child(spPr, "solidFill");
      Element clr = solid == null ? null : Xml.child(solid, "srgbClr");
      if (clr != null) d = d.with("fill", Xml.attr(clr, "val", ""));
      Element ln = Xml.child(spPr, "ln");
      if (ln != null) {
        if (Xml.child(ln, "noFill") != null) d = d.with("line", "none");
        Element lsolid = Xml.child(ln, "solidFill");
        Element lclr = lsolid == null ? null : Xml.child(lsolid, "srgbClr");
        if (lclr != null) d = d.with("line", Xml.attr(lclr, "val", ""));
        if (!Xml.attr(ln, "w", "").isEmpty())
          d = d.with("lineWidth", String.valueOf(Xml.number(Xml.attr(ln, "w", "12700")) / 12700f));
      }
    }
    Element content = DocxPictures.descendant(wsp, "txbxContent");
    if (content != null) {
      var text = new StringBuilder();
      for (Element para : Xml.children(content, null)) {
        if (!"p".equals(Xml.local(para))) continue;
        if (text.length() > 0) text.append('\n');
        collectText(para, text);
        Element sz = DocxPictures.descendant(para, "sz"), color = DocxPictures.descendant(para, "color");
        if (sz != null) d = d.with("fontSize", String.valueOf(Xml.number(Xml.attr(sz, "val", "22")) / 2));
        if (color != null && Xml.attr(color, "val", "").length() == 6) d = d.with("textColor", Xml.attr(color, "val", ""));
        if (DocxPictures.descendant(para, "b") != null) d = d.with("bold", "true");
        Element jc = DocxPictures.descendant(para, "jc");
        if (jc != null) d = d.with("align", "both".equals(Xml.attr(jc, "val", "left")) ? "left" : Xml.attr(jc, "val", "left"));
      }
      d = d.with("text", text.toString());
    }
    Element body = Xml.child(wsp, "bodyPr");
    if (body != null) {
      String anchor = Xml.attr(body, "anchor", "t");
      d = d.with("valign", "ctr".equals(anchor) ? "middle" : "b".equals(anchor) ? "bottom" : "top");
      String vert = Xml.attr(body, "vert", "horz");
      if ("vert".equals(vert)) d = d.with("textDirection", "rotate90");
      else if ("vert270".equals(vert)) d = d.with("textDirection", "rotate270");
      else if ("wordArtVert".equals(vert)) d = d.with("textDirection", "stacked");
    }
    return d;
  }

  private static void collectText(Element e, StringBuilder out) {
    if ("t".equals(Xml.local(e))) out.append(Xml.text(e));
    else if ("tab".equals(Xml.local(e))) out.append('\t');
    else for (Element c : Xml.children(e, null)) collectText(c, out);
  }

  /** An Office Math zone as an equation drawing. */
  static Drawing math(Element oMath) {
    var text = new StringBuilder();
    collectText(oMath, text);
    String value = text.toString(), number = "";
    int mark = value.lastIndexOf(NUMBER);
    if (mark >= 0 && value.endsWith(")") && value.startsWith("(", mark + NUMBER.length())) {
      number = value.substring(mark + NUMBER.length() + 1, value.length() - 1);
      value = value.substring(0, mark);
    }
    var tree = Equation.fromOmml(oMath);
    String linear = Equation.linear(tree);
    String suffix = number.isEmpty() ? "" : NUMBER + "(" + number + ")";
    if (!suffix.isEmpty() && linear.endsWith(suffix)) linear = linear.substring(0, linear.length() - suffix.length());
    else if (!suffix.isEmpty()) linear = value;
    var d = Drawing.of("equation").with("text", linear).with("number", number);
    if (DocxPictures.descendant(oMath, "nor") != null) d = d.with("normalText", "true");
    // The source math zone is kept and saved verbatim until the equation is edited.
    String xml = Xml.write(oMath);
    if (xml.startsWith("<m:") && xml.length() < 190000) {
      if (!xml.contains("xmlns:m=")) {
        int end = xml.indexOf('>');
        int slash = xml.charAt(end - 1) == '/' ? end - 1 : end;
        xml = xml.substring(0, slash) + " xmlns:m=\"" + M + "\"" + xml.substring(slash);
      }
      d = d.with("omml", xml);
      d = d.with("ommlKey", Equation.key(d));
    }
    return d;
  }
}
