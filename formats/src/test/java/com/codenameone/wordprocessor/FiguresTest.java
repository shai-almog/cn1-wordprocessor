package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.io.*;
import org.junit.Test;

public class FiguresTest {
  private final LayoutEngine engine = new LayoutEngine(new PdfMetrics());
  private TableContents.Paginator pages() { return d -> engine.layout(d,0); }
  private Session fixture() {
    var blocks = new Sequence<Block>().append(new Paragraph("p1",new RichText()))
        .append(new Paragraph("p2",RichText.of("Overview",TextStyle.DEFAULT)).with("style","Heading1"));
    for (int i=0; i<3; i++) {
      String label=i==2 ? "Table" : "Figure";
      var text=RichText.of(i==1 ? "" : label+" ",TextStyle.DEFAULT)
          .concat(RichText.field(new InlineField("SEQ "+label,"1",false,false),TextStyle.DEFAULT))
          .concat(RichText.of(": Caption "+i,TextStyle.DEFAULT));
      blocks=blocks.append(new Paragraph("p"+(i+3),text).with("style","Caption"));
    }
    return new Session(new Document(blocks,PageSpec.A4,"Figures",6));
  }
  @Test public void figuresUseSequenceLabelsIncludingHiddenLabelsAndCoexistWithContents() throws Exception {
    var s=fixture();
    TableContents.insert(s,TableContents.options(s.document()),pages());
    TableContents.insert(s,TableContents.options(s.document(),"Figure"),pages());
    int[] figures=TableContents.range(s.document(),"Figure");
    assertEquals(3,figures[1]-figures[0]);
    assertEquals("Figure 1: Caption 0\t1",s.document().paragraph(figures[0]+1).text.plainText());
    assertEquals("2: Caption 1\t1",s.document().paragraph(figures[0]+2).text.plainText());
    assertNotNull(TableContents.range(s.document()));
    TableContents.insert(s,TableContents.options(s.document(),"Table"),pages());
    assertEquals(3,TableContents.ranges(s.document()).size());
    var out=new ByteArrayOutputStream();Docx.write(s.document(),out);
    Docx.Opened opened=Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(),opened.warnings.isEmpty());
    assertEquals(3,TableContents.ranges(opened.document).size());
    var reopened=new Session(DocumentXml.read(DocumentXml.write(opened.document)));
    int source=ReviewComment.block(reopened.document(),opened.document.bookmarks.get("_Toc2").startId);
    reopened.select(source,reopened.document().paragraph(source).text.length(),reopened.document().paragraph(source).text.length());
    reopened.replace(" revised");
    TableContents.updateFigures(reopened,pages());
    figures=TableContents.range(reopened.document(),"Figure");
    assertTrue(reopened.document().paragraph(figures[0]+1).text.plainText().contains("revised"));
    var before=reopened.document();
    TableContents.remove(reopened,"Figure");
    assertNull(TableContents.range(reopened.document(),"Figure"));
    assertNotNull(TableContents.range(reopened.document(),"Table"));
    assertNotNull(TableContents.range(reopened.document()));
    reopened.undo();assertSame(before,reopened.document());
  }

  @Test public void updatedCaptionNumbersAreUsedAndCustomLabelDoesNotCollectSimilarNames() {
    var s=fixture();
    TableContents.insert(s,TableContents.options(s.document(),"Figure"),pages());
    int[] figures=TableContents.range(s.document(),"Figure");
    var noPages=new TableContents.Options(1,"Illustrations",false,true,"dot",false,"Figure");
    TableContents.insert(s,noPages,pages());
    figures=TableContents.range(s.document(),"Figure");
    assertEquals("Illustrations",s.document().paragraph(figures[0]).text.plainText());
    assertEquals("2: Caption 1",s.document().paragraph(figures[0]+2).text.plainText());
    TableContents.insert(s,TableContents.options(s.document(),"Fig"),pages());
    int[] empty=TableContents.range(s.document(),"Fig");
    assertEquals(1,empty[1]-empty[0]);
  }
}
