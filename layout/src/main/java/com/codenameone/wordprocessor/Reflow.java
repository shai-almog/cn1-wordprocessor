package com.codenameone.wordprocessor;

import java.util.*;

/** A transient narrow-screen layout view. Source text, bookmarks and print geometry stay intact. */
public final class Reflow {
  private Reflow() {}

  public static Document document(Document original, float width) {
    if (Float.isNaN(width) || Float.isInfinite(width) || width < 120)
      throw new IllegalArgumentException("Invalid reflow width");
    Sequence<Block> blocks = original.blocks;
    for (Section.Range section : original.sectionRanges()) {
      float ratio = Math.min(1, (width - 24) / section.raw.page.contentWidth());
      for (int i = section.first; i <= section.last; i++) {
        Block block = blocks.get(i);
        if (block instanceof Paragraph) {
          Paragraph adapted = paragraph((Paragraph) block, ratio);
          if (adapted != block) blocks = blocks.set(i, adapted);
        } else if (block instanceof Table) {
          Table table = (Table) block;
          for (int row = 0; row < table.rows(); row++)
            for (int column = 0; column < table.columns(); column++) {
              Sequence<Paragraph> cell = table.cell(row, column);
              Sequence<Paragraph> adapted = cell;
              for (int p = 0; p < cell.size(); p++) {
                Paragraph value = paragraph(cell.get(p), ratio);
                if (value != cell.get(p)) adapted = adapted.set(p, value);
              }
              if (adapted != cell) table = table.setCell(row, column, adapted);
            }
          TableFormat.Sizing size = table.format.sizing;
          if (ratio < 1 && ("points".equals(size.unit) || size.indent != 0))
            table =
                table.format(
                    table.format.sizing(
                        new TableFormat.Sizing(
                            size.unit,
                            "points".equals(size.unit) ? Math.max(.01f, size.width * ratio) : size.width,
                            size.align,
                            size.indent * ratio,
                            size.fixed)));
          if (table != block) blocks = blocks.set(i, table);
        }
      }
    }
    Map<String, Section> sections = new LinkedHashMap<String, Section>();
    for (Map.Entry<String, Section> entry : original.sections.entrySet())
      sections.put(entry.getKey(), entry.getValue().page(page(entry.getValue().page, width)));
    return original
        .blocks(blocks, original.nextId)
        .sections(sections, original.lastSection.page(page(original.page, width)));
  }

  private static Paragraph paragraph(Paragraph p, float ratio) {
    if (ratio == 1) return p;
    RichText text =
        p.text.mapNotes(
            (offset, note) -> {
              Sequence<Paragraph> story = note.paragraphs;
              for (int i = 0; i < story.size(); i++) {
                Paragraph original = story.get(i), value = paragraph(original, ratio);
                if (value != original) story = story.set(i, value);
              }
              return note.paragraphs(story);
            });
    if (text != p.text) p = p.text(text);
    if (p.indent != 0) p = p.with("indent", "" + p.indent * ratio);
    if (p.format.rightIndent != 0) p = p.with("rightIndent", "" + p.format.rightIndent * ratio);
    if (p.format.firstIndent != 0) p = p.with("firstIndent", "" + p.format.firstIndent * ratio);
    if (!p.format.tabs.isEmpty()) {
      List<TabStop> tabs = new ArrayList<TabStop>();
      for (TabStop tab : p.format.tabs)
        tabs.add(new TabStop(Math.max(.01f, tab.position * ratio), tab.alignment, tab.leader));
      p = p.with("tabs", TabStop.encode(tabs));
    }
    return p;
  }

  private static PageSpec page(PageSpec p, float width) {
    return new PageSpec(width, p.height, 12, 12, 12, 12);
  }
}
