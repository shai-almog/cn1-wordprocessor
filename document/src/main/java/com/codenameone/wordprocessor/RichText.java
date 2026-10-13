package com.codenameone.wordprocessor;

import java.util.*;

/** Immutable styled rope. Leaf chunks are bounded; edits copy only the search path. */
public final class RichText {
  public interface Visitor {
    void run(int offset, String text, TextStyle style);
  }

  private static final int CHUNK = 256;

  private static final class Node {
    final Node l, r;
    final String text;
    final TextStyle style;
    final int length, height;

    Node(String s, TextStyle t) {
      l = r = null;
      text = s;
      style = t;
      length = s.length();
      height = 1;
    }

    Node(Node a, Node b) {
      l = a;
      r = b;
      text = null;
      style = null;
      length = len(a) + len(b);
      height = 1 + Math.max(h(a), h(b));
    }
  }

  private final Node root;

  public RichText() {
    root = null;
  }

  private RichText(Node n) {
    root = n;
  }

  public static RichText of(String s) {
    return of(s, TextStyle.DEFAULT);
  }

  public static RichText of(String s, TextStyle t) {
    if (s == null || t == null) throw new NullPointerException();
    return new RichText(build(s, t, 0, s.length()));
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
    if (a.text != null && b.text != null && a.style.equals(b.style) && a.length + b.length <= CHUNK)
      return new Node(a.text + b.text, a.style);
    if (h(a) > h(b) + 1) return balance(new Node(a.l, join(a.r, b)));
    if (h(b) > h(a) + 1) return balance(new Node(join(a, b.l), b.r));
    return new Node(a, b);
  }

  private static Node balance(Node n) {
    if (h(n.l) > h(n.r) + 1) {
      Node l = n.l;
      if (h(l.l) >= h(l.r)) return new Node(l.l, new Node(l.r, n.r));
      Node m = l.r;
      return new Node(new Node(l.l, m.l), new Node(m.r, n.r));
    }
    if (h(n.r) > h(n.l) + 1) {
      Node r = n.r;
      if (h(r.r) >= h(r.l)) return new Node(new Node(n.l, r.l), r.r);
      Node m = r.l;
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
    Node n = root;
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

  public TextStyle styleAt(int i) {
    if (length() == 0) return TextStyle.DEFAULT;
    i = Math.max(0, Math.min(length() - 1, i));
    Node n = root;
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
    return new RichText(split(split(root, b)[0], a)[1]);
  }

  public RichText concat(RichText r) {
    return new RichText(join(root, r.root));
  }

  public RichText replace(int a, int b, RichText r) {
    range(a, b);
    Node[] l = split(root, a), right = split(l[1], b - a);
    return new RichText(join(join(l[0], r.root), right[1]));
  }

  public RichText format(int a, int b, final String key, final String value) {
    range(a, b);
    Node[] l = split(root, a), r = split(l[1], b - a);
    return new RichText(join(join(l[0], map(r[0], key, value)), r[1]));
  }

  private Node map(Node n, String k, String v) {
    if (n == null) return null;
    if (n.text != null) return new Node(n.text, n.style.with(k, v));
    return join(map(n.l, k, v), map(n.r, k, v));
  }

  public void visit(Visitor visitor) {
    visit(root, 0, visitor);
  }

  private void visit(Node n, int offset, Visitor v) {
    if (n == null) return;
    if (n.text != null) v.run(offset, n.text, n.style);
    else {
      visit(n.l, offset, v);
      visit(n.r, offset + len(n.l), v);
    }
  }

  public String toString() {
    final StringBuilder b = new StringBuilder(length());
    visit(
        new Visitor() {
          public void run(int o, String s, TextStyle t) {
            b.append(s);
          }
        });
    return b.toString();
  }
}
