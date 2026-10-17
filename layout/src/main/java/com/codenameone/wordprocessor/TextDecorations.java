package com.codenameone.wordprocessor;

/** Shared underline paths in typographic points, relative to the glyph baseline. */
public final class TextDecorations {
  private TextDecorations() {}

  public interface Lines {
    void line(float x1, float y1, float x2, float y2);
  }

  public static float weight(TextStyle style) {
    String type = style.characters.underlineStyle;
    return "thick".equals(type) || type.endsWith("Heavy") ? 1.5f : .5f;
  }

  public static void underline(LayoutEngine.Span span, LayoutEngine.Metrics metrics, Lines lines) {
    String type = span.style.characters.underlineStyle;
    if ("none".equals(type) || span.displayText.isEmpty() || span.width <= 0) return;
    if (!"words".equals(type)) {
      pattern(type, 0, span.width, lines);
      return;
    }
    String raw = span.rawDisplay;
    RichText text = RichText.of(raw, span.style);
    int spaces = 0;
    for (int at = 0; at < raw.length(); at = Graphemes.next(text, at))
      if (Character.isWhitespace(Character.codePointAt(raw, at))) spaces++;
    float padding =
        spaces == 0
            ? 0
            : Math.max(0, span.width - CharacterMetrics.width(metrics, raw, span.style)) / spaces;
    float x = 0, start = -1;
    for (int at = 0; at < raw.length(); ) {
      int end = Graphemes.next(text, at);
      boolean space = Character.isWhitespace(Character.codePointAt(raw, at));
      if (space && start >= 0) {
        pattern("single", start, Math.min(x, span.width), lines);
        start = -1;
      }
      if (!space && start < 0) start = x;
      x +=
          CharacterMetrics.width(metrics, raw.substring(at, end), span.style)
              + (space ? padding : 0);
      at = end;
    }
    if (start >= 0) pattern("single", start, span.width, lines);
  }

  private static void pattern(String type, float start, float end, Lines lines) {
    if (end <= start) return;
    if ("double".equals(type)) {
      lines.line(start, 1, end, 1);
      lines.line(start, 2.5f, end, 2.5f);
      return;
    }
    if (type.startsWith("wav")) {
      wave(start, end, 1.5f, lines);
      if ("wavyDouble".equals(type)) wave(start, end, 3.5f, lines);
      return;
    }
    float[] pattern;
    if (type.startsWith("dotDot") || type.startsWith("dashDotDot"))
      pattern = new float[] {4, 2, .5f, 2, .5f, 2};
    else if (type.startsWith("dotDash") || type.startsWith("dashDot"))
      pattern = new float[] {4, 2, .5f, 2};
    else if (type.startsWith("dot")) pattern = new float[] {.5f, 2};
    else if (type.startsWith("dashLong")) pattern = new float[] {8, 3};
    else if (type.startsWith("dash")) pattern = new float[] {4, 2};
    else {
      lines.line(start, 1, end, 1);
      return;
    }
    float x = start;
    int index = 0;
    while (x < end) {
      float next = Math.min(end, x + pattern[index]);
      if (index % 2 == 0) lines.line(x, 1, next, 1);
      x = next;
      index = (index + 1) % pattern.length;
    }
  }

  private static void wave(float start, float end, float y, Lines lines) {
    float x = start, previous = y;
    boolean down = true;
    while (x < end) {
      float next = Math.min(end, x + 1.5f);
      float target = y + (down ? .75f : -.75f);
      float nextY = previous + (target - previous) * (next - x) / 1.5f;
      lines.line(x, previous, next, nextY);
      x = next;
      previous = nextY;
      down = !down;
    }
  }
}
