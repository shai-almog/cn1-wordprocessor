package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Office Math equation tree: read from OMML ({@code m:oMath}/{@code m:oMathPara}) or from the
 * linear format typed into Insert &gt; Equation, and written back as OMML, linear text and LaTeX.
 * {@link #layout} builds a box tree measured through the caller's text metrics, which {@link
 * DrawingOps} turns into the text and line primitives shared by the editor and PDF export.
 *
 * <p>Covered structures: plain runs, fractions (bar, no bar, linear, skewed), sub/superscripts and
 * pre-scripts, radicals with degree, n-ary operators with limits, delimiters with separators,
 * matrices, equation arrays, accents, overbars/underbars, functions, lower/upper limits, group
 * characters and boxes. Nesting deeper than {@link #MAX_DEPTH} is flattened to its text, so hostile
 * input never overflows the stack.
 */
public final class Equation {
  public static final int MAX_DEPTH = 40;
  static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";
  private static final int MAX_NODES = 20000;
  /** Bracket pairs the linear parser reads as a group. */
  private static final String OPENERS = "([{\u27e8\u230a\u2308\u27e6", CLOSERS = ")]}\u27e9\u230b\u2309\u27e7";
  /** Characters that may stand for a bracket in an explicit U+251C...U+2524 group. */
  static final String BRACKETS = "()[]{}\u27e8\u27e9\u2308\u2309\u230a\u230b|\u2016\u27e6\u27e7";
  /**
   * Stored after an n-ary operator whose limits are forced beside it (Word's "Change Limit
   * Location to Subscript-Superscript", Wordcraft's side-limit templates). Linear text, which is
   * how an equation is stored, has no notation for it; a private-use character keeps it.
   */
  static final char SIDE_LIMITS = '\ue001';

  /** One node. Arguments are rows (kind "row") except where noted in {@link Equation}. */
  public static final class Node {
    public final String kind, a, b, c;
    public final List<Node> args;

    Node(String kind, String a, String b, String c, List<Node> args) {
      this.kind = kind;
      this.a = a == null ? "" : a;
      this.b = b == null ? "" : b;
      this.c = c == null ? "" : c;
      this.args = args == null ? Collections.<Node>emptyList() : args;
    }

    public Node arg(int i) {
      return i < args.size() ? args.get(i) : row();
    }

    boolean empty() {
      return "row".equals(kind) && args.isEmpty() || "run".equals(kind) && a.isEmpty();
    }
  }

  private Equation() {}

  static Node row(Node... items) {
    return new Node("row", null, null, null, new ArrayList<>(Arrays.asList(items)));
  }

  static Node rowOf(List<Node> items) {
    return items.size() == 1 && "row".equals(items.get(0).kind) ? items.get(0) : new Node("row", null, null, null, items);
  }

  static Node run(String text, boolean normal) {
    return new Node("run", text, normal ? "1" : "", null, null);
  }

  static Node node(String kind, String a, String b, String c, Node... args) {
    return new Node(kind, a, b, c, new ArrayList<>(Arrays.asList(args)));
  }

  // ============================================================================ OMML import

  /** The tree of an {@code m:oMath} or {@code m:oMathPara}; never throws on hostile nesting. */
  public static Node fromOmml(Element e) {
    int[] budget = {MAX_NODES};
    if ("oMathPara".equals(Xml.local(e))) {
      var rows = new ArrayList<Node>();
      for (Element m : Xml.children(e, null)) if ("oMath".equals(Xml.local(m))) rows.add(container(m, 1, budget));
      return rows.size() == 1 ? rows.get(0) : new Node("eqArr", null, null, null, rows);
    }
    return container(e, 1, budget);
  }

  private static Node container(Element e, int depth, int[] budget) {
    if (e == null) return row();
    if (depth > MAX_DEPTH || budget[0] <= 0) return row(run(flatText(e), false));
    var items = new ArrayList<Node>();
    for (Element c : Xml.children(e, null)) {
      Node n = item(c, depth, budget);
      if (n != null) items.add(n);
    }
    return new Node("row", null, null, null, items);
  }

  private static Node arg(Element e, String name, int depth, int[] budget) {
    return container(Xml.child(e, name), depth + 1, budget);
  }

  private static String prop(Element e, String pr, String name, String fallback) {
    Element p = Xml.child(e, pr);
    Element v = p == null ? null : Xml.child(p, name);
    return v == null ? fallback : Xml.attr(v, "val", "");
  }

  private static boolean on(Element e, String pr, String name) {
    Element p = Xml.child(e, pr);
    Element v = p == null ? null : Xml.child(p, name);
    if (v == null) return false;
    String val = Xml.attr(v, "val", "1");
    return "1".equals(val) || "on".equals(val) || "true".equals(val);
  }

  private static Node item(Element e, int depth, int[] budget) {
    budget[0]--;
    String k = Xml.local(e);
    if (k.endsWith("Pr")) return null;
    switch (k) {
      case "r": {
        var t = new StringBuilder();
        for (Element c : Xml.children(e, null))
          if ("t".equals(Xml.local(c))) t.append(Xml.text(c));
        Element rPr = Xml.child(e, "rPr");
        boolean normal = rPr != null && (Xml.child(rPr, "nor") != null
            || "p".equals(prop(e, "rPr", "sty", "")));
        return t.length() == 0 ? null : run(t.toString(), normal);
      }
      case "f": {
        String type = prop(e, "fPr", "type", "bar");
        return node("f", type, null, null, arg(e, "num", depth, budget), arg(e, "den", depth, budget));
      }
      case "sSup":
        return node("sSup", null, null, null, arg(e, "e", depth, budget), arg(e, "sup", depth, budget));
      case "sSub":
        return node("sSub", null, null, null, arg(e, "e", depth, budget), arg(e, "sub", depth, budget));
      case "sSubSup":
        return node("sSubSup", null, null, null, arg(e, "e", depth, budget), arg(e, "sub", depth, budget), arg(e, "sup", depth, budget));
      case "sPre":
        return node("sPre", null, null, null, arg(e, "e", depth, budget), arg(e, "sub", depth, budget), arg(e, "sup", depth, budget));
      case "rad":
        return node("rad", on(e, "radPr", "degHide") ? "1" : "", null, null, arg(e, "e", depth, budget), arg(e, "deg", depth, budget));
      case "nary": {
        String chr = prop(e, "naryPr", "chr", "\u222b");
        String loc = prop(e, "naryPr", "limLoc", integral(chr) ? "subSup" : "undOvr");
        return node("nary", chr, loc, null, arg(e, "e", depth, budget), arg(e, "sub", depth, budget), arg(e, "sup", depth, budget));
      }
      case "d": {
        var parts = new ArrayList<Node>();
        for (Element c : Xml.children(e, null)) if ("e".equals(Xml.local(c))) parts.add(container(c, depth + 1, budget));
        return new Node("d", prop(e, "dPr", "begChr", "("), prop(e, "dPr", "endChr", ")"), prop(e, "dPr", "sepChr", "|"), parts);
      }
      case "m": {
        var rows = new ArrayList<Node>();
        for (Element r : Xml.children(e, null))
          if ("mr".equals(Xml.local(r))) {
            var cells = new ArrayList<Node>();
            for (Element c : Xml.children(r, null)) if ("e".equals(Xml.local(c))) cells.add(container(c, depth + 1, budget));
            rows.add(new Node("mr", null, null, null, cells));
          }
        return new Node("m", null, null, null, rows);
      }
      case "eqArr": {
        var rows = new ArrayList<Node>();
        for (Element c : Xml.children(e, null)) if ("e".equals(Xml.local(c))) rows.add(container(c, depth + 1, budget));
        return new Node("eqArr", null, null, null, rows);
      }
      case "acc":
        return node("acc", prop(e, "accPr", "chr", "\u0302"), null, null, arg(e, "e", depth, budget));
      case "bar":
        return node("bar", prop(e, "barPr", "pos", "bot"), null, null, arg(e, "e", depth, budget));
      case "func":
        return node("func", null, null, null, arg(e, "e", depth, budget), arg(e, "fName", depth, budget));
      case "limLow": case "limUpp":
        return node(k, null, null, null, arg(e, "e", depth, budget), arg(e, "lim", depth, budget));
      case "groupChr":
        return node("groupChr", prop(e, "groupChrPr", "chr", "\u23df"), prop(e, "groupChrPr", "pos", "bot"), null, arg(e, "e", depth, budget));
      case "box": case "borderBox": case "phant":
        return node("box", k, null, null, arg(e, "e", depth, budget));
      case "oMath": case "e":
        return container(e, depth + 1, budget);
      default: {
        String text = flatText(e);
        return text.isEmpty() ? null : run(text, false);
      }
    }
  }

  static boolean integral(String chr) {
    return chr.length() == 1 && (chr.charAt(0) >= '\u222b' && chr.charAt(0) <= '\u2233');
  }

  /** All text below {@code e}, iteratively so hostile depth cannot overflow the stack. */
  static String flatText(Element e) {
    var out = new StringBuilder();
    var stack = new ArrayList<Element>();
    stack.add(e);
    while (!stack.isEmpty() && out.length() < 100000) {
      Element x = stack.remove(stack.size() - 1);
      if (x.isTextElement()) continue;
      if ("t".equals(Xml.local(x))) {
        out.append(Xml.text(x));
        continue;
      }
      for (int i = x.getNumChildren() - 1; i >= 0; i--) stack.add(x.getChildAt(i));
    }
    return out.toString();
  }

  // ============================================================================ linear format

  private static final String FUNCS =
      " sin cos tan cot sec csc sinh cosh tanh csch sech coth arcsin arccos arctan log ln exp lim max min det gcd lg ";
  private static final String NARY = "\u2211\u220f\u2210\u222b\u222c\u222d\u222e\u222f\u2230\u22c3\u22c2\u22c1\u22c0\u2a00\u2a01\u2a02\u2a04\u2a06";
  private static final String[][] WORDS = {
    {"sum", "\u2211"}, {"prod", "\u220f"}, {"coprod", "\u2210"}, {"int", "\u222b"},
    {"iint", "\u222c"}, {"iiint", "\u222d"}, {"oint", "\u222e"}, {"bigcup", "\u22c3"},
    {"bigcap", "\u22c2"}, {"cdots", "\u22ef"}, {"ldots", "\u2026"}, {"to", "\u2192"},
    {"langle", "\u27e8"}, {"rangle", "\u27e9"}, {"neq", "\u2260"}, {"leq", "\u2264"},
    {"geq", "\u2265"}, {"Rightarrow", "\u21d2"}, {"Leftrightarrow", "\u21d4"}, {"subset", "\u2282"},
    {"cup", "\u222a"}, {"cap", "\u2229"}, {"psi", "\u03c8"}, {"chi", "\u03c7"}, {"rho", "\u03c1"},
    {"tau", "\u03c4"}, {"eta", "\u03b7"}, {"zeta", "\u03b6"}, {"kappa", "\u03ba"}, {"nu", "\u03bd"},
    {"xi", "\u03be"}, {"Gamma", "\u0393"}, {"Theta", "\u0398"}, {"Lambda", "\u039b"}, {"Pi", "\u03a0"},
    {"Phi", "\u03a6"}, {"Psi", "\u03a8"}, {"varepsilon", "\u03b5"}, {"varphi", "\u03c6"}
  };

  static String symbol(String word) {
    for (String[] s : DrawingOps.symbols()) if (s[0].equals("\\" + word)) return s[1];
    for (String[] s : WORDS) if (s[0].equals(word)) return s[1];
    return null;
  }

  /** Parses linear format ({@code a/b}, {@code x^2}, {@code sqrt(x)}, {@code \sum_(i=1)^n i}). */
  public static Node fromLinear(String text) {
    try {
      var p = new Linear(text == null ? "" : text);
      Node r = p.parseRow("");
      return r;
    } catch (IllegalArgumentException | IndexOutOfBoundsException tooDeep) {
      return row(run(text, false));
    }
  }

  private static final class Linear {
    final String s;
    int i, depth, nodes;

    Linear(String s) {
      this.s = s;
    }

    char peek() {
      while (i < s.length() && s.charAt(i) == ' ') i++;
      return i < s.length() ? s.charAt(i) : 0;
    }

    void enter() {
      if (++depth > MAX_DEPTH || ++nodes > MAX_NODES) throw new IllegalArgumentException("Equation too deep");
    }

    Node parseRow(String stop) {
      enter();
      var items = new ArrayList<Node>();
      while (true) {
        char c = peek();
        if (c == 0 || stop.indexOf(c) >= 0) break;
        Node n = fraction(stop);
        if (n != null) items.add(n);
      }
      depth--;
      return new Node("row", null, null, null, items);
    }

    Node fraction(String stop) {
      Node a = scripted(stop);
      while (true) {
        char c = peek();
        if (c != '/' && c != '\u00a6' && c != '\u2298' && c != '\u2044' && c != '\u2215') return a;
        i++;
        Node b = scripted(stop);
        a = node("f", c == '/' ? "bar" : c == '\u00a6' ? "noBar" : c == '\u2044' ? "skw" : "lin", null, null, strip(a), strip(b == null ? row() : b));
      }
    }

    Node operand(String stop) {
      Node n = primary(stop);
      return strip(n == null ? row() : n);
    }

    Node scripted(String stop) {
      enter();
      Node base = primary(stop);
      if (base == null) {
        depth--;
        return null;
      }
      while (i < s.length() && s.charAt(i) >= '\u0300' && s.charAt(i) <= '\u036f' || i < s.length() && s.charAt(i) >= '\u20d0' && s.charAt(i) <= '\u20ef')
        base = node("acc", String.valueOf(s.charAt(i++)), null, null, strip(base));
      Node sub = null, sup = null;
      boolean stacked = false;
      while (true) {
        char c = peek();
        if ((c == '\u252c' || c == '\u2534') && "nary".equals(base.c) && (c == '\u252c' ? sub : sup) == null) {
          // UnicodeMath's below/above operators on an n-ary operator force stacked limits.
          i++;
          stacked = true;
          if (c == '\u252c') sub = operand(stop);
          else sup = operand(stop);
        } else if (c == '_' && sub == null) {
          i++;
          sub = operand(stop);
        } else if (c == '^' && sup == null) {
          i++;
          sup = operand(stop);
        } else if (c == '\u252c' || c == '\u2534') {
          i++;
          base = node(c == '\u252c' ? "limLow" : "limUpp", null, null, null, strip(base), operand(stop));
        } else break;
      }
      Node out;
      if ("nary".equals(base.c)) {
        if (peek() == '\u2592') i++;
        Node body = scripted(stop);
        String loc = stacked ? "undOvr" : !base.b.isEmpty() ? base.b : integral(base.a) ? "subSup" : "undOvr";
        out = node("nary", base.a, loc, null, body == null ? row() : strip(body),
            sub == null ? row() : sub, sup == null ? row() : sup);
      } else if ("func".equals(base.c)) {
        Node name = run(base.a, true);
        if (sub != null && "lim".equals(base.a)) name = node("limLow", null, null, null, row(name), sub);
        else if (sub != null || sup != null) name = script(name, sub, sup);
        if (peek() == '\u2061') i++;
        Node arg = peek() == 0 || stop.indexOf(peek()) >= 0 ? null : scripted(stop);
        out = arg == null ? name : node("func", null, null, null, arg, row(name));
      } else out = sub == null && sup == null ? base : script(base, sub, sup);
      depth--;
      return out;
    }

    Node script(Node base, Node sub, Node sup) {
      if (sub != null && sup != null) return node("sSubSup", null, null, null, base, sub, sup);
      return sub != null ? node("sSub", null, null, null, base, sub) : node("sSup", null, null, null, base, sup);
    }

    Node group(char open, char close) {
      return group(open, String.valueOf(close));
    }

    /**
     * A bracket group after {@code open} (0: none, after {@code U+251C}) up to one of
     * {@code closers}; {@code U+2524} closes it with no bracket and {@code U+2502} separates
     * elements, as in UnicodeMath and Wordcraft's linear parser.
     */
    Node group(char open, String closers) {
      if (open != 0) i++;
      var rows = new ArrayList<Node>();
      var cells = new ArrayList<Node>();
      boolean grid = false;
      String end = "";
      while (true) {
        cells.add(parseRow(closers + "&@\u2524\u2502"));
        char c = peek();
        if (c == '\u2502' && !grid) {
          i++;
        } else if (c == '\u2524' && !grid) {
          i++;
          break;
        } else if (c == '&') {
          grid = true;
          i++;
        } else if (c == '@') {
          grid = true;
          i++;
          rows.add(new Node("mr", null, null, null, cells));
          cells = new ArrayList<>();
        } else {
          if (c != 0 && closers.indexOf(c) >= 0) {
            i++;
            end = String.valueOf(c);
          } else if (closers.length() == 1) end = closers;
          break;
        }
      }
      if (grid) {
        rows.add(new Node("mr", null, null, null, cells));
        return new Node("m", null, null, null, rows);
      }
      return new Node("d", open == 0 ? "" : String.valueOf(open), end, "|", cells);
    }

    /**
     * {@code U+251C X ... Y U+2524}: brackets given explicitly, either side optional (any bracket
     * character, so outward or doubled brackets); the cursor is just after {@code U+251C}. Null
     * (cursor unchanged) when there is no matching {@code U+2524}.
     */
    Node explicitGroup() {
      int level = 1, k = i;
      for (; k < s.length(); k++) {
        char c = s.charAt(k);
        if (c == '\u251c') level++;
        else if (c == '\u2524' && --level == 0) break;
      }
      if (level != 0) return null;
      String inner = s.substring(i, k);
      i = k + 1;
      String beg = "", end = "";
      if (!inner.isEmpty() && BRACKETS.indexOf(inner.charAt(0)) >= 0) {
        beg = inner.substring(0, 1);
        inner = inner.substring(1);
      }
      if (!inner.isEmpty() && BRACKETS.indexOf(inner.charAt(inner.length() - 1)) >= 0 && !paired(inner)) {
        end = inner.substring(inner.length() - 1);
        inner = inner.substring(0, inner.length() - 1);
      }
      var sub = new Linear(inner);
      sub.depth = depth;
      sub.nodes = nodes;
      var cells = new ArrayList<Node>();
      while (true) {
        cells.add(sub.parseRow("\u2502"));
        sub.depth = depth;
        if (sub.peek() == '\u2502') sub.i++;
        else if (sub.peek() != 0) sub.i++;
        else break;
      }
      nodes = sub.nodes;
      return new Node("d", beg, end, "|", cells);
    }

    /** Whether a bar at the cursor has a matching bar later at the same bracket level. */
    boolean barCloses(char bar) {
      int level = 0;
      for (int k = i + 1; k < s.length(); k++) {
        char c = s.charAt(k);
        if (OPENERS.indexOf(c) >= 0 || c == '\u251c') level++;
        else if (CLOSERS.indexOf(c) >= 0 || c == '\u2524') {
          if (--level < 0) return false;
        } else if (c == bar && level == 0) return true;
      }
      return false;
    }

    /** A closing bracket ending {@code t} that pairs with an opening one inside is content. */
    private static boolean paired(String t) {
      char last = t.charAt(t.length() - 1);
      int at = ")]}\u27e9\u2309\u230b\u27e7".indexOf(last);
      if (at < 0) return false;
      char opener = "([{\u27e8\u2308\u230a\u27e6".charAt(at);
      int open = 0, close = 0;
      for (int j = 0; j < t.length() - 1; j++) {
        if (t.charAt(j) == opener) open++;
        else if (t.charAt(j) == last) close++;
      }
      return open > close;
    }

    Node primary(String stop) {
      char c = peek();
      if (c == 0 || stop.indexOf(c) >= 0) return null;
      enter();
      try {
        if (c == '(') return group('(', ')');
        if (c == '[') return group('[', ']');
        if (c == '{') return group('{', '}');
        if (c == '\u27e8') return group('\u27e8', '\u27e9');
        if (c == '\u3016') {
          // Word's invisible grouping brackets around an n-ary or function argument.
          i++;
          Node r = parseRow("\u3017");
          if (peek() == '\u3017') i++;
          return r;
        }
        if (c == '\u230a') return group('\u230a', '\u230b');
        if (c == '\u2308') return group('\u2308', '\u2309');
        if (c == '\u27e6') return group('\u27e6', '\u27e7');
        if (c == '\u221a') {
          i++;
          return radical(operand(stop));
        }
        if (c == '\u25a0' || c == '\u2588') {
          i++;
          Node m = operand(stop);
          if (!"m".equals(m.kind)) m = new Node("m", null, null, null, new ArrayList<>(Collections.singletonList(new Node("mr", null, null, null, new ArrayList<>(Collections.singletonList(m))))));
          if (c == '\u25a0') return m;
          var rows = new ArrayList<Node>();
          for (Node r : m.args) rows.add(rowOf(new ArrayList<>(r.args)));
          return new Node("eqArr", null, null, null, rows);
        }
        if (c == '\u25a1') {
          i++;
          return node("box", "box", null, null, operand(stop));
        }
        if (c == '\u2581' || c == '\u00af') {
          i++;
          return node("bar", c == '\u00af' ? "top" : "bot", null, null, operand(stop));
        }
        if (c == '\u23df' || c == '\u23de') {
          i++;
          return node("groupChr", String.valueOf(c), c == '\u23de' ? "top" : "bot", null, operand(stop));
        }
        if (NARY.indexOf(c) >= 0) {
          i++;
          // SIDE_LIMITS after the operator: limits forced beside it (see SIDE_LIMITS).
          boolean side = i < s.length() && s.charAt(i) == SIDE_LIMITS;
          if (side) i++;
          return new Node("run", String.valueOf(c), side ? "subSup" : "", "nary", null);
        }
        if ((c == '|' || c == '\u2016') && barCloses(c)) return group(c, String.valueOf(c));
        if (c == '\u251c') {
          i++;
          Node explicit = explicitGroup();
          return explicit != null ? explicit : group((char) 0, ")]}\u27e9\u2309\u230b\u27e7");
        }
        if (c == '\\') {
          int j = i + 1;
          while (j < s.length() && Unicode.letter(s.charAt(j)) && s.charAt(j) < 128) j++;
          String word = s.substring(i + 1, j);
          i = j;
          if ("sqrt".equals(word)) return radical(operand(stop));
          if (FUNCS.contains(" " + word + " ") && !word.isEmpty()) return new Node("run", word, "1", "func", null);
          String sym = word.isEmpty() ? null : symbol(word);
          if (sym == null) return run(word.isEmpty() ? "\\" : word, !word.isEmpty());
          if (NARY.indexOf(sym.charAt(0)) >= 0) return new Node("run", sym, "", "nary", null);
          return run(sym, false);
        }
        if (c >= '0' && c <= '9' || c == '.') {
          int j = i;
          while (j < s.length() && (s.charAt(j) >= '0' && s.charAt(j) <= '9' || s.charAt(j) == '.')) j++;
          String t = s.substring(i, j);
          i = j;
          return run(t, false);
        }
        if (Unicode.letter(c)) {
          int j = i;
          while (j < s.length() && Unicode.letter(s.charAt(j))) j++;
          String word = s.substring(i, j);
          if (FUNCS.contains(" " + word + " ")) {
            i = j;
            return new Node("run", word, "1", "func", null);
          }
          if ("sqrt".equals(word) && j < s.length() && s.charAt(j) == '(') {
            i = j;
            return radical(operand(stop));
          }
          i = j;
          return run(word, false);
        }
        i++;
        return run(String.valueOf(c), false);
      } finally {
        depth--;
      }
    }

    Node radical(Node body) {
      if ("m".equals(body.kind) && body.args.size() == 1 && body.args.get(0).args.size() == 2)
        return node("rad", "", null, null, body.args.get(0).args.get(1), body.args.get(0).args.get(0));
      return node("rad", "1", null, null, body, row());
    }
  }

  /** Drops the grouping parentheses of an operand: {@code (a+b)/c} has numerator {@code a+b}. */
  static Node strip(Node n) {
    if ("d".equals(n.kind) && "(".equals(n.a) && ")".equals(n.b) && n.args.size() == 1) return n.args.get(0);
    return n;
  }

  // ============================================================================ writers

  /** Linear format text that {@link #fromLinear} reads back to the same structure. */
  public static String linear(Node n) {
    var b = new StringBuilder();
    linear(n, b, 0);
    return b.toString();
  }

  private static void op(Node n, StringBuilder b, int depth) {
    boolean atom = "run".equals(n.kind) && simple(n.a)
        || "row".equals(n.kind) && n.args.size() == 1 && "run".equals(n.args.get(0).kind) && simple(n.args.get(0).a)
        || "d".equals(n.kind) || "m".equals(n.kind)
        || "row".equals(n.kind) && n.args.size() == 1 && ("d".equals(n.args.get(0).kind) && "(".equals(n.args.get(0).a));
    if (atom) linear(n, b, depth);
    else {
      b.append('(');
      linear(n, b, depth);
      b.append(')');
    }
  }

  /** An n-ary or function argument: several items go in invisible grouping brackets. */
  private static void body(Node n, StringBuilder b, int depth, boolean func) {
    boolean row = "row".equals(n.kind) && !n.args.isEmpty();
    boolean paren = row && n.args.size() == 1 && "d".equals(n.args.get(0).kind) && "(".equals(n.args.get(0).a);
    boolean atom = row && n.args.size() == 1 && "run".equals(n.args.get(0).kind) && simple(n.args.get(0).a);
    // An n-ary operand drops one level of parentheses when parsed and a function argument keeps
    // them, so Word's invisible grouping brackets carry what plain parentheses cannot.
    if (func ? row && !atom && !paren : paren) {
      b.append('\u3016');
      linear(n, b, depth);
      b.append('\u3017');
    } else op(n, b, depth);
  }

  private static boolean simple(String t) {
    if (t.isEmpty()) return false;
    // The empty-argument placeholder needs no parentheses.
    if ("\u2b1a".equals(t)) return true;
    boolean digits = true, letters = true;
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      digits &= c >= '0' && c <= '9' || c == '.';
      letters &= Unicode.letter(c);
    }
    return digits || letters;
  }

  private static void linear(Node n, StringBuilder b, int depth) {
    if (depth > MAX_DEPTH + 4) return;
    int d = depth + 1;
    switch (n.kind) {
      case "run": {
        boolean func = "1".equals(n.b) && FUNCS.contains(" " + n.a + " ");
        boolean word = "1".equals(n.b) && !func && n.a.length() > 1 && simple(n.a);
        if (word) b.append('\\');
        b.append(n.a);
        if (word) b.append(' ');
        break;
      }
      case "row":
        for (int i = 0; i < n.args.size(); i++) {
          Node c = n.args.get(i);
          // A letter or digit right after a structure would join its last argument when parsed
          // back (x^k a becomes x^(ka)); a space keeps them apart and is dropped on parsing.
          if (i == 0 || "run".equals(n.args.get(i - 1).kind) || "d".equals(n.args.get(i - 1).kind) || b.length() == 0) {
            linear(c, b, d);
            continue;
          }
          var piece = new StringBuilder();
          linear(c, piece, d);
          char last = b.charAt(b.length() - 1), first = piece.length() > 0 ? piece.charAt(0) : ' ';
          boolean letters = Unicode.letter(last) && Unicode.letter(first);
          boolean digits = last >= '0' && last <= '9' && first >= '0' && first <= '9';
          if (letters || digits) b.append(' ');
          b.append(piece);
        }
        break;
      case "f":
        op(n.arg(0), b, d);
        b.append("noBar".equals(n.a) ? "\u00a6" : "lin".equals(n.a) ? "\u2298" : "skw".equals(n.a) ? "\u2044" : "/");
        op(n.arg(1), b, d);
        break;
      case "sSup": case "sSub": case "sSubSup":
        op(n.arg(0), b, d);
        if (!"sSup".equals(n.kind)) {
          b.append('_');
          op(n.arg(1), b, d);
        }
        if (!"sSub".equals(n.kind)) {
          b.append('^');
          op(n.arg("sSup".equals(n.kind) ? 1 : 2), b, d);
        }
        break;
      case "sPre":
        b.append("()_");
        op(n.arg(1), b, d);
        b.append('^');
        op(n.arg(2), b, d);
        op(n.arg(0), b, d);
        break;
      case "rad":
        b.append('\u221a');
        if (n.arg(1).empty()) op(n.arg(0), b, d);
        else {
          b.append('(');
          linear(n.arg(1), b, d);
          b.append('&');
          linear(n.arg(0), b, d);
          b.append(')');
        }
        break;
      case "nary": {
        // Limit placement other than the operator's default is kept: stacked limits as
        // UnicodeMath's below/above operators, side limits as SIDE_LIMITS after the operator.
        String def = integral(n.a) ? "subSup" : "undOvr";
        boolean stacked = "undOvr".equals(n.b) && !def.equals(n.b);
        b.append(n.a);
        if ("subSup".equals(n.b) && !def.equals(n.b)) b.append(SIDE_LIMITS);
        if (!n.arg(1).empty()) {
          b.append(stacked ? '\u252c' : '_');
          op(n.arg(1), b, d);
        }
        if (!n.arg(2).empty()) {
          b.append(stacked ? '\u2534' : '^');
          op(n.arg(2), b, d);
        }
        b.append('\u2592');
        body(n.arg(0), b, d, false);
        break;
      }
      case "d": {
        // Matching pairs are written plainly; a missing closing bracket as U+2524 and any
        // other pairing (one-sided, bars, outward or doubled brackets) as U+251C...U+2524.
        int pair = OPENERS.indexOf(n.a);
        boolean plain = !n.a.isEmpty() && pair >= 0 && n.b.equals(CLOSERS.substring(pair, pair + 1));
        boolean openOnly = !plain && !n.a.isEmpty() && pair >= 0 && n.b.isEmpty();
        if (!plain && !openOnly) b.append('\u251c');
        b.append(n.a);
        for (int i = 0; i < n.args.size(); i++) {
          if (i > 0) b.append("|".equals(n.c) ? "\u2502" : n.c);
          linear(n.args.get(i), b, d);
        }
        b.append(n.b);
        if (!plain) b.append('\u2524');
        break;
      }
      case "m": case "eqArr":
        b.append("m".equals(n.kind) ? '\u25a0' : '\u2588').append('(');
        for (int r = 0; r < n.args.size(); r++) {
          if (r > 0) b.append('@');
          Node rowNode = n.args.get(r);
          if ("mr".equals(rowNode.kind))
            for (int c = 0; c < rowNode.args.size(); c++) {
              if (c > 0) b.append('&');
              linear(rowNode.args.get(c), b, d);
            }
          else linear(rowNode, b, d);
        }
        b.append(')');
        break;
      case "acc":
        op(n.arg(0), b, d);
        b.append(n.a);
        break;
      case "bar":
        b.append("top".equals(n.a) ? '\u00af' : '\u2581');
        op(n.arg(0), b, d);
        break;
      case "func":
        linear(n.arg(1), b, d);
        b.append('\u2061');
        body(n.arg(0), b, d, true);
        break;
      case "limLow": case "limUpp":
        op(n.arg(0), b, d);
        b.append("limLow".equals(n.kind) ? '\u252c' : '\u2534');
        op(n.arg(1), b, d);
        break;
      case "groupChr":
        b.append(n.a);
        op(n.arg(0), b, d);
        break;
      case "box":
        b.append('\u25a1');
        op(n.arg(0), b, d);
        break;
      default:
        for (Node c : n.args) linear(c, b, d);
    }
  }

  /** OMML for one {@code m:oMath} (with its namespace declaration). */
  public static String omml(Node n) {
    var b = new StringBuilder("<m:oMath xmlns:m=\"").append(M).append("\">");
    omml(n, b, 0);
    return b.append("</m:oMath>").toString();
  }

  private static void wrap(String tag, Node n, StringBuilder b, int depth) {
    b.append("<m:").append(tag).append('>');
    omml(n, b, depth + 1);
    b.append("</m:").append(tag).append('>');
  }

  private static void val(StringBuilder b, String pr, String name, String value) {
    b.append("<m:").append(pr).append("><m:").append(name).append(" m:val=\"").append(Xml.attribute(value))
        .append("\"/></m:").append(pr).append('>');
  }

  private static void omml(Node n, StringBuilder b, int depth) {
    if (depth > MAX_DEPTH + 4) return;
    switch (n.kind) {
      case "run":
        b.append("<m:r>");
        if ("1".equals(n.b)) b.append("<m:rPr><m:sty m:val=\"p\"/></m:rPr>");
        b.append("<m:t xml:space=\"preserve\">").append(Xml.escape(n.a)).append("</m:t></m:r>");
        break;
      case "row": case "mr":
        for (Node c : n.args) omml(c, b, depth + 1);
        break;
      case "f":
        b.append("<m:f>");
        if (!"bar".equals(n.a)) val(b, "fPr", "type", n.a);
        wrap("num", n.arg(0), b, depth);
        wrap("den", n.arg(1), b, depth);
        b.append("</m:f>");
        break;
      case "sSup": case "sSub": case "sSubSup": case "sPre":
        b.append("<m:").append(n.kind).append('>');
        if ("sPre".equals(n.kind)) {
          wrap("sub", n.arg(1), b, depth);
          wrap("sup", n.arg(2), b, depth);
          wrap("e", n.arg(0), b, depth);
        } else {
          wrap("e", n.arg(0), b, depth);
          if ("sSup".equals(n.kind)) wrap("sup", n.arg(1), b, depth);
          else wrap("sub", n.arg(1), b, depth);
          if ("sSubSup".equals(n.kind)) wrap("sup", n.arg(2), b, depth);
        }
        b.append("</m:").append(n.kind).append('>');
        break;
      case "rad":
        b.append("<m:rad>");
        if ("1".equals(n.a)) val(b, "radPr", "degHide", "1");
        wrap("deg", n.arg(1), b, depth);
        wrap("e", n.arg(0), b, depth);
        b.append("</m:rad>");
        break;
      case "nary":
        b.append("<m:nary><m:naryPr><m:chr m:val=\"").append(Xml.attribute(n.a)).append("\"/><m:limLoc m:val=\"")
            .append(n.b.isEmpty() ? "undOvr" : n.b).append("\"/>");
        if (n.arg(1).empty()) b.append("<m:subHide m:val=\"1\"/>");
        if (n.arg(2).empty()) b.append("<m:supHide m:val=\"1\"/>");
        b.append("</m:naryPr>");
        wrap("sub", n.arg(1), b, depth);
        wrap("sup", n.arg(2), b, depth);
        wrap("e", n.arg(0), b, depth);
        b.append("</m:nary>");
        break;
      case "d":
        b.append("<m:d><m:dPr><m:begChr m:val=\"").append(Xml.attribute(n.a)).append("\"/><m:sepChr m:val=\"")
            .append(Xml.attribute(n.c)).append("\"/><m:endChr m:val=\"").append(Xml.attribute(n.b)).append("\"/></m:dPr>");
        for (Node c : n.args) wrap("e", c, b, depth);
        b.append("</m:d>");
        break;
      case "m":
        b.append("<m:m>");
        for (Node r : n.args) {
          b.append("<m:mr>");
          for (Node c : r.args) wrap("e", c, b, depth);
          b.append("</m:mr>");
        }
        b.append("</m:m>");
        break;
      case "eqArr":
        b.append("<m:eqArr>");
        for (Node r : n.args) wrap("e", r, b, depth);
        b.append("</m:eqArr>");
        break;
      case "acc":
        b.append("<m:acc>");
        val(b, "accPr", "chr", n.a);
        wrap("e", n.arg(0), b, depth);
        b.append("</m:acc>");
        break;
      case "bar":
        b.append("<m:bar>");
        val(b, "barPr", "pos", n.a);
        wrap("e", n.arg(0), b, depth);
        b.append("</m:bar>");
        break;
      case "func":
        b.append("<m:func>");
        wrap("fName", n.arg(1), b, depth);
        wrap("e", n.arg(0), b, depth);
        b.append("</m:func>");
        break;
      case "limLow": case "limUpp":
        b.append("<m:").append(n.kind).append('>');
        wrap("e", n.arg(0), b, depth);
        wrap("lim", n.arg(1), b, depth);
        b.append("</m:").append(n.kind).append('>');
        break;
      case "groupChr":
        b.append("<m:groupChr><m:groupChrPr><m:chr m:val=\"").append(Xml.attribute(n.a)).append("\"/><m:pos m:val=\"")
            .append(n.b).append("\"/></m:groupChrPr>");
        wrap("e", n.arg(0), b, depth);
        b.append("</m:groupChr>");
        break;
      case "box": {
        String tag = "borderBox".equals(n.a) || "phant".equals(n.a) ? n.a : "box";
        b.append("<m:").append(tag).append('>');
        wrap("e", n.arg(0), b, depth);
        b.append("</m:").append(tag).append('>');
        break;
      }
      default:
        for (Node c : n.args) omml(c, b, depth + 1);
    }
  }

  private static final String[][] TEX = {
    {"\u2211", "\\sum"}, {"\u220f", "\\prod"}, {"\u2210", "\\coprod"}, {"\u222b", "\\int"},
    {"\u222c", "\\iint"}, {"\u222d", "\\iiint"}, {"\u222e", "\\oint"}, {"\u22c3", "\\bigcup"},
    {"\u22c2", "\\bigcap"}, {"\u2026", "\\ldots"}, {"\u22ef", "\\cdots"}, {"\u2260", "\\neq"},
    {"\u2264", "\\leq"}, {"\u2265", "\\geq"}, {"\u21d2", "\\Rightarrow"}, {"\u27e8", "\\langle"},
    {"\u27e9", "\\rangle"}, {"\u2282", "\\subset"}, {"\u222a", "\\cup"}, {"\u2229", "\\cap"}
  };

  /** LaTeX math-mode source (without the surrounding {@code $} or {@code \[ \]}). */
  public static String latex(Node n) {
    var b = new StringBuilder();
    latex(n, b, 0);
    return b.toString().trim();
  }

  private static String texChar(char c) {
    String s = String.valueOf(c);
    for (String[] t : TEX) if (t[0].equals(s)) return t[1] + " ";
    for (String[] t : DrawingOps.symbols())
      if (t[1].equals(s) && !"\\sqrt".equals(t[0])) return ("\\degree".equals(t[0]) ? "^\\circ" : t[0]) + " ";
    for (String[] t : WORDS) if (t[1].equals(s)) return "\\" + t[0] + " ";
    switch (c) {
      case '{': return "\\{";
      case '}': return "\\}";
      case '#': case '$': case '%': case '&': case '_': return "\\" + c;
      case '\\': return "\\backslash ";
      case '~': return "\\sim ";
      case '^': return "\\hat{}";
      default: return s;
    }
  }

  private static void group(Node n, StringBuilder b, int depth) {
    b.append('{');
    latex(n, b, depth + 1);
    b.append('}');
  }

  private static String delimiter(String c, boolean open) {
    if (c.isEmpty()) return ".";
    switch (c) {
      case "{": return "\\{";
      case "}": return "\\}";
      case "\u27e8": return "\\langle ";
      case "\u27e9": return "\\rangle ";
      case "\u230a": return "\\lfloor ";
      case "\u230b": return "\\rfloor ";
      case "\u2308": return "\\lceil ";
      case "\u2309": return "\\rceil ";
      case "\u2016": return "\\| ";
      default: return c;
    }
  }

  private static void latex(Node n, StringBuilder b, int depth) {
    if (depth > MAX_DEPTH * 2 + 8) return;
    int d = depth + 1;
    switch (n.kind) {
      case "run":
        if ("1".equals(n.b) && FUNCS.contains(" " + n.a + " ")) b.append('\\').append(n.a).append(' ');
        else if ("1".equals(n.b) && simple(n.a) && Unicode.letter(n.a.charAt(0))) b.append("\\mathrm{").append(n.a).append('}');
        else for (int i = 0; i < n.a.length(); i++) b.append(texChar(n.a.charAt(i)));
        break;
      case "row": case "mr":
        for (Node c : n.args) latex(c, b, d);
        break;
      case "f":
        if ("lin".equals(n.a) || "skw".equals(n.a)) {
          group(n.arg(0), b, d);
          b.append('/');
          group(n.arg(1), b, d);
        } else {
          b.append("noBar".equals(n.a) ? "\\genfrac{}{}{0pt}{}" : "\\frac");
          group(n.arg(0), b, d);
          group(n.arg(1), b, d);
        }
        break;
      case "sSup": case "sSub": case "sSubSup":
        group(n.arg(0), b, d);
        if (!"sSup".equals(n.kind)) {
          b.append('_');
          group(n.arg(1), b, d);
        }
        if (!"sSub".equals(n.kind)) {
          b.append('^');
          group(n.arg("sSup".equals(n.kind) ? 1 : 2), b, d);
        }
        break;
      case "sPre":
        b.append("{}_");
        group(n.arg(1), b, d);
        b.append('^');
        group(n.arg(2), b, d);
        group(n.arg(0), b, d);
        break;
      case "rad":
        b.append("\\sqrt");
        if (!n.arg(1).empty()) {
          b.append('[');
          latex(n.arg(1), b, d);
          b.append(']');
        }
        group(n.arg(0), b, d);
        break;
      case "nary": {
        String op = texChar(n.a.isEmpty() ? '\u222b' : n.a.charAt(0)).trim();
        b.append(op.startsWith("\\") ? op : "\\mathop{" + op + "}");
        if ("undOvr".equals(n.b) && integral(n.a)) b.append("\\limits");
        if (!n.arg(1).empty()) {
          b.append('_');
          group(n.arg(1), b, d);
        }
        if (!n.arg(2).empty()) {
          b.append('^');
          group(n.arg(2), b, d);
        }
        b.append(' ');
        group(n.arg(0), b, d);
        break;
      }
      case "d":
        b.append("\\left").append(delimiter(n.a, true));
        for (int i = 0; i < n.args.size(); i++) {
          if (i > 0) b.append("|".equals(n.c) ? "\\middle| " : texChar(n.c.isEmpty() ? ' ' : n.c.charAt(0)));
          latex(n.args.get(i), b, d);
        }
        b.append("\\right").append(delimiter(n.b, false));
        break;
      case "m": case "eqArr":
        b.append("m".equals(n.kind) ? "\\begin{matrix}" : "\\begin{aligned}");
        for (int r = 0; r < n.args.size(); r++) {
          if (r > 0) b.append("\\\\");
          Node rowNode = n.args.get(r);
          if ("mr".equals(rowNode.kind))
            for (int c = 0; c < rowNode.args.size(); c++) {
              if (c > 0) b.append('&');
              latex(rowNode.args.get(c), b, d);
            }
          else latex(rowNode, b, d);
        }
        b.append("m".equals(n.kind) ? "\\end{matrix}" : "\\end{aligned}");
        break;
      case "acc": {
        String cmd;
        switch (n.a.isEmpty() ? '\u0302' : n.a.charAt(0)) {
          case '\u0303': cmd = "\\tilde"; break;
          case '\u0307': cmd = "\\dot"; break;
          case '\u0308': cmd = "\\ddot"; break;
          case '\u0304': case '\u0305': cmd = "\\bar"; break;
          case '\u20d7': cmd = "\\vec"; break;
          case '\u030c': cmd = "\\check"; break;
          case '\u0306': cmd = "\\breve"; break;
          default: cmd = "\\hat";
        }
        b.append(cmd);
        group(n.arg(0), b, d);
        break;
      }
      case "bar":
        b.append("top".equals(n.a) ? "\\overline" : "\\underline");
        group(n.arg(0), b, d);
        break;
      case "func":
        latex(n.arg(1), b, d);
        group(n.arg(0), b, d);
        break;
      case "limLow": case "limUpp": {
        Node base = n.arg(0);
        Node only = "row".equals(base.kind) && base.args.size() == 1 ? base.args.get(0) : base;
        if ("limLow".equals(n.kind) && "run".equals(only.kind) && FUNCS.contains(" " + only.a + " ")) {
          b.append('\\').append(only.a).append("_");
          group(n.arg(1), b, d);
        } else {
          b.append("limLow".equals(n.kind) ? "\\underset" : "\\overset");
          group(n.arg(1), b, d);
          group(base, b, d);
        }
        break;
      }
      case "groupChr":
        b.append("top".equals(n.b) ? "\\overbrace" : "\\underbrace");
        group(n.arg(0), b, d);
        break;
      case "box":
        b.append("borderBox".equals(n.a) ? "\\boxed" : "");
        group(n.arg(0), b, d);
        break;
      default:
        for (Node c : n.args) latex(c, b, d);
    }
  }

  // ============================================================================ layout

  /** A glyph run placed relative to its box origin (left edge, baseline). */
  public record Glyph(String text, float x, float y, float size, boolean italic, float width) {}

  /** A laid-out node: width, ascent and descent above/below the baseline, plus its primitives. */
  public static final class Box {
    public float width, ascent, descent;
    public final List<Glyph> glyphs = new ArrayList<>();
    /** Polylines as x,y pairs; the last element of each array is the stroke width. */
    public final List<float[]> lines = new ArrayList<>();

    void place(Box child, float dx, float dy) {
      for (Glyph g : child.glyphs) glyphs.add(new Glyph(g.text, g.x + dx, g.y + dy, g.size, g.italic, g.width));
      for (float[] l : child.lines) {
        float[] m = l.clone();
        for (int i = 0; i + 1 < m.length - 1; i += 2) {
          m[i] += dx;
          m[i + 1] += dy;
        }
        lines.add(m);
      }
    }

    void line(float stroke, float... pts) {
      float[] l = Arrays.copyOf(pts, pts.length + 1);
      l[pts.length] = stroke;
      lines.add(l);
    }
  }

  private static final String SPACED = "+-=<>\u00b1\u00d7\u00f7\u2264\u2265\u2260\u2248\u2192\u2190\u2208\u22c5\u2212\u21d2\u2261";

  /** Lays out {@code n} at font {@code size} (points) using {@code metrics} for every glyph. */
  public static Box layout(Node n, float size, LayoutEngine.Metrics metrics) {
    return layout(n, size, metrics, 0);
  }

  private static Box text(String t, float size, boolean italic, LayoutEngine.Metrics metrics, float pad) {
    var style = TextStyle.DEFAULT.with("size", String.valueOf(size));
    if (italic) style = style.with("italic", "true");
    float w = metrics.width(t, style);
    if (w < t.length() * size * .25f) w = t.length() * size * .55f;
    var box = new Box();
    box.glyphs.add(new Glyph(t, pad, 0, size, italic, w));
    box.width = w + pad * 2;
    box.ascent = size * .78f;
    box.descent = size * .24f;
    return box;
  }

  private static Box layout(Node n, float s, LayoutEngine.Metrics m, int depth) {
    if (depth > MAX_DEPTH * 2 + 8) return new Box();
    int d = depth + 1;
    float axis = s * .27f;
    switch (n.kind) {
      case "run": {
        boolean letters = false;
        for (int i = 0; i < n.a.length(); i++) letters |= Unicode.letter(n.a.charAt(i));
        boolean spaced = n.a.length() == 1 && SPACED.indexOf(n.a.charAt(0)) >= 0;
        return text(n.a, s, letters && !"1".equals(n.b), m, spaced ? s * .2f : 0);
      }
      case "f": {
        Box num = layout(n.arg(0), s * .9f, m, d), den = layout(n.arg(1), s * .9f, m, d);
        var box = new Box();
        if ("lin".equals(n.a) || "skw".equals(n.a)) {
          Box slash = text("/", s, false, m, 0);
          box.place(num, 0, 0);
          box.place(slash, num.width, 0);
          box.place(den, num.width + slash.width, 0);
          box.width = num.width + slash.width + den.width;
          box.ascent = Math.max(slash.ascent, Math.max(num.ascent, den.ascent));
          box.descent = Math.max(slash.descent, Math.max(num.descent, den.descent));
          return box;
        }
        float gap = s * .14f, w = Math.max(num.width, den.width) + s * .3f;
        float ny = -(axis + gap + num.descent), dy = -axis + gap + den.ascent;
        box.place(num, (w - num.width) / 2, ny);
        box.place(den, (w - den.width) / 2, dy);
        if (!"noBar".equals(n.a)) box.line(s * .055f, s * .06f, -axis, w - s * .06f, -axis);
        box.width = w;
        box.ascent = -ny + num.ascent;
        box.descent = dy + den.descent;
        return box;
      }
      case "sSup": case "sSub": case "sSubSup": case "sPre": {
        Box base = layout(n.arg(0), s, m, d);
        float ss = Math.max(s * .7f, 4);
        Box sub = "sSup".equals(n.kind) ? null : layout(n.arg(1), ss, m, d);
        Box sup = "sSub".equals(n.kind) ? null : layout(n.arg("sSup".equals(n.kind) ? 1 : 2), ss, m, d);
        float up = Math.max(s * .42f, base.ascent - (sup == null ? 0 : sup.ascent) * .5f);
        float down = Math.max(s * .22f, base.descent);
        float sw = Math.max(sub == null ? 0 : sub.width, sup == null ? 0 : sup.width);
        var box = new Box();
        boolean pre = "sPre".equals(n.kind);
        float bx = pre ? sw + s * .05f : 0, sx = pre ? 0 : base.width + s * .05f;
        box.place(base, bx, 0);
        if (sup != null) box.place(sup, sx, -up);
        if (sub != null) box.place(sub, sx, down);
        box.width = base.width + sw + s * .05f;
        box.ascent = Math.max(base.ascent, sup == null ? 0 : up + sup.ascent);
        box.descent = Math.max(base.descent, sub == null ? 0 : down + sub.descent);
        return box;
      }
      case "rad": {
        Box body = layout(n.arg(0), s, m, d);
        Box deg = n.arg(1).empty() ? null : layout(n.arg(1), s * .6f, m, d);
        float gap = s * .12f, top = body.ascent + gap, sign = s * .6f, stroke = s * .055f;
        float x0 = deg == null ? 0 : Math.max(0, deg.width - sign * .35f);
        var box = new Box();
        if (deg != null) box.place(deg, 0, -top * .45f);
        box.line(stroke, x0, -top * .35f, x0 + sign * .2f, -top * .45f, x0 + sign * .55f, body.descent,
            x0 + sign, -top, x0 + sign + body.width + s * .1f, -top);
        box.place(body, x0 + sign + s * .05f, 0);
        box.width = x0 + sign + body.width + s * .15f;
        box.ascent = Math.max(top + stroke * 2, deg == null ? 0 : top * .45f + deg.ascent);
        box.descent = body.descent + stroke;
        return box;
      }
      case "nary": {
        boolean integral = integral(n.a);
        float os = s * (integral ? 1.6f : 1.4f);
        Box op = text(n.a.isEmpty() ? "\u222b" : n.a, os, false, m, 0);
        float opy = os * .27f - axis;
        Box sub = n.arg(1).empty() ? null : layout(n.arg(1), s * .7f, m, d);
        Box sup = n.arg(2).empty() ? null : layout(n.arg(2), s * .7f, m, d);
        Box body = layout(n.arg(0), s, m, d);
        var box = new Box();
        float opTop = op.ascent - opy, opBottom = op.descent + opy, x;
        float asc = opTop, desc = opBottom;
        if ("subSup".equals(n.b)) {
          box.place(op, 0, opy);
          float sx = op.width * .8f;
          float sw = 0;
          if (sup != null) {
            box.place(sup, sx, -opTop + sup.ascent);
            sw = sup.width;
          }
          if (sub != null) {
            box.place(sub, sx, opBottom - sub.descent);
            sw = Math.max(sw, sub.width);
          }
          x = sx + sw + s * .1f;
        } else {
          float w = Math.max(op.width, Math.max(sub == null ? 0 : sub.width, sup == null ? 0 : sup.width));
          box.place(op, (w - op.width) / 2, opy);
          if (sup != null) {
            box.place(sup, (w - sup.width) / 2, -opTop - s * .08f - sup.descent);
            asc += s * .08f + sup.ascent + sup.descent;
          }
          if (sub != null) {
            box.place(sub, (w - sub.width) / 2, opBottom + s * .08f + sub.ascent);
            desc += s * .08f + sub.ascent + sub.descent;
          }
          x = w + s * .15f;
        }
        box.place(body, x, 0);
        box.width = x + body.width;
        box.ascent = Math.max(asc, body.ascent);
        box.descent = Math.max(desc, body.descent);
        return box;
      }
      case "d": {
        var inner = new Box();
        float x = 0;
        var parts = new ArrayList<Box>();
        for (Node c : n.args) parts.add(layout(c, s, m, d));
        float asc = s * .78f, desc = s * .24f;
        for (Box p : parts) {
          asc = Math.max(asc, p.ascent);
          desc = Math.max(desc, p.descent);
        }
        float ds = Math.max(s, (asc + desc) / 1.02f);
        float center = (desc - asc) / 2, dy = center + ds * .27f;
        var box = new Box();
        if (!n.a.isEmpty()) {
          Box open = text(n.a, ds, false, m, 0);
          box.place(open, x, dy);
          x += open.width;
        }
        for (int i = 0; i < parts.size(); i++) {
          if (i > 0 && !n.c.isEmpty()) {
            Box sep = text(n.c, ds, false, m, s * .05f);
            box.place(sep, x, dy);
            x += sep.width;
          }
          box.place(parts.get(i), x, 0);
          x += parts.get(i).width;
        }
        if (!n.b.isEmpty()) {
          Box close = text(n.b, ds, false, m, 0);
          box.place(close, x, dy);
          x += close.width;
        }
        box.width = x;
        box.ascent = Math.max(asc, ds * .78f - dy);
        box.descent = Math.max(desc, ds * .24f + dy);
        return box;
      }
      case "m": case "eqArr": {
        boolean matrix = "m".equals(n.kind);
        var cells = new ArrayList<List<Box>>();
        int cols = 0;
        for (Node r : n.args) {
          var rowBoxes = new ArrayList<Box>();
          if (matrix && "mr".equals(r.kind)) for (Node c : r.args) rowBoxes.add(layout(c, s, m, d));
          else rowBoxes.add(layout(r, s, m, d));
          cols = Math.max(cols, rowBoxes.size());
          cells.add(rowBoxes);
        }
        float[] cw = new float[cols];
        for (List<Box> r : cells) for (int c = 0; c < r.size(); c++) cw[c] = Math.max(cw[c], r.get(c).width);
        float colGap = s * .8f, rowGap = s * .3f, y = 0;
        var box = new Box();
        float total = 0;
        for (float w : cw) total += w;
        total += Math.max(0, cols - 1) * colGap;
        var baselines = new float[cells.size()];
        for (int r = 0; r < cells.size(); r++) {
          float a = s * .78f, de = s * .24f;
          for (Box b : cells.get(r)) {
            a = Math.max(a, b.ascent);
            de = Math.max(de, b.descent);
          }
          baselines[r] = y + a;
          y += a + de + rowGap;
        }
        float height = Math.max(0, y - rowGap), shift = -height / 2 - axis;
        for (int r = 0; r < cells.size(); r++) {
          float x = 0;
          for (int c = 0; c < cells.get(r).size(); c++) {
            Box b = cells.get(r).get(c);
            box.place(b, matrix ? x + (cw[c] - b.width) / 2 : x, baselines[r] + shift);
            x += cw[c] + colGap;
          }
        }
        box.width = total;
        box.ascent = -shift;
        box.descent = height + shift;
        return box;
      }
      case "acc": {
        Box body = layout(n.arg(0), s, m, d);
        var box = new Box();
        box.place(body, 0, 0);
        char c = n.a.isEmpty() ? '\u0302' : n.a.charAt(0);
        float top = body.ascent + s * .08f;
        if (c == '\u0304' || c == '\u0305' || c == '\u00af') box.line(s * .05f, 0, -top, body.width, -top);
        else {
          String mark = c == '\u0303' ? "~" : c == '\u0307' ? "\u02d9" : c == '\u0308' ? "\u00a8" : c == '\u20d7' ? "\u2192"
              : c == '\u030c' ? "\u02c7" : c == '\u0306' ? "\u02d8" : c < '\u0300' || c > '\u036f' ? String.valueOf(c) : "^";
          Box acc = text(mark, s * .8f, false, m, 0);
          box.place(acc, (body.width - acc.width) / 2, -top + s * .45f);
        }
        box.width = body.width;
        box.ascent = top + s * .3f;
        box.descent = body.descent;
        return box;
      }
      case "bar": {
        Box body = layout(n.arg(0), s, m, d);
        var box = new Box();
        box.place(body, 0, 0);
        boolean top = "top".equals(n.a);
        float y = top ? -(body.ascent + s * .08f) : body.descent + s * .08f;
        box.line(s * .05f, 0, y, body.width, y);
        box.width = body.width;
        box.ascent = top ? -y + s * .06f : body.ascent;
        box.descent = top ? body.descent : y + s * .06f;
        return box;
      }
      case "func": {
        Box name = layout(n.arg(1), s, m, d), arg = layout(n.arg(0), s, m, d);
        var box = new Box();
        box.place(name, 0, 0);
        box.place(arg, name.width + s * .17f, 0);
        box.width = name.width + s * .17f + arg.width;
        box.ascent = Math.max(name.ascent, arg.ascent);
        box.descent = Math.max(name.descent, arg.descent);
        return box;
      }
      case "limLow": case "limUpp": {
        Box base = layout(n.arg(0), s, m, d), lim = layout(n.arg(1), s * .7f, m, d);
        float w = Math.max(base.width, lim.width);
        var box = new Box();
        box.place(base, (w - base.width) / 2, 0);
        boolean low = "limLow".equals(n.kind);
        float y = low ? base.descent + s * .05f + lim.ascent : -(base.ascent + s * .05f + lim.descent);
        box.place(lim, (w - lim.width) / 2, y);
        box.width = w;
        box.ascent = low ? base.ascent : -y + lim.ascent;
        box.descent = low ? y + lim.descent : base.descent;
        return box;
      }
      case "groupChr": {
        Box body = layout(n.arg(0), s, m, d);
        var box = new Box();
        box.place(body, 0, 0);
        boolean top = "top".equals(n.b);
        float y = top ? -(body.ascent + s * .12f) : body.descent + s * .12f, t = top ? -s * .2f : s * .2f;
        float w = body.width, st = s * .05f;
        box.line(st, 0, y, w * .05f, y + t * .5f, w * .45f, y + t * .5f, w * .5f, y + t, w * .55f, y + t * .5f,
            w * .95f, y + t * .5f, w, y);
        box.width = w;
        box.ascent = top ? body.ascent + s * .35f : body.ascent;
        box.descent = top ? body.descent : body.descent + s * .35f;
        return box;
      }
      case "box": {
        Box body = layout(n.arg(0), s, m, d);
        if (!"borderBox".equals(n.a)) return body;
        var box = new Box();
        float p = s * .1f;
        box.place(body, p, 0);
        box.width = body.width + p * 2;
        box.ascent = body.ascent + p;
        box.descent = body.descent + p;
        box.line(s * .04f, 0, -box.ascent, box.width, -box.ascent, box.width, box.descent, 0, box.descent, 0, -box.ascent);
        return box;
      }
      default: {
        var box = new Box();
        box.ascent = s * .78f;
        box.descent = s * .24f;
        float x = 0;
        for (Node c : n.args) {
          Box b = layout(c, s, m, d);
          box.place(b, x, 0);
          x += b.width;
          box.ascent = Math.max(box.ascent, b.ascent);
          box.descent = Math.max(box.descent, b.descent);
        }
        box.width = x;
        return box;
      }
    }
  }

  // ============================================================================ drawing bridge

  /** The tree a drawing shows: its source OMML while unedited, else its linear text. */
  public static Node of(Drawing d) {
    String xml = d.get("omml", "");
    if (!xml.isEmpty() && key(d).equals(d.get("ommlKey", ""))) {
      try {
        return fromOmml(Xml.parse(xml));
      } catch (java.io.IOException | RuntimeException broken) {
        // fall back to the linear text below
      }
    }
    return fromLinear(d.get("text", ""));
  }

  /** What the stored OMML must still match for it to be written back verbatim. */
  static String key(Drawing d) {
    return d.get("text", "") + "\u0001" + d.get("number", "") + "\u0001" + d.flag("normalText");
  }

  /** Height in points a drawing needs to show {@code n} at {@code size}. */
  static float height(Node n, float size) {
    Box b = layout(n, size, new PdfMetrics());
    return Math.max(30, (float) Math.ceil(b.ascent + b.descent + size * .6f));
  }

  // ============================================================================ inline equations

  /**
   * Field instruction prefix of an inline equation ({@code m:oMath} inside a paragraph). The field
   * is one object character in the text, so the caret steps over it as one unit and selection,
   * copy, paste and undo treat it like any other field; display equations ({@code m:oMathPara})
   * stay block drawings.
   */
  public static final String INLINE = "OMATH ";

  /** An inline equation field holding {@code linear}. */
  public static InlineField inline(String linear) {
    String text = linear == null || linear.trim().isEmpty() ? "\u2b1a" : linear.trim();
    return new InlineField(INLINE + text, text, false, false);
  }

  /** True for an inline equation field. */
  public static boolean inline(InlineField f) {
    return f != null && f.instruction.startsWith(INLINE);
  }

  /** The linear text an inline equation field holds. */
  public static String inlineText(InlineField f) {
    return f.instruction.substring(INLINE.length());
  }

  /** The drawing an inline equation paints with, sized to {@code style}. */
  public static Drawing inlineDrawing(InlineField f, TextStyle style) {
    return Drawing.of("equation").with("text", inlineText(f)).with("justify", "left")
        .with("fontSize", String.valueOf(style == null ? 12 : style.size)).with("display", "false").with("inline", "true");
  }

  /** The inline equation at {@code at} placed with its origin on the baseline at (x, baseline). */
  static LayoutEngine.PlacedPicture placed(InlineField f, TextStyle style, int block, int page,
      float x, float baseline, float width) {
    return new LayoutEngine.PlacedPicture(Picture.drawing("math", inlineDrawing(f, style), Math.max(1, width), 1, "", "left"),
        block, page, x, baseline, Math.max(1, width), 1);
  }

  /** Insert &gt; Equation's ready-made equations, as in Wordcraft: id, description, linear text. */
  public static final String[][] BUILT_INS = {
    {"pythagoras", "Sides of a right triangle", "a^2+b^2=c^2"},
    {"area", "Circle area from its radius", "A=\u03c0r^2"},
    {"quadratic", "Roots of ax\u00b2+bx+c", "x=(\u2212b\u00b1\u221a(b^2\u22124ac))/2a"},
    {"binomial", "Binomial expansion of (x+a)\u207f", "(x+a)^n=\u2211_(k=0)^n\u2592\u3016(n\u00a6k) x^k a^(n\u2212k)\u3017"},
    {"expansion", "Power series of (1+x)\u207f", "(1+x)^n=1+nx/1!+(n(n\u22121)x^2)/2!+\u22ef"},
    {"euler", "Euler's identity", "e^(i\u03c0)+1=0"},
    {"derivative", "Derivative as a limit", "f'(x)=lim\u252c(h\u21920)\u2061\u3016(f(x+h)\u2212f(x))/h\u3017"},
    {"taylor", "Exponential series", "e^x=1+x/1!+x^2/2!+x^3/3!+\u22ef,  \u2212\u221e<x<\u221e"},
    {"gaussian", "Integral of e^(\u2212x\u00b2)", "\u222b_(\u2212\u221e)^\u221e\u2592e^(\u2212x^2) dx=\u221a\u03c0"},
    {"fourier", "Sine and cosine series of f(x)",
      "f(x)=a_0+\u2211_(n=1)^\u221e\u2592\u3016(a_n cos\u2061\u3016n\u03c0x/L\u3017+b_n sin\u2061\u3016n\u03c0x/L\u3017)\u3017"},
    {"trig1", "Sum or difference of sines",
      "sin\u2061\u03b1\u00b1sin\u2061\u03b2=2 sin\u2061\u30161/2(\u03b1\u00b1\u03b2)\u3017 cos\u2061\u30161/2(\u03b1\u2213\u03b2)\u3017"},
    {"trig2", "Sum of cosines", "cos\u2061\u03b1+cos\u2061\u03b2=2 cos\u2061\u30161/2(\u03b1+\u03b2)\u3017 cos\u2061\u30161/2(\u03b1\u2212\u03b2)\u3017"},
    {"normal", "Normal probability density", "f(x)=1/(\u03c3\u221a(2\u03c0)) e^(\u2212(x\u2212\u03bc)^2/(2\u03c3^2))"},
    {"bayes", "Bayes' rule", "P(A|B)=(P(B|A)P(A))/P(B)"},
  };

  /** The linear text of built-in equation {@code id}, or null. */
  public static String builtIn(String id) {
    for (String[] b : BUILT_INS) if (b[0].equals(id)) return b[2];
    return null;
  }
}
