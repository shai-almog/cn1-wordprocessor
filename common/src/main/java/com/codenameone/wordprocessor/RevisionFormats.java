package com.codenameone.wordprocessor;

import com.codename1.xml.Element;

final class RevisionFormats {
  private RevisionFormats() {}

  static void attributes(StringBuilder b, Revision r, String prefix) {
    if (r == null) return;
    attributes(b, r.previous, prefix + "prior-");
    b.append(" ")
        .append(prefix)
        .append("id=\"")
        .append(Xml.escape(r.id))
        .append("\" ")
        .append(prefix)
        .append("kind=\"")
        .append(r.kind)
        .append("\" ")
        .append(prefix)
        .append("author=\"")
        .append(Xml.escape(r.author))
        .append("\" ")
        .append(prefix)
        .append("date=\"")
        .append(Xml.escape(r.date))
        .append("\"");
  }

  static Revision nativeRead(Element e, String prefix) {
    String id = Xml.attr(e, prefix + "id", "");
    return id.isEmpty()
        ? null
        : new Revision(
                id,
                Xml.attr(e, prefix + "kind", ""),
                Xml.attr(e, prefix + "author", ""),
                Xml.attr(e, prefix + "date", ""))
            .previous(nativeRead(e, prefix + "prior-"));
  }

  static Revision docxRead(Element e) {
    return new Revision(
        Xml.attr(e, "id", "0"),
        "ins".equals(Xml.local(e)) ? "insert" : "delete",
        Xml.attr(e, "author", ""),
        Xml.attr(e, "date", ""));
  }

  static void start(StringBuilder b, Revision r) {
    if (r != null && r.previous != null) start(b, r.previous);
    if (r != null)
      b.append(r.inserted() ? "<w:ins w:id=\"" : "<w:del w:id=\"")
          .append(Xml.escape(r.id))
          .append("\" w:author=\"")
          .append(Xml.escape(r.author))
          .append("\" w:date=\"")
          .append(Xml.escape(r.date))
          .append("\">");
  }

  static void end(StringBuilder b, Revision r) {
    if (r != null) {
      b.append(r.inserted() ? "</w:ins>" : "</w:del>");
      if (r.previous != null) end(b, r.previous);
    }
  }

  static void styleAttributes(StringBuilder b, TextStyle s, String prefix) {
    String[] names = {
      "font",
      "size",
      "color",
      "background",
      "bold",
      "italic",
      "underline",
      "strike",
      "link",
      "script"
    };
    String[] values = {
      s.font,
      String.valueOf(s.size),
      Integer.toHexString(s.color),
      Integer.toHexString(s.background),
      String.valueOf(s.bold),
      String.valueOf(s.italic),
      String.valueOf(s.underline),
      String.valueOf(s.strike),
      s.link,
      s.script
    };
    characterAttributes(b, s, prefix);
    for (int i = 0; i < names.length; i++)
      b.append(" ")
          .append(prefix)
          .append(names[i])
          .append("=\"")
          .append(Xml.escape(values[i]))
          .append("\"");
  }

  static TextStyle readStyle(Element e, String prefix) {
    return readCharacters(
        e,
        prefix,
        new TextStyle(
            Xml.attr(e, prefix + "font", "Serif"),
            Float.parseFloat(Xml.attr(e, prefix + "size", "12")),
            Integer.parseInt(Xml.attr(e, prefix + "color", "202833"), 16),
            Boolean.parseBoolean(Xml.attr(e, prefix + "bold", "false")),
            Boolean.parseBoolean(Xml.attr(e, prefix + "italic", "false")),
            Boolean.parseBoolean(Xml.attr(e, prefix + "underline", "false")),
            Boolean.parseBoolean(Xml.attr(e, prefix + "strike", "false")),
            Integer.parseInt(Xml.attr(e, prefix + "background", "ffffff"), 16),
            Xml.attr(e, prefix + "link", ""),
            Xml.attr(e, prefix + "script", "baseline")));
  }

  static void characterAttributes(StringBuilder b, TextStyle s, String prefix) {
    for (String key : CharacterFormat.KEYS)
      b.append(" ")
          .append(prefix)
          .append(key)
          .append("=\"")
          .append(s.characters.value(key))
          .append("\"");
  }

  static TextStyle readCharacters(Element e, String prefix, TextStyle style) {
    for (String key : CharacterFormat.KEYS)
      style = style.with(key, Xml.attr(e, prefix + key, style.characters.value(key)));
    return style;
  }

  static void formatStart(StringBuilder b, Revision r) {
    b.append("<w:rPrChange w:id=\"")
        .append(Xml.escape(r.id))
        .append("\" w:author=\"")
        .append(Xml.escape(r.author))
        .append("\" w:date=\"")
        .append(Xml.escape(r.date))
        .append("\">");
  }

  static Document reserveIds(Document d) {
    return d.blocks(d.blocks, Revisions.nextId(d));
  }
}
