package com.codenameone.wordprocessor;

/** UTF-16-safe word and story navigation, shared by keyboard movement and deletion. */
public final class TextNavigation {
  private TextNavigation() {}

  /** Move to a word boundary, stopping at adjacent paragraph boundaries and embedded blocks. */
  public static int[] word(Document document, int block, int offset, boolean forward) {
    var text = document.paragraph(block).text;
    if (forward && offset == text.length()) {
      if (block + 1 < document.blocks.size() && document.blocks.get(block + 1) instanceof Paragraph)
        return new int[] {block + 1, 0};
    } else if (!forward && offset == 0) {
      if (block > 0 && document.blocks.get(block - 1) instanceof Paragraph)
        return new int[] {block - 1, document.paragraph(block - 1).text.length()};
    } else if (forward) {
      // Range-field marks are transparent: a word runs through a citation's start or end mark,
      // and the caret stops after the spaces, before any mark that follows them.
      offset = skipMarks(text, offset, true);
      if (offset < text.length()) {
        int kind = kind(codePoint(text, offset));
        while (offset < text.length()
            && (isMark(text, offset) || kind(codePoint(text, offset)) == kind))
          offset += Character.charCount(codePoint(text, offset));
        while (offset < text.length() && !isMark(text, offset)
            && kind(codePoint(text, offset)) == 0)
          offset += Character.charCount(codePoint(text, offset));
      }
    } else {
      while (offset > 0 && (isMark(text, offset - 1)
          || kind(codePoint(text, previous(text, offset))) == 0))
        offset = previous(text, offset);
      if (offset > 0) {
        int kind = kind(codePoint(text, previous(text, offset)));
        while (offset > 0 && (isMark(text, offset - 1)
            || kind(codePoint(text, previous(text, offset))) == kind))
          offset = previous(text, offset);
      }
    }
    return new int[] {block, offset};
  }

  /**
   * The word around {@code offset} selected by a double click or long press: the run of letters,
   * digits and marks touching the offset, or the single next character when none is.
   */
  static int[] wordAt(String text, int offset) {
    int n = text.length();
    int a = Math.max(0, Math.min(offset, n));
    // Never start inside a surrogate pair: the selection must cover whole code points.
    if (a > 0 && a < n && Character.isLowSurrogate(text.charAt(a))
        && Character.isHighSurrogate(text.charAt(a - 1))) a--;
    int z = a;
    while (a > 0) {
      int cp = Character.codePointBefore(text, a);
      if (!isWordChar(cp)) break;
      a -= Character.charCount(cp);
    }
    while (z < n) {
      int cp = Character.codePointAt(text, z);
      if (!isWordChar(cp)) break;
      z += Character.charCount(cp);
    }
    // Selections cover whole grapheme clusters, so typing over a flag, a ZWJ emoji sequence or
    // an emoji with its variation selector replaces all of it rather than leaving parts behind.
    RichText g = RichText.of(text);
    while (a > 0 && !Graphemes.isBoundary(g, a)) a = Graphemes.previous(g, a);
    if (a == z && z < n) z = Graphemes.next(g, z);
    while (z < n && !Graphemes.isBoundary(g, z)) z = Graphemes.next(g, z);
    return new int[] {a, z};
  }

  private static boolean isWordChar(int cp) {
    return Unicode.letterOrDigit(cp) || Unicode.mark(cp);
  }

  /** Paragraph shortcuts retain the active story and do not jump across embedded blocks. */
  public static int[] paragraph(Document document, int block, int offset, boolean forward) {
    if (!forward && offset > 0) return new int[] {block, 0};
    int next = block + (forward ? 1 : -1);
    if (next >= 0
        && next < document.blocks.size()
        && document.blocks.get(next) instanceof Paragraph) return new int[] {next, 0};
    return new int[] {block, forward ? document.paragraph(block).text.length() : 0};
  }

  /** AppKit paragraph selection advances to the next end only when already at an end. */
  public static int[] paragraphEnd(Document document, int block, int offset) {
    if (offset == document.paragraph(block).text.length()
        && block + 1 < document.blocks.size()
        && document.blocks.get(block + 1) instanceof Paragraph) block++;
    return new int[] {block, document.paragraph(block).text.length()};
  }

  /** Boundary of the current editing story (the body, or the active table cell). */
  public static int[] boundary(Document document, boolean end) {
    for (int i = end ? document.blocks.size() - 1 : 0;
        i >= 0 && i < document.blocks.size();
        i += end ? -1 : 1)
      if (document.blocks.get(i) instanceof Paragraph)
        return new int[] {i, end ? document.paragraph(i).text.length() : 0};
    throw new IllegalArgumentException("No editable paragraph");
  }

  /** Delete the selected range or one word through the normal undo/revision transaction. */
  public static void deleteWord(Session session, boolean forward) {
    var selection = session.selection();
    // Any selection, a cross-story one included, is deleted whole (Wordcraft's delete_word_*).
    if (session.cellSelection() != null || session.mixedSelection() != null || !selection.collapsed()) {
      session.replace("");
      return;
    }
    var document = session.editingDocument();
    int block = selection.block, offset = selection.caret;
    int[] target;
    do {
      target = word(document, block, offset, forward);
      if (target[0] == block && target[1] == offset) return;
      boolean visible = !document.trackChanges;
      int first = Math.min(block, target[0]), last = Math.max(block, target[0]);
      for (int b = first; !visible && b <= last; b++) {
        var paragraph = document.paragraph(b);
        int from = b == first ? (forward ? offset : target[1]) : 0;
        int to = b == last ? (forward ? target[1] : offset) : paragraph.text.length();
        for (int i = from; !visible && i < to; i++) {
          var revision = paragraph.text.styleAt(i).revision;
          visible = revision == null || revision.inserted();
        }
        if (b < last)
          visible |= paragraph.breakRevision == null || paragraph.breakRevision.inserted();
      }
      block = target[0];
      offset = target[1];
      if (visible) break;
    } while (true);
    session.selectRange(selection.block, selection.caret, target[0], target[1]);
    session.replace("");
  }

  /**
   * True if the character at {@code at} is a zero-width range-field mark (a Zotero citation's
   * start or end), which, as Wordcraft's FieldStart/FieldEnd markers, takes no caret stop.
   */
  public static boolean isMark(RichText text, int at) {
    if (at < 0 || at >= text.length() || text.charAt(at) != '\ufffc' || !text.hasFields())
      return false;
    InlineField f = text.fieldAt(at);
    return f != null && (f.rangeEnd() || f.rangeStart() && !f.showCode);
  }

  /** {@code offset} moved forward (or back) past any adjacent zero-width marks. */
  public static int skipMarks(RichText text, int offset, boolean forward) {
    if (forward) while (offset < text.length() && isMark(text, offset)) offset++;
    else while (offset > 0 && isMark(text, offset - 1)) offset--;
    return offset;
  }

  /**
   * The caret stop one character after (or before) {@code offset}: marks next to the caret are
   * crossed together with the character beyond them, so each arrow press moves over one visible
   * character. Returns {@code offset} unchanged when only marks lie between it and the end.
   */
  public static int step(RichText text, int offset, boolean forward) {
    int at = skipMarks(text, offset, forward);
    if (forward) return at == text.length() ? offset : Graphemes.next(text, at);
    return at == 0 ? offset : Graphemes.previous(text, at);
  }

  private static int previous(RichText text, int offset) {
    offset--;
    if (offset > 0
        && Character.isLowSurrogate(text.charAt(offset))
        && Character.isHighSurrogate(text.charAt(offset - 1))) offset--;
    return offset;
  }

  private static int codePoint(RichText text, int offset) {
    char first = text.charAt(offset);
    if (Character.isHighSurrogate(first)
        && offset + 1 < text.length()
        && Character.isLowSurrogate(text.charAt(offset + 1)))
      return Character.toCodePoint(first, text.charAt(offset + 1));
    return first;
  }

  static boolean isWhitespace(int cp) {
    // Unicode White_Space property, independent of the platform Character tables.
    return cp >= 9 && cp <= 13
        || cp == 0x20
        || cp == 0x85
        || cp == 0xa0
        || cp == 0x1680
        || cp >= 0x2000 && cp <= 0x200a
        || cp == 0x2028
        || cp == 0x2029
        || cp == 0x202f
        || cp == 0x205f
        || cp == 0x3000;
  }

  private static int kind(int cp) {
    if (isWhitespace(cp)) return 0;
    return Unicode.word(cp) ? 1 : 2;
  }
}
