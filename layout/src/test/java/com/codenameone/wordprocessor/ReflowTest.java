package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class ReflowTest {
  @Test public void contentsLeadersAndPageNumbersFitPhoneWithoutChangingPrintTabs() {
    Session s = new Session(Document.empty());
    s.replace("\nOverview\nDetails");
    s.select(1,0,8); s.applyStyle("Heading1");
    s.select(2,0,7); s.applyStyle("Heading2"); s.select(0,0,0);
    LayoutEngine engine = new LayoutEngine(new LayoutEngine.Metrics() {
      public float width(String text, TextStyle style) { return text.length()*6; }
      public float height(TextStyle style) { return 12; }
    });
    TableContents.insert(s, TableContents.options(s.document()), d -> engine.layout(d,0));
    Document original = s.document(), phone = Reflow.document(original, 240);
    assertSame(original.page, s.document().page);
    assertSame(original.bookmarks, phone.bookmarks);
    assertEquals(original.plainText(),phone.plainText());
    assertEquals(original.page.contentWidth(),original.paragraph(1).format.tabs.get(0).position,.01);
    assertEquals(216,phone.paragraph(1).format.tabs.get(0).position,.01);
    for (int entry = 1; entry <= 2; entry++) {
      List<LayoutEngine.Line> lines = new ArrayList<LayoutEngine.Line>();
      for(LayoutEngine.Page page:engine.layout(phone,0).pages)
        for(LayoutEngine.Line line:page.lines) if(line.paragraphId.equals(phone.paragraph(entry).id)) lines.add(line);
      assertEquals(1,lines.size());
      LayoutEngine.Line line = lines.get(0);
      LayoutEngine.Span last = line.spans.get(line.spans.size()-1);
      assertEquals(228, line.x + last.x + last.width, .01);
    }
  }

  @Test public void eachSectionUsesItsOwnScaleAndCellTextKeepsIdentity() {
    Session s = new Session(Document.empty()); s.replace("A\t1\nB\t2");
    s.select(0,0,0); s.paragraph("tabs","200,right,dot");
    s.select(1,0,0); s.paragraph("tabs","400,right,dot");
    Document d = s.document().section(0,new Section(new PageSpec(300,500,50,50,50,50),PageFurniture.EMPTY,"nextPage",0));
    Document phone = Reflow.document(d,224);
    assertEquals(200,phone.paragraph(0).format.tabs.get(0).position,.01);
    assertEquals(400*200/d.page.contentWidth(),phone.paragraph(1).format.tabs.get(0).position,.01);
    assertSame(d.paragraph(0).text,phone.paragraph(0).text);
  }
}
