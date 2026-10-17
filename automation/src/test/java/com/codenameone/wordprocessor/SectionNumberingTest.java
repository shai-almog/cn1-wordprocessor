package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class SectionNumberingTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    Map<String, Object> a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static final LayoutEngine.Metrics METRICS =
      new LayoutEngine.Metrics() {
        public float width(String text, TextStyle style) {
          return text.length() * style.size * .5f;
        }

        public float height(TextStyle style) {
          return style.size * 1.2f;
        }
      };

  @Test
  public void romanPageNumbersAndLineNumbersLayoutAndRoundTrip() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Page ");
    run(s, "insert.pageNumber");
    for (int i = 0; i < 4; i++) {
      s.enterParagraph();
      s.replace("Line " + i);
    }
    run(s, "layout.pageNumberFormat", "format", "lowerRoman", "start", 4);
    run(s, "layout.lineNumbers", "countBy", 2, "restart", "continuous");
    PageDecor d = s.activeSection().raw.decor;
    assertEquals("lowerRoman", d.numberFormat);
    assertEquals(2, d.lineCountBy);
    assertEquals(4, s.activeSection().raw.pageStart);

    LayoutEngine.Result r = new LayoutEngine(METRICS).layout(s.document(), 0);
    LayoutEngine.Page page = r.pages.get(0);
    assertEquals(2, page.lineNumbers.size());
    assertEquals(2, (int) page.lineNumbers.get(0)[1]);
    assertEquals(4, (int) page.lineNumbers.get(1)[1]);
    s.mapFields(DocumentFields.resolver(s.document(), r, new LinkedHashMap<String, String>()));
    assertTrue(s.document().plainText(), s.document().paragraph(0).text.plainText().startsWith("Page iv"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened back = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(back.warnings.toString(), back.warnings.isEmpty());
    assertEquals(d, back.document.lastSection.decor);
    assertEquals(d, DocumentXml.read(DocumentXml.write(s.document())).lastSection.decor);
  }
}
