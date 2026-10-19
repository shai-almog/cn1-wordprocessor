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
    /** Each attribute's name without its prefix, at its name's index in attrs. */
    String[] locals;
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
      int colon = name.indexOf(':');
      add(name, colon < 0 ? name : name.substring(colon + 1), value);
      return -1;
    }

    /** Appends an attribute the element does not have yet. */
    void add(String name, String local, String value) {
      if (attrs == null) {
        attrs = new String[4];
        locals = new String[4];
      } else if (attrCount == attrs.length) {
        attrs = Arrays.copyOf(attrs, attrCount * 2);
        locals = Arrays.copyOf(locals, attrCount * 2);
      }
      locals[attrCount] = local;
      attrs[attrCount++] = name;
      attrs[attrCount++] = value;
    }

    /**
     * The index of the attribute named exactly name, else w:name, else any prefix:name; -1
     * when absent. Compares pooled local names instead of building or suffix-matching names.
     */
    int lookup(String name) {
      int prefixed = -1, w = -1;
      for (int i = 0; i < attrCount; i += 2) {
        String k = attrs[i];
        if (k.equals(name)) return i;
        if (locals[i] != k && locals[i].equals(name)) {
          if (k.length() == name.length() + 2 && k.charAt(0) == 'w') w = i;
          else if (prefixed < 0) prefixed = i;
        }
      }
      return w >= 0 ? w : prefixed;
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
      System.arraycopy(locals, i + 2, locals, i, attrCount - i - 2);
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
    return finish(reader);
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
    return finish(new Reader(text.toCharArray(), text.length()));
  }

  /** The parsed root, or the parser's recorded error as an IOException. */
  private static Element finish(Reader reader) throws IOException {
    var root = reader.read();
    if (root == null) throw new IOException(reader.failure + " at XML offset " + reader.failedAt);
    return root;
  }

  /**
   * The parser never throws: ParparVM gives a method that can throw, or that calls anything,
   * an emulated operand stack and an overflow guard, so the per-character loops are static
   * leaf helpers over local copies of the buffer, and the first error is recorded and unwound
   * by returning null. Only {@link #parse} turns it into an IOException.
   */
  private static final class Reader {
    final char[] c;
    final int n;
    int pos, count;
    /** The whole part as one compact String when every character is Latin-1, else null. */
    String source;
    /** The first error and the offset it was found at; parsing stops once set. */
    String failure;
    int failedAt;

    Reader(char[] c, int n) {
      this.c = c;
      this.n = n;
    }

    String text(int start, int length) {
      return source != null ? source.substring(start, start + length) : new String(c, start, length);
    }

    /** Records the first error; always null so callers can {@code return fail(...)}. */
    Node fail(String message) {
      if (failure == null) {
        failure = message;
        failedAt = pos;
      }
      return null;
    }

    // ---- leaf helpers: no calls, no throws, locals only ----

    /** The first index at or after p that is not an ASCII XML space. */
    static int spaces(char[] c, int p, int n) {
      while (p < n) {
        char ch = c[p];
        if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') return p;
        p++;
      }
      return p;
    }

    /** The first index at or after p that is not an ASCII name character. */
    static int nameEnd(char[] c, int p, int n) {
      while (p < n) {
        char ch = c[p];
        if (!(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9'
            || ch == '_' || ch == ':' || ch == '-' || ch == '.')) return p;
        p++;
      }
      return p;
    }

    /** The index of ch at or after p, or n. */
    static int until(char[] c, char ch, int p, int n) {
      while (p < n && c[p] != ch) p++;
      return p;
    }

    /** Whether t's characters occur at p. */
    static boolean starts(char[] c, int p, int n, char[] t) {
      if (p < 0 || p + t.length > n) return false;
      for (int i = 0; i < t.length; i++) if (c[p + i] != t[i]) return false;
      return true;
    }

    /** The index of t at or after p, or -1. */
    static int find(char[] c, int p, int n, char[] t) {
      char first = t[0];
      for (int last = n - t.length; p <= last; p++) {
        if (c[p] != first) continue;
        int i = 1;
        while (i < t.length && c[p + i] == t[i]) i++;
        if (i == t.length) return p;
      }
      return -1;
    }

    static final int ENTITY = 1, CONTROL = 2, LESS = 4;

    /** ENTITY, CONTROL and LESS flags for the characters of [start, end). */
    static int scan(char[] c, int start, int end) {
      int flags = 0;
      for (int i = start; i < end; i++) {
        char ch = c[i];
        if (ch < 32) {
          if (ch != '\t' && ch != '\n' && ch != '\r') flags |= CONTROL;
        } else if (ch == '&') flags |= ENTITY;
        else if (ch == '<') flags |= LESS;
      }
      return flags;
    }

    static int hash(char[] c, int start, int end) {
      int h = end - start;
      for (int i = start; i < end; i++) h = 31 * h + c[i];
      return h ^ (h >>> 16);
    }

    static boolean same(char[] pooled, char[] c, int start, int len) {
      if (pooled == null || pooled.length != len) return false;
      for (int i = 0; i < len; i++) if (pooled[i] != c[start + i]) return false;
      return true;
    }

    static final char[] PI = {'<', '?'}, PI_END = {'?', '>'}, COMMENT = {'<', '!', '-', '-'},
        COMMENT_END = {'-', '-', '>'}, CDATA = {'<', '!', '[', 'C', 'D', 'A', 'T', 'A', '['},
        CDATA_END = {']', ']', '>'};

    // ---- cold paths: calls allowed, rarely reached ----

    void whitespace() {
      int p = spaces(c, pos, n);
      while (p < n && (c[p] > 127 || c[p] < 32) && Character.isWhitespace(c[p])) p = spaces(c, p + 1, n);
      pos = p;
    }

    /** End of a name starting at p, following non-ASCII letters and digits too. */
    int wideNameEnd(int p) {
      p = nameEnd(c, p, n);
      while (p < n && c[p] > 127 && (Unicode.letter(c[p]) || Character.isDigit(c[p]))) p = nameEnd(c, p + 1, n);
      return p;
    }

    /** A name at pos, or null after recording the error. */
    String name() {
      int start = pos;
      if (start >= n) return failName();
      char first = c[start];
      boolean ascii = first >= 'a' && first <= 'z' || first >= 'A' && first <= 'Z' || first == '_' || first == ':';
      if (!ascii && !(first > 127 && Unicode.letter(first))) return failName();
      int end = nameEnd(c, start + 1, n);
      if (end < n && c[end] > 127) end = wideNameEnd(end);
      pos = end;
      return shared(start, end);
    }

    private String failName() {
      fail("Expected XML name");
      return null;
    }

    /** Pool of short strings: names and small values repeat thousands of times per part. */
    private final String[] pool = new String[1024];
    // Each pooled string's characters, compared against the buffer directly.
    private final char[][] poolChars = new char[1024][];
    // Every pool miss: each distinct short name (so every element's local name) at least once.
    final Set<String> vocabulary = new HashSet<>();

    String shared(int start, int end) {
      int len = end - start;
      if (len > 24) return text(start, len);
      int slot = hash(c, start, end) & (pool.length - 1);
      if (same(poolChars[slot], c, start, len)) return pool[slot];
      var v = text(start, len);
      vocabulary.add(v);
      pool[slot] = v;
      poolChars[slot] = java.util.Arrays.copyOfRange(c, start, end);
      return v;
    }

    /** Skips a construct opened by begin and closed by end; false when absent or on error. */
    boolean skip(char[] begin, char[] end) {
      if (!starts(c, pos, n, begin)) return false;
      int finish = find(c, pos + begin.length, n, end);
      if (finish < 0) {
        fail("Unterminated XML declaration/comment");
        return false;
      }
      pos = finish + end.length;
      return true;
    }

    Node read() {
      if (n > 0 && c[0] == '\ufeff') pos++;
      whitespace();
      while (skip(PI, PI_END) || skip(COMMENT, COMMENT_END)) whitespace();
      if (failure != null) return null;
      var root = element(0);
      if (root == null) return null;
      whitespace();
      while (skip(COMMENT, COMMENT_END)) whitespace();
      if (failure != null) return null;
      if (pos != n) return fail("Trailing XML content");
      root.vocabulary = vocabulary;
      return root;
    }

    Node element(int depth) {
      if (depth > 256 || ++count > 500000) return fail("XML complexity limit exceeded");
      final char[] c = this.c;
      final int n = this.n;
      if (pos + 1 < n && c[pos] == '<' && c[pos + 1] == '!')
        return fail("DTD and entity declarations are not supported");
      if (pos >= n || c[pos] != '<') return fail("Expected <");
      pos++;
      int nameStart = pos;
      var tag = name();
      if (tag == null) return null;
      int colon = tag.indexOf(':');
      var e = new Node(tag, colon < 0 ? tag : shared(nameStart + colon + 1, pos));
      while (true) {
        whitespace();
        if (pos + 1 < n && c[pos] == '/' && c[pos + 1] == '>') {
          pos += 2;
          return e;
        }
        if (pos < n && c[pos] == '>') {
          pos++;
          break;
        }
        int keyStart = pos;
        var key = name();
        if (key == null) return null;
        if (e.index(key) >= 0) return fail("Duplicate attribute");
        int keyColon = key.indexOf(':');
        var keyLocal = keyColon < 0 ? key : shared(keyStart + keyColon + 1, pos);
        whitespace();
        if (pos >= n || c[pos] != '=') return fail("Expected =");
        pos++;
        whitespace();
        if (pos >= n) return fail("Missing attribute value");
        char quote = c[pos++];
        if (quote != '\"' && quote != '\'') return fail("Unquoted attribute");
        int end = until(c, quote, pos, n);
        if (end >= n) return fail("Unterminated attribute");
        var value = value(pos, end, true);
        if (value == null) return null;
        e.add(key, keyLocal, value);
        pos = end + 1;
      }
      while (pos < n) {
        if (c[pos] != '<') {
          int end = until(c, '<', pos, n);
          if (end >= n) return fail("Unterminated element");
          var value = value(pos, end, false);
          if (value == null) return null;
          e.addChild(new Element(value, true));
          pos = end;
          continue;
        }
        char next = pos + 1 < n ? c[pos + 1] : 0;
        if (next == '/') {
          pos += 2;
          var close = name();
          if (close == null) return null;
          whitespace();
          if (pos >= n || c[pos] != '>') return fail("Expected >");
          pos++;
          if (!tag.equals(close)) return fail("Mismatched closing tag");
          return e;
        }
        if (next == '!') {
          if (skip(COMMENT, COMMENT_END)) continue;
          if (failure != null) return null;
          if (starts(c, pos, n, CDATA)) {
            int end = find(c, pos + 9, n, CDATA_END);
            if (end < 0) return fail("Unterminated CDATA");
            e.addChild(new Element(text(pos + 9, end - pos - 9), true));
            pos = end + 3;
            continue;
          }
        } else if (next == '?') {
          if (skip(PI, PI_END)) continue;
          return null;
        }
        var child = element(depth + 1);
        if (child == null) return null;
        e.addChild(child);
      }
      return fail("Unterminated element");
    }

    /**
     * The decoded text of [start, end), or null after recording an error: validated in place,
     * and only copied out (pooled for attributes) when it holds no entity, which is nearly
     * every value in OOXML.
     */
    String value(int start, int end, boolean attribute) {
      int flags = scan(c, start, end);
      if ((flags & CONTROL) != 0 || attribute && (flags & LESS) != 0) {
        fail((flags & CONTROL) != 0 ? "Invalid XML control character" : "Invalid attribute value");
        return null;
      }
      if ((flags & ENTITY) != 0) return decode(start, end);
      return attribute ? shared(start, end) : text(start, end - start);
    }

    /** Entity-decoded [start, end), or null after recording an error. */
    String decode(int start, int end) {
      var b = new StringBuilder(end - start);
      for (int i = start; i < end; i++) {
        char ch = c[i];
        if (ch != '&') {
          b.append(ch);
          continue;
        }
        int semi = until(c, ';', i + 1, end);
        if (semi >= end) return failText("Unterminated entity");
        int from = i + 1, len = semi - from;
        if (len == 3 && c[from] == 'a' && c[from + 1] == 'm' && c[from + 2] == 'p') b.append('&');
        else if (len == 2 && c[from] == 'l' && c[from + 1] == 't') b.append('<');
        else if (len == 2 && c[from] == 'g' && c[from + 1] == 't') b.append('>');
        else if (len == 4 && c[from] == 'q' && c[from + 1] == 'u' && c[from + 2] == 'o' && c[from + 3] == 't') b.append('\"');
        else if (len == 4 && c[from] == 'a' && c[from + 1] == 'p' && c[from + 2] == 'o' && c[from + 3] == 's') b.append('\'');
        else if (len > 1 && c[from] == '#') {
          int cp = codePoint(c, from + 1, semi);
          if (cp < 0) return failText("Invalid numeric entity");
          if (cp >= 0x10000) b.append((char) (0xd7c0 + (cp >> 10))).append((char) (0xdc00 | cp & 0x3ff));
          else b.append((char) cp);
        } else return failText("Unknown entity");
        i = semi;
      }
      return b.toString();
    }

    private String failText(String message) {
      fail(message);
      return null;
    }

    /** The XML character a numeric reference's digits [p, end) name, or -1 when invalid. */
    static int codePoint(char[] c, int p, int end) {
      int radix = 10;
      if (p < end && c[p] == 'x') {
        radix = 16;
        p++;
      }
      if (p >= end || end - p > 8) return -1;
      int cp = 0;
      for (; p < end; p++) {
        char ch = c[p];
        int digit = ch >= '0' && ch <= '9' ? ch - '0'
            : radix == 16 && ch >= 'a' && ch <= 'f' ? ch - 'a' + 10
            : radix == 16 && ch >= 'A' && ch <= 'F' ? ch - 'A' + 10 : -1;
        if (digit < 0) return -1;
        cp = cp * radix + digit;
      }
      if (cp > 0x10ffff || cp >= 0xd800 && cp <= 0xdfff || cp < 32 && cp != 9 && cp != 10 && cp != 13) return -1;
      return cp;
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
  /**
   * A decimal int (optional sign, at most nine digits), or the fallback when v is anything
   * else. Never throws, unlike Integer.parseInt.
   */
  public static int integer(String v, int fallback) {
    int n = v.length(), i = n > 0 && (v.charAt(0) == '-' || v.charAt(0) == '+') ? 1 : 0;
    if (i == n || n - i > 9) return fallback;
    int value = 0;
    for (int j = i; j < n; j++) {
      char ch = v.charAt(j);
      if (ch < '0' || ch > '9') return fallback;
      value = value * 10 + (ch - '0');
    }
    return v.charAt(0) == '-' ? -value : value;
  }

  public static float number(String v) {
    int value = integer(v, Integer.MIN_VALUE);
    return value != Integer.MIN_VALUE ? value : decimal(v);
  }

  /** The rare non-integer measurement, kept out of number() so its hot path never throws. */
  private static float decimal(String v) {
    return Float.parseFloat(v);
  }


  public static String attr(Element e, String name, String fallback) {
    // Exact lookups: Element.getAttribute lower-cases the name on every call, which made
    // attribute reads the largest cost of opening a document. OOXML names are case-exact.
    if (e instanceof Node n) {
      int i = n.lookup(name);
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
