package com.codenameone.wordprocessor;

import java.util.*;

/** Immutable document root. IDs are document-local and stable through undo and preview. */
public final class Document {
  public final Sequence<Block> blocks;
  public final PageSpec page;
  public final PageFurniture furniture;
  public final Section lastSection;
  public final NoteOptions notes;
  public final Map<String, Section> sections;
  public final String title;
  public final boolean trackChanges;
  public final long nextId;
  public final List<ReviewComment> comments;
  public final Map<String, ParagraphStyle> styles;
  public final Map<String, Bookmark> bookmarks;
  public final Bibliography bibliography;
  public final DocumentProperties properties;

  public Document(Sequence<Block> blocks, PageSpec page, String title, long nextId) {
    this(
        blocks,
        page,
        title,
        nextId,
        Collections.<ReviewComment>emptyList(),
        ParagraphStyle.defaults(),
        PageFurniture.EMPTY,
        "nextPage",
        0,
        NoteOptions.EMPTY,
        Columns.ONE,
        PageDecor.NONE,
        NoteOptions.DEFAULT,
        Collections.<String, Section>emptyMap(),
        false,
        Collections.<String, Bookmark>emptyMap(), Bibliography.EMPTY, DocumentProperties.EMPTY);
  }

  public Document(
      Sequence<Block> blocks,
      PageSpec page,
      String title,
      long nextId,
      List<ReviewComment> comments) {
    this(
        blocks,
        page,
        title,
        nextId,
        Collections.unmodifiableList(new ArrayList<ReviewComment>(comments)),
        ParagraphStyle.defaults(),
        PageFurniture.EMPTY,
        "nextPage",
        0,
        NoteOptions.EMPTY,
        Columns.ONE,
        PageDecor.NONE,
        NoteOptions.DEFAULT,
        Collections.<String, Section>emptyMap(),
        false,
        Collections.<String, Bookmark>emptyMap(), Bibliography.EMPTY, DocumentProperties.EMPTY);
  }

  private Document(
      Sequence<Block> blocks,
      PageSpec page,
      String title,
      long nextId,
      List<ReviewComment> comments,
      Map<String, ParagraphStyle> styles,
      PageFurniture furniture,
      String sectionStart,
      int pageStart,
      NoteOptions sectionNotes,
      Columns sectionColumns,
      PageDecor sectionDecor,
      NoteOptions notes,
      Map<String, Section> sections,
      boolean trackChanges,
      Map<String, Bookmark> bookmarks, Bibliography bibliography, DocumentProperties properties) {
    if (bibliography == null) throw new NullPointerException("bibliography");
    if (properties == null) throw new NullPointerException("properties");
    this.properties = properties;
    this.bibliography = bibliography;
    this.notes = notes.resolve(NoteOptions.DEFAULT);
    this.bookmarks = bookmarks;
    this.trackChanges = trackChanges;
    this.furniture = furniture;
    this.sections = sections;
    lastSection = new Section(page, furniture, sectionStart, pageStart, sectionNotes, sectionColumns, sectionDecor);
    this.comments = comments;
    this.styles = styles;
    if (blocks == null || page == null || title == null || blocks.size() == 0 || nextId < 1)
      throw new IllegalArgumentException("Invalid document");
    this.blocks = blocks;
    this.page = page;
    this.title = title;
    this.nextId = nextId;
  }

  public Document trackChanges(boolean enabled) {
    return new Document(
        blocks,
        page,
        title,
        nextId,
        comments,
        styles,
        furniture,
        lastSection.start,
        lastSection.pageStart,
        lastSection.notes,
        lastSection.columns,
        lastSection.decor,
        notes,
        sections,
        enabled,
        bookmarks, bibliography, properties);
  }

  public static Document empty() {
    return new Document(
        new Sequence<Block>().append(new Paragraph("p1", new RichText())),
        PageSpec.A4,
        "Untitled",
        2);
  }

  public Document blocks(Sequence<Block> b, long next) {
    Map<String, Section> kept = sections;
    if (!sections.isEmpty()) {
      var present = new HashSet<String>();
      for (Block block : b) if (block instanceof Paragraph) present.add(block.id());
      if (!present.containsAll(sections.keySet())) {
        kept = new LinkedHashMap<>(sections);
        kept.keySet().retainAll(present);
        kept = Collections.unmodifiableMap(kept);
      }
    }
    return new Document(
        b,
        page,
        title,
        next,
        comments,
        styles,
        furniture,
        lastSection.start,
        lastSection.pageStart,
        lastSection.notes,
        lastSection.columns,
        lastSection.decor,
        notes,
        kept,
        trackChanges,
        bookmarks, bibliography, properties);
  }

  public Document page(PageSpec p) {
    return new Document(
        blocks,
        p,
        title,
        nextId,
        comments,
        styles,
        furniture,
        lastSection.start,
        lastSection.pageStart,
        lastSection.notes,
        lastSection.columns,
        lastSection.decor,
        notes,
        sections,
        trackChanges,
        bookmarks, bibliography, properties);
  }

  public Document title(String t) {
    return new Document(
        blocks,
        page,
        t,
        nextId,
        comments,
        styles,
        furniture,
        lastSection.start,
        lastSection.pageStart,
        lastSection.notes,
        lastSection.columns,
        lastSection.decor,
        notes,
        sections,
        trackChanges,
        bookmarks, bibliography, properties);
  }

  public Document comments(List<ReviewComment> value) {
    return new Document(
        blocks,
        page,
        title,
        nextId,
        Collections.unmodifiableList(new ArrayList<ReviewComment>(value)),
        styles,
        furniture,
        lastSection.start,
        lastSection.pageStart,
        lastSection.notes,
        lastSection.columns,
        lastSection.decor,
        notes,
        sections,
        trackChanges,
        bookmarks, bibliography, properties);
  }

  public Document bookmarks(Map<String, Bookmark> value) {
    if (value == null || value.size() > 10000)
      throw new IllegalArgumentException("Too many bookmarks");
    for (Map.Entry<String, Bookmark> entry : value.entrySet()) {
      if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().name))
        throw new IllegalArgumentException("Invalid bookmark key");
      entry.getValue().validate(this);
    }
    return new Document(
        blocks,
        page,
        title,
        nextId,
        comments,
        styles,
        furniture,
        lastSection.start,
        lastSection.pageStart,
        lastSection.notes,
        lastSection.columns,
        lastSection.decor,
        notes,
        sections,
        trackChanges,
        Collections.unmodifiableMap(new LinkedHashMap<String, Bookmark>(value)), bibliography, properties);
  }

  public Document styles(Map<String, ParagraphStyle> value) {
    if (value == null || value.size() > 1024 || !value.containsKey("Normal"))
      throw new IllegalArgumentException("Invalid document styles");
    for (Map.Entry<String, ParagraphStyle> e : value.entrySet())
      if (e.getValue() == null || !e.getKey().equals(e.getValue().id))
        throw new IllegalArgumentException("Invalid style key");
    return new Document(
        blocks,
        page,
        title,
        nextId,
        comments,
        Collections.unmodifiableMap(new LinkedHashMap<String, ParagraphStyle>(value)),
        furniture,
        lastSection.start,
        lastSection.pageStart,
        lastSection.notes,
        lastSection.columns,
        lastSection.decor,
        notes,
        sections,
        trackChanges,
        bookmarks, bibliography, properties);
  }

  public Document furniture(PageFurniture value) {
    if (value == null) throw new NullPointerException();
    return value == furniture
        ? this
        : new Document(
            blocks,
            page,
            title,
            nextId,
            comments,
            styles,
            value,
            lastSection.start,
            lastSection.pageStart,
        lastSection.notes,
        lastSection.columns,
        lastSection.decor,
        notes,
            sections,
            trackChanges,
            bookmarks, bibliography, properties);
  }

  public Document sections(Map<String, Section> value, Section last) {
    if (value == null || last == null) throw new NullPointerException();
    var ids = new HashSet<String>();
    for (Block block : blocks) if (block instanceof Paragraph) ids.add(block.id());
    if (!ids.containsAll(value.keySet()) || value.containsValue(null))
      throw new IllegalArgumentException("Section boundary must end a body paragraph");
    return new Document(
        blocks,
        last.page,
        title,
        nextId,
        comments,
        styles,
        last.furniture,
        last.start,
        last.pageStart,
        last.notes,
        last.columns,
        last.decor,
        notes,
        Collections.unmodifiableMap(new LinkedHashMap<String, Section>(value)),
        trackChanges,
        bookmarks, bibliography, properties);
  }

  public List<Section.Range> sectionRanges() {
    var result = new ArrayList<Section.Range>();
    int first = 0;
    var previous = PageFurniture.EMPTY;
    for (int i = 0; i < blocks.size(); i++) {
      var section = sections.get(blocks.get(i).id());
      if (section != null) {
        var range = new Section.Range(first, i, section, previous, notes);
        result.add(range);
        previous = range.resolved.furniture;
        first = i + 1;
      }
    }
    result.add(new Section.Range(first, blocks.size() - 1, lastSection, previous, notes));
    return Collections.unmodifiableList(result);
  }

  public Section.Range sectionAt(int block) {
    if (block < 0 || block >= blocks.size()) throw new IndexOutOfBoundsException();
    for (Section.Range range : sectionRanges()) if (block <= range.last) return range;
    throw new IllegalStateException("Missing section");
  }

  public Document section(int block, Section value) {
    var range = sectionAt(block);
    if (range.last == blocks.size() - 1 && !sections.containsKey(blocks.get(range.last).id()))
      return sections(sections, value);
    var changed = new LinkedHashMap<String, Section>(sections);
    changed.put(blocks.get(range.last).id(), value);
    return sections(changed, lastSection);
  }

  public Document notes(NoteOptions value) {
    return new Document(blocks, page, title, nextId, comments, styles, furniture, lastSection.start,
        lastSection.pageStart, lastSection.notes, lastSection.columns, lastSection.decor, value, sections, trackChanges, bookmarks, bibliography, properties);
  }

  public Document bibliography(Bibliography value) {
    return new Document(blocks, page, title, nextId, comments, styles, furniture, lastSection.start,
        lastSection.pageStart, lastSection.notes, lastSection.columns, lastSection.decor, notes, sections, trackChanges, bookmarks, value, properties);
  }

  public Document properties(DocumentProperties value) {
    return new Document(blocks, page, title, nextId, comments, styles, furniture, lastSection.start,
        lastSection.pageStart, lastSection.notes, lastSection.columns, lastSection.decor, notes, sections, trackChanges, bookmarks, bibliography, value);
  }

  public String plainText() {
    var s = new StringBuilder();
    boolean first = true;
    for (Block b : blocks) {
      if (!first) s.append('\n');
      first = false;
      if (b instanceof Paragraph paragraph) s.append(paragraph.text.plainText());
      else if (b instanceof Picture picture) s.append("[Picture: ").append(picture.alt).append("]");
      else if (b instanceof Table t) {
        for (int r = 0; r < t.rows(); r++) {
          if (r > 0) s.append('\n');
          for (int c = 0; c < t.columns(); c++) {
            if (c > 0) s.append('\t');
            int index = 0;
            for (Paragraph p : t.cell(r, c)) {
              if (index++ > 0) s.append('\n');
              s.append(p.text.plainText());
            }
          }
        }
      }
    }
    return s.toString();
  }

  public Paragraph paragraph(int index) {
    var b = blocks.get(index);
    if (!(b instanceof Paragraph)) throw new IllegalArgumentException("Select a paragraph");
    return (Paragraph) b;
  }
}
