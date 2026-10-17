package com.codenameone.wordprocessor;

import java.util.*;

/** Traversal and numbering of inline-owned note stories. */
public final class Notes {
  private Notes() {}

  public interface Mapper { Note map(Note note); }

  public static final class Reference {
    public final Note note;
    public final int block, row, column, paragraph, offset;
    Reference(Note note, int block, int row, int column, int paragraph, int offset) {
      this.note = note; this.block = block; this.row = row; this.column = column;
      this.paragraph = paragraph; this.offset = offset;
    }
    int compare(int b, int r, int c, int p, int o) {
      int[] left = {block, row, column, paragraph, offset}, right = {b, r, c, p, o};
      for (int i = 0; i < left.length; i++) if (left[i] != right[i]) return left[i] < right[i] ? -1 : 1;
      return 0;
    }
  }

  public static List<Reference> references(Document document) {
    List<Reference> result = new ArrayList<Reference>();
    for (int b = 0; b < document.blocks.size(); b++) {
      Block block = document.blocks.get(b);
      if (block instanceof Paragraph) references(result, (Paragraph) block, b, -1, -1, 0);
      else if (block instanceof Table) {
        Table table = (Table) block;
        for (int r = 0; r < table.rows(); r++) for (int c = 0; c < table.columns(); c++) {
          if (table.covered(r, c)) continue;
          Sequence<Paragraph> cell = table.cell(r, c);
          for (int p = 0; p < cell.size(); p++) references(result, cell.get(p), b, r, c, p);
        }
      }
    }
    return result;
  }

  private static void references(List<Reference> out, Paragraph value, int b, int r, int c, int p) {
    value.text.mapNotes((offset, note) -> { out.add(new Reference(note, b, r, c, p, offset)); return note; });
  }

  public static List<Note> all(Document document) {
    final List<Note> result = new ArrayList<Note>();
    map(document, note -> { result.add(note); return note; });
    return result;
  }

  public static Document map(Document document, Mapper mapper) {
    Sequence<Block> blocks = document.blocks;
    for (int i = 0; i < blocks.size(); i++) {
      Block original = blocks.get(i), value = original;
      if (original instanceof Paragraph) value = paragraph((Paragraph) original, mapper);
      else if (original instanceof Table) {
        Table table = (Table) original;
        for (int row = 0; row < table.rows(); row++)
          for (int column = 0; column < table.columns(); column++) {
            if (table.covered(row, column)) continue;
            Sequence<Paragraph> cell = table.cell(row, column), updated = cell;
            for (int p = 0; p < cell.size(); p++) {
              Paragraph old = cell.get(p), replacement = paragraph(old, mapper);
              if (old != replacement) updated = updated.set(p, replacement);
            }
            if (updated != cell) table = table.setCell(row, column, updated);
          }
        value = table;
      }
      if (value != original) blocks = blocks.set(i, value);
    }
    return blocks == document.blocks ? document : document.blocks(blocks, document.nextId);
  }

  private static Paragraph paragraph(Paragraph paragraph, Mapper mapper) {
    RichText value = paragraph.text.mapNotes((offset, note) -> mapper.map(note));
    return value == paragraph.text ? paragraph : paragraph.text(value);
  }

  /** Renumber in document order and detach pasted copies from their original note identities. */
  public static Document normalize(Document document) {
    final Set<String> ids = new HashSet<String>();
    final Set<String> reserved = new HashSet<String>();
    for (Note note : all(document)) reserved.add(note.id);
    final long[] next = {document.nextId};
    Document result = map(document, note -> {
      if (!ids.add(note.id)) {
        String id = "n" + next[0]++;
        while (reserved.contains(id) || !ids.add(id)) id = "n" + next[0]++;
        reserved.add(id);
        Sequence<Paragraph> story = new Sequence<Paragraph>();
        for (Paragraph p : note.paragraphs) story = story.append(p.copy("p" + next[0]++, p.text));
        note = new Note(id, note.kind, note.mark, note.label, story);
      }
      return note;
    });
    return numbered(next[0] == result.nextId ? result : result.blocks(result.blocks, next[0]), null);
  }
  /** Page-dependent numbering uses physical anchor pages supplied by pagination. */
  public static Document numbered(Document document, Map<String, Integer> pages) {
    Map<String, String> labels = new HashMap<String, String>();
    List<Section.Range> sections = document.sectionRanges();
    int section = 0;
    int[] count = {0, 0}, previousSection = {-1, -1}, previousPage = {-1, -1};
    for (Reference reference : references(document)) {
      while (section + 1 < sections.size() && reference.block > sections.get(section).last) section++;
      Note note = reference.note;
      if (!note.mark.isEmpty()) { labels.put(note.id, note.mark); continue; }
      int kind = "footnote".equals(note.kind) ? 0 : 1;
      NoteOptions.Properties options = sections.get(section).resolved.notes.get(note.kind);
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
