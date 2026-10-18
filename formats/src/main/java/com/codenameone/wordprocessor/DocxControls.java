package com.codenameone.wordprocessor;

import com.codename1.xml.Element;

/** Run-level content controls ({@code w:sdt}) to and from {@link ContentControl} fields. */
final class DocxControls {
  private static final String W14 = "http://schemas.microsoft.com/office/word/2010/wordml";
  private static final String W15 = "http://schemas.microsoft.com/office/word/2012/wordml";

  private DocxControls() {}

  static void write(StringBuilder b, InlineField field, TextStyle style, DocxLinks links) {
    ContentControl c = ContentControl.parse(field.instruction);
    b.append("<w:sdt><w:sdtPr>");
    if (!c.get("title", "").isEmpty()) b.append("<w:alias w:val=\"").append(Xml.attribute(c.get("title", ""))).append("\"/>");
    if (!c.get("tag", "").isEmpty()) b.append("<w:tag w:val=\"").append(Xml.attribute(c.get("tag", ""))).append("\"/>");
    var lock = c.get("lock", "none");
    if (!"none".equals(lock)) b.append("<w:lock w:val=\"").append(lock).append("\"/>");
    if (field.result.equals(c.placeholder())) b.append("<w:showingPlcHdr/>");
    var t = c.type;
    if ("plainText".equals(t)) b.append("<w:text/>");
    else if ("picture".equals(t)) b.append("<w:picture/>");
    else if ("buildingBlock".equals(t))
      b.append("<w:docPartObj>")
          .append(c.get("gallery", "").isEmpty() ? "" : "<w:docPartGallery w:val=\"" + Xml.attribute(c.get("gallery", "")) + "\"/>")
          .append("</w:docPartObj>");
    else if ("checkBox".equals(t))
      b.append("<w14:checkbox xmlns:w14=\"").append(W14).append("\"><w14:checked w14:val=\"")
          .append("true".equals(c.get("checked", "false")) ? 1 : 0)
          .append("\"/><w14:checkedState w14:val=\"2612\" w14:font=\"MS Gothic\"/><w14:uncheckedState w14:val=\"2610\" w14:font=\"MS Gothic\"/></w14:checkbox>");
    else if ("comboBox".equals(t) || "dropDown".equals(t)) {
      String tag = "comboBox".equals(t) ? "comboBox" : "dropDownList";
      b.append("<w:").append(tag).append('>');
      for (String item : c.items())
        b.append("<w:listItem w:displayText=\"").append(Xml.attribute(item)).append("\" w:value=\"")
            .append(Xml.attribute(item)).append("\"/>");
      b.append("</w:").append(tag).append('>');
    } else if ("datePicker".equals(t)) {
      b.append("<w:date");
      if (!c.get("date", "").isEmpty()) b.append(" w:fullDate=\"").append(Xml.attribute(c.get("date", ""))).append("T00:00:00Z\"");
      b.append("><w:dateFormat w:val=\"").append(Xml.attribute(c.get("format", "M/d/yyyy")))
          .append("\"/><w:lid w:val=\"en-US\"/></w:date>");
    } else if ("repeatingSection".equals(t))
      b.append("<w15:repeatingSection xmlns:w15=\"").append(W15).append("\"/>");
    else b.append("<w:richText/>");
    b.append("</w:sdtPr><w:sdtContent>");
    Docx.writeRun(b, field.result, style, links);
    b.append("</w:sdtContent></w:sdt>");
  }

  /** The control described by {@code sdtPr}, or null for unsupported kinds (their content is kept). */
  static ContentControl read(Element pr) {
    var type = "richText";
    ContentControl c = null;
    for (Element e : Xml.children(pr, null)) {
      String k = Xml.local(e);
      if ("text".equals(k)) type = "plainText";
      else if ("picture".equals(k)) type = "picture";
      else if ("docPartObj".equals(k)) type = "buildingBlock";
      else if ("checkbox".equals(k)) type = "checkBox";
      else if ("comboBox".equals(k)) type = "comboBox";
      else if ("dropDownList".equals(k)) type = "dropDown";
      else if ("date".equals(k)) type = "datePicker";
      else if ("repeatingSection".equals(k)) type = "repeatingSection";
      else if ("citation".equals(k) || "bibliography".equals(k) || "equation".equals(k)) return null;
    }
    c = ContentControl.of(type);
    for (Element e : Xml.children(pr, null)) {
      String k = Xml.local(e);
      if ("alias".equals(k)) c = c.with("title", Xml.attr(e, "val", ""));
      else if ("tag".equals(k)) c = c.with("tag", Xml.attr(e, "val", ""));
      else if ("lock".equals(k) && ContentControl.LOCKS.contains(Xml.attr(e, "val", ""))) c = c.with("lock", Xml.attr(e, "val", ""));
      else if ("checkbox".equals(k)) {
        Element checked = Xml.child(e, "checked");
        String v = checked == null ? "0" : Xml.attr(checked, "val", "0");
        c = c.with("checked", String.valueOf("1".equals(v) || "true".equals(v)));
      } else if ("comboBox".equals(k) || "dropDownList".equals(k)) {
        var items = new StringBuilder();
        for (Element item : Xml.children(e, null))
          if ("listItem".equals(Xml.local(item))) {
            if (items.length() > 0) items.append('|');
            items.append(Xml.attr(item, "displayText", Xml.attr(item, "value", "")).replace('|', '/'));
          }
        c = c.with("items", items.toString());
      } else if ("date".equals(k)) {
        String full = Xml.attr(e, "fullDate", "");
        if (full.length() >= 10) c = c.with("date", full.substring(0, 10));
        Element format = Xml.child(e, "dateFormat");
        if (format != null) c = c.with("format", Xml.attr(format, "val", ""));
      } else if ("docPartObj".equals(k)) {
        Element gallery = Xml.child(e, "docPartGallery");
        if (gallery != null) c = c.with("gallery", Xml.attr(gallery, "val", ""));
      }
    }
    return c;
  }
}
