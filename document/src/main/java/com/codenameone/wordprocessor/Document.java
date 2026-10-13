package com.codenameone.wordprocessor;

import java.util.*;

/** Immutable document root. IDs are document-local and stable through undo and preview. */
public final class Document {
  public final Sequence<Block> blocks;
  public final PageSpec page;
  public final String title;
  public final long nextId;

  public Document(Sequence<Block> blocks, PageSpec page, String title, long nextId) {
    if (blocks == null || page == null || title == null || blocks.size() == 0 || nextId < 1)
      throw new IllegalArgumentException("Invalid document");
    this.blocks = blocks;
    this.page = page;
    this.title = title;
    this.nextId = nextId;
  }

  public static Document empty() {
    return new Document(
        new Sequence<Block>().append(new Paragraph("p1", new RichText())),
        PageSpec.A4,
        "Untitled",
        2);
  }

  public Document blocks(Sequence<Block> b, long next) {
    return new Document(b, page, title, next);
  }

  public Document page(PageSpec p) {
    return new Document(blocks, p, title, nextId);
  }

  public Document title(String t) {
    return new Document(blocks, page, t, nextId);
  }

  public String plainText() {
    StringBuilder s = new StringBuilder();
    boolean first = true;
    for (Block b : blocks) {
      if (!first) s.append('\n');
      first = false;
      if (b instanceof Paragraph) s.append(((Paragraph) b).text);
      else if (b instanceof Table) {
        Table t = (Table) b;
        for (int r = 0; r < t.rows(); r++) {
          if (r > 0) s.append('\n');
          for (int c = 0; c < t.columns(); c++) {
            if (c > 0) s.append('\t');
            int index = 0;
            for (Paragraph p : t.cell(r, c)) {
              if (index++ > 0) s.append('\n');
              s.append(p.text);
            }
          }
        }
      }
    }
    return s.toString();
  }

  public Paragraph paragraph(int index) {
    Block b = blocks.get(index);
    if (!(b instanceof Paragraph)) throw new IllegalArgumentException("Select a paragraph");
    return (Paragraph) b;
  }
}
