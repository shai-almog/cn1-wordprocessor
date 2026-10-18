package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Bounded, non-resolving XML reader that preserves text whitespace exactly. */
public final class Xml {
  private Xml() {}

  /**
   * Parsed element with unsynchronized attributes. Element keeps attributes in a Hashtable;
   * on ParparVM every Hashtable owns a monitor that the collector tears down under a global
   * lock, which made attribute storage the largest cost of opening a document natively.
   */
  static final class Node extends Element {
    /** Attribute names and values, alternating; elements carry only a handful. */
    String[] attrs;
    int attrCount;
    /** Tag without its namespace prefix, pooled at parse time. */
    String local;

    Node(String tag, String local) {
      super(tag);
      this.local = local;
    }

    int index(String name) {
      for (int i = 0; i < attrCount; i += 2) if (attrs[i].equals(name)) return i;
      return -1;
    }

    public int setAttribute(String name, String value) {
      int i = index(name);
      if (i >= 0) {
        attrs[i + 1] = value;
        return -1;
      }
      if (attrs == null) attrs = new String[4];
      else if (attrCount == attrs.length) attrs = Arrays.copyOf(attrs, attrCount * 2);
      attrs[attrCount++] = name;
      attrs[attrCount++] = value;
      return -1;
    }

    public String getAttribute(String name) {
      int i = index(name);
      if (i < 0 && attrCount > 0) i = index(name.toLowerCase());
      return i < 0 ? null : attrs[i + 1];
    }

    public void removeAttribute(String name) {
      int i = index(name);
      if (i < 0) return;
      System.arraycopy(attrs, i + 2, attrs, i, attrCount - i - 2);
      attrCount -= 2;
      attrs[attrCount] = attrs[attrCount + 1] = null;
    }

    public Hashtable getAttributes() {
      if (attrCount == 0) return null;
      var h = new Hashtable();
      for (int i = 0; i < attrCount; i += 2) h.put(attrs[i], attrs[i + 1]);
      return h;
    }
  }

  /** True when a and b have the same tags, attributes and text, recursively. */
  static boolean same(Element a, Element b) {
    if (a == b) return true;
    if (a.isTextElement() || b.isTextElement())
      return a.isTextElement() && b.isTextElement() && a.getText().equals(b.getText());
    if (!a.getTagName().equals(b.getTagName()) || a.getNumChildren() != b.getNumChildren())
      return false;
    if (a instanceof Node x && b instanceof Node y) {
      if (x.attrCount != y.attrCount) return false;
      for (int i = 0; i < x.attrCount; i += 2) {
        int j = y.index(x.attrs[i]);
        if (j < 0 || !x.attrs[i + 1].equals(y.attrs[j + 1])) return false;
      }
    } else {
      Hashtable p = a.getAttributes(), q = b.getAttributes();
      if (p == null ? q != null && !q.isEmpty() : !p.equals(q == null ? new Hashtable() : q))
        return false;
    }
    for (int i = 0; i < a.getNumChildren(); i++)
      if (!same(a.getChildAt(i), b.getChildAt(i))) return false;
    return true;
  }

  /** Order-independent hash consistent with {@link #same}. */
  static int shape(Element e) {
    if (e.isTextElement()) return e.getText().hashCode();
    int h = e.getTagName().hashCode();
    if (e instanceof Node n) {
      for (int i = 0; i < n.attrCount; i += 2) h += n.attrs[i].hashCode() ^ n.attrs[i + 1].hashCode();
    } else if (e.getAttributes() != null) h += e.getAttributes().hashCode();
    for (int i = 0; i < e.getNumChildren(); i++) h = h * 31 + shape(e.getChildAt(i));
    return h;
  }

  public static String escape(String s) {
    // Most values need no escaping; String.replace compiles a pattern per call on some VMs.
    boolean plain = true;
    for (int i = 0; i < s.length() && plain; i++) {
      char c = s.charAt(i);
      plain = c != '&' && c != '<' && c != '>' && c != '"' && c != '\'';
    }
    if (plain) return s;
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  /** XML attribute whitespace must use references to survive standard reader normalization. */
  public static String attribute(String value) {
    return escape(value).replace("\t", "&#9;").replace("\n", "&#10;").replace("\r", "&#13;");
  }

  static String write(Element element) {
    var out = new StringBuilder();
    write(element, out);
    return out.toString();
  }

  private static void write(Element element, StringBuilder out) {
    if (element.isTextElement()) {
      out.append(escape(element.getText()));
      return;
    }
    out.append('<').append(element.getTagName());
    if (element instanceof Node n) {
      for (int i = 0; i < n.attrCount; i += 2)
        out.append(' ').append(n.attrs[i]).append("=\"").append(escape(n.attrs[i + 1])).append('"');
    } else {
      var attributes = element.getAttributes();
      if (attributes != null)
        for (Object key : attributes.keySet()) {
          var name = (String) key;
          out.append(' ')
              .append(name)
              .append("=\"")
              .append(escape((String) attributes.get(name)))
              .append('"');
        }
    }
    out.append('>');
    for (int i = 0; i < element.getNumChildren(); i++) write(element.getChildAt(i), out);
    out.append("</").append(element.getTagName()).append('>');
  }

  public static Element parse(byte[] bytes) throws IOException {
    var encoding = "UTF-8";
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
    // The hot loops index this copy directly: String.charAt is a checked call per character
    // on ParparVM, and a part is scanned character by character.
    final char[] c;
    final int n;
    int pos, count;

    Reader(String s) {
      this.s = s;
      c = s.toCharArray();
      n = c.length;
    }

    IOException error(String m) {
      return new IOException(m + " at XML offset " + pos);
    }

    void whitespace() {
      while (pos < n) {
        char ch = c[pos];
        if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch > 127 && Character.isWhitespace(ch)) pos++;
        else if (ch < 32 && Character.isWhitespace(ch)) pos++;
        else break;
      }
    }

    boolean nameStart(char c) {
      return c >= 'a' && c <= 'z'
          || c >= 'A' && c <= 'Z'
          || c == '_'
          || c == ':'
          || c > 127 && Character.isLetter(c);
    }

    String name() throws IOException {
      int start = pos;
      if (pos >= n || !nameStart(c[pos])) throw error("Expected XML name");
      pos++;
      while (pos < n) {
        char ch = c[pos];
        if (nameStart(ch)
            || ch >= '0' && ch <= '9'
            || ch == '-'
            || ch == '.'
            || ch > 127 && Character.isDigit(ch)) pos++;
        else break;
      }
      return shared(start, pos);
    }

    /** Pool of short strings: names and small values repeat thousands of times per part. */
    private final String[] pool = new String[1024];

    String shared(int start, int end) {
      int len = end - start;
      if (len > 24) return s.substring(start, end);
      int h = len;
      for (int i = start; i < end; i++) h = 31 * h + c[i];
      int slot = (h ^ (h >>> 16)) & (pool.length - 1);
      var cached = pool[slot];
      if (cached != null && cached.length() == len && s.regionMatches(start, cached, 0, len))
        return cached;
      var v = new String(c, start, len);
      pool[slot] = v;
      return v;
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
      var root = element(0);
      whitespace();
      while (skip("<!--", "-->")) whitespace();
      if (pos != s.length()) throw error("Trailing XML content");
      return root;
    }

    Element element(int depth) throws IOException {
      if (depth > 256 || ++count > 500000) throw error("XML complexity limit exceeded");
      if (s.startsWith("<!", pos)) throw error("DTD and entity declarations are not supported");
      expect('<');
      int nameStart = pos;
      var tag = name();
      int colon = tag.indexOf(':');
      var e = new Node(tag, colon < 0 ? tag : shared(nameStart + colon + 1, pos));
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
        var key = name();
        if (((Node) e).index(key) >= 0) throw error("Duplicate attribute");
        whitespace();
        expect('=');
        whitespace();
        if (pos >= s.length()) throw error("Missing attribute value");
        char quote = s.charAt(pos++);
        if (quote != '\"' && quote != '\'') throw error("Unquoted attribute");
        int end = pos;
        while (end < n && c[end] != quote) end++;
        if (end >= n) throw error("Unterminated attribute");
        e.setAttribute(key, value(pos, end, true));
        pos = end + 1;
      }
      while (pos < s.length()) {
        if (s.startsWith("</", pos)) {
          pos += 2;
          var close = name();
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
        if (c[pos] == '<') e.addChild(element(depth + 1));
        else {
          int end = pos;
          while (end < n && c[end] != '<') end++;
          if (end >= n) throw error("Unterminated element");
          e.addChild(new Element(value(pos, end, false), true));
          pos = end;
        }
      }
      throw error("Unterminated element");
    }

    /**
     * The decoded text of [start, end): validated in place, and only copied out (pooled for
     * attributes) when it holds no entity, which is nearly every value in OOXML.
     */
    String value(int start, int end, boolean attribute) throws IOException {
      boolean entity = false;
      for (int i = start; i < end; i++) {
        char ch = c[i];
        if (ch < 32) {
          if (ch != '\t' && ch != '\n' && ch != '\r') throw error("Invalid XML control character");
        } else if (ch == '&') entity = true;
        else if (ch == '<' && attribute) throw error("Invalid attribute value");
      }
      if (entity) return decode(new String(c, start, end - start));
      return attribute ? shared(start, end) : new String(c, start, end - start);
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
        var entity = raw.substring(i + 1, end);
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
    if (e instanceof Node node) return node.local;
    var n = e.getTagName();
    int i = n.indexOf(':');
    return i < 0 ? n : n.substring(i + 1);
  }

  public static String attr(Element e, String name, String fallback) {
    // Exact lookups: Element.getAttribute lower-cases the name on every call, which made
    // attribute reads the largest cost of opening a document. OOXML names are case-exact.
    if (e instanceof Node n) {
      int i = n.index(name);
      if (i < 0) i = n.index("w:" + name);
      for (int j = 0; i < 0 && j < n.attrCount; j += 2) {
        var k = n.attrs[j];
        if (k.length() > name.length()
            && k.endsWith(name)
            && k.charAt(k.length() - name.length() - 1) == ':') i = j;
      }
      return i < 0 ? fallback : n.attrs[i + 1];
    }
    var attributes = e.getAttributes();
    if (attributes == null) return fallback;
    var v = (String) attributes.get(name);
    if (v == null) v = (String) attributes.get("w:" + name);
    if (v == null)
      for (Object key : attributes.keySet()) {
        var k = (String) key;
        if (k.endsWith(":" + name)) {
          v = (String) attributes.get(k);
          break;
        }
      }
    return v == null ? fallback : v;
  }


  public static Element child(Element e, String name) {
    for (int i = 0; i < e.getNumChildren(); i++) {
      var c = e.getChildAt(i);
      if (!c.isTextElement() && local(c).equals(name)) return c;
    }
    return null;
  }

  public static List<Element> children(Element e, String name) {
    var a = new ArrayList<Element>();
    for (int i = 0; i < e.getNumChildren(); i++) {
      var c = e.getChildAt(i);
      if (!c.isTextElement() && (name == null || local(c).equals(name))) a.add(c);
    }
    return a;
  }

  public static String text(Element e) {
    if (e.isTextElement()) return e.getText();
    if (e.getNumChildren() == 1 && e.getChildAt(0).isTextElement())
      return e.getChildAt(0).getText();
    var b = new StringBuilder();
    for (int i = 0; i < e.getNumChildren(); i++) b.append(text(e.getChildAt(i)));
    return b.toString();
  }
}
