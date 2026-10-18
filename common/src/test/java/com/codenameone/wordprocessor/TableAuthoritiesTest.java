package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import org.junit.Test;

public class TableAuthoritiesTest {
  private final LayoutEngine engine = new LayoutEngine(new PdfMetrics());
  private TableContents.Paginator pages() { return d -> engine.layout(d,0); }
  private List<String> entries(Document d) {
    int[] r=TableAuthorities.range(d);var result=new ArrayList<String>();
    for(int i=r[0]+1;i<r[1];i++)result.add(d.paragraph(i).text.plainText());return result;
  }
  @Test public void marksPreserveSelectedTextAndQuotesAndUndo() {
    var s=new Session(Document.empty());s.replace("Smith v. Jones held it.");s.select(0,0,14);
    var before=s.document();TableAuthorities.mark(s,"Smith v. Jones: \"quoted\"");
    assertEquals(before.plainText(),s.document().plainText());
    var f=s.document().paragraph(0).text.fieldAt(14);
    assertEquals("Smith v. Jones: \"quoted\"",new FieldCode(f.instruction).switches.get("l"));
    s.undo();assertSame(before,s.document());s.redo();
    before=s.document();try{TableAuthorities.mark(s,"\nInvalid");fail();}catch(IllegalArgumentException expected){assertSame(before,s.document());}
  }
  @Test public void tableSortsDeduplicatesUpdatesAndRemovesAtomically() {
    var s=new Session(Document.empty());s.replace("Smith v. Jones held it.\nDoe v. Roe agreed.\n");
    s.select(0,0,14);TableAuthorities.mark(s,"Smith v. Jones");s.select(2,0,0);
    TableAuthorities.insert(s,"Authorities","hyphen",pages());
    assertEquals(Arrays.asList("Smith v. Jones\t1"),entries(s.document()));
    var before=s.document();s.select(1,0,10);TableAuthorities.mark(s,"Doe v. Roe");TableAuthorities.mark(s,"Doe v. Roe");
    TableAuthorities.update(s,pages());assertEquals(Arrays.asList("Doe v. Roe\t1","Smith v. Jones\t1"),entries(s.document()));
    s.undo();s.undo();s.undo();assertSame(before,s.document());
    TableAuthorities.remove(s);assertNull(TableAuthorities.range(s.document()));assertTrue(s.document().paragraph(0).text.length()>22);
    s.undo();assertSame(before,s.document());
  }
  @Test public void physicalPagesAndSectionRestartsIncludeTableFootprint() {
    var s=new Session(Document.empty());s.replace("\nFirst citation\nSecond citation");
    s.replaceDocument(s.document().page(new PageSpec(300,100,10,10,10,10)));
    s.select(1,0,0);TableAuthorities.mark(s,"Alpha: long authority name");
    s.select(2,0,0);s.sectionBreak("nextPage");s.section(s.activeSection().raw.numbering(7));
    TableAuthorities.mark(s,"Beta authority");s.select(0,0,0);TableAuthorities.insert(s,"Authorities","dot",pages());
    assertEquals(2,entries(s.document()).size());
    assertTrue(entries(s.document()).toString(),entries(s.document()).contains("Beta authority\t7"));
    assertFalse(entries(s.document()).contains("Alpha: long authority name\t1"));
  }
  @Test public void nativeDocxAndIndexCoexistenceRemainEditable() throws Exception {
    var s=new Session(Document.empty());s.replace("\nSmith v. Jones\n");s.select(1,0,14);
    TableAuthorities.mark(s,"Smith v. Jones");DocumentIndex.mark(s,"Smith","","","",false,false,false,"");
    s.select(0,0,0);TableAuthorities.insert(s,"Authorities","hyphen",pages());
    s.select(s.document().blocks.size()-1,0,0);DocumentIndex.insert(s,DocumentIndex.options(s.document()),pages());
    var bytes=new ByteArrayOutputStream();Docx.write(s.document(),bytes);
    Docx.Opened opened=Docx.read(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(opened.warnings.toString(),opened.warnings.isEmpty());
    for(Document d:new Document[]{DocumentXml.read(DocumentXml.write(s.document())),opened.document}){
      assertNotNull(TableAuthorities.range(d));assertNotNull(DocumentIndex.range(d));
      assertEquals("hyphen",TableAuthorities.options(d).leader);
      var reopened=new Session(d);TableAuthorities.update(reopened,pages());DocumentIndex.update(reopened,pages());
      assertEquals(Arrays.asList("Smith v. Jones\t1"),entries(reopened.document()));
      TableAuthorities.remove(reopened);assertNull(TableAuthorities.range(reopened.document()));assertNotNull(DocumentIndex.range(reopened.document()));
    }
  }
  @Test public void lockedAndUnsupportedFieldsPreserveCachedResults() {
    var s=new Session(Document.empty());TableAuthorities.insert(s,"Authorities","dot",pages());
    int[] r=TableAuthorities.range(s.document());var p=s.document().paragraph(r[0]);
    s.replaceDocument(s.document().blocks(s.document().blocks.set(r[0],p.text(RichText.field(new InlineField("TOA \\c 1","",true,false),TextStyle.DEFAULT))),s.document().nextId));
    var before=s.document();TableAuthorities.update(s,pages());assertSame(before,s.document());
    s.replaceDocument(before.blocks(before.blocks.set(r[0],p.text(RichText.field(new InlineField("TOA \\c 1","",false,false),TextStyle.DEFAULT))),before.nextId));
    before=s.document();try{TableAuthorities.update(s,pages());fail();}catch(IllegalArgumentException expected){assertSame(before,s.document());}
  }
}
