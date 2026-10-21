package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import org.junit.Test;

/** Right-to-left mirroring of cell lines, float gaps, drop caps and list labels (Wordcraft para.rs). */
public class RtlMirrorLinesTest {
  private final Commands commands = new Commands();
  private static final LayoutEngine.Metrics METRICS =
      new LayoutEngine.Metrics() {
        public float width(String text, TextStyle style) {
          return text.length() * style.size * .5f;
        }

        public float height(TextStyle style) {
          return style.size * 1.2f;
        }
      };

  private void run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    commands.execute(s, id, a);
  }

  private static LayoutEngine.Line first(Document d, String id) {
    for (LayoutEngine.Line l : new LayoutEngine(METRICS).layout(d, 0).pages.get(0).allLines())
      if (id.equals(l.paragraphId) && !l.spans.isEmpty()) return l;
    throw new AssertionError("no line for " + id);
  }

  @Test
  public void cellLinesMirrorWithinTheCell() {
    var s = new Session(Document.empty());
    s.replace("x");
    s.insertTable(1, 2);
    s.setCell(1, 0, 0, "ab");
    String id = ((Table) s.document().blocks.get(1)).cell(0, 0).get(0).id;
    var ltr = first(s.document(), id);
    s.enterCell(1, 0, 0);
    // Start-aligned right to left text: laid out from the cell's right edge.
    s.mapParagraphs(p -> p.with("rtl", "true"));
    s.leaveCell();
    var rtl = first(s.document(), id);
    assertTrue(((Table) s.document().blocks.get(1)).cell(0, 0).get(0).format.rtl);
    assertTrue(rtl.x + " " + ltr.x + " " + rtl.block + " " + rtl.row, rtl.rtl);
    assertTrue(ltr.x + " -> " + rtl.x + " w" + ltr.width + "/" + rtl.width + " " + rtl.spans.get(0).x, rtl.x > ltr.x + 50);
  }

  @Test
  public void listLabelSitsRightOfRtlText() {
    var s = new Session(Document.empty());
    s.replace("item");
    run(s, "para.bullets");
    run(s, "para.rtl");
    var line = first(s.document(), s.document().paragraph(0).id);
    assertFalse(line.marker.isEmpty());
    assertTrue(line.rtl);
    assertTrue(line.markerX(6) >= line.x + line.width);
  }

  @Test
  public void dropCapHangsAtTheRightEdge() {
    var s = new Session(Document.empty());
    s.replace("Drop capped right to left paragraph text that wraps over several lines of the page width here");
    run(s, "insert.dropCap", "position", "drop", "lines", 2);
    String id = s.document().paragraph(0).id;
    float ltrDrop = 0, rtlDrop = 0;
    for (LayoutEngine.Line l : new LayoutEngine(METRICS).layout(s.document(), 0).pages.get(0).allLines())
      if (id.equals(l.paragraphId) && l.spans.size() == 1 && "D".equals(l.spans.get(0).text)) ltrDrop = l.x;
    run(s, "para.rtl");
    for (LayoutEngine.Line l : new LayoutEngine(METRICS).layout(s.document(), 0).pages.get(0).allLines())
      if (id.equals(l.paragraphId) && l.spans.size() == 1 && "D".equals(l.spans.get(0).text)) rtlDrop = l.x;
    assertTrue(ltrDrop + " -> " + rtlDrop, rtlDrop > ltrDrop + 100);
  }

  @Test
  public void floatGapsMirror() {
    float[] cut = {10, 20, 0, 100, 150, 200, 260};
    float[] m = LayoutEngine.mirrorCut(cut, 400);
    assertArrayEquals(new float[] {20, 10, 0, 140, 200, 250, 300}, m, 0.001f);
  }
}
