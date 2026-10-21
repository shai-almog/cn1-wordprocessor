package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.util.*;

/** OOXML simple and complex inline fields. Unsupported instructions retain their cached result. */
final class DocxFields {
  interface RunReader {
    RichText read(Element run, String link);
  }

  private static final class Pending {
    final StringBuilder instruction = new StringBuilder();
    RichText result = new RichText();
    TextStyle style;
    boolean separated, locked;

    Pending(TextStyle style, boolean locked) {
      this.style = style;
      this.locked = locked;
    }
  }

  private final RunReader reader;
  private final DocxLinks links;
  private final Set<String> warnings;
  private final List<Pending> stack = new ArrayList<>();
  private RichText text = new RichText();
  private Revision revision;

  private DocxFields(RunReader reader, DocxLinks links, Set<String> warnings) {
    this.reader = reader;
    this.links = links;
    this.warnings = warnings;
  }

  static RichText read(Element paragraph, RunReader reader, DocxLinks links, Set<String> warnings) {
    var parser = new DocxFields(reader, links, warnings);
    for (Element child : Xml.children(paragraph, null)) parser.node(child, "");
    while (!parser.stack.isEmpty()) {
      warnings.add("Unclosed complex field");
      parser.close();
    }
    return parser.text;
  }

  private void append(RichText value) {
    if (revision != null) {
      final var mark = revision;
      value = value.mapStyles(s -> s.revision(mark));
    }
    if (stack.isEmpty()) text = text.concat(value);
    else {
      var field = stack.get(stack.size() - 1);
      if (field.separated) field.result = field.result.concat(value);
    }
  }

  private void close() {
    var field = stack.remove(stack.size() - 1);
    var instruction = field.instruction.toString().trim();
    if (instruction.isEmpty()) {
      warnings.add("Field without instructions");
      append(field.result);
      return;
    }
    if (!stack.isEmpty()) {
      var parent = stack.get(stack.size() - 1);
      if (!parent.separated) {
        parent.instruction.append(" { ").append(instruction).append(" } ");
        return;
      }
      warnings.add("Nested field result is flattened within its parent field");
    }
    String formats = instruction.startsWith(Zotero.PREFIX) ? formats(field.result, field.style) : "";
    if (formats.isEmpty()) checkResult(field.result, field.style);
    checkInstruction(instruction);
    append(
        RichText.field(
            new InlineField(instruction, field.result.plainText(), field.locked, false, formats),
            field.style));
  }

  /** Field types kept and saved verbatim but displayed only as their stored result. */
  private void checkInstruction(String instruction) {
    String type = instruction.trim();
    int space = type.indexOf(' ');
    if (space > 0) type = type.substring(0, space);
    if ("EQ".equalsIgnoreCase(type))
      warnings.add("Equation field (EQ, used for enclosed and combined characters) shown as its stored result");
  }

  private void node(Element node, String link) {
    String kind = Xml.local(node);
    if ("pPr".equals(kind)
        || "commentRangeStart".equals(kind)
        || "commentRangeEnd".equals(kind)
        || "bookmarkStart".equals(kind)
        || "bookmarkEnd".equals(kind)) return;
    if (RevisionFormats.tracked(kind)) {
      var before = revision;
      revision = RevisionFormats.docxRead(node).previous(before);
      for (Element child : Xml.children(node, null)) node(child, link);
      revision = before;
      return;
    }
    if ("oMathPara".equals(kind)) return;
    if ("oMath".equals(kind)) {
      // m:oMath among the runs is an inline equation; m:oMathPara stays a display (block) equation.
      String linear = Equation.linear(Equation.fromOmml(node));
      append(RichText.field(Equation.inline(linear), TextStyle.DEFAULT));
      return;
    }
    if ("fldSimple".equals(kind)) {
      RichText result = read(node, reader, links, warnings);
      String instruction = Xml.attr(node, "instr", "");
      if (instruction.trim().isEmpty()) {
        warnings.add("Field without instructions");
        append(result);
      } else {
        checkResult(result, result.styleAt(0));
        checkInstruction(instruction);
        append(
            RichText.field(
                new InlineField(instruction, result.plainText(), enabled(node, "fldLock"), false),
                result.styleAt(0)));
      }
      return;
    }
    if ("hyperlink".equals(kind)) {
      for (Element child : Xml.children(node, null)) node(child, links.target(node));
      return;
    }
    if ("sdt".equals(kind)) {
      Element props = Xml.child(node, "sdtPr"), content = Xml.child(node, "sdtContent");
      ContentControl control = props == null ? null : DocxControls.read(props);
      if (control == null || content == null) {
        if (content != null) for (Element child : Xml.children(content, null)) node(child, link);
        return;
      }
      RichText result = read(content, reader, links, warnings);
      var value = result.plainText();
      // Repeating section items are stored as the section's "; "-separated content.
      if ("repeatingSection".equals(control.type) && !value.isEmpty())
        control = control.with("items", value.replace("; ", "|"));
      append(
          RichText.field(
              control.field(value),
              value.isEmpty() ? TextStyle.DEFAULT : result.styleAt(0)));
      return;
    }
    if (!"r".equals(kind)) {
      warnings.add("Inline feature: " + kind);
      for (Element child : Xml.children(node, null)) node(child, link);
      return;
    }
    var style = reader.read(node, link).styleAt(0).revision(revision);
    boolean skipCustomMark = false;
    for (Element child : Xml.children(node, null)) {
      String type = Xml.local(child);
      if ("footnoteReference".equals(type) || "endnoteReference".equals(type)) {
        if (links.notes == null) throw new IllegalArgumentException("Notes outside the main story are unsupported");
        var mark = "";
        if (enabled(child, "customMarkFollows")) {
          Element literal = Xml.child(node, "t");
          if (literal == null) throw new IllegalArgumentException("Missing custom note mark");
          mark = Xml.text(literal); skipCustomMark = true;
        }
        var note = links.notes.read(type.startsWith("foot") ? "footnote" : "endnote",
            Xml.attr(child, "id", ""), mark, warnings);
        append(RichText.note(note, style.with("script", "superscript")));
        continue;
      }
      if (skipCustomMark && "t".equals(type)) { skipCustomMark = false; continue; }
      if ("fldChar".equals(type)) {
        String action = Xml.attr(child, "fldCharType", "");
        if ("begin".equals(action)) {
          if (stack.size() >= 64) throw new IllegalArgumentException("Field nesting exceeds 64");
          stack.add(new Pending(style, enabled(child, "fldLock")));
        } else if ("separate".equals(action) && !stack.isEmpty())
          stack.get(stack.size() - 1).separated = true;
        else if ("end".equals(action) && !stack.isEmpty()) close();
        else warnings.add("Unmatched complex field marker");
      } else if ("ruby".equals(type)) {
        append(Docx.ruby(child, style));
      } else if (("instrText".equals(type) || "delInstrText".equals(type))) {
        if (stack.isEmpty()) warnings.add("Instruction outside a field");
        else stack.get(stack.size() - 1).instruction.append(Xml.text(child));
      } else if (("t".equals(type) || "delText".equals(type))
          || "tab".equals(type)
          || "br".equals(type)
          || "cr".equals(type)) {
        if (!stack.isEmpty()
            && stack.get(stack.size() - 1).separated
            && stack.get(stack.size() - 1).result.length() == 0)
          stack.get(stack.size() - 1).style = style;
        append(
            RichText.of(
                ("t".equals(type) || "delText".equals(type))
                    ? Xml.text(child)
                    : "tab".equals(type)
                        ? "\t"
                        // Docx.COLUMN_BREAK: lifted into ParagraphFormat.columnBreak by Docx.
                        : "br".equals(type) && "column".equals(Xml.attr(child, "type", ""))
                            ? Docx.COLUMN_BREAK_TEXT
                            : "\n",
                style));
      }
    }
  }

  /** A Zotero result's formatted runs relative to the field's first-run style ("" if uniform). */
  private static String formats(RichText result, TextStyle base) {
    var b = new StringBuilder();
    boolean[] any = {false};
    result.visit(
        new RichText.Visitor() {
          public void run(int offset, String text, TextStyle style) {
            if (text.isEmpty()) return;
            int flags = InlineField.flags(style, base);
            any[0] |= flags != 0;
            if (b.length() > 0) b.append(';');
            b.append(text.length()).append(':').append(flags);
          }

          public void field(int offset, InlineField f, TextStyle style) {
            run(offset, InlineField.OBJECT, style);
          }
        });
    return any[0] ? b.toString() : "";
  }

  private void checkResult(RichText result, TextStyle first) {
    result.visit(
        (offset, text, style) -> {
          if (!style.equals(first))
            warnings.add("Mixed formatting within a field result is flattened");
          if (text.indexOf('\n') >= 0 || text.indexOf('\t') >= 0 || text.indexOf('\r') >= 0)
            warnings.add("Field result line breaks and tabs display as spaces");
        });
  }

  private static boolean enabled(Element node, String name) {
    String value = Xml.attr(node, name, "0");
    return "1".equals(value) || "true".equals(value) || "on".equals(value);
  }

  static void write(StringBuilder b, InlineField field, TextStyle style, DocxLinks links) {
    if (field.rangeEnd()) {
      // A range field's end mark; its result was written as the runs before it.
      b.append("<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>");
      return;
    }
    if (field.rangeStart()) {
      // A range field's start mark: begin, code and separate; the paragraphs' own runs follow.
      b.append("<w:r><w:fldChar w:fldCharType=\"begin\" w:fldLock=\"")
          .append(field.locked ? 1 : 0)
          .append("\"/></w:r><w:r><w:instrText xml:space=\"preserve\">")
          .append(Xml.escape(field.instruction))
          .append("</w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>");
      return;
    }
    if (Equation.inline(field)) {
      // An inline equation is m:oMath directly in the paragraph (no m:oMathPara).
      b.append(Equation.omml(Equation.fromLinear(Equation.inlineText(field))));
      return;
    }
    if (ContentControl.is(field) && style.revision == null) {
      DocxControls.write(b, field, style, links);
      return;
    }
    if (style.revision != null || Zotero.is(field)) {
      // Zotero's Word plugin addresses citations as complex fields, so ADDIN fields stay complex.
      if (style.revision != null) RevisionFormats.start(b, style.revision);
      b.append("<w:r><w:fldChar w:fldCharType=\"begin\" w:fldLock=\"")
          .append(field.locked ? 1 : 0)
          .append("\"/></w:r>");
      String instruction =
          style.revision == null || style.revision.inserted() ? "instrText" : "delInstrText";
      b.append("<w:r><w:")
          .append(instruction)
          .append(" xml:space=\"preserve\">")
          .append(Xml.escape(field.instruction))
          .append("</w:")
          .append(instruction)
          .append("></w:r>");
      b.append("<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>");
      var pieces = style.revision == null ? field.piecesOf(field.result) : null;
      // A Zotero bibliography is one paragraph per entry between its marks, as Wordcraft writes it.
      boolean split = style.revision == null && links.paragraphMarks && field.result.indexOf('\n') >= 0
          && Zotero.code(field.instruction).regionMatches(true, 0, "BIBL", 0, 4);
      if (pieces != null || split) {
        // Formatted runs between separate and end, as Word and Wordcraft keep a citation's.
        if (pieces == null) pieces = InlineField.plainPieces(field.result);
        for (InlineField.Piece piece : pieces) {
          String t = piece.text;
          for (int i = 0; ; ) {
            int nl = split ? t.indexOf('\n', i) : -1;
            String part = nl < 0 ? t.substring(i) : t.substring(i, nl);
            if (!part.isEmpty()) Docx.writeRun(b, part, piece.style(style), links);
            if (nl < 0) break;
            b.append(DocxLinks.PARAGRAPH);
            i = nl + 1;
          }
        }
      } else if (style.revision == null) Docx.writeRun(b, field.result, style, links);
      else Docx.writeRun(b, field.result, style.revision(null), links, !style.revision.inserted());
      b.append("<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>");
      if (style.revision != null) RevisionFormats.end(b, style.revision);
      return;
    }
    b.append("<w:fldSimple w:instr=\"")
        .append(Xml.escape(field.instruction))
        .append("\" w:fldLock=\"")
        .append(field.locked ? 1 : 0)
        .append("\">");
    Docx.writeRun(b, field.result, style, links);
    b.append("</w:fldSimple>");
  }
}
