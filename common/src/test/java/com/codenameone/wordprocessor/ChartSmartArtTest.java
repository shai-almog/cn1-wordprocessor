package com.codenameone.wordprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class ChartSmartArtTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String C = "xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" "
      + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"";

  static String chartPart(String plot) {
    return "<c:chartSpace " + C + "><c:chart><c:title><c:tx><c:rich><a:p><a:r><a:t>Sales</a:t></a:r></a:p>"
        + "</c:rich></c:tx></c:title><c:plotArea>" + plot + "</c:plotArea><c:legend/></c:chart></c:chartSpace>";
  }

  static String ser(String name, String[] cats, String[] vals, String catTag, String valTag) {
    var b = new StringBuilder("<c:ser><c:tx><c:strRef><c:strCache><c:pt idx=\"0\"><c:v>" + name
        + "</c:v></c:pt></c:strCache></c:strRef></c:tx><c:" + catTag + "><c:strRef><c:strCache><c:ptCount val=\""
        + cats.length + "\"/>");
    for (int i = 0; i < cats.length; i++) b.append("<c:pt idx=\"" + i + "\"><c:v>" + cats[i] + "</c:v></c:pt>");
    b.append("</c:strCache></c:strRef></c:" + catTag + "><c:" + valTag + "><c:numRef><c:numCache><c:ptCount val=\""
        + vals.length + "\"/>");
    for (int i = 0; i < vals.length; i++) b.append("<c:pt idx=\"" + i + "\"><c:v>" + vals[i] + "</c:v></c:pt>");
    return b.append("</c:numCache></c:numRef></c:" + valTag + "></c:ser>").toString();
  }

  static final String BAR = chartPart("<c:barChart><c:barDir val=\"bar\"/>"
      + ser("North", new String[] {"Q1", "Q2", "Q3"}, new String[] {"1.5", "2", "3"}, "cat", "val")
      + ser("South", new String[] {"Q1", "Q2", "Q3"}, new String[] {"4", "5", "6"}, "cat", "val")
      + "<c:dLbls><c:showVal val=\"1\"/></c:dLbls></c:barChart>");

  private static String inline(String graphicData) {
    return "<w:p><w:r><w:drawing><wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">"
        + "<wp:extent cx=\"5486400\" cy=\"3200400\"/><wp:docPr id=\"1\" name=\"Chart 1\"/>"
        + "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" + graphicData
        + "</a:graphic></wp:inline></w:drawing></w:r></w:p>";
  }

  static final String CHART_REF = inline("<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">"
      + "<c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" xmlns:r=\"" + REL
      + "\" r:id=\"rId7\"/></a:graphicData>");

  static final String DGM_REF = inline("<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/diagram\">"
      + "<dgm:relIds xmlns:dgm=\"http://schemas.openxmlformats.org/drawingml/2006/diagram\" xmlns:r=\"" + REL
      + "\" r:dm=\"rId1\" r:lo=\"rId2\" r:qs=\"rId3\" r:cs=\"rId4\"/></a:graphicData>");

  static final String DGM_DATA = "<dgm:dataModel xmlns:dgm=\"http://schemas.openxmlformats.org/drawingml/2006/diagram\" "
      + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><dgm:ptLst>"
      + "<dgm:pt modelId=\"0\" type=\"doc\"><dgm:prSet loTypeId=\"urn:microsoft.com/office/officeart/2005/8/layout/process1\"/></dgm:pt>"
      + "<dgm:pt modelId=\"1\"><dgm:t><a:p><a:r><a:t>Plan</a:t></a:r></a:p></dgm:t></dgm:pt>"
      + "<dgm:pt modelId=\"2\"><dgm:t><a:p><a:r><a:t>Build</a:t></a:r></a:p></dgm:t></dgm:pt>"
      + "<dgm:pt modelId=\"3\"><dgm:t><a:p><a:r><a:t>Detail</a:t></a:r></a:p></dgm:t></dgm:pt>"
      + "<dgm:pt modelId=\"9\" type=\"pres\"/></dgm:ptLst><dgm:cxnLst>"
      + "<dgm:cxn modelId=\"c2\" srcId=\"0\" destId=\"2\" srcOrd=\"1\"/>"
      + "<dgm:cxn modelId=\"c1\" srcId=\"0\" destId=\"1\" srcOrd=\"0\"/>"
      + "<dgm:cxn modelId=\"c3\" srcId=\"1\" destId=\"3\" srcOrd=\"0\"/>"
      + "<dgm:cxn modelId=\"c4\" type=\"presOf\" srcId=\"1\" destId=\"9\"/></dgm:cxnLst>"
      + "<dgm:extLst><a:ext><dsp:dataModelExt xmlns:dsp=\"http://schemas.microsoft.com/office/drawing/2008/diagram\" relId=\"rId5\"/>"
      + "</a:ext></dgm:extLst></dgm:dataModel>";

  static final String DSP = "<dsp:drawing xmlns:dsp=\"http://schemas.microsoft.com/office/drawing/2008/diagram\" "
      + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><dsp:spTree>"
      + "<dsp:sp><dsp:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"2743200\" cy=\"1600200\"/></a:xfrm>"
      + "<a:prstGeom prst=\"roundRect\"/><a:solidFill><a:schemeClr val=\"accent2\"/></a:solidFill></dsp:spPr>"
      + "<dsp:style><a:fontRef idx=\"minor\"><a:schemeClr val=\"lt1\"/></a:fontRef></dsp:style>"
      + "<dsp:txBody><a:p><a:r><a:t>Plan</a:t></a:r></a:p><a:p><a:r><a:t>Detail</a:t></a:r></a:p></dsp:txBody></dsp:sp>"
      + "<dsp:sp><dsp:spPr><a:xfrm><a:off x=\"2743200\" y=\"0\"/><a:ext cx=\"2743200\" cy=\"1600200\"/></a:xfrm>"
      + "<a:prstGeom prst=\"rect\"/><a:solidFill><a:srgbClr val=\"112233\"/></a:solidFill></dsp:spPr>"
      + "<dsp:txBody><a:p><a:r><a:t>Build</a:t></a:r></a:p></dsp:txBody></dsp:sp></dsp:spTree></dsp:drawing>";

  private static byte[] zip(Map<String, String> parts) throws Exception {
    var out = new ByteArrayOutputStream();
    var zip = new ZipOutputStream(out);
    for (Map.Entry<String, String> e : parts.entrySet()) {
      zip.putNextEntry(new ZipEntry(e.getKey()));
      zip.write(e.getValue().getBytes("UTF-8"));
      zip.closeEntry();
    }
    zip.close();
    return out.toByteArray();
  }

  private static Docx.Opened open(String body, String rels, String... extra) throws Exception {
    var parts = new LinkedHashMap<String, String>();
    parts.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>");
    parts.put("word/document.xml", "<w:document xmlns:w=\"" + W + "\"><w:body>" + body + "</w:body></w:document>");
    parts.put("word/_rels/document.xml.rels",
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + rels + "</Relationships>");
    for (int i = 0; i + 1 < extra.length; i += 2) parts.put(extra[i], extra[i + 1]);
    return Docx.read(new ByteArrayInputStream(zip(parts)));
  }

  private static String rel(String id, String type, String target) {
    return "<Relationship Id=\"" + id + "\" Type=\"" + REL + "/" + type + "\" Target=\"" + target + "\"/>";
  }

  private static Docx.Opened chartDoc(String part) throws Exception {
    return open(CHART_REF, rel("rId7", "chart", "charts/chart1.xml"), "word/charts/chart1.xml", part);
  }

  private static Docx.Opened smartArtDoc() throws Exception {
    return open(DGM_REF, rel("rId1", "diagramData", "diagrams/data1.xml") + rel("rId2", "diagramLayout", "diagrams/layout1.xml")
            + rel("rId3", "diagramQuickStyle", "diagrams/quickStyle1.xml") + rel("rId4", "diagramColors", "diagrams/colors1.xml")
            + "<Relationship Id=\"rId5\" Type=\"http://schemas.microsoft.com/office/2007/relationships/diagramDrawing\" Target=\"diagrams/drawing1.xml\"/>",
        "word/diagrams/data1.xml", DGM_DATA, "word/diagrams/layout1.xml", "<dgm:layoutDef xmlns:dgm=\"x\" uniqueId=\"urn:x/layout/process1\"/>",
        "word/diagrams/quickStyle1.xml", "<q/>", "word/diagrams/colors1.xml", "<c/>", "word/diagrams/drawing1.xml", DSP);
  }

  private static int index(Document d, String kind) {
    for (int i = 0; i < d.blocks.size(); i++)
      if (d.blocks.get(i) instanceof Picture p && p.drawing != null && kind.equals(p.drawing.kind)) return i;
    throw new AssertionError("no " + kind);
  }

  private static Drawing drawing(Document d, String kind) {
    return ((Picture) d.blocks.get(index(d, kind))).drawing;
  }

  private static Map<String, String> save(Document d, Docx.Opened source) throws Exception {
    var out = new ByteArrayOutputStream();
    if (source == null) Docx.write(d, out);
    else Docx.write(d, source, out, true);
    var parts = new LinkedHashMap<String, String>();
    var in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    for (ZipEntry e; (e = in.getNextEntry()) != null; ) parts.put(e.getName(), new String(in.readAllBytes(), "UTF-8"));
    return parts;
  }

  private static Document copy(Document d, Drawing replacement, String kind) {
    var blocks = d.blocks;
    if (replacement != null) {
      int i = index(d, kind);
      Picture p = (Picture) blocks.get(i);
      blocks = blocks.set(i, Picture.drawing("i99", replacement, p.width, p.height, p.alt, p.align));
    }
    return new Document(blocks, d.page, d.title, 1000);
  }

  @Test
  public void importsBarChart() throws Exception {
    Drawing d = drawing(chartDoc(BAR).document, "chart");
    assertEquals("bar", d.get("chartType", ""));
    assertEquals("Category,North,South\nQ1,1.5,4\nQ2,2,5\nQ3,3,6", d.get("data", ""));
    assertEquals("Sales", d.get("title", ""));
    assertTrue(d.flag("dataLabels"));
    assertEquals("true", d.get("legend", ""));
  }

  @Test
  public void importsEveryPlotKind() throws Exception {
    String[] c = {"1", "2"}, v = {"3", "4"};
    String[][] cases = {
      {"<c:barChart><c:barDir val=\"col\"/>" + ser("A", c, v, "cat", "val") + "</c:barChart>", "column"},
      {"<c:lineChart>" + ser("A", c, v, "cat", "val") + "</c:lineChart>", "line"},
      {"<c:pie3DChart>" + ser("A", c, v, "cat", "val") + "</c:pie3DChart>", "pie"},
      {"<c:doughnutChart>" + ser("A", c, v, "cat", "val") + "</c:doughnutChart>", "doughnut"},
      {"<c:areaChart>" + ser("A", c, v, "cat", "val") + "</c:areaChart>", "area"},
      {"<c:scatterChart>" + ser("A", c, v, "xVal", "yVal") + "</c:scatterChart>", "scatter"},
    };
    for (String[] k : cases) {
      Drawing d = drawing(chartDoc(chartPart(k[0])).document, "chart");
      assertEquals(k[1], d.get("chartType", ""));
      assertTrue(d.get("data", ""), d.get("data", "").endsWith("\n1,3\n2,4"));
      DrawingOps.Ops ops = DrawingOps.build(d, 400, 240);
      assertTrue(k[1], ops.paths.size() > 2);
    }
  }

  @Test
  public void drawsChartForPdf() throws Exception {
    Drawing d = drawing(chartDoc(BAR).document, "chart");
    DrawingOps.Ops ops = DrawingOps.build(d, 432, 252);
    boolean title = false, label = false;
    for (DrawingOps.Text t : ops.texts) {
      title |= t.text().equals("Sales");
      label |= t.text().equals("1.5");
    }
    assertTrue(title && label);
    int bars = 0;
    for (DrawingOps.Path p : ops.paths) if (p.fill() == DrawingOps.PALETTE[1]) bars++;
    assertTrue(bars >= 3);
  }

  @Test
  public void unchangedChartKeepsOriginalPart() throws Exception {
    Docx.Opened o = chartDoc(BAR);
    var parts = save(copy(o.document, null, "chart"), o);
    String doc = parts.get("word/document.xml");
    assertTrue(doc, doc.contains("r:id=\"rId7\""));
    assertEquals(BAR, parts.get("word/charts/chart1.xml"));
    assertFalse(parts.containsKey("word/charts/cn1chart1.xml"));
  }

  @Test
  public void editedChartIsRegeneratedAndReadsBack() throws Exception {
    Docx.Opened o = chartDoc(BAR);
    Drawing edited = drawing(o.document, "chart").with("data", "Category,North\nQ1,9\nQ2,8").with("chartType", "line");
    var parts = save(copy(o.document, edited, "chart"), o);
    String xml = parts.get("word/charts/cn1chart1.xml");
    assertNotNull(xml);
    assertTrue(xml.contains("<c:lineChart>"));
    assertTrue(parts.get("word/_rels/document.xml.rels").contains("cn1chart1.xml"));
    assertTrue(parts.get("[Content_Types].xml").contains("drawingml.chart+xml"));
    // The generated part parses back to the same chart.
    Drawing back = DocxCharts.chart(Xml.parse(xml.getBytes("UTF-8")), new java.util.HashSet<String>());
    assertEquals("line", back.get("chartType", ""));
    assertEquals("Category,North\nQ1,9\nQ2,8", back.get("data", ""));
    assertEquals("Sales", back.get("title", ""));
  }

  @Test
  public void insertedChartsWriteChartPartsForEveryType() throws Exception {
    for (String type : DrawingOps.CHARTS) {
      Drawing d = Drawing.of("chart").with("chartType", type).with("data", DrawingOps.SAMPLE_CHART).with("title", "T");
      String xml = DocxCharts.chartXml(d);
      Drawing back = DocxCharts.chart(Xml.parse(xml.getBytes("UTF-8")), new java.util.HashSet<String>());
      assertEquals(type, back.get("chartType", ""));
      boolean single = "pie".equals(type) || "doughnut".equals(type);
      if (!single && !"scatter".equals(type)) assertEquals(DrawingOps.SAMPLE_CHART, back.get("data", ""));
    }
  }

  @Test
  public void importsSmartArtWithSavedDrawing() throws Exception {
    Docx.Opened o = smartArtDoc();
    Drawing d = drawing(o.document, "smartArt");
    assertEquals("process", d.get("layout", ""));
    assertEquals("Plan\n\tDetail\nBuild", d.get("nodes", ""));
    DrawingOps.Ops ops = DrawingOps.build(d, 432, 252);
    boolean accent2 = false, custom = false;
    for (DrawingOps.Path p : ops.paths) {
      accent2 |= p.fill() == DrawingOps.PALETTE[1];
      custom |= p.fill() == 0x112233;
    }
    assertTrue(accent2 && custom);
    // Unedited: the diagram parts are referenced again.
    var parts = save(copy(o.document, null, "smartArt"), o);
    assertTrue(parts.get("word/document.xml").contains("r:dm=\"rId1\""));
    // Edited text: drawn from the basic layout and saved as shapes.
    Drawing edited = d.with("nodes", "One\nTwo");
    DrawingOps.Ops ops2 = DrawingOps.build(edited, 432, 252);
    boolean one = false;
    for (DrawingOps.Text t : ops2.texts) one |= t.text().contains("One");
    assertTrue(one);
    String doc = save(copy(o.document, edited, "smartArt"), o).get("word/document.xml");
    // Edited SmartArt is written as newly generated diagram parts, as Wordcraft does.
    assertTrue(doc.contains("r:dm=\"cn1Dgm"));
    assertFalse(doc.contains("wpg:wgp"));
  }

  @Test
  public void createdSmartArtRoundTripsAsDiagramParts() throws Exception {
    String[] layouts = {"list", "process", "cycle", "hierarchy", "pyramid", "radial", "matrix", "venn"};
    String[] colors = {"accent1", "colorful", "accent4"};
    for (int i = 0; i < layouts.length; i++) {
      Drawing d = Drawing.of("smartArt").with("layout", layouts[i]).with("nodes", "Plan\n\tDetail & more\nBuild\nShip")
          .with("colors", colors[i % colors.length]);
      var blocks = new Sequence<Block>().append(Picture.drawing("i1", d, 432, 252, "", "center"));
      var out = new ByteArrayOutputStream();
      Docx.write(new Document(blocks, PageSpec.A4, "t", 1), out);
      var parts = new LinkedHashMap<String, String>();
      var in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) parts.put(e.getName(), new String(in.readAllBytes(), "UTF-8"));
      int found = 0;
      for (Map.Entry<String, String> e : parts.entrySet())
        if (e.getKey().startsWith("word/diagrams/")) {
          found++;
          Xml.parse(e.getValue().getBytes("UTF-8")); // well-formed
          assertTrue(parts.get("[Content_Types].xml").contains("/" + e.getKey()));
        }
      assertEquals(5, found);
      String rels = parts.get("word/_rels/document.xml.rels");
      assertTrue(rels.contains("relationships/diagramData") && rels.contains("relationships/diagramDrawing"));
      // Read the diagram parts as Word would: without the app's own encoded drawing in docPr.
      parts.put("word/document.xml", parts.get("word/document.xml").replaceAll("<a:extLst[^>]*><a:ext[^>]*><cn:drawing[^>]*/></a:ext></a:extLst>", ""));
      assertFalse(parts.get("word/document.xml").contains("cn:drawing"));
      Docx.Opened back = Docx.read(new ByteArrayInputStream(zip(parts)));
      Drawing r = drawing(back.document, "smartArt");
      assertEquals(layouts[i], r.get("layout", ""));
      assertEquals("Plan\n\tDetail & more\nBuild\nShip", r.get("nodes", ""));
      assertEquals(colors[i % colors.length], r.get("colors", ""));
      assertFalse(layouts[i], r.get("shapes", "").isEmpty()); // the cached drawing was read
      assertTrue(r.get("shapes", "").contains("Plan"));
    }
  }

  @Test
  public void cachedDrawingCarriesConnectors() throws Exception {
    String nodes = "Plan\n\tA\n\tB\nBuild\nShip";
    String hier = DocxSmartArt.drawingXml(Drawing.of("smartArt").with("layout", "hierarchy").with("nodes", nodes), 432, 252);
    Xml.parse(hier.getBytes("UTF-8"));
    // Wordcraft's tree: Plan, Build and Ship are roots; Plan gets a trunk, a bar and a drop to A and B.
    assertEquals(4, count(hier, "prst=\"line\""));
    String radial = DocxSmartArt.drawingXml(Drawing.of("smartArt").with("layout", "radial").with("nodes", nodes), 432, 252);
    assertEquals(4, count(radial, "prst=\"line\"")); // spokes to A, B (Plan's children), Build and Ship
    // Read back as Word would, the connectors draw as open strokes.
    String shapes = DocxCharts.shapes(Xml.parse(hier.getBytes("UTF-8")), Math.round(432 * 12700.0), Math.round(252 * 12700.0));
    var ops = new DrawingOps.Ops();
    DrawingOps.savedShapes(ops, shapes, 432, 252);
    assertEquals(4, openPaths(ops));
  }

  @Test
  public void smartArtDrawsConnectorsAndCycleArrowsOnScreen() {
    // Three levels: Root > (A > A1, A2), B. Every parent joins its children with elbow lines.
    var tree = DrawingOps.build(Drawing.of("smartArt").with("layout", "hierarchy").with("nodes", "Root\n\tA\n\t\tA1\n\t\tA2\n\tB"), 400, 300);
    assertEquals(8, openPaths(tree)); // trunk, bar and two drops under Root and under A
    var spokes = DrawingOps.build(Drawing.of("smartArt").with("layout", "radial").with("nodes", "Hub\n\tA\n\tB\nC"), 300, 300);
    assertEquals(3, openPaths(spokes));
    // Cycle: no ring, an arrow between each pair of circles (filled, rotated along the circle).
    var cycle = DrawingOps.build(Drawing.of("smartArt").with("layout", "cycle").with("nodes", "One\nTwo\nThree\nFour"), 300, 300);
    assertEquals(0, openPaths(cycle));
    int arrows = 0, circles = 0;
    for (var p : cycle.paths) if (p.closed() && p.points().length == 14) arrows++;
    for (Object[] s : cycle.shapes) if ("ellipse".equals(s[0])) circles++;
    assertEquals(4, arrows);
    assertEquals(4, circles);
  }

  private static int openPaths(DrawingOps.Ops ops) {
    int open = 0;
    for (var p : ops.paths) if (!p.closed()) open++;
    return open;
  }

  private static int count(String s, String sub) {
    int n = 0;
    for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
    return n;
  }

  @Test
  public void hostileChartsAndDiagramsAreBounded() throws Exception {
    var many = new StringBuilder("<c:lineChart>");
    for (int s = 0; s < 60; s++) many.append(ser("S,\n" + s, new String[] {"a,b"}, new String[] {"NaN"}, "cat", "val"));
    many.append("<c:ser><c:val><c:numCache><c:ptCount val=\"99999999\"/><c:pt idx=\"-5\"><c:v>1</c:v></c:pt>"
        + "<c:pt idx=\"2147483647\"><c:v>1e400</c:v></c:pt></c:numCache></c:val></c:ser></c:lineChart>");
    Docx.Opened o = chartDoc(chartPart(many.toString()));
    Drawing d = drawing(o.document, "chart");
    var rows = DrawingOps.table(d.get("data", ""));
    assertTrue(rows.size() <= DocxCharts.MAX_ROWS + 1);
    assertTrue(rows.get(0).length <= DocxCharts.MAX_SERIES + 1);
    assertTrue(o.warnings.toString(), o.warnings.toString().contains("series"));
    DrawingOps.build(d, 300, 200);
    // Missing part, unknown plot and broken XML only warn.
    Docx.Opened missing = open(CHART_REF, "");
    assertTrue(missing.warnings.toString(), !missing.warnings.isEmpty());
    Docx.Opened unknown = chartDoc(chartPart("<c:foo/>"));
    assertTrue(unknown.warnings.toString(), unknown.warnings.toString().contains("not supported"));
    Docx.Opened broken = chartDoc("<c:chartSpace");
    assertFalse(broken.warnings.isEmpty());
    // A cyclic diagram model terminates.
    String cyclic = DGM_DATA.replace("srcId=\"1\" destId=\"3\"", "srcId=\"1\" destId=\"0\"");
    String nodes = DocxCharts.nodes(Xml.parse(cyclic.getBytes("UTF-8")));
    assertTrue(nodes, nodes.contains("Plan"));
    // Junk saved shapes draw nothing rather than failing.
    DrawingOps.savedShapes(new DrawingOps.Ops(), "x\n,,,\nrect,a,b,c,d,e,f,g,h,i,j,k,\n", 100, 100);
  }
}
