package com.codenameone.wordprocessor;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paragraph direction and dialogs, section layout options (vertical alignment, direction, text
 * flow, document grid, page setup, hyphenation) and the Design tab: themes, theme colors and
 * fonts, style sets, paragraph spacing and effects. Design commands restyle the built-in heading,
 * title and body styles in one undo step and record the chosen names as document settings.
 */
final class LayoutDesignCommands implements Commands.Extension {
  /** name, heading color, title color, heading font, body font. */
  static final String[][] THEMES = {
    {"Office", "2f5496", "1f3763", "Calibri Light", "Calibri"},
    {"Facet", "5b8a1f", "3c5c14", "Trebuchet MS", "Trebuchet MS"},
    {"Integral", "1b8ba8", "125d70", "Tw Cen MT Condensed", "Tw Cen MT"},
    {"Ion", "1a6b8f", "11475f", "Century Gothic", "Century Gothic"},
    {"Retrospect", "c0640d", "804209", "Calibri Light", "Calibri"},
    {"Slice", "0d3a6b", "092747", "Century Gothic", "Century Gothic"},
    {"Wisp", "8b2a0d", "5c1c09", "Century Gothic", "Century Gothic"},
    {"Organic", "6b8a3c", "475c28", "Garamond", "Garamond"},
    {"Basis", "a6402c", "6e2a1d", "Corbel", "Corbel"}
  };

  /** name, heading color, title color. */
  static final String[][] COLORS = {
    {"Office", "2f5496", "1f3763"},
    {"Grayscale", "404040", "262626"},
    {"Blue", "1f4e79", "142f4a"},
    {"Blue II", "2e75b6", "1f4e79"},
    {"Green", "538135", "375623"},
    {"Yellow", "bf8f00", "7f6000"},
    {"Orange", "c55a11", "843c0c"},
    {"Red", "c00000", "800000"},
    {"Violet", "7030a0", "4b2069"},
    {"Paper", "8c6d46", "5d482f"}
  };

  /** name, heading font, body font. */
  static final String[][] FONTS = {
    {"Office", "Calibri Light", "Calibri"},
    {"Office 2007-2010", "Cambria", "Calibri"},
    {"Arial", "Arial", "Arial"},
    {"Times New Roman", "Times New Roman", "Times New Roman"},
    {"Georgia", "Georgia", "Georgia"},
    {"Garamond", "Garamond", "Garamond"},
    {"Century Gothic", "Century Gothic", "Century Gothic"},
    {"Trebuchet MS", "Trebuchet MS", "Trebuchet MS"},
    {"Verdana", "Verdana", "Verdana"}
  };

  static final List<String> STYLE_SETS =
      Arrays.asList(
          "Default", "Basic (Simple)", "Basic (Elegant)", "Basic (Stylish)", "Lines (Simple)",
          "Lines (Distinctive)", "Shaded", "Casual", "Centered", "Minimalist");

  /** name, space before, space after, line multiple. */
  static final Object[][] SPACING = {
    {"Default", 0f, 8f, 1.08f},
    {"No Paragraph Space", 0f, 0f, 1f},
    {"Compact", 0f, 4f, 1f},
    {"Tight", 0f, 6f, 1f},
    {"Open", 0f, 10f, 1.15f},
    {"Relaxed", 0f, 6f, 1.5f},
    {"Double", 0f, 8f, 2f}
  };

  static final List<String> EFFECTS =
      Arrays.asList("Office", "Subtle Solids", "Banded Edge", "Smoky Glass", "Glow Edge", "Grunge Texture", "Frosted Glass", "Top Shadow", "Inset", "Milk Glass", "Riblet", "Reflection");

  static final List<String> HEADINGS =
      Arrays.asList("Heading1", "Heading2", "Heading3", "Heading4", "Heading5", "Heading6", "Title", "Subtitle");

  public void register(Commands c) {
    c.edit("para.rtl", "",
        "Make the selected paragraphs right-to-left (mirrors alignment and indents)",
        (s, a) -> direction(s, true));
    c.edit("para.ltr", "",
        "Make the selected paragraphs left-to-right", (s, a) -> direction(s, false));
    c.edit(
        "para.asianTypography", "kinsoku?:boolean,wordWrap?:boolean,overflowPunct?:boolean,autoSpaceDE?:boolean,autoSpaceDN?:boolean",
        "Asian typography: kinsoku line breaking, Latin word wrap, hanging punctuation, auto spacing",
        (s, a) -> {
          final Map<String, Object> args = a;
          s.mapParagraphs(
              p -> {
                var off = new StringBuilder();
                for (String option : ParagraphFormat.ASIAN) {
                  boolean on =
                      args.containsKey(option)
                          ? Boolean.TRUE.equals(args.get(option))
                          : p.format.asian(option);
                  if (!on) off.append(off.length() == 0 ? "" : ",").append(option);
                }
                return p.with("asianOff", off.toString());
              });
          var p = s.document().paragraph(s.selection().block);
          var out = new LinkedHashMap<String, Object>();
          for (String option : ParagraphFormat.ASIAN) out.put(option, p.format.asian(option));
          return out;
        });
    c.register(
        "para.dialog",
        "Paragraph dialog: set several paragraph properties at once and return them",
        """
        align?:string,left?:number,right?:number,firstLine?:number,before?:number,after?:number,\
        lineSpacing?:number,lineRule?:string,outlineLevel?:number,keepNext?:boolean,\
        keepLines?:boolean,widowControl?:boolean,pageBreak?:boolean""",
        true,
        (s, a) -> {
          String[][] keys = {
            {"align", "align"}, {"left", "indent"}, {"right", "rightIndent"},
            {"firstLine", "firstIndent"}, {"before", "before"}, {"after", "after"},
            {"lineSpacing", "spacing"}, {"lineRule", "lineRule"}, {"outlineLevel", "outlineLevel"},
            {"keepNext", "keepNext"}, {"keepLines", "keepLines"},
            {"widowControl", "widowControl"}, {"pageBreak", "pageBreak"}
          };
          for (String[] k : keys)
            if (a.containsKey(k[0])) {
              var v = a.get(k[0]);
              String value =
                  v instanceof Number n
                      ? ("outlineLevel".equals(k[0])
                          ? String.valueOf(n.intValue())
                          : String.valueOf(n.floatValue()))
                      : String.valueOf(v);
              if ("align".equals(k[0])) {
                if ("justify".equals(value)) value = "both";
                if (!Arrays.asList("left", "center", "right", "both", "distribute").contains(value))
                  throw new IllegalArgumentException("Unknown alignment " + value);
              }
              s.paragraph(k[1], value);
            }
          var p = s.document().paragraph(s.selection().block);
          var out = new LinkedHashMap<String, Object>();
          out.put("align", p.align);
          out.put("left", p.indent);
          out.put("right", p.format.rightIndent);
          out.put("firstLine", p.format.firstIndent);
          out.put("before", p.before);
          out.put("after", p.after);
          out.put("lineSpacing", p.lineSpacing);
          out.put("lineRule", p.format.lineRule);
          out.put("outlineLevel", p.format.outlineLevel);
          out.put("keepNext", p.keepNext);
          out.put("keepLines", p.format.keepLines);
          out.put("widowControl", p.format.widowControl);
          out.put("pageBreak", p.pageBreak);
          out.put("rtl", p.format.rtl);
          return out;
        });
    c.edit("layout.sectionRtl", "value?:boolean",
        "Right-to-left section: columns and gutter run right to left",
        (s, a) -> sectionLayout(s, "rtl", String.valueOf(!a.containsKey("value") || Boolean.TRUE.equals(a.get("value")))));
    c.edit("layout.verticalAlign", "value:string",
        "Vertical page alignment of the section: top, center, both or bottom",
        (s, a) -> sectionLayout(s, "verticalAlign", Commands.str(a, "value")));
    c.edit("layout.textDirection", "value:string",
        "Section text flow: lrTb (horizontal), tbRl (vertical) or btLr",
        (s, a) -> sectionLayout(s, "textDirection", Commands.str(a, "value")));
    c.edit("layout.documentGrid", "type:string,linePitch?:number,charPitch?:number",
        "Document grid: default, lines, linesAndChars or snapToChars, with line/character pitch in points",
        (s, a) -> {
          var r = s.activeSection().raw;
          var d = r.decor.withLayout("grid", Commands.str(a, "type"));
          d = d.withLayout("gridPitch", a.containsKey("linePitch") ? num(a, "linePitch") : d.layout("gridPitch", ""));
          d = d.withLayout("charPitch", a.containsKey("charPitch") ? num(a, "charPitch") : d.layout("charPitch", ""));
          if ("default".equals(d.layout("grid", "default"))) d = d.withLayout("gridPitch", "").withLayout("charPitch", "");
          s.section(r.decor(d));
          return d.layout();
        });
    c.edit(
        "layout.pageSetup", "width?:number,height?:number,orientation?:string,top?:number,right?:number,bottom?:number,left?:number,verticalAlign?:string",
        "Page setup dialog: size, orientation and margins in points for the current section",
        (s, a) -> {
          var r = s.activeSection().raw;
          var p = r.page;
          float w = f(a, "width", p.width), h = f(a, "height", p.height);
          if (a.containsKey("orientation")) {
            String o = Commands.str(a, "orientation");
            if (!"portrait".equals(o) && !"landscape".equals(o))
              throw new IllegalArgumentException("Orientation is portrait or landscape");
            if ("landscape".equals(o) == (w < h)) {
              float t = w;
              w = h;
              h = t;
            }
          }
          float top = f(a, "top", p.top), right = f(a, "right", p.right);
          float bottom = f(a, "bottom", p.bottom), left = f(a, "left", p.left);
          if (w < 72 || h < 72 || w > 1584 || h > 1584)
            throw new IllegalArgumentException("Page size must be 1 to 22 inches");
          if (top < 0 || right < 0 || bottom < 0 || left < 0 || left + right > w - 36 || top + bottom > h - 36)
            throw new IllegalArgumentException("Margins leave no room for text");
          var next = r.page(new PageSpec(w, h, top, right, bottom, left));
          if (a.containsKey("verticalAlign"))
            next = next.decor(next.decor.withLayout("verticalAlign", Commands.str(a, "verticalAlign")));
          s.section(next);
          var q = s.activeSection().raw.page;
          var out = new LinkedHashMap<String, Object>();
          out.put("width", q.width);
          out.put("height", q.height);
          out.put("orientation", q.width > q.height ? "landscape" : "portrait");
          out.put("top", q.top);
          out.put("right", q.right);
          out.put("bottom", q.bottom);
          out.put("left", q.left);
          out.put("verticalAlign", s.activeSection().raw.decor.layout("verticalAlign", "top"));
          return out;
        });
    c.edit("layout.hyphenation", "mode:string,zone?:number,limit?:number,caps?:boolean",
        "Hyphenation: none, automatic or manual, with hyphenation zone (pt), consecutive-hyphen limit and caps",
        (s, a) -> {
          String mode = Commands.str(a, "mode");
          if (!Arrays.asList("none", "automatic", "manual").contains(mode))
            throw new IllegalArgumentException("Hyphenation is none, automatic or manual");
          var p = s.document().properties.withSetting("hyphenation", "none".equals(mode) ? "" : mode);
          if (a.containsKey("zone")) p = p.withSetting("hyphenation.zone", num(a, "zone"));
          if (a.containsKey("limit")) p = p.withSetting("hyphenation.limit", String.valueOf(((Number) a.get("limit")).intValue()));
          if (a.containsKey("caps")) p = p.withSetting("hyphenation.caps", Boolean.TRUE.equals(a.get("caps")) ? "" : "false");
          s.documentProperties(p);
          return p.setting("hyphenation", "none");
        });

    c.edit("design.theme", "name:string",
        "Apply a document theme (colors and fonts of headings, title and body)", (s, a) -> {
          String[] t = find(THEMES, Commands.str(a, "name"));
          restyle(s, t[1], t[2], t[3], t[4]);
          settings(s, "design.theme", t[0], "design.colors", t[0], "design.fonts", t[0]);
          return t[0];
        });
    c.edit("design.themeColors", "name:string",
        "Apply a theme color set to headings and title", (s, a) -> {
          String[] t = find(COLORS, Commands.str(a, "name"));
          restyle(s, t[1], t[2], null, null);
          settings(s, "design.colors", t[0]);
          return t[0];
        });
    c.edit("design.themeFonts", "name:string",
        "Apply a theme font pair (headings, body)", (s, a) -> {
          String[] t = find(FONTS, Commands.str(a, "name"));
          restyle(s, null, null, t[1], t[2]);
          settings(s, "design.fonts", t[0]);
          return t[0];
        });
    c.edit("design.styleSet", "name:string",
        "Apply a style set to the heading and title styles", (s, a) -> {
          String name = Commands.str(a, "name");
          if (!STYLE_SETS.contains(name)) throw new IllegalArgumentException("Unknown style set: " + name);
          styleSet(s, name);
          settings(s, "design.styleSet", name);
          return name;
        });
    c.edit("design.paragraphSpacing", "name:string",
        "Paragraph spacing preset for the Normal style", (s, a) -> {
          String name = Commands.str(a, "name");
          for (Object[] p : SPACING)
            if (p[0].equals(name)) {
              var n = s.document().styles.get("Normal");
              var q =
                  n.paragraph
                      .with("before", String.valueOf(p[1]))
                      .with("after", String.valueOf(p[2]))
                      .with("spacing", String.valueOf(p[3]))
                      .with("lineRule", "auto");
              s.defineStyle(new ParagraphStyle(n.id, n.name, q, n.text));
              settings(s, "design.spacing", name);
              return name;
            }
          throw new IllegalArgumentException("Unknown paragraph spacing: " + name);
        });
    c.edit("design.effects", "name:string",
        "Theme effects: the shadow/glow look applied to shapes, text boxes, SmartArt and charts",
        (s, a) -> {
          String name = Commands.str(a, "name");
          if (!EFFECTS.contains(name)) throw new IllegalArgumentException("Unknown effects: " + name);
          boolean shadow = !Arrays.asList("Office", "Subtle Solids", "Banded Edge", "Riblet", "Glow Edge").contains(name);
          String glow = name.contains("Glow") || name.contains("Glass") ? "c9d9f0" : "";
          var d = s.document();
          Sequence<Block> blocks = d.blocks;
          for (int i = 0; i < blocks.size(); i++)
            if (blocks.get(i) instanceof Picture) {
              var pic = (Picture) blocks.get(i);
              if (pic.drawing == null
                  || Arrays.asList("ink", "equation", "signature").contains(pic.drawing.kind)) continue;
              blocks =
                  blocks.set(
                      i, pic.drawing(pic.drawing.with("shadow", shadow ? "true" : "").with("glow", glow)));
            }
          s.updateBlocks(blocks);
          s.documentProperties(s.document().properties.withSetting("design.effects", name));
          return name;
        });
    c.query("design.setDefault", "",
        "Remember the current theme, colors, fonts, style set and spacing as the default for new documents",
        (s, a) -> {
          var p = s.document().properties;
          var b = new StringBuilder();
          for (String k : new String[] {"design.theme", "design.colors", "design.fonts", "design.styleSet", "design.spacing"})
            b.append(k).append('=').append(p.setting(k, "")).append('\n');
          defaults = b.toString();
          return defaults;
        });
  }

  /** Last design.setDefault snapshot ("key=value" lines); applied by {@link #applyDefaults}. */
  static volatile String defaults = "";

  /** Re-applies the remembered design defaults to a session (used for new documents). */
  static void applyDefaults(Commands c, Session s) {
    for (String line : TextUtil.split(defaults, '\n')) {
      int eq = line.indexOf('=');
      if (eq < 0 || eq == line.length() - 1) continue;
      String key = line.substring(0, eq), value = line.substring(eq + 1);
      String id =
          "design.theme".equals(key) ? "design.theme"
              : "design.colors".equals(key) ? "design.themeColors"
              : "design.fonts".equals(key) ? "design.themeFonts"
              : "design.styleSet".equals(key) ? "design.styleSet" : "design.paragraphSpacing";
      var args = new LinkedHashMap<String, Object>();
      args.put("name", value);
      c.execute(s, id, args);
    }
  }

  private static Object direction(Session s, final boolean rtl) {
    s.mapParagraphs(
        p -> {
          if (p.format.rtl == rtl) return p;
          String align = "left".equals(p.align) ? "right" : "right".equals(p.align) ? "left" : p.align;
          float left = p.indent, right = p.format.rightIndent;
          return p.with("rtl", String.valueOf(rtl))
              .with("align", align)
              .with("indent", String.valueOf(right))
              .with("rightIndent", String.valueOf(left));
        });
    return null;
  }

  private static Object sectionLayout(Session s, String key, String value) {
    var r = s.activeSection().raw;
    s.section(r.decor(r.decor.withLayout(key, value)));
    return s.activeSection().raw.decor.layout();
  }

  private static String[] find(String[][] table, String name) {
    for (String[] row : table) if (row[0].equalsIgnoreCase(name)) return row;
    var b = new StringBuilder();
    for (String[] row : table) b.append(b.length() == 0 ? "" : ", ").append(row[0]);
    throw new IllegalArgumentException("Unknown name " + name + "; choose " + b);
  }

  /** Recolors/refonts headings and title; the body font goes to Normal (null leaves a part). */
  private static void restyle(Session s, String heading, String title, String headFont, String bodyFont) {
    Map<String, ParagraphStyle> styles = s.document().styles;
    for (String id : HEADINGS) {
      var st = styles.get(id);
      if (st == null) continue;
      var t = st.text;
      boolean isTitle = "Title".equals(id);
      if (heading != null && !"Subtitle".equals(id)) t = t.with("color", isTitle ? title : heading);
      if (headFont != null) t = t.with("font", "Subtitle".equals(id) && bodyFont != null ? bodyFont : headFont);
      if (!t.equals(st.text)) s.defineStyle(new ParagraphStyle(st.id, st.name, st.paragraph, t));
    }
    if (bodyFont != null)
      for (String id : new String[] {"Normal", "NoSpacing", "Caption"}) {
        var st = styles.get(id);
        if (st != null && !bodyFont.equals(st.text.font))
          s.defineStyle(new ParagraphStyle(st.id, st.name, st.paragraph, st.text.with("font", bodyFont)));
      }
  }

  private static void styleSet(Session s, String name) {
    Map<String, ParagraphStyle> base = ParagraphStyle.defaults();
    Map<String, ParagraphStyle> styles = s.document().styles;
    for (String id : HEADINGS) {
      ParagraphStyle st = styles.get(id), def = base.get(id);
      if (st == null || def == null) continue;
      // Keep the theme's color and font; reset shape properties from the built-in definition.
      var t =
          def.text.with("color", TextUtil.padded(st.text.color, 16, 6)).with("font", st.text.font);
      var p = def.paragraph;
      String color = TextUtil.padded(st.text.color, 16, 6);
      boolean heading = id.startsWith("Heading");
      if ("Basic (Elegant)".equals(name)) t = t.with("allCaps", "true").with("characterSpacing", "1");
      else if ("Basic (Stylish)".equals(name)) t = t.with("bold", "false").with("italic", heading ? "true" : "false");
      else if ("Basic (Simple)".equals(name)) t = t.with("bold", "true");
      else if ("Lines (Simple)".equals(name) && heading)
        p = p.with("borders", "bottom=.5," + color + ",1,single");
      else if ("Lines (Distinctive)".equals(name) && heading)
        p = p.with("borders", "top=1.5," + color + ",1,single;bottom=.5," + color + ",1,single");
      else if ("Shaded".equals(name) && heading) {
        p = p.with("shading", color);
        t = t.with("color", "ffffff");
      } else if ("Casual".equals(name)) t = t.with("italic", "true");
      else if ("Centered".equals(name)) p = p.with("align", "center");
      else if ("Minimalist".equals(name)) t = t.with("bold", "false").with("size", String.valueOf(Math.max(11, def.text.size - 2)));
      s.defineStyle(new ParagraphStyle(st.id, st.name, p, t));
    }
  }

  private static void settings(Session s, String... kv) {
    var p = s.document().properties;
    for (int i = 0; i < kv.length; i += 2) p = p.withSetting(kv[i], kv[i + 1]);
    s.documentProperties(p);
  }

  private static float f(Map<String, Object> a, String k, float fallback) {
    return a.containsKey(k) ? ((Number) a.get(k)).floatValue() : fallback;
  }

  private static String num(Map<String, Object> a, String k) {
    return String.valueOf(((Number) a.get(k)).floatValue());
  }
}
