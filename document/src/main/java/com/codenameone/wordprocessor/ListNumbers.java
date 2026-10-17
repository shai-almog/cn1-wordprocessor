package com.codenameone.wordprocessor;

/** Shared list labels for screen pagination and file export. */
public final class ListNumbers {
  private final int[] values = new int[9];
  private String previous = "";

  private final String[] formats = new String[9];

  public String next(Paragraph p) {
    if (p.list.isEmpty()) {
      reset();
      return "";
    }
    if (!previous.equals(p.list)) reset();
    previous = p.list;
    int level = p.listLevel;
    for (int i = level + 1; i < values.length; i++) values[i] = 0;
    values[level] = p.listStart > 0 ? p.listStart : values[level] + 1;
    formats[level] = levelFormat(p.listFormat, level);
    if ("bullet".equals(p.list)) {
      String t = template(p.listFormat);
      return t != null ? t : BULLETS[level % 3];
    }
    return label(levelText(p.listFormat, level), level);
  }

  /** Expands Word lvlText: %1..%9 are the counters of levels 1..9 in each level's format. */
  private String label(String text, int level) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '%' && i + 1 < text.length() && text.charAt(i + 1) >= '1' && text.charAt(i + 1) <= '9') {
        int k = text.charAt(++i) - '1';
        if (k <= level)
          b.append(format(Math.max(1, values[k]), formats[k] == null ? "decimal" : formats[k]));
      } else b.append(c);
    }
    return b.toString();
  }

  public static final String[] BULLETS = {"\u2022", "\u25e6", "\u25aa"};

  /** Numbering formats a list paragraph may carry, optionally followed by "|" and a label template. */
  public static final java.util.List<String> BASES =
      java.util.Arrays.asList(
          "decimal", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman", "outline", "legal",
          "bullet");

  public static boolean valid(String listFormat) {
    int bar = listFormat.indexOf('|');
    String base = bar < 0 ? listFormat : listFormat.substring(0, bar);
    if (!BASES.contains(base)) return false;
    if (bar < 0) return true;
    String t = listFormat.substring(bar + 1);
    return !t.isEmpty() && t.length() <= 40 && t.indexOf('|') < 0 && t.indexOf('\n') < 0;
  }

  public static String base(String listFormat) {
    int bar = listFormat.indexOf('|');
    return bar < 0 ? listFormat : listFormat.substring(0, bar);
  }

  /** The custom label template, or null for the format's default label. */
  public static String template(String listFormat) {
    int bar = listFormat.indexOf('|');
    return bar < 0 ? null : listFormat.substring(bar + 1);
  }

  /** Word numFmt of a level: outline cycles 1, a, i; legal is decimal throughout. */
  public static String levelFormat(String listFormat, int level) {
    String base = base(listFormat);
    if ("outline".equals(base)) return new String[] {"decimal", "lowerLetter", "lowerRoman"}[level % 3];
    if ("legal".equals(base) || "bullet".equals(base)) return "decimal";
    return base;
  }

  /** Word lvlText of a level. */
  public static String levelText(String listFormat, int level) {
    String t = template(listFormat);
    if (t != null) return t;
    if ("legal".equals(base(listFormat))) {
      StringBuilder b = new StringBuilder();
      for (int i = 1; i <= level + 1; i++) b.append('%').append(i).append('.');
      return b.toString();
    }
    return "%" + (level + 1) + ".";
  }

  public void reset() {
    java.util.Arrays.fill(values, 0);
    java.util.Arrays.fill(formats, null);
    previous = "";
  }

  public static String format(int number, String format) {
    format = levelFormat(format, 0);
    if (format.endsWith("Letter")) {
      String s = "";
      int n = number;
      do {
        n--;
        s = (char) ('a' + n % 26) + s;
        n /= 26;
      } while (n > 0);
      return format.startsWith("upper") ? s.toUpperCase() : s;
    }
    if (format.endsWith("Roman") && number <= 3999) {
      int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
      String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < values.length; i++)
        while (number >= values[i]) {
          b.append(symbols[i]);
          number -= values[i];
        }
      return format.startsWith("lower") ? b.toString().toLowerCase() : b.toString();
    }
    return String.valueOf(number);
  }
}
