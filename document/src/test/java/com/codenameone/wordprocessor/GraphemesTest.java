package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class GraphemesTest {
  @Test public void unicode16OfficialConformanceForwardBackwardAndEveryOffset() throws Exception {
    Path data = Paths.get("../third-party/unicode-16.0.0/GraphemeBreakTest.txt");
    int cases = 0;
    for (String raw : Files.readAllLines(data, StandardCharsets.UTF_8)) {
      String line = raw.split("#", 2)[0].trim();
      if (line.isEmpty()) continue;
      List<Integer> bounds = new ArrayList<Integer>();
      StringBuilder text = new StringBuilder();
      for (String token : line.split("\\s+")) {
        if ("÷".equals(token)) bounds.add(text.length());
        else if (!"×".equals(token)) text.appendCodePoint(Integer.parseInt(token, 16));
      }
      RichText rope = RichText.of(text.toString());
      for (int i = 0; i <= rope.length(); i++)
        assertEquals(raw + " offset " + i, bounds.contains(i), Graphemes.isBoundary(rope,i));
      for (int i = 1; i < bounds.size(); i++) {
        assertEquals(raw, (int)bounds.get(i), Graphemes.next(rope,bounds.get(i-1)));
        assertEquals(raw, (int)bounds.get(i-1), Graphemes.previous(rope,bounds.get(i)));
      }
      cases++;
    }
    assertEquals(1093,cases);
  }

  private static final String[] CLUSTERS = {"a\u0301", "ש\u05b8", "क्‍ष", "각", "👩🏽‍💻", "🇮🇱", "👨‍👩‍👧‍👦"};
  @Test public void backspaceAndDeleteRemoveWholeClustersAndUndoRestoresThem() {
    for (String cluster : CLUSTERS) {
      Session s = new Session(Document.empty());s.replace("L"+cluster+"R");
      s.select(0,1,1);s.deleteForward();assertEquals("LR",s.document().plainText());
      s.undo();assertEquals("L"+cluster+"R",s.document().plainText());
      s.select(0,1+cluster.length(),1+cluster.length());s.deleteBackward();
      assertEquals("LR",s.document().plainText());s.undo();s.redo();assertEquals("LR",s.document().plainText());
    }
  }
  @Test public void styleAndRopeLeafBoundariesDoNotSplitClusters() {
    StringBuilder prefix=new StringBuilder();for(int i=0;i<255;i++)prefix.append('x');
    RichText text=RichText.of(prefix+"a").concat(RichText.of("\u0301",TextStyle.DEFAULT.with("bold","true"))).concat(RichText.of("z"));
    assertEquals(257,Graphemes.next(text,255));assertEquals(255,Graphemes.previous(text,257));
    assertFalse(Graphemes.isBoundary(text,256));
  }
  @Test public void interiorCaretDeletesWholeClusterAndAdjacentFlagPairsStaySeparate() {
    Session s=new Session(Document.empty());s.replace("a\u0301b");s.select(0,1,1);s.deleteBackward();assertEquals("b",s.document().plainText());
    s.undo();s.select(0,1,1);s.deleteForward();assertEquals("b",s.document().plainText());
    RichText flags=RichText.of("🇮🇱🇯🇵🇬");assertEquals(4,Graphemes.next(flags,0));assertEquals(8,Graphemes.next(flags,4));assertEquals(10,Graphemes.next(flags,8));
  }
  @Test public void trackedDeletionSkipsAlreadyDeletedClusters() {
    Session s=new Session(Document.empty());s.replace("a\u0301👩🏽‍💻z");s.trackChanges(true);s.select(0,0,0);
    s.deleteForward();s.deleteForward();s.resolveRevision(null,true);
    assertEquals("z",s.document().plainText());
    s=new Session(Document.empty());s.replace("za\u0301👩🏽‍💻");s.trackChanges(true);
    s.deleteBackward();s.deleteBackward();s.resolveRevision(null,true);assertEquals("z",s.document().plainText());
  }
  @Test public void tableCellDeletionUsesSameCharacterBoundaries() {
    Session s=new Session(Document.empty());s.insertTable(1,2);s.setCell(1,0,0,"a\u0301👩🏽‍💻");s.setCell(1,0,1,"keep");
    s.enterCell(1,0,0);s.select(0,0,0);s.deleteForward();assertEquals("👩🏽‍💻",s.editingDocument().plainText());
    s.undo();assertEquals("a\u0301👩🏽‍💻",s.editingDocument().plainText());
  }
}
