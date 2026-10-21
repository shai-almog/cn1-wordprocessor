package com.codenameone.wordprocessor;

import java.util.*;

/** Revision traversal and acceptance operate on body, cells and owned margin stories alike. */
public final class Revisions {
  private Revisions() {}

  public static final class Change {
    public final Revision revision;
    public final String paragraph, text, story;
    public final int block, row, column, start, end;

    Change(
        Revision revision,
        Paragraph p,
        String text,
        String story,
        int block,
        int row,
        int column,
        int start,
        int end) {
      this.revision = revision;
      paragraph = p.id;
      this.text = text;
      this.story = story;
      this.block = block;
      this.row = row;
      this.column = column;
      this.start = start;
      this.end = end;
    }
  }

  public static List<Change> list(Document d) {
    final var changes = new ArrayList<Change>();
    for (int b = 0; b < d.blocks.size(); b++) {
      var block = d.blocks.get(b);
      if (block instanceof Paragraph paragraph) collect(changes, paragraph, "", b, -1, -1);
      else if (block instanceof Table table) {
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++)
            if (!table.covered(r, c))
              for (Paragraph p : table.cell(r, c)) collect(changes, p, "", b, r, c);
      }
    }
    for (Section.Range section : d.sectionRanges())
      for (String key : PageFurniture.KEYS)
        if (section.raw.furniture.stories.containsKey(key))
          collectBlocks(changes, section.raw.furniture.blocks(key), key, section.first, -1, -1);
    for (Note note : Notes.all(d)) collectBlocks(changes, note.blocks, "note:" + note.id, -1, -1, -1);
    return Collections.unmodifiableList(changes);
  }

  /**
   * A header/footer story's changes: its paragraphs and those in its tables' cells at any depth
   * (located by story and paragraph id, as header paragraphs are).
   */
  private static void collectBlocks(List<Change> out, Sequence<Block> blocks, String story, int block, int row, int col) {
    for (Block b : blocks) {
      if (b instanceof Paragraph p) collect(out, p, story, block, row, col);
      else if (b instanceof Table t)
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            if (!t.covered(r, c)) collectBlocks(out, t.blocks(r, c), story, block, row, col);
    }
  }

  private static void collect(
      final List<Change> out,
      final Paragraph p,
      final String story,
      final int block,
      final int row,
      final int col) {
    p.text.visit(
        (offset, text, style) -> {
          for (var revision = style.revision;
              revision != null && !text.isEmpty();
              revision = revision.previous)
            out.add(
                new Change(
                    revision,
                    p,
                    text,
                    story,
                    block,
                    row,
                    col,
                    offset,
                    offset + (p.text.objectAt(offset) ? 1 : text.length())));
        });
    p.text.visit(
        (offset, text, style) -> {
          if (style.formatRevision != null)
            out.add(
                new Change(
                    style.formatRevision,
                    p,
                    "Formatting: " + text,
                    story,
                    block,
                    row,
                    col,
                    offset,
                    offset + (p.text.objectAt(offset) ? 1 : text.length())));
        });
    if (p.formatRevision != null)
      out.add(
          new Change(
              p.formatRevision,
              p,
              "Paragraph formatting",
              story,
              block,
              row,
              col,
              0,
              p.text.length()));
    for (var revision = p.breakRevision; revision != null; revision = revision.previous)
      out.add(
          new Change(
              revision,
              p,
              "Paragraph break",
              story,
              block,
              row,
              col,
              p.text.length(),
              p.text.length()));
  }

  public static long nextId(Document document) {
    long next = document.nextId;
    for (Change change : list(document)) {
      var id = change.revision.id;
      if (TextUtil.digits(id, 0)) {
        try {
          long value = Long.parseLong(id);
          if (value == Long.MAX_VALUE) throw new IllegalArgumentException("Revision id exhausted");
          next = Math.max(next, value + 1);
        } catch (NumberFormatException ex) {
          throw new IllegalArgumentException("Revision id exceeds supported range", ex);
        }
      }
    }
    return next;
  }

  /** Resolve only the selected characters and paragraph marks, retaining changes outside it. */
  public static Document resolveSelection(
      Document d,
      Selection selection,
      boolean accept,
      boolean paragraphProperties,
      boolean caretMark) {
    Sequence<Block> blocks = d.blocks;
    var sections = new LinkedHashMap<String, Section>(d.sections);
    for (int b = selection.endBlock(); b >= selection.startBlock(); b--) {
      var block = blocks.get(b);
      if (block instanceof Table table) {
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++)
            if (!table.covered(r, c))
              table = table.setCell(r, c, resolveParagraphs(table.cell(r, c), null, accept));
        blocks = blocks.set(b, table);
        continue;
      }
      if (!(block instanceof Paragraph)) continue;
      var p = (Paragraph) block;
      int from = b == selection.startBlock() ? selection.start() : 0;
      int to = b == selection.endBlock() ? selection.end() : p.text.length();
      p = p.text(p.text.replace(from, to, p.text.slice(from, to).review(null, accept)));
      if (paragraphProperties && (b != selection.endBlock() || to > from || selection.collapsed()))
        p = p.reviewFormat(null, accept);
      if ((b < selection.endBlock() || caretMark) && p.breakRevision != null) {
        var resolution = p.breakRevision.resolve(null, accept);
        if (resolution.removed() && b + 1 < blocks.size() && blocks.get(b + 1) instanceof Paragraph) {
          var following = (Paragraph) blocks.get(b + 1);
          var boundary = sections.remove(following.id);
          sections.remove(p.id);
          if (boundary != null) sections.put(p.id, boundary);
          p = p.text(p.text.concat(following.text)).breakRevision(following.breakRevision);
          blocks = blocks.remove(b + 1);
        } else p = p.breakRevision(resolution.remaining());
      }
      blocks = blocks.set(b, p);
    }
    return d.blocks(blocks, d.nextId).sections(sections, d.lastSection);
  }

  /** The move names carried by the changes whose ids are given, or that a selection holds. */
  static Set<String> moveNames(Document d, String id) {
    var names = new LinkedHashSet<String>();
    for (Change c : list(d))
      if (c.revision.move != null && (id == null || id.equals(c.revision.id))) names.add(c.revision.move);
    return names;
  }

  /**
   * Resolves every revision that is an end of one of the named moves: accepting or rejecting
   * either end of a move resolves both (Wordcraft cmd/moves.rs resolve).
   */
  static Document resolveMoves(Document d, Set<String> names, boolean accept) {
    if (names.isEmpty()) return d;
    var ids = new LinkedHashSet<String>();
    for (Change c : list(d)) if (c.revision.move != null && names.contains(c.revision.move)) ids.add(c.revision.id);
    for (String id : ids) d = resolve(d, id, accept);
    return d;
  }

  /** Names the revisions with the given ids as ends of the move {@code name}, in the body and cells. */
  static Document nameMove(Document d, Set<String> ids, String name) {
    var blocks = new Sequence<Block>();
    for (Block block : d.blocks) {
      if (block instanceof Paragraph p) block = nameMove(p, ids, name);
      else if (block instanceof Table t) {
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            if (!t.covered(r, c)) {
              var cell = new Sequence<Paragraph>();
              for (Paragraph p : t.cell(r, c)) cell = cell.append(nameMove(p, ids, name));
              t = t.setCell(r, c, cell);
            }
        block = t;
      }
      blocks = blocks.append(block);
    }
    return d.blocks(blocks, d.nextId);
  }

  private static Paragraph nameMove(Paragraph p, Set<String> ids, String name) {
    return p.text(p.text.mapStyles(s -> s.revision == null ? s : s.revision(named(s.revision, ids, name))))
        .breakRevision(named(p.breakRevision, ids, name));
  }

  private static Revision named(Revision r, Set<String> ids, String name) {
    if (r == null) return null;
    var prior = named(r.previous, ids, name);
    var result = prior == r.previous ? r : r.previous(prior);
    return ids.contains(r.id) && !"format".equals(r.kind) ? result.move(name) : result;
  }

  public static Document resolve(Document d, String id, boolean accept) {
    var sections = new LinkedHashMap<String, Section>(d.sections);
    var blocks = resolveBlocks(d.blocks, id, accept, sections);
    var next = new LinkedHashMap<String, Section>();
    for (Map.Entry<String, Section> entry : sections.entrySet())
      next.put(entry.getKey(), resolveSection(entry.getValue(), id, accept));
    var result = d.blocks(blocks, d.nextId).sections(next, resolveSection(d.lastSection, id, accept));
    return Notes.map(result, note -> {
      // Note stories hold tables and pictures too (resolved like the body's blocks).
      var resolved = resolveBlocks(note.blocks, id, accept, new LinkedHashMap<>());
      return sameBlocks(resolved, note.blocks) ? note : note.blocks(resolved);
    });
  }

  private static boolean sameBlocks(Sequence<Block> a, Sequence<Block> b) {
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
    return true;
  }

  private static Section resolveSection(Section section, String id, boolean accept) {
    var furniture = section.furniture;
    for (String key : PageFurniture.KEYS)
      if (furniture.stories.containsKey(key))
        furniture =
            furniture.blocks(
                key, resolveBlocks(furniture.blocks(key), id, accept, new LinkedHashMap<>()));
    return section.furniture(furniture);
  }

  private static Sequence<Paragraph> resolveParagraphs(
      Sequence<Paragraph> source, String id, boolean accept) {
    var blocks = new Sequence<Block>();
    for (Paragraph p : source) blocks = blocks.append(p);
    var result = new Sequence<Paragraph>();
    for (Block b : resolveBlocks(blocks, id, accept, new LinkedHashMap<>()))
      result = result.append((Paragraph) b);
    return result;
  }

  private static Sequence<Block> resolveBlocks(
      Sequence<Block> source, String id, boolean accept, Map<String, Section> sections) {
    var blocks = new Sequence<Block>();
    for (Block block : source) {
      if (block instanceof Paragraph p) {
        block = p.text(p.text.review(id, accept)).reviewFormat(id, accept);
      } else if (block instanceof Table t) {
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            if (!t.covered(r, c)) t = t.setCell(r, c, resolveParagraphs(t.cell(r, c), id, accept));
        block = t;
      }
      blocks = blocks.append(block);
    }
    for (int i = 0; i < blocks.size(); i++)
      if (blocks.get(i) instanceof Paragraph) {
        var p = (Paragraph) blocks.get(i);
        var r = p.breakRevision;
        if (r == null) continue;
        var resolution = r.resolve(id, accept);
        if (resolution.removed() && i + 1 < blocks.size() && blocks.get(i + 1) instanceof Paragraph) {
          var following = (Paragraph) blocks.get(i + 1);
          var boundary = sections.remove(following.id);
          sections.remove(p.id);
          if (boundary != null) sections.put(p.id, boundary);
          blocks =
              blocks
                  .set(
                      i,
                      p.text(p.text.concat(following.text)).breakRevision(following.breakRevision))
                  .remove(i + 1);
          i--;
        } else blocks = blocks.set(i, p.breakRevision(resolution.remaining()));
      }
    return blocks;
  }
}
