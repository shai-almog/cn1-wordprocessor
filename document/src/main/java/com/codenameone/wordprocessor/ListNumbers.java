package com.codenameone.wordprocessor;

/** Shared list labels for screen pagination and file export. */
public final class ListNumbers {
  private final int[] values = new int[9];
  private String previous = "";

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
    if ("bullet".equals(p.list)) return new String[] {"•", "◦", "▪"}[level % 3];
    return format(values[level], p.listFormat) + ".";
  }

  public void reset() {
    java.util.Arrays.fill(values, 0);
    previous = "";
  }

  public static String format(int number, String format) {
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
