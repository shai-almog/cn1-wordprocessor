package com.codenameone.wordprocessor;
import static org.junit.Assert.*;
import org.junit.Test;
public class CellCommentsTest {
  private Session sample() {
    var s=new Session(Document.empty());s.replace("Body text");s.select(0,0,4);s.addComment("Ada","Body");
    s.select(0,9,9);s.insertTable(2,2);s.setCell(1,0,0,"alpha beta gamma");s.setCell(1,1,1,"other cell");
    s.enterCell(1,0,0);s.select(0,6,10);s.addComment("Grace","Cell");return s;
  }
  private String cellId(Session s){for(ReviewComment c:s.document().comments)if(c.text.equals("Cell"))return c.id;throw new AssertionError();}
  @Test public void addNavigateAndUndoPreserveBodyAndCellComments() {
    var s=sample();var id=cellId(s);assertEquals(2,s.document().comments.size());
    s.leaveCell();s.select(0,0,0);s.replace("prefix ");s.selectComment(id);
    assertTrue(s.inCell());assertEquals("beta",s.selectedText());assertEquals(0,s.cellRow());
    s.leaveCell();s.selectComment(s.document().comments.get(0).id);assertFalse(s.inCell());assertEquals("Body",s.selectedText());
    s.undo();assertEquals(2,s.document().comments.size());s.undo();assertEquals(1,s.document().comments.size());s.redo();assertEquals(2,s.document().comments.size());
  }
  @Test public void editsAndParagraphSplitsUseActualCellEditRange() {
    var s=sample();var id=cellId(s);s.select(0,0,0);s.replace("prefix ");s.selectComment(id);assertEquals("beta",s.selectedText());assertEquals(13,s.selection().start());
    s.select(0,13,13);s.enterParagraph();s.selectComment(id);assertEquals("beta",s.selectedText());assertEquals(1,s.selection().block);
    s.select(1,0,4);s.replace("");assertEquals(1,s.document().comments.size());s.undo();s.selectComment(id);assertEquals("beta",s.selectedText());
  }
  @Test public void repeatedTextCellInsertionHasUnambiguousAnchor() {
    var s=new Session(Document.empty());s.insertTable(1,1);s.setCell(1,0,0,"aaaa");s.enterCell(1,0,0);s.select(0,1,3);s.addComment("Ada","pair");var id=s.document().comments.get(0).id;
    s.select(0,0,0);s.replace("a");s.selectComment(id);assertEquals(2,s.selection().start());assertEquals(4,s.selection().end());
  }
  @Test public void rowMovesMergesAndCellRemovalKeepOrDropTheCorrectAnchor() {
    var s=sample();var id=cellId(s);s.leaveCell();s.tableStructure(1,"insertRow",0);s.selectComment(id);assertEquals(1,s.cellRow());assertEquals("beta",s.selectedText());
    s.leaveCell();s.mergeCells(1,0,0,2,2);s.selectComment(id);assertEquals(0,s.cellRow());assertEquals("beta",s.selectedText());
    s.leaveCell();s.splitCell(1,0,0);s.selectComment(id);assertEquals("beta",s.selectedText());
    s.leaveCell();s.tableStructure(1,"deleteRow",0);assertEquals(1,s.document().comments.size());s.undo();s.selectComment(id);assertEquals("beta",s.selectedText());
  }
  @Test public void replacementAndTrackedAcceptKeepCellThreadsConsistent() {
    var s=sample();var id=cellId(s);s.leaveCell();s.setCell(1,0,0,"prefix alpha beta gamma");s.selectComment(id);assertEquals("beta",s.selectedText());
    s.trackChanges(true);s.selectComment(id);s.replace("");s.resolveRevision(null,true);assertEquals(1,s.document().comments.size());s.undo();assertEquals(2,s.document().comments.size());
  }
  @Test public void repliesResolveReopenDeleteAndUndoWorkForCellThreads() {
    var s=sample();var id=cellId(s);s.updateComment(id,true,false);s.replyComment(id,"Lin","Confirmed");
    var c=s.document().comments.get(1);assertFalse(c.resolved);assertEquals("Confirmed",c.replies.get(0).text);
    s.updateComment(id,false,true);assertEquals(1,s.document().comments.size());s.undo();s.selectComment(id);assertEquals("beta",s.selectedText());
  }
  @Test public void multiParagraphCellRangeAndOtherCellEditsStayIndependent() {
    var s=sample();s.leaveCell();s.setCell(1,1,1,"first\nsecond");s.enterCell(1,1,1);s.selectRange(0,2,1,3);s.addComment("Other","Across");var id=s.document().comments.get(2).id;
    s.enterCell(1,0,0);s.select(0,0,0);s.replace("prefix ");s.selectComment(id);assertEquals("rst\nsec",s.selectedText());assertEquals(1,s.cellRow());assertEquals(1,s.cellColumn());
  }
}
