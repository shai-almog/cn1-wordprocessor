package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/** DATE/TIME pictures and AUTHOR as Wordcraft (references.rs date_picture, insert.rs format_date, fields.rs). */
public class DateAuthorFieldTest {
  @Test
  public void datePictures() {
    assertEquals("MMMM d, yyyy", DocumentFields.datePicture("DATE \\@ \"MMMM d, yyyy\" \\* MERGEFORMAT"));
    assertEquals("HH:mm", DocumentFields.datePicture("TIME \\@ HH:mm"));
    assertNull(DocumentFields.datePicture("DATE"));
    // 2026-01-02 is a Friday (weekday 5), 14:05:09.
    assertEquals("January 2, 2026", DocumentFields.formatDate("MMMM d, yyyy", 2026, 1, 2, 14, 5, 9, 5));
    assertEquals("2026-01-02", DocumentFields.formatDate("yyyy-MM-dd", 2026, 1, 2, 14, 5, 9, 5));
    assertEquals("Friday, Jan 02 26", DocumentFields.formatDate("dddd, MMM dd yy", 2026, 1, 2, 14, 5, 9, 5));
    assertEquals("2:05:09 PM", DocumentFields.formatDate("h:mm:ss am/pm", 2026, 1, 2, 14, 5, 9, 5));
    assertEquals("12:05 AM", DocumentFields.formatDate("h:mm AM/PM", 2026, 1, 2, 0, 5, 9, 5));
    assertEquals("Fri 14", DocumentFields.formatDate("ddd HH", 2026, 1, 2, 14, 5, 9, 5));
  }

  private static String author(String saved) {
    var p = new Paragraph("p1", RichText.field(new InlineField("AUTHOR", saved, false, false), TextStyle.DEFAULT));
    var d = new Document(new Sequence<Block>().append(p), PageSpec.A4, "T", 2);
    d = d.properties(d.properties.with("author", "Ada"));
    var s = new Session(d);
    var engine = new LayoutEngine(new LayoutEngine.Metrics() {
      public float width(String t, TextStyle st) { return t.length() * 6; }
      public float height(TextStyle st) { return 12; }
    });
    s.mapFields(DocumentFields.resolver(s.document(), engine.layout(s.document(), 0), Collections.<String, String>emptyMap()));
    return s.document().paragraph(0).text.plainText();
  }

  @Test
  public void authorFromPropertiesOnlyWhenResultEmpty() {
    assertEquals("Ada", author(""));
    assertEquals("Grace", author("Grace"));
  }
}
