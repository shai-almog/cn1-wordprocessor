package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

/** Tracked table property changes (w:tblPrChange/w:trPrChange/w:tcPrChange, Wordcraft fmt_change). */
public class TablePropertyRevisionTest {
  private Session tracked() {
    var s = new Session(Document.empty());
    s.replace("body");
    s.select(0, 4, 4);
    s.insertTable(2, 2);
    s.trackChanges(true);
    var t = (Table) s.document().blocks.get(1);
    var f = t.format.cell(1, 1, t.format.cell(1, 1).background(0xff0000))
        .row(0, new TableFormat.Row(30, "exact", false)).withProp("rtl", "true");
    s.formatTable(1, f);
    return s;
  }

  private static Table table(Document d) {
    for (Block b : d.blocks) if (b instanceof Table t) return t;
    throw new AssertionError("no table");
  }

  private static void verifyTracked(Document d) {
    var f = table(d).format;
    assertNotNull(f.revision());
    assertEquals("format", f.revision().kind);
    assertEquals(0xff0000, f.cell(1, 1).background);
    assertEquals(0xffffff, f.previous().cell(1, 1).background);
    assertEquals(30, f.minHeight(0), .01);
    assertEquals(0, f.previous().minHeight(0), .01);
    assertEquals("true", f.prop("rtl", ""));
    assertEquals("", f.previous().prop("rtl", ""));
    boolean listed = false;
    for (Revisions.Change c : Revisions.list(d)) listed |= "Table properties".equals(c.text);
    assertTrue(listed);
  }

  @Test public void editsAreTrackedAndResolved() {
    var s = tracked();
    var d = s.document();
    verifyTracked(d);
    var id = table(d).format.revision().id;
    var rejected = Revisions.resolve(d, id, false);
    assertNull(table(rejected).format.revision());
    assertEquals(0xffffff, table(rejected).format.cell(1, 1).background);
    assertFalse(table(rejected).format.row(0).rule.equals("exact"));
    var accepted = Revisions.resolve(d, id, true);
    assertNull(table(accepted).format.revision());
    assertEquals(0xff0000, table(accepted).format.cell(1, 1).background);
  }

  @Test public void untrackedEditsAreNotRecorded() {
    var s = new Session(Document.empty());
    s.replace("body");
    s.select(0, 4, 4);
    s.insertTable(2, 2);
    var t = (Table) s.document().blocks.get(1);
    s.formatTable(1, t.format.withProp("rtl", "true"));
    assertNull(table(s.document()).format.revision());
  }

  @Test public void docxRoundTrip() throws Exception {
    var bytes = new ByteArrayOutputStream();
    Docx.write(tracked().document(), bytes);
    String xml = new String(bytes.toByteArray(), "ISO-8859-1");
    var opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    verifyTracked(opened.document);
  }

  @Test public void nativeRoundTrip() throws Exception {
    String xml = DocumentXml.write(tracked().document());
    var d = DocumentXml.read(xml);
    assertEquals(xml, DocumentXml.write(d));
    verifyTracked(d);
  }
}
