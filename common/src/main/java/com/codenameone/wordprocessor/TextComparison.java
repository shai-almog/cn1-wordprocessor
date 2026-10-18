package com.codenameone.wordprocessor;

import java.util.*;

/** Bounded text comparison. Offsets are UTF-16 boundaries; surrogate pairs are never split. */
public final class TextComparison {
  private TextComparison() {}

  public static final class Edit {
    public final int from, to, revisedFrom, revisedTo;
    public final boolean same;

    Edit(int from, int to, int a, int b, boolean same) {
      this.from = from;
      this.to = to;
      revisedFrom = a;
      revisedTo = b;
      this.same = same;
    }
  }

  public static final class Result {
    public final List<Edit> edits;
    public final boolean coarse;

    Result(List<Edit> edits, boolean coarse) {
      this.edits = Collections.unmodifiableList(edits);
      this.coarse = coarse;
    }
  }

  private static final class Token {
    final int start, end;
    final String key;

    Token(String text, int start, int end, boolean caseChanges, boolean whiteSpace) {
      this.start = start;
      this.end = end;
      var value = text.substring(start, end);
      if (!whiteSpace && whitespace(value)) value = " ";
      key = caseChanges ? value : value.toLowerCase();
    }
  }

  private static boolean whitespace(String text) {
    for (int i = 0; i < text.length(); i++)
      if (!Character.isWhitespace(text.charAt(i))) return false;
    return true;
  }

  private static int codeEnd(String s, int i) {
    return i + 1 < s.length()
            && s.charAt(i) >= 0xd800
            && s.charAt(i) <= 0xdbff
            && s.charAt(i + 1) >= 0xdc00
            && s.charAt(i + 1) <= 0xdfff
        ? i + 2
        : i + 1;
  }

  private static int type(char c) {
    return Character.isWhitespace(c) ? 0 : Unicode.letterOrDigit(c) || c == '_' ? 1 : 2;
  }

  private static List<Token> tokens(String s, boolean chars, boolean cases, boolean space) {
    if (s.length() > 2000000)
      throw new IllegalArgumentException(
          "Comparison is limited to two million characters per document");
    var out = new ArrayList<Token>();
    for (int i = 0; i < s.length(); ) {
      int end = codeEnd(s, i), type = type(s.charAt(i));
      if ((!chars && type < 2) || !space && type == 0)
        while (end < s.length() && type(s.charAt(end)) == type) end = codeEnd(s, end);
      out.add(new Token(s, i, end, cases, space));
      i = end;
      if (out.size() > 200000)
        throw new IllegalArgumentException(
            "Comparison has too many text tokens; use word-level comparison");
    }
    return out;
  }

  public static Result compare(
      String original,
      String revised,
      boolean characters,
      boolean caseChanges,
      boolean whiteSpace) {
    List<Token> a = tokens(original, characters, caseChanges, whiteSpace),
        b = tokens(revised, characters, caseChanges, whiteSpace);
    int prefix = 0;
    while (prefix < a.size() && prefix < b.size() && a.get(prefix).key.equals(b.get(prefix).key))
      prefix++;
    int ae = a.size(), be = b.size();
    while (ae > prefix && be > prefix && a.get(ae - 1).key.equals(b.get(be - 1).key)) {
      ae--;
      be--;
    }
    var matches = new ArrayList<int[]>();
    for (int i = 0; i < prefix; i++) matches.add(new int[] {i, i});
    int n = ae - prefix, m = be - prefix;
    boolean coarse = (long) n * m > 4000000;
    if (!coarse && n > 0 && m > 0) {
      int[][] lcs = new int[n + 1][m + 1];
      for (int i = n - 1; i >= 0; i--)
        for (int j = m - 1; j >= 0; j--)
          lcs[i][j] =
              a.get(prefix + i).key.equals(b.get(prefix + j).key)
                  ? 1 + lcs[i + 1][j + 1]
                  : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      int i = 0, j = 0;
      while (i < n && j < m) {
        if (a.get(prefix + i).key.equals(b.get(prefix + j).key)) {
          matches.add(new int[] {prefix + i++, prefix + j++});
        } else if (lcs[i + 1][j] >= lcs[i][j + 1]) i++;
        else j++;
      }
    }
    while (ae < a.size()) matches.add(new int[] {ae++, be++});
    var edits = new ArrayList<Edit>();
    int from = 0, to = 0;
    for (int[] match : matches) {
      Token x = a.get(match[0]), y = b.get(match[1]);
      if (from != x.start || to != y.start) edits.add(new Edit(from, x.start, to, y.start, false));
      if (!edits.isEmpty() && edits.get(edits.size() - 1).same) {
        var prior = edits.remove(edits.size() - 1);
        edits.add(new Edit(prior.from, x.end, prior.revisedFrom, y.end, true));
      } else edits.add(new Edit(x.start, x.end, y.start, y.end, true));
      from = x.end;
      to = y.end;
    }
    if (from != original.length() || to != revised.length())
      edits.add(new Edit(from, original.length(), to, revised.length(), false));
    return new Result(edits, coarse);
  }
}
