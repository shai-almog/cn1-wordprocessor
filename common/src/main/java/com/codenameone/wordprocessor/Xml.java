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
  /**
   * Whether a parsed part may contain an element with this local name (24 characters or fewer).
   * False means it certainly does not, so readers skip searching each paragraph for it.
   */
  static boolean mayContain(Element root, String local) {
    return !(root instanceof Node) || ((Node) root).vocabulary == null || ((Node) root).vocabulary.contains(local);
  }

  static final class Node extends Element {
    /** Attribute names and values, alternating; elements carry only a handful. */
    String[] attrs;
    int attrCount;
    /** Tag without its namespace prefix, pooled at parse time. */
    String local;
    /** On a parsed root only: every short name and value the part contains. */
    Set<String> vocabulary;

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
    if (!"UTF-8".equals(encoding)) return parse(new String(bytes, encoding));
    if (bytes.length > 32 * 1024 * 1024) throw new IOException("XML part exceeds 32 MiB");
    // Decoded straight into the reader's buffer: ParparVM's String(byte[], "UTF-8") decodes
    // through generic charset code, and the reader then copied the String to a char[] again.
    char[] chars = new char[bytes.length];
    boolean[] wide = {false};
    int n = decodeUtf8(bytes, chars, wide);
    var reader = new Reader(chars, n);
    // A part of Latin-1 characters (nearly every OOXML part) becomes one compact String that
    // names, values and text are sliced from: ParparVM copies a compact slice's bytes, where a
    // String built from char[] scans and packs every character again. A part with any wider
    // character keeps the char[] constructor, which still packs each Latin-1 string on its own.
    if (!wide[0]) reader.source = new String(chars, 0, n);
    return reader.read();
  }

  /**
   * Decodes UTF-8 into {@code out} (at least bytes.length long) and returns the char count.
   * Malformed input becomes U+FFFD exactly as the JDK decoder replaces it: one replacement per
   * maximal invalid prefix (Unicode's "maximal subpart" rule), the next byte decoded afresh.
   */
  static int decodeUtf8(byte[] bytes, char[] out) {
    return decodeUtf8(bytes, out, new boolean[1]);
  }

  /** Sets {@code wide[0]} when a decoded character is above U+00FF. */
  static int decodeUtf8(byte[] bytes, char[] out, boolean[] wide) {
    int n = 0, length = bytes.length;
    for (int i = 0; i < length; ) {
      int b = bytes[i];
      if (b >= 0) {
        out[n++] = (char) b;
        i++;
        continue;
      }
      b &= 0xff;
      int need, low = 0x80, high = 0xbf, cp;
      if (b >= 0xc2 && b <= 0xdf) {
        need = 1;
        cp = b & 0x1f;
      } else if (b >= 0xe0 && b <= 0xef) {
        need = 2;
        cp = b & 0x0f;
        if (b == 0xe0) low = 0xa0; // 0xed keeps the full range: see the surrogate case below
      } else if (b >= 0xf0 && b <= 0xf4) {
        need = 3;
        cp = b & 0x07;
        if (b == 0xf0) low = 0x90;
        else if (b == 0xf4) high = 0x8f;
      } else {
        out[n++] = '\ufffd';
        wide[0] = true;
        i++;
        continue;
      }
      int j = i + 1;
      boolean ok = true;
      for (int k = 0; k < need; k++, j++) {
        int c = j < length ? bytes[j] & 0xff : -1;
        // Only the first continuation byte has the narrowed range.
        if (c < (k == 0 ? low : 0x80) || c > (k == 0 ? high : 0xbf)) {
          ok = false;
          break;
        }
        cp = cp << 6 | c & 0x3f;
      }
      if (!ok) {
        out[n++] = '\ufffd';
        wide[0] = true;
        i = j;
      } else if (cp >= 0xd800 && cp <= 0xdfff) {
        out[n++] = '\ufffd'; // JDK 17 replaces a whole encoded surrogate with one character
        wide[0] = true;
        i = j;
      } else if (cp >= 0x10000) {
        wide[0] = true;
        out[n++] = (char) (0xd7c0 + (cp >> 10));
        out[n++] = (char) (0xdc00 | cp & 0x3ff);
        i = j;
      } else {
        out[n++] = (char) cp;
        if (cp > 0xff) wide[0] = true;
        i = j;
      }
    }
    return n;
  }

  public static Element parse(String text) throws IOException {
    if (text.length() > 32 * 1024 * 1024) throw new IOException("XML part exceeds 32 MiB");
    return new Reader(text.toCharArray(), text.length()).read();
  }

  private static final class Reader {
    // The hot loops index this copy directly: String.charAt is a checked call per character
    // on ParparVM, and a part is scanned character by character.
    final char[] c;
    final int n;
    int pos, count;
    /** The whole part as one compact String when every character is Latin-1, else null. */
    String source;

    String text(int start, int length) {
      return source != null ? source.substring(start, start + length) : new String(c, start, length);
    }

    Reader(char[] c, int n) {
      this.c = c;
      this.n = n;
    }

    /** Whether t occurs at {@code at}. */
    boolean starts(String t, int at) {
      if (at < 0 || at + t.length() > n) return false;
      for (int i = 0; i < t.length(); i++) if (c[at + i] != t.charAt(i)) return false;
      return true;
    }

    /** The index of t at or after {@code from}, or -1. */
    int find(String t, int from) {
      char first = t.charAt(0);
      for (int i = from, last = n - t.length(); i <= last; i++) if (c[i] == first && starts(t, i)) return i;
      return -1;
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
          || c > 127 && Unicode.letter(c);
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
    // Each pooled string's characters, compared against the buffer directly: String methods
    // (regionMatches, charAt) are checked calls per character on ParparVM.
    private final char[][] poolChars = new char[1024][];
    // Every pool miss: each distinct short name (so every element's local name) at least once.
    final Set<String> vocabulary = new HashSet<>();

    String shared(int start, int end) {
      int len = end - start;
      if (len > 24) return text(start, len);
      int h = len;
      for (int i = start; i < end; i++) h = 31 * h + c[i];
      int slot = (h ^ (h >>> 16)) & (pool.length - 1);
      char[] chars = poolChars[slot];
      if (chars != null && chars.length == len) {
        int i = 0;
        while (i < len && chars[i] == c[start + i]) i++;
        if (i == len) return pool[slot];
      }
      var v = text(start, len);
      vocabulary.add(v);
      pool[slot] = v;
      poolChars[slot] = java.util.Arrays.copyOfRange(c, start, end);
      return v;
    }

    void expect(char ch) throws IOException {
      if (pos >= n || c[pos++] != ch) throw error("Expected " + ch);
    }

    /** Whether the buffer holds a, b at pos. */
    boolean at(char a, char b) {
      return pos + 1 < n && c[pos] == a && c[pos + 1] == b;
    }

    boolean skip(String begin, String end) throws IOException {
      // Every construct skipped here starts with '<' and a second character that a tag lacks.
      if (!at(begin.charAt(0), begin.charAt(1)) || !starts(begin, pos)) return false;
      int finish = find(end, pos + begin.length());
      if (finish < 0) throw error("Unterminated XML declaration/comment");
      pos = finish + end.length();
      return true;
    }

    Element read() throws IOException {
      if (n > 0 && c[0] == '\ufeff') pos++;
      whitespace();
      while (skip("<?", "?>") || skip("<!--", "-->")) whitespace();
      var root = element(0);
      whitespace();
      while (skip("<!--", "-->")) whitespace();
      if (pos != n) throw error("Trailing XML content");
      ((Node) root).vocabulary = vocabulary;
      return root;
    }

    Element element(int depth) throws IOException {
      if (depth > 256 || ++count > 500000) throw error("XML complexity limit exceeded");
      if (at('<', '!')) throw error("DTD and entity declarations are not supported");
      expect('<');
      int nameStart = pos;
      var tag = name();
      int colon = tag.indexOf(':');
      var e = new Node(tag, colon < 0 ? tag : shared(nameStart + colon + 1, pos));
      while (true) {
        whitespace();
        if (at('/', '>')) {
          pos += 2;
          return e;
        }
        if (pos < n && c[pos] == '>') {
          pos++;
          break;
        }
        var key = name();
        if (((Node) e).index(key) >= 0) throw error("Duplicate attribute");
        whitespace();
        expect('=');
        whitespace();
        if (pos >= n) throw error("Missing attribute value");
        char quote = c[pos++];
        if (quote != '\"' && quote != '\'') throw error("Unquoted attribute");
        int end = pos;
        while (end < n && c[end] != quote) end++;
        if (end >= n) throw error("Unterminated attribute");
        e.setAttribute(key, value(pos, end, true));
        pos = end + 1;
      }
      while (pos < n) {
        if (at('<', '/')) {
          pos += 2;
          var close = name();
          whitespace();
          expect('>');
          if (!tag.equals(close)) throw error("Mismatched closing tag");
          return e;
        }
        if (skip("<!--", "-->")) continue;
        if (at('<', '!') && starts("<![CDATA[", pos)) {
          int end = find("]]>", pos + 9);
          if (end < 0) throw error("Unterminated CDATA");
          e.addChild(new Element(text(pos + 9, end - pos - 9), true));
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
      if (entity) return decode(text(start, end - start));
      return attribute ? shared(start, end) : text(start, end - start);
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

  /**
   * Float.parseFloat with a fast path for the plain integers nearly every OOXML measurement
   * is: ParparVM's general float parser was a tenth of the time spent opening a DOCX.
   */
  public static float number(String v) {
    int n = v.length(), i = n > 0 && v.charAt(0) == '-' ? 1 : 0;
    if (i == n || n - i > 9) return Float.parseFloat(v);
    int value = 0;
    for (int j = i; j < n; j++) {
      char ch = v.charAt(j);
      if (ch < '0' || ch > '9') return Float.parseFloat(v);
      value = value * 10 + (ch - '0');
    }
    return i == 1 ? -value : value;
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
