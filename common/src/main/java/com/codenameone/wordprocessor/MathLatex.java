package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;

/**
 * LaTeX math to an equation tree, a port of Wordcraft's {@code math_latex::parse_latex} (Word's
 * LaTeX equation input): {@code \frac}, {@code \sqrt[n]{}}, scripts, big operators with limits
 * (taking the following term as their operand), {@code \left...\middle...\right}, matrix,
 * {@code cases} and {@code aligned} environments, accents, {@code \overbrace}/{@code underbrace},
 * {@code \overset}/{@code underset}, {@code \text}/{@code \mathrm}/{@code \mathbb}/
 * {@code \mathcal}/{@code \mathfrak}, function names, spacing and the named symbols. Unknown
 * commands stay as text. Never throws; nesting is bounded by {@link Equation#MAX_DEPTH}.
 *
 * <p>Differences from Wordcraft, kept deliberately: the tree has no run styles, so
 * {@code \mathbf}/{@code \mathsf}/{@code \mathtt} keep their content plain and {@code \mathbb}
 * etc. map letters to the Unicode math alphanumerics; top-level {@code \\} rows (an {@code align}
 * body) become an equation array; {@code \label}, {@code \nonumber} and {@code \notag} are dropped.
 */
final class MathLatex {
  private static final int CMD = 0, OPEN = 1, CLOSE = 2, SUP = 3, SUB = 4, AMP = 5, ROW = 6, CH = 7;
  private static final String[] TEXT_COMMANDS = {"text", "textrm", "mbox", "textnormal", "textbf", "textit"};
  /** Function names beyond {@link Equation#FUNCS} that LaTeX writes upright. */
  private static final String MORE_FUNCS = " liminf limsup sup inf Pr arg deg dim hom ker ";
  /** Characters that end a big operator's operand (relations, binary operators, punctuation). */
  private static final String STOPS = "=<>+-−±∓×÷·∘∗,;:&≠≤≥"
      + "≈≡≃≅∝≪≫∈∉⊂⊃⊆⊇→←↔"
      + "⇒⇐⇔↦∼∪∩∧∨⊕⊗";

  private final int[] types;
  private final String[] vals;
  private final int count;
  private int i, depth;

  private MathLatex(int[] types, String[] vals, int count, int depth) {
    this.types = types;
    this.vals = vals;
    this.count = count;
    this.depth = depth;
  }

  private static boolean ascii(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
  }

  private static boolean textCommand(String name) {
    for (String t : TEXT_COMMANDS) if (t.equals(name)) return true;
    return false;
  }

  /** LaTeX math as an equation tree (a row). */
  static Equation.Node parse(String tex) {
    String s = tex == null ? "" : tex;
    int len = Math.min(s.length(), 20000);
    int[] types = new int[len + 1];
    String[] vals = new String[len + 1];
    int n = 0, k = 0;
    boolean textNext = false;
    int textDepth = -1;
    while (k < len) {
      char c = s.charAt(k++);
      boolean ws = c == ' ' || c == '\t' || c == '\n' || c == '\r';
      if (textDepth >= 0 && ws) {
        if (!(n > 0 && types[n - 1] == CH && " ".equals(vals[n - 1]))) {
          types[n] = CH;
          vals[n++] = " ";
        }
        continue;
      }
      boolean wasTextNext = textNext;
      textNext = false;
      if (c == '\\') {
        int start = k;
        while (k < len && ascii(s.charAt(k))) k++;
        if (k == start) {
          if (k < len) {
            char x = s.charAt(k++);
            types[n] = x == '\\' ? ROW : CMD;
            vals[n++] = String.valueOf(x);
          }
        } else {
          String name = s.substring(start, k);
          textNext = textDepth < 0 && textCommand(name);
          types[n] = CMD;
          vals[n++] = name;
        }
      } else if (c == '{') {
        textDepth = textDepth >= 0 ? textDepth + 1 : wasTextNext ? 0 : -1;
        types[n++] = OPEN;
      } else if (c == '}') {
        textDepth = textDepth > 0 ? textDepth - 1 : -1;
        types[n++] = CLOSE;
      } else if (c == '^') types[n++] = SUP;
      else if (c == '_') types[n++] = SUB;
      else if (c == '&') types[n++] = AMP;
      else if (ws) textNext = wasTextNext;
      else if (c == '%') {
        while (k < len && s.charAt(k) != '\n') k++;
      } else {
        types[n] = CH;
        // A surrogate pair stays one character.
        if (c >= '\ud800' && c <= '\udbff' && k < len) vals[n++] = s.substring(k - 1, ++k);
        else vals[n++] = c == '~' ? " " : String.valueOf(c);
      }
    }
    var p = new MathLatex(types, vals, n, 0);
    List<Equation.Node> rows = new ArrayList<>();
    List<Equation.Node> out = new ArrayList<>();
    while (p.i < p.count) {
      out.addAll(p.seq(false));
      if (p.i >= p.count) break;
      int t = p.types[p.i];
      if (t == AMP) out.add(Equation.run("&", false));
      else if (t == ROW) {
        rows.add(Equation.rowOf(merge(out)));
        out = new ArrayList<>();
      }
      p.i++;
    }
    if (rows.isEmpty()) return Equation.rowOf(merge(out));
    if (!out.isEmpty()) rows.add(Equation.rowOf(merge(out)));
    return rows.size() == 1 ? rows.get(0) : new Equation.Node("eqArr", null, null, null, rows);
  }

  private boolean at(int type) {
    return i < count && types[i] == type;
  }

  private boolean atCmd(String name) {
    return at(CMD) && name.equals(vals[i]);
  }

  /** A sequence up to a closing brace, {@code &}, {@code \\}, {@code \right}, {@code \middle} or {@code \end}. */
  private List<Equation.Node> seq(boolean inGroup) {
    List<Equation.Node> out = new ArrayList<>();
    if (++depth > Equation.MAX_DEPTH) {
      i = count;
      depth--;
      return out;
    }
    while (i < count) {
      int t = types[i];
      if (t == CLOSE || t == AMP || t == ROW) break;
      if (t == CMD && ("right".equals(vals[i]) || "middle".equals(vals[i]) || "end".equals(vals[i]))) break;
      if (t == SUP || t == SUB) {
        i++;
        Equation.Node s = Equation.rowOf(arg());
        Equation.Node base = out.isEmpty() ? null : out.remove(out.size() - 1);
        out.add(script(base, t == SUP, s));
      } else if (t == CMD && ("limits".equals(vals[i]) || "nolimits".equals(vals[i])
          || "displaystyle".equals(vals[i]) || "textstyle".equals(vals[i]))) {
        String c = vals[i++];
        Equation.Node last = out.isEmpty() ? null : out.get(out.size() - 1);
        if (last != null && "nary".equals(last.kind) && !"displaystyle".equals(c) && !"textstyle".equals(c))
          out.set(out.size() - 1, new Equation.Node("nary", last.a, "limits".equals(c) ? "undOvr" : "subSup", null, last.args));
      } else out.addAll(atom());
    }
    depth--;
    return naryOperands(out, 0);
  }

  /** A command argument: a braced group or one token. */
  private List<Equation.Node> arg() {
    if (at(OPEN)) {
      i++;
      List<Equation.Node> g = seq(true);
      if (at(CLOSE)) i++;
      return g;
    }
    return i < count ? atom() : new ArrayList<>();
  }

  /** An optional {@code [argument]}. */
  private List<Equation.Node> opt() {
    if (!(at(CH) && "[".equals(vals[i]))) return null;
    i++;
    int start = i, level = 0;
    while (i < count) {
      int t = types[i++];
      if (t == CH && "]".equals(vals[i - 1]) && level == 0) break;
      if (t == OPEN) level++;
      else if (t == CLOSE) level--;
    }
    int end = i > start && types[i - 1] == CH && "]".equals(vals[i - 1]) ? i - 1 : i;
    int[] t2 = new int[end - start + 1];
    String[] v2 = new String[end - start + 1];
    System.arraycopy(types, start, t2, 0, end - start);
    System.arraycopy(vals, start, v2, 0, end - start);
    return new MathLatex(t2, v2, end - start, depth).seq(false);
  }

  /** The text of a braced argument, verbatim (named symbols mapped). */
  private String rawArg() {
    var s = new StringBuilder();
    if (!at(OPEN)) {
      if (at(CH)) s.append(vals[i++]);
      return s.toString();
    }
    i++;
    int level = 0;
    while (i < count) {
      int t = types[i];
      String v = vals[i++];
      if (t == CLOSE && level == 0) break;
      switch (t) {
        case OPEN: level++; s.append('{'); break;
        case CLOSE: level--; s.append('}'); break;
        case CH: s.append(v); break;
        case CMD: {
          String sym = ascii(v.charAt(0)) ? Equation.symbol(v) : null;
          s.append(sym != null ? sym : v);
          break;
        }
        case SUP: s.append('^'); break;
        case SUB: s.append('_'); break;
        case AMP: s.append('&'); break;
        default: s.append('\n');
      }
    }
    return s.toString();
  }

  /** A delimiter after {@code \left}, {@code \right}, {@code \middle} or {@code \big}; "" for {@code .}. */
  private String delim() {
    if (i >= count) return "";
    int t = types[i];
    String v = vals[i++];
    if (t == CH) return ".".equals(v) ? "" : v;
    if (t != CMD) return "";
    switch (v) {
      case "{": case "lbrace": return "{";
      case "}": case "rbrace": return "}";
      case "|": case "Vert": return "‖";
      case "vert": return "|";
      case "langle": return "⟨";
      case "rangle": return "⟩";
      case "lfloor": return "⌊";
      case "rfloor": return "⌋";
      case "lceil": return "⌈";
      case "rceil": return "⌉";
      default: {
        String sym = Equation.symbol(v);
        return sym == null ? "" : sym;
      }
    }
  }

  private List<Equation.Node> atom() {
    List<Equation.Node> out = new ArrayList<>();
    if (depth > Equation.MAX_DEPTH) {
      i = count;
      return out;
    }
    if (i >= count) return out;
    depth++;
    int t = types[i];
    String v = vals[i++];
    if (t == OPEN) {
      out = seq(true);
      if (at(CLOSE)) i++;
    } else if (t == CH) {
      if (Equation.NARY.indexOf(v.charAt(0)) >= 0) out.add(nary(v));
      else out.add(Equation.run(v, false));
    } else if (t == CMD) out = command(v);
    depth--;
    return out;
  }

  private static Equation.Node nary(String chr) {
    return Equation.node("nary", chr, Equation.integral(chr) ? "subSup" : "undOvr", null,
        Equation.row(), Equation.row(), Equation.row());
  }

  private static List<Equation.Node> one(Equation.Node n) {
    List<Equation.Node> out = new ArrayList<>();
    out.add(n);
    return out;
  }

  private static List<Equation.Node> runOf(String text, boolean normal) {
    return one(Equation.run(text, normal));
  }

  private List<Equation.Node> command(String c) {
    switch (c) {
      case "frac": case "dfrac": case "tfrac": case "cfrac": {
        Equation.Node num = Equation.rowOf(arg());
        return one(Equation.node("f", "bar", null, null, num, Equation.rowOf(arg())));
      }
      case "binom": case "dbinom": case "tbinom": {
        Equation.Node n = Equation.rowOf(arg());
        Equation.Node f = Equation.node("f", "noBar", null, null, n, Equation.rowOf(arg()));
        return one(new Equation.Node("d", "(", ")", "|", one(Equation.row(f))));
      }
      case "sqrt": {
        List<Equation.Node> deg = opt();
        Equation.Node e = Equation.rowOf(arg());
        boolean hide = deg == null || deg.isEmpty();
        return one(Equation.node("rad", hide ? "1" : "", null, null, e, hide ? Equation.row() : Equation.rowOf(deg)));
      }
      case "left": {
        String beg = delim(), sep = "", end = "";
        List<Equation.Node> elems = new ArrayList<>();
        elems.add(Equation.rowOf(merge(seq(false))));
        while (at(CMD)) {
          String k = vals[i++];
          if ("middle".equals(k)) {
            String d = delim();
            sep = d.isEmpty() ? "|" : d;
            elems.add(Equation.rowOf(merge(seq(false))));
          } else {
            if ("right".equals(k)) end = delim();
            break;
          }
        }
        return one(new Equation.Node("d", beg, end, sep.isEmpty() ? "|" : sep, elems));
      }
      case "bigl": case "Bigl": case "biggl": case "Biggl": case "bigr": case "Bigr": case "biggr":
      case "Biggr": case "big": case "Big": case "bigg": case "Bigg": case "bigm": case "Bigm": {
        String d = delim();
        return d.isEmpty() ? new ArrayList<>() : runOf(d, false);
      }
      case "begin":
        return environment(rawArg().trim());
      case "text": case "textrm": case "mbox": case "textnormal": case "textbf": case "textit": {
        // Normal (non-math) text, as Wordcraft: m:nor with \textbf/\textit bold/italic.
        String t = rawArg();
        String sty = "textbf".equals(c) ? "b" : "textit".equals(c) ? "i" : "p";
        return t.isEmpty() ? new ArrayList<>() : one(Equation.run(t, true, "sty=" + sty + ";nor=1;"));
      }
      case "operatorname": case "operatorname*":
        return apply(one(Equation.run(rawArg(), true)));
      case "mathrm": case "rm": case "mathup":
        return restyle(arg(), null, 0);
      case "mathbf": case "bf":
        return style(arg(), "sty", "b", 0);
      case "mathit":
        return style(arg(), "sty", "i", 0);
      case "boldsymbol": case "bm":
        return style(arg(), "sty", "bi", 0);
      case "mathsf":
        return style(arg(), "scr", "sans-serif", 0);
      case "mathtt":
        return style(arg(), "scr", "monospace", 0);
      case "mathbb":
        return restyle(arg(), "double", 0);
      case "mathcal": case "mathscr":
        return restyle(arg(), "script", 0);
      case "mathfrak":
        return restyle(arg(), "fraktur", 0);
      case "overline":
        return one(Equation.node("bar", "top", null, null, Equation.rowOf(arg())));
      case "underline":
        return one(Equation.node("bar", "bot", null, null, Equation.rowOf(arg())));
      case "boxed": case "fbox":
        return one(Equation.node("box", "borderBox", null, null, Equation.rowOf(arg())));
      case "phantom":
        return one(Equation.node("box", "phant", "0000", null, Equation.rowOf(arg())));
      case "hphantom":
        return one(Equation.node("box", "phant", "0011", null, Equation.rowOf(arg())));
      case "vphantom":
        return one(Equation.node("box", "phant", "0100", null, Equation.rowOf(arg())));
      case "overbrace": case "underbrace": case "overparen": case "underparen": {
        boolean top = c.startsWith("over");
        String chr = "overbrace".equals(c) ? "⏞" : "underbrace".equals(c) ? "⏟"
            : "overparen".equals(c) ? "⏜" : "⏝";
        Equation.Node g = Equation.node("groupChr", chr, top ? "top" : "bot", null, Equation.rowOf(arg()));
        if (at(top ? SUP : SUB)) {
          i++;
          return one(Equation.node(top ? "limUpp" : "limLow", null, null, null, Equation.row(g), Equation.rowOf(arg())));
        }
        return one(g);
      }
      case "stackrel": case "overset": {
        Equation.Node over = Equation.rowOf(arg());
        return one(Equation.node("limUpp", null, null, null, Equation.rowOf(arg()), over));
      }
      case "underset": {
        Equation.Node under = Equation.rowOf(arg());
        return one(Equation.node("limLow", null, null, null, Equation.rowOf(arg()), under));
      }
      case "hat": case "widehat": case "tilde": case "widetilde": case "dot": case "ddot": case "dddot":
      case "check": case "breve": case "acute": case "grave": case "vec": case "bar": case "overrightarrow":
      case "overleftarrow": case "overleftrightarrow": case "mathring":
        return one(Equation.node("acc", accent(c), null, null, Equation.rowOf(arg())));
      case ",": case "thinspace": return runOf(" ", false);
      case ":": case ">": case "medspace": return runOf(" ", false);
      case ";": case "thickspace": return runOf(" ", false);
      case "!": case "negthinspace": case "nonumber": case "notag": return new ArrayList<>();
      case "label": case "tag":
        rawArg();
        return new ArrayList<>();
      case " ": return runOf(" ", false);
      case "quad": return runOf(" ", false);
      case "qquad": return runOf("  ", false);
      case "{": case "}": case "%": case "$": case "#": case "&": case "_":
        return runOf(c, false);
      case "|": return runOf("‖", false);
      default:
        break;
    }
    if (Equation.FUNCS.contains(" " + c + " ") || MORE_FUNCS.contains(" " + c + " ")) {
      Equation.Node plain = Equation.run(c, true);
      boolean limits = " lim liminf limsup max min sup inf det gcd Pr ".contains(" " + c + " ");
      List<Equation.Node> named;
      if (limits && at(SUB)) {
        i++;
        named = one(Equation.node("limLow", null, null, null, Equation.row(plain), Equation.rowOf(arg())));
      } else if (at(SUP) || at(SUB)) {
        boolean sup = at(SUP);
        i++;
        named = one(script(plain, sup, Equation.rowOf(arg())));
      } else named = one(plain);
      return apply(named);
    }
    String sym = Equation.symbol(c);
    if (sym == null) sym = extraSymbol(c);
    if (sym != null) return one(Equation.NARY.indexOf(sym.charAt(0)) >= 0 && sym.length() == 1 ? nary(sym) : Equation.run(sym, false));
    return runOf("\\" + c, false);
  }

  /** Symbols Wordcraft names that the shared table may not carry. */
  private static String extraSymbol(String c) {
    String[] extra = {"iint", "∬", "iiint", "∭", "oint", "∮", "bigcup", "⋃", "bigcap", "⋂",
      "coprod", "∐", "bigoplus", "⨁", "bigotimes", "⨂", "langle", "⟨", "rangle", "⟩",
      "ldots", "…", "cdots", "⋯", "vdots", "⋮", "ddots", "⋱", "mid", "∣", "setminus", "∖",
      "le", "≤", "ge", "≥", "ne", "≠", "to", "→", "gets", "←", "lbrace", "{", "rbrace", "}",
      "backslash", "\\", "lvert", "|", "rvert", "|", "vert", "|", "Vert", "‖", "colon", ":", "dots", "…"};
    for (int k = 0; k < extra.length; k += 2) if (extra[k].equals(c)) return extra[k + 1];
    return null;
  }

  private static String accent(String c) {
    switch (c) {
      case "hat": case "widehat": return "̂";
      case "tilde": case "widetilde": return "̃";
      case "dot": return "̇";
      case "ddot": return "̈";
      case "dddot": return "⃛";
      case "check": return "̌";
      case "breve": return "̆";
      case "acute": return "́";
      case "grave": return "̀";
      case "bar": return "̅";
      case "overleftarrow": return "⃖";
      case "overleftrightarrow": return "⃡";
      case "mathring": return "̊";
      default: return "⃗";
    }
  }

  /** A function name applied to the following term (with its scripts). */
  private List<Equation.Node> apply(List<Equation.Node> name) {
    List<Equation.Node> e = atom();
    while (at(SUP) || at(SUB)) {
      boolean sup = at(SUP);
      i++;
      Equation.Node s = Equation.rowOf(arg());
      Equation.Node base = e.size() == 1 ? e.remove(0) : Equation.rowOf(new ArrayList<>(e));
      e = new ArrayList<>();
      e.add(script(base, sup, s));
    }
    if (e.isEmpty()) return name;
    return one(Equation.node("func", null, null, null, Equation.rowOf(e), Equation.rowOf(name)));
  }

  private List<Equation.Node> environment(String env) {
    List<List<Equation.Node>> rows = new ArrayList<>();
    rows.add(new ArrayList<>());
    while (true) {
      List<Equation.Node> cell = merge(seq(false));
      rows.get(rows.size() - 1).add(Equation.rowOf(cell));
      if (i >= count) break;
      int t = types[i];
      if (t == ROW) {
        i++;
        rows.add(new ArrayList<>());
      } else if (t == CMD && "end".equals(vals[i])) {
        i++;
        rawArg();
        break;
      } else i++;
    }
    if (rows.size() > 1) {
      boolean blank = true;
      for (Equation.Node c : rows.get(rows.size() - 1)) blank &= c.empty();
      if (blank) rows.remove(rows.size() - 1);
    }
    String base = env.endsWith("*") ? env.substring(0, env.length() - 1) : env;
    String beg = null, end = null;
    switch (base) {
      case "pmatrix": beg = "("; end = ")"; break;
      case "bmatrix": beg = "["; end = "]"; break;
      case "Bmatrix": beg = "{"; end = "}"; break;
      case "vmatrix": beg = "|"; end = "|"; break;
      case "Vmatrix": beg = "‖"; end = "‖"; break;
      default: break;
    }
    Equation.Node inner;
    if (beg != null || "matrix".equals(base) || "smallmatrix".equals(base) || "array".equals(base)) {
      int cols = 1;
      for (List<Equation.Node> r : rows) cols = Math.max(cols, r.size());
      List<Equation.Node> mrows = new ArrayList<>();
      for (List<Equation.Node> r : rows) {
        List<Equation.Node> cells = new ArrayList<>(r);
        while (cells.size() < cols) cells.add(Equation.row());
        mrows.add(new Equation.Node("mr", null, null, null, cells));
      }
      inner = new Equation.Node("m", null, null, null, mrows);
    } else {
      List<Equation.Node> eq = new ArrayList<>();
      for (List<Equation.Node> r : rows) eq.add(joinCells(r));
      inner = eq.size() == 1 && !"cases".equals(base) && !"dcases".equals(base) && !"rcases".equals(base)
          ? eq.get(0) : new Equation.Node("eqArr", null, null, null, eq);
      if ("cases".equals(base) || "dcases".equals(base)) {
        beg = "{";
        end = "";
      } else if ("rcases".equals(base)) {
        beg = "";
        end = "}";
      }
    }
    if (beg == null) return one(inner);
    return one(new Equation.Node("d", beg, end, "|", one(Equation.row(inner))));
  }

  private static Equation.Node joinCells(List<Equation.Node> cells) {
    List<Equation.Node> out = new ArrayList<>();
    for (int k = 0; k < cells.size(); k++) {
      if (k > 0) out.add(Equation.run("&", false));
      Equation.Node c = cells.get(k);
      if ("row".equals(c.kind)) out.addAll(c.args);
      else out.add(c);
    }
    return Equation.rowOf(merge(out));
  }

  private static Equation.Node script(Equation.Node base, boolean sup, Equation.Node s) {
    if (base != null && "nary".equals(base.kind) && base.arg(0).empty())
      return sup ? Equation.node("nary", base.a, base.b, null, base.arg(0), base.arg(1), s)
          : Equation.node("nary", base.a, base.b, null, base.arg(0), s, base.arg(2));
    if (base != null && "sSub".equals(base.kind) && sup)
      return Equation.node("sSubSup", null, null, null, base.arg(0), base.arg(1), s);
    if (base != null && "sSup".equals(base.kind) && !sup)
      return Equation.node("sSubSup", null, null, null, base.arg(0), s, base.arg(1));
    Equation.Node b = base == null ? Equation.row() : "row".equals(base.kind) ? base : Equation.row(base);
    return Equation.node(sup ? "sSup" : "sSub", null, null, null, b, s);
  }

  /** Big operators take the following term as their operand. */
  private static List<Equation.Node> naryOperands(List<Equation.Node> a, int depth) {
    List<Equation.Node> out = new ArrayList<>();
    for (int k = 0; k < a.size(); k++) {
      Equation.Node n = a.get(k);
      if (!"nary".equals(n.kind) || !n.arg(0).empty() || depth > Equation.MAX_DEPTH) {
        out.add(n);
        continue;
      }
      List<Equation.Node> body = new ArrayList<>();
      while (k + 1 < a.size()) {
        Equation.Node next = a.get(k + 1);
        if ("run".equals(next.kind) && !next.a.isEmpty() && STOPS.indexOf(next.a.charAt(0)) >= 0) break;
        body.add(next);
        k++;
      }
      body = merge(naryOperands(body, depth + 1));
      out.add(Equation.node("nary", n.a, n.b, null, Equation.rowOf(body), n.arg(1), n.arg(2)));
    }
    return out;
  }

  /** Upright ({@code script == null}) or Unicode math alphanumeric runs. */
  private static List<Equation.Node> restyle(List<Equation.Node> a, String script, int depth) {
    List<Equation.Node> out = new ArrayList<>();
    for (Equation.Node n : a) {
      if ("run".equals(n.kind)) {
        if (script == null) {
          out.add(Equation.run(n.a, true));
          continue;
        }
        var b = new StringBuilder();
        for (int k = 0; k < n.a.length(); k++) {
          char ch = n.a.charAt(k);
          String m = EquationEditor.mathAlnum(script + ch);
          if (m != null) b.append(m);
          else b.append(ch);
        }
        out.add(Equation.run(b.toString(), "1".equals(n.b)));
      } else if (depth < Equation.MAX_DEPTH) {
        List<Equation.Node> args = new ArrayList<>();
        for (Equation.Node x : n.args)
          args.add("row".equals(x.kind) || "mr".equals(x.kind)
              ? new Equation.Node(x.kind, x.a, x.b, x.c, restyle(x.args, script, depth + 1), x.x)
              : Equation.rowOf(restyle(one(x), script, depth + 1)));
        out.add(new Equation.Node(n.kind, n.a, n.b, n.c, args, n.x));
      } else out.add(n);
    }
    return out;
  }

  /**
   * Every run inside {@code a} with run property {@code key} set to {@code value} (OMML m:sty or
   * m:scr), as Wordcraft's restyle.
   */
  private static List<Equation.Node> style(List<Equation.Node> a, String key, String value, int depth) {
    List<Equation.Node> out = new ArrayList<>();
    for (Equation.Node n : a) {
      if ("run".equals(n.kind)) {
        String sty = "sty".equals(key) ? value : Equation.runProp(n.x, "sty");
        String scr = "scr".equals(key) ? value : Equation.runProp(n.x, "scr");
        boolean nor = "1".equals(Equation.runProp(n.x, "nor"));
        String props = Equation.runProps(sty, scr, nor, "1".equals(Equation.runProp(n.x, "lit")), Equation.runProp(n.x, "w"));
        if ("p".equals(sty)) props = "sty=p;" + props;
        out.add(new Equation.Node("run", n.a, n.b, n.c, null, props));
      } else if (depth < Equation.MAX_DEPTH) {
        List<Equation.Node> args = new ArrayList<>();
        for (Equation.Node x : n.args)
          args.add("row".equals(x.kind) || "mr".equals(x.kind)
              ? new Equation.Node(x.kind, x.a, x.b, x.c, style(x.args, key, value, depth + 1), x.x)
              : Equation.rowOf(style(one(x), key, value, depth + 1)));
        out.add(new Equation.Node(n.kind, n.a, n.b, n.c, args, n.x));
      } else out.add(n);
    }
    return out;
  }

  /** Adjacent runs of the same kind merged. */
  private static List<Equation.Node> merge(List<Equation.Node> a) {
    List<Equation.Node> out = new ArrayList<>();
    for (Equation.Node n : a) {
      Equation.Node last = out.isEmpty() ? null : out.get(out.size() - 1);
      if (last != null && "run".equals(n.kind) && "run".equals(last.kind) && last.b.equals(n.b) && last.c.isEmpty() && n.c.isEmpty() && last.x.equals(n.x))
        out.set(out.size() - 1, Equation.run(last.a + n.a, "1".equals(n.b), n.x));
      else out.add(n);
    }
    return out;
  }
}
