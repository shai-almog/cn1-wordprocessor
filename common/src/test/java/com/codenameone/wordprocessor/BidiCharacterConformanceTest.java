package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import com.codename1.ui.editor.BidiBridge;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/// Runs the Unicode 16.0.0 BidiCharacterTest.txt conformance cases (resolved levels and visual order)
/// through BidiBridge. The committed fixture is every 60th case of the official file; pass
/// -Dbidi.characterTest=/path/to/BidiCharacterTest.txt to run all 91,000+ cases.
public class BidiCharacterConformanceTest {

  @Test
  public void bidiCharacterTestSubsetPasses() throws Exception {
    String full = System.getProperty("bidi.characterTest");
    InputStream in =
        full != null && full.length() > 0
            ? new FileInputStream(full)
            : BidiCharacterConformanceTest.class.getResourceAsStream(
                "/BidiCharacterTest-subset.txt");
    assertNotNull(in);
    int total = 0;
    int failed = 0;
    StringBuilder firstFailures = new StringBuilder();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        total++;
        String err = check(line);
        if (err != null) {
          failed++;
          if (failed <= 5) firstFailures.append(err).append('\n');
        }
      }
    }
    System.out.println("BidiCharacterTest: " + (total - failed) + "/" + total + " passed");
    assertTrue(total > 0);
    assertEquals(firstFailures.toString(), 0, failed);
  }

  private static String check(String line) {
    String[] f = line.split(";");
    String[] cps = f[0].trim().split(" ");
    StringBuilder text = new StringBuilder();
    int[] unitOf = new int[cps.length];
    for (int i = 0; i < cps.length; i++) {
      unitOf[i] = text.length();
      text.appendCodePoint(Integer.parseInt(cps[i], 16));
    }
    // Field 2 is the resolved paragraph level; for auto direction (2) it is the P2/P3 result, which the
    // editor computes outside BidiBridge, so the case is run with that level as its base direction.
    boolean rtl = Integer.parseInt(f[2].trim()) == 1;
    byte[] levels = BidiBridge.levels(text.toString(), rtl);
    String[] expected = f[3].trim().split(" ");
    StringBuilder visible = new StringBuilder();
    int keptCount = 0;
    for (int i = 0; i < cps.length; i++) {
      if (expected[i].equals("x")) continue;
      keptCount++;
      int got = levels[unitOf[i]];
      if (got != Integer.parseInt(expected[i])) {
        return line + " -> level " + got + " at " + i;
      }
    }
    // Visual order of the kept code points (rule L2 over their levels).
    byte[] kept = new byte[keptCount];
    int[] keptIndex = new int[keptCount];
    int k = 0;
    for (int i = 0; i < cps.length; i++) {
      if (expected[i].equals("x")) continue;
      kept[k] = levels[unitOf[i]];
      keptIndex[k++] = i;
    }
    int[] order = BidiBridge.visualOrder(kept);
    for (int v = 0; v < order.length; v++) {
      if (visible.length() > 0) visible.append(' ');
      visible.append(keptIndex[order[v]]);
    }
    String want = f.length > 4 ? f[4].trim() : "";
    if (!visible.toString().equals(want)) {
      return line + " -> order " + visible;
    }
    return null;
  }
}
