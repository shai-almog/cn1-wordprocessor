package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Insert and format vector objects: shapes, text boxes, WordArt, icons, signature lines, charts,
 * SmartArt, equations, ink, canvases and embedded-object placeholders. Every object is a {@link
 * Picture} carrying a {@link Drawing}, so selection, size, alignment, alt text, undo and save
 * behave like pictures. Commands that format an object take {@code block}; without it they use the
 * object at the caret or the one just before it.
 */
final class DrawingCommands implements Commands.Extension {
  /** Pen presets added with draw.addPen, in order. */
  private final List<String> pens = new ArrayList<>();

  public void register(final Commands c) {
    pens.add("pen,000000,1");
    pens.add("pencil,404040,1");
    pens.add("highlighter,ffff00,4");

    c.register(
        "insert.shape",
        "Insert a shape: " + DrawingOps.SHAPES,
        "shape?:string,width?:number,height?:number,text?:string,fill?:string,line?:string",
        true,
        (s, a) -> {
          String shape = Commands.str(a, "shape", "rect");
          if (!DrawingOps.SHAPES.contains(shape)) throw new IllegalArgumentException("Unknown shape: " + shape);
          var d = Drawing.of("shape").with("shape", shape);
          d = copy(a, d, "text", "fill", "line");
          boolean line = "line".equals(shape) || "arrowLine".equals(shape);
          return insert(s, d, f(a, "width", 144), f(a, "height", line ? 12 : 96), "Shape: " + shape);
        });
    c.edit("insert.textBox", "text?:string,width?:number,height?:number",
        "Insert a text box", (s, a) ->
            insert(
                s,
                Drawing.of("textBox").with("text", Commands.str(a, "text", "Type here")).with("valign", "top"),
                f(a, "width", 180),
                f(a, "height", 72),
                "Text box"));
    c.edit("insert.wordArt", "text?:string,style?:integer",
        "Insert WordArt text", (s, a) -> {
          String text = Commands.str(a, "text", "Your text here");
          Drawing d = wordArtStyle(Drawing.of("wordArt").with("text", text), Commands.integer(a, "style", 1));
          return insert(s, d, Math.min(468, Math.max(120, text.length() * 26)), 60, text);
        });
    c.register(
        "insert.icon",
        "Insert an icon: " + DrawingOps.ICONS,
        "icon:string,color?:string,size?:number",
        true,
        (s, a) -> {
          String icon = Commands.str(a, "icon");
          if (!DrawingOps.ICONS.contains(icon)) throw new IllegalArgumentException("Unknown icon: " + icon);
          var d = Drawing.of("icon").with("icon", icon);
          if (a.containsKey("color")) d = d.with("fill", Commands.str(a, "color"));
          float size = f(a, "size", 48);
          return insert(s, d, size, size, icon + " icon");
        });
    c.edit("insert.signatureLine", "signer?:string,title?:string",
        "Insert a signature line with the suggested signer and title", (s, a) -> {
          Drawing d = copy(a, Drawing.of("signature"), "signer");
          if (a.containsKey("title")) d = d.with("signerTitle", Commands.str(a, "title"));
          return insert(s, d, 216, 72, "Signature line" + (a.containsKey("signer") ? ": " + Commands.str(a, "signer") : ""));
        });
    c.edit("insert.object", "name:string,base64?:string",
        "Embed a file as an object (shown as an icon with its name)", (s, a) -> {
          String name = Commands.str(a, "name");
          if (name.isEmpty() || name.length() > 200) throw new IllegalArgumentException("Invalid object name");
          var d = Drawing.of("object").with("name", name);
          if (a.containsKey("base64")) {
            String data = Commands.str(a, "base64");
            com.codename1.util.Base64.decode(data.getBytes());
            d = d.with("data", data);
          }
          return insert(s, d, 72, 72, name);
        });
    c.edit("insert.canvas", "width?:number,height?:number",
        "Insert a drawing canvas for ink and shapes",
        (s, a) -> insert(s, Drawing.of("canvas"), f(a, "width", 432), f(a, "height", 216), "Drawing canvas"));

    // ---------------------------------------------------------------- shape formatting
    c.edit("shape.fill", "block?:integer,color?:string,transparency?:number",
        "Set the shape or text box fill color (hex or none) and transparency (0-100)", (s, a) -> {
          Drawing d = drawing(s, a);
          if (a.containsKey("color")) d = d.with("fill", color(Commands.str(a, "color")));
          if (a.containsKey("transparency"))
            d = d.with("transparency", String.valueOf(clamp(f(a, "transparency", 0), 0, 100)));
          return update(s, a, d);
        });
    c.edit("shape.outline", "block?:integer,color?:string,width?:number",
        "Set the shape outline color (hex or none) and width in points", (s, a) -> {
          Drawing d = drawing(s, a);
          if (a.containsKey("color")) d = d.with("line", color(Commands.str(a, "color")));
          if (a.containsKey("width")) d = d.with("lineWidth", String.valueOf(clamp(f(a, "width", 1), 0, 72)));
          return update(s, a, d);
        });
    c.edit("shape.effects", "block?:integer,shadow?:boolean,glow?:string",
        "Shadow and glow effects for an object", (s, a) -> {
          Drawing d = drawing(s, a);
          if (a.containsKey("shadow")) d = d.with("shadow", String.valueOf(a.get("shadow")));
          if (a.containsKey("glow")) d = d.with("glow", color(Commands.str(a, "glow")));
          return update(s, a, d);
        });
    c.edit(
        "shape.text", "block?:integer,text:string,size?:number,color?:string,bold?:boolean,align?:string",
        "Set the text inside a shape or text box", (s, a) -> {
          var d = drawing(s, a);
          if (d.story != null) {
            // A box holding a block story keeps it (Wordcraft's text box part): the text edits
            // its paragraphs and the formatting applies to their runs and alignment.
            long[] next = {s.document().nextId};
            var story = TextBoxes.retext(d.story, Commands.str(a, "text"), next);
            var out = new Sequence<Block>();
            for (Block b : story) {
              if (!(b instanceof Paragraph p)) {
                // Tables and pictures stay as they are; the formatting applies to the paragraphs.
                out = out.append(b);
                continue;
              }
              int n = p.text.length();
              if (a.containsKey("size")) p = p.text(p.text.format(0, n, "size", String.valueOf(clamp(f(a, "size", 11), 1, 400))));
              if (a.containsKey("color")) p = p.text(p.text.format(0, n, "color", color(Commands.str(a, "color"))));
              if (a.containsKey("bold")) p = p.text(p.text.format(0, n, "bold", String.valueOf(a.get("bold"))));
              if (a.containsKey("align")) p = p.with("align", oneOf(Commands.str(a, "align"), "left", "center", "right"));
              out = out.append(p);
            }
            int block = block(s, a);
            s.replacePicture(block, picture(s, block).drawing(d.story(out)), next[0]);
            return null;
          }
          d = d.with("text", Commands.str(a, "text"));
          if (a.containsKey("size")) d = d.with("fontSize", String.valueOf(clamp(f(a, "size", 11), 1, 400)));
          if (a.containsKey("color")) d = d.with("textColor", color(Commands.str(a, "color")));
          if (a.containsKey("bold")) d = d.with("bold", String.valueOf(a.get("bold")));
          if (a.containsKey("align")) d = d.with("align", oneOf(Commands.str(a, "align"), "left", "center", "right"));
          return update(s, a, d);
        });
    c.edit("shape.textDirection", "block?:integer,direction:string",
        "Text direction in a shape: horizontal, rotate90, rotate270 or stacked", (s, a) ->
            update(s, a, drawing(s, a).with("textDirection",
                oneOf(Commands.str(a, "direction"), "horizontal", "rotate90", "rotate270", "stacked"))));
    c.edit("shape.alignText", "block?:integer,align:string",
        "Vertical text alignment in a shape: top, middle or bottom",
        (s, a) -> update(s, a, drawing(s, a).with("valign", oneOf(Commands.str(a, "align"), "top", "middle", "bottom"))));
    c.edit("shape.change", "block?:integer,shape:string",
        "Change the geometry of a shape", (s, a) -> {
          String shape = Commands.str(a, "shape");
          if (!DrawingOps.SHAPES.contains(shape)) throw new IllegalArgumentException("Unknown shape: " + shape);
          return update(s, a, drawing(s, a).with("shape", shape));
        });
    c.edit("shape.link", "block?:integer,target:integer",
        "Link a text box to another so overflowing text continues there", (s, a) -> {
          int from = block(s, a), to = Commands.integer(a, "target");
          Picture source = picture(s, from), target = picture(s, to);
          if (from == to || source.drawing == null || target.drawing == null
              || !"textBox".equals(source.drawing.kind) || !"textBox".equals(target.drawing.kind))
            throw new IllegalArgumentException("Link two different text boxes");
          // As Wordcraft's link_text_box: the source keeps its story, which flows on into the
          // target (laid out by the layout engine); the target must be empty, not linked to by
          // another box and not one the text already runs through.
          var d = source.drawing;
          if (!d.get("linkNext", "").isEmpty())
            throw new IllegalArgumentException("This text box is already linked: break the link first");
          if (!target.drawing.get("text", "").trim().isEmpty())
            throw new IllegalArgumentException("The text box to link to must be empty");
          var root = s.document();
          for (java.util.List<String> chain : TextBoxes.chains(root)) {
            if (chain.indexOf(target.id()) > 0)
              throw new IllegalArgumentException("Another text box already links to that one");
            if (chain.contains(source.id()) && chain.contains(target.id()))
              throw new IllegalArgumentException("A text box can't link to a box its text already runs through");
          }
          var linked = source.drawing(d.with("linkNext", target.id()));
          s.transact(x -> x.replacePicture(from, linked));
          return Json.object("textBox", source.id(), "next", target.id());
        });

    // ---------------------------------------------------------------- WordArt
    c.edit("wordArt.style", "block?:integer,style:integer",
        "Apply a WordArt style (1-6)",
        (s, a) -> update(s, a, wordArtStyle(drawing(s, a), Commands.integer(a, "style"))));
    c.edit("wordArt.textFill", "block?:integer,color:string",
        "WordArt text fill color",
        (s, a) -> update(s, a, drawing(s, a).with("textFill", color(Commands.str(a, "color")))));
    c.edit("wordArt.textOutline", "block?:integer,color:string",
        "WordArt text outline color (or none)",
        (s, a) -> update(s, a, drawing(s, a).with("textOutline", color(Commands.str(a, "color")))));
    c.register(
        "wordArt.transform",
        "WordArt transform: " + DrawingOps.WORD_ART,
        "block?:integer,transform:string",
        true,
        (s, a) -> {
          String t = Commands.str(a, "transform");
          if (!DrawingOps.WORD_ART.contains(t)) throw new IllegalArgumentException("Unknown transform: " + t);
          return update(s, a, drawing(s, a).with("transform", t));
        });

    // ---------------------------------------------------------------- charts
    c.register(
        "insert.chart",
        "Insert a chart: " + DrawingOps.CHARTS + ", columnStacked or barStacked; data is CSV (header row, then categories)",
        "type?:string,data?:string,title?:string",
        true,
        (s, a) -> {
          String type = Commands.str(a, "type", "column");
          String grouping = grouping(type);
          type = baseType(type);
          if (!DrawingOps.CHARTS.contains(type)) throw new IllegalArgumentException("Unknown chart type: " + type);
          var d = Drawing.of("chart").with("chartType", type).with("grouping", grouping).with("data", DrawingOps.SAMPLE_CHART);
          if (a.containsKey("data")) d = d.with("data", chartData(Commands.str(a, "data")));
          if (a.containsKey("title")) d = d.with("title", Commands.str(a, "title")).with("titleSet", "true");
          return insert(s, d, 360, 216, "Chart");
        });
    c.edit("chart.type", "block?:integer,type:string",
        "Change chart type", (s, a) -> {
          String type = Commands.str(a, "type");
          String grouping = grouping(type);
          type = baseType(type);
          if (!DrawingOps.CHARTS.contains(type)) throw new IllegalArgumentException("Unknown chart type: " + type);
          return update(s, a, chart(s, a).with("chartType", type).with("grouping", grouping));
        });
    c.edit("chart.editData", "block?:integer,data?:string",
        "Read or replace chart data (CSV: header row, then one row per category)", (s, a) -> {
          Drawing d = chart(s, a);
          if (a.containsKey("data")) update(s, a, d = d.with("data", chartData(Commands.str(a, "data"))));
          return d.get("data", DrawingOps.SAMPLE_CHART);
        });
    c.edit("chart.title", "block?:integer,title:string",
        "Set or remove the chart title",
        (s, a) -> update(s, a, chart(s, a).with("title", Commands.str(a, "title")).with("titleSet", "true")));
    c.edit("chart.legend", "block?:integer,value?:boolean",
        "Show or hide the chart legend", (s, a) -> {
          Drawing d = chart(s, a);
          boolean on = Commands.flag(a, "value", "false".equals(d.get("legend", "true")));
          return update(s, a, d.with("legend", String.valueOf(on)));
        });
    c.edit("chart.dataLabels", "block?:integer,value?:boolean",
        "Show or hide data labels", (s, a) -> {
          Drawing d = chart(s, a);
          boolean on = Commands.flag(a, "value", !d.flag("dataLabels"));
          return update(s, a, d.with("dataLabels", String.valueOf(on)));
        });

    // ---------------------------------------------------------------- SmartArt
    // The same operations as Wordcraft's SmartArt Design tab and Text Pane (insert.smartArt,
    // smartArt.items/textPane/addShape/promote/demote/layout/colors/reset): items are lines whose
    // leading tabs are their level (0-7), at most 100 of them; each command is one undo step.
    c.register(
        "insert.smartArt",
        "Insert SmartArt: " + DrawingOps.SMART_ART + " (basicList is list); nodes are lines, tab-indented for levels",
        "layout?:string,nodes?:string,items?:array,colors?:string",
        true,
        (s, a) -> {
          String layout = layout(Commands.str(a, "layout", "list"));
          var d = Drawing.of("smartArt").with("layout", layout).with("nodes", "Text\nText\nText");
          if (a.containsKey("nodes")) d = d.with("nodes", nodes(Commands.str(a, "nodes")));
          if (a.containsKey("items")) d = d.with("nodes", items(a.get("items")));
          if (a.containsKey("colors")) d = d.with("colors", colors(Commands.str(a, "colors")));
          return insert(s, d, 432, 252, "SmartArt " + layout);
        });
    c.query("smartArt.get", "block?:integer",
        "The selected SmartArt graphic: layout, colors and items (text and level)", (s, a) -> {
          Drawing d = smart(s, a);
          var items = new ArrayList<Object>();
          for (String line : lines(d)) items.add(Json.object("text", line.trim(), "level", tabs(line).length()));
          return Json.object("layout", d.get("layout", "list"), "colors", d.get("colors", "accent1"), "items", items);
        });
    c.edit("smartArt.items", "block?:integer,items:array",
        "Replace the SmartArt outline: strings (leading tabs are the level) or {text, level 0-7}", (s, a) -> {
          Drawing d = smart(s, a);
          return update(s, a, d.with("nodes", items(a.get("items"))));
        });
    c.edit("smartArt.textPane", "block?:integer,nodes?:string",
        "Read or replace SmartArt text (one node per line, tab-indented for levels)", (s, a) -> {
          Drawing d = smart(s, a);
          if (a.containsKey("nodes")) update(s, a, d = d.with("nodes", nodes(Commands.str(a, "nodes"))));
          return d.get("nodes", "");
        });
    c.edit("smartArt.addShape", "block?:integer,text?:string,after?:integer,below?:boolean",
        "Add a SmartArt item after the given item and the items under it (default the last); below adds a sub-item right under it",
        (s, a) -> {
          Drawing d = smart(s, a);
          var lines = lines(d);
          if (lines.size() >= MAX_ITEMS) throw new IllegalStateException("A SmartArt graphic holds at most " + MAX_ITEMS + " items");
          String text = clean(Commands.str(a, "text", ""));
          // An empty line would not be an item in the outline text, so an empty shape shows Word's placeholder.
          if (text.isEmpty()) text = "Text";
          if (lines.isEmpty()) {
            lines.add(text);
            return update(s, a, d.with("nodes", join(lines)));
          }
          int at = Math.min(Commands.integer(a, "after", lines.size() - 1), lines.size() - 1);
          if (at < 0) throw new IllegalArgumentException("Item out of range");
          int level = tabs(lines.get(at)).length();
          if (Boolean.TRUE.equals(a.get("below"))) lines.add(at + 1, indent(Math.min(MAX_LEVEL, level + 1)) + text);
          else lines.add(descendantsEnd(lines, at), indent(level) + text);
          return update(s, a, d.with("nodes", join(lines)));
        });
    c.edit("smartArt.promote", "block?:integer,index?:integer,node?:integer",
        "Promote a SmartArt item (default the last) and the items under it one level", (s, a) -> level(s, a, -1));
    c.edit("smartArt.demote", "block?:integer,index?:integer,node?:integer",
        "Demote a SmartArt item (default the last) and the items under it one level", (s, a) -> level(s, a, 1));
    c.register(
        "smartArt.layout",
        "Change SmartArt layout: " + DrawingOps.SMART_ART,
        "block?:integer,layout:string",
        true,
        (s, a) -> update(s, a, smart(s, a).with("layout", layout(Commands.str(a, "layout")))));
    c.edit("smartArt.colors", "block?:integer,colors?:string,variant?:string",
        "SmartArt colors: colorful or accent1..accent6", (s, a) -> {
          String v = Commands.str(a, a.containsKey("variant") ? "variant" : "colors");
          return update(s, a, smart(s, a).with("colors", colors(v)));
        });
    c.edit("smartArt.reset", "block?:integer",
        "Reset SmartArt colors and styles, keeping text and layout", (s, a) -> {
          Drawing d = smart(s, a);
          return update(s, a, Drawing.of("smartArt").with("layout", d.get("layout", "list")).with("nodes", d.get("nodes", "")));
        });

    // ---------------------------------------------------------------- equations
    c.edit("insert.equation", "text?:string,latex?:string,builtin?:string,display?:boolean",
        "Insert an equation in linear format (\\\\alpha, x^2, (a)/(b) ...) or a built-in one"
            + " (pythagoras, area, quadratic, binomial, expansion, euler, derivative, taylor, gaussian,"
            + " fourier, trig1, trig2, normal, bayes). On an empty line it is a display equation,"
            + " in text an inline one", (s, a) -> {
          String text = Commands.str(a, "text", "");
          if (a.containsKey("latex")) text = Equation.linear(MathLatex.parse(Commands.str(a, "latex")));
          if (a.containsKey("builtin")) {
            text = Equation.builtIn(Commands.str(a, "builtin"));
            if (text == null) throw new IllegalArgumentException("Unknown built-in equation");
          }
          var d = Drawing.of("equation").with("text", text);
          var sel = s.selection();
          boolean emptyLine = sel.block != sel.anchorBlock
              || !(s.document().blocks.get(sel.block) instanceof Paragraph para)
              || para.text.toString().trim().length() == sel.end() - sel.start();
          boolean display = a.containsKey("display") ? (Boolean) a.get("display") : emptyLine;
          if (!display && s.document().blocks.get(sel.block) instanceof Paragraph) {
            s.insertField(Equation.inline(text));
            return Json.object("inline", true);
          }
          float width = display ? s.activeSection().resolved.page.contentWidth() : 160;
          float height = Equation.height(Equation.fromLinear(d.get("text", "")), d.num("fontSize", 12));
          return insert(s, d.with("display", String.valueOf(display)), width, height, "Equation");
        });
    c.edit("equation.inputFormat", "block?:integer,format:string",
        "unicode|latex: how typed equations build up (the Equation tab's Unicode / LaTeX toggle, as"
            + " Wordcraft's equation.inputFormat); professional|linear: show the equation built up or linear",
        (s, a) -> {
          String f = Commands.str(a, "format");
          if ("unicode".equalsIgnoreCase(f) || "latex".equalsIgnoreCase(f)) {
            s.mathLatex = "latex".equalsIgnoreCase(f);
            return Json.object("format", s.mathLatex ? "latex" : "unicode");
          }
          return update(s, a, equation(s, a).with("format", oneOf(f, "professional", "linear")));
        });
    c.edit("equation.convert", "block?:integer",
        "Toggle the equation between professional and linear form", (s, a) -> {
          Drawing d = equation(s, a);
          return update(s, a, d.with("format", "linear".equals(d.get("format", "professional")) ? "professional" : "linear"));
        });
    c.edit("equation.normalText", "block?:integer,value?:boolean",
        "Toggle upright normal text in the equation", (s, a) -> {
          Drawing d = equation(s, a);
          boolean on = Commands.flag(a, "value", !d.flag("normalText"));
          return update(s, a, d.with("normalText", String.valueOf(on)));
        });
    c.edit("equation.insertSymbol", "block?:integer,name:string",
        "Append a math symbol by control word (alpha, pm, infty, sum ...)", (s, a) -> {
          String name = Commands.str(a, "name");
          String word = name.startsWith("\\") ? name : "\\" + name;
          boolean known = false;
          for (String[] sym : DrawingOps.symbols()) known |= sym[0].equals(word);
          if (!known) throw new IllegalArgumentException("Unknown symbol: " + name);
          Drawing d = equation(s, a);
          return update(s, a, d.with("text", d.get("text", "") + word + " "));
        });
    c.edit("equation.structure", "block?:integer,kind:string",
        "Append a structure: fraction, script, subscript, radical, integral, sum, bracket, function, accent, limit, matrix",
        (s, a) -> {
          String kind = Commands.str(a, "kind");
          String snippet =
              "fraction".equals(kind) ? "(a)/(b)"
                  : "script".equals(kind) ? "x^2"
                  : "subscript".equals(kind) ? "x_1"
                  : "radical".equals(kind) ? "\\sqrt (x)"
                  : "integral".equals(kind) ? "\\int _0^1 f(x)dx"
                  : "sum".equals(kind) ? "\\sum _(i=1)^n i"
                  : "bracket".equals(kind) ? "(x)"
                  : "function".equals(kind) ? "sin(x)"
                  : "accent".equals(kind) ? "x\u0302"
                  : "limit".equals(kind) ? "lim_(n\\rightarrow \\infty ) a_n"
                  : "matrix".equals(kind) ? "[(1&0@0&1)]" : null;
          if (snippet == null) throw new IllegalArgumentException("Unknown structure: " + kind);
          Drawing d = equation(s, a);
          return update(s, a, d.with("text", d.get("text", "") + snippet));
        });
    c.edit("equation.display", "block?:integer,display?:boolean",
        "Change the equation to display (own line, full width) or inline", (s, a) -> {
          int block = block(s, a);
          Picture p = picture(s, block);
          Drawing d = equation(s, a);
          boolean display = Commands.flag(a, "display", !"true".equals(d.get("display", "true")));
          float width = display ? s.activeSection().resolved.page.contentWidth() : Math.max(60, d.get("text", "").length() * 7f);
          s.replacePicture(block, p.drawing(d.with("display", String.valueOf(display))).resize(width, p.height, p.alt, p.align));
          return display;
        });
    c.edit("equation.justify", "block?:integer,justify:string",
        "Equation justification: left, center or right",
        (s, a) -> update(s, a, equation(s, a).with("justify", oneOf(Commands.str(a, "justify"), "left", "center", "right"))));
    c.edit("equation.number", "block?:integer,value?:boolean",
        "Number the equation; numbers count display equations in document order", (s, a) -> {
          int block = block(s, a);
          Drawing d = equation(s, a);
          boolean on = Commands.flag(a, "value", d.get("number", "").isEmpty());
          s.replacePicture(block, picture(s, block).drawing(d.with("number", on ? "#" : "")));
          return renumber(s);
        });

    // ---------------------------------------------------------------- ink
    for (final String tool : new String[] {"pen", "pencil", "highlighter"})
      c.register(
          "draw." + tool,
          "Draw a " + tool + " stroke on the canvas or ink at the caret (points: x,y x,y ... in points)",
          "block?:integer,points:string,color?:string,width?:number",
          true,
          (s, a) -> {
            String color = a.containsKey("color") ? color(Commands.str(a, "color")) : defaultPen(tool)[1];
            if ("none".equals(color)) throw new IllegalArgumentException("Choose an ink color");
            float width = clamp(f(a, "width", Float.parseFloat(defaultPen(tool)[2])), .25f, 72);
            String points = points(Commands.str(a, "points"));
            var stroke = tool + "," + color + "," + width + ":" + points;
            int block = inkTarget(s, a);
            Picture p = picture(s, block);
            var strokes = p.drawing.get("strokes", "");
            s.replacePicture(block, p.drawing(p.drawing.with("strokes", strokes.isEmpty() ? stroke : strokes + "|" + stroke)));
            return Json.object("block", block, "strokes", count(p.drawing.get("strokes", "")) + 1);
          });
    c.query("draw.addPen", "tool:string,color:string,width?:number",
        "Add a pen preset (tool pen|pencil|highlighter, color, width) to the pen gallery",
        (s, a) -> {
          String tool = oneOf(Commands.str(a, "tool"), "pen", "pencil", "highlighter");
          String color = color(Commands.str(a, "color"));
          if ("none".equals(color)) throw new IllegalArgumentException("Choose an ink color");
          pens.add(tool + "," + color + "," + clamp(f(a, "width", 1), .25f, 72));
          return new ArrayList<Object>(pens);
        });
    c.edit("draw.eraser", "block?:integer,x:number,y:number,radius?:number",
        "Erase ink strokes passing near a point (x, y in points within the object)", (s, a) -> {
          int block = block(s, a);
          Picture p = ink(s, block);
          var before = p.drawing.get("strokes", "");
          String after = DrawingOps.erase(before, f(a, "x", 0), f(a, "y", 0), f(a, "radius", 6));
          if (!after.equals(before)) s.replacePicture(block, p.drawing(p.drawing.with("strokes", after)));
          return count(before) - count(after);
        });
    c.query("draw.lasso", "block?:integer,x:number,y:number,width:number,height:number",
        "Lasso select: report the ink strokes whose points fall inside a rectangle", (s, a) -> {
          Picture p = ink(s, block(s, a));
          float x = f(a, "x", 0), y = f(a, "y", 0), w = f(a, "width", 0), h = f(a, "height", 0);
          var hits = new ArrayList<Object>();
          var strokes = DrawingOps.split(p.drawing.get("strokes", ""), '|');
          for (int i = 0; i < strokes.size(); i++) {
            float[] box = bounds(strokes.get(i));
            if (box != null && box[0] >= x && box[1] >= y && box[2] <= x + w && box[3] <= y + h) hits.add(i);
          }
          return hits;
        });
    c.edit("draw.inkToShape", "block?:integer",
        "Convert the last ink stroke to the closest shape (line, rectangle, ellipse or triangle)",
        (s, a) -> {
          int block = block(s, a);
          Picture p = ink(s, block);
          var strokes = DrawingOps.split(p.drawing.get("strokes", ""), '|');
          if (strokes.isEmpty() || strokes.get(0).isEmpty()) throw new IllegalArgumentException("No ink to convert");
          var last = strokes.remove(strokes.size() - 1);
          float[] box = bounds(last);
          String shape = recognize(last, box);
          float w = Math.max(4, box[2] - box[0]), h = Math.max(4, box[3] - box[1]);
          var head = last.substring(0, last.indexOf(':'));
          var parts = DrawingOps.split(head, ',');
          var d =
              Drawing.of("shape").with("shape", shape).with("fill", "none")
                  .with("line", parts.size() > 1 ? parts.get(1) : "000000").with("lineWidth", parts.size() > 2 ? parts.get(2) : "1");
          if (s.inCell()) {
            // A cell's ink converts within the cell (Wordcraft's object paths run through cells).
            s.replacePictureAndAdd(block, p.drawing(p.drawing.with("strokes", join(strokes, '|'))),
                Picture.drawing("i0", d, w, "line".equals(shape) ? 12 : h, "Shape: " + shape, p.align));
            return shape;
          }
          var keep = s.selection();
          Sequence<Block> blocks = s.document().blocks;
          long id = s.document().nextId;
          blocks = blocks.set(block, p.drawing(p.drawing.with("strokes", join(strokes, '|'))));
          blocks = blocks.insert(block + 1, Picture.drawing("i" + id, d, w, "line".equals(shape) ? 12 : h, "Shape: " + shape, p.align));
          s.replaceDocument(s.document().blocks(blocks, id + 1));
          near(s, keep.block > block ? keep.block + 1 : keep.block);
          return shape;
        });
    c.query("draw.replay", "block?:integer",
        "Ink replay: the strokes of an ink object in drawing order", (s, a) -> {
          var out = new ArrayList<Object>();
          for (String stroke : DrawingOps.split(ink(s, block(s, a)).drawing.get("strokes", ""), '|'))
            if (!stroke.isEmpty()) out.add(stroke);
          return out;
        });

    // ---------------------------------------------------------------- arrange
    c.edit("arrange.align", "block?:integer,align:string",
        "Align the object left, center or right", (s, a) -> {
          int block = block(s, a);
          Picture p = picture(s, block);
          s.updatePicture(block, p.width, p.height, p.alt, oneOf(Commands.str(a, "align"), "left", "center", "right"), false);
          return null;
        });
    c.edit("arrange.wrap", "block?:integer,wrap:string",
        "Text wrapping: inline, square, tight, through, topBottom, behind or front", (s, a) -> {
          int block = block(s, a);
          Picture p = picture(s, block);
          String wrap = oneOf(Commands.str(a, "wrap"), "inline", "square", "tight", "through", "topBottom", "behind", "front");
          if (p.drawing == null) s.replacePicture(block, p.look(p.look.wrap(wrap, p.look.wrapPolygon)));
          else s.replacePicture(block, p.drawing(p.drawing.with("wrap", wrap)));
          return wrap;
        });
    c.edit("arrange.wrapText", "block?:integer,side?:string",
        "The sides of a Square, Tight or Through object text flows on: bothSides, left, right or largest (the wider side, line by line)", (s, a) -> {
          int block = block(s, a);
          Picture p = picture(s, block);
          String side = oneOf(Commands.str(a, "side", "bothSides"), "bothSides", "left", "right", "largest");
          s.replacePicture(block, p.wrapSide(side));
          return side;
        });
    c.edit("arrange.bounds", "block?:integer,x:number,y:number,page?:integer",
        "Move an object to page point x, y (points) on its anchor's page, or on page (0-based) re-anchored to the paragraph there: it floats there, Square wrapped if it was inline", (s, a) -> {
          int block = block(s, a);
          if (s.inCell()) {
            // A cell object stays in its cell (Word's layoutInCell): x, y are offsets from the
            // cell's content left and the anchor paragraph's top.
            Picture next = picture(s, block).movedInCell(Commands.number(a, "x", 0), Commands.number(a, "y", 0));
            s.replacePicture(block, next);
            return next.position();
          }
          if (a.get("page") != null) {
            int page = (int) Commands.number(a, "page", 0);
            var layout = c.layoutEngine().layout(s.document(), s.revision());
            int now = -1;
            for (int i = 0; i < layout.pages.size() && now < 0; i++)
              for (LayoutEngine.PlacedPicture x : layout.pages.get(i).pictures) if (!x.inCell() && x.block() == block) now = i;
            if (page != now) {
              Document moved = Picture.reanchored(s.document(), layout, block, page,
                  Commands.number(a, "x", 0), Commands.number(a, "y", 0));
              if (moved == null) throw new IllegalArgumentException("No text on that page to anchor to");
              s.replaceDocument(moved);
              return "reanchored";
            }
          }
          Picture next = picture(s, block).movedTo(Commands.number(a, "x", 0), Commands.number(a, "y", 0));
          s.replacePicture(block, next);
          return next.position();
        });
    c.edit("arrange.nudge", "block?:integer,dx?:number,dy?:number",
        "Move a floating object by dx, dy points", (s, a) -> {
          int block = block(s, a);
          Picture next = picture(s, block).nudged(Commands.number(a, "dx", 0), Commands.number(a, "dy", 0));
          s.replacePicture(block, next);
          return next.position();
        });
    c.edit("arrange.bringForward", "block?:integer,all?:boolean",
        "Bring the object forward (or to front with all)", (s, a) -> z(s, a, 1));
    c.edit("arrange.sendBackward", "block?:integer,all?:boolean",
        "Send the object backward (or to back with all)", (s, a) -> z(s, a, -1));
    c.edit("arrange.selectionPane", "block?:integer,name?:string,visible?:boolean",
        "Selection pane: list objects, rename one or hide/show it", (s, a) -> {
          if (a.containsKey("name") || a.containsKey("visible")) {
            int block = block(s, a);
            Picture p = picture(s, block);
            Drawing d = p.drawing == null ? Drawing.of("object") : p.drawing;
            if (p.drawing == null) throw new IllegalArgumentException("Select a drawn object");
            if (a.containsKey("name")) d = d.with("objectName", Commands.str(a, "name"));
            if (a.containsKey("visible")) d = d.with("hidden", String.valueOf(!(Boolean) a.get("visible")));
            s.replacePicture(block, p.drawing(d));
          }
          return objects(s.document());
        });
    c.query("select.objects", "",
        "List every picture and drawn object (block, kind, name)", (s, a) -> objects(s.document()));
    c.edit("arrange.group", "block?:integer,from?:integer,to?:integer,ungroup?:boolean",
        "Group the drawn objects in blocks from..to into one canvas, or ungroup a group",
        DrawingCommands::group);
  }

  // ---------------------------------------------------------------- helpers

  /** Puts the caret at the start of the first paragraph at or after {@code block}. */
  private static void near(Session s, int block) {
    var d = s.document();
    for (int i = Math.max(0, Math.min(block, d.blocks.size() - 1)); i < d.blocks.size(); i++)
      if (d.blocks.get(i) instanceof Paragraph) {
        s.select(i, 0, 0);
        return;
      }
    for (int i = d.blocks.size() - 1; i >= 0; i--)
      if (d.blocks.get(i) instanceof Paragraph) {
        s.select(i, 0, 0);
        return;
      }
  }

  private static Object insert(Session s, Drawing d, float w, float h, String alt) {
    int at = s.insertPicture(Picture.drawing("pending", d, w, h, alt, "left"));
    return Json.object("block", at);
  }

  private static int block(Session s, Map<String, Object> a) {
    if (a.containsKey("block")) return Commands.integer(a, "block");
    var d = s.editingDocument();
    int b = s.selection().block;
    if (b < d.blocks.size() && d.blocks.get(b) instanceof Picture) return b;
    for (int i = b - 1; i >= 0 && i >= b - 2; i--) if (d.blocks.get(i) instanceof Picture) return i;
    throw new IllegalArgumentException("Select an object");
  }

  private static Picture picture(Session s, int block) {
    var d = s.editingDocument(); // a table cell's blocks while a cell object is selected
    if (block < 0 || block >= d.blocks.size() || !(d.blocks.get(block) instanceof Picture))
      throw new IllegalArgumentException("Select an object");
    return (Picture) d.blocks.get(block);
  }

  private static Drawing drawing(Session s, Map<String, Object> a) {
    Picture p = picture(s, block(s, a));
    if (p.drawing == null) throw new IllegalArgumentException("Select a shape or drawn object");
    return p.drawing;
  }

  private static Drawing kind(Session s, Map<String, Object> a, String kind, String label) {
    Drawing d = drawing(s, a);
    if (!kind.equals(d.kind)) throw new IllegalArgumentException("Select " + label);
    return d;
  }

  private static Drawing chart(Session s, Map<String, Object> a) {
    return kind(s, a, "chart", "a chart");
  }

  private static Drawing smart(Session s, Map<String, Object> a) {
    return kind(s, a, "smartArt", "a SmartArt graphic");
  }

  private static Drawing equation(Session s, Map<String, Object> a) {
    return kind(s, a, "equation", "an equation");
  }

  private static Object update(Session s, Map<String, Object> a, Drawing d) {
    int block = block(s, a);
    s.replacePicture(block, picture(s, block).drawing(d));
    return null;
  }

  private static Drawing copy(Map<String, Object> a, Drawing d, String... keys) {
    for (String k : keys)
      if (a.containsKey(k)) {
        String v = Commands.str(a, k);
        d = d.with(k, "fill".equals(k) || "line".equals(k) ? color(v) : v);
      }
    return d;
  }

  static String color(String v) {
    if ("none".equals(v)) return "none";
    String h = v.startsWith("#") ? v.substring(1) : v;
    if (h.length() != 6 || !TextUtil.hex(h, 6)) throw new IllegalArgumentException("Color must be RRGGBB or none");
    return h.toLowerCase();
  }

  private static String oneOf(String v, String... allowed) {
    for (String x : allowed) if (x.equals(v)) return v;
    var b = new StringBuilder();
    for (String x : allowed) b.append(b.length() == 0 ? "" : ", ").append(x);
    throw new IllegalArgumentException("Expected one of " + b + ": " + v);
  }

  private static float f(Map<String, Object> a, String k, float fallback) {
    return a.containsKey(k) ? ((Number) a.get(k)).floatValue() : fallback;
  }

  private static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static Drawing wordArtStyle(Drawing d, int style) {
    int[][] styles = {
      {0x4472c4, -1, 0}, {0x000000, -1, 1}, {0xed7d31, 0xffffff, 0}, {0xffffff, 0x4472c4, 1},
      {0x70ad47, 0x375623, 1}, {0xffc000, 0x7f6000, 0}
    };
    if (style < 1 || style > styles.length) throw new IllegalArgumentException("WordArt style 1-6");
    var st = styles[style - 1];
    return d.with("textFill", hex(st[0])).with("textOutline", st[1] < 0 ? "none" : hex(st[1]))
        .with("shadow", String.valueOf(st[2] == 1)).with("wordArtStyle", String.valueOf(style));
  }

  private static String hex(int rgb) {
    return Integer.toHexString(0x1000000 | rgb).substring(1);
  }

  private static String chartData(String data) {
    var rows = DrawingOps.table(data);
    if (rows.size() < 2 || rows.get(0).length < 2) throw new IllegalArgumentException("Chart data needs a header row and one category");
    if (rows.size() > 200 || rows.get(0).length > 30) throw new IllegalArgumentException("Chart data is too large");
    return data.trim();
  }

  private static String nodes(String text) {
    if (DrawingOps.nodes(text).isEmpty()) throw new IllegalArgumentException("SmartArt needs at least one node");
    return text;
  }

  private static List<String> lines(Drawing d) {
    var out = new ArrayList<String>();
    for (String line : DrawingOps.split(d.get("nodes", ""), '\n')) if (!line.trim().isEmpty()) out.add(line);
    return out;
  }

  private static String tabs(String line) {
    int n = 0;
    while (n < line.length() && line.charAt(n) == '\t') n++;
    return line.substring(0, n);
  }

  private static String join(List<String> lines) {
    return join(lines, '\n');
  }

  private static String join(List<String> parts, char sep) {
    var b = new StringBuilder();
    for (String p : parts) {
      if (b.length() > 0) b.append(sep);
      b.append(p);
    }
    return b.toString();
  }

  static final int MAX_ITEMS = 100, MAX_LEVEL = 7;

  private static Object level(Session s, Map<String, Object> a, int delta) {
    Drawing d = smart(s, a);
    var lines = lines(d);
    if (lines.isEmpty()) throw new IllegalStateException("The graphic has no items");
    int node = Commands.integer(a, a.containsKey("index") ? "index" : "node", lines.size() - 1);
    if (node < 0 || node >= lines.size()) throw new IllegalArgumentException("Item out of range");
    int level = tabs(lines.get(node)).length(), end = descendantsEnd(lines, node);
    if (delta < 0 && level == 0) throw new IllegalStateException("This item is already at the top level");
    if (delta > 0) {
      int deepest = 0;
      for (int j = node; j < end; j++) deepest = Math.max(deepest, tabs(lines.get(j)).length());
      if (node == 0 || tabs(lines.get(node - 1)).length() < level || deepest >= MAX_LEVEL)
        throw new IllegalStateException("This item can't go down a level (it needs an item before it at its level)");
    }
    for (int j = node; j < end; j++) lines.set(j, delta < 0 ? lines.get(j).substring(1) : "\t" + lines.get(j));
    update(s, a, d.with("nodes", join(lines)));
    return true;
  }

  /** The index after item i and the items under it (deeper items that follow it). */
  static int descendantsEnd(List<String> lines, int i) {
    int level = tabs(lines.get(i)).length(), j = i + 1;
    while (j < lines.size() && tabs(lines.get(j)).length() > level) j++;
    return j;
  }

  private static String indent(int level) {
    var b = new StringBuilder();
    for (int i = 0; i < level; i++) b.append('\t');
    return b.toString();
  }

  /** Item text: control characters as spaces, ends trimmed, at most 1000 characters. */
  private static String clean(String t) {
    var b = new StringBuilder();
    for (int i = 0; i < t.length() && b.length() < 1000; i++) b.append(t.charAt(i) < ' ' ? ' ' : t.charAt(i));
    return b.toString().trim();
  }

  private static String items(Object v) {
    if (!(v instanceof List<?> list)) throw new IllegalArgumentException("items: [string | {text, level}]");
    var lines = new ArrayList<String>();
    for (Object it : list) {
      if (lines.size() >= MAX_ITEMS) break;
      int level = 0;
      String text;
      if (it instanceof Map<?, ?> m) {
        text = m.get("text") == null ? "" : String.valueOf(m.get("text"));
        if (m.get("level") instanceof Number n) level = n.intValue();
      } else if (it instanceof String str) {
        while (level < str.length() && str.charAt(level) == '\t') level++;
        text = str;
      } else throw new IllegalArgumentException("items: [string | {text, level}]");
      text = clean(text);
      lines.add(indent(Math.max(0, Math.min(MAX_LEVEL, level))) + (text.isEmpty() ? "Text" : text));
    }
    if (lines.isEmpty()) throw new IllegalArgumentException("SmartArt needs at least one item");
    return join(lines);
  }

  private static String layout(String id) {
    String l = DrawingOps.smartLayout(id);
    if (l == null) throw new IllegalArgumentException("Unknown layout: " + id + " (" + DrawingOps.SMART_ART + ")");
    return l;
  }

  private static String colors(String v) {
    if (!"colorful".equals(v) && !(v.startsWith("accent") && v.length() == 7 && v.charAt(6) >= '1' && v.charAt(6) <= '6'))
      throw new IllegalArgumentException("colors: colorful or accent1..accent6");
    return v;
  }

  /** Numbers every numbered equation 1, 2, 3 in document order; returns the count. */
  private static int renumber(Session s) {
    if (s.inCell()) {
      // A cell's equations count from 1 within the cell and leave the body count alone, as
      // Wordcraft lays cell paragraphs out with eq_number 0 (layout/src/table.rs).
      var cell = s.editingDocument().blocks;
      int n = 0;
      for (int i = 0; i < cell.size(); i++)
        if (cell.get(i) instanceof Picture p && p.drawing != null && "equation".equals(p.drawing.kind)
            && !p.drawing.get("number", "").isEmpty()) {
          String next = String.valueOf(++n);
          if (!next.equals(p.drawing.get("number", ""))) s.replacePicture(i, p.drawing(p.drawing.with("number", next)));
        }
      return n;
    }
    var doc = s.document();
    Sequence<Block> blocks = doc.blocks;
    int n = 0;
    for (int i = 0; i < blocks.size(); i++) {
      if (!(blocks.get(i) instanceof Picture)) continue;
      var p = (Picture) blocks.get(i);
      if (p.drawing == null || !"equation".equals(p.drawing.kind) || p.drawing.get("number", "").isEmpty()) continue;
      String next = String.valueOf(++n);
      if (!next.equals(p.drawing.get("number", ""))) blocks = blocks.set(i, p.drawing(p.drawing.with("number", next)));
    }
    if (blocks != doc.blocks) {
      var keep = s.selection();
      s.replaceDocument(doc.blocks(blocks, doc.nextId));
      s.selectRange(keep.anchorBlock, keep.anchor, keep.block, keep.caret);
    }
    return n;
  }

  private String[] defaultPen(String tool) {
    for (int i = pens.size() - 1; i >= 0; i--)
      if (pens.get(i).startsWith(tool + ",")) return TextUtil.split(pens.get(i), ',');
    return new String[] {tool, "000000", "1"};
  }

  private static String points(String text) {
    var b = new StringBuilder();
    int n = 0;
    for (String pt : DrawingOps.split(text.trim(), ' ')) {
      if (pt.isEmpty()) continue;
      int comma = pt.indexOf(',');
      if (comma < 0) throw new IllegalArgumentException("Points are x,y pairs separated by spaces");
      float x = Float.parseFloat(pt.substring(0, comma)), y = Float.parseFloat(pt.substring(comma + 1));
      if (Float.isNaN(x) || Float.isNaN(y) || Math.abs(x) > 10000 || Math.abs(y) > 10000)
        throw new IllegalArgumentException("Point out of range");
      b.append(n++ == 0 ? "" : " ").append(x).append(',').append(y);
    }
    if (n == 0 || n > 5000) throw new IllegalArgumentException("A stroke needs 1 to 5000 points");
    return b.toString();
  }

  private static int count(String strokes) {
    int n = 0;
    for (String st : DrawingOps.split(strokes, '|')) if (!st.isEmpty()) n++;
    return n;
  }

  private static Picture ink(Session s, int block) {
    Picture p = picture(s, block);
    if (p.drawing == null || !("ink".equals(p.drawing.kind) || "canvas".equals(p.drawing.kind)))
      throw new IllegalArgumentException("Select ink or a drawing canvas");
    return p;
  }

  /** The canvas/ink object at the caret, or a new ink object inserted for this stroke. */
  private static int inkTarget(Session s, Map<String, Object> a) {
    try {
      int block = block(s, a);
      Picture p = picture(s, block);
      if (p.drawing != null && ("ink".equals(p.drawing.kind) || "canvas".equals(p.drawing.kind))) return block;
    } catch (IllegalArgumentException none) {
      // fall through and create ink
    }
    if (a.containsKey("block")) throw new IllegalArgumentException("Select ink or a drawing canvas");
    return s.insertPicture(Picture.drawing("pending", Drawing.of("ink"), s.activeSection().resolved.page.contentWidth(), 144, "Ink", "left"));
  }

  static float[] bounds(String stroke) {
    int colon = stroke.indexOf(':');
    if (colon < 0) return null;
    float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
    for (String pt : DrawingOps.split(stroke.substring(colon + 1).trim(), ' ')) {
      int comma = pt.indexOf(',');
      if (comma < 0) continue;
      float x = Float.parseFloat(pt.substring(0, comma)), y = Float.parseFloat(pt.substring(comma + 1));
      box[0] = Math.min(box[0], x);
      box[1] = Math.min(box[1], y);
      box[2] = Math.max(box[2], x);
      box[3] = Math.max(box[3], y);
    }
    return box[0] > box[2] ? null : box;
  }

  /**
   * Classifies a stroke by how far its points sit from the bounding box edges and from the inscribed
   * ellipse, plus straightness for lines and corner count for triangles.
   */
  static String recognize(String stroke, float[] box) {
    float w = box[2] - box[0], h = box[3] - box[1];
    if (Math.min(w, h) < Math.max(w, h) * .15f) return "line";
    var pts = new ArrayList<float[]>();
    for (String pt : DrawingOps.split(stroke.substring(stroke.indexOf(':') + 1).trim(), ' ')) {
      int comma = pt.indexOf(',');
      if (comma > 0) pts.add(new float[] {Float.parseFloat(pt.substring(0, comma)), Float.parseFloat(pt.substring(comma + 1))});
    }
    float edge = 0, ring = 0;
    float cx = (box[0] + box[2]) / 2, cy = (box[1] + box[3]) / 2;
    for (float[] p : pts) {
      float dx = Math.min(Math.abs(p[0] - box[0]), Math.abs(p[0] - box[2])) / w;
      float dy = Math.min(Math.abs(p[1] - box[1]), Math.abs(p[1] - box[3])) / h;
      edge += Math.min(dx, dy);
      double r = Math.sqrt(com.codename1.util.MathUtil.pow((p[0] - cx) / (w / 2), 2) + com.codename1.util.MathUtil.pow((p[1] - cy) / (h / 2), 2));
      ring += Math.abs(r - 1);
    }
    edge /= pts.size();
    ring /= pts.size();
    // Triangle: top half narrower than the bottom half.
    float topSpan = 0, bottomSpan = 0;
    float topMin = Float.MAX_VALUE, topMax = -Float.MAX_VALUE, botMin = Float.MAX_VALUE, botMax = -Float.MAX_VALUE;
    for (float[] p : pts) {
      if (p[1] < box[1] + h * .25f) {
        topMin = Math.min(topMin, p[0]);
        topMax = Math.max(topMax, p[0]);
      } else if (p[1] > box[1] + h * .75f) {
        botMin = Math.min(botMin, p[0]);
        botMax = Math.max(botMax, p[0]);
      }
    }
    topSpan = topMax > topMin ? topMax - topMin : 0;
    bottomSpan = botMax > botMin ? botMax - botMin : 0;
    if (bottomSpan > w * .7f && topSpan < w * .35f) return "triangle";
    return edge < ring * .6f ? "rect" : "ellipse";
  }

  private static Object z(Session s, Map<String, Object> a, int delta) {
    int block = block(s, a);
    Picture p = picture(s, block);
    if (p.drawing == null) throw new IllegalArgumentException("Select a shape or drawn object");
    int z = Math.round(p.drawing.num("z", 0));
    boolean all = a.containsKey("all") && (Boolean) a.get("all");
    int next = all ? (delta > 0 ? 1000 : -1000) : Math.max(-1000, Math.min(1000, z + delta));
    s.replacePicture(block, p.drawing(p.drawing.with("z", String.valueOf(next))));
    return next;
  }

  private static List<Object> objects(Document d) {
    var out = new ArrayList<Object>();
    for (int i = 0; i < d.blocks.size(); i++) {
      if (!(d.blocks.get(i) instanceof Picture)) continue;
      var p = (Picture) d.blocks.get(i);
      String kind = p.drawing == null ? "picture" : p.drawing.kind;
      String name = p.drawing == null ? "" : p.drawing.get("objectName", "");
      out.add(
          Json.object(
              "block", i, "kind", kind, "name", name.isEmpty() ? (p.alt.isEmpty() ? kind : p.alt) : name,
              "visible", p.drawing == null || !p.drawing.flag("hidden"),
              "wrap", p.wrap(),
              "z", p.drawing == null ? 0 : Math.round(p.drawing.num("z", 0))));
    }
    return out;
  }

  private static final char ITEM = '\u001e', FIELD = '\u001f';

  /** Canvas children: x, y, w, h and the child's encoding, one record each. */
  private static Object group(Session s, Map<String, Object> a) {
    var doc = s.document();
    var keep = s.selection();
    if (a.containsKey("ungroup") && (Boolean) a.get("ungroup")) {
      int block = block(s, a);
      Picture p = picture(s, block);
      if (p.drawing == null || !"canvas".equals(p.drawing.kind) || p.drawing.get("items", "").isEmpty())
        throw new IllegalArgumentException("Select a group");
      var blocks = doc.blocks.remove(block);
      long id = doc.nextId;
      int at = block, n = 0;
      for (String item : DrawingOps.split(p.drawing.get("items", ""), ITEM)) {
        var f = DrawingOps.split(item, FIELD);
        if (f.size() != 5) continue;
        blocks = blocks.insert(at++, Picture.drawing("i" + id++, Drawing.decode(f.get(4)),
            Float.parseFloat(f.get(2)), Float.parseFloat(f.get(3)), "", p.align));
        n++;
      }
      s.replaceDocument(doc.blocks(blocks, id));
      near(s, keep.block > block ? keep.block + n - 1 : keep.block);
      return n;
    }
    int from = Commands.integer(a, "from"), to = Commands.integer(a, "to");
    if (from < 0 || to >= doc.blocks.size() || to <= from) throw new IllegalArgumentException("Group needs a block range of two or more");
    var items = new StringBuilder();
    float y = 0, width = 0;
    for (int i = from; i <= to; i++) {
      var b = doc.blocks.get(i);
      if (!(b instanceof Picture) || ((Picture) b).drawing == null)
        throw new IllegalArgumentException("Only adjacent drawn objects can be grouped");
      var p = (Picture) b;
      if (items.length() > 0) items.append(ITEM);
      items.append(0).append(FIELD).append(y).append(FIELD).append(p.width).append(FIELD).append(p.height)
          .append(FIELD).append(p.drawing.encode());
      y += p.height + 6;
      width = Math.max(width, p.width);
    }
    Sequence<Block> blocks = doc.blocks;
    for (int i = to; i > from; i--) blocks = blocks.remove(i);
    var first = (Picture) doc.blocks.get(from);
    var canvas = Drawing.of("canvas").with("items", items.toString()).with("line", "none");
    blocks = blocks.set(from, Picture.drawing(first.id(), canvas, width, y - 6, "Group", first.align));
    s.replaceDocument(doc.blocks(blocks, doc.nextId));
    for (int i = from + 1; i < blocks.size(); i++)
      if (blocks.get(i) instanceof Paragraph) {
        s.select(i, 0, 0);
        break;
      }
    return Json.object("block", from, "items", to - from + 1);
  }

  /** Wordcraft's ChartType ids columnStacked and barStacked: column/bar with stacked grouping. */
  private static String grouping(String type) {
    return "columnStacked".equals(type) || "barStacked".equals(type) ? "stacked" : "";
  }

  private static String baseType(String type) {
    return "columnStacked".equals(type) ? "column" : "barStacked".equals(type) ? "bar" : type;
  }
}
