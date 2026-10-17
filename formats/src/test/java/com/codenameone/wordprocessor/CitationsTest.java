package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import org.junit.Test;

public class CitationsTest {
  private CitationSource source(String tag, String author, String title) {
    Map<String,String> map=new LinkedHashMap<String,String>();map.put("tag",tag);map.put("author",author);map.put("title",title);map.put("year","2021");map.put("publisher","Harbor Books");map.put("city","Portland");return new CitationSource(map);
  }
  private List<String> entries(Document d) {
    int[] range=Citations.range(d);List<String> result=new ArrayList<String>();
    for(int i=range[0]+1;i<range[1];i++)result.add(d.paragraph(i).text.plainText());return result;
  }
  @Test public void sourceEditsAndStyleChangesRefreshAndUndoTogether() {
    Session s=new Session(Document.empty());s.replace("Opening\n");s.select(0,7,7);
    Citations.insert(s,null,source("A","Rivera, Alex; Chen, Mei","Shared Spaces"),"12–14");
    assertEquals("Opening(Rivera & Chen, 2021, p. 12–14)",s.document().paragraph(0).text.plainText());
    s.select(1,0,0);Citations.bibliography(s,null);Document before=s.document();
    assertEquals(Arrays.asList("Rivera, A., & Chen, M. (2021). Shared Spaces. Harbor Books."),entries(before));
    Citations.style(s,"IEEE");assertTrue(s.document().plainText().contains("Opening[1]"));assertTrue(entries(s.document()).get(0).startsWith("[1] A. Rivera, M. Chen"));
    s.undo();assertSame(before,s.document());s.redo();
    Citations.source(s,source("A","Rivera, Alex","Revised"));assertTrue(entries(s.document()).get(0).contains("Revised"));
    Citations.removeSource(s,"A");assertTrue(s.document().plainText().contains("Error! Source not found: A"));assertTrue(entries(s.document()).isEmpty());
    s.undo();assertFalse(s.document().plainText().contains("Error!"));
  }
  @Test public void numberingFollowsFirstCitationAndExistingSourceEditKeepsCorrectTag() {
    Session s=new Session(Document.empty());Citations.source(s,source("A","Adams, Ann","Alpha"));Citations.source(s,source("B","Baker, Ben","Beta"));
    Citations.style(s,"IEEE");Citations.insert(s,"B",null,"");Citations.insert(s,"A",null,"");assertEquals("[1][2]",s.document().plainText());
    s.select(0,0,0);Citations.insert(s,null,source("A","Adams, Ann","Edited Alpha"),"");
    assertEquals("[1][2][1]",s.document().plainText());assertEquals("Edited Alpha",s.document().bibliography.sources.get("A").get("title"));
    Citations.style(s,"GOST (title sort)");assertEquals("[2][1][2]",s.document().plainText());
  }
  @Test public void sourceAndCitationOperationsKeepTableCellSelectionAndUndo() {
    Session s=new Session(Document.empty());s.insertTable(2,2);int table=-1;
    for(int i=0;i<s.document().blocks.size();i++)if(s.document().blocks.get(i) instanceof Table)table=i;
    s.enterCell(table,1,1);s.replace("Cell ");Document before=s.document();
    Citations.insert(s,null,source("A","Rivera, Alex","Cell source"),"8");
    assertTrue(s.inCell());assertEquals(1,s.cellRow());assertEquals(6,s.selection().caret);
    assertTrue(s.document().bibliography.sources.containsKey("A"));assertTrue(s.editingDocument().plainText().contains("(Rivera, 2021, p. 8)"));
    s.undo();assertEquals(DocumentXml.write(before),DocumentXml.write(s.document()));assertTrue(s.inCell());
    s.redo();Citations.source(s,source("A","Chen, Mei","Edited"));assertTrue(s.inCell());assertEquals(6,s.selection().caret);assertTrue(s.editingDocument().plainText().contains("Chen"));
  }
  @Test public void nativeAndDocxRetainSourceFieldsAndEditableBibliography() throws Exception {
    Session s=new Session(Document.empty());s.replace("\n");s.select(0,0,0);
    Map<String,String> data=new LinkedHashMap<String,String>(source("A","Rivera, Alex; Chen, Mei","A & B <study>").values);
    data.put("editor","Smith, Jane");data.put("kind","article");data.put("journal","Journal");data.put("volume","7");data.put("issue","2");data.put("doi","10.1/example");data.put("accessed","2026-10-15");
    CitationSource source=new CitationSource(data);Citations.insert(s,null,source,"12 14");s.select(1,0,0);Citations.bibliography(s,"My references");
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();Docx.write(s.document(),bytes);Docx.Opened opened=Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(),opened.warnings.isEmpty());
    for(Document d:new Document[]{DocumentXml.read(DocumentXml.write(s.document())),opened.document}) {
      assertEquals(source.values,d.bibliography.sources.get("A").values);assertEquals("APA",d.bibliography.style);assertNotNull(Citations.range(d));assertEquals(entries(s.document()),entries(d));
      Session reopened=new Session(d);Citations.style(reopened,"MLA");assertTrue(reopened.document().paragraph(0).text.plainText().contains("(Rivera and Chen 12 14)"));
      assertEquals(1,entries(reopened.document()).size());Citations.remove(reopened);assertNull(Citations.range(reopened.document()));assertEquals(1,reopened.document().bibliography.sources.size());
    }
  }
  @Test public void emptyLibraryUnknownStyleAndLockedBibliographyAreSafe() throws Exception {
    Session s=new Session(Document.empty());Citations.bibliography(s,null);assertTrue(entries(s.document()).isEmpty());
    Document before=s.document();try{Citations.style(s,"Unsupported");fail();}catch(IllegalArgumentException expected){assertSame(before,s.document());}
    try{Citations.insert(s,"missing",source("A","Author","Title"),"");fail();}catch(IllegalArgumentException expected){assertSame(before,s.document());}
    int[] range=Citations.range(s.document());Paragraph p=s.document().paragraph(range[0]);int at=p.text.length()-1;
    s.select(range[0],at,at);s.changeField(at,p.text.fieldAt(at).locked(true));before=s.document();Citations.update(s);assertSame(before,s.document());
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();Docx.write(s.document(),bytes);assertNotNull(Citations.range(Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document));
  }
  @Test public void eachStyleHasDistinctCitationAndBibliographyRendering() {
    CitationSource source=source("A","Rivera, Alex; Chen, Mei","Shared Spaces");
    String[] cites={"(Rivera & Chen, 2021, p. 12)","(Rivera and Chen 2021, 12)","[2, 12]","[2, p. 12]","[2, p. 12]","(Rivera and Chen, 2021, p. 12)","[2]","(Rivera and Chen 2021, p. 12)","(2, p. 12)","(Rivera and Chen 12)","(2, p. 12)","(Rivera and Chen 2021, 12)"};
    for(int i=0;i<CitationStyles.NAMES.length;i++) {
      assertEquals(CitationStyles.NAMES[i],cites[i],CitationStyles.citation(source,i,2,"12"));
      String entry=CitationStyles.entry(source,i,2);assertTrue(entry,entry.contains("Shared Spaces"));assertTrue(entry,entry.contains("2021"));assertTrue(entry,entry.contains("Harbor Books"));assertFalse(entry,entry.contains(".."));
    }
    assertEquals(8,CitationStyles.index("\\ISO690Nmerical.XSL"));assertEquals(4,CitationStyles.index("GOST - Title Sort"));
  }
  @Test public void customXmlPropertiesRemainStableAndUnrelatedPartsSurvive() throws Exception {
    Map<String,byte[]> parts=new LinkedHashMap<String,byte[]>();
    parts.put("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"></Types>".getBytes("UTF-8"));
    byte[] unrelated="<data>Preserve me</data>".getBytes("UTF-8");
    parts.put("customXml/cn1Bibliography1.xml",unrelated);parts.put("customXml/cn1Bibliography2Props.xml",unrelated);
    Bibliography library=Bibliography.EMPTY.put(source("A","Rivera, Alex","Spaces"));BibliographyFormats.write(parts,library);
    assertArrayEquals(unrelated,parts.get("customXml/cn1Bibliography1.xml"));assertArrayEquals(unrelated,parts.get("customXml/cn1Bibliography2Props.xml"));
    assertTrue(parts.containsKey("customXml/cn1Bibliography2Props1.xml"));int count=parts.size();
    BibliographyFormats.write(parts,library.style("MLA"));assertEquals(count,parts.size());
    Set<String> warnings=new LinkedHashSet<String>();Bibliography reopened=BibliographyFormats.read(parts,warnings);
    assertTrue(warnings.toString(),warnings.isEmpty());assertEquals("MLA",reopened.style);assertEquals(library.sources.get("A").values,reopened.sources.get("A").values);
    BibliographyFormats.write(parts,library.remove("A"));assertTrue(BibliographyFormats.read(parts,warnings).sources.isEmpty());
  }
  @Test public void compositeCitationsAndLockedFieldsRetainCachedResults() {
    Session s=new Session(Document.empty());Citations.source(s,source("A","Rivera, Alex","Spaces"));
    s.insertField(new InlineField("CITATION A \\m B","Composite cached",false,false));
    s.insertField(new InlineField("CITATION A","Locked cached",true,false));Citations.style(s,"IEEE");
    assertEquals("Composite cachedLocked cached",s.document().plainText());
  }
  @Test public void duplicateSourcesAndInvalidDataRejectWithoutDataLoss() throws Exception {
    Session s=new Session(Document.empty());Document before=s.document();Map<String,String> data=new HashMap<String,String>();data.put("tag","bad tag");
    try{Citations.source(s,new CitationSource(data));fail();}catch(IllegalArgumentException expected){assertSame(before,s.document());}
    data.clear();data.put("author",";;");assertEquals("Source",new CitationSource(data).tag());
    String xml=DocumentXml.write(before).replace("</bibliography>","<source tag=\"A\"/><source tag=\"A\"/></bibliography>");
    try{DocumentXml.read(xml);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Duplicate"));}
  }
}
