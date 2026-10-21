package com.codenameone.wordprocessor;

/**
 * One in-place equation edit against a {@link Session}: the {@link EquationEditor} caret model
 * plus where the equation lives (a display equation block, or an inline equation field at an
 * offset in a paragraph). Every change is written back as the equation's linear text in one
 * session transaction, so each edit is one undo step and DOCX/PDF export see an ordinary equation.
 */
final class EquationEditing {
  final Session session;
  final int block;
  /** Offset of the inline equation field in its paragraph, or -1 for a display equation. */
  final int field;
  final EquationEditor editor;
  /** Normal (upright, unconverted) text mode, as Word's Normal Text button. */
  boolean normalText;

  private EquationEditing(Session session, int block, int field, String linear) {
    this.session = session;
    this.block = block;
    this.field = field;
    editor = new EquationEditor(linear);
    editor.homeEnd(true);
  }

  /** The equation at the selection (a selected display equation, or an inline one beside the caret). */
  static EquationEditing at(Session s) {
    return at(s, -1);
  }

  /**
   * The equation in {@code block} (a display equation), or else at the selection: an inline
   * equation beside the caret, or a display equation the caret paragraph follows (how objects
   * are selected for their commands).
   */
  static EquationEditing at(Session s, int block) {
    var blocks = s.document().blocks;
    if (block >= 0) return block < blocks.size() ? display(s, block) : null;
    var sel = s.selection();
    if (sel.block < 0 || sel.block >= blocks.size()) return null;
    Block b = blocks.get(sel.block);
    if (b instanceof Paragraph para) {
      for (int at : new int[] {sel.caret, sel.caret - 1}) {
        if (at < 0 || at >= para.text.length()) continue;
        InlineField f = para.text.fieldAt(at);
        if (Equation.inline(f)) return new EquationEditing(s, sel.block, at, Equation.inlineText(f));
      }
    }
    for (int i = sel.block; i >= 0 && i >= sel.block - 2; i--) {
      var e = display(s, i);
      if (e != null) return e;
      if (i == sel.block && b instanceof Paragraph para && para.text.length() > 0) break;
    }
    return null;
  }

  private static EquationEditing display(Session s, int block) {
    Block b = s.document().blocks.get(block);
    return b instanceof Picture p && p.drawing != null && "equation".equals(p.drawing.kind)
        ? new EquationEditing(s, block, -1, p.drawing.get("text", "")) : null;
  }

  /** The linear text the document holds for this equation now. */
  String stored() {
    Block b = session.document().blocks.get(block);
    if (field < 0) return b instanceof Picture p && p.drawing != null ? p.drawing.get("text", "") : "";
    InlineField f = b instanceof Paragraph para && field < para.text.length() ? para.text.fieldAt(field) : null;
    return Equation.inline(f) ? Equation.inlineText(f) : "";
  }

  /** Writes the editor's text back when it changed (one undo step). */
  void commit() {
    commit(false);
  }

  /**
   * Writes the editor's text back when it changed. {@code typing} joins the change to the
   * previous typing in this equation as one undo step, as Wordcraft groups {@code equation.type}
   * under one "Typing" step until another edit happens.
   */
  void commit(boolean typing) {
    String text = editor.linear();
    String now = stored();
    if (field >= 0 && text.isEmpty()) text = EquationEditor.HOLE;
    if (text.equals(now)) return;
    final String value = text;
    if (typing) session.transactGrouped(this, x -> write(x, block, field, value));
    else write(session, block, field, value);
  }

  /** Sets an equation's linear text: a display block's drawing, or an inline field. */
  static void write(Session s, int block, int field, String text) {
    Block b = s.document().blocks.get(block);
    if (field < 0) {
      if (!(b instanceof Picture p) || p.drawing == null || !"equation".equals(p.drawing.kind))
        throw new IllegalArgumentException("Select an equation");
      Drawing d = p.drawing.with("text", text);
      float h = Math.max(p.height, Equation.height(Equation.fromLinear(text), d.num("fontSize", 12)));
      s.replacePicture(block, p.drawing(d).resize(p.width, h, p.alt, p.align));
      return;
    }
    if (!(b instanceof Paragraph para) || !Equation.inline(para.text.fieldAt(field)))
      throw new IllegalArgumentException("No inline equation there");
    final String pid = para.id;
    final InlineField value = Equation.inline(text);
    s.mapFields((paragraph, at, f) -> pid.equals(paragraph) && at == field ? value : f);
  }

  void type(String text) {
    editor.type(text, normalText);
    commit(true);
  }

  void backspace() {
    editor.deleteBack();
    commit();
  }

  void delete() {
    editor.deleteForward();
    commit();
  }

  void insertTemplate(String linear) {
    insertTemplate(linear, "");
  }

  /** A Structures template; {@code limits} forces its n-ary limit placement ("" keeps the default). */
  void insertTemplate(String linear, String limits) {
    editor.insertTemplate(linear, limits);
    commit();
  }

  /** A structure action from {@link EquationEditor#structureActions}; false when it does not apply. */
  boolean structure(int level, String action) {
    if (!editor.applyStructure(level, action)) return false;
    commit();
    return true;
  }

  /** Arrow, Home/End, Tab: leaving a slot finishes it first; false when the caret leaves. */
  boolean move(String dir) {
    if (!normalText && editor.finish()) commit();
    switch (dir) {
      case "left":
        return editor.moveLeft();
      case "right":
        return editor.moveRight();
      case "up":
        editor.moveVertical(true);
        return true;
      case "down":
        editor.moveVertical(false);
        return true;
      case "home":
        editor.homeEnd(false);
        return true;
      case "end":
        editor.homeEnd(true);
        return true;
      case "next":
        editor.nextPlaceholder(false);
        return true;
      case "prev":
        editor.nextPlaceholder(true);
        return true;
      default:
        throw new IllegalArgumentException("Unknown direction " + dir);
    }
  }

  /** Enter: build up what was typed; false when there was nothing to build (editing ends). */
  boolean enter() {
    if (normalText) return false;
    boolean changed = editor.finish();
    // A top-level '#' numbers a display equation, as in Word and Wordcraft's number_equation.
    String number = field < 0 ? editor.takeNumber() : null;
    if (number != null) {
      if (number.length() > 1 && number.charAt(0) == '(' && number.charAt(number.length() - 1) == ')')
        number = number.substring(1, number.length() - 1);
      final String n = number.isEmpty() ? "1" : number;
      final String text = editor.linear();
      session.transact(x -> {
        write(x, block, field, text);
        Picture p = (Picture) x.document().blocks.get(block);
        x.replacePicture(block, p.drawing(p.drawing.with("number", n)));
      });
      return true;
    }
    if (!changed) return false;
    commit();
    return true;
  }

  /**
   * Places the caret at the slot position nearest to ({@code x}, {@code y}) in the drawing's own
   * coordinates, as Wordcraft's {@code equation_hit} does for a click in an equation.
   */
  void hit(Drawing d, float w, float h, LayoutEngine.Metrics metrics, float x, float y) {
    EquationEditor.Pos best = editor.pos;
    float bestScore = Float.MAX_VALUE;
    for (EquationEditor.Pos p : editor.positions(400)) {
      editor.pos = p;
      float[] c = caret(d, w, h, metrics);
      float dy = y < c[1] ? c[1] - y : y > c[1] + c[2] ? y - c[1] - c[2] : 0;
      // Inside a slot's line, horizontal distance decides; deeper slots win ties (the click is
      // on the smaller glyphs of a script or limit).
      float score = Math.abs(x - c[0]) + dy * 4 - p.depth() * 0.01f;
      if (score < bestScore) {
        bestScore = score;
        best = p;
      }
    }
    editor.pos = best;
  }

  /** Puts the document caret beside the equation after editing ends ({@code before}: left of it). */
  void leave(boolean before) {
    if (field >= 0) {
      int at = before ? field : field + 1;
      session.select(block, at, at);
    }
  }

  /**
   * The caret in the equation drawing's own coordinates, {x, top, height}, matching the
   * placement {@link DrawingOps} uses for built-up equations of {@code w} by {@code h}.
   */
  float[] caret(Drawing d, float w, float h, LayoutEngine.Metrics metrics) {
    float size = d.num("fontSize", 12);
    String justify = d.get("justify", "center");
    float right = d.get("number", "").isEmpty() ? w : w - 40;
    Equation.Box box = Equation.layout(Equation.of(d), size, metrics);
    float x = "left".equals(justify) ? 0 : "right".equals(justify) ? right - box.width : (right - box.width) / 2;
    x = Math.max(0, x);
    float base = d.flag("inline") ? 0 : (h - box.ascent - box.descent) / 2 + box.ascent;
    float[] c = editor.caret(size, metrics);
    return new float[] {x + c[0], base + c[1] - c[2] * .85f, c[2] * 1.1f};
  }
}
