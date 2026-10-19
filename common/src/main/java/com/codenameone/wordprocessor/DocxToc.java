package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.util.*;

/** Converts Word's multi-paragraph TOC field to the editable contents story and back. */
final class DocxToc {
  static final String START = """
      <w:sdt><w:sdtPr><w:docPartObj><w:docPartGallery w:val="Table of Contents"/>\
      <w:docPartUnique/></w:docPartObj></w:sdtPr><w:sdtContent>""";
  static final String END_FIELD = "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";

  static InlineField marker(Paragraph p) {
    for (InlineField f : p.text.fields()) if (contents(f.instruction)) return f;
    return null;
  }

  static Paragraph withoutMarker(Paragraph p) {
    if (marker(p) == null) return p;
    for (int i = p.text.length() - 1; i >= 0; i--) {
      var f = p.text.fieldAt(i);
      if (f != null && contents(f.instruction)) p = p.text(p.text.replace(i, i + 1, new RichText()));
    }
    return p;
  }

  static String begin(InlineField f) {
    return "<w:r><w:fldChar w:fldCharType=\"begin\" w:fldLock=\"" + (f.locked ? "1" : "0")
        + "\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> " + Xml.escape(f.instruction)
        + " </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>";
  }

  private static boolean contents(String instruction) {
    var code = new FieldCode(instruction);
    return "TOC".equals(code.name) || "INDEX".equals(code.name) || "TOA".equals(code.name) || "BIBLIOGRAPHY".equals(code.name);
  }

  static List<Element> body(Element body, Set<String> warnings) {
    return body(body, true, warnings);
  }

  /** {@code fields} false: the part has no complex field, so no TOC field can be lifted. */
  static List<Element> body(Element body, boolean fields, Set<String> warnings) {
    var blocks = new ArrayList<Element>();
    for (Element child : Xml.children(body, null)) {
      Element properties = Xml.child(child, "sdtPr");
      Element object = properties == null ? null : Xml.child(properties, "docPartObj");
      Element gallery = object == null ? null : Xml.child(object, "docPartGallery");
      Element content = Xml.child(child, "sdtContent");
      if ("sdt".equals(Xml.local(child)) && gallery != null && content != null
          && ("Table of Contents".equalsIgnoreCase(Xml.attr(gallery, "val", ""))
              || "Table of Figures".equalsIgnoreCase(Xml.attr(gallery, "val", ""))
              || "Indexes".equalsIgnoreCase(Xml.attr(gallery, "val", ""))
              || "Bibliographies".equalsIgnoreCase(Xml.attr(gallery, "val", ""))
              || "Table of Authorities".equalsIgnoreCase(Xml.attr(gallery, "val", "")))) {
        if (Xml.child(properties, "lock") != null) warnings.add("Contents control editing lock is not supported");
        blocks.addAll(Xml.children(content, null));
      } else blocks.add(child);
    }
    if (!fields) return blocks;
    // Only remove a complete TOC field after verifying its result paragraphs. Other fields,
    // including nested PAGEREFs, remain in the ordinary inline field reader.
    var stack = new ArrayList<Pending>();
    var complete = new ArrayList<Pending>();
    for (int index = 0; index < blocks.size(); index++) {
      var p = blocks.get(index);
      if (!"p".equals(Xml.local(p))) { stack.clear(); continue; }
      var tokens = new ArrayList<Element>();
      tokens(p, tokens);
      for (Element token : tokens) {
        String kind = Xml.local(token), action = Xml.attr(token, "fldCharType", "");
        if ("fldChar".equals(kind) && "begin".equals(action)) {
          if (stack.size() >= 64) throw new IllegalArgumentException("Field nesting exceeds 64");
          var f = new Pending(index, token);
          stack.add(f);
        } else if (!stack.isEmpty()) {
          var f = stack.get(stack.size() - 1);
          if ("instrText".equals(kind) && !f.separated) {
            f.instruction.append(Xml.text(token)); f.nodes.add(token);
          } else if ("fldChar".equals(kind) && "separate".equals(action)) {
            f.separated = true; f.nodes.add(token);
          } else if ("fldChar".equals(kind) && "end".equals(action)) {
            stack.remove(stack.size() - 1); f.nodes.add(token); f.last = index;
            if (stack.isEmpty() && f.separated && contents(f.instruction.toString())) complete.add(f);
          }
        }
      }
    }
    // Reverse order so inserting a missing title doesn't shift the remaining ranges.
    for (int index = complete.size() - 1; index >= 0; index--) {
      var f = complete.get(index);
      boolean bibliography = "BIBLIOGRAPHY".equals(new FieldCode(f.instruction.toString()).name);
      boolean authorities = "TOA".equals(new FieldCode(f.instruction.toString()).name);
      boolean indexField = "INDEX".equals(new FieldCode(f.instruction.toString()).name);
      boolean entries = true;
      for (int i = f.first; i <= f.last; i++) {
        String style = style(blocks.get(i));
        boolean headingEntry = style.length() == 4 && style.startsWith("TOC") && style.charAt(3) >= '1' && style.charAt(3) <= '9';
        boolean figureEntry = new FieldCode(f.instruction.toString()).switches.containsKey("c")
            && ("ListEntry".equals(style) || "TableofFigures".equals(style));
        if (bibliography ? !Citations.entryStyle(style) : authorities ? !DocumentIndex.authorityStyle(style) : indexField ? !DocumentIndex.entryStyle(style) : !headingEntry && !figureEntry) entries = false;
      }
      if (!entries) {
        if (f.first != f.last) warnings.add("Unsupported contents field layout; cached paragraphs retained");
        continue;
      }
      String headingStyle = bibliography ? "BibliographyHeading" : authorities ? "TOAHeading" : indexField ? "IndexHeading" : "TOCHeading";
      Element header;
      if (f.first > 0 && headingStyle.equals(style(blocks.get(f.first - 1)))) header = blocks.get(f.first - 1);
      else {
        header = new Element("w:p");
        Element props = new Element("w:pPr"), name = new Element("w:pStyle");
        name.setAttribute("w:val", headingStyle); props.addChild(name); header.addChild(props);
        blocks.add(f.first, header);
      }
      for (Element node : f.nodes) {
        var parent = node.getParent();
        for (int i = parent.getNumChildren() - 1; i >= 0; i--)
          if (parent.getChildAt(i) == node) parent.removeChildAt(i);
      }
      var marker = new Element("w:fldSimple");
      marker.setAttribute("w:instr", f.instruction.toString().trim());
      marker.setAttribute("w:fldLock", f.locked ? "1" : "0");
      header.addChild(marker);
    }
    return blocks;
  }

  private static String style(Element p) {
    Element props = Xml.child(p, "pPr"), style = props == null ? null : Xml.child(props, "pStyle");
    return style == null ? "" : Xml.attr(style, "val", "");
  }

  private static void tokens(Element node, List<Element> result) {
    for (Element child : Xml.children(node, null)) {
      String kind = Xml.local(child);
      if ("fldChar".equals(kind) || "instrText".equals(kind)) result.add(child);
      else if ("r".equals(kind) || "hyperlink".equals(kind)) tokens(child, result);
    }
  }

  private static final class Pending {
    final int first;
    int last;
    final boolean locked;
    boolean separated;
    final StringBuilder instruction = new StringBuilder();
    final List<Element> nodes = new ArrayList<>();
    Pending(int first, Element begin) {
      this.first = first;
      String lock = Xml.attr(begin, "fldLock", "0");
      locked = "1".equals(lock) || "true".equals(lock) || "on".equals(lock);
      nodes.add(begin);
    }
  }
}
