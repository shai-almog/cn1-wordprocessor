package com.codenameone.wordprocessor;

/** Immutable author/date identity shared by the runs and paragraph marks of an edit. */
public final class Revision {
  public final String id, kind, author, date;
  public final Revision previous;

  public Revision(String id, String kind, String author, String date) {
    this(id, kind, author, date, null);
  }

  private Revision(String id, String kind, String author, String date, Revision previous) {
    this.previous = previous;
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
    return new Revision(id, kind, author, date, value);
  }

  public static final class Resolution {
    public final boolean removed;
    public final Revision remaining;

    Resolution(boolean removed, Revision remaining) {
      this.removed = removed;
      this.remaining = remaining;
    }
  }

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
        && date.equals(r.date);
  }

  public int hashCode() {
    return id.hashCode() * 31 + kind.hashCode();
  }
}
