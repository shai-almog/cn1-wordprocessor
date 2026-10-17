package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class RecipientWorkbookTest {
  private byte[] zip(String... entries) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (int i = 0; i < entries.length; i += 2) {
        zip.putNextEntry(new ZipEntry(entries[i]));
        zip.write(entries[i + 1].getBytes("UTF-8"));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private byte[] xlsx(String cells, boolean date1904) throws Exception {
    return zip(
        "xl/workbook.xml",
        "<workbook xmlns:r='r'><workbookPr date1904='"
            + (date1904 ? "1" : "0")
            + "'/><sheets><sheet name='People' r:id='r1'/><sheet name='Other'"
            + " r:id='r2'/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels",
        "<Relationships><Relationship Id='r1' Target='worksheets/one.xml'"
            + " Type='x/worksheet'/><Relationship Id='r2' Target='worksheets/two.xml'"
            + " Type='x/worksheet'/></Relationships>",
        "xl/worksheets/one.xml",
        "<worksheet><sheetData>" + cells + "</sheetData></worksheet>",
        "xl/worksheets/two.xml",
        "<worksheet><sheetData><row><c t='inlineStr'><is><t>Name</t></is></c></row><row><c"
            + " t='inlineStr'><is><t>Bo</t></is></c></row></sheetData></worksheet>",
        "xl/sharedStrings.xml",
        "<sst><si><r><t>Ada </t></r><r><t>Lovelace</t></r><rPh><t>ignored</t></rPh></si></sst>",
        "xl/styles.xml",
        "<styleSheet><numFmts><numFmt numFmtId='164' formatCode='yyyy-mm-dd"
            + " hh:mm:ss'/></numFmts><cellXfs><xf numFmtId='0'/><xf numFmtId='14'/><xf"
            + " numFmtId='164'/><xf numFmtId='21'/></cellXfs></styleSheet>");
  }

  @Test
  public void workbookChoiceSparseCellsSharedInlineCachedAndBooleanValues() throws Exception {
    RecipientWorkbook w =
        new RecipientWorkbook(
            xlsx(
                "<row><c r='A1' t='s'><v>0</v></c><c r='C1'><f>1+2</f><v>3</v></c><c r='D1'"
                    + " t='b'><v>1</v></c><c r='E1' t='inlineStr'><is><t>London</t></is></c></row>",
                false));
    assertEquals(Arrays.asList("People", "Other"), w.sheets());
    MergeData d = w.read("people", false);
    assertEquals(Arrays.asList("Ada Lovelace", "", "3", "TRUE", "London"), d.rows.get(0));
    assertEquals("Bo", w.read(1, true).rows.get(0).get(0));
  }

  @Test
  public void excelDateSystemsLeapBugAndTimeFormats() throws Exception {
    String row =
        "<row><c s='1'><v>60</v></c><c s='1'><v>61</v></c><c s='2'><v>61.5</v></c><c"
            + " s='3'><v>0.5</v></c></row>";
    assertEquals(
        Arrays.asList("1900-02-29", "1900-03-01", "1900-03-01 12:00", "12:00"),
        new RecipientWorkbook(xlsx(row, false)).read(0, false).rows.get(0));
    assertEquals(
        "1904-01-01",
        new RecipientWorkbook(xlsx("<row><c s='1'><v>0</v></c></row>", true))
            .read(0, false)
            .rows
            .get(0)
            .get(0));
  }

  @Test
  public void odsGroupsRepeatsDatesBooleansAndHeaderOrder() throws Exception {
    String cell = "<table:table-cell office:value-type='string'><text:p>";
    byte[] bytes =
        zip(
            "content.xml",
            "<office:document-content xmlns:office='o' xmlns:table='t'"
                + " xmlns:text='x'><office:body><office:spreadsheet><table:table"
                + " table:name='Guests'><table:table-header-rows><table:table-row>"
                + cell
                + "Name</text:p></table:table-cell>"
                + cell
                + "Date</text:p></table:table-cell>"
                + cell
                + "Flag</text:p></table:table-cell></table:table-row></table:table-header-rows><table:table-row"
                + " table:number-rows-repeated='2'>"
                + cell
                + "Ada</text:p></table:table-cell><table:table-cell office:value-type='date'"
                + " office:date-value='2026-10-15'/><table:table-cell office:value-type='boolean'"
                + " office:boolean-value='true'/></table:table-row><table:table-row"
                + " table:number-rows-repeated='10000000'><table:table-cell"
                + " table:number-columns-repeated='10000'/></table:table-row></table:table></office:spreadsheet></office:body></office:document-content>");
    MergeData d = new RecipientWorkbook(bytes).read(0, true);
    assertEquals(Arrays.asList("Name", "Date", "Flag"), d.fields);
    assertEquals(2, d.rows.size());
    assertEquals(Arrays.asList("Ada", "2026-10-15", "TRUE"), d.rows.get(0));
  }

  @Test
  public void invalidSharedReferencesColumnBoundsAndExternalTargetsFail() throws Exception {
    for (String cells :
        new String[] {
          "<row><c t='s'><v>999</v></c></row>",
          "<row><c r='ZZZ1'><v>5</v></c></row>",
          "<row><c r='A1'><v>5</v></c><c r='A1'><v>6</v></c></row>"
        }) {
      try {
        new RecipientWorkbook(xlsx(cells, false)).read(0, false);
        fail();
      } catch (IOException expected) {
      }
    }
    try {
      new RecipientWorkbook(
          zip(
              "xl/workbook.xml",
              "<workbook><sheets><sheet id='r1'/></sheets></workbook>",
              "xl/_rels/workbook.xml.rels",
              "<Relationships><Relationship Id='r1' Type='x/worksheet' TargetMode='External'"
                  + " Target='https://example.org/data'/></Relationships>"));
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void envelopesAndLabelGeometryRoundTripAndUndo() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Letter body");
    Document before = s.document();
    MailingSheets.envelope(s, "Ada Lovelace\n1 Main Road\nLondon", "Sender", "DL", true);
    assertEquals(2, s.document().sectionRanges().size());
    assertEquals(623.6f, s.document().sectionAt(0).raw.page.width, .01);
    assertEquals(2, new LayoutEngine(new PdfMetrics()).layout(s.document(), 0).pages.size());
    s.undo();
    assertSame(before, s.document());
    assertEquals(1, MailingSheets.labels(s, "letter-3x10", "Ada\nLondon", false, 0, 0, 2, 3));
    Table table = (Table) s.document().blocks.get(0);
    assertEquals(10, table.rows());
    assertEquals(5, table.columns());
    assertEquals("Ada", table.cell(1, 4).get(0).text.plainText());
    assertEquals("", table.cell(0, 0).get(0).text.plainText());
    LayoutEngine.Result layout = new LayoutEngine(new PdfMetrics()).layout(s.document(), 0);
    assertEquals(1, layout.pages.size());
    assertEquals(72, layout.pages.get(0).boxes.get(0).height, .01);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Docx.write(s.document(), bytes);
    Document reopened = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    assertEquals(s.document().plainText(), reopened.plainText());
    assertEquals(72, ((Table) reopened.blocks.get(0)).format.minHeight(0), .01);
    Document labels = s.document();
    try {
      MailingSheets.labels(s, "letter-3x10", "x", false, 40, 10, 0, 0);
      fail();
    } catch (IllegalArgumentException expected) {
      assertSame(labels, s.document());
    }
  }
}
