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
  private final List<Pending> stack = new ArrayList<Pending>();
  private RichText text = new RichText();
  private Revision revision;

  private DocxFields(RunReader reader, DocxLinks links, Set<String> warnings) {
    this.reader = reader;
    this.links = links;
    this.warnings = warnings;
  }

  static RichText read(Element paragraph, RunReader reader, DocxLinks links, Set<String> warnings) {
    DocxFields parser = new DocxFields(reader, links, warnings);
    for (Element child : Xml.children(paragraph, null)) parser.node(child, "");
    while (!parser.stack.isEmpty()) {
      warnings.add("Unclosed complex field");
      parser.close();
    }
    return parser.text;
  }

  private void append(RichText value) {
    if (revision != null) {
      final Revision mark = revision;
      value = value.mapStyles(s -> s.revision(mark));
    }
    if (stack.isEmpty()) text = text.concat(value);
    else {
      Pending field = stack.get(stack.size() - 1);
      if (field.separated) field.result = field.result.concat(value);
    }
  }

  private void close() {
    Pending field = stack.remove(stack.size() - 1);
    String instruction = field.instruction.toString().trim();
    if (instruction.isEmpty()) {
      warnings.add("Field without instructions");
      append(field.result);
      return;
    }
    if (!stack.isEmpty()) {
      Pending parent = stack.get(stack.size() - 1);
      if (!parent.separated) {
        parent.instruction.append(" { ").append(instruction).append(" } ");
        return;
      }
      warnings.add("Nested field result is flattened within its parent field");
    }
    checkResult(field.result, field.style);
    append(
        RichText.field(
            new InlineField(instruction, field.result.plainText(), field.locked, false),
            field.style));
  }

  private void node(Element node, String link) {
    String kind = Xml.local(node);
    if ("pPr".equals(kind)
        || "commentRangeStart".equals(kind)
        || "commentRangeEnd".equals(kind)
        || "bookmarkStart".equals(kind)
        || "bookmarkEnd".equals(kind)) return;
    if ("ins".equals(kind) || "del".equals(kind)) {
      Revision before = revision;
      revision = RevisionFormats.docxRead(node).previous(before);
      for (Element child : Xml.children(node, null)) node(child, link);
      revision = before;
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
      String value = result.plainText();
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
    TextStyle style = reader.read(node, link).styleAt(0).revision(revision);
    boolean skipCustomMark = false;
    for (Element child : Xml.children(node, null)) {
      String type = Xml.local(child);
      if ("footnoteReference".equals(type) || "endnoteReference".equals(type)) {
        if (links.notes == null) throw new IllegalArgumentException("Notes outside the main story are unsupported");
        String mark = "";
        if (enabled(child, "customMarkFollows")) {
          Element literal = Xml.child(node, "t");
          if (literal == null) throw new IllegalArgumentException("Missing custom note mark");
          mark = Xml.text(literal); skipCustomMark = true;
        }
        Note note = links.notes.read(type.startsWith("foot") ? "footnote" : "endnote",
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
    if (ContentControl.is(field) && style.revision == null) {
      DocxControls.write(b, field, style, links);
      return;
    }
    if (style.revision != null) {
      RevisionFormats.start(b, style.revision);
      b.append("<w:r><w:fldChar w:fldCharType=\"begin\" w:fldLock=\"")
          .append(field.locked ? 1 : 0)
          .append("\"/></w:r>");
      String instruction = style.revision.inserted() ? "instrText" : "delInstrText";
      b.append("<w:r><w:")
          .append(instruction)
          .append(" xml:space=\"preserve\">")
          .append(Xml.escape(field.instruction))
          .append("</w:")
          .append(instruction)
          .append("></w:r>");
      b.append("<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>");
      Docx.writeRun(b, field.result, style.revision(null), links, !style.revision.inserted());
      b.append("<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>");
      RevisionFormats.end(b, style.revision);
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
