package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import com.codename1.ui.RichTextFormat;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.Test;

public class TextBlocksThreadTest {
  @Test
  public void cellNestingDepthIsNotSharedAcrossThreads() throws Exception {
    // A static mutable counter would let a read on one thread change another's cap.
    for (Field f : TextBlocks.class.getDeclaredFields())
      if (Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers()))
        fail("shared mutable static: " + f.getName());
    var b = new StringBuilder();
    for (int i = 0; i < 200; i++) b.append("<table><tr><td>");
    b.append("deepest");
    for (int i = 0; i < 200; i++) b.append("</td></tr></table>");
    String html = b.toString();
    Throwable[] err = new Throwable[1];
    Thread[] ts = new Thread[4];
    for (int t = 0; t < ts.length; t++) {
      ts[t] = new Thread(() -> {
        try {
          for (int r = 0; r < 5; r++)
            assertTrue(TextFormats.read(html, RichTextFormat.HTML, true).blocks.get(0) instanceof Table);
        } catch (Throwable e) {
          err[0] = e;
        }
      });
      ts[t].start();
    }
    for (Thread t : ts) t.join();
    if (err[0] != null) throw new AssertionError(err[0]);
  }
}
