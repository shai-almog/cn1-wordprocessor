package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.io.*;
import org.junit.Test;

public class IndexFormatTest {
  @Test public void nativeAndDocxPreserveEditableIndexAndMarkedEntries() throws Exception {
    Session s=new Session(Document.empty());s.replace("\nKilns\nPresses");
    s.select(1,0,5);DocumentIndex.mark(s,"Kilns","firing","","",true,true,false,"");
    s.select(2,0,7);DocumentIndex.mark(s,"Presses","","See Kilns","",false,false,false,"");
    s.select(0,0,0);LayoutEngine engine=new LayoutEngine(new PdfMetrics());
    DocumentIndex.insert(s,new DocumentIndex.Options("Subject index",false,true,true,"hyphen"),d->engine.layout(d,0));
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();Docx.write(s.document(),bytes);
    Docx.Opened opened=Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(),opened.warnings.isEmpty());
    for(Document d:new Document[]{DocumentXml.read(DocumentXml.write(s.document())),opened.document}){
      assertNotNull(DocumentIndex.range(d));assertEquals("Subject index",DocumentIndex.options(d).title);
      assertEquals("hyphen",DocumentIndex.options(d).leader);
      assertEquals(s.document().plainText(),d.plainText());
      Session reopened=new Session(d);DocumentIndex.update(reopened,doc->engine.layout(doc,0));
      assertEquals(d.plainText(),reopened.document().plainText());
      int[] range=DocumentIndex.range(reopened.document());Paragraph sub=reopened.document().paragraph(range[0]+3);
      assertEquals("firing\t1",sub.text.plainText());assertTrue(sub.text.styleAt(sub.text.length()-1).bold);
      assertTrue(sub.text.styleAt(sub.text.length()-1).italic);
      DocumentIndex.remove(reopened);assertNull(DocumentIndex.range(reopened.document()));
      assertEquals("\nKilns\nPresses",reopened.document().plainText());
    }
  }
  @Test public void emptyAndLockedIndexAndContentsStaySeparate() throws Exception {
    Session s=new Session(Document.empty());s.replace("\nHeading\n");s.select(1,0,0);s.applyStyle("Heading1");
    LayoutEngine engine=new LayoutEngine(new PdfMetrics());s.select(0,0,0);
    TableContents.insert(s,TableContents.options(s.document()),d->engine.layout(d,0));
    s.select(s.document().blocks.size()-1,0,0);DocumentIndex.insert(s,DocumentIndex.options(s.document()),d->engine.layout(d,0));
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();Docx.write(s.document(),bytes);
    Document d=Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    assertNotNull(TableContents.range(d));assertNotNull(DocumentIndex.range(d));
    int[] range=DocumentIndex.range(d);assertEquals(range[0]+1,range[1]);
    Paragraph p=d.paragraph(range[0]);InlineField marker=DocumentIndex.marker(p);
    s.replaceDocument(d.blocks(d.blocks.set(range[0],p.text(DocxToc.withoutMarker(p).text.concat(RichText.field(
        new InlineField(marker.instruction,"",true,false),TextStyle.DEFAULT)))),d.nextId));
    Document locked=s.document();DocumentIndex.update(s,doc->engine.layout(doc,0));assertSame(locked,s.document());
  }
  @Test public void wordcraftIndexImportsAndUpdatesWithoutDuplicateCachedEntries() throws Exception {
    Docx.Opened opened=Docx.read(getClass().getResourceAsStream("/wordcraft-index.docx"));
    Document d=opened.document;assertArrayEquals(new int[]{2,8},DocumentIndex.range(d));
    assertEquals("Kilns",d.paragraph(0).text.plainText());assertEquals("Presses",d.paragraph(1).text.plainText());
    assertEquals("XE \"Kilns:firing\" \\b",d.paragraph(0).text.fieldAt(5).instruction);
    assertEquals("XE \"Kilns\"",d.paragraph(0).text.fieldAt(6).instruction);
    LayoutEngine engine=new LayoutEngine(new PdfMetrics());Session s=new Session(d);
    DocumentIndex.update(s,doc->engine.layout(doc,0));
    assertArrayEquals(new int[]{2,8},DocumentIndex.range(s.document()));
    assertEquals("firing, 1",s.document().paragraph(5).text.plainText());
    assertEquals("Presses. See Kilns",s.document().paragraph(7).text.plainText());
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();Docx.write(s.document(),bytes);
    Docx.Opened reopened=Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(reopened.warnings.toString(),reopened.warnings.isEmpty());
    assertEquals(s.document().plainText(),reopened.document.plainText());
  }

}
