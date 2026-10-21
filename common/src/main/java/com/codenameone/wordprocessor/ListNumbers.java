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
    return label(levelText(p.listFormat, level), level, "legal".equals(base(p.listFormat)));
  }

  /** Expands Word lvlText: %1..%9 are the counters of levels 1..9 in each level's format. */
  private String label(String text, int level, boolean legal) {
    var b = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '%' && i + 1 < text.length() && text.charAt(i + 1) >= '1' && text.charAt(i + 1) <= '9') {
        int k = text.charAt(++i) - '1';
        if (k <= level)
          b.append(format(Math.max(1, values[k]),
              legal || formats[k] == null ? "decimal" : formats[k]));
      } else b.append(c);
    }
    return b.toString();
  }

  public static final String[] BULLETS = {"\u2022", "\u25e6", "\u25aa"};

  /** Numbering formats a list paragraph may carry, optionally followed by "|" and a label template. */
  public static final java.util.List<String> BASES =
      java.util.Arrays.asList(
          "decimal", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman", "outline", "legal",
          "bullet", "decimalZero", "ordinal", "cardinalText", "ordinalText", "chicago",
          "numberInDash", "none", "ideographDigital", "koreanDigital2", "taiwaneseDigital",
          "japaneseCounting", "japaneseLegal", "japaneseDigitalTenThousand", "aiueo", "aiueoFullWidth",
          "iroha", "irohaFullWidth", "decimalFullWidth", "decimalFullWidth2", "decimalHalfWidth",
          "decimalEnclosedCircle", "decimalEnclosedCircleChinese", "decimalEnclosedFullstop",
          "decimalEnclosedParen", "ideographEnclosedCircle", "ideographTraditional", "ideographZodiac",
          "ideographZodiacTraditional", "ideographLegalTraditional", "taiwaneseCounting",
          "taiwaneseCountingThousand", "chineseCounting", "chineseCountingThousand",
          "chineseLegalSimplified", "koreanCounting", "koreanDigital", "koreanLegal", "ganada", "chosung",
          // Kept and written back, drawn as decimal (as in Wordcraft, which does not draw them yet).
          "hebrew1", "hebrew2", "arabicAlpha", "arabicAbjad", "hindiVowels", "hindiConsonants",
          "hindiNumbers", "hindiCounting", "thaiLetters", "thaiNumbers", "thaiCounting", "russianLower",
          "russianUpper", "vietnameseCounting");

  public static boolean valid(String listFormat) {
    int bar = listFormat.indexOf('|');
    String base = bar < 0 ? listFormat : listFormat.substring(0, bar);
    if (!BASES.contains(base)) return false;
    if (bar < 0) return true;
    var t = listFormat.substring(bar + 1);
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
      var b = new StringBuilder();
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
    String cjk = CjkNumbers.format(number, format);
    if (cjk != null) return cjk;
    if (format.endsWith("Letter")) {
      // Word letters repeat past z (aa, bb, ...), as Wordcraft; at most 780 (30 z's).
      if (number <= 0) return "";
      int n = Math.min(number, 780);
      var b = new StringBuilder();
      for (int k = (n - 1) / 26; k >= 0; k--) b.append((char) ('a' + (n - 1) % 26));
      return format.startsWith("upper") ? b.toString().toUpperCase() : b.toString();
    }
    switch (format) {
      case "decimalZero":
        return number < 10 && number >= 0 ? "0" + number : String.valueOf(number);
      case "ordinal":
        return number + ordinalSuffix(number);
      case "cardinalText":
        return capitalize(words(Math.min(Math.max(number, 0), 999999)));
      case "ordinalText":
        return ordinalText(number);
      case "chicago": {
        if (number <= 0) return "";
        char mark = "*\u2020\u2021\u00a7".charAt((number - 1) % 4);
        var b = new StringBuilder();
        for (int k = Math.min((number - 1) / 4 + 1, 10); k > 0; k--) b.append(mark);
        return b.toString();
      }
      case "numberInDash":
        return "- " + number + " -";
      case "none":
        return "";
      default:
        break;
    }
    if (format.endsWith("Roman") && number <= 3999) {
      int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
      String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
      var b = new StringBuilder();
      for (int i = 0; i < values.length; i++)
        while (number >= values[i]) {
          b.append(symbols[i]);
          number -= values[i];
        }
      return format.startsWith("lower") ? b.toString().toLowerCase() : b.toString();
    }
    return String.valueOf(number);
  }

  private static String ordinalSuffix(int n) {
    int t = n % 100;
    if (t >= 11 && t <= 13) return "th";
    return n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
  }

  private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six",
      "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
      "sixteen", "seventeen", "eighteen", "nineteen"};
  private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty",
      "seventy", "eighty", "ninety"};

  /** English cardinal words, as Wordcraft's cardinal_text before capitalizing. */
  private static String words(int n) {
    if (n < 20) return ONES[n];
    if (n < 100) return n % 10 == 0 ? TENS[n / 10] : TENS[n / 10] + "-" + ONES[n % 10];
    if (n < 1000) {
      String h = words(n / 100) + " hundred";
      return n % 100 == 0 ? h : h + " " + words(n % 100);
    }
    String th = words(n / 1000) + " thousand";
    return n % 1000 == 0 ? th : th + " " + words(n % 1000);
  }

  /** Capitalizes the first letter and each letter after a space or hyphen (Twenty-One). */
  private static String capitalize(String s) {
    var b = new StringBuilder(s.length());
    boolean up = true;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      b.append(up ? Character.toUpperCase(c) : c);
      up = c == ' ' || c == '-';
    }
    return b.toString();
  }

  private static String ordinalText(int number) {
    String c = words(Math.min(Math.max(number, 0), 999999));
    int cut = Math.max(c.lastIndexOf('-'), c.lastIndexOf(' '));
    String head = c.substring(0, cut + 1), last = c.substring(cut + 1);
    switch (last) {
      case "one": last = "first"; break;
      case "two": last = "second"; break;
      case "three": last = "third"; break;
      case "five": last = "fifth"; break;
      case "eight": last = "eighth"; break;
      case "nine": last = "ninth"; break;
      case "twelve": last = "twelfth"; break;
      default:
        last = last.endsWith("y") ? last.substring(0, last.length() - 1) + "ieth" : last + "th";
    }
    return capitalize(head + last);
  }
}
