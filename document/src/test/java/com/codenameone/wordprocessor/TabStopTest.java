package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TabStopTest {
  @Test
  public void tabsAreImmutableSortedAndTransactional() {
    List<TabStop> input =
        new ArrayList<TabStop>(
            Arrays.asList(new TabStop(120, "right", "dot"), new TabStop(60, "left", "none")));
    ParagraphFormat f = new ParagraphFormat(0, 0, 36, false, true, input);
    input.clear();
    assertEquals(60, f.tabs.get(0).position, 0);
    assertEquals(2, f.tabs.size());
    try {
      f.tabs.clear();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    Session s = new Session(Document.empty());
    s.paragraph("tabs", TabStop.encode(f.tabs));
    s.paragraph("firstIndent", "18");
    assertEquals(2, s.document().paragraph(0).format.tabs.size());
    s.undo();
    s.undo();
    assertTrue(s.document().paragraph(0).format.tabs.isEmpty());
    s.redo();
    assertEquals("right", s.document().paragraph(0).format.tabs.get(1).alignment);
    Document before = s.document();
    try {
      s.paragraph("tabs", "NaN,right,dot");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
  }

  @Test
  public void duplicatePositionsReplaceAndInvalidStopsReject() {
    assertEquals(
        1,
        TabStop.normalized(
                Arrays.asList(new TabStop(72, "left", "none"), new TabStop(72, "right", "dot")))
            .size());
    for (String value :
        new String[] {
          "-1,left,none", "72,garbage,none", "72,left,garbage", "1,left", "Infinity,left,none"
        }) {
      try {
        TabStop.decode(value);
        fail(value);
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
