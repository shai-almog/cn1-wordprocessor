package com.codenameone.wordprocessor;

import java.util.*;

/** Structural preview summaries supplement the plain-text before/after view. */
public final class DocumentDiff {
  private DocumentDiff() {}

  public static List<String> describe(Document before, Document after) {
    var changes = new ArrayList<String>();
    if (!before.title.equals(after.title)) changes.add("Title changed to " + after.title);
    PageSpec a = before.page, b = after.page;
    if (a.width != b.width
        || a.height != b.height
        || a.top != b.top
        || a.left != b.left
        || a.right != b.right
        || a.bottom != b.bottom) changes.add("Page geometry changed");
    var old = new LinkedHashMap<String, Block>();
    for (Block block : before.blocks) old.put(block.id(), block);
    for (Block block : after.blocks) {
      var previous = old.remove(block.id());
      if (previous == null) {
        changes.add("Added " + block.id());
        continue;
      }
      if (block == previous) continue;
      if (block instanceof Paragraph q && previous instanceof Paragraph p) {
        boolean text = !p.text.toString().equals(q.text.toString());
        boolean format =
            !p.style.equals(q.style)
                || !p.align.equals(q.align)
                || !p.list.equals(q.list)
                || p.before != q.before
                || p.after != q.after
                || p.indent != q.indent
                || p.lineSpacing != q.lineSpacing
                || !p.format.lineRule.equals(q.format.lineRule)
                || p.pageBreak != q.pageBreak
                || p.keepNext != q.keepNext;
        if (!text && !format)
          for (int i = 0; i < p.text.length(); i++)
            if (!p.text.styleAt(i).equals(q.text.styleAt(i))) {
              format = true;
              break;
            }
        if (text || format)
          changes.add(
              block.id()
                  + ": "
                  + (text ? "text changed" : "")
                  + (text && format ? ", " : "")
                  + (format ? "formatting changed" : ""));
      } else changes.add(block.id() + ": table or block structure changed");
    }
    for (String id : old.keySet()) changes.add("Removed " + id);
    return Collections.unmodifiableList(changes);
  }
}
