package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/** Stacked and 100% stacked charts as Wordcraft (read/chart.rs grouping, chart_spec.rs writing). */
public class ChartStackingTest {
  private static String part(String kind, String dir, String grouping) {
    String ser = "";
    for (int s = 0; s < 2; s++)
      ser += "<c:ser><c:idx val=\"" + s + "\"/><c:tx><c:v>S" + s + "</c:v></c:tx>"
          + "<c:cat><c:strLit><c:ptCount val=\"2\"/><c:pt idx=\"0\"><c:v>A</c:v></c:pt><c:pt idx=\"1\"><c:v>B</c:v></c:pt></c:strLit></c:cat>"
          + "<c:val><c:numLit><c:ptCount val=\"2\"/><c:pt idx=\"0\"><c:v>" + (s + 1) + "</c:v></c:pt><c:pt idx=\"1\"><c:v>3</c:v></c:pt></c:numLit></c:val></c:ser>";
    return "<c:chartSpace xmlns:c=\"" + DocxCharts.CHART + "\"><c:chart><c:plotArea><c:" + kind + ">"
        + (dir == null ? "" : "<c:barDir val=\"" + dir + "\"/>") + "<c:grouping val=\"" + grouping + "\"/>" + ser
        + "</c:" + kind + "></c:plotArea></c:chart></c:chartSpace>";
  }

  private static Drawing read(String xml) throws Exception {
    return DocxCharts.chart(Xml.parse(xml), new LinkedHashSet<>());
  }

  @Test
  public void groupingsReadWrittenAndDrawn() throws Exception {
    Drawing col = read(part("barChart", "col", "stacked"));
    assertEquals("column", col.get("chartType", ""));
    assertEquals("stacked", col.get("grouping", ""));
    String xml = DocxCharts.chartXml(col);
    assertTrue(xml, xml.contains("<c:grouping val=\"stacked\"/>") && xml.contains("<c:overlap val=\"100\"/>"));
    assertEquals("stacked", read(xml).get("grouping", ""));
    assertEquals("percentStacked", read(part("areaChart", null, "percentStacked")).get("grouping", ""));
    assertEquals("", read(part("barChart", "bar", "clustered")).get("grouping", ""));

    // Stacked columns: the second series sits on the first; category A is 1 + 2 = 3 tall.
    var ops = DrawingOps.build(col, 400, 250);
    float[] first = null, second = null;
    for (DrawingOps.Path p : ops.paths) {
      if (p.fill() == DrawingOps.PALETTE[0] && first == null) first = p.points();
      if (p.fill() == DrawingOps.PALETTE[1] && second == null) second = p.points();
    }
    assertNotNull(first);
    assertNotNull(second);
    float top1 = Math.min(first[1], first[5]), bottom2 = Math.max(second[1], second[5]);
    assertEquals(top1, bottom2, .5f);
    assertEquals(Math.min(first[0], first[4]), Math.min(second[0], second[4]), .01f);

    // 100% stacked: axis labels in percent.
    var pct = DrawingOps.build(read(part("barChart", "col", "percentStacked")), 400, 250);
    boolean hundred = false;
    for (DrawingOps.Text t : pct.texts) hundred |= t.text().equals("100%");
    assertTrue(hundred);
  }
}
