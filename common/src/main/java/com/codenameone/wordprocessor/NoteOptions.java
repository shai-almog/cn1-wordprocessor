package com.codenameone.wordprocessor;

import java.util.*;

/** Immutable document defaults and sparse section overrides for automatic notes. */
public final class NoteOptions {
  public static final List<String> FORMATS = Collections.unmodifiableList(Arrays.asList(
      "decimal", "lowerRoman", "upperRoman", "lowerLetter", "upperLetter", "decimalZero",
      "ordinal", "hex", "chicago", "numberInDash", "none"));
  public static final class Properties {
    public final String numFmt, numRestart, pos;
    /** Zero inherits the document start; explicit starts are 1 through 32767. */
    public final int numStart;
    public Properties(String numFmt, int numStart, String numRestart, String pos) {
      if (numFmt != null && !FORMATS.contains(numFmt) || numStart < 0 || numStart > 32767
          || numRestart != null && !Arrays.asList("continuous", "eachSect", "eachPage").contains(numRestart)
          || pos != null && !Arrays.asList("pageBottom", "beneathText", "sectEnd", "docEnd").contains(pos))
        throw new IllegalArgumentException("Invalid note options");
      this.numFmt = numFmt; this.numStart = numStart; this.numRestart = numRestart; this.pos = pos;
    }
    public Properties resolve(Properties defaults) {
      return new Properties(numFmt == null ? defaults.numFmt : numFmt,
          numStart == 0 ? defaults.numStart : numStart,
          numRestart == null ? defaults.numRestart : numRestart, pos == null ? defaults.pos : pos);
    }
    public Properties clear(Properties changed) {
      return new Properties(changed.numFmt == null ? numFmt : null, changed.numStart == 0 ? numStart : 0,
          changed.numRestart == null ? numRestart : null, changed.pos == null ? pos : null);
    }
    public boolean isEmpty() { return numFmt == null && numStart == 0 && numRestart == null && pos == null; }
    void validate(String kind) {
      boolean foot = "footnote".equals(kind);
      if (!foot && "eachPage".equals(numRestart)
          || pos != null && !(foot ? Arrays.asList("pageBottom", "beneathText") : Arrays.asList("sectEnd", "docEnd")).contains(pos))
        throw new IllegalArgumentException("Invalid " + kind + " placement or restart");
    }
  }
  public static final Properties INHERIT = new Properties(null, 0, null, null);
  public static final NoteOptions EMPTY = new NoteOptions(INHERIT, INHERIT);
  public static final NoteOptions DEFAULT = new NoteOptions(
      new Properties("decimal", 1, "continuous", "pageBottom"),
      new Properties("lowerRoman", 1, "continuous", "docEnd"));
  public final Properties footnote, endnote;
  public NoteOptions(Properties footnote, Properties endnote) {
    if (footnote == null || endnote == null) throw new NullPointerException();
    footnote.validate("footnote"); endnote.validate("endnote");
    this.footnote = footnote; this.endnote = endnote;
  }
  public Properties get(String kind) {
    if ("footnote".equals(kind)) return footnote;
    if ("endnote".equals(kind)) return endnote;
    throw new IllegalArgumentException("Unknown note kind");
  }
  public NoteOptions with(String kind, Properties value) {
    get(kind);
    return "footnote".equals(kind) ? new NoteOptions(value, endnote) : new NoteOptions(footnote, value);
  }
  public NoteOptions resolve(NoteOptions defaults) {
    return new NoteOptions(footnote.resolve(defaults.footnote), endnote.resolve(defaults.endnote));
  }
  public static String label(int number, String format) {
    if ("none".equals(format)) return "";
    if ("hex".equals(format)) return Integer.toHexString(number).toUpperCase();
    if ("decimalZero".equals(format)) return number < 10 ? "0" + number : "" + number;
    if ("numberInDash".equals(format)) return "- " + number + " -";
    if ("ordinal".equals(format)) {
      int last = number % 10, pair = number % 100;
      return number + (pair >= 11 && pair <= 13 ? "th" : last == 1 ? "st" : last == 2 ? "nd" : last == 3 ? "rd" : "th");
    }
    if ("chicago".equals(format) || format.endsWith("Letter")) {
      String alphabet = "chicago".equals(format) ? "*†‡§" : "abcdefghijklmnopqrstuvwxyz";
      int repeat = Math.min(40, (number - 1) / alphabet.length() + 1);
      var out = new StringBuilder();
      for (int i = 0; i < repeat; i++) out.append(alphabet.charAt((number - 1) % alphabet.length()));
      return "upperLetter".equals(format) ? out.toString().toUpperCase() : out.toString();
    }
    return ListNumbers.format(number, format);
  }
}
