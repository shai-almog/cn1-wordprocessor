package com.codenameone.wordprocessor;

import java.util.*;

/**
 * A selection whose ends lie in different stories of the body: body text and table cells at any
 * nesting depth, as Wordcraft's Pos (story, path, offset) ranges run in document order across
 * containers. A position is a cell path (block, row, column triples from the body, outermost
 * first; empty for the body), a block index in that cell's story and a UTF-16 offset.
 */
public final class MixedRange {
  public static final class Pos {
    public final int[] path;
    public final int block, offset;

    public Pos(int[] path, int block, int offset) {
      this.path = path.clone();
      this.block = block;
      this.offset = offset;
    }

    int[] key() {
      int[] out = Arrays.copyOf(path, path.length + 1);
      out[path.length] = block;
      return out;
    }

    boolean sameStory(Pos o) {
      return Arrays.equals(path, o.path);
    }
  }

  /** A paragraph covered by the range and the part of its text that is selected. */
  public static final class Covered {
    public final int[] path;
    public final int block, from, to;
    public final Paragraph paragraph;

    Covered(int[] path, int block, Paragraph paragraph, int from, int to) {
      this.path = path;
      this.block = block;
      this.paragraph = paragraph;
      this.from = from;
      this.to = to;
    }
  }

  public interface ParagraphEdit {
    Paragraph apply(Paragraph p, int from, int to);
  }

  public final Pos anchor, focus;

  public MixedRange(Pos anchor, Pos focus) {
    this.anchor = anchor;
    this.focus = focus;
  }

  public Pos start() {
    return compare(anchor, focus) <= 0 ? anchor : focus;
  }

  public Pos end() {
    return compare(anchor, focus) <= 0 ? focus : anchor;
  }

  /** Document order: depth-first, rows before columns (Wordcraft's Path ordering). */
  public static int compare(Pos a, Pos b) {
    int c = compareKeys(a.key(), b.key());
    return c != 0 ? c : Integer.compare(a.offset, b.offset);
  }

  static int compareKeys(int[] a, int[] b) {
    for (int i = 0; i < Math.min(a.length, b.length); i++)
      if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
    return Integer.compare(a.length, b.length);
  }

  /** The blocks of the story at {@code path} (the body for an empty path). */
  public static Sequence<Block> story(Document body, int[] path) {
    var blocks = body.blocks;
    for (int i = 0; i + 2 < path.length; i += 3)
      blocks = ((Table) blocks.get(path[i])).blocks(path[i + 1], path[i + 2]);
    return blocks;
  }

  /** Paragraphs from {@code a} to {@code b} in document order, with their selected parts. */
  public List<Covered> covered(Document body) {
    var out = new ArrayList<Covered>();
    Pos a = start(), b = end();
    int first = a.path.length > 0 ? a.path[0] : a.block, last = b.path.length > 0 ? b.path[0] : b.block;
    walk(body.blocks, new int[0], first, last, a, b, out);
    return out;
  }

  private static void walk(
      Sequence<Block> blocks, int[] prefix, int from, int to, Pos a, Pos b, List<Covered> out) {
    for (int i = Math.max(0, from); i <= Math.min(to, blocks.size() - 1); i++) {
      var block = blocks.get(i);
      if (block instanceof Paragraph p) {
        int[] key = Arrays.copyOf(prefix, prefix.length + 1);
        key[prefix.length] = i;
        if (compareKeys(key, a.key()) < 0 || compareKeys(key, b.key()) > 0) continue;
        int f = Arrays.equals(key, a.key()) ? Math.min(a.offset, p.text.length()) : 0;
        int t = Arrays.equals(key, b.key()) ? Math.min(b.offset, p.text.length()) : p.text.length();
        out.add(new Covered(prefix, i, p, f, Math.max(f, t)));
      } else if (block instanceof Table table) {
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++) {
            if (table.covered(r, c)) continue;
            int[] inner = Arrays.copyOf(prefix, prefix.length + 3);
            inner[prefix.length] = i;
            inner[prefix.length + 1] = r;
            inner[prefix.length + 2] = c;
            walk(table.blocks(r, c), inner, 0, Integer.MAX_VALUE, a, b, out);
          }
      }
    }
  }

  /**
   * The paragraph before or after {@code block} of the story at {@code path} in document order,
   * when it lies in another story (into, out of or past a table); null otherwise or at an end.
   */
  public static Pos adjacent(Document body, int[] path, int block, boolean forward) {
    var all = new ArrayList<Covered>();
    var first = new Pos(new int[0], -1, 0);
    var last = new Pos(new int[] {Integer.MAX_VALUE}, 0, 0);
    walk(body.blocks, new int[0], 0, Integer.MAX_VALUE, first, last, all);
    for (int i = 0; i < all.size(); i++) {
      var c = all.get(i);
      if (c.block != block || !Arrays.equals(c.path, path)) continue;
      int j = i + (forward ? 1 : -1);
      if (j < 0 || j >= all.size()) return null;
      var n = all.get(j);
      if (Arrays.equals(n.path, path)) return null;
      return new Pos(n.path, n.block, 0);
    }
    return null;
  }

  /** The body with {@code edit} applied to every covered paragraph. */
  public Document map(Document body, ParagraphEdit edit) {
    var covered = covered(body);
    var blocks = body.blocks;
    for (Covered c : covered) {
      var next = edit.apply(c.paragraph, c.from, c.to);
      if (next != c.paragraph) blocks = setBlock(blocks, c.path, 0, c.block, next);
    }
    return blocks == body.blocks ? body : body.blocks(blocks, body.nextId);
  }

  static Sequence<Block> setBlock(Sequence<Block> blocks, int[] path, int at, int block, Block value) {
    if (at >= path.length) return blocks.set(block, value);
    var table = (Table) blocks.get(path[at]);
    int r = path[at + 1], c = path[at + 2];
    return blocks.set(
        path[at], table.setBlocks(r, c, setBlock(table.blocks(r, c), path, at + 3, block, value)));
  }

  public interface ParagraphChange {
    Paragraph apply(Paragraph p);
  }

  /**
   * The body with {@code f} applied to the paragraphs paragraph formatting covers: every covered
   * paragraph except a last one the range only reaches the start of (Wordcraft's para_paths_in).
   */
  public Document mapParagraphs(Document body, final ParagraphChange f) {
    final Pos b = end();
    final boolean dropLast = b.offset == 0 && !start().sameStory(b) || b.offset == 0 && start().block != b.block;
    return map(
        body,
        (p, from, to) -> {
          if (dropLast && to == 0 && MixedRange.story(body, b.path).get(b.block) == p) return p;
          return f.apply(p);
        });
  }

  /** Selected text: covered paragraphs' parts joined by newlines. */
  public String text(Document body) {
    var b = new StringBuilder();
    boolean first = true;
    for (Covered c : covered(body)) {
      if (!first) b.append('\n');
      first = false;
      b.append(c.paragraph.text.slice(c.from, c.to).plainText());
    }
    return b.toString();
  }

  /** The covered paragraphs, cut to the range, as a flat fragment (Wordcraft's copy_blocks). */
  public Document copy(Document body) {
    var blocks = new Sequence<Block>();
    for (Covered c : covered(body))
      blocks = blocks.append(c.paragraph.text(c.paragraph.text.slice(c.from, c.to)));
    return new Document(blocks, body.page, "Clipboard", body.nextId).styles(body.styles);
  }

  /**
   * Deletes the range as Wordcraft does across containers: text is cleared in every covered
   * paragraph (a tracked deletion when {@code deletion} is set) and, when the range starts in the
   * body and ends in a table, the body tables strictly between are removed.
   */
  public Document delete(Document body, final Revision deletion) {
    var out =
        map(
            body,
            (p, from, to) -> {
              if (from >= to) return p;
              var cut = deletion == null ? new RichText() : p.text.slice(from, to).deleted(deletion);
              return p.text(p.text.replace(from, to, cut));
            });
    Pos a = start(), b = end();
    if (deletion == null && a.path.length == 0 && b.path.length > 0) {
      var blocks = out.blocks;
      for (int i = b.path[0] - 1; i > a.block; i--)
        if (blocks.get(i) instanceof Table) blocks = blocks.remove(i);
      if (blocks != out.blocks) out = out.blocks(blocks, out.nextId);
    }
    return out;
  }
}
