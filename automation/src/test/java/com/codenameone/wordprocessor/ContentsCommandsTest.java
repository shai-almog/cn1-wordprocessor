package com.codenameone.wordprocessor;
import static org.junit.Assert.*;
import org.junit.Test;
public class ContentsCommandsTest {
  @Test public void sourcesAreValidatedAndCommandsPreserveSelectedText() {
    var s=new Session(Document.empty()); var commands=new Commands(); s.replace("\nSource"); s.select(1,0,6);
    commands.execute(s,"references.addText",Json.object("level",2));
    assertEquals(2,s.document().paragraph(1).format.outlineLevel);
    commands.execute(s,"references.markToc",Json.object("level",3));
    assertEquals("Source",s.document().paragraph(1).text.plainText());
    assertEquals("Source",new FieldCode(s.document().paragraph(1).text.fieldAt(6).instruction).arguments.get(0));
    s.select(0,0,0);
    commands.execute(s,"references.toc",Json.object("headingStyles",false,"outlineLevels",false,"tcFields",true));
    assertEquals("Source\t1",s.document().paragraph(1).text.plainText());
    var before=s.document();
    try { commands.execute(s,"references.toc",Json.object("styleLevels",Json.object("Normal",2.5))); fail(); }
    catch(IllegalArgumentException expected) { assertSame(before,s.document()); }
    try { commands.execute(s,"references.addText",Json.object("level",10)); fail(); }
    catch(IllegalArgumentException expected) { assertSame(before,s.document()); }
  }
}
