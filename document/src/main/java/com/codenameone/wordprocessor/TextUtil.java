package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;

/** Small locale-independent text operations supported by the Codename One runtime. */
public final class TextUtil {
  private TextUtil() {}

  /** Literal delimiter, retaining empty fields including the final field. */
  public static String[] split(String text, char delimiter) {
    List<String> parts = new ArrayList<String>();
    int start = 0, end;
    while ((end = text.indexOf(delimiter, start)) >= 0) {
      parts.add(text.substring(start, end));
      start = end + 1;
    }
    parts.add(text.substring(start));
    return parts.toArray(new String[parts.size()]);
  }

  public static String padded(long value, int radix, int width) {
    String text = Long.toString(value, radix);
    StringBuilder out = new StringBuilder();
    for (int i = text.length(); i < width; i++) out.append('0');
    return out.append(text).toString();
  }

  /** ASCII hex digit; ParparVM's Character.digit accepts some non-ASCII letters. */
  public static boolean hex(char c) {
    return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
  }

  /** True when text is exactly length hex digits (String.matches is unavailable on ParparVM). */
  public static boolean hex(String text, int length) {
    if (text == null || text.length() != length) return false;
    for (int i = 0; i < length; i++) if (!hex(text.charAt(i))) return false;
    return true;
  }

  public static boolean digits(String text, int start) {
    if (start >= text.length()) return false;
    for (int i = start; i < text.length(); i++)
      if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
    return true;
  }

  public static String fileName(String title) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < title.length(); i++) {
      char c = title.charAt(i);
      out.append(
          c >= 'a' && c <= 'z'
                  || c >= 'A' && c <= 'Z'
                  || c >= '0' && c <= '9'
                  || c == '_'
                  || c == '-'
              ? c
              : '_');
    }
    return out.toString();
  }

  public static boolean commandId(String id) {
    if (id.length() < 2 || id.charAt(0) < 'a' || id.charAt(0) > 'z') return false;
    for (int i = 1; i < id.length(); i++) {
      char c = id.charAt(i);
      if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.'))
        return false;
    }
    return true;
  }
}
