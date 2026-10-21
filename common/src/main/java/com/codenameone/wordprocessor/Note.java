package com.codenameone.wordprocessor;

/**
 * Immutable inline reference owning a rich footnote or endnote story. Like Word and Wordcraft (a
 * note part is a story of body blocks), the story holds paragraphs, tables and pictures; {@link
 * #paragraphs} is its top-level paragraphs and {@link #blocks} the full story.
 */
public final class Note {
  public final String id, kind, mark, label;
  public final Sequence<Paragraph> paragraphs;
  public final Sequence<Block> blocks;
  private final Sequence<Paragraph> displayed;
  private final Sequence<Block> displayedBlocks;

  public Note(String id, String kind, String mark, String label, Sequence<? extends Block> story) {
    Sequence<Paragraph> paragraphs = story == null ? null : paragraphsOf(story);
    if (id == null || id.isEmpty() || id.length() > 100
        || !("footnote".equals(kind) || "endnote".equals(kind))
        || mark == null || mark.length() > 20 || label == null || label.length() > 40
        || paragraphs == null || paragraphs.size() == 0 || story.size() > 10000)
      throw new IllegalArgumentException("Invalid note");
    for (int i = 0; i < mark.length(); i++)
      if ((mark.charAt(i) < 32 || mark.charAt(i) >= 127 && mark.charAt(i) <= 159)) throw new IllegalArgumentException("Invalid note mark");
    for (Paragraph p : paragraphs)
      if (p.text.hasNotes()) throw new IllegalArgumentException("Notes cannot contain notes");
    for (Block b : story)
      if (b instanceof Table t && hasNotes(t)) throw new IllegalArgumentException("Notes cannot contain notes");
    this.id = id;
    this.kind = kind;
    this.mark = mark;
    this.label = label;
    this.paragraphs = paragraphs;
    var first = paragraphs.get(0);
    var style = first.text.styleAt(0).clean().with("link", "");
    var prefix = RichText.of(label, style.with("script", "superscript"))
        .concat(RichText.of(" ", style.with("script", "baseline")));
    displayed = paragraphs.set(0, first.text(prefix.concat(first.text)));
    blocks = widen(story);
    // The label leads the story's first paragraph (a story opening with a table keeps it there).
    int at = -1;
    for (int i = 0; i < blocks.size() && at < 0; i++) if (blocks.get(i) == first) at = i;
    displayedBlocks = at < 0 ? blocks : blocks.set(at, displayed.get(0));
  }

  @SuppressWarnings("unchecked")
  private static Sequence<Block> widen(Sequence<? extends Block> blocks) {
    // Sequence is immutable, so a paragraph sequence is safely a block sequence.
    return (Sequence<Block>) blocks;
  }

  @SuppressWarnings("unchecked")
  private static Sequence<Paragraph> paragraphsOf(Sequence<? extends Block> story) {
    boolean only = true;
    for (Block b : story) only &= b instanceof Paragraph;
    if (only) return (Sequence<Paragraph>) story;
    var out = new Sequence<Paragraph>();
    for (Block b : story) if (b instanceof Paragraph p) out = out.append(p);
    return out;
  }

  private static boolean hasNotes(Table t) {
    for (int r = 0; r < t.rows(); r++)
      for (int c = 0; c < t.columns(); c++)
        for (Block b : t.blocks(r, c))
          if (b instanceof Paragraph p ? p.text.hasNotes() : b instanceof Table n && hasNotes(n)) return true;
    return false;
  }

  /** True when the story holds a table or picture, not only paragraphs. */
  public boolean hasBlocks() {
    return blocks != (Object) paragraphs;
  }

  /** The story with the label leading its first paragraph, for layout. */
  public Sequence<Block> displayedBlocks() { return displayedBlocks; }

  /** Stable paragraph identities let all layout passes reuse measured note text. */
  public Sequence<Paragraph> displayedParagraphs() { return displayed; }

  public Note label(String value) {
    return label.equals(value) ? this : new Note(id, kind, mark, value, blocks);
  }

  /**
   * Replaces the top-level paragraphs, keeping tables and pictures between them (in a story that
   * holds any, paragraph count changes go to {@link #blocks(Sequence)} instead).
   */
  public Note paragraphs(Sequence<Paragraph> value) {
    if (value == paragraphs) return this;
    if (!hasBlocks() || value.size() != paragraphs.size()) return new Note(id, kind, mark, label, value);
    var out = blocks;
    for (int i = 0, k = 0; i < out.size(); i++)
      if (out.get(i) instanceof Paragraph) out = out.set(i, value.get(k++));
    return new Note(id, kind, mark, label, out);
  }

  /** Replaces the whole story: paragraphs, tables and pictures. */
  public Note blocks(Sequence<? extends Block> value) {
    return value == blocks ? this : new Note(id, kind, mark, label, value);
  }

  public Note kind(String value) {
    return kind.equals(value) ? this : new Note(id, value, mark, label, blocks);
  }

  public String plainText() {
    var out = new StringBuilder();
    for (int i = 0; i < paragraphs.size(); i++) {
      if (i > 0) out.append('\n');
      out.append(paragraphs.get(i).text.plainText());
    }
    return out.toString();
  }
}
