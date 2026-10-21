package com.codenameone.wordprocessor;

import java.util.*;

/** Named body range. Markers do not consume text offsets or change visible text. */
public final class Bookmark {
  public final String name, startId, endId;
  public final int start, end;

  public Bookmark(String name, String startId, int start, String endId, int end) {
    if (!validName(name) || startId == null || endId == null || start < 0 || end < 0)
      throw new IllegalArgumentException("Invalid bookmark name or range");
    this.name = name;
    this.startId = startId;
    this.start = start;
    this.endId = endId;
    this.end = end;
  }

  public static boolean validName(String name) {
    if (name == null
        || name.isEmpty()
        || name.length() > 40
        || !(Unicode.letter(name.charAt(0)) || name.charAt(0) == '_')) return false;
    for (int i = 1; i < name.length(); i++)
      if (!(Unicode.letterOrDigit(name.charAt(i)) || name.charAt(i) == '_')) return false;
    return true;
  }

  /**
   * As Wordcraft's bookmark marks, a range may sit in any story: body text and table cells at any
   * depth, a header/footer story or a footnote/endnote story (the same flows as comment ranges).
   */
  public void validate(Document d) {
    if (ReviewComment.locate(d, startId) == null) {
      // A cell of a nested table (any depth): both marks in that one cell story.
      var story = nestedStory(d);
      if (story == null) throw new IllegalArgumentException("Invalid bookmark range: " + name);
      validate(story);
      return;
    }
    if (!ReviewComment.validRange(d, startId, start, endId, end))
      throw new IllegalArgumentException("Invalid bookmark range: " + name);
    ReviewComment.locate(d, startId).value.text.slice(start, start);
    ReviewComment.locate(d, endId).value.text.slice(end, end);
  }

  /** Cell path (block, row, column per level) of the nested cell holding the start mark, or null. */
  public int[] nestedPath(Document d) {
    return path(d.blocks, startId, new int[0]);
  }

  /** The nested cell story holding both marks, as a document, or null. */
  Document nestedStory(Document d) {
    int[] path = nestedPath(d);
    if (path == null || !Arrays.equals(path, path(d.blocks, endId, new int[0]))) return null;
    Sequence<Block> blocks = d.blocks;
    for (int i = 0; i < path.length; i += 3)
      blocks = ((Table) blocks.get(path[i])).blocks(path[i + 1], path[i + 2]);
    return new Document(blocks, d.page, d.title, d.nextId);
  }

  private static int[] path(Sequence<Block> blocks, String id, int[] prefix) {
    for (int b = 0; b < blocks.size(); b++) {
      if (blocks.get(b) instanceof Paragraph p && p.id.equals(id))
        return prefix.length == 0 ? null : prefix;
      if (!(blocks.get(b) instanceof Table table)) continue;
      for (int row = 0; row < table.rows(); row++)
        for (int column = 0; column < table.columns(); column++) {
          if (table.covered(row, column)) continue;
          int[] next = Arrays.copyOf(prefix, prefix.length + 3);
          next[prefix.length] = b;
          next[prefix.length + 1] = row;
          next[prefix.length + 2] = column;
          int[] found = path(table.blocks(row, column), id, next);
          if (found != null) return found;
          for (Block inner : table.blocks(row, column))
            if (inner instanceof Paragraph p && p.id.equals(id)) return next;
        }
    }
    return null;
  }

  /** The story holding the start mark, for Go To: null when the bookmark is in body paragraphs. */
  public ReviewComment.Anchor anchor(Document d) {
    return ReviewComment.locate(d, startId);
  }

  public String text(Document d) {
    validate(d);
    if (ReviewComment.locate(d, startId) == null) return text(nestedStory(d));
    int a = ReviewComment.block(d, startId), b = ReviewComment.block(d, endId);
    if (a < 0 || b < 0) {
      // Cell, header/footer or note story: the paragraphs of that flow in order.
      return proxyText(d);
    }
    var result = new StringBuilder();
    for (int i = a; i <= b; i++) {
      if (i > a) result.append('\n');
      var block = d.blocks.get(i);
      if (block instanceof Paragraph paragraph) {
        var t = paragraph.text;
        result.append(t.slice(i == a ? start : 0, i == b ? end : t.length()).plainText());
      }
    }
    return result.toString();
  }

  private String proxyText(Document d) {
    var first = ReviewComment.locate(d, startId);
    var last = ReviewComment.locate(d, endId);
    if (first.value == last.value) return first.value.text.slice(start, end).plainText();
    var between = new ArrayList<ReviewComment.Anchor>();
    for (var at : ReviewComment.index(d).values())
      if (at.sameFlow(first) && order(first, at) < 0 && order(at, last) < 0) between.add(at);
    between.sort(Bookmark::order);
    var result = new StringBuilder(first.value.text.slice(start, first.value.text.length()).plainText());
    for (var at : between) result.append('\n').append(at.value.text.plainText());
    result.append('\n').append(last.value.text.slice(0, end).plainText());
    return result.toString();
  }

  private static int order(ReviewComment.Anchor x, ReviewComment.Anchor y) {
    return x.sameStory(y) ? Integer.compare(x.paragraph, y.paragraph) : x.compareTo(y);
  }

  private ReviewComment proxy() {
    return new ReviewComment("bookmark:" + name, "", "", startId, start, endId, end, false);
  }

  static Map<String, Bookmark> reanchor(Document before, Document after) {
    if (before.bookmarks.isEmpty() || before.blocks == after.blocks) return before.bookmarks;
    return fromProxies(before, after, ReviewComment.reanchor(withProxies(before), after));
  }

  static Map<String, Bookmark> reanchor(
      Document before, Document after, Selection replaced, Selection inserted) {
    if (before.bookmarks.isEmpty()) return before.bookmarks;
    return fromProxies(
        before, after, ReviewComment.reanchor(withProxies(before), after, replaced, inserted));
  }

  /** Bookmarks follow edits exactly as comment ranges do in every story (Wordcraft moves both marks). */
  private static Document withProxies(Document d) {
    var list = new ArrayList<ReviewComment>();
    for (Bookmark mark : d.bookmarks.values()) list.add(mark.proxy());
    return d.comments(list);
  }

  private static Map<String, Bookmark> fromProxies(Document before, List<ReviewComment> moved) {
    return fromProxies(before, null, moved);
  }

  private static Map<String, Bookmark> fromProxies(
      Document before, Document after, List<ReviewComment> moved) {
    var byName = new HashMap<String, ReviewComment>();
    for (ReviewComment c : moved) byName.put(c.id, c);
    var result = new LinkedHashMap<String, Bookmark>();
    for (Bookmark mark : before.bookmarks.values()) {
      var c = byName.get("bookmark:" + mark.name);
      if (c == null) {
        // Nested-cell marks are edited in their own cell story; other edits leave them alone.
        if (after != null && ReviewComment.locate(before, mark.startId) == null) {
          try {
            mark.validate(after);
            result.put(mark.name, mark);
          } catch (RuntimeException gone) {
          }
        }
        continue;
      }
      result.put(
          mark.name,
          c.startId.equals(mark.startId) && c.start == mark.start && c.endId.equals(mark.endId)
                  && c.end == mark.end
              ? mark
              : new Bookmark(mark.name, c.startId, c.start, c.endId, c.end));
    }
    return result;
  }
}
