package com.codenameone.wordprocessor;

import java.util.*;

/**
 * The five diagram parts of a created or edited SmartArt graphic, as Wordcraft writes them
 * (crates/docx/src/smart_art.rs): the data model (dgm:dataModel, items as node points joined by
 * parOf connections), a layout definition of our own named by the built-in layout id, the flat
 * quick style, the color definition, and the cached dsp:drawing Word displays without running the
 * layout. All five are relationships of the story part; the data model names the drawing's
 * relationship in its dsp:dataModelExt.
 */
final class DocxSmartArt {
  static final String DATA_CT = "application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml";
  static final String LAYOUT_CT = "application/vnd.openxmlformats-officedocument.drawingml.diagramLayout+xml";
  static final String STYLE_CT = "application/vnd.openxmlformats-officedocument.drawingml.diagramStyle+xml";
  static final String COLORS_CT = "application/vnd.openxmlformats-officedocument.drawingml.diagramColors+xml";
  static final String DRAWING_CT = "application/vnd.ms-office.drawingml.diagramDrawing+xml";
  static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
  static final String[] REL_TYPES = {
    REL + "diagramData", REL + "diagramLayout", REL + "diagramQuickStyle", REL + "diagramColors",
    "http://schemas.microsoft.com/office/2007/relationships/diagramDrawing"
  };
  static final String[] CONTENT_TYPES = {DATA_CT, LAYOUT_CT, STYLE_CT, COLORS_CT, DRAWING_CT};
  static final String[] NAMES = {"data", "layout", "quickStyle", "colors", "drawing"};
  private static final String DGM = DocxCharts.DIAGRAM, A = DocxCharts.A;
  private static final String DSP = "http://schemas.microsoft.com/office/drawing/2008/diagram";
  private static final String QUICK_STYLE = "urn:microsoft.com/office/officeart/2005/8/quickstyle/simple1";
  private static final String[] STYLE_LABELS = {"node1", "connector", "venn"};

  private DocxSmartArt() {}

  /** The built-in layout id and category Wordcraft names for a layout. */
  static String[] layoutId(String layout) {
    String base = "urn:microsoft.com/office/officeart/2005/8/layout/";
    switch (layout) {
      case "process": return new String[] {base + "process1", "process"};
      case "cycle": return new String[] {base + "cycle2", "cycle"};
      case "hierarchy": return new String[] {base + "hierarchy1", "hierarchy"};
      case "pyramid": return new String[] {base + "pyramid1", "pyramid"};
      case "radial": return new String[] {base + "radial1", "cycle"};
      case "matrix": return new String[] {base + "matrix3", "matrix"};
      case "venn": return new String[] {base + "venn1", "relationship"};
      default: return new String[] {base + "default", "list"};
    }
  }

  /** The color definition id and category of a colors value (colorful or accentN). */
  static String[] colorsId(String colors) {
    String base = "urn:microsoft.com/office/officeart/2005/8/colors/";
    if ("colorful".equals(colors)) return new String[] {base + "colorful1", "colorful"};
    String a = accent(colors);
    return new String[] {base + a + "_2", a};
  }

  /** The colors value of a color definition id (the inverse of {@link #colorsId}), or accent1. */
  static String colors(String uniqueId) {
    String s = uniqueId.toLowerCase();
    int slash = s.lastIndexOf('/');
    if (slash >= 0) s = s.substring(slash + 1);
    if (s.startsWith("colorful")) return "colorful";
    if (s.startsWith("accent") && s.length() >= 7 && s.charAt(6) >= '1' && s.charAt(6) <= '6') return s.substring(0, 7);
    return "accent1";
  }

  private static String accent(String colors) {
    return colors.startsWith("accent") && colors.length() == 7 && colors.charAt(6) >= '1' && colors.charAt(6) <= '6'
        ? colors : "accent1";
  }

  private static String modelId(int n) {
    String hex = Integer.toHexString(n).toUpperCase();
    var b = new StringBuilder("{5743A57A-0000-4000-8000-");
    for (int i = hex.length(); i < 12; i++) b.append('0');
    return b.append(hex).append('}').toString();
  }

  private static String esc(String s) {
    return Xml.attribute(s);
  }

  private static void emptyT(StringBuilder b) {
    b.append("<dgm:t><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></dgm:t>");
  }

  /** The data model of a SmartArt drawing; its cached drawing is the story relationship drawingRel. */
  static String dataXml(Drawing d, String drawingRel) {
    String[] lo = layoutId(d.get("layout", "list")), cs = colorsId(d.get("colors", "accent1"));
    List<Object[]> items = DrawingOps.nodes(d.get("nodes", "Text"));
    String doc = modelId(0);
    var b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    b.append("<dgm:dataModel xmlns:dgm=\"").append(DGM).append("\" xmlns:a=\"").append(A).append("\"><dgm:ptLst>");
    b.append("<dgm:pt modelId=\"").append(doc).append("\" type=\"doc\"><dgm:prSet loTypeId=\"").append(lo[0])
        .append("\" loCatId=\"").append(lo[1]).append("\" qsTypeId=\"").append(QUICK_STYLE)
        .append("\" qsCatId=\"simple\" csTypeId=\"").append(cs[0]).append("\" csCatId=\"").append(cs[1])
        .append("\"/><dgm:spPr/>");
    emptyT(b);
    b.append("</dgm:pt>");
    for (int i = 0; i < items.size(); i++) {
      int base = 1 + 4 * i;
      b.append("<dgm:pt modelId=\"").append(modelId(base)).append("\"><dgm:prSet/><dgm:spPr/><dgm:t><a:bodyPr/>")
          .append("<a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>").append(esc((String) items.get(i)[1]))
          .append("</a:t></a:r></a:p></dgm:t></dgm:pt>");
      String[] types = {"parTrans", "sibTrans"};
      for (int k = 0; k < 2; k++) {
        b.append("<dgm:pt modelId=\"").append(modelId(base + 2 + k)).append("\" type=\"").append(types[k])
            .append("\" cxnId=\"").append(modelId(base + 1)).append("\"><dgm:prSet/><dgm:spPr/>");
        emptyT(b);
        b.append("</dgm:pt>");
      }
    }
    b.append("</dgm:ptLst><dgm:cxnLst>");
    // Each item's parent is the nearest earlier item at a lower level (the document at level 1).
    int[] order = new int[items.size() + 1];
    for (int i = 0; i < items.size(); i++) {
      int level = (Integer) items.get(i)[0], parent = -1;
      for (int j = i - 1; j >= 0 && level > 1; j--)
        if ((Integer) items.get(j)[0] < level) {
          parent = j;
          break;
        }
      int base = 1 + 4 * i;
      String src = parent < 0 ? doc : modelId(1 + 4 * parent);
      b.append("<dgm:cxn modelId=\"").append(modelId(base + 1)).append("\" srcId=\"").append(src)
          .append("\" destId=\"").append(modelId(base)).append("\" srcOrd=\"").append(order[parent + 1]++)
          .append("\" destOrd=\"0\" parTransId=\"").append(modelId(base + 2)).append("\" sibTransId=\"")
          .append(modelId(base + 3)).append("\"/>");
    }
    b.append("</dgm:cxnLst><dgm:bg/><dgm:whole/><dgm:extLst><a:ext uri=\"").append(DSP)
        .append("\"><dsp:dataModelExt xmlns:dsp=\"").append(DSP).append("\" relId=\"").append(esc(drawingRel))
        .append("\" minVer=\"").append(DGM).append("\"/></a:ext></dgm:extLst></dgm:dataModel>");
    return b.toString();
  }

  private static void node(StringBuilder b, String name, String style, String alg, String[][] params, String shape,
      String[][] presOf, Runnable body) {
    b.append("<dgm:layoutNode name=\"").append(name).append('"');
    if (style != null) b.append(" styleLbl=\"").append(style).append('"');
    b.append("><dgm:alg type=\"").append(alg).append('"');
    if (params.length == 0) b.append("/>");
    else {
      b.append('>');
      for (String[] p : params) b.append("<dgm:param type=\"").append(p[0]).append("\" val=\"").append(p[1]).append("\"/>");
      b.append("</dgm:alg>");
    }
    b.append("<dgm:shape");
    if (shape != null) b.append(" type=\"").append(shape).append('"');
    b.append(" r:blip=\"\"><dgm:adjLst/></dgm:shape><dgm:presOf");
    for (String[] p : presOf) b.append(' ').append(p[0]).append("=\"").append(p[1]).append('"');
    b.append("/><dgm:constrLst/><dgm:ruleLst/>");
    if (body != null) body.run();
    b.append("</dgm:layoutNode>");
  }

  private static final String[][] NONE = {}, SELF = {{"axis", "self"}};
  private static final String[][] ITEM_TEXT = {{"axis", "desc;self"}, {"ptType", "node;node"}, {"st", "1;1"}, {"cnt", "0;1"}};

  /** A layout definition of our own for a layout, named by its built-in layout id. */
  static String layoutXml(String layout) {
    String[] id = layoutId(layout);
    var b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    b.append("<dgm:layoutDef xmlns:dgm=\"").append(DGM).append("\" xmlns:a=\"").append(A).append("\" xmlns:r=\"")
        .append(DocxCharts.R).append("\" uniqueId=\"").append(id[0]).append("\"><dgm:title val=\"").append(layout)
        .append("\"/><dgm:desc val=\"\"/><dgm:catLst><dgm:cat type=\"").append(id[1])
        .append("\" pri=\"1000\"/></dgm:catLst>");
    String alg;
    String[][] params;
    String shape;
    switch (layout) {
      case "process": alg = "lin"; params = new String[][] {{"linDir", "fromL"}, {"nodeVertAlign", "mid"}}; shape = "roundRect"; break;
      case "cycle": case "venn": alg = "cycle"; params = new String[][] {{"stAng", "0"}, {"spanAng", "360"}}; shape = "ellipse"; break;
      case "radial": alg = "cycle"; params = new String[][] {{"stAng", "0"}, {"spanAng", "360"}, {"ctrShpMap", "fNode"}}; shape = "ellipse"; break;
      case "hierarchy": alg = "hierChild"; params = new String[][] {{"linDir", "fromL"}, {"chAlign", "t"}}; shape = "roundRect"; break;
      case "pyramid": alg = "pyra"; params = new String[][] {{"linDir", "fromT"}}; shape = "trapezoid"; break;
      case "matrix": alg = "snake"; params = new String[][] {{"grDir", "tL"}, {"flowDir", "row"}, {"contDir", "sameDir"}}; shape = "roundRect"; break;
      default: alg = "snake"; params = new String[][] {{"grDir", "tL"}, {"flowDir", "row"}, {"contDir", "sameDir"}}; shape = "rect";
    }
    final String sh = shape;
    node(b, "diagram", null, alg, params, null, NONE, () -> {
      if ("hierarchy".equals(layout)) {
        b.append("<dgm:forEach name=\"branches\" axis=\"ch\" ptType=\"node\">");
        node(b, "branch", null, "hierRoot", NONE, null, NONE, () -> {
          node(b, "item", "node1", "tx", NONE, sh, SELF, null);
          node(b, "below", null, "hierChild", new String[][] {{"chAlign", "t"}}, null, NONE, () -> {
            b.append("<dgm:forEach name=\"joins\" axis=\"ch\" ptType=\"parTrans\">");
            node(b, "join", "connector", "conn",
                new String[][] {{"dim", "1D"}, {"connRout", "bend"}, {"begPts", "bCtr"}, {"endPts", "tCtr"}}, "conn", SELF, null);
            b.append("</dgm:forEach><dgm:forEach name=\"deeper\" ref=\"branches\"/>");
          });
        });
        b.append("</dgm:forEach>");
      } else if ("radial".equals(layout)) {
        b.append("<dgm:forEach name=\"middle\" axis=\"ch\" ptType=\"node\" cnt=\"1\">");
        node(b, "centre", "node1", "tx", NONE, sh, SELF, () -> {
          b.append("<dgm:forEach name=\"around\" axis=\"ch\" ptType=\"node\">");
          node(b, "spoke", "node1", "tx", NONE, sh, ITEM_TEXT, null);
          b.append("</dgm:forEach>");
        });
        b.append("</dgm:forEach>");
      } else {
        b.append("<dgm:forEach name=\"items\" axis=\"ch\" ptType=\"node\">");
        node(b, "item", "venn".equals(layout) ? "venn" : "node1", "tx", NONE, sh, ITEM_TEXT, null);
        if ("process".equals(layout) || "cycle".equals(layout)) {
          b.append("<dgm:forEach name=\"arrows\" axis=\"followSib\" ptType=\"sibTrans\" cnt=\"1\">");
          node(b, "arrow", "connector", "conn", new String[][] {{"dim", "2D"}}, "rightArrow", SELF, null);
          b.append("</dgm:forEach>");
        }
        b.append("</dgm:forEach>");
      }
    });
    return b.append("</dgm:layoutDef>").toString();
  }

  private static void schemeList(StringBuilder b, String tag, String meth, List<String> vals) {
    b.append('<').append(tag).append(" meth=\"").append(meth).append("\">");
    for (String v : vals) b.append("<a:schemeClr val=\"").append(v).append("\"/>");
    b.append("</").append(tag).append('>');
  }

  /** The color definition of a colors value. */
  static String colorsXml(String colors) {
    String[] id = colorsId(colors);
    boolean colorful = "colorful".equals(colors);
    var accents = new ArrayList<String>();
    if (colorful) for (int n = 1; n <= 6; n++) accents.add("accent" + n);
    else accents.add(accent(colors));
    var b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    b.append("<dgm:colorsDef xmlns:dgm=\"").append(DGM).append("\" xmlns:a=\"").append(A).append("\" uniqueId=\"")
        .append(id[0]).append("\"><dgm:title val=\"").append(id[1]).append("\"/><dgm:desc val=\"\"/><dgm:catLst><dgm:cat type=\"")
        .append(id[1]).append("\" pri=\"1000\"/></dgm:catLst>");
    for (String lbl : STYLE_LABELS) {
      b.append("<dgm:styleLbl name=\"").append(lbl).append("\">");
      schemeList(b, "dgm:fillClrLst", colorful ? "cycle" : "repeat", accents);
      schemeList(b, "dgm:linClrLst", "repeat", Collections.singletonList("lt1"));
      b.append("<dgm:effectClrLst/><dgm:txLinClrLst/>");
      schemeList(b, "dgm:txFillClrLst", "repeat", Collections.singletonList("venn".equals(lbl) ? "tx1" : "lt1"));
      b.append("<dgm:txEffectClrLst/></dgm:styleLbl>");
    }
    return b.append("</dgm:colorsDef>").toString();
  }

  /** The flat quick style. */
  static String styleXml() {
    String scene = "<dgm:scene3d><a:camera prst=\"orthographicFront\"/><a:lightRig rig=\"threePt\" dir=\"t\"/></dgm:scene3d>";
    var b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    b.append("<dgm:styleDef xmlns:dgm=\"").append(DGM).append("\" xmlns:a=\"").append(A).append("\" uniqueId=\"")
        .append(QUICK_STYLE).append("\"><dgm:title val=\"Flat\"/><dgm:desc val=\"\"/><dgm:catLst>")
        .append("<dgm:cat type=\"simple\" pri=\"1000\"/></dgm:catLst>").append(scene);
    for (String lbl : STYLE_LABELS) {
      b.append("<dgm:styleLbl name=\"").append(lbl).append("\">").append(scene).append("<dgm:sp3d/><dgm:txPr/><dgm:style>");
      String[][] refs = {{"a:lnRef", "2"}, {"a:fillRef", "1"}, {"a:effectRef", "0"}};
      for (String[] r : refs)
        b.append('<').append(r[0]).append(" idx=\"").append(r[1]).append("\"><a:scrgbClr r=\"0\" g=\"0\" b=\"0\"/></")
            .append(r[0]).append('>');
      b.append("<a:fontRef idx=\"minor\"><a:schemeClr val=\"").append("venn".equals(lbl) ? "tx1" : "lt1")
          .append("\"/></a:fontRef></dgm:style></dgm:styleLbl>");
    }
    return b.append("</dgm:styleDef>").toString();
  }

  private static long emu(float pt) {
    return Math.round(pt * 12700);
  }

  private static String hex(int rgb) {
    return Integer.toHexString(0x1000000 | (rgb & 0xffffff)).substring(1).toUpperCase();
  }

  /** a:xfrm rot (60000ths of a degree, clockwise, 0..360) for a shape recorded with a rotation. */
  private static String rotation(Object[] s) {
    if (s.length <= 10 || !(s[10] instanceof Float)) return "";
    float deg = (Float) s[10] % 360;
    if (deg < 0) deg += 360;
    long rot = Math.round(deg * 60000.0);
    return rot == 0 || rot >= 21600000 ? "" : " rot=\"" + rot + "\"";
  }

  /**
   * The cached dsp:drawing of a SmartArt drawing laid out at w x h points: the node shapes the
   * app draws, its hierarchy/spoke connector lines and the cycle ring, each with its fill, outline and text.
   */
  static String drawingXml(Drawing d, float w, float h) {
    DrawingOps.Ops ops = DrawingOps.build(d.with("shapes", "").with("dgmKey", ""), w, h);
    var b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    b.append("<dsp:drawing xmlns:dgm=\"").append(DGM).append("\" xmlns:dsp=\"").append(DSP).append("\" xmlns:a=\"")
        .append(A).append("\"><dsp:spTree><dsp:nvGrpSpPr><dsp:cNvPr id=\"0\" name=\"\"/><dsp:cNvGrpSpPr/></dsp:nvGrpSpPr>")
        .append("<dsp:grpSpPr/>");
    String size = d.get("fontSize", "12");
    int k = 0;
    for (Object[] s : ops.shapes) {
      float x = (Float) s[1], y = (Float) s[2], sw = (Float) s[3], sh = (Float) s[4];
      int fill = (Integer) s[5], line = (Integer) s[6], text = (Integer) s[7];
      b.append("<dsp:sp modelId=\"").append(modelId(0x10000 + k++)).append("\"><dsp:nvSpPr><dsp:cNvPr id=\"0\" name=\"\"/>")
          .append("<dsp:cNvSpPr/></dsp:nvSpPr><dsp:spPr><a:xfrm").append(s.length > 9 && Boolean.TRUE.equals(s[9]) ? " flipH=\"1\"" : "")
          .append(rotation(s))
          .append("><a:off x=\"").append(emu(x)).append("\" y=\"")
          .append(emu(y)).append("\"/><a:ext cx=\"").append(emu(sw)).append("\" cy=\"").append(emu(sh))
          .append("\"/></a:xfrm><a:prstGeom prst=\"").append(s[0]).append("\"><a:avLst/></a:prstGeom>");
      if (fill < 0) b.append("<a:noFill/>");
      else b.append("<a:solidFill><a:srgbClr val=\"").append(hex(fill)).append("\"/></a:solidFill>");
      if (line < 0) b.append("<a:ln><a:noFill/></a:ln>");
      else b.append("<a:ln w=\"").append("line".equals(s[0]) ? 19050 : 12700).append("\"><a:solidFill><a:srgbClr val=\"").append(hex(line)).append("\"/></a:solidFill></a:ln>");
      b.append("</dsp:spPr><dsp:style><a:lnRef idx=\"2\"><a:scrgbClr r=\"0\" g=\"0\" b=\"0\"/></a:lnRef><a:fillRef idx=\"1\">")
          .append("<a:scrgbClr r=\"0\" g=\"0\" b=\"0\"/></a:fillRef><a:effectRef idx=\"0\"><a:scrgbClr r=\"0\" g=\"0\" b=\"0\"/>")
          .append("</a:effectRef><a:fontRef idx=\"minor\"><a:srgbClr val=\"").append(hex(text))
          .append("\"/></a:fontRef></dsp:style><dsp:txBody><a:bodyPr anchor=\"ctr\"/><a:lstStyle/>");
      String body = (String) s[8];
      List<String> lines = DrawingOps.split(body, '\n');
      String[] paras = lines.isEmpty() ? new String[] {""} : lines.toArray(new String[0]);
      for (int i = 0; i < paras.length; i++) {
        b.append("<a:p><a:pPr algn=\"ctr\"/>");
        if (!paras[i].isEmpty())
          b.append("<a:r><a:rPr lang=\"en-US\" sz=\"").append(Math.round(Xml.number(size) * (i == 0 ? 100 : 80)))
              .append("\"><a:solidFill><a:srgbClr val=\"").append(hex(text)).append("\"/></a:solidFill></a:rPr><a:t>")
              .append(esc(i == 0 ? paras[i] : "• " + paras[i])).append("</a:t></a:r>");
        b.append("</a:p>");
      }
      b.append("</dsp:txBody><dsp:txXfrm><a:off x=\"").append(emu(x)).append("\" y=\"").append(emu(y))
          .append("\"/><a:ext cx=\"").append(emu(sw)).append("\" cy=\"").append(emu(sh)).append("\"/></dsp:txXfrm></dsp:sp>");
    }
    return b.append("</dsp:spTree></dsp:drawing>").toString();
  }
}
