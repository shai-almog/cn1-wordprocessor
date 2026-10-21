package com.codenameone.wordprocessor;

/** Immutable review comment anchored to a UTF-16 range in stable paragraph IDs. */
public final class ReviewComment {
  public final String id, author, text, startId, endId;
  public final int start, end;
  public final boolean resolved;
  public final java.util.List<Reply> replies;
  /** When the comment was written, ISO 8601 (Wordcraft Comment.date, w:date); "" when unknown. */
  public final String date;

  public static final class Reply {
    public final String author, text;
    /** ISO 8601 or "". */
    public final String date;

    public Reply(String author, String text) {
      this(author, text, "");
    }

    public Reply(String author, String text, String date) {
      if (author == null || text == null || author.trim().isEmpty() || text.trim().isEmpty())
        throw new IllegalArgumentException("Enter a reply author and text");
      this.author = author;
      this.text = text;
      this.date = date == null ? "" : date;
    }
  }

  /** The current UTC time as Word writes w:date (second precision, Z). */
  public static String now() {
    var c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
    c.setTime(new java.util.Date());
    return c.get(java.util.Calendar.YEAR) + "-" + two(c.get(java.util.Calendar.MONTH) + 1) + "-"
        + two(c.get(java.util.Calendar.DAY_OF_MONTH)) + "T" + two(c.get(java.util.Calendar.HOUR_OF_DAY))
        + ":" + two(c.get(java.util.Calendar.MINUTE)) + ":" + two(c.get(java.util.Calendar.SECOND)) + "Z";
  }

  private static String two(int v) {
    return v < 10 ? "0" + v : String.valueOf(v);
  }

  /** A date for display: "2024-03-05 14:07" from ISO 8601, "" when absent or malformed. */
  public static String displayDate(String iso) {
    if (iso == null || iso.length() < 10) return "";
    String day = iso.substring(0, 10);
    if (iso.length() >= 16 && iso.charAt(10) == 'T') return day + " " + iso.substring(11, 16);
    return day;
  }

  public ReviewComment(
      String id,
      String author,
      String text,
      String startId,
      int start,
      String endId,
      int end,
      boolean resolved) {
    this(
        id,
        author,
        text,
        startId,
        start,
        endId,
        end,
        resolved,
        java.util.Collections.<Reply>emptyList());
  }

  public ReviewComment(
      String id,
      String author,
      String text,
      String startId,
      int start,
      String endId,
      int end,
      boolean resolved,
      java.util.List<Reply> replies) {
    this(id, author, text, startId, start, endId, end, resolved, replies, "");
  }

  public ReviewComment(
      String id,
      String author,
      String text,
      String startId,
      int start,
      String endId,
      int end,
      boolean resolved,
      java.util.List<Reply> replies,
      String date) {
    this.date = date == null ? "" : date;
    this.replies = java.util.Collections.unmodifiableList(new java.util.ArrayList<Reply>(replies));
    if (id == null
        || author == null
        || text == null
        || startId == null
        || endId == null
        || start < 0
        || end < 0) throw new IllegalArgumentException("Invalid comment");
    this.id = id;
    this.author = author;
    this.text = text;
    this.startId = startId;
    this.start = start;
    this.endId = endId;
    this.end = end;
    this.resolved = resolved;
  }

  public ReviewComment resolved(boolean value) {
    return new ReviewComment(id, author, text, startId, start, endId, end, value, replies, date);
  }

  public ReviewComment reply(String author, String text) {
    return reply(author, text, now());
  }

  public ReviewComment reply(String author, String text, String date) {
    var next = new java.util.ArrayList<Reply>(replies);
    next.add(new Reply(author, text, date));
    return new ReviewComment(id, this.author, this.text, startId, start, endId, end, false, next, this.date);
  }

  /** This comment with its date replaced. */
  public ReviewComment dated(String value) {
    return new ReviewComment(id, author, text, startId, start, endId, end, resolved, replies, value);
  }

  /** Replaces thread text or one reply without changing authors, anchors or resolution. */
  public ReviewComment edited(int replyIndex, String value) {
    if (value == null || value.trim().isEmpty())
      throw new IllegalArgumentException("Enter comment text");
    if (replyIndex < -1 || replyIndex >= replies.size())
      throw new IllegalArgumentException("Unknown reply");
    if (replyIndex == -1)
      return new ReviewComment(id, author, value, startId, start, endId, end, resolved, replies, date);
    var next = new java.util.ArrayList<Reply>(replies);
    next.set(replyIndex, new Reply(replies.get(replyIndex).author, value, replies.get(replyIndex).date));
    return new ReviewComment(id, author, text, startId, start, endId, end, resolved, next, date);
  }

  /**
   * A paragraph in the body, one owning table cell, or a footnote/endnote story ({@link #note}
   * set; block/row/column then locate the note's reference). Paragraph IDs survive row moves.
   */
  public static final class Anchor {
    public final int block, row, column, paragraph;
    public final Paragraph value;
    /** The note whose story holds the paragraph, or null. */
    public final Note note;
    private Anchor(int block, int row, int column, int paragraph, Paragraph value) {
      this(block, row, column, paragraph, value, null);
    }
    private Anchor(int block, int row, int column, int paragraph, Paragraph value, Note note) {
      this.block = block; this.row = row; this.column = column;
      this.paragraph = paragraph; this.value = value; this.note = note;
    }
    public boolean sameStory(Anchor other) {
      if (other == null) return false;
      if (note != null || other.note != null)
        return note != null && other.note != null && note.id.equals(other.note.id);
      return row < 0 ? other.row < 0
          : block == other.block && row == other.row && column == other.column;
    }
    /**
     * Whether a range from this paragraph to other can exist: the same note story, or both in
     * the body stream (top-level paragraphs and table cells, as Wordcraft's body story holds
     * cells, so a range may run from the body into or across a table's cells).
     */
    public boolean sameFlow(Anchor other) {
      if (other == null) return false;
      if (note != null || other.note != null) return sameStory(other);
      return true;
    }
    /** Document order within one flow: block, then row, cell and paragraph. */
    public int compareTo(Anchor o) {
      if (block != o.block) return block < o.block ? -1 : 1;
      if (row != o.row) return row < o.row ? -1 : 1;
      if (column != o.column) return column < o.column ? -1 : 1;
      return Integer.compare(paragraph, o.paragraph);
    }
    Document story(Document root) {
      var paragraphs = new Sequence<Block>();
      if (note != null) {
        for (Paragraph p : note.paragraphs) paragraphs = paragraphs.append(p);
        return new Document(paragraphs, root.page, root.title, root.nextId);
      }
      if (row < 0) return root;
      for (Paragraph p : ((Table) root.blocks.get(block)).cell(row, column))
        paragraphs = paragraphs.append(p);
      return new Document(paragraphs, root.page, root.title, root.nextId);
    }
  }

  public static Anchor locate(Document document, String id) {
    return id == null ? null : scan(document, id, null);
  }

  /** One index per immutable root lets painting resolve all anchors without rescanning tables. */
  public static java.util.Map<String, Anchor> index(Document document) {
    var result = new java.util.HashMap<String, Anchor>();
    scan(document, null, result);
    return result;
  }

  private static Anchor scan(Document document, String id, java.util.Map<String, Anchor> result) {
    for (int b = 0; b < document.blocks.size(); b++) {
      var block = document.blocks.get(b);
      if (block instanceof Paragraph paragraph) {
        if (result != null || block.id().equals(id)) {
          var value = new Anchor(b, -1, -1, b, paragraph);
          if (result == null) return value;
          result.put(block.id(), value);
        }
        var inNote = notes(paragraph, b, -1, -1, id, result);
        if (inNote != null) return inNote;
      }
      if (block instanceof Table table) {
        for (int row = 0; row < table.rows(); row++)
          for (int column = 0; column < table.columns(); column++) {
            if (table.covered(row, column)) continue;
            int index = 0;
            for (Paragraph p : table.cell(row, column)) {
              if (result != null || p.id.equals(id)) {
                var value = new Anchor(b, row, column, index, p);
                if (result == null) return value;
                result.put(p.id, value);
              }
              var inNote = notes(p, b, row, column, id, result);
              if (inNote != null) return inNote;
              index++;
            }
          }
      }
    }
    return null;
  }

  private static Anchor notes(
      Paragraph host, int b, int row, int column, String id, java.util.Map<String, Anchor> result) {
    if (!host.text.hasNotes()) return null;
    final Anchor[] found = {null};
    host.text.mapNotes(
        (offset, note) -> {
          int index = 0;
          for (Paragraph p : note.paragraphs) {
            if (found[0] == null && (result != null || p.id.equals(id))) {
              var value = new Anchor(b, row, column, index, p, note);
              if (result == null) found[0] = value;
              else result.put(p.id, value);
            }
            index++;
          }
          return note;
        });
    return found[0];
  }

  /**
   * Ranges may span paragraphs of the body stream (from body text into or across table cells,
   * as Wordcraft's body story orders cells) or of one footnote/endnote story.
   */
  public static boolean validRange(Document document, String first, int start, String last, int end) {
    Anchor a = locate(document, first), z = locate(document, last);
    if (a == null || !a.sameFlow(z) || start < 0 || end < 0) return false;
    int order = a.sameStory(z) ? Integer.compare(a.paragraph, z.paragraph) : a.compareTo(z);
    return order <= 0 && start <= a.value.text.length()
        && end <= z.value.text.length() && (order != 0 || start <= end);
  }

  public static int block(Document d, String id) {
    for (int i = 0; i < d.blocks.size(); i++) if (d.blocks.get(i).id().equals(id)) return i;
    return -1;
  }

  public static java.util.List<ReviewComment> reanchor(Document before, Document after) {
    if (before.comments.isEmpty() || before.blocks == after.blocks) return before.comments;
    DocumentText old = new DocumentText(before), next = new DocumentText(after);
    if (old.text.equals(next.text) && sameTables(before, after)) {
      boolean valid = true;
      for (ReviewComment c : before.comments)
        if (!validRange(after, c.startId, c.start, c.endId, c.end)) valid = false;
      if (valid) return before.comments;
    }
    return move(before, after, new RangeMapping(before, after, old, next));
  }

  public static java.util.List<ReviewComment> reanchor(
      Document before, Document after, Selection replaced, Selection insertedEnd) {
    return move(before, after, new RangeMapping(before, after, replaced, insertedEnd));
  }

  private static boolean sameTables(Document before, Document after) {
    for (Block block : before.blocks) if (block instanceof Table) {
      int index = block(after, block.id());
      if (index < 0 || after.blocks.get(index) != block) return false;
    }
    return true;
  }

  private static ReviewComment moveCell(Document before, Document after, ReviewComment comment) {
    Anchor old = locate(before, comment.startId);
    if (old == null || old.row < 0) return null;
    var source = old.story(before);
    Anchor target = locate(after, comment.startId);
    if (target == null) target = locate(after, comment.endId);
    // An endpoint may have been removed by a paragraph join. Another stable paragraph
    // in the same cell identifies the surviving story; removing the whole cell drops it.
    if (target == null) for (Block p : source.blocks) {
      target = locate(after, p.id());
      if (target != null) break;
    }
    if (target == null || target.row < 0) return null;
    var destination = target.story(after);
    // Preserve unchanged anchors when cells are merged/reordered around them.
    Anchor first = locate(before, comment.startId), last = locate(before, comment.endId);
    Anchor nextFirst = locate(after, comment.startId), nextLast = locate(after, comment.endId);
    if (first != null && last != null && nextFirst != null && nextFirst.sameStory(nextLast)
        && first.value.text == nextFirst.value.text && last.value.text == nextLast.value.text
        && validRange(after, comment.startId, comment.start, comment.endId, comment.end)) return comment;
    var range = new RangeMapping(source, destination).move(
        comment.startId, comment.start, comment.endId, comment.end, true);
    return range == null ? null : relocated(comment, destination, range);
  }

  private static ReviewComment relocated(ReviewComment comment, Document document, int[] range) {
    return new ReviewComment(comment.id, comment.author, comment.text,
        document.blocks.get(range[0]).id(), range[1], document.blocks.get(range[2]).id(),
        range[3], comment.resolved, comment.replies, comment.date);
  }

  private static java.util.List<ReviewComment> move(
      Document before, Document after, RangeMapping mapping) {
    var result = new java.util.ArrayList<ReviewComment>();
    for (ReviewComment c : before.comments) {
      Anchor first = locate(before, c.startId), last = locate(before, c.endId);
      if (first == null || last == null) continue;
      if (first.note != null || !first.sameStory(last)) {
        ReviewComment moved = moveAcross(after, mapping, c, first, last);
        if (moved != null) result.add(moved);
      } else if (first.row >= 0) {
        ReviewComment moved = moveCell(before, after, c);
        if (moved != null) result.add(moved);
      } else {
        var range = mapping.move(c.startId, c.start, c.endId, c.end, true);
        if (range != null) result.add(relocated(c, after, range));
      }
    }
    return result;
  }

  /**
   * A note-story range, or one spanning stories of the body stream (body text and cells): its
   * top-level endpoints follow the edit; endpoints in cells or notes (edited in their own
   * story view) keep their paragraph and are clamped to it. Dropped when a paragraph is gone.
   */
  private static ReviewComment moveAcross(
      Document after, RangeMapping mapping, ReviewComment c, Anchor first, Anchor last) {
    String startId = c.startId, endId = c.endId;
    int start = c.start, end = c.end;
    if (first.note == null && first.row < 0) {
      int[] at = mapping.move(c.startId, c.start, c.startId, c.start, false);
      if (at == null) return null;
      startId = after.blocks.get(at[0]).id();
      start = at[1];
    }
    if (last.note == null && last.row < 0) {
      int[] at = mapping.move(c.endId, c.end, c.endId, c.end, false);
      if (at == null) return null;
      endId = after.blocks.get(at[0]).id();
      end = at[1];
    }
    Anchor a = locate(after, startId), z = locate(after, endId);
    if (a == null || z == null) return null;
    start = Math.min(start, a.value.text.length());
    end = Math.min(end, z.value.text.length());
    if (!validRange(after, startId, start, endId, end)) return null;
    return new ReviewComment(c.id, c.author, c.text, startId, start, endId, end, c.resolved, c.replies, c.date);
  }
}
