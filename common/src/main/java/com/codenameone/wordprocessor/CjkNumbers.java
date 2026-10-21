package com.codenameone.wordprocessor;

/**
 * East Asian list number formats, ported from Wordcraft's doc::cjk_numbers (counting systems
 * with ten/hundred/thousand units and myriad groups, digit sets, cyclic sets and enclosed
 * numbers). Values outside a set show decimal, as there.
 */
final class CjkNumbers {
  private CjkNumbers() {}

  private static final int NEVER = 0, LEADING_TEN = 1, UNITS = 2;

  private static final String CN = "\u3007\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d";
  private static final String CHINESE_LEGAL = "\u96f6\u58f9\u8d30\u53c1\u8086\u4f0d\u9646\u67d2\u634c\u7396";
  private static final String TRAD_LEGAL = "\u96f6\u58f9\u8cb3\u53c3\u8086\u4f0d\u9678\u67d2\u634c\u7396";
  private static final String JA_LEGAL = "\u3007\u58f1\u5f10\u53c2\u56db\u4f0d\u516d\u4e03\u516b\u4e5d";
  private static final String KO = "\uc601\uc77c\uc774\uc0bc\uc0ac\uc624\uc721\uce60\ud314\uad6c";
  private static final String STEMS = "\u7532\u4e59\u4e19\u4e01\u620a\u5df1\u5e9a\u8f9b\u58ec\u7678";
  private static final String BRANCHES = "\u5b50\u4e11\u5bc5\u536f\u8fb0\u5df3\u5348\u672a\u7533\u9149\u620c\u4ea5";
  private static final String AIUEO = "\u30a2\u30a4\u30a6\u30a8\u30aa\u30ab\u30ad\u30af\u30b1\u30b3\u30b5\u30b7\u30b9\u30bb\u30bd\u30bf\u30c1\u30c4\u30c6\u30c8\u30ca\u30cb\u30cc\u30cd\u30ce\u30cf\u30d2\u30d5\u30d8\u30db\u30de\u30df\u30e0\u30e1\u30e2\u30e4\u30e6\u30e8\u30e9\u30ea\u30eb\u30ec\u30ed\u30ef\u30f2\u30f3";
  private static final String AIUEO_HALF = "\uff71\uff72\uff73\uff74\uff75\uff76\uff77\uff78\uff79\uff7a\uff7b\uff7c\uff7d\uff7e\uff7f\uff80\uff81\uff82\uff83\uff84\uff85\uff86\uff87\uff88\uff89\uff8a\uff8b\uff8c\uff8d\uff8e\uff8f\uff90\uff91\uff92\uff93\uff94\uff95\uff96\uff97\uff98\uff99\uff9a\uff9b\uff9c\uff66\uff9d";
  private static final String IROHA = "\u30a4\u30ed\u30cf\u30cb\u30db\u30d8\u30c8\u30c1\u30ea\u30cc\u30eb\u30f2\u30ef\u30ab\u30e8\u30bf\u30ec\u30bd\u30c4\u30cd\u30ca\u30e9\u30e0\u30a6\u30f0\u30ce\u30aa\u30af\u30e4\u30de\u30b1\u30d5\u30b3\u30a8\u30c6\u30a2\u30b5\u30ad\u30e6\u30e1\u30df\u30b7\u30f1\u30d2\u30e2\u30bb\u30b9";
  private static final String IROHA_HALF = "\uff72\uff9b\uff8a\uff86\uff8e\uff8d\uff84\uff81\uff98\uff87\uff99\uff66\uff9c\uff76\uff96\uff80\uff9a\uff7f\uff82\uff88\uff85\uff97\uff91\uff73\u30f0\uff89\uff75\uff78\uff94\uff8f\uff79\uff8c\uff7a\uff74\uff83\uff71\uff7b\uff77\uff95\uff92\uff90\uff7c\u30f1\uff8b\uff93\uff7e\uff7d";
  private static final String GANADA = "\uac00\ub098\ub2e4\ub77c\ub9c8\ubc14\uc0ac\uc544\uc790\ucc28\uce74\ud0c0\ud30c\ud558";
  private static final String CHOSUNG = "\u3131\u3134\u3137\u3139\u3141\u3142\u3145\u3147\u3148\u314a\u314b\u314c\u314d\u314e";
  private static final String[] KO_ONES = {"", "\ud558\ub098", "\ub458", "\uc14b", "\ub137", "\ub2e4\uc12f", "\uc5ec\uc12f", "\uc77c\uacf1", "\uc5ec\ub35f", "\uc544\ud649"};
  private static final String[] KO_TENS = {"", "\uc5f4", "\uc2a4\ubb3c", "\uc11c\ub978", "\ub9c8\ud754", "\uc270", "\uc608\uc21c", "\uc77c\ud754", "\uc5ec\ub4e0", "\uc544\ud754"};

  private static final class Counting {
    final String digits, units, myriads;
    final char zero;
    final int omitOne;

    Counting(String digits, String units, String myriads, char zero, int omitOne) {
      this.digits = digits;
      this.units = units;
      this.myriads = myriads;
      this.zero = zero;
      this.omitOne = omitOne;
    }

    String format(long n) {
      if (n == 0) return String.valueOf(zero != 0 ? zero : '\u3007');
      long[] groups = {n / 100000000L, (n / 10000) % 10000, n % 10000};
      var out = new StringBuilder();
      boolean zeroPending = false;
      for (int gi = 0; gi < 3; gi++) {
        long g = groups[gi];
        if (g == 0) {
          zeroPending |= out.length() > 0;
          continue;
        }
        long[] ds = {g / 1000, (g / 100) % 10, (g / 10) % 10, g % 10};
        for (int pos = 0; pos < 4; pos++) {
          int d = (int) ds[pos];
          if (d == 0) {
            zeroPending |= out.length() > 0;
            continue;
          }
          if (zeroPending && zero != 0) out.append(zero);
          zeroPending = false;
          char unit = pos == 0 ? units.charAt(2) : pos == 1 ? units.charAt(1) : pos == 2 ? units.charAt(0) : 0;
          boolean omit = d == 1 && unit != 0
              && (omitOne == UNITS || omitOne == LEADING_TEN && pos == 2 && out.length() == 0);
          if (!omit) out.append(digits.charAt(d));
          if (unit != 0) out.append(unit);
        }
        if (gi == 0) out.append(myriads.charAt(1));
        else if (gi == 1) out.append(myriads.charAt(0));
      }
      return out.toString();
    }
  }

  private static final Counting CHINESE = new Counting(CN, "\u5341\u767e\u5343", "\u4e07\u4ebf", (char) 0x96f6, LEADING_TEN);
  private static final Counting TAIWANESE = new Counting(CN, "\u5341\u767e\u5343", "\u842c\u5104", (char) 0x96f6, LEADING_TEN);
  private static final Counting CHINESE_LEGAL_C = new Counting(CHINESE_LEGAL, "\u62fe\u4f70\u4edf", "\u4e07\u4ebf", (char) 0x96f6, NEVER);
  private static final Counting TRAD_LEGAL_C = new Counting(TRAD_LEGAL, "\u62fe\u4f70\u4edf", "\u842c\u5104", (char) 0x96f6, NEVER);
  private static final Counting JAPANESE = new Counting(CN, "\u5341\u767e\u5343", "\u4e07\u5104", (char) 0, UNITS);
  private static final Counting JAPANESE_LEGAL = new Counting(JA_LEGAL, "\u62fe\u767e\u9621", "\u842c\u5104", (char) 0, NEVER);
  private static final Counting KOREAN = new Counting(KO, "\uc2ed\ubc31\ucc9c", "\ub9cc\uc5b5", (char) 0, UNITS);

  /** Format names this class draws. */
  static final java.util.List<String> FORMATS = java.util.Arrays.asList(
      "ideographDigital", "koreanDigital2", "taiwaneseDigital", "japaneseCounting", "japaneseLegal",
      "japaneseDigitalTenThousand", "aiueo", "aiueoFullWidth", "iroha", "irohaFullWidth",
      "decimalFullWidth", "decimalFullWidth2", "decimalHalfWidth", "decimalEnclosedCircle",
      "decimalEnclosedCircleChinese", "decimalEnclosedFullstop", "decimalEnclosedParen",
      "ideographEnclosedCircle", "ideographTraditional", "ideographZodiac", "ideographZodiacTraditional",
      "ideographLegalTraditional", "taiwaneseCounting", "taiwaneseCountingThousand", "chineseCounting",
      "chineseCountingThousand", "chineseLegalSimplified", "koreanCounting", "koreanDigital",
      "koreanLegal", "ganada", "chosung");

  /** n drawn in format, or null when format is not one of {@link #FORMATS}. */
  static String format(int n, String format) {
    if (n < 0) return String.valueOf(n);
    switch (format) {
      case "ideographDigital":
      case "koreanDigital2":
      case "taiwaneseDigital":
        return digits(n, CN);
      case "japaneseCounting": return JAPANESE.format(n);
      case "japaneseLegal": return JAPANESE_LEGAL.format(n);
      case "japaneseDigitalTenThousand": return tenThousand(n);
      case "aiueo": return nth(AIUEO_HALF, n);
      case "aiueoFullWidth": return nth(AIUEO, n);
      case "iroha": return nth(IROHA_HALF, n);
      case "irohaFullWidth": return nth(IROHA, n);
      case "decimalFullWidth":
      case "decimalFullWidth2": {
        String s = String.valueOf(n);
        var b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) b.append((char) (0xFF10 + s.charAt(i) - '0'));
        return b.toString();
      }
      case "decimalHalfWidth": return String.valueOf(n);
      case "decimalEnclosedCircle": return enclosed(n, 0x2460, 20);
      case "decimalEnclosedCircleChinese": return enclosed(n, 0x2460, 10);
      case "decimalEnclosedFullstop": return enclosed(n, 0x2488, 20);
      case "decimalEnclosedParen": return enclosed(n, 0x2474, 20);
      case "ideographEnclosedCircle": return enclosed(n, 0x3280, 10);
      case "ideographTraditional": return nth(STEMS, n);
      case "ideographZodiac": return nth(BRANCHES, n);
      case "ideographZodiacTraditional":
        if (n < 1 || n > 60) return String.valueOf(n);
        return "" + STEMS.charAt((n - 1) % 10) + BRANCHES.charAt((n - 1) % 12);
      case "ideographLegalTraditional": return TRAD_LEGAL_C.format(n);
      case "taiwaneseCounting": return n < 100 ? TAIWANESE.format(n) : digits(n, CN);
      case "taiwaneseCountingThousand": return TAIWANESE.format(n);
      case "chineseCounting": return n < 100 ? CHINESE.format(n) : digits(n, CN);
      case "chineseCountingThousand": return CHINESE.format(n);
      case "chineseLegalSimplified": return CHINESE_LEGAL_C.format(n);
      case "koreanCounting": return KOREAN.format(n);
      case "koreanDigital": return digits(n, KO);
      case "koreanLegal":
        if (n == 0 || n > 99) return String.valueOf(n);
        return KO_TENS[n / 10] + KO_ONES[n % 10];
      case "ganada": return nth(GANADA, n);
      case "chosung": return nth(CHOSUNG, n);
      default: return null;
    }
  }

  private static String digits(long n, String set) {
    String s = String.valueOf(n);
    var b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) b.append(set.charAt(s.charAt(i) - '0'));
    return b.toString();
  }

  private static String tenThousand(long n) {
    long[] groups = {n / 100000000L, (n / 10000) % 10000, n % 10000};
    char[] marks = {'\u5104', '\u4e07', 0};
    var out = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      String s = digits(groups[i], CN);
      if (out.length() == 0) {
        if (groups[i] == 0 && marks[i] != 0) continue;
      } else for (int k = s.length(); k < 4; k++) out.append('\u3007');
      out.append(s);
      if (marks[i] != 0) out.append(marks[i]);
    }
    return out.toString();
  }

  private static String nth(String set, int n) {
    return n >= 1 && n <= set.length() ? String.valueOf(set.charAt(n - 1)) : String.valueOf(n);
  }

  private static String enclosed(int n, int first, int count) {
    return n >= 1 && n <= count ? String.valueOf((char) (first + n - 1)) : String.valueOf(n);
  }
}
