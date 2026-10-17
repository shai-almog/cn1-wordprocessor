package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class TabInheritanceTest {
  private Document roundtrip(Document d) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened result = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(result.warnings.toString(), result.warnings.isEmpty());
    return result.document;
  }

  private Session styled() {
    Session s = new Session(Document.empty());
    s.replace("A\tB");
    s.defineStyle(
        new ParagraphStyle(
            "Tabs",
            "Tabs",
            s.document().paragraph(0).with("tabs", "60,bar,none;120,right,dot"),
            TextStyle.DEFAULT));
    s.applyStyle("Tabs");
    return s;
  }

  @Test
  public void clearedInheritedTabsStayClearedAfterTwoRoundTrips() throws Exception {
    Session s = styled();
    s.paragraph("tabs", "");
    Document d = roundtrip(roundtrip(s.document()));
    assertTrue(d.paragraph(0).format.tabs.isEmpty());
    assertEquals(
        "60.0,bar,none;120.0,right,dot",
        TabStop.encode(d.styles.get("Tabs").paragraph.format.tabs));
    s.undo();
    assertEquals(2, s.document().paragraph(0).format.tabs.size());
    s.redo();
    assertTrue(s.document().paragraph(0).format.tabs.isEmpty());
  }

  @Test
  public void partialClearsAndChangedAlignmentSurviveInCellsAndHeaders() throws Exception {
    Session s = styled();
    s.paragraph("tabs", "120,left,hyphen");
    s.insertTable(1, 1);
    s.enterCell(1, 0, 0);
    s.applyStyle("Tabs");
    s.paragraph("tabs", "60,bar,none");
    Paragraph header =
        new Paragraph("hp", RichText.of("Header", TextStyle.DEFAULT)).with("style", "Tabs");
    Document d =
        roundtrip(
            s.document()
                .furniture(
                    PageFurniture.EMPTY.story("header", new Sequence<Paragraph>().append(header))));
    assertEquals("120.0,left,hyphen", TabStop.encode(d.paragraph(0).format.tabs));
    assertEquals(
        "60.0,bar,none", TabStop.encode(((Table) d.blocks.get(1)).cell(0, 0).get(0).format.tabs));
    assertTrue(d.furniture.get("header").get(0).format.tabs.isEmpty());
  }

  @Test
  public void barOnlyParagraphRoundTripsAndProducesPdfRuleWithoutTabCharacter() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Text without tabs");
    s.paragraph("tabs", "48,bar,none;144,bar,none");
    assertEquals(
        "48.0,bar,none;144.0,bar,none",
        TabStop.encode(roundtrip(s.document()).paragraph(0).format.tabs));
    assertEquals(
        DocumentXml.write(s.document()),
        DocumentXml.write(DocumentXml.read(DocumentXml.write(s.document()))));
    String pdf =
        new String(
            PdfExporter.write(s.document().page(new PageSpec(240, 300, 20, 20, 20, 20))),
            "ISO-8859-1");
    assertTrue(pdf, pdf.contains("0 G 0.5 w 68.000 280.000 m 68.000 "));
    assertTrue(pdf, pdf.contains("0 G 0.5 w 164.000 280.000 m 164.000 "));
  }

  @Test
  public void nondefaultBarIntervalUsesDocumentSetting() throws Exception {
    Session s = new Session(Document.empty());
    s.paragraph("tabs", "48,bar,none");
    s.paragraph("tabInterval", "48");
    Document d = roundtrip(s.document());
    assertEquals(48, d.paragraph(0).format.tabInterval, .001);
    assertEquals("48.0,bar,none", TabStop.encode(d.paragraph(0).format.tabs));
  }

  @Test
  public void inheritedClearsApplyAfterBasedOnStylesAndDocumentDefaults() throws Exception {
    Map<String, String> parts = new LinkedHashMap<String, String>();
    String ns = " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
    parts.put(
        "word/document.xml",
        "<w:document"
            + ns
            + "><w:body><w:p><w:pPr><w:pStyle w:val=\"Child\"/><w:tabs><w:tab w:val=\"clear\""
            + " w:pos=\"2400\"/></w:tabs></w:pPr><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t></w:r></w:p></w:body></w:document>");
    parts.put(
        "word/styles.xml",
        "<w:styles"
            + ns
            + "><w:style w:type=\"paragraph\" w:styleId=\"Base\"><w:pPr><w:tabs><w:tab"
            + " w:val=\"bar\" w:pos=\"1200\"/><w:tab w:val=\"right\""
            + " w:pos=\"2400\"/></w:tabs></w:pPr></w:style><w:style w:type=\"paragraph\""
            + " w:styleId=\"Child\"><w:basedOn w:val=\"Base\"/><w:pPr><w:tabs><w:tab"
            + " w:val=\"clear\" w:pos=\"1200\"/><w:tab w:val=\"left\""
            + " w:pos=\"3600\"/></w:tabs></w:pPr></w:style></w:styles>");
    parts.put(
        "word/settings.xml",
        "<w:settings" + ns + "><w:defaultTabStop w:val=\"960\"/></w:settings>");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ZipOutputStream zip = new ZipOutputStream(out);
    for (Map.Entry<String, String> entry : parts.entrySet()) {
      zip.putNextEntry(new ZipEntry(entry.getKey()));
      zip.write(entry.getValue().getBytes("UTF-8"));
      zip.closeEntry();
    }
    zip.finish();
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    assertEquals(48, opened.document.paragraph(0).format.tabInterval, .001);
    assertEquals("180.0,left,none", TabStop.encode(opened.document.paragraph(0).format.tabs));
    assertEquals(
        "180.0,left,none", TabStop.encode(roundtrip(opened.document).paragraph(0).format.tabs));
  }

  @Test
  public void mixedBarDefaultIntervalsRequireExplicitConversion() {
    Paragraph first =
        new Paragraph("a", new RichText()).with("tabInterval", "48").with("tabs", "48,bar,none");
    Paragraph second = new Paragraph("b", new RichText()).with("tabs", "36,bar,none");
    Document d =
        new Document(new Sequence<Block>().append(first).append(second), PageSpec.A4, "Mixed", 3);
    assertFalse(Docx.conversionWarnings(d, null).isEmpty());
  }
}
