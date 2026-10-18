package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class DocumentIndexTest {
  private final LayoutEngine engine = new LayoutEngine(new LayoutEngine.Metrics() {
    public float width(String text, TextStyle style) { return text.length() * 6; }
    public float height(TextStyle style) { return 12; }
  });
  private TableContents.Paginator pages() { return d -> engine.layout(d, 0); }
  private int mark(Session s, String entry, String sub, String see, String bookmark, boolean bold, boolean italic, boolean all, String search) {
    return DocumentIndex.mark(s, entry, sub, see, bookmark, bold, italic, all, search);
  }
  private List<String> entries(Document d) {
    var values = new ArrayList<String>(); int[] range = DocumentIndex.range(d);
    for (int i = range[0] + 1; i < range[1]; i++) values.add(d.paragraph(i).text.plainText());
    return values;
  }
  @Test public void markAllHonorsBoundariesCaseDuplicatesAndUndo() {
    var s = new Session(Document.empty()); s.replace("Kilns are hot.\nGlaze the kilns.\nKilns again.\nKilnsmith");
    s.select(0, 0, 5); var before = s.document();
    assertEquals(2, mark(s, "Kilns", "", "", "", false, false, true, "Kilns"));
    assertEquals(before.plainText(), s.document().plainText());
    s.undo(); assertSame(before, s.document()); s.redo();
    assertEquals(0, mark(s, "Kilns", "", "", "", false, false, true, "Kilns"));
    s.select(0, 0, 5); mark(s, "Kilns", "firing", "", "", true, false, false, "");
    assertEquals("Kilns are hot.", s.document().paragraph(0).text.plainText());
    assertEquals("XE \"Kilns:firing\" \\b", s.document().paragraph(0).text.fieldAt(5).instruction);
  }
  @Test public void indentedRunInCrossReferencesAndPageStyles() {
    var s = new Session(Document.empty()); s.replace("Kilns\nPresses\n");
    s.select(0, 0, 5); mark(s,"Kilns","firing","","",true,false,false,"");
    s.select(0, 0, 5); mark(s,"Kilns","","","",false,false,false,"");
    s.select(1, 0, 7); mark(s,"Presses","","See Kilns","",false,false,false,"");
    s.select(2,0,0); var before=s.document();
    DocumentIndex.insert(s,new DocumentIndex.Options("Index",true,true,true,"hyphen"),pages());
    assertEquals(Arrays.asList("K","Kilns\t1: firing, 1","P","Presses. See Kilns"),entries(s.document()));
    int[] range=DocumentIndex.range(s.document()); var line=s.document().paragraph(range[0]+2);
    assertTrue(line.text.styleAt(line.text.length()-1).bold);
    assertEquals("hyphen",line.format.tabs.get(0).leader);
    s.undo(); assertSame(before,s.document()); s.redo();
    DocumentIndex.insert(s,new DocumentIndex.Options("Index",false,false,false,"none"),pages());
    assertEquals(Arrays.asList("Kilns, 1","firing, 1","Presses. See Kilns"),entries(s.document()));
    assertEquals(36,s.document().paragraph(DocumentIndex.range(s.document())[0]+2).indent,0);
    DocumentIndex.update(s,pages()); assertEquals(3,entries(s.document()).size());
    DocumentIndex.remove(s); assertNull(DocumentIndex.range(s.document())); assertEquals(before.plainText(),s.document().plainText());
  }
  @Test public void paginationIncludesIndexAndBookmarkRanges() {
    var s=new Session(Document.empty()); s.replace("\nAlpha\nBeta\nGamma\nDelta\nEpsilon\nZeta");
    s.replaceDocument(s.document().page(new PageSpec(300,100,10,10,10,10)));
    var marks=new LinkedHashMap<String,Bookmark>();
    marks.put("span",new Bookmark("span",s.document().paragraph(1).id,0,s.document().paragraph(6).id,4));
    s.replaceDocument(s.document().bookmarks(marks));
    for(int i=1;i<=6;i++){s.select(i,0,0);mark(s,s.document().paragraph(i).text.plainText(),"","","",false,false,false,"");}
    s.select(1,0,0);mark(s,"Whole range","","","span",false,true,false,"");
    s.select(0,0,0);DocumentIndex.insert(s,DocumentIndex.options(s.document()),pages());
    var layout=pages().layout(s.document());
    var pageNumbers=new HashMap<String,Integer>();
    for(LayoutEngine.Page p:layout.pages)for(LayoutEngine.Line l:p.lines)pageNumbers.put(l.paragraphId,p.number);
    int first=pageNumbers.get(marks.get("span").startId),last=pageNumbers.get(marks.get("span").endId);
    assertTrue(first>1);assertTrue(last>first);
    assertTrue(entries(s.document()).toString(),entries(s.document()).contains("Whole range, "+first+"\u2013"+last));
    assertTrue(entries(s.document()).contains("Alpha, "+first));
  }
  @Test public void tableMarksAndUpdateKeepCellSelection() {
    var s=new Session(Document.empty());s.replace("Alpha\n");s.select(1,0,0);s.insertTable(1,2);
    int table=2;assertTrue(s.document().blocks.get(table) instanceof Table);
    s.enterCell(table,0,0);s.replace("Alpha");s.leaveCell();s.enterCell(table,0,1);s.replace("Alpha");s.leaveCell();
    assertEquals(3,mark(s,"Alpha","","","",false,false,true,"Alpha"));
    s.select(0,0,0);DocumentIndex.insert(s,DocumentIndex.options(s.document()),pages());
    assertEquals(Arrays.asList("A","Alpha, 1"),entries(s.document()));
    table+=3;s.enterCell(table,0,1);s.select(0,1,4);
    DocumentIndex.update(s,pages());assertTrue(s.inCell());assertEquals(1,s.cellColumn());
    assertEquals(1,s.selection().anchor);assertEquals(4,s.selection().caret);
  }
  @Test public void failedPaginationAndInvalidEntriesRollback() {
    var s=new Session(Document.empty());s.replace("Alpha");var before=s.document();
    try{mark(s,"Alpha","","","missing",false,false,false,"");fail();}catch(IllegalArgumentException expected){assertSame(before,s.document());}
    s.select(0,0,0);mark(s,"Alpha","","","",false,false,false,"");before=s.document();
    try{DocumentIndex.insert(s,DocumentIndex.options(s.document()),d->{throw new IllegalStateException("layout failed");});fail();}
    catch(IllegalStateException expected){assertSame(before,s.document());}
  }
}
