package com.codenameone.wordprocessor;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class CitationCommandsTest {
  @Test public void sourceCitationAndBibliographyCommandsAreTransactional() {
    var s=new Session(Document.empty());var c=new Commands();
    c.execute(s,"references.sources",Json.object("add",Json.object("tag","A","author","Rivera, Alex","title","Spaces","year","2021")));
    assertEquals(1,((List<?>)c.execute(s,"references.sources",Json.object())).size());
    c.execute(s,"references.citation",Json.object("tag","A","pages","15"));s.replace("\n");
    c.execute(s,"references.bibliography",Json.object());var before=s.document();
    c.execute(s,"references.citationStyle",Json.object("style","MLA"));assertTrue(s.document().plainText().contains("(Rivera 15)"));
    s.undo();assertSame(before,s.document());c.execute(s,"references.updateFields",Json.object());
    assertNotNull(Citations.range(s.document()));c.execute(s,"references.removeBibliography",Json.object());assertNull(Citations.range(s.document()));
  }
  @Test public void invalidNestedSourceFieldsCannotMutateDocument() {
    var s=new Session(Document.empty());var c=new Commands();var before=s.document();
    try{c.execute(s,"references.citation",Json.object("source",Json.object("title",15)));fail();}catch(IllegalArgumentException expected){assertSame(before,s.document());}
    try{c.execute(s,"references.sources",Json.object("add",Json.object("tag","A","unknown","value")));fail();}catch(IllegalArgumentException expected){assertSame(before,s.document());}
  }
}
