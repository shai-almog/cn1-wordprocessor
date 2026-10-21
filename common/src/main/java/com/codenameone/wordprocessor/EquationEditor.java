package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;

/**
 * In-place structure editing of one equation, a port of Wordcraft's {@code math_edit.rs}: a caret
 * inside the Office Math tree that moves across structure slots (fraction numerator/denominator,
 * scripts, radical degree/base, n-ary limits and operand, delimiter elements, function name and
 * argument, limits, accents, boxes, matrix cells, equation-array rows), typing and deleting inside
 * slots, Math AutoCorrect ({@code \alpha} becomes the Greek letter on space or a relation), build-up
 * of linear input (typing {@code a/b} then space makes a fraction), structure templates (the
 * Structures gallery) with the caret placed in their first empty slot and Tab between empty slots.
 *
 * <p>Pure model code: it holds a mutable tree converted from {@link Equation.Node} and writes the
 * result back as linear text, which is how the document stores an equation. An empty slot of a
 * structure is written as the placeholder {@code U+2B1A}, which Word and Wordcraft also show.
 */
final class EquationEditor {
  static final String HOLE = "\u2b1a";
  private static final int MAX_DEPTH = Equation.MAX_DEPTH;

  /** A caret: (node index, slot) pairs from the root down, then an offset in that slot. */
  static final class Pos {
    final int[] path;
    final int off;

    Pos(int[] path, int off) {
      this.path = path == null ? new int[0] : path;
      this.off = off;
    }

    int depth() {
      return path.length / 2;
    }

    Pos with(int off) {
      return new Pos(path, off);
    }

    Pos parent() {
      int[] p = new int[path.length - 2];
      System.arraycopy(path, 0, p, 0, p.length);
      return new Pos(p, 0);
    }

    Pos child(int node, int slot, int off) {
      int[] p = new int[path.length + 2];
      System.arraycopy(path, 0, p, 0, path.length);
      p[path.length] = node;
      p[path.length + 1] = slot;
      return new Pos(p, off);
    }

    int lastNode() {
      return path[path.length - 2];
    }

    int lastSlot() {
      return path[path.length - 1];
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Pos && ((Pos) o).off == off && java.util.Arrays.equals(((Pos) o).path, path);
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(path) * 31 + off;
    }

    @Override
    public String toString() {
      return java.util.Arrays.toString(path) + "@" + off;
    }
  }

  /** A mutable node: a run of text, or a structure with slots (rows of nodes). */
  static final class N {
    String kind, a, b, c;
    String text;
    boolean normal;
    final List<List<N>> slots = new ArrayList<>();
    /** Optional slots (radical degree, n-ary limits) that are absent and not navigable. */
    boolean[] hidden;
    /** Matrix row lengths (cells are slots in reading order). */
    int[] rowLens;

    boolean run() {
      return "run".equals(kind);
    }

    int units() {
      return run() ? text.length() : 1;
    }

    N copy() {
      N n = new N();
      n.kind = kind;
      n.a = a;
      n.b = b;
      n.c = c;
      n.text = text;
      n.normal = normal;
      n.hidden = hidden == null ? null : hidden.clone();
      n.rowLens = rowLens == null ? null : rowLens.clone();
      for (List<N> s : slots) n.slots.add(copyAll(s));
      return n;
    }
  }

  static N run(String text, boolean normal) {
    N n = new N();
    n.kind = "run";
    n.text = text;
    n.normal = normal;
    n.a = n.b = n.c = "";
    return n;
  }

  static List<N> copyAll(List<N> a) {
    List<N> out = new ArrayList<>();
    for (N n : a) out.add(n.copy());
    return out;
  }

  /** The equation's root row. */
  List<N> root;
  Pos pos;

  EquationEditor(String linear) {
    root = parse(linear);
    pos = new Pos(null, 0);
  }

  // ------------------------------------------------------------------------ conversion

  static List<N> parse(String linear) {
    List<N> out = new ArrayList<>();
    if (linear == null || linear.isEmpty() || HOLE.equals(linear)) return out;
    fromNode(Equation.fromLinear(linear), out, 0);
    return out;
  }

  private static void fromNode(Equation.Node n, List<N> out, int depth) {
    if (depth > MAX_DEPTH * 2) return;
    if ("row".equals(n.kind)) {
      for (Equation.Node c : n.args) fromNode(c, out, depth + 1);
      return;
    }
    if ("run".equals(n.kind)) {
      if (!n.a.isEmpty()) out.add(run(n.a, "1".equals(n.b)));
      return;
    }
    N m = new N();
    m.kind = n.kind;
    m.a = n.a;
    m.b = n.b;
    m.c = n.c;
    if ("m".equals(n.kind)) {
      m.rowLens = new int[n.args.size()];
      for (int r = 0; r < n.args.size(); r++) {
        Equation.Node row = n.args.get(r);
        List<Equation.Node> cells = "mr".equals(row.kind) ? row.args : java.util.Collections.singletonList(row);
        m.rowLens[r] = cells.size();
        for (Equation.Node cell : cells) m.slots.add(slot(cell, depth));
      }
    } else {
      int count = n.args.size();
      if ("rad".equals(n.kind) || "func".equals(n.kind) || "limLow".equals(n.kind) || "limUpp".equals(n.kind)
          || "sSup".equals(n.kind) || "sSub".equals(n.kind) || "f".equals(n.kind)) count = Math.max(count, 2);
      if ("nary".equals(n.kind) || "sSubSup".equals(n.kind) || "sPre".equals(n.kind)) count = Math.max(count, 3);
      if ("acc".equals(n.kind) || "bar".equals(n.kind) || "box".equals(n.kind) || "groupChr".equals(n.kind))
        count = Math.max(count, 1);
      for (int i = 0; i < count; i++) m.slots.add(slot(n.arg(i), depth));
      m.hidden = new boolean[count];
      if ("rad".equals(n.kind)) m.hidden[1] = n.arg(1).empty();
      if ("nary".equals(n.kind)) {
        m.hidden[1] = n.arg(1).empty();
        m.hidden[2] = n.arg(2).empty();
      }
    }
    out.add(m);
  }

  private static List<N> slot(Equation.Node n, int depth) {
    List<N> s = new ArrayList<>();
    fromNode(n, s, depth + 1);
    // A lone placeholder is an empty slot.
    if (s.size() == 1 && s.get(0).run() && HOLE.equals(s.get(0).text)) s.clear();
    return s;
  }

  /** The equation as a tree for layout and OMML. */
  static Equation.Node toNode(List<N> row) {
    return toRow(row, false, 0);
  }

  private static Equation.Node toRow(List<N> row, boolean hole, int depth) {
    List<Equation.Node> items = new ArrayList<>();
    if (row.isEmpty() && hole) items.add(Equation.run(HOLE, false));
    for (N n : row) items.add(toNode(n, depth + 1));
    return new Equation.Node("row", null, null, null, items);
  }

  private static Equation.Node toNode(N n, int depth) {
    if (n.run()) return Equation.run(n.text, n.normal);
    List<Equation.Node> args = new ArrayList<>();
    if (n.rowLens != null) {
      int k = 0;
      for (int len : n.rowLens) {
        List<Equation.Node> cells = new ArrayList<>();
        for (int c = 0; c < len; c++) cells.add(toRow(n.slots.get(k++), true, depth));
        args.add(new Equation.Node("mr", null, null, null, cells));
      }
    } else
      for (int i = 0; i < n.slots.size(); i++)
        args.add(toRow(n.slots.get(i), n.hidden == null || !n.hidden[i], depth));
    return new Equation.Node(n.kind, n.a, n.b, n.c, args);
  }

  /** Linear text of the whole equation. */
  String linear() {
    return root.isEmpty() ? "" : Equation.linear(toNode(root));
  }

  static String linear(List<N> nodes) {
    return nodes.isEmpty() ? "" : Equation.linear(toNode(nodes));
  }

  // ------------------------------------------------------------------------ tree access

  static int units(List<N> a) {
    int u = 0;
    for (N n : a) u += n.units();
    return u;
  }

  /** Slots in reading order that the caret visits. */
  static int[] nav(N n) {
    switch (n.kind) {
      case "sPre":
        return new int[] {1, 2, 0};
      case "rad":
        return n.hidden != null && n.hidden[1] ? new int[] {0} : new int[] {1, 0};
      case "nary": {
        int[] v = new int[3];
        int k = 0;
        if (!n.hidden[1]) v[k++] = 1;
        if (!n.hidden[2]) v[k++] = 2;
        v[k++] = 0;
        return java.util.Arrays.copyOf(v, k);
      }
      case "func":
        return new int[] {1, 0};
      default: {
        int[] v = new int[n.slots.size()];
        for (int i = 0; i < v.length; i++) v[i] = i;
        return v;
      }
    }
  }

  private static int indexOf(int[] a, int v) {
    for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
    return -1;
  }

  List<N> slotAt(int[] path) {
    return slotAt(root, path, path.length);
  }

  static List<N> slotAt(List<N> root, int[] path, int len) {
    List<N> a = root;
    for (int i = 0; i + 1 < len; i += 2) {
      if (path[i] < 0 || path[i] >= a.size()) return null;
      N n = a.get(path[i]);
      if (path[i + 1] < 0 || path[i + 1] >= n.slots.size()) return null;
      a = n.slots.get(path[i + 1]);
    }
    return a;
  }

  /** {@code pos} made valid for the current tree. */
  Pos clamp(Pos p) {
    List<N> a = root;
    int[] path = p.path;
    for (int i = 0; i + 1 < path.length && i / 2 < MAX_DEPTH; i += 2) {
      int n = path[i], c = path[i + 1];
      if (n >= 0 && n < a.size() && c >= 0 && c < a.get(n).slots.size()) a = a.get(n).slots.get(c);
      else {
        int[] q = java.util.Arrays.copyOf(path, i);
        return new Pos(q, nodeStart(a, Math.max(0, Math.min(n, a.size()))));
      }
    }
    return new Pos(path, Math.max(0, Math.min(p.off, units(a))));
  }

  static int nodeStart(List<N> a, int i) {
    int u = 0;
    for (int k = 0; k < i && k < a.size(); k++) u += a.get(k).units();
    return u;
  }

  /** {index, offset inside} for the unit offset; offset 0 means before node index. */
  private static int[] locate(List<N> a, int off) {
    int u = 0;
    for (int i = 0; i < a.size(); i++) {
      if (off == u) return new int[] {i, 0};
      int len = a.get(i).units();
      if (off < u + len) return new int[] {i, off - u};
      u += len;
    }
    return new int[] {a.size(), 0};
  }

  /** Splits a run at {@code off}; returns the node index there. */
  private static int split(List<N> a, int off) {
    int[] l = locate(a, off);
    if (l[1] == 0) return l[0];
    N r = a.get(l[0]);
    N right = run(r.text.substring(l[1]), r.normal);
    r.text = r.text.substring(0, l[1]);
    a.add(l[0] + 1, right);
    return l[0] + 1;
  }

  // ------------------------------------------------------------------------ editing

  /** Inserts literal text at the caret (no AutoCorrect or build-up). */
  void insertText(String text, boolean normal) {
    pos = clamp(pos);
    if (text.isEmpty()) return;
    List<N> a = slotAt(pos.path);
    if (a == null) return;
    int[] l = locate(a, pos.off);
    if (l[1] > 0 && a.get(l[0]).normal == normal) {
      N r = a.get(l[0]);
      r.text = r.text.substring(0, l[1]) + text + r.text.substring(l[1]);
    } else {
      int i = split(a, pos.off);
      if (i > 0 && a.get(i - 1).run() && a.get(i - 1).normal == normal) a.get(i - 1).text += text;
      else if (i < a.size() && a.get(i).run() && a.get(i).normal == normal) a.get(i).text = text + a.get(i).text;
      else a.add(i, run(text, normal));
    }
    pos = pos.with(pos.off + text.length());
  }

  /** Inserts a structure template in linear form; the caret goes to its first empty slot. */
  void insertTemplate(String linear, String limits) {
    // Wordcraft also forces stacked or side limits for some templates; linear text (how the
    // document stores an equation) cannot carry the placement, so the parser's default is kept.
    insertNodes(parse(linear));
  }

  void insertNodes(List<N> nodes) {
    pos = clamp(pos);
    List<N> a = slotAt(pos.path);
    if (a == null) return;
    int at = split(a, pos.off);
    a.addAll(at, nodes);
    // Tidying merges runs around the insertion, so find the inserted nodes again by identity.
    tidy();
    int first = -1;
    for (int k = 0; k < a.size() && first < 0; k++) if (!nodes.isEmpty() && a.get(k) == nodes.get(0)) first = k;
    if (first < 0) {
      pos = clamp(pos);
      return;
    }
    for (int k = first; k < a.size(); k++) {
      if (!nodes.contains(a.get(k))) break;
      Pos inner = firstEmpty(a.get(k), pos.path, k, 0);
      if (inner != null) {
        pos = inner;
        return;
      }
    }
    int last = first;
    while (last < a.size() && nodes.contains(a.get(last))) last++;
    pos = clamp(new Pos(pos.path, nodeStart(a, last)));
  }

  private static Pos firstEmpty(N n, int[] prefix, int index, int depth) {
    if (depth > MAX_DEPTH || n.run()) return null;
    for (int c : nav(n)) {
      List<N> s = n.slots.get(c);
      Pos here = new Pos(prefix, 0).child(index, c, 0);
      if (s.isEmpty()) return here;
      for (int k = 0; k < s.size(); k++) {
        Pos p = firstEmpty(s.get(k), here.path, k, depth + 1);
        if (p != null) return p;
      }
    }
    return null;
  }

  /** Merges adjacent compatible runs and drops empty ones (structures stay). */
  void tidy() {
    tidy(root, 0);
  }

  private static void tidy(List<N> a, int depth) {
    if (depth > MAX_DEPTH) return;
    for (int i = a.size() - 1; i >= 0; i--) {
      N n = a.get(i);
      if (n.run() && n.text.isEmpty()) a.remove(i);
      else if (n.run() && i > 0 && a.get(i - 1).run() && a.get(i - 1).normal == n.normal) {
        a.get(i - 1).text += n.text;
        a.remove(i);
      }
    }
    for (N n : a) for (List<N> s : n.slots) tidy(s, depth + 1);
  }

  private static boolean allEmpty(N n) {
    for (int c : nav(n)) if (!n.slots.get(c).isEmpty()) return false;
    return true;
  }

  private static List<N> dissolve(N n) {
    List<N> out = new ArrayList<>();
    for (int c : nav(n)) out.addAll(n.slots.get(c));
    return out;
  }

  private static boolean high(char c) {
    return c >= '\ud800' && c <= '\udbff';
  }

  private static boolean low(char c) {
    return c >= '\udc00' && c <= '\udfff';
  }

  /** Characters a step covers backward from {@code off} in a run (a surrogate pair is one). */
  private static int stepBack(String t, int k) {
    return k >= 2 && low(t.charAt(k - 1)) && high(t.charAt(k - 2)) ? 2 : 1;
  }

  private static int stepForward(String t, int k) {
    return k + 1 < t.length() && high(t.charAt(k)) && low(t.charAt(k + 1)) ? 2 : 1;
  }

  /** Backspace. */
  void deleteBack() {
    pos = clamp(pos);
    if (pos.off > 0) {
      List<N> a = slotAt(pos.path);
      int[] l = locate(a, pos.off - 1);
      N n = a.get(l[0]);
      if (n.run()) {
        int end = pos.off - nodeStart(a, l[0]);
        int step = stepBack(n.text, end);
        n.text = n.text.substring(0, end - step) + n.text.substring(end);
        Pos out = pos.with(pos.off - step);
        tidy();
        pos = clamp(out);
        return;
      }
      if (allEmpty(n)) {
        a.remove(l[0]);
        Pos out = pos.with(pos.off - 1);
        tidy();
        pos = clamp(out);
        return;
      }
      // Into the structure's last slot, at its end.
      int[] order = nav(n);
      int c = order[order.length - 1];
      pos = pos.child(l[0], c, units(n.slots.get(c)));
      return;
    }
    if (pos.depth() == 0) return;
    int j = pos.lastNode(), c = pos.lastSlot();
    Pos parentPos = pos.parent();
    List<N> parent = slotAt(parentPos.path);
    int start = nodeStart(parent, j);
    N node = parent.get(j);
    int[] order = nav(node);
    int at = indexOf(order, c);
    if (allEmpty(node) || at == 0) {
      parent.remove(j);
      if (!allEmpty(node)) parent.addAll(j, dissolve(node));
      tidy();
      pos = clamp(parentPos.with(start));
      return;
    }
    if (at > 0) {
      int pc = order[at - 1];
      pos = parentPos.child(j, pc, units(node.slots.get(pc)));
    } else pos = parentPos.with(start);
  }

  /** Delete (forward). */
  void deleteForward() {
    pos = clamp(pos);
    List<N> a = slotAt(pos.path);
    if (pos.off < units(a)) {
      int[] l = locate(a, pos.off);
      N n = a.get(l[0]);
      if (n.run()) {
        int k = l[1];
        n.text = n.text.substring(0, k) + n.text.substring(k + stepForward(n.text, k));
      } else if (allEmpty(n)) a.remove(l[0]);
      else {
        pos = pos.child(l[0], nav(n)[0], 0);
        return;
      }
      Pos out = pos;
      tidy();
      pos = clamp(out);
      return;
    }
    if (pos.depth() == 0) return;
    int j = pos.lastNode(), c = pos.lastSlot();
    Pos parentPos = pos.parent();
    List<N> parent = slotAt(parentPos.path);
    int start = nodeStart(parent, j);
    N node = parent.get(j);
    if (allEmpty(node)) {
      parent.remove(j);
      tidy();
      pos = clamp(parentPos.with(start));
      return;
    }
    int[] order = nav(node);
    int at = indexOf(order, c);
    pos = at >= 0 && at + 1 < order.length ? parentPos.child(j, order[at + 1], 0) : parentPos.with(start + 1);
  }

  // ------------------------------------------------------------------------ navigation

  /** One step right; false at the very end (the caret leaves the equation). */
  boolean moveRight() {
    Pos p = clamp(pos);
    List<N> a = slotAt(p.path);
    if (p.off < units(a)) {
      int[] l = locate(a, p.off);
      N n = a.get(l[0]);
      if (n.run()) {
        pos = p.with(p.off + stepForward(n.text, l[1]));
        return true;
      }
      int[] order = nav(n);
      pos = order.length > 0 ? p.child(l[0], order[0], 0) : p.with(p.off + 1);
      return true;
    }
    if (p.depth() == 0) return false;
    int j = p.lastNode(), c = p.lastSlot();
    Pos parentPos = p.parent();
    List<N> parent = slotAt(parentPos.path);
    int[] order = nav(parent.get(j));
    int at = indexOf(order, c);
    pos = at >= 0 && at + 1 < order.length ? parentPos.child(j, order[at + 1], 0) : parentPos.with(nodeStart(parent, j) + 1);
    return true;
  }

  /** One step left; false at the very start. */
  boolean moveLeft() {
    Pos p = clamp(pos);
    List<N> a = slotAt(p.path);
    if (p.off > 0) {
      int[] l = locate(a, p.off - 1);
      N n = a.get(l[0]);
      if (n.run()) {
        pos = p.with(p.off - stepBack(n.text, p.off - nodeStart(a, l[0])));
        return true;
      }
      int[] order = nav(n);
      if (order.length == 0) {
        pos = p.with(p.off - 1);
        return true;
      }
      int c = order[order.length - 1];
      pos = p.child(l[0], c, units(n.slots.get(c)));
      return true;
    }
    if (p.depth() == 0) return false;
    int j = p.lastNode(), c = p.lastSlot();
    Pos parentPos = p.parent();
    List<N> parent = slotAt(parentPos.path);
    N node = parent.get(j);
    int[] order = nav(node);
    int at = indexOf(order, c);
    if (at > 0) pos = parentPos.child(j, order[at - 1], units(node.slots.get(order[at - 1])));
    else pos = parentPos.with(nodeStart(parent, j));
    return true;
  }

  private static int vertical(N n, int c, boolean up) {
    switch (n.kind) {
      case "f":
        return c == 1 && up ? 0 : c == 0 && !up ? 1 : -1;
      case "sSup":
        return c == 0 && up ? 1 : c == 1 && !up ? 0 : -1;
      case "sSub":
        return c == 0 && !up ? 1 : c == 1 && up ? 0 : -1;
      case "sSubSup": case "sPre":
        return up && (c == 1 || c == 0) ? 2 : !up && (c == 2 || c == 0) ? 1 : -1;
      case "nary":
        return c == 1 && up && !n.hidden[2] ? 2 : c == 2 && !up && !n.hidden[1] ? 1 : -1;
      case "limLow":
        return c == 0 && !up ? 1 : c == 1 && up ? 0 : -1;
      case "limUpp":
        return c == 0 && up ? 1 : c == 1 && !up ? 0 : -1;
      case "eqArr":
        return up ? c - 1 : c + 1 < n.slots.size() ? c + 1 : -1;
      case "m": {
        int k = 0, r = -1, col = 0;
        for (int i = 0; i < n.rowLens.length; i++) {
          if (c < k + n.rowLens[i]) {
            r = i;
            col = c - k;
            break;
          }
          k += n.rowLens[i];
        }
        int tr = up ? r - 1 : r + 1;
        if (r < 0 || tr < 0 || tr >= n.rowLens.length || n.rowLens[tr] == 0) return -1;
        int start = 0;
        for (int i = 0; i < tr; i++) start += n.rowLens[i];
        return start + Math.min(col, n.rowLens[tr] - 1);
      }
      default:
        return -1;
    }
  }

  /** Up or down into the nearest stacked slot; false when there is none. */
  boolean moveVertical(boolean up) {
    Pos p = clamp(pos);
    for (int level = p.depth() - 1; level >= 0; level--) {
      List<N> parent = slotAt(root, p.path, level * 2);
      N node = parent.get(p.path[level * 2]);
      int t = vertical(node, p.path[level * 2 + 1], up);
      if (t >= 0) {
        int[] path = java.util.Arrays.copyOf(p.path, level * 2 + 2);
        path[level * 2 + 1] = t;
        int len = units(node.slots.get(t));
        pos = new Pos(path, level + 1 == p.depth() ? Math.min(p.off, len) : len);
        return true;
      }
    }
    return false;
  }

  /** Start or end of the current slot; from there, of the whole equation. */
  void homeEnd(boolean end) {
    Pos p = clamp(pos);
    int target = end ? units(slotAt(p.path)) : 0;
    pos = p.off != target || p.depth() == 0 ? p.with(target) : new Pos(null, end ? units(root) : 0);
  }

  private static void slotPaths(List<N> a, int[] prefix, List<int[]> out, int depth) {
    if (depth > MAX_DEPTH || out.size() > 10000) return;
    out.add(prefix);
    for (int i = 0; i < a.size(); i++)
      for (int c : nav(a.get(i))) slotPaths(a.get(i).slots.get(c), new Pos(prefix, 0).child(i, c, 0).path, out, depth + 1);
  }

  /** Tab / Shift+Tab: the next (previous) empty slot, wrapping; false when there is none. */
  boolean nextPlaceholder(boolean back) {
    List<int[]> paths = new ArrayList<>();
    slotPaths(root, new int[0], paths, 0);
    int cur = 0;
    for (int i = 0; i < paths.size(); i++) if (java.util.Arrays.equals(paths.get(i), pos.path)) cur = i;
    int pick = -1;
    for (int k = 1; k <= paths.size(); k++) {
      int i = ((back ? cur - k : cur + k) % paths.size() + paths.size()) % paths.size();
      int[] p = paths.get(i);
      if (p.length > 0 && slotAt(p).isEmpty()) {
        pick = i;
        break;
      }
    }
    if (pick < 0) return false;
    pos = new Pos(paths.get(pick), 0);
    return true;
  }

  // ------------------------------------------------------------------------ AutoCorrect, build-up

  /** The character Math AutoCorrect gives {@code \name}, or null. */
  static String symbol(String name) {
    for (String[] s : EquationGallery.AUTOCORRECT) if (s[0].equals(name)) return s[1];
    String alnum = mathAlnum(name);
    if (alnum != null) return alnum;
    return Equation.symbol(name);
  }

  /** {@code doubleR}, {@code scriptL}, {@code frakturg}: the math alphanumeric letter. */
  static String mathAlnum(String name) {
    String[] prefixes = {"double", "script", "fraktur"};
    for (int k = 0; k < 3; k++) {
      if (!name.startsWith(prefixes[k]) || name.length() != prefixes[k].length() + 1) continue;
      char ch = name.charAt(name.length() - 1);
      boolean upper = ch >= 'A' && ch <= 'Z', lower = ch >= 'a' && ch <= 'z', digit = ch >= '0' && ch <= '9';
      String holes = k == 0 ? "CHNPQRZ" : k == 1 ? "BEFHILMRego" : "CHIRZ";
      String fill = k == 0 ? "\u2102\u210d\u2115\u2119\u211a\u211d\u2124"
          : k == 1 ? "\u212c\u2130\u2131\u210b\u2110\u2112\u2133\u211b\u212f\u210a\u2134" : "\u212d\u210c\u2111\u211c\u2128";
      int hole = holes.indexOf(ch);
      if (hole >= 0) return fill.substring(hole, hole + 1);
      int cp;
      if (upper) cp = (k == 0 ? 0x1D538 : k == 1 ? 0x1D49C : 0x1D504) + (ch - 'A');
      else if (lower) cp = (k == 0 ? 0x1D552 : k == 1 ? 0x1D4B6 : 0x1D51E) + (ch - 'a');
      else if (digit && k == 0) cp = 0x1D7D8 + (ch - '0');
      else return null;
      cp -= 0x10000;
      return new String(new char[] {(char) (0xd800 + (cp >> 10)), (char) (0xdc00 + (cp & 0x3ff))});
    }
    return null;
  }

  /** Replaces {@code \name} just before the caret with its character; true when it did. */
  boolean autocorrect() {
    pos = clamp(pos);
    List<N> a = slotAt(pos.path);
    if (a == null || pos.off == 0) return false;
    int[] l = locate(a, pos.off - 1);
    N r = a.get(l[0]);
    if (!r.run() || r.normal) return false;
    int k = pos.off - nodeStart(a, l[0]);
    int slash = r.text.lastIndexOf('\\', k - 1);
    if (slash < 0) return false;
    String name = r.text.substring(slash + 1, k);
    if (name.isEmpty()) return false;
    for (int i = 0; i < name.length(); i++) {
      char ch = name.charAt(i);
      if (!(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9')) return false;
    }
    String c = symbol(name);
    if (c == null) return false;
    r.text = r.text.substring(0, slash) + c + r.text.substring(k);
    pos = pos.with(pos.off - (k - slash) + c.length());
    return true;
  }

  private static final String BUILDS = "/\u2044\u2215\u00a6^_\u221a\u221b\u221c\u2592\u252c\u2534\u2588\u25a0\u25ad\u25a1"
      + "\u00af\u2581\u27e1\u23de\u23df([{|\u2016\u27e8\u2308\u230a\u3016\u251c\u2061"
      + "\u2211\u220f\u2210\u222b\u222c\u222d\u222e\u222f\u2230\u22c3\u22c2\u22c1\u22c0";
  private static final String[] FUNCTIONS = {
    "sin", "cos", "tan", "cot", "sec", "csc", "sinh", "cosh", "tanh", "arcsin", "arccos", "arctan", "log", "ln",
    "exp", "lim", "max", "min", "det", "gcd", "lg"
  };

  /** Typed text that build-up turns into structures. */
  static boolean builds(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (BUILDS.indexOf(c) >= 0 || c >= '\u0300' && c <= '\u036f' || c >= '\u20d0' && c <= '\u20ff') return true;
    }
    for (String f : FUNCTIONS) if (s.contains(f)) return true;
    return false;
  }

  private static boolean plain(N n) {
    if (n.run()) return !n.normal;
    return !"eqArr".equals(n.kind) && !"m".equals(n.kind);
  }

  private static boolean unbalanced(List<N> nodes, int depth) {
    if (depth > MAX_DEPTH) return false;
    for (N n : nodes) {
      if (n.run() && n.text.indexOf('\\') >= 0) return true;
      if ("d".equals(n.kind) && !n.a.isEmpty() && n.b.isEmpty()) return true;
      for (List<N> s : n.slots) if (unbalanced(s, depth + 1)) return true;
    }
    return false;
  }

  /** More opening than closing brackets: the user is still typing the group. */
  static boolean openBrackets(String text) {
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ("([{\u3016\u27e8\u2308\u230a".indexOf(c) >= 0) depth++;
      else if (")]}\u3017\u27e9\u2309\u230b".indexOf(c) >= 0) depth--;
    }
    return depth > 0;
  }

  /** Builds up the linear text before the caret in its slot; true when the structure changed. */
  boolean buildUp() {
    pos = clamp(pos);
    List<N> a = slotAt(pos.path);
    if (a == null) return false;
    int at = split(a, pos.off);
    int start = 0;
    for (int i = at - 1; i >= 0; i--)
      if (!plain(a.get(i))) {
        start = i + 1;
        break;
      }
    List<N> region = new ArrayList<>(a.subList(start, at));
    String text = linear(region);
    if (region.isEmpty() || !builds(text) || openBrackets(text)) {
      tidy();
      return false;
    }
    List<N> built = parse(text);
    tidy(built, 0);
    List<N> merged = copyAll(region);
    tidy(merged, 0);
    if (describe(merged).equals(describe(built)) || unbalanced(built, 0)) {
      tidy();
      return false;
    }
    int before = nodeStart(a, start);
    for (int i = at - 1; i >= start; i--) a.remove(i);
    a.addAll(start, built);
    Pos out = pos.with(before + units(built));
    tidy();
    pos = clamp(out);
    return true;
  }

  /** A structural fingerprint for comparisons and tests. */
  static String describe(List<N> nodes) {
    var b = new StringBuilder();
    describe(nodes, b, 0);
    return b.toString();
  }

  private static void describe(List<N> nodes, StringBuilder b, int depth) {
    if (depth > MAX_DEPTH) return;
    for (N n : nodes) {
      if (n.run()) b.append(n.normal ? "'" : "\"").append(n.text).append(n.normal ? "'" : "\"");
      else {
        b.append(n.kind).append('[').append(n.a).append('|').append(n.b).append(']').append('{');
        for (int i = 0; i < n.slots.size(); i++) {
          if (i > 0) b.append(',');
          describe(n.slots.get(i), b, depth + 1);
        }
        b.append('}');
      }
    }
  }

  private static final String RELATIONS = "=<>\u2260\u2264\u2265\u2248\u2261\u2243\u2245\u221d\u226a\u226b\u2208\u2209"
      + "\u2282\u2283\u2286\u2287\u2192\u2190\u2194\u21d2\u21d0\u21d4\u27f6\u27f5\u27f7\u21a6\u2225\u22a5\u223c\u2250";

  /**
   * Typing, as Wordcraft's {@code equation.type}: a space, tab or relation first applies Math
   * AutoCorrect and build-up to what precedes the caret; a space that triggered a change is
   * consumed. In normal text mode nothing is converted.
   */
  void type(String text, boolean normal) {
    for (int i = 0; i < text.length() && i < 10000; i++) {
      char c = text.charAt(i);
      if (!normal && (c == ' ' || c == '\t' || RELATIONS.indexOf(c) >= 0)) {
        boolean changed = autocorrect();
        changed |= buildUp();
        if (c == ' ' && changed) continue;
      }
      if (high(c) && i + 1 < text.length()) {
        insertText(text.substring(i, i + 2), normal);
        i++;
      } else insertText(String.valueOf(c), normal);
    }
  }

  /** Leaving a slot or pressing Enter finishes what was typed (AutoCorrect, then build-up). */
  boolean finish() {
    boolean changed = autocorrect();
    changed |= buildUp();
    return changed;
  }

  // ------------------------------------------------------------------------ caret geometry

  /** Marker run placed at the caret to find it in a layout (private use; never saved). */
  static final String MARK = "\ue000";

  /**
   * The caret in equation box coordinates: {x, baselineY, size} relative to the equation box's
   * origin (left edge, baseline), from laying out the tree with a marker at the caret.
   */
  float[] caret(float size, LayoutEngine.Metrics metrics) {
    List<N> copy = copyAll(root);
    Pos p = clamp(pos);
    List<N> a = slotAt(copy, p.path, p.path.length);
    if (a == null) return new float[] {0, 0, size};
    int at = split(a, p.off);
    a.add(at, run(MARK, false));
    Equation.Box box = Equation.layout(toNode(copy), size, metrics);
    for (Equation.Glyph g : box.glyphs)
      if (g.text().equals(MARK)) return new float[] {g.x(), g.y(), g.size()};
    return new float[] {0, 0, size};
  }
}
