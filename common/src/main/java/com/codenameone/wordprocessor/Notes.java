package com.codenameone.wordprocessor;

import java.util.*;

/** Traversal and numbering of inline-owned note stories. */
public final class Notes {
  private Notes() {}

  public interface Mapper { Note map(Note note); }

  public static final class Reference {
    public final Note note;
    /**
     * The body block (paragraph, or outermost table) and that table's cell; {@link #path} is the
     * full cell path for tables nested in cells, and {@code paragraph} the block index in the
     * story at that path.
     */
    public final int block, row, column, paragraph, offset;
    public final int[] path;

    Reference(Note note, int[] path, int paragraph, int offset) {
      this.note = note;
      this.path = path;
      this.block = path.length > 0 ? path[0] : paragraph;
      this.row = path.length > 0 ? path[1] : -1;
      this.column = path.length > 0 ? path[2] : -1;
      this.paragraph = path.length > 0 ? paragraph : 0;
      this.offset = offset;
    }

    /** The paragraph holding the reference. */
    public Paragraph paragraph(Document document) {
      return (Paragraph)
          MixedRange.story(document, path).get(path.length > 0 ? paragraph : block);
    }
  }

  /** Note references in document order, in body paragraphs and table cells at any depth. */
  public static List<Reference> references(Document document) {
    var result = new ArrayList<Reference>();
    for (MixedRange.Covered c : MixedRange.paragraphs(document)) {
      final int[] path = c.path;
      final int p = c.block;
      c.paragraph.text.mapNotes(
          (offset, note) -> {
            result.add(new Reference(note, path, p, offset));
            return note;
          });
    }
    return result;
  }

  public static List<Note> all(Document document) {
    final var result = new ArrayList<Note>();
    map(document, note -> { result.add(note); return note; });
    return result;
  }

  public static Document map(Document document, Mapper mapper) {
    var blocks = map(document.blocks, mapper);
    return blocks == document.blocks ? document : document.blocks(blocks, document.nextId);
  }

  /** Maps notes in paragraphs and in table cells, nested tables included. */
  private static Sequence<Block> map(Sequence<Block> blocks, Mapper mapper) {
    var out = blocks;
    for (int i = 0; i < blocks.size(); i++) {
      Block original = blocks.get(i), value = original;
      if (original instanceof Paragraph paragraph) value = paragraph(paragraph, mapper);
      else if (original instanceof Table table) {
        for (int row = 0; row < table.rows(); row++)
          for (int column = 0; column < table.columns(); column++) {
            if (table.covered(row, column)) continue;
            var cell = table.blocks(row, column);
            var updated = map(cell, mapper);
            if (updated != cell) table = table.setBlocks(row, column, updated);
          }
        value = table;
      }
      if (value != original) out = out.set(i, value);
    }
    return out;
  }

  private static Paragraph paragraph(Paragraph paragraph, Mapper mapper) {
    var value = paragraph.text.mapNotes((offset, note) -> mapper.map(note));
    return value == paragraph.text ? paragraph : paragraph.text(value);
  }

  /** Renumber in document order and detach pasted copies from their original note identities. */
  public static Document normalize(Document document) {
    final var ids = new HashSet<String>();
    final var reserved = new HashSet<String>();
    for (Note note : all(document)) reserved.add(note.id);
    final long[] next = {document.nextId};
    Document result = map(document, note -> {
      if (!ids.add(note.id)) {
        var id = "n" + next[0]++;
        while (reserved.contains(id) || !ids.add(id)) id = "n" + next[0]++;
        reserved.add(id);
        var story = new Sequence<Paragraph>();
        for (Paragraph p : note.paragraphs) story = story.append(p.copy("p" + next[0]++, p.text));
        note = new Note(id, note.kind, note.mark, note.label, story);
      }
      return note;
    });
    return numbered(next[0] == result.nextId ? result : result.blocks(result.blocks, next[0]), null);
  }
  /** Page-dependent numbering uses physical anchor pages supplied by pagination. */
  public static Document numbered(Document document, Map<String, Integer> pages) {
    var labels = new HashMap<String, String>();
    List<Section.Range> sections = document.sectionRanges();
    int section = 0;
    int[] count = {0, 0}, previousSection = {-1, -1}, previousPage = {-1, -1};
    for (Reference reference : references(document)) {
      while (section + 1 < sections.size() && reference.block > sections.get(section).last) section++;
      var note = reference.note;
      if (!note.mark.isEmpty()) { labels.put(note.id, note.mark); continue; }
      int kind = "footnote".equals(note.kind) ? 0 : 1;
      var options = sections.get(section).resolved.notes.get(note.kind);
      Integer page = pages == null ? null : pages.get(note.id);
      boolean restart = previousSection[kind] < 0
          || "eachSect".equals(options.numRestart) && previousSection[kind] != section
          || "eachPage".equals(options.numRestart) && page != null && page != previousPage[kind];
      count[kind] = restart ? options.numStart : count[kind] + 1;
      previousSection[kind] = section;
      if (page != null) previousPage[kind] = page;
      labels.put(note.id, NoteOptions.label(count[kind], options.numFmt));
    }
    return map(document, note -> note.label(labels.get(note.id)));
  }

}
