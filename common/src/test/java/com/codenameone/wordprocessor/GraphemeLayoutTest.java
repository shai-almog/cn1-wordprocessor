package com.codenameone.wordprocessor;
import static org.junit.Assert.*;
import org.junit.Test;
public class GraphemeLayoutTest {
  @Test public void narrowLinesAndDistributedSpacingKeepClustersWhole() {
    for(String alignment:new String[]{"left","distribute","justify"}) {
      var s=new Session(Document.empty());s.replace("á👩🏽‍💻🇮🇱 क्‍षZ");s.paragraph("align",alignment);
      s.select(0,1,2);s.format("bold","true");
      var engine=new LayoutEngine(new LayoutEngine.Metrics(){
        public float width(String s,TextStyle style){return s.codePointCount(0,s.length())*10;}
        public float height(TextStyle style){return 12;}
      });
      var d=s.document().page(new PageSpec(80,500,10,30,10,30));
      var result=engine.layout(d,0);var text=d.paragraph(0).text;
      int end=0;var joined=new StringBuilder();
      for(LayoutEngine.Page page:result.pages)for(LayoutEngine.Line line:page.lines){
        assertEquals(end,line.start);assertTrue(Graphemes.isBoundary(text,line.start));assertTrue(Graphemes.isBoundary(text,line.end));
        for(LayoutEngine.Span span:line.spans)joined.append(span.text);
        end=line.end;
      }
      assertEquals(text.length(),end);assertEquals(text.toString(),joined.toString());
    }
  }
}
