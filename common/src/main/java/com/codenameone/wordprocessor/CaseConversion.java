package com.codenameone.wordprocessor;

/** Case conversion in selection order, with context shared across differently styled runs. */
final class CaseConversion {
  private final String[] converted;
  private final int[] offsets;
  private RichText result;

  CaseConversion(
      RichText text, String mode, boolean paragraphStart, Revision insertion, Revision deletion) {
    var source = text.toString();
    converted = new String[source.length()];
    offsets = new int[source.length() + 1];
    final boolean[] deleted = new boolean[source.length()];
    text.visit(
        new RichText.Visitor() {
          public void note(int offset, Note note, TextStyle style) { run(offset, InlineField.OBJECT, style); }
          public void field(int offset, InlineField field, TextStyle style) { run(offset, InlineField.OBJECT, style); }
          public void run(int offset, String value, TextStyle style) {
            if (style.revision != null && !style.revision.inserted())
              for (int i = offset; i < offset + value.length(); i++) deleted[i] = true;
          }
        });
    boolean upperNext = "title".equals(mode) || paragraphStart;
    for (int i = 0; i < source.length(); ) {
      int cp = Character.codePointAt(source, i), length = Character.charCount(cp);
      var original = source.substring(i, i + length);
      if (deleted[i]) {
        converted[i] = original;
        i += length;
        continue;
      }
      String upper = original.toUpperCase(), lower = original.toLowerCase();
      boolean letter = !upper.equals(lower) || Unicode.letter(cp);
      if (cp == 0x03a3
          && i > 0
          && isLetterBefore(source, i, deleted)
          && (i + length == source.length() || !isLetterAfter(source, i + length, deleted)))
        lower = "ς";
      String value;
      if ("upper".equals(mode)) value = upper;
      else if ("lower".equals(mode)) value = lower;
      else if ("toggle".equals(mode)) value = original.equals(upper) ? lower : upper;
      else {
        value = upperNext && letter ? upper : lower;
        if (letter) upperNext = false;
        if ("title".equals(mode) ? Character.isWhitespace(cp) : cp == '.' || cp == '!' || cp == '?')
          upperNext = true;
      }
      converted[i] = value;
      i += length;
    }
    final int[] lengths = new int[source.length() + 1];
    for (int i = 0; i < source.length(); i++)
      lengths[i + 1] = lengths[i] + (converted[i] == null ? 0 : converted[i].length());
    final RichText[] out = {RichText.of("", text.styleAt(0))};
    text.visit(
        new RichText.Visitor() {
          public void run(int offset, String value, TextStyle style) {
            boolean deleted = style.revision != null && !style.revision.inserted();
            String changed = deleted ? value : segment(offset, offset + value.length());
            int base = out[0].length();
            boolean tracked = insertion != null && !changed.equals(value);
            for (int i = 0; i <= value.length(); i++) {
              offsets[offset + i] = base + (deleted ? i : lengths[offset + i] - lengths[offset]);
            }
            RichText original = RichText.of(value, style);
            out[0] =
                out[0].concat(
                    changed.equals(value)
                        ? original
                        : tracked
                            ? RichText.of(changed, style.clean().revision(insertion))
                                .concat(original.deleted(deletion))
                            : RichText.of(changed, style));
            offsets[offset + value.length()] = out[0].length();
          }

          public void note(int offset, Note note, TextStyle style) {
            offsets[offset] = out[0].length();
            out[0] = out[0].concat(RichText.note(note, style));
            offsets[offset + 1] = out[0].length();
          }

          public void field(int offset, InlineField field, TextStyle style) {
            offsets[offset] = out[0].length();
            out[0] = out[0].concat(RichText.field(field, style));
            offsets[offset + 1] = out[0].length();
          }
        });
    result = out[0];
  }

  RichText text() {
    return result;
  }

  int offset(int offset) {
    return offsets[Math.max(0, Math.min(offsets.length - 1, offset))];
  }

  private String segment(int start, int end) {
    var out = new StringBuilder();
    for (int i = start; i < end; i++) if (converted[i] != null) out.append(converted[i]);
    return out.toString();
  }

  private static boolean isLetterBefore(String text, int offset, boolean[] deleted) {
    int start = offset - 1;
    while (start >= 0 && deleted[start]) start--;
    if (start < 0) return false;
    if (start > 0
        && Character.isLowSurrogate(text.charAt(start))
        && Character.isHighSurrogate(text.charAt(start - 1))) start--;
    return isLetterAt(text, start);
  }

  private static boolean isLetterAfter(String text, int offset, boolean[] deleted) {
    while (offset < text.length() && deleted[offset]) offset++;
    return offset < text.length() && isLetterAt(text, offset);
  }

  private static boolean isLetterAt(String text, int offset) {
    int cp = Character.codePointAt(text, offset);
    var value = text.substring(offset, offset + Character.charCount(cp));
    return Unicode.letter(cp) || !value.toUpperCase().equals(value.toLowerCase());
  }

  static boolean word(char c) {
    return Unicode.letterOrDigit(c) || c == '\'' || c == '_';
  }

  static String cycle(String text) {
    boolean lower = false, upper = false;
    for (int i = 0; i < text.length(); ) {
      int cp = Character.codePointAt(text, i);
      var value = text.substring(i, i + Character.charCount(cp));
      lower |= !value.equals(value.toUpperCase());
      upper |= !value.equals(value.toLowerCase());
      i += Character.charCount(cp);
    }
    return !upper ? "upper" : lower ? "lower" : "title";
  }
}
