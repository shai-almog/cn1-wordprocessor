package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.zip.*;
import org.junit.Test;

/** word/settings.xml children are written in CT_Settings schema order, input settings kept. */
public class DocxSettingsOrderTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

  private static byte[] docx(String settings) throws Exception {
    var out = new ByteArrayOutputStream();
    var zip = new ZipOutputStream(out);
    zip.putNextEntry(new ZipEntry("word/document.xml"));
    zip.write(("<w:document xmlns:w=\"" + W + "\"><w:body><w:p><w:r><w:t>a</w:t></w:r></w:p></w:body></w:document>").getBytes("UTF-8"));
    zip.closeEntry();
    if (settings != null) {
      zip.putNextEntry(new ZipEntry("word/settings.xml"));
      zip.write(settings.getBytes("UTF-8"));
      zip.closeEntry();
    }
    zip.close();
    return out.toByteArray();
  }

  private static String settings(Document d, Docx.Opened source) throws Exception {
    var out = new ByteArrayOutputStream();
    Docx.write(d, source, out, true);
    var zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    for (ZipEntry e; (e = zip.getNextEntry()) != null; )
      if ("word/settings.xml".equals(e.getName())) return new String(zip.readAllBytes(), "UTF-8");
    return null;
  }

  private static void ordered(String xml, String... names) {
    int last = -1;
    for (String n : names) {
      int at = xml.indexOf("<w:" + n);
      assertTrue(n + " missing: " + xml, at >= 0);
      assertTrue(n + " out of order: " + xml, at > last);
      last = at;
    }
  }

  @Test
  public void readSettingsAreWrittenInSchemaOrder() throws Exception {
    var opened = Docx.read(new ByteArrayInputStream(docx("<w:settings xmlns:w=\"" + W + "\"><w:zoom w:percent=\"120\"/>"
        + "<w:defaultTabStop w:val=\"720\"/><w:compat><w:compatSetting w:name=\"compatibilityMode\""
        + " w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>")));
    ordered(settings(opened.document.trackChanges(true), opened), "zoom", "trackRevisions", "defaultTabStop", "footnotePr", "endnotePr", "compat");
  }

  @Test
  public void newSettingsAreWrittenInSchemaOrder() throws Exception {
    var d = Docx.read(new ByteArrayInputStream(docx(null))).document.trackChanges(true);
    ordered(settings(d, null), "trackRevisions", "footnotePr", "compat");
  }
}
