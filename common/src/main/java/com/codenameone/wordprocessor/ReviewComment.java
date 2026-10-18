package com.codenameone.wordprocessor;

/** Immutable review comment anchored to a UTF-16 range in stable paragraph IDs. */
public final class ReviewComment {
  public final String id, author, text, startId, endId;
  public final int start, end;
  public final boolean resolved;
  public final java.util.List<Reply> replies;

  public static final class Reply {
    public final String author, text;

    public Reply(String author, String text) {
      if (author == null || text == null || author.trim().isEmpty() || text.trim().isEmpty())
        throw new IllegalArgumentException("Enter a reply author and text");
      this.author = author;
      this.text = text;
    }
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
    return new ReviewComment(id, author, text, startId, start, endId, end, value, replies);
  }

  public ReviewComment reply(String author, String text) {
    var next = new java.util.ArrayList<Reply>(replies);
    next.add(new Reply(author, text));
    return new ReviewComment(id, this.author, this.text, startId, start, endId, end, false, next);
  }

  /** Replaces thread text or one reply without changing authors, anchors or resolution. */
  public ReviewComment edited(int replyIndex, String value) {
    if (value == null || value.trim().isEmpty())
      throw new IllegalArgumentException("Enter comment text");
    if (replyIndex < -1 || replyIndex >= replies.size())
      throw new IllegalArgumentException("Unknown reply");
    if (replyIndex == -1)
      return new ReviewComment(id, author, value, startId, start, endId, end, resolved, replies);
    var next = new java.util.ArrayList<Reply>(replies);
    next.set(replyIndex, new Reply(replies.get(replyIndex).author, value));
    return new ReviewComment(id, author, text, startId, start, endId, end, resolved, next);
  }

  /** A paragraph in the body or one owning table cell. Paragraph IDs survive row moves. */
  public static final class Anchor {
    public final int block, row, column, paragraph;
    public final Paragraph value;
    private Anchor(int block, int row, int column, int paragraph, Paragraph value) {
      this.block = block; this.row = row; this.column = column;
      this.paragraph = paragraph; this.value = value;
    }
    public boolean sameStory(Anchor other) {
      return other != null && (row < 0 ? other.row < 0
          : block == other.block && row == other.row && column == other.column);
    }
    Document story(Document root) {
      if (row < 0) return root;
      var paragraphs = new Sequence<Block>();
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
      if (block instanceof Paragraph paragraph && (result != null || block.id().equals(id))) {
        var value = new Anchor(b, -1, -1, b, paragraph);
        if (result == null) return value;
        result.put(block.id(), value);
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
              index++;
            }
          }
      }
    }
    return null;
  }

  /** Ranges may span paragraphs within the body or within a single table cell. */
  public static boolean validRange(Document document, String first, int start, String last, int end) {
    Anchor a = locate(document, first), z = locate(document, last);
    return a != null && a.sameStory(z) && start >= 0 && end >= 0
        && a.paragraph <= z.paragraph && start <= a.value.text.length()
        && end <= z.value.text.length() && (a.paragraph != z.paragraph || start <= end);
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
        range[3], comment.resolved, comment.replies);
  }

  private static java.util.List<ReviewComment> move(
      Document before, Document after, RangeMapping mapping) {
    var result = new java.util.ArrayList<ReviewComment>();
    for (ReviewComment c : before.comments) {
      if (block(before, c.startId) < 0) {
        ReviewComment moved = moveCell(before, after, c);
        if (moved != null) result.add(moved);
      } else {
        var range = mapping.move(c.startId, c.start, c.endId, c.end, true);
        if (range != null) result.add(relocated(c, after, range));
      }
    }
    return result;
  }
}
