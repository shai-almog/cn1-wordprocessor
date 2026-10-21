package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class LatexTest {
  private static final TextStyle T = TextStyle.DEFAULT;

  private static Paragraph p(String id, RichText text) {
    return new Paragraph(id, text);
  }

  private static Sequence<Paragraph> cell(String id, String text) {
    return Sequence.<Paragraph>of(Collections.singletonList(p(id, RichText.of(text, T))));
  }

  private static Document doc(Block... blocks) {
    return new Document(Sequence.<Block>of(Arrays.asList(blocks)), PageSpec.A4, "t", 1000);
  }

  private static Document sample() {
    var note = new Note("n1", "footnote", "", "1",
        Sequence.<Paragraph>of(Collections.singletonList(p("np", RichText.of("A note.", T)))));
    var cells = new ArrayList<List<Sequence<Paragraph>>>();
    cells.add(Arrays.asList(cell("c1", "Name"), cell("c2", "Value"), cell("c3", "")));
    cells.add(Arrays.asList(cell("c4", "Merged"), cell("c5", ""), cell("c6", "x")));
    cells.add(Arrays.asList(cell("c7", "Tall"), cell("c8", "y"), cell("c9", "z")));
    cells.add(Arrays.asList(cell("c10", ""), cell("c11", "w"), cell("c12", "v")));
    var table = new Table("tb", cells,
        Arrays.asList(new Table.Merge(1, 0, 1, 2), new Table.Merge(2, 0, 2, 1)));
    var d = doc(
        p("h1", RichText.of("Introduction", T)).with("style", "Heading1"),
        p("b1", RichText.of("Plain ", T)
            .concat(RichText.of("bold", T.with("bold", "true")))
            .concat(RichText.of(" and ", T))
            .concat(RichText.of("italic", T.with("italic", "true")))
            .concat(RichText.of(" under", T.with("underline", "true")))
            .concat(RichText.of(" gone", T.with("strike", "true")))
            .concat(RichText.of(" Caps", T.with("smallCaps", "true")))
            .concat(RichText.of("2", T.with("script", "superscript")))
            .concat(RichText.of("i", T.with("script", "subscript")))
            .concat(RichText.of(" red", T.with("color", "ff0000")))
            .concat(RichText.of(" big", T.size(18)))
            .concat(RichText.of(" site", T.with("link", "https://example.com/a%20b")))
            .concat(RichText.note(note, T))),
        p("h2", RichText.of("Details", T)).with("style", "Heading2"),
        p("l1", RichText.of("first", T)).with("list", "bullet"),
        p("l2", RichText.of("nested", T)).with("list", "number").with("listLevel", "1"),
        p("l3", RichText.of("second", T)).with("list", "bullet"),
        p("c", RichText.of("Centered", T)).with("align", "center"),
        table,
        p("r", RichText.of("See ", T).concat(RichText.field(
            new InlineField("REF intro \\h", "Introduction", false, false), T))));
    var marks = new LinkedHashMap<String, Bookmark>();
    marks.put("intro", new Bookmark("intro", "h1", 0, "h1", 0));
    return d.bookmarks(marks).properties(
        DocumentProperties.EMPTY.with("title", "A Paper").with("author", "Ada Lovelace"));
  }

  @Test
  public void exportsStandaloneDocumentWithEachConstruct() {
    String tex = Latex.write(sample());
    assertTrue(tex.startsWith("\\documentclass{article}\n"));
    for (String expected : new String[] {
      "\\usepackage[normalem]{ulem}", "\\usepackage{xcolor}", "\\usepackage{multirow}",
      "\\usepackage{hyperref}", "\\title{A Paper}", "\\author{Ada Lovelace}", "\\maketitle",
      "\\section*{Introduction}\\label{intro}", "\\subsection*{Details}", "\\textbf{bold}",
      "\\textit{italic}", "\\uline{ under}", "\\sout{ gone}", "\\textsc{ Caps}",
      "\\textsuperscript{2}", "\\textsubscript{i}", "\\textcolor[HTML]{FF0000}{ red}",
      "{\\fontsize{18}{21.6}\\selectfont{} big}", "\\href{https://example.com/a\\%20b}{ site}",
      "\\footnote{A note.}", "\\begin{itemize}\n\\item{} first\n\\begin{enumerate}\n\\item{} nested\n\\end{enumerate}\n\\item{} second\n\\end{itemize}",
      "\\begin{center}\nCentered\n\\end{center}", "\\begin{tabular}{|l|l|l|}",
      "\\multicolumn{2}{|l|}{Merged} & x \\\\", "\\multirow{2}{*}{Tall} & y & z",
      "\\cline{2-2}\\cline{3-3}", "\\hyperref[intro]{Introduction}", "\\end{document}\n"
    }) assertTrue(expected + "\n---\n" + tex, tex.contains(expected));
    assertFalse(tex.contains("\\longtable"));
  }

  @Test
  public void escapesSpecialCharactersAndUnicode() {
    assertEquals("\\textbackslash{}\\{\\}\\$\\&\\%\\#\\_\\textasciitilde{}\\textasciicircum{}",
        Latex.escape("\\{}$&%#_~^"));
    assertEquals("a--b---c\\ldots{} ``q'' -{}- '{}' x~y \\textless{}\\textgreater{}\\textbar{}",
        Latex.escape("a\u2013b\u2014c\u2026 \u201cq\u201d -- '' x\u00a0y <>|"));
    assertEquals("caf\u00e9 \u00fc\\newline{}next", Latex.escape("caf\u00e9 \u00fc\nnext"));
    var tex = Latex.write(doc(p("a", RichText.of("50% of $x & {y}_1 #2 ~ ^ \\cmd", T))));
    var back = Latex.read(tex);
    assertEquals("50% of $x & {y}_1 #2 ~ ^ \\cmd", back.paragraph(0).text.plainText());
  }

  @Test
  public void importsRealisticPaper() {
    String src = String.join("\n",
        "\\documentclass[11pt]{article}",
        "\\usepackage{amsmath} % maths",
        "\\newcommand{\\R}{\\mathbb{R}}",
        "\\title{On \\emph{Things}}",
        "\\author{A. Author \\and B. Writer\\thanks{Funded.}}",
        "\\begin{document}",
        "\\maketitle",
        "\\begin{abstract}",
        "We study things.",
        "\\end{abstract}",
        "\\section{Introduction}\\label{sec:intro}",
        "Text with \\textbf{bold}, \\emph{emphasis}, \\underline{under} and",
        "a footnote\\footnote{The note \\textit{text}.}. See Section~\\ref{sec:intro}",
        "% a comment that is dropped",
        "and \\url{https://x.org} or \\href{https://y.org}{the site}. Na\\\"ive caf\\'e --- done.",
        "",
        "Second paragraph\\\\with a break and \\verb|a_b$| verbatim. Inline $\\alpha + x^2$.",
        "\\subsection*{Lists}",
        "\\begin{itemize}",
        "  \\item One",
        "  \\begin{enumerate}\\item Inner\\end{enumerate}",
        "  \\item Two",
        "\\end{itemize}",
        "\\begin{quote}Quoted.\\end{quote}",
        "\\begin{verbatim}",
        "x = {1, 2}",
        "\\end{verbatim}",
        "\\begin{table}[h]\\centering",
        "\\begin{tabular}{|l|c|r|}",
        "\\hline",
        "\\multicolumn{2}{|c|}{Head} & R \\\\ \\hline",
        "\\multirow{2}{*}{M} & b & c \\\\",
        " & e & f \\\\ \\hline",
        "\\end{tabular}",
        "\\caption{A table}",
        "\\end{table}",
        "\\begin{figure}\\includegraphics[width=3cm]{missing.png}\\end{figure}",
        "\\[ E = mc^2 \\]",
        "\\unknownmacro{kept words} \\vspace{2cm} end.",
        "\\end{document}",
        "ignored after end");
    Document d = Latex.read(src);
    assertEquals("On Things", d.properties.get("title"));
    assertEquals("A. Author , B. Writer", d.properties.get("author"));
    String all = plain(d);
    for (String expected : new String[] {
      "We study things.", "Introduction", "Quoted.", "x = {1, 2}", "A table", "[Picture: missing.png]",
      "E = mc^2", "kept words end.", "Na\u00efve caf\u00e9 \u2014 done.", "the site", "a_b$",
      "\u03b1 + x^2", "Second paragraph\nwith a break"
    }) assertTrue(expected + "\n---\n" + all, all.contains(expected));
    assertFalse(all.contains("comment"));
    assertFalse(all.contains("ignored"));
    assertFalse(all.contains("2cm"));
    Paragraph intro = find(d, "Introduction");
    assertEquals("Heading1", intro.style);
    assertEquals("Heading2", find(d, "Lists").style);
    assertEquals("Title", find(d, "On Things").style);
    assertNotNull(d.bookmarks.get("sec_intro"));
    Paragraph body = find(d, "Text with");
    assertTrue(body.text.hasNotes());
    assertEquals(1, body.text.fields().size());
    assertTrue(body.text.fields().get(0).instruction.startsWith("REF sec_intro"));
    assertTrue(styleOf(body, "bold").bold);
    assertTrue(styleOf(body, "emphasis").italic);
    assertTrue(styleOf(body, "under").hasUnderline());
    assertEquals("https://y.org", styleOf(body, "the site").link);
    assertEquals("bullet", find(d, "One").list);
    assertEquals("number", find(d, "Inner").list);
    assertEquals(1, find(d, "Inner").listLevel);
    assertTrue(find(d, "Quoted.").indent > 0);
    Table t = null;
    for (Block b : d.blocks) if (b instanceof Table x) t = x;
    assertNotNull(t);
    assertEquals(3, t.rows());
    assertEquals(3, t.columns());
    assertEquals(2, t.span(0, 0).columns);
    assertEquals(2, t.span(1, 0).rows);
    assertEquals("e", t.cell(2, 1).get(0).text.plainText());
    assertEquals("right", t.cell(0, 2).get(0).align);
  }

  @Test
  public void roundTripPreservesStructure() {
    Document original = sample();
    Document back = Latex.read(Latex.write(original));
    String text = plain(back);
    for (String w : new String[] {"Introduction", "Details", "first", "nested", "second", "Centered",
      "Merged", "Tall", "See Introduction"}) assertTrue(w + "\n" + text, text.contains(w));
    assertEquals("Heading1", find(back, "Introduction").style);
    assertEquals("Heading2", find(back, "Details").style);
    assertEquals("bullet", find(back, "first").list);
    assertEquals("number", find(back, "nested").list);
    assertEquals("center", find(back, "Centered").align);
    Paragraph b1 = find(back, "Plain");
    assertTrue(styleOf(b1, "bold").bold);
    assertTrue(styleOf(b1, "italic").italic);
    assertTrue(styleOf(b1, "under").hasUnderline());
    assertTrue(styleOf(b1, "gone").strike);
    assertTrue(styleOf(b1, "Caps").characters.smallCaps);
    assertEquals(0xff0000, styleOf(b1, "red").color);
    assertEquals(18f, styleOf(b1, "big").size, .01f);
    assertEquals("https://example.com/a%20b", styleOf(b1, "site").link);
    assertTrue(b1.text.hasNotes());
    assertEquals("A Paper", back.properties.get("title"));
    assertNotNull(back.bookmarks.get("intro"));
    Table t = null;
    for (Block b : back.blocks) if (b instanceof Table x) t = x;
    assertNotNull(t);
    assertEquals(4, t.rows());
    assertEquals(3, t.columns());
    assertEquals(2, t.span(1, 0).columns);
    assertEquals(2, t.span(2, 0).rows);
    assertEquals("w", t.cell(3, 1).get(0).text.plainText());
    // A second trip is stable.
    assertEquals(Latex.write(back), Latex.write(Latex.read(Latex.write(back))));
  }

  @Test
  public void hostileInputNeverThrows() {
    var r = new Random(7);
    String alphabet = "\\{}[]$&%~^_#\n ab";
    String[] pieces = {"\\begin{itemize}", "\\end{itemize}", "\\begin{tabular}{", "\\end{tabular}",
      "\\item", "\\footnote{", "\\multicolumn{", "\\multirow{-3}{*}{", "\\\\", "&", "\\verb", "\\section{",
      "\\label{", "\\ref{", "\\href{", "\\begin{verbatim}", "\\textbf{", "$$", "\\[", "\\'", "\\"};
    for (int k = 0; k < 400; k++) {
      var b = new StringBuilder();
      int len = r.nextInt(200);
      for (int j = 0; j < len; j++)
        if (r.nextInt(3) == 0) b.append(pieces[r.nextInt(pieces.length)]);
        else b.append(alphabet.charAt(r.nextInt(alphabet.length())));
      Document d = Latex.read(b.toString());
      assertNotNull(d);
      Latex.write(d);
    }
    var deep = new StringBuilder();
    for (int k = 0; k < 100000; k++) deep.append("{\\textbf{\\begin{quote}");
    assertNotNull(Latex.read(deep.toString()));
    assertNotNull(Latex.read(""));
    assertNotNull(Latex.read("\\begin{tabular}{*{999999}{c}} a & b"));
  }

  private static String plain(Document d) {
    var b = new StringBuilder();
    for (Block block : d.blocks)
      if (block instanceof Paragraph p) b.append(p.text.plainText()).append('\n');
      else if (block instanceof Picture pic && pic.drawing != null)
        b.append(pic.drawing.get("text", "")).append('\n');
      else if (block instanceof Table t)
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            for (Paragraph p : t.cell(r, c)) b.append(p.text.plainText()).append('\n');
    return b.toString();
  }

  private static Paragraph find(Document d, String prefix) {
    for (Block b : d.blocks)
      if (b instanceof Paragraph p && p.text.plainText().startsWith(prefix)) return p;
    throw new AssertionError("No paragraph " + prefix + "\n" + plain(d));
  }

  private static TextStyle styleOf(Paragraph p, String word) {
    var raw = new StringBuilder();
    for (int k = 0; k < p.text.length(); k++) raw.append(p.text.charAt(k));
    int at = raw.indexOf(word);
    assertTrue(word, at >= 0);
    return p.text.styleAt(at + 1);
  }
}
