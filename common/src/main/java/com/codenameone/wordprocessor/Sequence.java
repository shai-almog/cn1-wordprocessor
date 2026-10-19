package com.codenameone.wordprocessor;

import java.util.*;

/** Persistent AVL sequence. Edits share unaffected subtrees; snapshots are O(1). */
public final class Sequence<T> implements Iterable<T> {
  private final Node<T> root;

  private static final class Node<T> {
    final Node<T> l, r;
    final T value;
    final int size, height;

    Node(Node<T> l, T v, Node<T> r) {
      this.l = l;
      value = v;
      this.r = r;
      size = 1 + size(l) + size(r);
      height = 1 + Math.max(height(l), height(r));
    }
  }

  public Sequence() {
    root = null;
  }

  /** A balanced sequence of the list's elements, built in linear time. */
  public static <T> Sequence<T> of(List<? extends T> values) {
    return new Sequence<T>(build(values, 0, values.size()));
  }

  private static <T> Node<T> build(List<? extends T> values, int from, int to) {
    if (from >= to) return null;
    int mid = (from + to) >>> 1;
    return new Node<T>(build(values, from, mid), values.get(mid), build(values, mid + 1, to));
  }

  private Sequence(Node<T> n) {
    root = n;
  }

  private static int size(Node<?> n) {
    return n == null ? 0 : n.size;
  }

  private static int height(Node<?> n) {
    return n == null ? 0 : n.height;
  }

  public int size() {
    return size(root);
  }

  public int depth() {
    return height(root);
  }

  public T get(int i) {
    check(i, false);
    Node<T> n = root;
    while (true) {
      int k = size(n.l);
      if (i == k) return n.value;
      if (i < k) n = n.l;
      else {
        i -= k + 1;
        n = n.r;
      }
    }
  }

  private void check(int i, boolean end) {
    if (i < 0 || i >= size() + (end ? 1 : 0)) throw new IndexOutOfBoundsException("Index " + i);
  }

  private static <T> Node<T> balance(Node<T> n) {
    int b = height(n.l) - height(n.r);
    if (b > 1) {
      Node<T> l = n.l;
      if (height(l.l) < height(l.r)) l = left(l);
      return right(new Node<T>(l, n.value, n.r));
    }
    if (b < -1) {
      Node<T> r = n.r;
      if (height(r.r) < height(r.l)) r = right(r);
      return left(new Node<T>(n.l, n.value, r));
    }
    return n;
  }

  private static <T> Node<T> left(Node<T> n) {
    Node<T> r = n.r;
    return new Node<>(new Node<T>(n.l, n.value, r.l), r.value, r.r);
  }

  private static <T> Node<T> right(Node<T> n) {
    Node<T> l = n.l;
    return new Node<>(l.l, l.value, new Node<T>(l.r, n.value, n.r));
  }

  public Sequence<T> insert(int i, T v) {
    check(i, true);
    if (v == null) throw new NullPointerException();
    return new Sequence<>(insert(root, i, v));
  }

  private Node<T> insert(Node<T> n, int i, T v) {
    if (n == null) return new Node<>(null, v, null);
    int k = size(n.l);
    return balance(
        i <= k
            ? new Node<T>(insert(n.l, i, v), n.value, n.r)
            : new Node<T>(n.l, n.value, insert(n.r, i - k - 1, v)));
  }

  public Sequence<T> append(T v) {
    return insert(size(), v);
  }

  public Sequence<T> set(int i, T v) {
    check(i, false);
    if (v == null) throw new NullPointerException();
    return new Sequence<>(set(root, i, v));
  }

  private Node<T> set(Node<T> n, int i, T v) {
    int k = size(n.l);
    if (i == k) return new Node<>(n.l, v, n.r);
    return i < k
        ? new Node<T>(set(n.l, i, v), n.value, n.r)
        : new Node<T>(n.l, n.value, set(n.r, i - k - 1, v));
  }

  public Sequence<T> remove(int i) {
    check(i, false);
    return new Sequence<>(remove(root, i));
  }

  private Node<T> remove(Node<T> n, int i) {
    int k = size(n.l);
    if (i < k) return balance(new Node<T>(remove(n.l, i), n.value, n.r));
    if (i > k) return balance(new Node<T>(n.l, n.value, remove(n.r, i - k - 1)));
    if (n.l == null) return n.r;
    if (n.r == null) return n.l;
    Node<T> s = n.r;
    while (s.l != null) s = s.l;
    return balance(new Node<T>(n.l, s.value, remove(n.r, 0)));
  }

  public Iterator<T> iterator() {
    return new Iterator<T>() {
      final ArrayList<Node<T>> stack = new ArrayList<>();

      {
        push(root);
      }

      void push(Node<T> n) {
        while (n != null) {
          stack.add(n);
          n = n.l;
        }
      }

      public boolean hasNext() {
        return !stack.isEmpty();
      }

      public T next() {
        if (!hasNext()) throw new NoSuchElementException();
        var n = stack.remove(stack.size() - 1);
        push(n.r);
        return n.value;
      }

      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
  }
}
