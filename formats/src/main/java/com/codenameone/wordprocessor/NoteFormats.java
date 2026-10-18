package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.util.*;

/** Sparse note settings shared by native XML and the standard DOCX property elements. */
final class NoteFormats {
  private NoteFormats() {}
  static void write(StringBuilder out, NoteOptions options, String prefix) {
    for (String kind : new String[] {"footnote", "endnote"}) {
      var p = options.get(kind);
      if (p.isEmpty()) continue;
      out.append('<').append(prefix).append(kind).append("Pr>");
      property(out, prefix, "pos", p.pos);
      property(out, prefix, "numFmt", p.numFmt);
      if (p.numStart > 0) property(out, prefix, "numStart", "" + p.numStart);
      property(out, prefix, "numRestart", p.numRestart);
      out.append("</").append(prefix).append(kind).append("Pr>");
    }
  }
  private static void property(StringBuilder out, String prefix, String name, String value) {
    if (value != null) out.append('<').append(prefix).append(name).append(' ').append(prefix)
        .append("val=\"").append(Xml.escape(value)).append("\"/>");
  }
  static NoteOptions read(Element parent, Set<String> warnings) {
    var result = NoteOptions.EMPTY;
    if (parent == null) return result;
    for (String kind : new String[] {"footnote", "endnote"}) {
      Element properties = Xml.child(parent, kind + "Pr");
      if (properties == null) continue;
      var value = NoteOptions.INHERIT;
      for (Element e : Xml.children(properties, null)) {
        String name = Xml.local(e), v = Xml.attr(e, "val", "");
        if (kind.equals(name)) continue; // DOCX separator references.
        try {
          NoteOptions.Properties change;
          if ("numFmt".equals(name)) change = new NoteOptions.Properties(v, 0, null, null);
          else if ("numStart".equals(name)) {
            int start = Integer.parseInt(v);
            if (start < 1) throw new IllegalArgumentException();
            change = new NoteOptions.Properties(null, start, null, null);
          } else if ("numRestart".equals(name)) change = new NoteOptions.Properties(null, 0, v, null);
          else if ("pos".equals(name)) change = new NoteOptions.Properties(null, 0, null, v);
          else throw new IllegalArgumentException();
          result.with(kind, change); // Validate kind-specific placement and restart.
          value = change.resolve(value);
        } catch (IllegalArgumentException ex) {
          if (warnings == null) throw new IllegalArgumentException("Invalid note setting " + kind + " " + name + "=" + v, ex);
          warnings.add("Note setting: " + kind + " " + name + "=" + v);
        }
      }
      result = result.with(kind, value);
    }
    return result;
  }
  static void settings(Element root, NoteOptions options) throws java.io.IOException {
    for (String kind : new String[] {"footnote", "endnote"}) {
      Element old = Xml.child(root, kind + "Pr");
      var xml = new StringBuilder("<settings>");
      write(xml, NoteOptions.EMPTY.with(kind, options.get(kind)), "w:");
      Element replacement = Xml.child(Xml.parse(xml.append("</settings>").toString()), kind + "Pr");
      if (old != null) {
        // Keep package-specific references to separator/continuation stories.
        for (Element e : Xml.children(old, kind)) replacement.addChild(Xml.parse(Xml.write(e)));
        root.removeChildAt(root.getChildIndex(old));
      }
      root.addChild(Xml.parse(Xml.write(replacement)));
    }
  }
}
