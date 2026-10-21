package com.codenameone.wordprocessor;

import java.util.*;

/** Section properties belong to the section ending at a paragraph, as in DOCX. */
public final class Section {
  public final PageSpec page;
  public final NoteOptions notes;
  public final PageFurniture furniture;
  public final String start;
  public final Columns columns;
  public final PageDecor decor;

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
    this(page, furniture, start, pageStart, notes, columns, PageDecor.NONE);
  }

  public Section(
      PageSpec page,
      PageFurniture furniture,
      String start,
      int pageStart,
      NoteOptions notes,
      Columns columns,
      PageDecor decor) {
    if (notes == null || columns == null || decor == null) throw new NullPointerException();
    this.decor = decor;
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
    return new Section(value, furniture, start, pageStart, notes, columns, decor);
  }

  public Section furniture(PageFurniture value) {
    return new Section(page, value, start, pageStart, notes, columns, decor);
  }

  public Section start(String value) {
    return new Section(page, furniture, value, pageStart, notes, columns, decor);
  }

  public Section numbering(int value) {
    return new Section(page, furniture, start, value, notes, columns, decor);
  }

  public Section columns(Columns value) {
    return new Section(page, furniture, start, pageStart, notes, value, decor);
  }

  public Section decor(PageDecor value) {
    return new Section(page, furniture, start, pageStart, notes, columns, value);
  }

  public Section notes(NoteOptions value) {
    return new Section(page, furniture, start, pageStart, value, columns, decor);
  }

  /** Absent story entries link to the previous section; an explicit empty entry unlinks. */
  public Section resolved(PageFurniture previous) {
    var stories =
        new LinkedHashMap<String, Sequence<Block>>(previous.stories);
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
            Collections.<String, Sequence<Block>>emptyMap(),
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
