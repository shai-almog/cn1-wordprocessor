package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.io.*;
import org.junit.Test;

public class TableContentsFormatTest {
  @Test public void nativeAndDocxKeepContentsEditableAfterReopen() throws Exception {
    Session s=new Session(Document.empty());
    s.replace("\nOverview\nDetails");
    s.select(1,0,8); s.applyStyle("Heading1");
    s.select(2,0,7); s.applyStyle("Heading2");
    s.select(0,0,0);
    LayoutEngine engine=new LayoutEngine(new PdfMetrics());
    TableContents.insert(s,new TableContents.Options(2,"Guide",true,true,"hyphen",true), d -> engine.layout(d,0));
    ByteArrayOutputStream out=new ByteArrayOutputStream(); Docx.write(s.document(),out);
    Docx.Opened docx=Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(docx.warnings.toString(),docx.warnings.isEmpty());
    for(Document d:new Document[]{DocumentXml.read(DocumentXml.write(s.document())),docx.document}) {
      assertEquals("Guide",d.paragraph(0).text.plainText());
      assertEquals(2,TableContents.options(d).levels);
      assertEquals("hyphen",TableContents.options(d).leader);
      assertTrue(TableContents.options(d).hyperlinks);
      assertEquals("Overview\t1",d.paragraph(1).text.plainText());
      assertTrue(d.paragraph(1).text.styleAt(0).link.startsWith("#_Toc"));
      assertEquals(2,d.bookmarks.size());
      assertTrue(d.styles.containsKey("TOC1"));
      Session reopened=new Session(d);
      int source=-1;
      for(int i=0;i<d.blocks.size();i++) if(d.blocks.get(i) instanceof Paragraph && "Heading1".equals(d.paragraph(i).style)) source=i;
      assertTrue(source>=0);
      reopened.select(source,0,8); reopened.replace("New heading");
      TableContents.update(reopened, doc -> engine.layout(doc,0));
      assertEquals("New heading\t1",reopened.document().paragraph(1).text.plainText());
      assertEquals(2,reopened.document().bookmarks.size());
      assertTrue(new String(PdfExporter.write(reopened.document()),"ISO-8859-1").contains("Guide"));
    }
  }

  @Test public void wordcraftComplexContentsRemainEditableAndReexportAsRange() throws Exception {
    Docx.Opened opened = Docx.read(getClass().getResourceAsStream("/wordcraft-contents.docx"));
    assertEquals(new java.util.LinkedHashSet<String>(java.util.Arrays.asList(
        "Character feature: kern",
         "Paragraph feature: contextualSpacing", "Paragraph feature: pBdr")),
        new java.util.LinkedHashSet<String>(opened.warnings));
    Document d = opened.document;
    assertArrayEquals(new int[]{0,3}, TableContents.range(d));
    assertEquals("Overview\t1", d.paragraph(1).text.plainText());
    assertEquals("Details\t1", d.paragraph(2).text.plainText());
    assertEquals(2, d.bookmarks.size());
    Session s = new Session(d);
    s.select(4, 0, 8); s.replace("Introduction");
    LayoutEngine engine = new LayoutEngine(new PdfMetrics());
    TableContents.update(s, doc -> engine.layout(doc, 0));
    assertEquals("Introduction\t1", s.document().paragraph(1).text.plainText());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); Docx.write(s.document(), bytes);
    java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    String xml = null;
    for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null;) {
      if (!e.getName().equals("word/document.xml")) continue;
      ByteArrayOutputStream part = new ByteArrayOutputStream(); byte[] buf = new byte[4096];
      for (int n; (n = zip.read(buf)) != -1;) part.write(buf, 0, n);
      xml = new String(part.toByteArray(), "UTF-8");
    }
    zip.close();
    assertNotNull(xml);
    assertTrue(xml.contains("<w:sdt>"));
    assertTrue(xml.indexOf("fldCharType=\"begin\"") < xml.indexOf("Introduction"));
    assertTrue(xml.indexOf("fldCharType=\"end\"") > xml.indexOf("Details"));
    Docx.Opened again = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(again.warnings.toString(), again.warnings.isEmpty());
    Session reopened = new Session(again.document);
    TableContents.remove(reopened);
    assertNull(TableContents.range(reopened.document()));
    assertEquals("\nIntroduction\nDetails", reopened.document().plainText());
  }

  @Test public void emptyContentsRoundTripAndLockedContentsDoNotRegenerate() throws Exception {
    Session s = new Session(Document.empty());
    LayoutEngine engine = new LayoutEngine(new PdfMetrics());
    TableContents.insert(s, TableContents.options(s.document()), d -> engine.layout(d, 0));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); Docx.write(s.document(), bytes);
    Document d = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    assertArrayEquals(new int[]{0,1}, TableContents.range(d));
    Paragraph p = d.paragraph(0);
    InlineField field = DocxToc.marker(p);
    RichText text = DocxToc.withoutMarker(p).text.concat(RichText.field(
        new InlineField(field.instruction, "", true, false), TextStyle.DEFAULT));
    s.replaceDocument(d.blocks(d.blocks.set(0, p.text(text)), d.nextId));
    Document locked = s.document();
    TableContents.update(s, doc -> engine.layout(doc, 0));
    assertSame(locked, s.document());
  }

  @Test public void advancedSourcesAndOutlineRevisionsSurviveNativeAndDocx() throws Exception {
    Session s=new Session(Document.empty()); s.replace("\nMapped source\nOutline source\nMarked source");
    s.select(1,0,0); s.defineStyle(new ParagraphStyle("Topic", "Special Topic", s.document().paragraph(1), TextStyle.DEFAULT)); s.applyStyle("Topic");
    s.select(2,0,0); s.paragraph("outlineLevel","2");
    s.select(3,0,6); TableContents.markEntry(s,"Marked entry",3,"X"); s.select(0,0,0);
    java.util.Map<String,Integer> styles=new java.util.LinkedHashMap<String,Integer>(); styles.put("Special Topic",1);
    LayoutEngine engine=new LayoutEngine(new PdfMetrics());
    TableContents.insert(s,new TableContents.Options(3,"Guide",true,true,"hyphen",true,null,false,true,true,styles,"X"),d->engine.layout(d,0));
    ByteArrayOutputStream bytes=new ByteArrayOutputStream(); Docx.write(s.document(),bytes);
    Docx.Opened opened=Docx.read(new ByteArrayInputStream(bytes.toByteArray())); assertTrue(opened.warnings.toString(),opened.warnings.isEmpty());
    for(Document d:new Document[]{DocumentXml.read(DocumentXml.write(s.document())),opened.document}) {
      TableContents.Options options=TableContents.options(d);
      assertFalse(options.headingStyles); assertTrue(options.outlineLevels); assertTrue(options.tcFields);
      assertEquals(styles,options.styleLevels); assertEquals("X",options.tcIdentifier);
      Session reopened=new Session(d); TableContents.update(reopened,doc->engine.layout(doc,0));
      assertEquals("Mapped source\t1",reopened.document().paragraph(1).text.plainText());
      assertEquals("Outline source\t1",reopened.document().paragraph(2).text.plainText());
      assertEquals("Marked entry\t1",reopened.document().paragraph(3).text.plainText());
      int source=-1;
      for(int j=0;j<d.blocks.size();j++) if(d.paragraph(j).text.plainText().equals("Outline source")) source=j;
      reopened.select(source,0,0); reopened.trackChanges(true); reopened.paragraph("outlineLevel","0");
      Document tracked=reopened.document();
      bytes.reset(); Docx.write(tracked,bytes);
      for(Document restored:new Document[]{DocumentXml.read(DocumentXml.write(tracked)),Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document}) {
        assertEquals(0,restored.paragraph(source).format.outlineLevel);
        Session review=new Session(restored); review.resolveRevision(null,false);
        assertEquals(2,review.document().paragraph(source).format.outlineLevel);
      }
    }
  }

  @Test public void inheritedOutlineDoesNotBecomeDirectFormattingOnStyleApplyOrImport() throws Exception {
    Session s=new Session(Document.empty()); s.replace("\nCustom heading"); s.select(1,0,0);
    s.defineStyle(new ParagraphStyle("Topic","Topic",s.document().paragraph(1).with("outlineLevel","2"),TextStyle.DEFAULT));
    s.applyStyle("Topic"); assertEquals(-1,s.document().paragraph(1).format.outlineLevel);
    ByteArrayOutputStream bytes=new ByteArrayOutputStream(); Docx.write(s.document(),bytes);
    Document d=Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    assertEquals(-1,d.paragraph(1).format.outlineLevel);
    assertEquals(2,d.styles.get("Topic").paragraph.format.outlineLevel);
    LayoutEngine engine=new LayoutEngine(new PdfMetrics()); s=new Session(d); s.select(0,0,0);
    TableContents.insert(s,new TableContents.Options(3,"Guide",true,true,"dot",true,null,false,true,false,
        java.util.Collections.<String,Integer>emptyMap(),""),doc->engine.layout(doc,0));
    assertArrayEquals(new int[]{0,1},TableContents.range(s.document()));
    TableContents.insert(s,new TableContents.Options(3,"Guide",true,true,"dot",true),doc->engine.layout(doc,0));
    assertEquals("Custom heading\t1",s.document().paragraph(1).text.plainText());
    int source=3; s.select(source,0,0); s.paragraph("outlineLevel","0");
    s.defineStyle(new ParagraphStyle("Topic","Topic",new Paragraph("template",new RichText()).with("outlineLevel","3"),TextStyle.DEFAULT));
    assertEquals(0,s.document().paragraph(source).format.outlineLevel);
  }
}
