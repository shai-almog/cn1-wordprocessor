package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class DocxFeaturesTest {
  private byte[] pack(String document, String styles) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var zip = new ZipOutputStream(bytes);
    for (String[] p :
        new String[][] {
          {
            "word/document.xml",
            "<w:document"
                + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + document
                + "</w:body></w:document>"
          },
          {"word/styles.xml", styles}
        }) {
      zip.putNextEntry(new ZipEntry(p[0]));
      zip.write(p[1].getBytes("UTF-8"));
      zip.closeEntry();
    }
    zip.finish();
    return bytes.toByteArray();
  }

  @Test
  public void commentThreadsRoundTripWithoutDuplicatingAnchorsOrRootText() throws Exception {
    var s = new Session(Document.empty());
    s.replace("alpha beta");
    s.select(0, 0, 5);
    s.addComment("Ada", "Root\nsecond line");
    var id = s.document().comments.get(0).id;
    s.replyComment(id, "Grace", "Reply one");
    s.replyComment(id, "Linus", "Reply two\nmore");
    s.updateComment(id, true, false);
    var bytes = new ByteArrayOutputStream();
    Docx.write(s.document(), bytes);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertFalse(opened.warnings.toString(), opened.warnings.toString().contains("Comment"));
    for (Document d :
        new Document[] {DocumentXml.read(DocumentXml.write(s.document())), opened.document}) {
      assertEquals(1, d.comments.size());
      var c = d.comments.get(0);
      assertEquals("Root\nsecond line", c.text);
      assertTrue(c.resolved);
      assertEquals(2, c.replies.size());
      assertEquals("Grace", c.replies.get(0).author);
      assertEquals("Reply two\nmore", c.replies.get(1).text);
      assertEquals(0, c.start);
      assertEquals(5, c.end);
    }
  }

  @Test
  public void rectangularMergesRoundTripWithoutLosingCellText() throws Exception {
    var s = new Session(Document.empty());
    s.insertTable(3, 3);
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) s.setCell(1, r, c, r + ":" + c);
    s.mergeCells(1, 0, 0, 2, 2);
    var bytes = new ByteArrayOutputStream();
    Docx.write(s.document(), bytes);
    Document nativeCopy = DocumentXml.read(DocumentXml.write(s.document()));
    var wordCopy = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    for (Document doc : new Document[] {nativeCopy, wordCopy}) {
      var table = (Table) doc.blocks.get(1);
      assertEquals(3, table.rows());
      assertEquals(3, table.columns());
      assertEquals(2, table.span(0, 0).rows);
      assertEquals(2, table.span(0, 0).columns);
      assertEquals("0:0\n0:1\n1:0\n1:1", Table.cellText(table.cell(0, 0)));
      assertEquals("2:2", Table.cellText(table.cell(2, 2)));
      assertTrue(table.covered(1, 1));
    }
  }

  @Test
  public void raisedAndLoweredRunsRetainBaseSizeAcrossNativeAndDocx() throws Exception {
    var s = new Session(Document.empty());
    s.replace("H2O and x2");
    s.select(0, 1, 2);
    s.format("script", "subscript");
    s.select(0, 9, 10);
    s.format("script", "superscript");
    var bytes = new ByteArrayOutputStream();
    Docx.write(s.document(), bytes);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    for (Document doc :
        new Document[] {opened.document, DocumentXml.read(DocumentXml.write(s.document()))}) {
      assertEquals("H2O and x2", doc.plainText());
      assertEquals("subscript", doc.paragraph(0).text.styleAt(1).script);
      assertEquals("superscript", doc.paragraph(0).text.styleAt(9).script);
      assertEquals(12, doc.paragraph(0).text.styleAt(9).size, .001);
      assertEquals("baseline", doc.paragraph(0).text.styleAt(0).script);
    }
    s.undo();
    assertEquals("baseline", s.document().paragraph(0).text.styleAt(9).script);
    s.redo();
    assertEquals("superscript", s.document().paragraph(0).text.styleAt(9).script);
  }

  @Test
  public void paragraphLayoutRoundTripsThroughNativeAndDocx() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Indented and justified text");
    s.paragraph("align", "both");
    s.paragraph("indent", "24");
    s.paragraph("rightIndent", "18");
    s.paragraph("firstIndent", "-12");
    s.paragraph("tabInterval", "24");
    s.paragraph("keepLines", "true");
    s.paragraph("widowControl", "false");
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    for (Document d :
        new Document[] {DocumentXml.read(DocumentXml.write(s.document())), opened.document}) {
      var p = d.paragraph(0);
      assertEquals("both", p.align);
      assertEquals(24, p.indent, .01);
      assertEquals(18, p.format.rightIndent, .01);
      assertEquals(-12, p.format.firstIndent, .01);
      assertEquals(24, p.format.tabInterval, .01);
      assertTrue(p.format.keepLines);
      assertFalse(p.format.widowControl);
    }
  }

  @Test
  public void commentRangesAndAuthorsRoundTripAcrossParagraphs() throws Exception {
    var s = new Session(Document.empty());
    s.replace("A quoted phrase\nAnd another paragraph");
    s.selectRange(0, 2, 1, 3);
    s.addComment("Ada & Bob", "Please check <this>\nSecond line");
    Document nativeCopy = DocumentXml.read(DocumentXml.write(s.document()));
    assertEquals(1, nativeCopy.comments.size());
    assertEquals(s.document().comments.get(0).text, nativeCopy.comments.get(0).text);
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened o = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(o.warnings.toString(), o.warnings.isEmpty());
    assertEquals(s.document().plainText(), o.document.plainText());
    assertEquals(1, o.document.comments.size());
    var c = o.document.comments.get(0);
    assertEquals("Ada & Bob", c.author);
    assertEquals("Please check <this>\nSecond line", c.text);
    var opened = new Session(o.document);
    opened.selectComment(c.id);
    assertEquals("quoted phrase\nAnd", opened.selectedText());
    opened.updateComment(c.id, true, false);
    assertTrue(DocumentXml.read(DocumentXml.write(opened.document())).comments.get(0).resolved);
    assertTrue(Docx.conversionWarnings(opened.document(), o).isEmpty());
    out.reset();
    Docx.write(opened.document(), o, out, false);
    Docx.Opened resolved = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(resolved.warnings.toString(), resolved.warnings.isEmpty());
    assertTrue(resolved.document.comments.get(0).resolved);
    assertEquals(c.text, resolved.document.comments.get(0).text);
  }

  @Test
  public void emptyParagraphFormattingRoundTrips() throws Exception {
    var s = new Session(Document.empty());
    s.format("size", "26");
    s.format("bold", "true");
    assertEquals(
        s.activeStyle(),
        DocumentXml.read(DocumentXml.write(s.document())).paragraph(0).text.styleAt(0));
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    assertEquals(
        s.activeStyle(),
        Docx.read(new ByteArrayInputStream(out.toByteArray()))
            .document
            .paragraph(0)
            .text
            .styleAt(0));
  }

  @Test
  public void styleDefaultsInheritanceAndDirectOverrides() throws Exception {
    var styles =
        """
        <w:styles\
         xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:sz\
         w:val="20"/><w:rFonts w:ascii="Source Sans\
         3"/></w:rPr></w:rPrDefault></w:docDefaults><w:style\
         w:styleId="Base"><w:rPr><w:b/></w:rPr><w:pPr><w:spacing\
         w:after="240"/><w:keepNext/></w:pPr></w:style><w:style\
         w:styleId="Title"><w:basedOn w:val="Base"/><w:rPr><w:sz\
         w:val="40"/><w:i/></w:rPr><w:pPr><w:jc\
         w:val="center"/></w:pPr></w:style><w:style w:styleId="Accent"><w:rPr><w:color\
         w:val="ff0000"/></w:rPr></w:style></w:styles>""";
    Docx.Opened o =
        Docx.read(
            new ByteArrayInputStream(
                pack(
                    """
                    <w:p><w:pPr><w:pStyle w:val="Title"/><w:spacing\
                     w:before="120"/></w:pPr><w:r><w:t>A</w:t></w:r><w:r><w:rPr><w:rStyle\
                     w:val="Accent"/><w:b w:val="0"/></w:rPr><w:t>B</w:t></w:r></w:p>""",
                    styles)));
    assertTrue(o.warnings.toString(), o.warnings.isEmpty());
    var p = o.document.paragraph(0);
    assertEquals(12, p.after, .01);
    assertEquals(6, p.before, .01);
    assertTrue(p.keepNext);
    assertEquals("center", p.align);
    assertTrue(p.text.styleAt(0).bold);
    assertTrue(p.text.styleAt(0).italic);
    assertEquals(20, p.text.styleAt(0).size, .01);
    assertFalse(p.text.styleAt(1).bold);
    assertEquals(0xff0000, p.text.styleAt(1).color);
    var out = new ByteArrayOutputStream();
    Docx.write(o.document.title("Changed"), o, out, false);
    var round = Docx.read(new ByteArrayInputStream(out.toByteArray())).document.paragraph(0);
    assertEquals(p.text.styleAt(0), round.text.styleAt(0));
    assertEquals(p.text.styleAt(1), round.text.styleAt(1));
  }

  @Test
  public void hyperlinkTargetsSurviveEditingAndEscaping() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Click here then there");
    s.select(0, 6, 10);
    s.format("link", "https://example.com/?a=1&b=2");
    s.select(0, 16, 21);
    s.format("link", "#chapter");
    var b = new ByteArrayOutputStream();
    Docx.write(s.document(), b);
    Docx.Opened o = Docx.read(new ByteArrayInputStream(b.toByteArray()));
    assertTrue(o.warnings.toString(), o.warnings.isEmpty());
    assertEquals("https://example.com/?a=1&b=2", o.document.paragraph(0).text.styleAt(7).link);
    assertEquals("#chapter", o.document.paragraph(0).text.styleAt(18).link);
    assertEquals("", o.document.paragraph(0).text.styleAt(0).link);
  }

  private List<String> labels(Document d) {
    var out = new ArrayList<String>();
    var n = new ListNumbers();
    for (Block b : d.blocks) if (b instanceof Paragraph paragraph) out.add(n.next(paragraph));
    return out;
  }

  @Test
  public void nestedListsRestartsAndNumberFormatsRoundTrip() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Parent\nChild\nChild two\nParent two\nRestart\nChild restart\nLast parent");
    s.selectRange(0, 0, 6, 11);
    s.paragraph("list", "number");
    for (int i : new int[] {1, 2, 5}) {
      s.select(i, 0, 0);
      s.paragraph("listLevel", "1");
      s.paragraph("listFormat", "lowerLetter");
    }
    s.select(4, 0, 0);
    s.paragraph("listStart", "5");
    s.select(5, 0, 0);
    s.paragraph("listStart", "3");
    var expected = Arrays.asList("1.", "a.", "b.", "2.", "5.", "c.", "6.");
    assertEquals(expected, labels(s.document()));
    assertEquals(expected, labels(DocumentXml.read(DocumentXml.write(s.document()))));
    var b = new ByteArrayOutputStream();
    Docx.write(s.document(), b);
    Docx.Opened o = Docx.read(new ByteArrayInputStream(b.toByteArray()));
    assertTrue(o.warnings.toString(), o.warnings.isEmpty());
    assertEquals(expected, labels(o.document));
    assertEquals("xxvii", ListNumbers.format(27, "lowerRoman"));
    assertEquals("AA", ListNumbers.format(27, "upperLetter"));
  }

  @Test
  public void inheritanceCycleIsBoundedAndReported() throws Exception {
    Docx.Opened o =
        Docx.read(
            new ByteArrayInputStream(
                pack(
                    "<w:p><w:pPr><w:pStyle w:val=\"A\"/></w:pPr><w:r><w:t>x</w:t></w:r></w:p>",
                    """
                    <w:styles\
                     xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style\
                     w:styleId="A"><w:basedOn w:val="B"/></w:style><w:style\
                     w:styleId="B"><w:basedOn w:val="A"/></w:style></w:styles>""")));
    assertTrue(o.warnings.toString(), o.warnings.toString().contains("Cyclic"));
    assertEquals("x", o.document.plainText());
  }
}
