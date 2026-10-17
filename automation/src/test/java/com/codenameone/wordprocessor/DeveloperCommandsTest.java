package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DeveloperCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    Map<String, Object> a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static List<String> controls(Document d) {
    final List<String> out = new ArrayList<String>();
    for (Block b : d.blocks)
      if (b instanceof Paragraph)
        ((Paragraph) b)
            .text.visit(
                new RichText.Visitor() {
                  public void run(int offset, String text, TextStyle style) {}

                  public void field(int offset, InlineField f, TextStyle style) {
                    if (ContentControl.is(f)) out.add(f.instruction + "=" + f.result);
                  }
                });
    return out;
  }

  @Test
  public void controlsInsertChangeAndRoundTrip() throws Exception {
    Session s = new Session(Document.empty());
    run(s, "developer.checkBox", "title", "Agree");
    run(s, "developer.properties", "checked", true);
    assertTrue(s.document().paragraph(0).text.plainText().length() == 1);
    run(s, "developer.dropDown", "items", "Red|Green|Blue", "text", "Green", "tag", "color");
    try {
      run(s, "developer.properties", "value", "Purple");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    run(s, "developer.properties", "value", "Blue");
    run(s, "developer.datePicker", "date", "2026-10-17", "format", "MMMM d, yyyy");
    run(s, "developer.plainText", "title", "Name", "text", "Ada");
    run(s, "developer.richText");
    run(s, "developer.comboBox", "items", "A|B");
    run(s, "developer.buildingBlock");
    run(s, "developer.picture");
    run(s, "developer.repeatingSection", "items", "Row 1|Row 2");
    List<String> before = controls(s.document());
    assertEquals(9, before.size());
    assertTrue(before.get(0), before.get(0).contains("checked=true") && before.get(0).endsWith("=\u2612"));
    assertTrue(before.get(1).endsWith("=Blue"));
    assertTrue(before.get(2).endsWith("=October 17, 2026"));
    assertEquals(true, run(s, "developer.designMode"));
    assertTrue(s.document().paragraph(0).text.plainText().length() > 0);
    run(s, "developer.designMode", "value", false);

    assertEquals(before, controls(DocumentXml.read(DocumentXml.write(s.document()))));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened back = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(before, controls(back.document));
  }
}
