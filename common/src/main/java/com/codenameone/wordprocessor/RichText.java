package com.codenameone.wordprocessor;

import java.util.*;

/** Immutable styled rope. Leaf chunks are bounded; edits copy only the search path. */
public final class RichText {
  public interface Visitor {
    void run(int offset, String text, TextStyle style);

    default void field(int offset, InlineField field, TextStyle style) {
      run(offset, field.display(), style);
    }

    default void note(int offset, Note note, TextStyle style) {
      run(offset, note.label, style);
    }
  }

  public interface StyleMapper {
    TextStyle map(TextStyle style);
  }

  public interface FieldMapper {
    InlineField map(int offset, InlineField field);
  }

  public interface NoteMapper {
    Note map(int offset, Note note);
  }

  public interface TextMapper {
    String map(String text);
  }

  private static final int CHUNK = 256;

  private static final class Node {
    final Node l, r;
    final String text;
    final TextStyle style;
    final InlineField field;
    final Note note;
    final int length, height;
    final boolean hasNotes, hasFields;

    Node(String s, TextStyle t) {
      this(s, t, null);
    }

    Node(String s, TextStyle t, InlineField field) {
      this(s, t, field, null);
    }

    Node(String s, TextStyle t, InlineField field, Note note) {
      this.note = note;
      hasNotes = note != null;
      hasFields = field != null;
      this.field = field;
      l = r = null;
      text = s;
      style = t;
      length = s.length();
      height = 1;
    }

    Node(Node a, Node b) {
      note = null;
      hasNotes = a.hasNotes || b.hasNotes;
      hasFields = a.hasFields || b.hasFields;
      field = null;
      l = a;
      r = b;
      text = null;
      style = null;
      length = len(a) + len(b);
      height = 1 + Math.max(h(a), h(b));
    }
  }

  private final Node root;
  private final TextStyle emptyStyle;

  public RichText() {
    root = null;
    emptyStyle = TextStyle.DEFAULT;
  }

  private RichText(Node n) {
    this(n, TextStyle.DEFAULT);
  }

  private RichText(Node n, TextStyle style) {
    root = n;
    emptyStyle = style;
  }

  public static RichText of(String s) {
    return of(s, TextStyle.DEFAULT);
  }

  public static RichText of(String s, TextStyle t) {
    if (s == null || t == null) throw new NullPointerException();
    return new RichText(build(s, t, 0, s.length()), t);
  }

  private static Node build(String s, TextStyle t, int a, int b) {
    if (a == b) return null;
    if (b - a <= CHUNK) return new Node(s.substring(a, b), t);
    int mid = (a + b) / 2;
    if (mid < b && Character.isLowSurrogate(s.charAt(mid))) mid++;
    return new Node(build(s, t, a, mid), build(s, t, mid, b));
  }

  private static int len(Node n) {
    return n == null ? 0 : n.length;
  }

  private static int h(Node n) {
    return n == null ? 0 : n.height;
  }

  private static Node join(Node a, Node b) {
    if (a == null) return b;
    if (b == null) return a;
    if (a.text != null
        && b.text != null
        && a.field == null
        && b.field == null
        && a.note == null
        && b.note == null
        && a.style.equals(b.style)
        && a.length + b.length <= CHUNK) return new Node(a.text + b.text, a.style);
    if (h(a) > h(b) + 1) return balance(new Node(a.l, join(a.r, b)));
    if (h(b) > h(a) + 1) return balance(new Node(join(a, b.l), b.r));
    return new Node(a, b);
  }

  private static Node balance(Node n) {
    if (h(n.l) > h(n.r) + 1) {
      var l = n.l;
      if (h(l.l) >= h(l.r)) return new Node(l.l, new Node(l.r, n.r));
      var m = l.r;
      return new Node(new Node(l.l, m.l), new Node(m.r, n.r));
    }
    if (h(n.r) > h(n.l) + 1) {
      var r = n.r;
      if (h(r.r) >= h(r.l)) return new Node(new Node(n.l, r.l), r.r);
      var m = r.l;
      return new Node(new Node(n.l, m.l), new Node(m.r, r.r));
    }
    return n;
  }

  private static Node[] split(Node n, int at) {
    if (n == null) return new Node[] {null, null};
    if (at == 0) return new Node[] {null, n};
    if (at == n.length) return new Node[] {n, null};
    if (n.text != null)
      return new Node[] {
        new Node(n.text.substring(0, at), n.style), new Node(n.text.substring(at), n.style)
      };
    int k = len(n.l);
    if (at < k) {
      Node[] s = split(n.l, at);
      return new Node[] {s[0], join(s[1], n.r)};
    }
    Node[] s = split(n.r, at - k);
    return new Node[] {join(n.l, s[0]), s[1]};
  }

  public static RichText field(InlineField field, TextStyle style) {
    if (field == null || style == null) throw new NullPointerException();
    return new RichText(new Node(InlineField.OBJECT, style, field), style);
  }

  public static RichText note(Note note, TextStyle style) {
    if (note == null || style == null) throw new NullPointerException();
    return new RichText(new Node(InlineField.OBJECT, style, null, note), style);
  }

  public boolean hasNotes() { return root != null && root.hasNotes; }

  public boolean hasFields() { return root != null && root.hasFields; }

  public boolean hasObjects() { return root != null && (root.hasNotes || root.hasFields); }

  public Note noteAt(int offset) {
    var n = leafAt(offset);
    return n == null ? null : n.note;
  }

  public boolean objectAt(int offset) {
    var n = leafAt(offset);
    return n != null && (n.field != null || n.note != null);
  }

  public String displayAt(int offset, String ordinary) {
    var n = leafAt(offset);
    return n == null ? ordinary : n.field != null ? n.field.display() : n.note != null ? n.note.label : ordinary;
  }

  public RichText mapNotes(NoteMapper mapper) {
    Node value = notes(root, 0, mapper);
    return value == root ? this : new RichText(value, emptyStyle);
  }

  private static Node notes(Node n, int offset, NoteMapper mapper) {
    if (n == null || !n.hasNotes) return n;
    if (n.text != null) {
      if (n.note == null) return n;
      var value = mapper.map(offset, n.note);
      if (value == null) throw new IllegalArgumentException("Missing note");
      return value == n.note ? n : new Node(n.text, n.style, null, value);
    }
    Node left = notes(n.l, offset, mapper), right = notes(n.r, offset + len(n.l), mapper);
    return left == n.l && right == n.r ? n : join(left, right);
  }

  private Node leafAt(int offset) {
    if (offset < 0 || offset >= length()) return null;
    var n = root;
    while (n.text == null) {
      int k = len(n.l);
      if (offset < k) n = n.l;
      else { offset -= k; n = n.r; }
    }
    return n;
  }

  /** Fields in text order (one entry per field run), skipping subtrees that hold none. */
  public List<InlineField> fields() {
    var out = new ArrayList<InlineField>();
    if (root != null) fields(root, out);
    return out;
  }

  private static void fields(Node n, List<InlineField> out) {
    if (!n.hasFields) return;
    if (n.text != null) out.add(n.field);
    else {
      fields(n.l, out);
      fields(n.r, out);
    }
  }

  public InlineField fieldAt(int offset) {
    if (offset < 0 || offset >= length()) return null;
    var n = root;
    while (n.text == null) {
      int k = len(n.l);
      if (offset < k) n = n.l;
      else {
        offset -= k;
        n = n.r;
      }
    }
    return n.field;
  }

  public RichText mapStyles(StyleMapper mapper) {
    return new RichText(transform(root, 0, mapper, null, null), mapper.map(emptyStyle));
  }

  public RichText mapFields(FieldMapper mapper) {
    Node mapped = transform(root, 0, null, mapper, null);
    return mapped == root ? this : new RichText(mapped, emptyStyle);
  }

  public RichText mapText(TextMapper mapper) {
    return new RichText(transform(root, 0, null, null, mapper), emptyStyle);
  }

  /** Case and similar text transformations retain old text for review; fields remain atomic. */
  public RichText trackedMapText(
      final TextMapper mapper, final Revision insertion, final Revision deletion) {
    if (insertion == null) return mapText(mapper);
    final RichText[] result = {of("", emptyStyle)};
    visit(
        new Visitor() {
          public void run(int offset, String text, TextStyle style) {
            String changed =
                style.revision != null && !style.revision.inserted() ? text : mapper.map(text);
            RichText original = of(text, style);
            result[0] =
                result[0].concat(
                    changed.equals(text)
                        ? original
                        : of(changed, style.clean().revision(insertion))
                            .concat(original.deleted(deletion)));
          }

          public void field(int offset, InlineField field, TextStyle style) {
            result[0] = result[0].concat(RichText.field(field, style));
          }

          public void note(int offset, Note note, TextStyle style) {
            result[0] = result[0].concat(RichText.note(note, style));
          }
        });
    return result[0];
  }

  private static Node transform(
      Node n, int offset, StyleMapper styles, FieldMapper fields, TextMapper texts) {
    if (n == null) return null;
    if (n.text != null) {
      TextStyle style = styles == null ? n.style : styles.map(n.style);
      InlineField field = n.field == null || fields == null ? n.field : fields.map(offset, n.field);
      if (n.field != null && field == null) throw new IllegalArgumentException("Missing field");
      String text = n.field == null && n.note == null && texts != null ? texts.map(n.text) : n.text;
      if (style == n.style && field == n.field && text == n.text) return n;
      return field == null && n.note == null
          ? build(text, style, 0, text.length())
          : new Node(InlineField.OBJECT, style, field, n.note);
    }
    Node left = transform(n.l, offset, styles, fields, texts),
        right = transform(n.r, offset + len(n.l), styles, fields, texts);
    return left == n.l && right == n.r ? n : join(left, right);
  }

  /** Transform or remove whole leaves while preserving atomic field identity. */
  public RichText review(final String id, final boolean accept) {
    if (root == null) return of("", emptyStyle.reviewFormat(id, accept));
    final RichText[] result = {of("", styleAt(0).clean())};
    visit(
        new Visitor() {
          private TextStyle style(TextStyle s) {
            var r = s.revision;
            if (r != null) {
              var result = r.resolve(id, accept);
              if (result.removed()) return null;
              s = s.revision(result.remaining());
            }
            return s.reviewFormat(id, accept);
          }

          public void run(int offset, String text, TextStyle s) {
            var next = style(s);
            if (next != null) result[0] = result[0].concat(of(text, next));
          }

          public void field(int offset, InlineField field, TextStyle s) {
            var next = style(s);
            if (next != null) result[0] = result[0].concat(RichText.field(field, next));
          }

          public void note(int offset, Note note, TextStyle s) {
            var next = style(s);
            if (next != null) result[0] = result[0].concat(RichText.note(note, next));
          }
        });
    return result[0];
  }

  /** Deleting one's unaccepted insertion cancels it; existing text becomes a deletion. */
  public RichText deleted(final Revision deletion) {
    final RichText[] result = {new RichText()};
    visit(
        new Visitor() {
          private TextStyle style(TextStyle s) {
            var r = s.revision;
            if (r != null && r.inserted() && r.author.equals(deletion.author)) return null;
            return r != null && !r.inserted() ? s : s.revision(deletion.previous(r));
          }

          public void run(int offset, String text, TextStyle s) {
            var next = style(s);
            if (next != null) result[0] = result[0].concat(of(text, next));
          }

          public void field(int offset, InlineField field, TextStyle s) {
            var next = style(s);
            if (next != null) result[0] = result[0].concat(RichText.field(field, next));
          }

          public void note(int offset, Note note, TextStyle s) {
            var next = style(s);
            if (next != null) result[0] = result[0].concat(RichText.note(note, next));
          }
        });
    return result[0];
  }

  public String plainText() {
    final var out = new StringBuilder();
    visit((offset, text, style) -> out.append(text));
    return out.toString();
  }

  public int length() {
    return len(root);
  }

  public int depth() {
    return h(root);
  }

  private void range(int a, int b) {
    if (a < 0 || b < a || b > length()) throw new IndexOutOfBoundsException();
    boundary(a);
    boundary(b);
  }

  private void boundary(int i) {
    if (i > 0
        && i < length()
        && Character.isHighSurrogate(charAt(i - 1))
        && Character.isLowSurrogate(charAt(i)))
      throw new IllegalArgumentException("Offset splits a Unicode surrogate pair");
  }

  public char charAt(int i) {
    if (i < 0 || i >= length()) throw new IndexOutOfBoundsException();
    var n = root;
    while (n.text == null) {
      int k = len(n.l);
      if (i < k) n = n.l;
      else {
        i -= k;
        n = n.r;
      }
    }
    return n.text.charAt(i);
  }

  /**
   * Each character's style, and whether it belongs to a field or note, for [0, length()):
   * one tree walk instead of a descent per character in layout's inner loops.
   */
  public void fill(TextStyle[] styles, boolean[] objects) {
    fill(root, 0, styles, objects);
  }

  private static void fill(Node n, int offset, TextStyle[] styles, boolean[] objects) {
    if (n == null) return;
    if (n.text != null) {
      boolean object = n.field != null || n.note != null;
      for (int i = offset, end = offset + n.length; i < end; i++) {
        styles[i] = n.style;
        objects[i] = object;
      }
      return;
    }
    fill(n.l, offset, styles, objects);
    fill(n.r, offset + len(n.l), styles, objects);
  }

  public TextStyle styleAt(int i) {
    if (length() == 0) return emptyStyle;
    i = Math.max(0, Math.min(length() - 1, i));
    var n = root;
    while (n.text == null) {
      int k = len(n.l);
      if (i < k) n = n.l;
      else {
        i -= k;
        n = n.r;
      }
    }
    return n.style;
  }

  public RichText slice(int a, int b) {
    range(a, b);
    return new RichText(split(split(root, b)[0], a)[1], styleAt(a));
  }

  public RichText concat(RichText r) {
    return new RichText(join(root, r.root), r.emptyStyle);
  }

  public RichText replace(int a, int b, RichText r) {
    range(a, b);
    Node[] l = split(root, a), right = split(l[1], b - a);
    return new RichText(join(join(l[0], r.root), right[1]), r.emptyStyle);
  }

  /** The paragraph mark's formatting (what an empty paragraph shows and types with). */
  public TextStyle markStyle() {
    return emptyStyle;
  }

  /** This text with its paragraph mark formatted as {@code mark}. */
  public RichText markStyle(TextStyle mark) {
    return mark == emptyStyle ? this : new RichText(root, mark);
  }

  public RichText format(int a, int b, final String key, final String value) {
    range(a, b);
    Node[] l = split(root, a), r = split(l[1], b - a);
    return new RichText(join(join(l[0], map(r[0], key, value)), r[1]), emptyStyle.with(key, value));
  }

  public RichText format(
      int a, int b, final String key, final String value, final Revision change) {
    if (change == null) return format(a, b, key, value);
    return replace(a, b, slice(a, b).mapStyles(s -> s.trackedWith(key, value, change)));
  }

  private Node map(Node n, String k, String v) {
    if (n == null) return null;
    if (n.text != null) return new Node(n.text, n.style.with(k, v), n.field, n.note);
    return join(map(n.l, k, v), map(n.r, k, v));
  }

  public void visit(Visitor visitor) {
    if (root == null) visitor.run(0, "", emptyStyle);
    else visit(root, 0, visitor);
  }

  private void visit(Node n, int offset, Visitor v) {
    if (n == null) return;
    if (n.note != null) v.note(offset, n.note, n.style);
    else if (n.field != null) v.field(offset, n.field, n.style);
    else if (n.text != null) v.run(offset, n.text, n.style);
    else {
      visit(n.l, offset, v);
      visit(n.r, offset + len(n.l), v);
    }
  }

  public String toString() {
    final var b = new StringBuilder(length());
    visit(
        new Visitor() {
          public void run(int o, String s, TextStyle t) {
            b.append(s);
          }

          public void field(int o, InlineField field, TextStyle t) {
            b.append(InlineField.OBJECT);
          }

          public void note(int o, Note note, TextStyle t) {
            b.append(InlineField.OBJECT);
          }
        });
    return b.toString();
  }
}
