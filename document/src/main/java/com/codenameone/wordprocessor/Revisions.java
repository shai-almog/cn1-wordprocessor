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
    final List<Change> changes = new ArrayList<Change>();
    for (int b = 0; b < d.blocks.size(); b++) {
      Block block = d.blocks.get(b);
      if (block instanceof Paragraph) collect(changes, (Paragraph) block, "", b, -1, -1);
      else if (block instanceof Table) {
        Table table = (Table) block;
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++)
            if (!table.covered(r, c))
              for (Paragraph p : table.cell(r, c)) collect(changes, p, "", b, r, c);
      }
    }
    for (Section.Range section : d.sectionRanges())
      for (String key : PageFurniture.KEYS)
        if (section.raw.furniture.stories.containsKey(key))
          for (Paragraph p : section.raw.furniture.get(key))
            collect(changes, p, key, section.first, -1, -1);
    for (Note note : Notes.all(d))
      for (Paragraph p : note.paragraphs) collect(changes, p, "note:" + note.id, -1, -1, -1);
    return Collections.unmodifiableList(changes);
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
          for (Revision revision = style.revision;
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
    for (Revision revision = p.breakRevision; revision != null; revision = revision.previous)
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
      String id = change.revision.id;
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
    Map<String, Section> sections = new LinkedHashMap<String, Section>(d.sections);
    for (int b = selection.endBlock(); b >= selection.startBlock(); b--) {
      Block block = blocks.get(b);
      if (block instanceof Table) {
        Table table = (Table) block;
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++)
            if (!table.covered(r, c))
              table = table.setCell(r, c, resolveParagraphs(table.cell(r, c), null, accept));
        blocks = blocks.set(b, table);
        continue;
      }
      if (!(block instanceof Paragraph)) continue;
      Paragraph p = (Paragraph) block;
      int from = b == selection.startBlock() ? selection.start() : 0;
      int to = b == selection.endBlock() ? selection.end() : p.text.length();
      p = p.text(p.text.replace(from, to, p.text.slice(from, to).review(null, accept)));
      if (paragraphProperties && (b != selection.endBlock() || to > from || selection.collapsed()))
        p = p.reviewFormat(null, accept);
      if ((b < selection.endBlock() || caretMark) && p.breakRevision != null) {
        Revision.Resolution resolution = p.breakRevision.resolve(null, accept);
        if (resolution.removed && b + 1 < blocks.size() && blocks.get(b + 1) instanceof Paragraph) {
          Paragraph following = (Paragraph) blocks.get(b + 1);
          Section boundary = sections.remove(following.id);
          sections.remove(p.id);
          if (boundary != null) sections.put(p.id, boundary);
          p = p.text(p.text.concat(following.text)).breakRevision(following.breakRevision);
          blocks = blocks.remove(b + 1);
        } else p = p.breakRevision(resolution.remaining);
      }
      blocks = blocks.set(b, p);
    }
    return d.blocks(blocks, d.nextId).sections(sections, d.lastSection);
  }

  public static Document resolve(Document d, String id, boolean accept) {
    Map<String, Section> sections = new LinkedHashMap<String, Section>(d.sections);
    Sequence<Block> blocks = resolveBlocks(d.blocks, id, accept, sections);
    Map<String, Section> next = new LinkedHashMap<String, Section>();
    for (Map.Entry<String, Section> entry : sections.entrySet())
      next.put(entry.getKey(), resolveSection(entry.getValue(), id, accept));
    Document result = d.blocks(blocks, d.nextId).sections(next, resolveSection(d.lastSection, id, accept));
    return Notes.map(result, note -> note.paragraphs(resolveParagraphs(note.paragraphs, id, accept)));
  }

  private static Section resolveSection(Section section, String id, boolean accept) {
    PageFurniture furniture = section.furniture;
    for (String key : PageFurniture.KEYS)
      if (furniture.stories.containsKey(key))
        furniture = furniture.story(key, resolveParagraphs(furniture.get(key), id, accept));
    return section.furniture(furniture);
  }

  private static Sequence<Paragraph> resolveParagraphs(
      Sequence<Paragraph> source, String id, boolean accept) {
    Sequence<Block> blocks = new Sequence<Block>();
    for (Paragraph p : source) blocks = blocks.append(p);
    Sequence<Paragraph> result = new Sequence<Paragraph>();
    for (Block b : resolveBlocks(blocks, id, accept, new LinkedHashMap<String, Section>()))
      result = result.append((Paragraph) b);
    return result;
  }

  private static Sequence<Block> resolveBlocks(
      Sequence<Block> source, String id, boolean accept, Map<String, Section> sections) {
    Sequence<Block> blocks = new Sequence<Block>();
    for (Block block : source) {
      if (block instanceof Paragraph) {
        Paragraph p = (Paragraph) block;
        block = p.text(p.text.review(id, accept)).reviewFormat(id, accept);
      } else if (block instanceof Table) {
        Table t = (Table) block;
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            if (!t.covered(r, c)) t = t.setCell(r, c, resolveParagraphs(t.cell(r, c), id, accept));
        block = t;
      }
      blocks = blocks.append(block);
    }
    for (int i = 0; i < blocks.size(); i++)
      if (blocks.get(i) instanceof Paragraph) {
        Paragraph p = (Paragraph) blocks.get(i);
        Revision r = p.breakRevision;
        if (r == null) continue;
        Revision.Resolution resolution = r.resolve(id, accept);
        if (resolution.removed && i + 1 < blocks.size() && blocks.get(i + 1) instanceof Paragraph) {
          Paragraph following = (Paragraph) blocks.get(i + 1);
          Section boundary = sections.remove(following.id);
          sections.remove(p.id);
          if (boundary != null) sections.put(p.id, boundary);
          blocks =
              blocks
                  .set(
                      i,
                      p.text(p.text.concat(following.text)).breakRevision(following.breakRevision))
                  .remove(i + 1);
          i--;
        } else blocks = blocks.set(i, p.breakRevision(resolution.remaining));
      }
    return blocks;
  }
}
