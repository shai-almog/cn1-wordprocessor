package com.codenameone.wordprocessor;

import java.util.*;

/** Bounded table aggregate fields. Formula text is parsed as data, never executed as code. */
public final class TableFormulas {
  private TableFormulas() {}

  public static String calculate(Table table, int row, int column, String instruction) {
    if (row < 0 || row >= table.rows() || column < 0 || column >= table.columns())
      throw new IllegalArgumentException("Formula cell is outside the table");
    var owner = table.span(row, column);
    return new Evaluation(table).formula(owner.row, owner.column, instruction);
  }

  public static Session.FieldResolver resolver(Document document) {
    final var paragraphs = new HashMap<String, Location>();
    for (Block block : document.blocks)
      if (block instanceof Table table) {
        var evaluation = new Evaluation(table);
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++) {
            if (table.covered(r, c)) continue;
            var location = new Location(evaluation, r, c);
            for (Paragraph p : table.cell(r, c)) paragraphs.put(p.id, location);
          }
      }
    return (paragraph, offset, field) -> {
      var at = paragraphs.get(paragraph);
      if (at == null || field.locked || !supported(field.instruction)) return field;
      try {
        return field.result(at.evaluation.formula(at.row, at.column, field.instruction));
      } catch (IllegalArgumentException error) {
        return field.result("!" + error.getMessage());
      }
    };
  }

  private static final class Location {
    final Evaluation evaluation;
    final int row, column;

    Location(Evaluation e, int r, int c) {
      evaluation = e;
      row = r;
      column = c;
    }
  }

  private static boolean supported(String instruction) {
    String s = upperAscii(instruction.trim());
    if (!s.startsWith("=")) return false;
    int open = s.indexOf('('), close = s.lastIndexOf(')');
    if (close > open) {
      var tail = s.substring(close + 1).trim();
      if (!tail.isEmpty() && !tail.equals("\\* MERGEFORMAT")) return false;
    }
    return open > 1 && operation(s.substring(1, open).trim());
  }

  private static boolean operation(String value) {
    return "SUM".equals(value)
        || "AVERAGE".equals(value)
        || "COUNT".equals(value)
        || "MIN".equals(value)
        || "MAX".equals(value)
        || "PRODUCT".equals(value);
  }

  // Formula keywords are ASCII identifiers regardless of the user's system language.
  private static String upperAscii(String value) {
    var result = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      result.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
    }
    return result.toString();
  }

  private static final class Evaluation {
    final Table table;
    final Map<Integer, Double> numbers = new HashMap<>();
    final Set<Integer> active = new HashSet<>();

    Evaluation(Table value) {
      table = value;
    }

    String formula(int row, int column, String instruction) {
      String text = upperAscii(instruction.trim());
      int open = text.indexOf('('), close = text.lastIndexOf(')');
      if (!text.startsWith("=") || open < 2 || close < open || text.length() > 8192)
        throw new IllegalArgumentException("Formula syntax error");
      String name = text.substring(1, open).trim(), tail = text.substring(close + 1).trim();
      if (!operation(name) || !(tail.isEmpty() || "\\* MERGEFORMAT".equals(tail)))
        throw new IllegalArgumentException("Unsupported formula or number format");
      String[] args = TextUtil.split(text.substring(open + 1, close), ',');
      if (args.length > 100) throw new IllegalArgumentException("Too many formula arguments");
      var values = new ArrayList<Double>();
      for (String argument : args) {
        // A merge occupies one value within a range; separate arguments may repeat a cell.
        var seen = new HashSet<Integer>();
        var a = argument.trim();
        if (a.isEmpty()) throw new IllegalArgumentException("Formula syntax error");
        if ("ABOVE".equals(a) || "BELOW".equals(a)) {
          for (int r = "ABOVE".equals(a) ? 0 : row + 1;
              r < ("ABOVE".equals(a) ? row : table.rows());
              r++) add(values, seen, r, column, row, column, true);
        } else if ("LEFT".equals(a) || "RIGHT".equals(a)) {
          for (int c = "LEFT".equals(a) ? 0 : column + 1;
              c < ("LEFT".equals(a) ? column : table.columns());
              c++) add(values, seen, row, c, row, column, true);
        } else {
          int colon = a.indexOf(':');
          if (colon >= 0) {
            int[] first = address(a.substring(0, colon)), last = address(a.substring(colon + 1));
            for (int r = Math.min(first[0], last[0]); r <= Math.max(first[0], last[0]); r++)
              for (int c = Math.min(first[1], last[1]); c <= Math.max(first[1], last[1]); c++)
                add(values, seen, r, c, row, column, false);
          } else if (a.charAt(0) >= 'A' && a.charAt(0) <= 'Z') {
            var cell = address(a);
            add(values, seen, cell[0], cell[1], row, column, false);
          } else {
            Double number = number(a);
            if (number == null) throw new IllegalArgumentException("Invalid formula argument");
            values.add(number);
          }
        }
      }
      double value = "PRODUCT".equals(name) ? 1 : 0;
      if (!values.isEmpty() && ("MIN".equals(name) || "MAX".equals(name))) value = values.get(0);
      for (double n : values) {
        if ("PRODUCT".equals(name)) value *= n;
        else if ("MIN".equals(name)) value = Math.min(value, n);
        else if ("MAX".equals(name)) value = Math.max(value, n);
        else value += n;
      }
      if ("COUNT".equals(name)) value = values.size();
      else if ("AVERAGE".equals(name) && !values.isEmpty()) value /= values.size();
      if (Double.isNaN(value) || Double.isInfinite(value))
        throw new IllegalArgumentException("Number error");
      return format(value);
    }

    int[] address(String text) {
      var s = text.trim();
      int at = 0, col = 0;
      while (at < s.length() && s.charAt(at) >= 'A' && s.charAt(at) <= 'Z') {
        col = col * 26 + s.charAt(at++) - 'A' + 1;
        if (col > table.columns())
          throw new IllegalArgumentException("Cell reference outside table");
      }
      if (at == 0 || !TextUtil.digits(s, at))
        throw new IllegalArgumentException("Invalid cell reference");
      int row;
      try {
        row = Integer.parseInt(s.substring(at));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid cell reference");
      }
      if (row < 1 || row > table.rows() || col < 1)
        throw new IllegalArgumentException("Cell reference outside table");
      return new int[] {row - 1, col - 1};
    }

    void add(
        List<Double> values,
        Set<Integer> seen,
        int row,
        int col,
        int targetRow,
        int targetCol,
        boolean direction) {
      var owner = table.span(row, col);
      row = owner.row;
      col = owner.column;
      if (row == targetRow && col == targetCol) {
        if (direction) return;
        throw new IllegalArgumentException("Circular reference");
      }
      int key = row * table.columns() + col;
      if (!seen.add(key)) return;
      var value = cell(row, col);
      if (value != null) values.add(value);
    }

    Double cell(final int row, final int col) {
      int key = row * table.columns() + col;
      if (numbers.containsKey(key)) return numbers.get(key);
      if (!active.add(key)) throw new IllegalArgumentException("Circular reference");
      try {
        if (active.size() > 128) throw new IllegalArgumentException("Formula dependency limit");
        final var text = new StringBuilder();
        for (Paragraph p : table.cell(row, col)) {
          if (text.length() > 0) text.append('\n');
          p.text.visit(
              new RichText.Visitor() {
                public void run(int offset, String value, TextStyle style) {
                  if (visible(style)) text.append(value);
                }

                public void field(int offset, InlineField field, TextStyle style) {
                  if (visible(style))
                    text.append(
                        !field.locked && supported(field.instruction)
                            ? formula(row, col, field.instruction)
                            : field.result);
                }
              });
        }
        Double result = number(text.toString());
        numbers.put(key, result);
        return result;
      } finally {
        active.remove(key);
      }
    }
  }

  private static boolean visible(TextStyle style) {
    return style.revision == null || style.revision.inserted();
  }

  /** English-style decimal points and correctly grouped currency/percent numbers. */
  static Double number(String value) {
    var s = value.trim();
    if (s.length() > 400) return null;
    if (s.endsWith("%")) s = s.substring(0, s.length() - 1).trim();
    s = currency(s);
    var sign = "";
    if (!s.isEmpty() && "+-\u2212\u2013".indexOf(s.charAt(0)) >= 0) {
      sign = s.charAt(0) == '+' ? "" : "-";
      s = currency(s.substring(1));
    }
    int dot = s.indexOf('.');
    String integer = dot < 0 ? s : s.substring(0, dot),
        fraction = dot < 0 ? "" : s.substring(dot + 1);
    if (!fraction.isEmpty() && !TextUtil.digits(fraction, 0)) return null;
    var digits = new StringBuilder();
    int group = 0, groups = 0;
    for (int i = 0; i < integer.length(); i++) {
      char c = integer.charAt(i);
      if (c >= '0' && c <= '9') {
        digits.append(c);
        group++;
      } else if (", '\u00a0\u202f\u2009\u2019".indexOf(c) >= 0) {
        if (group < 1 || (groups == 0 ? (group > 3 || digits.charAt(0) == '0') : group != 3))
          return null;
        groups++;
        group = 0;
      } else return null;
    }
    if (groups > 0 && group != 3 || digits.length() == 0 && fraction.isEmpty()) return null;
    try {
      double n = Double.parseDouble(sign + "0" + digits + "." + fraction + "0");
      return Double.isInfinite(n) || Double.isNaN(n) ? null : n;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String currency(String text) {
    var s = text.trim();
    var symbols = "$€£¥¢₽₴₺₹₩₪₫฿₱₸₼₾";
    while (!s.isEmpty() && symbols.indexOf(s.charAt(0)) >= 0) s = s.substring(1).trim();
    while (!s.isEmpty() && symbols.indexOf(s.charAt(s.length() - 1)) >= 0)
      s = s.substring(0, s.length() - 1).trim();
    if (s.length() > 4 && s.charAt(3) == ' ' && uppercase(s.substring(0, 3)))
      s = s.substring(4).trim();
    int space = s.lastIndexOf(' ');
    if (space >= 0 && s.length() - space <= 4 && letters(s.substring(space + 1)))
      s = s.substring(0, space).trim();
    return s;
  }

  private static boolean uppercase(String s) {
    for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 'A' || s.charAt(i) > 'Z') return false;
    return true;
  }

  private static boolean letters(String s) {
    if (s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) if (!Character.isLetter(s.charAt(i))) return false;
    return true;
  }

  static String format(double value) {
    if (value == Math.floor(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
    if (Math.abs(value) >= 9e15) return Double.toString(value);
    long rounded = Math.round(Math.abs(value) * 100);
    return (value < 0 ? "-" : "") + (rounded / 100) + "." + TextUtil.padded(rounded % 100, 10, 2);
  }
}
