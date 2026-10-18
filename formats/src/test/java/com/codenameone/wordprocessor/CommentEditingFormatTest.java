package com.codenameone.wordprocessor;
import static org.junit.Assert.*;
import java.io.*;
import org.junit.Test;
public class CommentEditingFormatTest {
  @Test public void editedMultilineCellThreadsPreserveBlankLinesAndMetadata() throws Exception {
    var s = new Session(Document.empty()); s.insertTable(1, 1); s.setCell(1, 0, 0, "hello");
    s.enterCell(1, 0, 0); s.select(0, 0, 5); s.addComment("Ada", "Old");
    var id = s.document().comments.get(0).id; s.replyComment(id, "Grace", "Reply"); s.updateComment(id, true, false);
    s.editComment(id, -1, "\nFirst\n\nLast\n"); s.editComment(id, 0, "\nReply\n\n");
    var bytes = new ByteArrayOutputStream(); Docx.write(s.document(), bytes);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray())); assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    for (Document d : new Document[] {DocumentXml.read(DocumentXml.write(s.document())), opened.document}) {
      var c = d.comments.get(0); assertEquals("\nFirst\n\nLast\n", c.text); assertEquals("\nReply\n\n", c.replies.get(0).text);
      assertTrue(c.resolved); assertEquals("Ada", c.author); assertEquals("Grace", c.replies.get(0).author);
      var roundtrip = new Session(d); roundtrip.selectComment(c.id); assertTrue(roundtrip.inCell()); assertEquals("hello", roundtrip.selectedText());
    }
  }
}
