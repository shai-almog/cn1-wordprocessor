package com.codenameone.wordprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/** Wordcraft's LaTeX math input (math_latex::parse_latex) and the LaTeX build-up mode. */
public class MathLatexTest {
  private static String lin(String tex) {
    return Equation.linear(MathLatex.parse(tex));
  }

  @Test
  public void parsesStructures() {
    assertEquals("a/b", lin("\\frac{a}{b}"));
    assertEquals("x^2+y_1^3", lin("x^2+y_1^3"));
    assertEquals("\u2211_(i=1)^n\u2592(i^2)=k", lin("\\sum_{i=1}^{n} i^2 = k"));
    assertEquals("(a/b)", lin("\\left( \\frac{a}{b} \\right)"));
    assertEquals("(\u25a0(a&b@c&d))", lin("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}"));
    assertEquals("x\u0302+v\u20d7", lin("\\hat{x}+\\vec{v}"));
    assertEquals("\u211d", lin("\\mathbb{R}"));
    assertEquals("(n\u00a6k)", lin("\\binom{n}{k}"));
    assertEquals("\u03b1\u03b2", lin("\\alpha\\beta"));
    assertEquals("sin^2\u2061x", lin("\\sin^2 x"));
    assertTrue(lin("\\sqrt[3]{x+1}").startsWith("\u221a("));
    assertTrue(lin("\\lim_{x\\to 0} \\frac{\\sin x}{x}").startsWith("lim\u252c(x\u21920)"));
    assertTrue(lin("f(x)=\\begin{cases} 1 & x>0 \\\\ 0 & x \\le 0 \\end{cases}").contains("{\u2588(1&x>0@0&x\u22640)"));
    // Unknown commands stay as text; hostile input never throws.
    assertEquals("\\foo", lin("\\foo"));
    var deep = new StringBuilder();
    for (int i = 0; i < 5000; i++) deep.append("\\frac{");
    lin(deep.toString());
    lin("}}}^_&\\\\\\left");
  }

  @Test
  public void latexBuildUpWhenBracesBalance() {
    var e = new EquationEditor("");
    e.latex = true;
    e.type("\\frac{a}{b", false);
    // Braces still open: nothing builds yet.
    e.type(" ", false);
    assertTrue(e.linear().contains("\\frac"));
    e.type("} ", false);
    assertEquals("a/b", e.linear());
    var u = new EquationEditor("");
    u.type("\\frac{a}{b} ", false);
    assertFalse("a/b".equals(u.linear()));
  }

  @Test
  public void inputFormatCommandAndInsertLatex() {
    var s = new Session(Document.empty());
    var c = new Commands();
    assertEquals("latex", ((Map<?, ?>) c.execute(s, "equation.inputFormat", Json.object("format", "latex"))).get("format"));
    assertTrue(s.mathLatex);
    c.execute(s, "insert.equation", Json.object("latex", "\\frac{1}{2}", "display", true));
    var e = EquationEditing.at(s);
    assertTrue(e != null && e.editor.latex);
    assertEquals("1/2", e.stored());
    c.execute(s, "equation.inputFormat", Json.object("format", "unicode"));
    assertFalse(s.mathLatex);
    assertNull(EquationEditing.inline(s, 99, 0));
  }
}
