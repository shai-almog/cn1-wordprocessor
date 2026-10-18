package com.codenameone.wordprocessor;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class IndexCommandsTest {
  @Test public void markInsertUpdateRemoveAndUnsupportedColumnsAreAtomic() {
    var s=new Session(Document.empty());var c=new Commands();s.replace("Kilns\nKilns again\n");
    s.select(0,0,5);Map<?,?> result=(Map<?,?>)c.execute(s,"references.markEntry",Json.object("all",true,"subentry","firing","bold",true));
    assertEquals(2,result.get("marked"));s.select(2,0,0);var before=s.document();
    try{c.execute(s,"references.index",Json.object("columns",2));fail();}catch(IllegalArgumentException expected){assertSame(before,s.document());}
    c.execute(s,"references.index",Json.object("type","runIn","headings",false));
    int[] range=DocumentIndex.range(s.document());assertEquals("Kilns: firing, 1",s.document().paragraph(range[0]+1).text.plainText());
    c.execute(s,"references.updateIndex",Json.object());assertEquals(2,DocumentIndex.range(s.document())[1]-range[0]);
    c.execute(s,"references.removeIndex",Json.object());assertNull(DocumentIndex.range(s.document()));
    s.undo();assertNotNull(DocumentIndex.range(s.document()));
  }
}
