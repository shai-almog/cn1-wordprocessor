package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import com.codename1.ui.RichTextFormat;
import org.junit.Test;

public class HtmlNestedTableDepthTest {
  @Test
  public void deeplyNestedHtmlTablesAreCappedNotOverflowed() {
    int levels = 3000;
    var b = new StringBuilder("<html><body>");
    for (int i = 0; i < levels; i++) b.append("<table><tr><td>");
    b.append("deepest");
    for (int i = 0; i < levels; i++) b.append("</td></tr></table>");
    b.append("</body></html>");
    Document d = TextFormats.read(b.toString(), RichTextFormat.HTML, true);
    int depth = 0;
    Block block = null;
    for (Block x : d.blocks) if (x instanceof Table) block = x;
    assertNotNull(block);
    while (block instanceof Table t) {
      depth++;
      Block next = null;
      for (Block x : t.blocks(0, 0)) if (x instanceof Table) next = x;
      if (next == null) {
        String text = "";
        for (Block x : t.blocks(0, 0)) if (x instanceof Paragraph p) text += p.text.toString();
        assertTrue(depth + ":" + t.blocks(0, 0).size() + ":" + text, text.contains("deepest"));
      }
      block = next;
    }
    assertTrue("depth " + depth, depth <= TextBlocks.MAX_NESTING + 1);
  }
}
