package com.codenameone.wordprocessor;

import java.util.*;

/** Section properties belong to the section ending at a paragraph, as in DOCX. */
public final class Section {
  public final PageSpec page;
  public final NoteOptions notes;
  public final PageFurniture furniture;
  public final String start;
  public final Columns columns;

  /** Zero continues numbering from the previous section; positive values restart it. */
  public final int pageStart;

  public Section(PageSpec page, PageFurniture furniture, String start, int pageStart) {
    this(page, furniture, start, pageStart, NoteOptions.EMPTY);
  }

  public Section(PageSpec page, PageFurniture furniture, String start, int pageStart, NoteOptions notes) {
    this(page, furniture, start, pageStart, notes, Columns.ONE);
  }

  public Section(
      PageSpec page,
      PageFurniture furniture,
      String start,
      int pageStart,
      NoteOptions notes,
      Columns columns) {
    if (notes == null || columns == null) throw new NullPointerException();
    this.columns = columns;
    this.notes = notes;
    if (page == null
        || furniture == null
        || !Arrays.asList("nextPage", "continuous", "oddPage", "evenPage").contains(start)
        || pageStart < 0
        || pageStart > 1000000) throw new IllegalArgumentException("Invalid section properties");
    this.page = page;
    this.furniture = furniture;
    this.start = start;
    this.pageStart = pageStart;
  }

  public Section page(PageSpec value) {
    return new Section(value, furniture, start, pageStart, notes, columns);
  }

  public Section furniture(PageFurniture value) {
    return new Section(page, value, start, pageStart, notes, columns);
  }

  public Section start(String value) {
    return new Section(page, furniture, value, pageStart, notes, columns);
  }

  public Section numbering(int value) {
    return new Section(page, furniture, start, value, notes, columns);
  }

  public Section columns(Columns value) {
    return new Section(page, furniture, start, pageStart, notes, value);
  }

  public Section notes(NoteOptions value) {
    return new Section(page, furniture, start, pageStart, value, columns);
  }

  /** Absent story entries link to the previous section; an explicit empty entry unlinks. */
  public Section resolved(PageFurniture previous) {
    Map<String, Sequence<Paragraph>> stories =
        new LinkedHashMap<String, Sequence<Paragraph>>(previous.stories);
    stories.putAll(furniture.stories);
    return furniture(
        new PageFurniture(
            stories,
            furniture.differentFirst,
            furniture.differentEven,
            furniture.headerDistance,
            furniture.footerDistance));
  }

  public Section linked() {
    return furniture(
        new PageFurniture(
            Collections.<String, Sequence<Paragraph>>emptyMap(),
            furniture.differentFirst,
            furniture.differentEven,
            furniture.headerDistance,
            furniture.footerDistance));
  }

  public static final class Range {
    public final int first, last;
    public final Section raw, resolved;

    Range(int first, int last, Section raw, PageFurniture previous, NoteOptions defaults) {
      this.first = first;
      this.last = last;
      this.raw = raw;
      resolved = raw.resolved(previous).notes(raw.notes.resolve(defaults));
    }
  }
}
