package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Bounded, non-resolving XML reader that preserves text whitespace exactly. */
public final class Xml {
  private Xml() {}

  public static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  public static Element parse(byte[] bytes) throws IOException {
    String encoding = "UTF-8";
    if (bytes.length > 1
        && ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254
            || (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255)) encoding = "UTF-16";
    return parse(new String(bytes, encoding));
  }

  public static Element parse(String text) throws IOException {
    if (text.length() > 32 * 1024 * 1024) throw new IOException("XML part exceeds 32 MiB");
    return new Reader(text).read();
  }

  private static final class Reader {
    final String s;
    int pos, count;

    Reader(String s) {
      this.s = s;
    }

    IOException error(String m) {
      return new IOException(m + " at XML offset " + pos);
    }

    void whitespace() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    boolean nameStart(char c) {
      return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c == ':'
          || c > 127 && Character.isLetter(c);
    }

    String name() throws IOException {
      int start = pos;
      if (pos >= s.length()
          || !nameStart(s.charAt(pos)))
        throw error("Expected XML name");
      pos++;
      while (pos < s.length()) {
        char c = s.charAt(pos);
        if (nameStart(c) || c >= '0' && c <= '9' || c == '-' || c == '.'
            || c > 127 && Character.isDigit(c)) pos++;
        else break;
      }
      return s.substring(start, pos);
    }

    void expect(char c) throws IOException {
      if (pos >= s.length() || s.charAt(pos++) != c) throw error("Expected " + c);
    }

    boolean skip(String begin, String end) throws IOException {
      if (!s.startsWith(begin, pos)) return false;
      int finish = s.indexOf(end, pos + begin.length());
      if (finish < 0) throw error("Unterminated XML declaration/comment");
      pos = finish + end.length();
      return true;
    }

    Element read() throws IOException {
      if (s.startsWith("\ufeff")) pos++;
      whitespace();
      while (skip("<?", "?>") || skip("<!--", "-->")) whitespace();
      Element root = element(0);
      whitespace();
      while (skip("<!--", "-->")) whitespace();
      if (pos != s.length()) throw error("Trailing XML content");
      return root;
    }

    Element element(int depth) throws IOException {
      if (depth > 256 || ++count > 500000) throw error("XML complexity limit exceeded");
      if (s.startsWith("<!", pos)) throw error("DTD and entity declarations are not supported");
      expect('<');
      String tag = name();
      Element e = new Element(tag);
      while (true) {
        whitespace();
        if (s.startsWith("/>", pos)) {
          pos += 2;
          return e;
        }
        if (pos < s.length() && s.charAt(pos) == '>') {
          pos++;
          break;
        }
        String key = name();
        if (e.getAttribute(key) != null) throw error("Duplicate attribute");
        whitespace();
        expect('=');
        whitespace();
        if (pos >= s.length()) throw error("Missing attribute value");
        char quote = s.charAt(pos++);
        if (quote != '\"' && quote != '\'') throw error("Unquoted attribute");
        int end = s.indexOf(quote, pos);
        if (end < 0) throw error("Unterminated attribute");
        String raw = s.substring(pos, end);
        if (raw.indexOf('<') >= 0) throw error("Invalid attribute value");
        e.setAttribute(key, decode(raw));
        pos = end + 1;
      }
      while (pos < s.length()) {
        if (s.startsWith("</", pos)) {
          pos += 2;
          String close = name();
          whitespace();
          expect('>');
          if (!tag.equals(close)) throw error("Mismatched closing tag");
          return e;
        }
        if (skip("<!--", "-->")) continue;
        if (s.startsWith("<![CDATA[", pos)) {
          int end = s.indexOf("]]>", pos + 9);
          if (end < 0) throw error("Unterminated CDATA");
          e.addChild(new Element(s.substring(pos + 9, end), true));
          pos = end + 3;
          continue;
        }
        if (skip("<?", "?>")) continue;
        if (s.charAt(pos) == '<') e.addChild(element(depth + 1));
        else {
          int end = s.indexOf('<', pos);
          if (end < 0) throw error("Unterminated element");
          e.addChild(new Element(decode(s.substring(pos, end)), true));
          pos = end;
        }
      }
      throw error("Unterminated element");
    }

    String decode(String raw) throws IOException {
      StringBuilder b = null;
      for (int i = 0; i < raw.length(); i++) {
        char c = raw.charAt(i);
        if (c != '&') {
          if (c < 32 && c != '\t' && c != '\n' && c != '\r')
            throw error("Invalid XML control character");
          if (b != null) b.append(c);
          continue;
        }
        if (b == null) b = new StringBuilder(raw.length()).append(raw, 0, i);
        int end = raw.indexOf(';', i + 1);
        if (end < 0) throw error("Unterminated entity");
        String entity = raw.substring(i + 1, end);
        if ("amp".equals(entity)) b.append('&');
        else if ("lt".equals(entity)) b.append('<');
        else if ("gt".equals(entity)) b.append('>');
        else if ("quot".equals(entity)) b.append('\"');
        else if ("apos".equals(entity)) b.append('\'');
        else if (entity.startsWith("#")) {
          try {
            int cp =
                entity.startsWith("#x")
                    ? Integer.parseInt(entity.substring(2), 16)
                    : Integer.parseInt(entity.substring(1));
            if (cp < 0
                || cp > 0x10ffff
                || cp >= 0xd800 && cp <= 0xdfff
                || cp < 32 && cp != 9 && cp != 10 && cp != 13) throw new NumberFormatException();
            b.append(Character.toChars(cp));
          } catch (IllegalArgumentException ex) {
            throw error("Invalid numeric entity");
          }
        } else throw error("Unknown entity");
        i = end;
      }
      return b == null ? raw : b.toString();
    }
  }

  public static String local(Element e) {
    String n = e.getTagName();
    int i = n.indexOf(':');
    return i < 0 ? n : n.substring(i + 1);
  }

  public static String attr(Element e, String name, String fallback) {
    String v = e.getAttribute(name);
    if (v == null) v = e.getAttribute("w:" + name);
    if (v == null && e.getAttributes() != null)
      for (Object key : e.getAttributes().keySet()) {
        String k = (String) key;
        if (k.endsWith(":" + name)) {
          v = e.getAttribute(k);
          break;
        }
      }
    return v == null ? fallback : v;
  }

  public static Element child(Element e, String name) {
    for (int i = 0; i < e.getNumChildren(); i++) {
      Element c = e.getChildAt(i);
      if (!c.isTextElement() && local(c).equals(name)) return c;
    }
    return null;
  }

  public static List<Element> children(Element e, String name) {
    List<Element> a = new ArrayList<Element>();
    for (int i = 0; i < e.getNumChildren(); i++) {
      Element c = e.getChildAt(i);
      if (!c.isTextElement() && (name == null || local(c).equals(name))) a.add(c);
    }
    return a;
  }

  public static String text(Element e) {
    if (e.isTextElement()) return e.getText();
    if (e.getNumChildren() == 1 && e.getChildAt(0).isTextElement())
      return e.getChildAt(0).getText();
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < e.getNumChildren(); i++) b.append(text(e.getChildAt(i)));
    return b.toString();
  }
}
