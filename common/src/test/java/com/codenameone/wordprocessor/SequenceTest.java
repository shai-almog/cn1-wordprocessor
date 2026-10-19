package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.ArrayList;
import org.junit.Test;

public class SequenceTest {
  @Test
  public void bulkBuiltSequencesAreBalancedAndEditable() {
    for (int n : new int[] {0, 1, 2, 3, 7, 8, 1000}) {
      var values = new ArrayList<Integer>();
      for (int i = 0; i < n; i++) values.add(i);
      var s = Sequence.of(values);
      assertEquals(n, s.size());
      for (int i = 0; i < n; i++) assertEquals(i, (int) s.get(i));
      assertTrue("depth " + s.depth(), s.depth() <= 32 - Integer.numberOfLeadingZeros(n));
      var edited = s.insert(n / 2, -1).remove(0);
      assertEquals(n, edited.size());
      if (n > 1) assertEquals(-1, (int) edited.get(n / 2 - 1));
    }
  }
}
