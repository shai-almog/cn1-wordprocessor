package com.codenameone.wordprocessor;

/** Maps anchored body ranges through a single replacement, including split/join. */
final class RangeMapping {
  private final Document before, after;
  private final DocumentText old, next;
  private final int from, oldEnd, newEnd;

  RangeMapping(Document before, Document after) {
    this(before, after, new DocumentText(before), new DocumentText(after));
  }

  RangeMapping(Document before, Document after, DocumentText old, DocumentText next) {
    this.before = before;
    this.after = after;
    this.old = old;
    this.next = next;
    int a = 0, b = old.text.length(), c = next.text.length();
    while (a < b && a < c && old.text.charAt(a) == next.text.charAt(a)) a++;
    while (b > a && c > a && old.text.charAt(b - 1) == next.text.charAt(c - 1)) {
      b--;
      c--;
    }
    from = a;
    oldEnd = b;
    newEnd = c;
  }

  RangeMapping(Document before, Document after, Selection replaced, Selection inserted) {
    this.before = before;
    this.after = after;
    old = new DocumentText(before);
    next = new DocumentText(after);
    from = old.absolute(replaced.startBlock(), replaced.start());
    oldEnd = old.absolute(replaced.endBlock(), replaced.end());
    newEnd = next.absolute(inserted.block, inserted.caret);
  }

  int[] move(String startId, int start, String endId, int end, boolean discardPoint) {
    int a = ReviewComment.block(before, startId), b = ReviewComment.block(before, endId);
    if (a < 0 || b < 0) return null;
    int x = old.absolute(a, start), y = old.absolute(b, end);
    if (oldEnd > from && x >= from && y <= oldEnd && newEnd == from && (discardPoint || x < y))
      return null;
    int mappedStart = map(x, true), mappedEnd = map(y, false);
    if (x == y) mappedEnd = mappedStart;
    int[] first = next.position(mappedStart),
        last = next.position(Math.max(mappedStart, mappedEnd));
    return new int[] {first[0], first[1], last[0], last[1]};
  }

  private int map(int at, boolean leading) {
    if (at < from) return at;
    if (at > oldEnd || at == oldEnd && oldEnd > from) return at + newEnd - oldEnd;
    if (at == from && oldEnd == from) return leading ? newEnd : from;
    return leading ? from : newEnd;
  }
}
