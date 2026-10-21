package com.codenameone.wordprocessor;

/** Immutable author/date identity shared by the runs and paragraph marks of an edit. */
public final class Revision {
  public final String id, kind, author, date;
  public final Revision previous;
  /**
   * The name linking the two ends of a tracked move (w:moveFrom / w:moveTo, ECMA-376
   * 17.13.5.21-28), or null: a deletion with a name is the moved-away source, an insertion the
   * destination. Accepting or rejecting either end resolves both (Wordcraft cmd/moves.rs).
   */
  public final String move;

  public Revision(String id, String kind, String author, String date) {
    this(id, kind, author, date, null, null);
  }

  private Revision(String id, String kind, String author, String date, Revision previous, String move) {
    this.previous = previous;
    this.move = move == null || move.isEmpty() || "format".equals(kind) ? null : move;
    if (id == null
        || id.isEmpty()
        || author == null
        || date == null
        || !("insert".equals(kind) || "delete".equals(kind) || "format".equals(kind)))
      throw new IllegalArgumentException("Invalid revision");
    this.id = id;
    this.kind = kind;
    this.author = author;
    this.date = date;
  }

  public Revision previous(Revision value) {
    return new Revision(id, kind, author, date, value, move);
  }

  /** This revision as one end of the move named {@code name} (null: an ordinary change). */
  public Revision move(String name) {
    return new Revision(id, kind, author, date, previous, name);
  }

  public record Resolution(boolean removed, Revision remaining) {}

  public Resolution resolve(String selectedId, boolean accept) {
    Resolution prior =
        previous == null ? new Resolution(false, null) : previous.resolve(selectedId, accept);
    if (prior.removed) return prior;
    if (selectedId == null || id.equals(selectedId))
      return new Resolution(remove(accept), prior.remaining);
    return new Resolution(false, previous == prior.remaining ? this : previous(prior.remaining));
  }

  public boolean inserted() {
    return "insert".equals(kind);
  }

  public boolean remove(boolean accept) {
    return inserted() != accept;
  }

  public boolean equals(Object other) {
    if (!(other instanceof Revision)) return false;
    var r = (Revision) other;
    return (previous == null ? r.previous == null : previous.equals(r.previous))
        && id.equals(r.id)
        && kind.equals(r.kind)
        && author.equals(r.author)
        && date.equals(r.date)
        && (move == null ? r.move == null : move.equals(r.move));
  }

  public int hashCode() {
    return id.hashCode() * 31 + kind.hashCode();
  }
}
