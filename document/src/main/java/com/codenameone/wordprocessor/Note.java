package com.codenameone.wordprocessor;

/** Immutable inline reference owning a rich footnote or endnote story. */
public final class Note {
  public final String id, kind, mark, label;
  public final Sequence<Paragraph> paragraphs;
  private final Sequence<Paragraph> displayed;

  public Note(String id, String kind, String mark, String label, Sequence<Paragraph> paragraphs) {
    if (id == null || id.isEmpty() || id.length() > 100
        || !("footnote".equals(kind) || "endnote".equals(kind))
        || mark == null || mark.length() > 20 || label == null || label.length() > 40
        || paragraphs == null || paragraphs.size() == 0 || paragraphs.size() > 10000)
      throw new IllegalArgumentException("Invalid note");
    for (int i = 0; i < mark.length(); i++)
      if ((mark.charAt(i) < 32 || mark.charAt(i) >= 127 && mark.charAt(i) <= 159)) throw new IllegalArgumentException("Invalid note mark");
    for (Paragraph p : paragraphs)
      if (p.text.hasNotes()) throw new IllegalArgumentException("Notes cannot contain notes");
    this.id = id;
    this.kind = kind;
    this.mark = mark;
    this.label = label;
    this.paragraphs = paragraphs;
    Paragraph first = paragraphs.get(0);
    TextStyle style = first.text.styleAt(0).clean().with("link", "");
    RichText prefix = RichText.of(label, style.with("script", "superscript"))
        .concat(RichText.of(" ", style.with("script", "baseline")));
    displayed = paragraphs.set(0, first.text(prefix.concat(first.text)));
  }

  /** Stable paragraph identities let all layout passes reuse measured note text. */
  public Sequence<Paragraph> displayedParagraphs() { return displayed; }

  public Note label(String value) {
    return label.equals(value) ? this : new Note(id, kind, mark, value, paragraphs);
  }

  public Note paragraphs(Sequence<Paragraph> value) {
    return value == paragraphs ? this : new Note(id, kind, mark, label, value);
  }

  public Note kind(String value) {
    return kind.equals(value) ? this : new Note(id, value, mark, label, paragraphs);
  }

  public String plainText() {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < paragraphs.size(); i++) {
      if (i > 0) out.append('\n');
      out.append(paragraphs.get(i).text.plainText());
    }
    return out.toString();
  }
}
