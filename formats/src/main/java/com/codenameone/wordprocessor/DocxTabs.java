package com.codenameone.wordprocessor;

import java.util.*;

/** Materializes resolved tabs and explicitly clears stops inherited from exported styles. */
final class DocxTabs {
  final float interval;
  private final Map<String, ParagraphStyle> styles;

  DocxTabs(Document document) {
    styles = document.styles;
    float chosen = 36;
    boolean first = true;
    for (Paragraph paragraph : paragraphs(document)) {
      if (first) {
        chosen = paragraph.format.tabInterval;
        first = false;
      }
      boolean bar = false;
      for (TabStop stop : paragraph.format.tabs) bar |= "bar".equals(stop.alignment);
      if (bar) {
        chosen = paragraph.format.tabInterval;
        break;
      }
    }
    interval = chosen;
  }

  private DocxTabs(float interval) {
    this.interval = interval;
    styles = Collections.<String, ParagraphStyle>emptyMap();
  }

  DocxTabs withoutStyles() {
    return new DocxTabs(interval);
  }

  static List<Paragraph> paragraphs(Document document) {
    var result = new ArrayList<Paragraph>();
    for (Block block : document.blocks)
      if (block instanceof Paragraph paragraph) result.add(paragraph);
      else if (block instanceof Table table) {
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++)
            for (Paragraph p : table.cell(r, c)) result.add(p);
      }
    for (Section.Range section : document.sectionRanges())
      for (String key : PageFurniture.KEYS)
        for (Paragraph p : section.raw.furniture.get(key)) result.add(p);
    for (Note note : Notes.all(document)) for (Paragraph p : note.paragraphs) result.add(p);
    return result;
  }

  void settings(com.codename1.xml.Element settings) throws java.io.IOException {
    for (int i = settings.getNumChildren() - 1; i >= 0; i--)
      if (!settings.getChildAt(i).isTextElement()
          && "defaultTabStop".equals(Xml.local(settings.getChildAt(i)))) settings.removeChildAt(i);
    settings.addChild(
        Xml.parse(
            "<w:defaultTabStop"
                + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                + " w:val=\""
                + Math.round(interval * 20)
                + "\"/>"));
  }

  private List<TabStop> stops(Paragraph p) {
    var result = new ArrayList<TabStop>(p.format.tabs);
    if (p.format.tabInterval != interval) {
      float last = 0;
      for (TabStop stop : result)
        if (!"bar".equals(stop.alignment)) last = Math.max(last, stop.position);
      // OOXML has no paragraph-specific default interval. Preserve the existing bounded fallback.
      for (int i = 1; result.size() < 64 && i * p.format.tabInterval <= 10000; i++) {
        float position = i * p.format.tabInterval;
        if (position <= last) continue;
        boolean occupied = false;
        for (TabStop stop : result) occupied |= Math.abs(stop.position - position) < .01f;
        if (!occupied) result.add(new TabStop(position, "left", "none"));
      }
    }
    return TabStop.normalized(result);
  }

  void write(StringBuilder out, Paragraph paragraph) {
    var current = stops(paragraph);
    var style = styles.get(paragraph.style);
    List<TabStop> inherited =
        style == null ? Collections.<TabStop>emptyList() : stops(style.paragraph);
    if (current.isEmpty() && inherited.isEmpty()) return;
    out.append("<w:tabs>");
    // Tabs are additive in OOXML: an empty list alone does not remove an inherited stop.
    for (TabStop stop : inherited) {
      boolean retained = false;
      for (TabStop value : current)
        retained |= Math.round(value.position * 20) == Math.round(stop.position * 20);
      if (!retained)
        out.append("<w:tab w:val=\"clear\" w:pos=\"")
            .append(Math.round(stop.position * 20))
            .append("\"/>");
    }
    for (TabStop stop : current)
      out.append("<w:tab w:val=\"")
          .append(stop.alignment)
          .append("\" w:leader=\"")
          .append(stop.leader)
          .append("\" w:pos=\"")
          .append(Math.round(stop.position * 20))
          .append("\"/>");
    out.append("</w:tabs>");
  }
}
