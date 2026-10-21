package com.codenameone.wordprocessor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Editing workflows that span several features: header/footer section navigation, extend,
 * similar-format and column selection, Advanced Find with wildcards, the Office clipboard and
 * Paste Special, the Font dialog, character styles, the Styles pane/inspector/manager, cover
 * pages, Quick Parts and AutoText galleries, Insert Text from File and embedded spreadsheets.
 */
final class WorkflowCommands implements Commands.Extension {
  /** Office clipboard: the last 24 copied fragments per session, newest first. */
  private static final SessionMap<List<Document>> CLIPBOARD = new SessionMap<>();

  static final int CLIPBOARD_SIZE = 24;

  /** Records the current selection on the Office clipboard (called by edit.copy and edit.cut). */
  static void record(Session s) {
    var fragment = s.copySelection();
    if (fragment == null) return;
    synchronized (CLIPBOARD) {
      var items = CLIPBOARD.get(s);
      if (items == null) CLIPBOARD.put(s, items = new ArrayList<Document>());
      items.add(0, fragment);
      while (items.size() > CLIPBOARD_SIZE) items.remove(items.size() - 1);
    }
  }

  private static List<Document> clipboard(Session s) {
    synchronized (CLIPBOARD) {
      var items = CLIPBOARD.get(s);
      return items == null ? new ArrayList<Document>() : items;
    }
  }

  static final String[][] COVERS = {
    {"Austin", "2f5496", "none", "44"},
    {"Banded", "ffffff", "2f5496", "40"},
    {"Facet", "5b8a1f", "none", "48"},
    {"Grid", "1f3763", "d9e2f3", "40"},
    {"Ion", "ffffff", "1b8ba8", "44"},
    {"Motion", "c0640d", "none", "40"},
    {"Retrospect", "ffffff", "c0640d", "44"},
    {"Sideline", "404040", "none", "36"},
    {"Whisp", "8b2a0d", "none", "40"}
  };

  public void register(Commands c) {
    c.query("hf.previous", "",
        "Go to the previous section's header/footer", (s, a) -> section(s, -1));
    c.query("hf.next", "",
        "Go to the next section's header/footer", (s, a) -> section(s, 1));
    c.query("select.extend", "block?:integer,offset?:integer",
        "Extend selection (F8): grow to word, sentence, paragraph, then the document; or extend to block/offset",
        (s, a) -> {
          var sel = s.selection();
          if (a.containsKey("block")) {
            int block = ((Number) a.get("block")).intValue();
            int offset = a.containsKey("offset") ? ((Number) a.get("offset")).intValue() : 0;
            if (block < 0 || block >= s.document().blocks.size()) throw new IllegalArgumentException("No such block");
            s.selectRange(sel.anchorBlock, sel.anchor, block, offset);
            return s.selectedText();
          }
          for (String step : new String[] {"select.word", "select.sentence", "select.paragraph", "select.all"}) {
            var before = s.selection();
            c.execute(s, step, new LinkedHashMap<>());
            if (!same(before, s.selection()) && covers(s.selection(), sel)) return s.selectedText();
            if (!covers(s.selection(), sel)) s.selectRange(before.anchorBlock, before.anchor, before.block, before.caret);
          }
          return s.selectedText();
        });
    c.edit("select.similar", "property?:string,value?:string",
        "Select text with similar formatting; returns the ranges and, with property/value, formats them all",
        (s, a) -> {
          var like = s.document().paragraph(s.selection().block).text.styleAt(Math.max(0, s.selection().start() - (s.selection().collapsed() ? 1 : 0)));
          var ranges = new ArrayList<int[]>();
          var d = s.document();
          for (int b = 0; b < d.blocks.size(); b++) {
            if (!(d.blocks.get(b) instanceof Paragraph)) continue;
            var t = ((Paragraph) d.blocks.get(b)).text;
            int start = -1;
            for (int i = 0; i <= t.length(); i++) {
              boolean match = i < t.length() && similar(t.styleAt(i), like);
              if (match && start < 0) start = i;
              if (!match && start >= 0) {
                ranges.add(new int[] {b, start, i});
                start = -1;
              }
            }
          }
          if (a.containsKey("property"))
            for (int[] r : ranges) {
              s.select(r[0], r[1], r[2]);
              s.format(Commands.str(a, "property"), Commands.str(a, "value"));
            }
          if (!ranges.isEmpty()) s.select(ranges.get(0)[0], ranges.get(0)[1], ranges.get(0)[2]);
          var out = new ArrayList<Object>();
          for (int[] r : ranges) out.add(Arrays.asList(r[0], r[1], r[2]));
          return out;
        });
    c.edit(
        "select.column", "fromBlock:integer,toBlock:integer,startColumn:integer,endColumn:integer,action?:string",
        "Column (block) selection: characters startColumn..endColumn of blocks fromBlock..toBlock; copy or delete",
        (s, a) -> {
          int b0 = Commands.integer(a, "fromBlock"), b1 = Commands.integer(a, "toBlock");
          int c0 = Commands.integer(a, "startColumn"), c1 = Commands.integer(a, "endColumn");
          if (b0 > b1 || c0 > c1 || b0 < 0 || b1 >= s.document().blocks.size() || c0 < 0)
            throw new IllegalArgumentException("Invalid column rectangle");
          String action = Commands.str(a, "action", "copy");
          var text = new StringBuilder();
          for (int b = b0; b <= b1; b++) {
            if (!(s.document().blocks.get(b) instanceof Paragraph)) continue;
            var line = s.document().paragraph(b).text.plainText();
            if (text.length() > 0) text.append('\n');
            if (c0 < line.length()) text.append(line.substring(c0, Math.min(c1, line.length())));
          }
          if ("delete".equals(action))
            for (int b = b1; b >= b0; b--) {
              if (!(s.document().blocks.get(b) instanceof Paragraph)) continue;
              int len = s.document().paragraph(b).text.length();
              if (c0 >= len) continue;
              s.select(b, c0, Math.min(c1, len));
              s.replace("");
            }
          else if (!"copy".equals(action)) throw new IllegalArgumentException("Action is copy or delete");
          else s.selectRange(b0, Math.min(c0, s.document().paragraph(b0).text.length()), b1, Math.min(c1, s.document().paragraph(b1).text.length()));
          return text.toString();
        });
    c.query(
        "edit.advancedFind", "text:string,matchCase?:boolean,wholeWord?:boolean,wildcards?:boolean,index?:integer",
        "Advanced Find: match case, whole words and wildcards (? * [abc] [!a] [a-z] < >); selects match number 'index'",
        (s, a) -> {
          String pattern = Commands.str(a, "text");
          if (pattern.isEmpty()) throw new IllegalArgumentException("Find what?");
          boolean matchCase = Boolean.TRUE.equals(a.get("matchCase")), whole = Boolean.TRUE.equals(a.get("wholeWord"));
          boolean wild = Boolean.TRUE.equals(a.get("wildcards"));
          var found = new ArrayList<int[]>();
          var d = s.document();
          for (int b = 0; b < d.blocks.size(); b++) {
            if (!(d.blocks.get(b) instanceof Paragraph)) continue;
            var text = d.paragraph(b).text.plainText();
            for (int i = 0; i < text.length(); ) {
              int end = wild ? wildcard(text, i, pattern, 0) : literal(text, i, pattern, matchCase || wild);
              if (end > i && (!whole || boundary(text, i - 1) && boundary(text, end))) {
                found.add(new int[] {b, i, end});
                i = end;
              } else i++;
            }
          }
          int index = Commands.integer(a, "index", 0);
          if (!found.isEmpty()) {
            var f = found.get(((index % found.size()) + found.size()) % found.size());
            s.select(f[0], f[1], f[2]);
          }
          var out = new ArrayList<Object>();
          for (int[] f : found) out.add(Arrays.asList(f[0], f[1], f[2]));
          return out;
        });
    c.edit("edit.clipboardPane", "action?:string,index?:integer",
        "Office clipboard (last 24 copies): list, paste an item, delete one or clear", (s, a) -> {
          String action = Commands.str(a, "action", "list");
          var items = clipboard(s);
          int index = Commands.integer(a, "index", 0);
          if ("paste".equals(action)) s.paste(item(items, index));
          else if ("delete".equals(action)) {
            item(items, index);
            synchronized (CLIPBOARD) {
              items.remove(index);
            }
          } else if ("clear".equals(action)) {
            synchronized (CLIPBOARD) {
              items.clear();
            }
          } else if (!"list".equals(action)) throw new IllegalArgumentException("Action is list, paste, delete or clear");
          var out = new ArrayList<Object>();
          for (Document item : items) {
            var text = item.plainText();
            out.add(text.length() > 80 ? text.substring(0, 80) : text);
          }
          return out;
        });
    c.edit("edit.pasteSpecial", "format:string,data?:string,index?:integer",
        "Paste Special: keepSource, merge, text, or html/rtf/markdown data; defaults to the newest clipboard item",
        (s, a) -> {
          pasteSpecial(s, Commands.str(a, "format"), a);
          return null;
        });
    c.edit("edit.pasteMerge", "index?:integer",
        "Paste with merge formatting: keep emphasis, take the destination font, size and color",
        (s, a) -> {
          pasteSpecial(s, "merge", a);
          return null;
        });
    c.edit(
        "format.fontDialog", "font?:string,size?:number,color?:string,bold?:boolean,italic?:boolean,underlineStyle?:string,underlineColor?:string,strike?:boolean,doubleStrike?:boolean,script?:string,smallCaps?:boolean,allCaps?:boolean,hidden?:boolean,characterSpacing?:number,characterScale?:number,characterPosition?:number",
        "Font dialog: set several character properties at once and return the effective font settings",
        (s, a) -> {
          for (Map.Entry<String, Object> e : a.entrySet()) {
            var v = e.getValue();
            String value = v instanceof Number n ? String.valueOf(n.floatValue()) : String.valueOf(v);
            if ("color".equals(e.getKey()) || "underlineColor".equals(e.getKey()))
              value = value.startsWith("#") ? value.substring(1) : value;
            if ("script".equals(e.getKey()) && !Arrays.asList("baseline", "superscript", "subscript").contains(value))
              throw new IllegalArgumentException("Script is baseline, superscript or subscript");
            s.format(e.getKey(), value);
          }
          var t = s.activeStyle();
          var out = new LinkedHashMap<String, Object>();
          out.put("font", t.font);
          out.put("size", t.size);
          out.put("color", TextUtil.padded(t.color, 16, 6));
          out.put("bold", t.bold);
          out.put("italic", t.italic);
          out.put("underlineStyle", t.characters.underlineStyle);
          out.put("strike", t.strike);
          out.put("script", t.script);
          for (String k : new String[] {"doubleStrike", "smallCaps", "allCaps", "hidden", "characterSpacing", "characterScale", "characterPosition"})
            out.put(k, t.characters.value(k));
          return out;
        });
    c.edit("format.charStyle", "name:string",
        "Apply a style's character formatting to the selected text only (a linked character style)",
        (s, a) -> {
          ParagraphStyle st = style(s.document(), Commands.str(a, "name"));
          var t = st.text;
          s.format("font", t.font);
          s.format("size", String.valueOf(t.size));
          s.format("color", TextUtil.padded(t.color, 16, 6));
          s.format("bold", String.valueOf(t.bold));
          s.format("italic", String.valueOf(t.italic));
          s.format("underline", String.valueOf(t.underline));
          s.format("allCaps", String.valueOf(t.characters.allCaps));
          s.format("smallCaps", String.valueOf(t.characters.smallCaps));
          return st.id;
        });
    c.query("styles.pane", "all?:boolean",
        "Styles pane: styles with usage counts and the current style (hidden styles only with all=true)",
        (s, a) -> {
          var d = s.document();
          var hidden = "," + d.properties.setting("styles.hidden", "") + ",";
          var uses = new LinkedHashMap<String, Integer>();
          for (Block b : d.blocks)
            if (b instanceof Paragraph paragraph) {
              var n = uses.get(paragraph.style);
              uses.put(paragraph.style, n == null ? 1 : n + 1);
            }
          String current = s.document().blocks.get(s.selection().block) instanceof Paragraph ? d.paragraph(s.selection().block).style : "";
          var out = new ArrayList<Object>();
          for (String id : ordered(d)) {
            if (hidden.contains("," + id + ",") && !Boolean.TRUE.equals(a.get("all"))) continue;
            var st = d.styles.get(id);
            var row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            row.put("name", st.name);
            row.put("inUse", uses.containsKey(id) ? uses.get(id) : 0);
            row.put("current", id.equals(current));
            row.put("font", st.text.font);
            row.put("size", st.text.size);
            row.put("bold", st.text.bold);
            out.add(row);
          }
          return out;
        });
    c.edit("styles.inspector", "clear?:string",
        "Style Inspector: paragraph/character style and direct formatting at the caret; clear=paragraph|character resets it",
        (s, a) -> {
          var d = s.document();
          int block = s.selection().block;
          if (!(d.blocks.get(block) instanceof Paragraph)) throw new IllegalArgumentException("Select a paragraph");
          var p = d.paragraph(block);
          var st = d.styles.get(p.style);
          if (st == null) st = ParagraphStyle.defaults().get("Normal");
          String clear = Commands.str(a, "clear", "");
          if ("paragraph".equals(clear)) {
            String[] keys = {"align", "before", "after", "indent", "spacing", "rightIndent", "firstIndent"};
            for (String k : keys) s.paragraph(k, paragraphValue(st.paragraph, k));
          } else if ("character".equals(clear)) {
            for (String k : new String[] {"font", "size", "color", "bold", "italic", "underline", "strike"})
              s.format(k, textValue(st.text, k));
          } else if (!clear.isEmpty()) throw new IllegalArgumentException("Clear paragraph or character");
          p = s.document().paragraph(block);
          var t = s.activeStyle();
          var out = new LinkedHashMap<String, Object>();
          out.put("paragraphStyle", st.id);
          var pd = new LinkedHashMap<String, Object>();
          for (String k : new String[] {"align", "before", "after", "indent", "spacing", "rightIndent", "firstIndent"})
            if (!paragraphValue(p, k).equals(paragraphValue(st.paragraph, k))) pd.put(k, paragraphValue(p, k));
          out.put("paragraphDirect", pd);
          var td = new LinkedHashMap<String, Object>();
          for (String k : new String[] {"font", "size", "color", "bold", "italic", "underline", "strike"})
            if (!textValue(t, k).equals(textValue(st.text, k))) td.put(k, textValue(t, k));
          out.put("characterDirect", td);
          return out;
        });
    c.edit("styles.manage", "action?:string,id?:string,order?:string",
        "Manage Styles: list, hide or show a style in the pane, or set the recommended order",
        (s, a) -> {
          var d = s.document();
          String action = Commands.str(a, "action", "list");
          var p = d.properties;
          if ("hide".equals(action) || "show".equals(action)) {
            String id = Commands.str(a, "id");
            style(d, id);
            var hidden = new ArrayList<String>();
            for (String h : TextUtil.split(p.setting("styles.hidden", ""), ',')) if (!h.isEmpty() && !h.equals(id)) hidden.add(h);
            if ("hide".equals(action)) hidden.add(id);
            p = p.withSetting("styles.hidden", join(hidden));
          } else if ("order".equals(action)) {
            var order = new ArrayList<String>();
            for (String id : TextUtil.split(Commands.str(a, "order"), ',')) {
              style(d, id.trim());
              order.add(id.trim());
            }
            p = p.withSetting("styles.order", join(order));
          } else if (!"list".equals(action)) throw new IllegalArgumentException("Action is list, hide, show or order");
          if (p != d.properties) s.documentProperties(p);
          var out = new LinkedHashMap<String, Object>();
          out.put("order", ordered(s.document()));
          out.put("hidden", s.document().properties.setting("styles.hidden", ""));
          return out;
        });
    c.edit(
        "insert.coverPage", "design?:string,title?:string,subtitle?:string,author?:string,date?:string,remove?:boolean",
        "Insert a designed cover page (title, subtitle, author, date, page break) or remove it",
        WorkflowCommands::coverPage);
    gallery(c, "insert.quickParts", "quickPart.", "Quick Parts gallery: save the selection, insert, list or delete an entry");
    gallery(c, "insert.autoText", "autoText.", "AutoText gallery: save the selection, insert, list or delete an entry");
    c.edit("insert.textFromFile", "data:string,format:string,password?:string",
        "Insert Text from File: base64 DOCX (password for an encrypted one), or RTF/HTML/Markdown/plain/native XML text, at the caret",
        (s, a) -> {
          String format = Commands.str(a, "format"), data = Commands.str(a, "data");
          Document doc;
          try {
            if ("docx".equals(format)) {
              byte[] bytes = com.codename1.util.Base64.decode(data.getBytes("UTF-8"));
              if (OfficeCrypto.isEncrypted(bytes)) {
                if (!a.containsKey("password")) throw new IllegalArgumentException("The document is encrypted; supply its password");
                bytes = OfficeCrypto.decrypt(bytes, Commands.str(a, "password"));
              }
              doc = Docx.read(new ByteArrayInputStream(bytes)).document;
            }
            else if ("native".equals(format)) doc = DocumentXml.read(data);
            else if ("rtf".equals(format)) doc = TextFormats.read(data, com.codename1.ui.RichTextFormat.RTF);
            else if ("html".equals(format)) doc = TextFormats.read(data, com.codename1.ui.RichTextFormat.HTML);
            else if ("markdown".equals(format)) doc = TextFormats.read(data, com.codename1.ui.RichTextFormat.MARKDOWN);
            else if ("text".equals(format)) {
              s.pasteText(data);
              return null;
            } else throw new IllegalArgumentException("Format is docx, rtf, html, markdown, text or native");
          } catch (IOException e) {
            throw new IllegalArgumentException("Unreadable file: " + e.getMessage());
          }
          s.paste(doc);
          return doc.blocks.size();
        });
    c.edit("insert.spreadsheet", "rows?:integer,columns?:integer,data?:string",
        "Insert an embedded spreadsheet: a gridded table seeded from TSV whose =formulas evaluate",
        (s, a) -> {
          String data = Commands.str(a, "data", "");
          String[] lines = data.isEmpty() ? new String[0] : TextUtil.split(data, '\n');
          int rows = Commands.integer(a, "rows", Math.max(3, lines.length));
          int cols = Commands.integer(a, "columns", 3);
          for (String line : lines) cols = Math.max(cols, TextUtil.split(line, '\t').length);
          s.insertTable(Math.max(rows, lines.length), cols);
          int block = s.cellBlock();
          if (block < 0)
            for (int i = s.document().blocks.size() - 1; i >= 0; i--)
              if (s.document().blocks.get(i) instanceof Table) {
                block = i;
                break;
              }
          for (int r = 0; r < lines.length; r++) {
            String[] cells = TextUtil.split(lines[r], '\t');
            for (int col = 0; col < cells.length; col++) {
              var v = cells[col];
              var args = new LinkedHashMap<String, Object>();
              args.put("block", block);
              args.put("row", r);
              args.put("column", col);
              if (v.startsWith("=")) {
                s.enterCell(block, r, col);
                args.clear();
                args.put("formula", v);
                c.execute(s, "table.formula", args);
              } else {
                args.put("text", v);
                c.execute(s, "table.setCell", args);
              }
            }
          }
          s.enterCell(block, 0, 0);
          var t = (Table) s.document().blocks.get(block);
          s.formatTable(block, TableStyleCommands.apply(TableStyleCommands.spec(s.document(), "TableGrid"), t.format.withProp("style", "TableGrid").withProp("look", "none")));
          return block;
        });
  }

  private static Document item(List<Document> items, int index) {
    if (index < 0 || index >= items.size()) throw new IllegalArgumentException("No clipboard item " + index);
    return items.get(index);
  }

  private static void pasteSpecial(Session s, String format, Map<String, Object> a) {
    Document fragment;
    if (a.containsKey("data")) {
      String data = Commands.str(a, "data");
      if ("text".equals(format)) {
        s.pasteText(data);
        return;
      }
      com.codename1.ui.RichTextFormat f =
          "html".equals(format) ? com.codename1.ui.RichTextFormat.HTML
              : "rtf".equals(format) ? com.codename1.ui.RichTextFormat.RTF
              : "markdown".equals(format) ? com.codename1.ui.RichTextFormat.MARKDOWN : null;
      if (f == null) throw new IllegalArgumentException("Data is pasted as text, html, rtf or markdown");
      fragment = TextFormats.read(data, f);
    } else fragment = item(clipboard(s), Commands.integer(a, "index", 0));
    if ("text".equals(format)) {
      s.pasteText(fragment.plainText());
      return;
    }
    if ("merge".equals(format)) {
      final var target = s.activeStyle();
      var blocks = new Sequence<Block>();
      for (Block b : fragment.blocks)
        blocks =
            blocks.append(
                b instanceof Paragraph p
                    ? p
                        .text(
                            p
                                .text.mapStyles(
                                    t ->
                                        target
                                            .with("bold", String.valueOf(t.bold))
                                            .with("italic", String.valueOf(t.italic))
                                            .with("underline", String.valueOf(t.underline))
                                            .with("strike", String.valueOf(t.strike))))
                    : b);
      fragment = fragment.blocks(blocks, fragment.nextId);
    } else if (!Arrays.asList("keepSource", "html", "rtf", "markdown").contains(format))
      throw new IllegalArgumentException("Format is keepSource, merge, text, html, rtf or markdown");
    s.paste(fragment);
  }

  private static Object section(Session s, int delta) {
    List<Section.Range> ranges = s.document().sectionRanges();
    var active = s.activeSection();
    int at = 0;
    for (int i = 0; i < ranges.size(); i++) if (ranges.get(i).first == active.first) at = i;
    int to = at + delta;
    if (to < 0 || to >= ranges.size())
      throw new IllegalArgumentException(delta < 0 ? "This is the first section" : "This is the last section");
    var r = ranges.get(to);
    if (s.cellBlock() >= 0) s.leaveCell();
    if (s.document().blocks.get(r.first) instanceof Table) s.enterCell(r.first, 0, 0);
    else s.select(r.first, 0, 0);
    var out = new LinkedHashMap<String, Object>();
    out.put("section", to);
    var header = new StringBuilder();
    for (Paragraph p : r.resolved.furniture.allParagraphs("header")) header.append(header.length() == 0 ? "" : "\n").append(p.text.plainText());
    var footer = new StringBuilder();
    for (Paragraph p : r.resolved.furniture.allParagraphs("footer")) footer.append(footer.length() == 0 ? "" : "\n").append(p.text.plainText());
    out.put("header", header.toString());
    out.put("footer", footer.toString());
    return out;
  }

  private static boolean same(Selection a, Selection b) {
    return a.anchorBlock == b.anchorBlock && a.anchor == b.anchor && a.block == b.block && a.caret == b.caret;
  }

  private static boolean covers(Selection outer, Selection inner) {
    long os = (long) outer.startBlock() << 32 | outer.start(), oe = (long) outer.endBlock() << 32 | outer.end();
    long is = (long) inner.startBlock() << 32 | inner.start(), ie = (long) inner.endBlock() << 32 | inner.end();
    return os <= is && oe >= ie;
  }

  private static boolean similar(TextStyle a, TextStyle b) {
    return a.font.equals(b.font) && a.size == b.size && a.bold == b.bold && a.italic == b.italic
        && a.underline == b.underline && a.strike == b.strike && a.color == b.color
        && a.background == b.background && a.script.equals(b.script);
  }

  private static int literal(String text, int at, String p, boolean matchCase) {
    if (at + p.length() > text.length()) return -1;
    for (int i = 0; i < p.length(); i++) {
      char a = text.charAt(at + i), b = p.charAt(i);
      if (a != b && (matchCase || Character.toLowerCase(a) != Character.toLowerCase(b))) return -1;
    }
    return at + p.length();
  }

  private static boolean boundary(String text, int i) {
    return i < 0 || i >= text.length() || !Unicode.letterOrDigit(text.charAt(i));
  }

  /** Word wildcard match of pattern[pi..] at text[at..]; returns the end offset or -1. */
  static int wildcard(String text, int at, String p, int pi) {
    if (pi == p.length()) return at;
    char c = p.charAt(pi);
    if (c == '*') {
      for (int end = at; end <= text.length(); end++) {
        int r = wildcard(text, end, p, pi + 1);
        if (r >= 0) return r;
      }
      return -1;
    }
    if (c == '<') return boundary(text, at - 1) && at < text.length() && !boundary(text, at) ? wildcard(text, at, p, pi + 1) : -1;
    if (c == '>') return at > 0 && !boundary(text, at - 1) && boundary(text, at) ? wildcard(text, at, p, pi + 1) : -1;
    if (at >= text.length()) return -1;
    char t = text.charAt(at);
    if (c == '?') return wildcard(text, at + 1, p, pi + 1);
    if (c == '[') {
      int close = p.indexOf(']', pi + 1);
      if (close < 0) throw new IllegalArgumentException("Unclosed [ in wildcard pattern");
      var set = p.substring(pi + 1, close);
      boolean negate = set.startsWith("!");
      if (negate) set = set.substring(1);
      boolean in = false;
      for (int i = 0; i < set.length(); i++)
        if (i + 2 < set.length() && set.charAt(i + 1) == '-') {
          if (t >= set.charAt(i) && t <= set.charAt(i + 2)) in = true;
          i += 2;
        } else if (set.charAt(i) == t) in = true;
      return in != negate ? wildcard(text, at + 1, p, close + 1) : -1;
    }
    if (c == '\\' && pi + 1 < p.length()) {
      return t == p.charAt(pi + 1) ? wildcard(text, at + 1, p, pi + 2) : -1;
    }
    return t == c ? wildcard(text, at + 1, p, pi + 1) : -1;
  }

  private static ParagraphStyle style(Document d, String id) {
    var st = d.styles.get(id);
    if (st == null) {
      for (ParagraphStyle candidate : d.styles.values()) if (candidate.name.equals(id)) return candidate;
      throw new IllegalArgumentException("Unknown style: " + id);
    }
    return st;
  }

  private static List<String> ordered(Document d) {
    var out = new ArrayList<String>();
    for (String id : TextUtil.split(d.properties.setting("styles.order", ""), ','))
      if (d.styles.containsKey(id) && !out.contains(id)) out.add(id);
    for (String id : d.styles.keySet()) if (!out.contains(id)) out.add(id);
    return out;
  }

  private static String join(List<String> items) {
    var b = new StringBuilder();
    for (String i : items) b.append(b.length() == 0 ? "" : ",").append(i);
    return b.toString();
  }

  private static String paragraphValue(Paragraph p, String k) {
    if ("align".equals(k)) return p.align;
    if ("before".equals(k)) return String.valueOf(p.before);
    if ("after".equals(k)) return String.valueOf(p.after);
    if ("indent".equals(k)) return String.valueOf(p.indent);
    if ("spacing".equals(k)) return String.valueOf(p.lineSpacing);
    if ("rightIndent".equals(k)) return String.valueOf(p.format.rightIndent);
    return String.valueOf(p.format.firstIndent);
  }

  private static String textValue(TextStyle t, String k) {
    if ("font".equals(k)) return t.font;
    if ("size".equals(k)) return String.valueOf(t.size);
    if ("color".equals(k)) return TextUtil.padded(t.color, 16, 6);
    if ("bold".equals(k)) return String.valueOf(t.bold);
    if ("italic".equals(k)) return String.valueOf(t.italic);
    if ("underline".equals(k)) return String.valueOf(t.underline);
    return String.valueOf(t.strike);
  }

  private static Object coverPage(Session s, Map<String, Object> a) {
    var d = s.document();
    var ids = d.properties.setting("coverPage.ids", "");
    var rest = new Sequence<Block>();
    var old = Arrays.asList(TextUtil.split(ids, ','));
    for (Block b : d.blocks) if (!old.contains(b.id())) rest = rest.append(b);
    if (Boolean.TRUE.equals(a.get("remove"))) {
      if (ids.isEmpty()) throw new IllegalArgumentException("The document has no cover page");
      if (rest.size() > 0 && rest.get(0) instanceof Paragraph)
        rest = rest.set(0, ((Paragraph) rest.get(0)).with("pageBreak", "false"));
      s.replaceDocument(d.blocks(rest, d.nextId).properties(d.properties.withSetting("coverPage.ids", "")));
      return null;
    }
    String design = Commands.str(a, "design", "Austin");
    String[] spec = null;
    for (String[] c : COVERS) if (c[0].equalsIgnoreCase(design)) spec = c;
    if (spec == null) throw new IllegalArgumentException("Unknown cover page design: " + design);
    long id = d.nextId;
    String title = a.containsKey("title") ? Commands.str(a, "title") : d.properties.get("title").isEmpty() ? "[Document title]" : d.properties.get("title");
    String subtitle = a.containsKey("subtitle") ? Commands.str(a, "subtitle") : d.properties.get("subject").isEmpty() ? "[Document subtitle]" : d.properties.get("subject");
    String author = a.containsKey("author") ? Commands.str(a, "author") : d.properties.get("author").isEmpty() ? "[Author name]" : d.properties.get("author");
    String date = Commands.str(a, "date", "[Date]");
    int fg = Integer.parseInt(spec[1], 16);
    var titleStyle = TextStyle.DEFAULT.with("size", spec[3]).with("color", spec[1]).with("bold", "true");
    var sub = TextStyle.DEFAULT.with("size", "18").with("color", fg == 0xffffff ? "ffffff" : "595959");
    var meta = TextStyle.DEFAULT.with("size", "12").with("color", "595959");
    Paragraph[] cover = {
      new Paragraph("p" + id++, RichText.of(title, titleStyle)).with("before", "144").with("after", "12"),
      new Paragraph("p" + id++, RichText.of(subtitle, sub)).with("after", "216"),
      new Paragraph("p" + id++, RichText.of(author, meta)).with("after", "0"),
      new Paragraph("p" + id++, RichText.of(date, meta)).with("after", "0")
    };
    if (!"none".equals(spec[2])) {
      cover[0] = cover[0].with("shading", spec[2]);
      cover[1] = cover[1].with("shading", spec[2]);
    }
    if ("Grid".equals(spec[0]) || "Sideline".equals(spec[0]))
      cover[0] = cover[0].with("borders", "bottom=1.5," + spec[1] + ",4,single");
    var blocks = new Sequence<Block>();
    var newIds = new StringBuilder();
    for (Paragraph p : cover) {
      blocks = blocks.append(p);
      newIds.append(newIds.length() == 0 ? "" : ",").append(p.id);
    }
    for (int i = 0; i < rest.size(); i++) {
      var b = rest.get(i);
      if (i == 0 && b instanceof Paragraph paragraph) b = paragraph.with("pageBreak", "true");
      blocks = blocks.append(b);
    }
    s.replaceDocument(d.blocks(blocks, id).properties(d.properties.withSetting("coverPage.ids", newIds.toString())));
    return spec[0];
  }

  /** App-wide galleries (this run), mirrored into document settings so they travel with it. */
  private static final Map<String, String> GALLERY = new LinkedHashMap<>();

  private static void gallery(Commands c, String id, final String prefix, String description) {
    c.register(
        id,
        description,
        "action?:string,name?:string",
        true,
        (s, a) -> {
          String action = Commands.str(a, "action", "list");
          if ("list".equals(action)) return entries(s, prefix);
          var name = Commands.str(a, "name").trim();
          if (name.isEmpty() || name.length() > 40) throw new IllegalArgumentException("Entry name must be 1-40 characters");
          var key = new StringBuilder(prefix);
          for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch > 127 || !(Unicode.letterOrDigit(ch) || ch == ' ')) throw new IllegalArgumentException("Names use letters, digits and spaces");
            key.append(ch == ' ' ? '_' : ch);
          }
          var k = key.toString();
          if ("save".equals(action)) {
            var fragment = s.copySelection();
            if (fragment == null) throw new IllegalArgumentException("Select the content to save");
            String xml = DocumentXml.write(fragment);
            synchronized (GALLERY) {
              GALLERY.put(k, xml);
            }
            s.documentProperties(s.document().properties.withSetting(k, xml));
          } else if ("insert".equals(action)) {
            var xml = s.document().properties.setting(k, "");
            if (xml.isEmpty())
              synchronized (GALLERY) {
                xml = GALLERY.containsKey(k) ? GALLERY.get(k) : "";
              }
            if (xml.isEmpty()) throw new IllegalArgumentException("No gallery entry " + name);
            try {
              s.paste(DocumentXml.read(xml));
            } catch (IOException e) {
              throw new IllegalArgumentException("Damaged gallery entry " + name);
            }
          } else if ("delete".equals(action)) {
            synchronized (GALLERY) {
              GALLERY.remove(k);
            }
            s.documentProperties(s.document().properties.withSetting(k, ""));
          } else throw new IllegalArgumentException("Action is list, save, insert or delete");
          return entries(s, prefix);
        });
  }

  private static List<String> entries(Session s, String prefix) {
    var out = new ArrayList<String>();
    for (String k : s.document().properties.settings().keySet())
      if (k.startsWith(prefix)) out.add(k.substring(prefix.length()).replace('_', ' '));
    synchronized (GALLERY) {
      for (String k : GALLERY.keySet())
        if (k.startsWith(prefix) && !out.contains(k.substring(prefix.length()).replace('_', ' ')))
          out.add(k.substring(prefix.length()).replace('_', ' '));
    }
    return out;
  }
}
