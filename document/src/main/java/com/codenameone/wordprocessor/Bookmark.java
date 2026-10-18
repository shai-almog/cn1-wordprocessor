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
        || !(Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')) return false;
    for (int i = 1; i < name.length(); i++)
      if (!(Character.isLetterOrDigit(name.charAt(i)) || name.charAt(i) == '_')) return false;
    return true;
  }

  public void validate(Document d) {
    int a = ReviewComment.block(d, startId), b = ReviewComment.block(d, endId);
    if (a < 0
        || b < a
        || !(d.blocks.get(a) instanceof Paragraph)
        || !(d.blocks.get(b) instanceof Paragraph)
        || start > d.paragraph(a).text.length()
        || end > d.paragraph(b).text.length()
        || a == b && start > end)
      throw new IllegalArgumentException("Invalid bookmark range: " + name);
    d.paragraph(a).text.slice(start, start);
    d.paragraph(b).text.slice(end, end);
  }

  public String text(Document d) {
    validate(d);
    int a = ReviewComment.block(d, startId), b = ReviewComment.block(d, endId);
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

  static Map<String, Bookmark> reanchor(Document before, Document after) {
    if (before.bookmarks.isEmpty() || before.blocks == after.blocks) return before.bookmarks;
    DocumentText old = new DocumentText(before), next = new DocumentText(after);
    if (old.text.equals(next.text)) {
      boolean valid = true;
      for (Bookmark mark : before.bookmarks.values())
        if (ReviewComment.block(after, mark.startId) < 0
            || ReviewComment.block(after, mark.endId) < 0) valid = false;
      if (valid) return before.bookmarks;
    }
    return move(before, after, new RangeMapping(before, after, old, next));
  }

  static Map<String, Bookmark> reanchor(
      Document before, Document after, Selection replaced, Selection inserted) {
    return move(before, after, new RangeMapping(before, after, replaced, inserted));
  }

  private static Map<String, Bookmark> move(Document before, Document after, RangeMapping mapping) {
    var result = new LinkedHashMap<String, Bookmark>();
    for (Bookmark mark : before.bookmarks.values()) {
      var r = mapping.move(mark.startId, mark.start, mark.endId, mark.end, false);
      if (r != null)
        result.put(
            mark.name,
            new Bookmark(
                mark.name, after.blocks.get(r[0]).id(), r[1], after.blocks.get(r[2]).id(), r[3]));
    }
    return result;
  }
}
