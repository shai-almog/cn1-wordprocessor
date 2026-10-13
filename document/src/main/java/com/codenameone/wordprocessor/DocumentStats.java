package com.codenameone.wordprocessor;

import java.util.*;

/** Per-block cached word counts. Updating a status bar does not flatten the entire document. */
public final class DocumentStats {
  private Map<Block, Integer> cache = new HashMap<Block, Integer>();
  private Document previous;
  private int words, measured;

  public int words(Document document) {
    if (document == previous) return words;
    Map<Block, Integer> next = new HashMap<Block, Integer>();
    words = 0;
    measured = 0;
    for (Block block : document.blocks) {
      Integer count = cache.get(block);
      if (count == null) {
        count = count(block);
        measured++;
      }
      next.put(block, count);
      words += count;
    }
    cache = next;
    previous = document;
    return words;
  }

  public int measuredBlocks() {
    return measured;
  }

  private int count(Block block) {
    if (block instanceof Paragraph) return countText(((Paragraph) block).text);
    int sum = 0;
    if (block instanceof Table) {
      Table t = (Table) block;
      for (int r = 0; r < t.rows(); r++)
        for (int c = 0; c < t.columns(); c++)
          for (Paragraph p : t.cell(r, c)) sum += countText(p.text);
    }
    return sum;
  }

  private int countText(RichText text) {
    int count = 0;
    boolean word = false;
    for (int i = 0; i < text.length(); i++) {
      boolean next = !Character.isWhitespace(text.charAt(i));
      if (next && !word) count++;
      word = next;
    }
    return count;
  }
}
