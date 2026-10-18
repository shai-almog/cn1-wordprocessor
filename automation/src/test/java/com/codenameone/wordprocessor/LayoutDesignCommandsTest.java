package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class LayoutDesignCommandsTest {
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

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static Document roundTrip(Document d) throws Exception {
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    return Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
  }

  @Test
  public void paragraphDirectionMirrorsAndRoundTrips() throws Exception {
    var s = new Session(Document.empty());
    s.replace("shalom");
    run(s, "para.dialog", "left", 36, "right", 0, "align", "left", "after", 4);
    run(s, "para.rtl");
    var p = s.document().paragraph(0);
    assertTrue(p.format.rtl);
    assertEquals("right", p.align);
    assertEquals(0, p.indent, 0);
    assertEquals(36, p.format.rightIndent, 0);
    run(s, "para.asianTypography", "kinsoku", false, "overflowPunct", false);
    assertFalse(s.document().paragraph(0).format.asian("kinsoku"));
    assertTrue(s.document().paragraph(0).format.asian("wordWrap"));
    for (Document back : new Document[] {DocumentXml.read(DocumentXml.write(s.document())), roundTrip(s.document())}) {
      assertTrue(back.paragraph(0).format.rtl);
      assertEquals("kinsoku,overflowPunct", back.paragraph(0).format.asianOff);
    }
    run(s, "para.ltr");
    assertEquals("left", s.document().paragraph(0).align);
    assertEquals(36, s.document().paragraph(0).indent, 0);
    s.undo();
    assertTrue(s.document().paragraph(0).format.rtl);
  }

  @Test
  public void sectionLayoutAffectsPaginationAndRoundTrips() throws Exception {
    var s = new Session(Document.empty());
    s.replace("one line");
    float top = new LayoutEngine(METRICS).layout(s.document(), 0).pages.get(0).lines.get(0).y;
    run(s, "layout.verticalAlign", "value", "center");
    var page = new LayoutEngine(METRICS).layout(s.document(), 0).pages.get(0);
    assertTrue(page.lines.get(0).y > top + 200);
    run(s, "layout.sectionRtl", "value", true);
    run(s, "layout.textDirection", "value", "tbRl");
    run(s, "layout.documentGrid", "type", "lines", "linePitch", 30);
    run(s, "layout.verticalAlign", "value", "top");
    var grid = new LayoutEngine(METRICS).layout(s.document(), 0).pages.get(0);
    assertEquals(30, grid.lines.get(0).height, .01);
    for (Document back : new Document[] {DocumentXml.read(DocumentXml.write(s.document())), roundTrip(s.document())}) {
      var d = back.sectionRanges().get(0).raw.decor;
      assertEquals("true", d.layout("rtl", ""));
      assertEquals("tbRl", d.layout("textDirection", ""));
      assertEquals("lines", d.layout("grid", ""));
      assertEquals(30, Float.parseFloat(d.layout("gridPitch", "0")), .01);
    }
    Map<?, ?> setup = (Map<?, ?>) run(s, "layout.pageSetup", "orientation", "landscape", "left", 90);
    assertEquals("landscape", setup.get("orientation"));
    assertEquals(90f, setup.get("left"));
    run(s, "layout.hyphenation", "mode", "automatic", "zone", 18);
    assertEquals("automatic", s.document().properties.setting("hyphenation", ""));
    assertEquals("automatic", roundTrip(s.document()).properties.setting("hyphenation", ""));
  }

  @Test
  public void designRestylesHeadingsAndRecordsSettings() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Head");
    s.applyStyle("Heading1");
    run(s, "design.theme", "name", "Facet");
    assertEquals("Trebuchet MS", s.document().styles.get("Heading1").text.font);
    assertEquals(0x5b8a1f, s.document().styles.get("Heading1").text.color);
    assertEquals("Trebuchet MS", s.document().paragraph(0).text.styleAt(0).font);
    run(s, "design.themeColors", "name", "Red");
    assertEquals(0xc00000, s.document().styles.get("Heading2").text.color);
    run(s, "design.themeFonts", "name", "Georgia");
    assertEquals("Georgia", s.document().styles.get("Normal").text.font);
    run(s, "design.styleSet", "name", "Lines (Simple)");
    assertTrue(s.document().styles.get("Heading1").paragraph.format.borders.bottom.present());
    run(s, "design.paragraphSpacing", "name", "Double");
    assertEquals(2, s.document().styles.get("Normal").paragraph.lineSpacing, 0);
    run(s, "design.effects", "name", "Top Shadow");
    assertEquals("Lines (Simple)", s.document().properties.setting("design.styleSet", ""));
    assertEquals("Georgia", roundTrip(s.document()).properties.setting("design.fonts", ""));
    run(s, "design.setDefault");
    var fresh = new Session(Document.empty());
    LayoutDesignCommands.applyDefaults(commands, fresh);
    assertEquals("Georgia", fresh.document().styles.get("Normal").text.font);
    try {
      run(s, "design.theme", "name", "Nope");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
