package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class DocumentTextTest {
  @Test
  public void nativeOffsetsSurviveParagraphSplitsAndQueuedSelections() {
    Session s = new Session(Document.empty());
    s.replace("Quarterly report");
    s.replace("\n");
    DocumentText text = new DocumentText(s.document());
    assertEquals("Quarterly report\n", text.text);
    int[] p = text.position(17);
    assertArrayEquals(new int[] {1, 0}, p);
    s.select(p[0], p[1], p[1]);
    s.replace("The ribbon supports editing.");
    text = new DocumentText(s.document());
    assertEquals(45, text.absolute(1, 28));
    assertArrayEquals(new int[] {1, 28}, text.position(45));
    int[] a = text.position(10), b = text.position(21);
    s.selectRange(a[0], a[1], b[0], b[1]);
    s.replace("revised ");
    assertEquals("Quarterly revised ribbon supports editing.", s.document().plainText());
  }

  @Test
  public void positionsClampAndDoNotSplitSurrogates() {
    Session s = new Session(Document.empty());
    s.replace("a😀b\nend");
    DocumentText t = new DocumentText(s.document());
    assertArrayEquals(new int[] {0, 1}, t.position(2));
    assertArrayEquals(new int[] {0, 0}, t.position(-1));
    assertArrayEquals(new int[] {1, 3}, t.position(999));
  }
}
