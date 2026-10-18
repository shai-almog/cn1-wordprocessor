package com.codenameone.wordprocessor;
import static org.junit.Assert.*;
import java.io.*;
import org.junit.Test;
public class CellCommentFormatTest {
  private Document sample() {
    var s=new Session(Document.empty());s.replace("body");s.select(0,0,4);s.addComment("Ada","Body");s.select(0,4,4);s.insertTable(2,2);s.setCell(1,1,1,"first\nsecond");
    s.enterCell(1,1,1);s.selectRange(0,2,1,3);s.addComment("Grace","Cell");var id=s.document().comments.get(1).id;s.replyComment(id,"Lin","Checked");s.updateComment(id,true,false);return s.document();
  }
  private void verify(Document d) {
    assertEquals(2,d.comments.size());var s=new Session(d);var c=d.comments.get(1);s.selectComment(c.id);assertTrue(s.inCell());assertEquals(1,s.cellRow());assertEquals(1,s.cellColumn());assertEquals("rst\nsec",s.selectedText());assertTrue(c.resolved);assertEquals("Checked",c.replies.get(0).text);
  }
  @Test public void nativeCellCommentsAndRepliesRoundTrip() throws Exception {
    String xml=DocumentXml.write(sample());Document d=DocumentXml.read(xml);assertEquals(xml,DocumentXml.write(d));verify(d);
  }
  @Test public void docxCellCommentsAndRepliesRoundTrip() throws Exception {
    var bytes=new ByteArrayOutputStream();Docx.write(sample(),bytes);Docx.Opened opened=Docx.read(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(opened.warnings.toString(),opened.warnings.isEmpty());verify(opened.document);
  }
  @Test public void nativeCrossCellAnchorIsRejected() throws Exception {
    var d=sample();var c=d.comments.get(1);var wrong=((Table)d.blocks.get(1)).cell(0,0).get(0).id;
    var xml=DocumentXml.write(d).replace("endId=\""+c.endId+"\"","endId=\""+wrong+"\"");
    assertNotEquals(DocumentXml.write(d),xml);
    try{DocumentXml.read(xml);fail("cross-cell range accepted");}catch(IOException expected){}
  }
}
