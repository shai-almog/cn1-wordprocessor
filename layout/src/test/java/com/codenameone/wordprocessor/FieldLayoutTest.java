package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class FieldLayoutTest {
  private LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String t, TextStyle s) {
            return t.length() * 6;
          }

          public float height(TextStyle s) {
            return 12;
          }
        });
  }

  @Test
  public void fieldHasAtomicSourceRangeAndResultWidthIncludingDecimalTab() {
    var text =
        RichText.of("Price\t")
            .concat(
                RichText.field(
                    new InlineField("MERGEFIELD Price", "123.45", false, false),
                    TextStyle.DEFAULT));
    var p = new Paragraph("p1", text).with("tabs", "120,decimal,dot");
    var d = new Document(new Sequence<Block>().append(p), PageSpec.A4, "Title", 2);
    var line = engine().layout(d, 0).pages.get(0).lines.get(0);
    var field = line.spans.get(2);
    assertEquals(6, field.start);
    assertEquals(7, field.end);
    assertEquals(36, field.width, .001);
    assertEquals(102, field.x, .001);
    assertEquals("123.45", field.displayText);
  }

  @Test
  public void pageCountsPropertiesMergeValuesAndLockedFieldsResolve() {
    var text =
        RichText.field(
                new InlineField("PAGE \\* ROMAN \\* Upper", "?", false, false), TextStyle.DEFAULT)
            .concat(
                RichText.field(new InlineField("NUMPAGES", "?", false, false), TextStyle.DEFAULT))
            .concat(RichText.field(new InlineField("TITLE", "?", false, false), TextStyle.DEFAULT))
            .concat(
                RichText.field(
                    new InlineField("MERGEFIELD \"Full name\"", "?", false, false),
                    TextStyle.DEFAULT))
            .concat(
                RichText.field(new InlineField("PAGE", "Locked", true, false), TextStyle.DEFAULT));
    var p = new Paragraph("p1", text);
    var second = new Paragraph("p2", text).with("pageBreak", "true");
    var s =
        new Session(
            new Document(new Sequence<Block>().append(p).append(second), PageSpec.A4, "Title", 3));
    var values = new HashMap<String, String>();
    values.put("Full name", "Ada Lovelace");
    s.mapFields(DocumentFields.resolver(s.document(), engine().layout(s.document(), 0), values));
    assertEquals("I2TitleAda LovelaceLocked", s.document().paragraph(0).text.plainText());
    assertEquals("II2TitleAda LovelaceLocked", s.document().paragraph(1).text.plainText());
  }
}
