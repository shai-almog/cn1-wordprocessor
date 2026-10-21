package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Text box stories, as Wordcraft's text box parts: a drawing picture anywhere in the document
 * (body, table cells, notes, headers and footers) may hold a block story of its own. These walks
 * find and replace those pictures; text box stories do not nest.
 */
public final class TextBoxes {
  private TextBoxes() {}

  /** Pictures whose drawing holds a block story, in document order (body, notes, furniture). */
  public static List<Picture> all(Document d) {
    var out = new ArrayList<Picture>();
    map(d, p -> {
      if (p.drawing != null && p.drawing.story != null) out.add(p);
      return p;
    });
    return out;
  }

  /** The drawing picture with {@code id} anywhere in the document, or null. */
  public static Picture find(Document d, String id) {
    final Picture[] found = {null};
    map(d, p -> {
      if (found[0] == null && p.id().equals(id)) found[0] = p;
      return p;
    });
    return found[0];
  }

  /** Replaces the story of picture {@code id}. */
  public static Document setStory(Document d, String id, Sequence<Block> story) {
    return map(d, p -> p.id().equals(id) && p.drawing != null ? p.drawing(p.drawing.story(story)) : p);
  }

  /** Maps every picture of the body, notes and headers/footers; returns d when none change. */
  public static Document map(Document d, UnaryOperator<Picture> f) {
    var out = d;
    var body = map(d.blocks, f);
    if (body != d.blocks) out = out.blocks(body, out.nextId);
    out = Notes.map(out, n -> {
      var mapped = map(n.blocks, f);
      return mapped == n.blocks ? n : n.blocks(mapped);
    });
    var furniture = furniture(out.furniture, f);
    if (furniture != out.furniture) out = out.furniture(furniture);
    if (!out.sections.isEmpty()) {
      boolean changed = false;
      var next = new LinkedHashMap<String, Section>();
      for (Map.Entry<String, Section> e : out.sections.entrySet()) {
        var s = e.getValue();
        var mf = furniture(s.furniture, f);
        if (mf != s.furniture) {
          s = s.furniture(mf);
          changed = true;
        }
        next.put(e.getKey(), s);
      }
      if (changed) out = out.sections(next, out.lastSection);
    }
    return out;
  }

  private static PageFurniture furniture(PageFurniture pf, UnaryOperator<Picture> f) {
    if (pf == null) return null;
    var out = pf;
    for (String key : new ArrayList<String>(pf.stories.keySet())) {
      var blocks = pf.stories.get(key);
      var mapped = map(blocks, f);
      if (mapped != blocks) out = out.blocks(key, mapped);
    }
    return out;
  }

  /** Maps the pictures of {@code in} at any table depth; returns {@code in} when none change. */
  public static Sequence<Block> map(Sequence<Block> in, UnaryOperator<Picture> f) {
    var out = in;
    for (int i = 0; i < in.size(); i++) {
      Block b = in.get(i), value = b;
      if (b instanceof Picture p) value = f.apply(p);
      else if (b instanceof Table t) {
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            if (!t.covered(r, c)) {
              var cell = t.blocks(r, c);
              var mapped = map(cell, f);
              if (mapped != cell) t = t.setBlocks(r, c, mapped);
            }
        value = t;
      }
      if (value != b) out = out.set(i, value);
    }
    return out;
  }

  /**
   * A copy of {@code story} whose paragraphs (cells included) take fresh ids from {@code next},
   * for a pasted or duplicated text box (ids are unique document-wide, as comments anchor by them).
   */
  public static Sequence<Block> renumber(Sequence<Block> story, long[] next) {
    var out = new Sequence<Block>();
    for (Block b : story) {
      if (b instanceof Paragraph p) out = out.append(p.copy("p" + next[0]++, p.text));
      else if (b instanceof Table t) {
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            if (!t.covered(r, c)) t = t.setBlocks(r, c, renumber(t.blocks(r, c), next));
        out = out.append(t);
      } else out = out.append(b);
    }
    return out;
  }
}
