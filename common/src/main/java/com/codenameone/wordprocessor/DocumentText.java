package com.codenameone.wordprocessor;

/** Stable UTF-16 document coordinates for native text-input snapshots. */
public final class DocumentText {
  private final Document document;
  private final int[] starts;
  public final String text;

  public DocumentText(Document document) {
    this.document = document;
    starts = new int[document.blocks.size()];
    var b = new StringBuilder();
    for (int i = 0; i < starts.length; i++) {
      if (i > 0) b.append('\n');
      starts[i] = b.length();
      var block = document.blocks.get(i);
      b.append(block instanceof Paragraph p ? p.text.toString() : "\ufffc");
    }
    text = b.toString();
  }

  public int absolute(int block, int offset) {
    return starts[block] + offset;
  }

  public int[] position(int offset) {
    offset = Math.max(0, Math.min(text.length(), offset));
    int low = 0, high = starts.length - 1;
    while (low < high) {
      int mid = (low + high + 1) / 2;
      if (starts[mid] <= offset) low = mid;
      else high = mid - 1;
    }
    int block = low;
    if (!(document.blocks.get(block) instanceof Paragraph)) {
      while (block < starts.length && !(document.blocks.get(block) instanceof Paragraph)) block++;
      if (block == starts.length) {
        block = low;
        while (block > 0 && !(document.blocks.get(block) instanceof Paragraph)) block--;
      }
    }
    var p = document.paragraph(block);
    int local = Math.max(0, Math.min(p.text.length(), offset - starts[block]));
    var t = p.text.toString();
    if (local > 0 && local < t.length() && Character.isLowSurrogate(t.charAt(local))) local--;
    return new int[] {block, local};
  }
}
