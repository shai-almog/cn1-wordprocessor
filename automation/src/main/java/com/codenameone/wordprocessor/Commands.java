package com.codenameone.wordprocessor;

import java.util.*;

/** Version 1 extension API. All handlers operate on the same session as the UI. */
public final class Commands {
  public interface Handler {
    Object execute(Session s, Map<String, Object> args);
  }

  public interface Extension {
    void register(Commands commands);
  }

  public static final class Descriptor {
    public final String id, description;
    public final Map<String, String> parameters;
    public final Set<String> required;
    final Handler handler;
    final boolean transactional;

    Descriptor(
        String id,
        String description,
        Map<String, String> params,
        Set<String> required,
        boolean tx,
        Handler handler) {
      this.id = id;
      this.description = description;
      parameters = Collections.unmodifiableMap(new LinkedHashMap<String, String>(params));
      this.required = Collections.unmodifiableSet(new HashSet<String>(required));
      transactional = tx;
      this.handler = handler;
    }

    public Map<String, Object> schema() {
      var props = new LinkedHashMap<String, Object>();
      for (Map.Entry<String, String> e : parameters.entrySet())
        // "any" accepts null or any JSON value, as Wordcraft's nullable arguments do.
        props.put(
            e.getKey(),
            "any".equals(e.getValue())
                ? new LinkedHashMap<String, Object>()
                : Json.object("type", e.getValue()));
      return Json.object(
          "type",
          "object",
          "properties",
          props,
          "required",
          new ArrayList<String>(required),
          "additionalProperties",
          false);
    }
  }

  private final LinkedHashMap<String, Descriptor> commands =
      new LinkedHashMap<>();

  private LayoutEngine layoutEngine;

  public Commands() {
    this(new LayoutEngine(new PdfMetrics()));
  }

  public Commands(LayoutEngine engine) {
    setLayoutEngine(engine);
    builtins();
    new QualityExtension().register(this);
    new TemplateExtension().register(this);
    new MailingsCommands().register(this);
    new TableLayoutCommands().register(this);
    new ComparisonCommands().register(this);
    new CatalogCommands().register(this);
    new CatalogNavigationCommands().register(this);
    new PictureCommands().register(this);
    new CatalogEditingCommands().register(this);
    new FileCommands().register(this);
    new TableTextCommands().register(this);
    new DrawingCommands().register(this);
    new DeveloperCommands().register(this);
    new LayoutDesignCommands().register(this);
    new TableStyleCommands().register(this);
    new WorkflowCommands().register(this);
    new ProofingCommands().register(this);
    new OfficeCommands().register(this);
    new WordAliasCommands().register(this);
  }

  /** Bind automation to the host's print layout, independent of viewport reflow. */
  public void setLayoutEngine(LayoutEngine engine) {
    if (engine == null) throw new IllegalArgumentException("A layout engine is required");
    layoutEngine = engine;
  }

  LayoutEngine layoutEngine() {
    return layoutEngine;
  }

  public void register(
      String id, String description, String spec, boolean transactional, Handler handler) {
    if (commands.containsKey(id) || !TextUtil.commandId(id))
      throw new IllegalArgumentException("Duplicate/invalid command " + id);
    var params = new LinkedHashMap<String, String>();
    var required = new HashSet<String>();
    if (!spec.isEmpty())
      for (String p : TextUtil.split(spec, ',')) {
        String[] kv = TextUtil.split(p, ':');
        var key = kv[0];
        if (!key.endsWith("?")) required.add(key);
        else key = key.substring(0, key.length() - 1);
        params.put(key, kv[1]);
      }
    commands.put(id, new Descriptor(id, description, params, required, transactional, handler));
  }

  /** Registers an undoable editing command. */
  public void edit(String id, String spec, String description, Handler handler) {
    register(id, description, spec, true, handler);
  }

  /** Registers a command that reads state or changes settings outside the undo history. */
  public void query(String id, String spec, String description, Handler handler) {
    register(id, description, spec, false, handler);
  }

  public Collection<Descriptor> descriptors() {
    return Collections.unmodifiableCollection(commands.values());
  }

  /** The last successful editing command, for edit.repeat. */
  String lastId;

  /** Top-level transactional command observers (the macro recorder); nested calls are not reported. */
  public interface Listener {
    void executed(Session session, String id, Map<String, Object> args);
  }

  final List<Listener> listeners = new java.util.ArrayList<>();

  private int depth;

  Map<String, Object> lastArgs;

  /** Caret {block, offset} after the most recent edits, newest last, for edit.goBack. */
  final List<int[]> editSpots = new java.util.ArrayList<>();

  /** Observes every outermost execute call; used by tests to record native replay cases. */
  public interface Tracer {
    Object begin(Session session, String id, Map<String, Object> args);

    void end(Object token, Session session, Object result, Throwable error);
  }

  public static Tracer tracer;

  private int traceDepth;

  public Object execute(final Session session, String id, final Map<String, Object> args) {
    var t = tracer;
    if (t == null || traceDepth > 0) return run(session, id, args);
    var token = t.begin(session, id, args);
    traceDepth++;
    Object result;
    try {
      result = run(session, id, args);
    } catch (RuntimeException ex) {
      traceDepth--;
      t.end(token, session, null, ex);
      throw ex;
    }
    traceDepth--;
    t.end(token, session, result, null);
    return result;
  }

  private Object run(final Session session, String id, final Map<String, Object> args) {
    final var d = commands.get(id);
    if (d == null) throw new IllegalArgumentException("Unknown command: " + id);
    for (String required : d.required)
      if (!args.containsKey(required))
        throw new IllegalArgumentException("Missing argument: " + required);
    for (Map.Entry<String, Object> e : args.entrySet()) {
      var type = d.parameters.get(e.getKey());
      if (type == null) throw new IllegalArgumentException("Unknown argument: " + e.getKey());
      var v = e.getValue();
      if ("string".equals(type) && !(v instanceof String)
          || "integer".equals(type)
              && (!(v instanceof Number) || ((Number) v).doubleValue() != ((Number) v).intValue())
          || "number".equals(type)
              && (!(v instanceof Number)
                  || Double.isNaN(((Number) v).doubleValue())
                  || Double.isInfinite(((Number) v).doubleValue()))
          || "object".equals(type) && !(v instanceof Map)
          || "array".equals(type) && !(v instanceof List)
          || "boolean".equals(type) && !(v instanceof Boolean))
        throw new IllegalArgumentException("Wrong type for " + e.getKey());
    }
    if (!d.transactional) return d.handler.execute(session, args);
    final Object[] result = new Object[1];
    depth++;
    try {
      session.transact(s -> result[0] = d.handler.execute(s, args));
    } finally {
      depth--;
    }
    if (depth == 0)
      for (Listener l : new java.util.ArrayList<Listener>(listeners)) l.executed(session, id, args);
    if (!id.startsWith("edit.undo") && !id.startsWith("edit.redo")) {
      lastId = id;
      lastArgs = new java.util.LinkedHashMap<>(args);
    }
    var x = session.selection();
    if (editSpots.isEmpty()
        || editSpots.get(editSpots.size() - 1)[0] != x.block
        || editSpots.get(editSpots.size() - 1)[1] != x.caret) {
      editSpots.add(new int[] {x.block, x.caret});
      if (editSpots.size() > 3) editSpots.remove(0);
    }
    return result[0];
  }

  public Object execute(Session s, String id) {
    return execute(s, id, Collections.<String, Object>emptyMap());
  }

  public static String str(Map<String, Object> a, String k) {
    return (String) a.get(k);
  }

  public static int integer(Map<String, Object> a, String k) {
    return ((Number) a.get(k)).intValue();
  }

  private static Map<String, Integer> contentsStyleLevels(Map<?, ?> source) {
    var result = new LinkedHashMap<String, Integer>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Number))
        throw new IllegalArgumentException("Style mappings require names and integer levels");
      var value = (Number) entry.getValue();
      if (value.doubleValue() != value.intValue())
        throw new IllegalArgumentException("Invalid style level");
      result.put((String) entry.getKey(), value.intValue());
    }
    return result;
  }

  private static Map<String, Object> noteProperties(NoteOptions.Properties p) {
    return Json.object(
        "numFmt", p.numFmt, "numStart", p.numStart, "numRestart", p.numRestart, "pos", p.pos);
  }

  private void builtins() {
    query("document.inspect", "",
        "Inspect document structure and selection", (s, a) -> inspect(s));
    query("document.text", "",
        "Return document text", (s, a) -> s.document().plainText());
    edit("document.title", "title:string",
        "Set document title", (s, a) -> {
          s.leaveCell();
          var x = s.selection();
          s.replaceDocument(s.document().title(str(a, "title")));
          s.selectRange(x.anchorBlock, x.anchor, x.block, x.caret);
          return null;
        });
    query("selection.set", "block:integer,start:integer,end:integer,endBlock?:integer",
        "Select a UTF-16 range across paragraphs", (s, a) -> {
          s.leaveCell();
          s.selectRange(
              integer(a, "block"),
              integer(a, "start"),
              a.containsKey("endBlock") ? integer(a, "endBlock") : integer(a, "block"),
              integer(a, "end"));
          return null;
        });
    query(
        "selection.cell", "block:integer,row:integer,column:integer,paragraph:integer,start:integer,end:integer",
        "Select rich text in a table cell", (s, a) -> {
          s.enterCell(integer(a, "block"), integer(a, "row"), integer(a, "column"));
          s.select(integer(a, "paragraph"), integer(a, "start"), integer(a, "end"));
          return null;
        });
    edit("text.insert", "text:string",
        "Replace selection with text", (s, a) -> {
          String text = str(a, "text");
          if (text.length() > 1024 * 1024) throw new IllegalArgumentException("Text exceeds 1 MiB");
          s.replace(text);
          return null;
        });
    edit("text.backspace", "",
        "Delete selection or previous grapheme cluster", (s, a) -> {
          s.deleteBackward();
          return null;
        });
    edit("format.changeCase", "mode?:string",
        "Change selected text case or the word around the caret", (s, a) -> {
          s.changeCase(a.containsKey("mode") ? str(a, "mode") : null);
          return null;
        });
    edit("format.set", "property:string,value:string",
        "Apply character formatting to selection", (s, a) -> {
          String k = str(a, "property");
          if (!Arrays.asList(
                  "bold",
                  "italic",
                  "underline",
                  "strike",
                  "doubleUnderline",
                  "doubleStrike",
                  "smallCaps",
                  "allCaps",
                  "hidden",
                  "characterSpacing",
                  "characterScale",
                  "characterPosition",
                  "underlineStyle",
                  "underlineColor",
                  "outline",
                  "shadow",
                  "emboss",
                  "engrave",
                  "shading",
                  "border",
                  "lang",
                  "ruby",
                  "enclose",
                  "script",
                  "font",
                  "size",
                  "color",
                  "background",
                  "link")
              .contains(k)) throw new IllegalArgumentException("Unknown format property");
          s.format(k, str(a, "value"));
          return null;
        });
    query("styles.list", "",
        "List document paragraph styles", (s, a) -> {
          var result = new ArrayList<Object>();
          for (ParagraphStyle style : s.document().styles.values())
            result.add(
                Json.object(
                    "id",
                    style.id,
                    "name",
                    style.name,
                    "font",
                    style.text.font,
                    "size",
                    style.text.size));
          return result;
        });
    edit("styles.apply", "id:string",
        "Apply a named style", (s, a) -> {
          s.applyStyle(str(a, "id"));
          return null;
        });
    edit("styles.create", "id:string,name:string",
        "Create a style from the selection", (s, a) -> {
          String id = str(a, "id");
          if (s.document().styles.containsKey(id))
            throw new IllegalArgumentException("Style already exists");
          s.defineStyle(
              new ParagraphStyle(
                  id,
                  str(a, "name"),
                  s.editingDocument().paragraph(s.selection().block),
                  s.activeStyle()));
          return null;
        });
    edit("styles.updateToMatch", "id:string",
        "Update a named style from the selection", (s, a) -> {
          var old = s.document().styles.get(str(a, "id"));
          if (old == null) throw new IllegalArgumentException("Unknown style");
          s.defineStyle(
              new ParagraphStyle(
                  old.id,
                  old.name,
                  s.editingDocument().paragraph(s.selection().block),
                  s.activeStyle()));
          return null;
        });
    edit("styles.modify", "id:string,property:string,value:string",
        "Modify a style definition and update its paragraphs", (s, a) -> {
          var old = s.document().styles.get(str(a, "id"));
          if (old == null) throw new IllegalArgumentException("Unknown style");
          String key = str(a, "property"), value = str(a, "value");
          if ("name".equals(key))
            s.defineStyle(new ParagraphStyle(old.id, value, old.paragraph, old.text));
          else if (Arrays.asList(
                  "font",
                  "size",
                  "color",
                  "bold",
                  "italic",
                  "underline",
                  "strike",
                  "doubleUnderline",
                  "doubleStrike",
                  "smallCaps",
                  "allCaps",
                  "hidden",
                  "characterSpacing",
                  "characterScale",
                  "characterPosition",
                  "underlineStyle",
                  "underlineColor",
                  "outline",
                  "shadow",
                  "emboss",
                  "engrave",
                  "background",
                  "script")
              .contains(key))
            s.defineStyle(
                new ParagraphStyle(old.id, old.name, old.paragraph, old.text.with(key, value)));
          else if (Arrays.asList(
                  "align",
                  "before",
                  "after",
                  "indent",
                  "spacing",
                  "lineRule",
                  "keepNext",
                  "rightIndent",
                  "firstIndent",
                  "tabInterval",
                  "tabs",
                  "keepLines",
                  "widowControl")
              .contains(key))
            s.defineStyle(
                new ParagraphStyle(old.id, old.name, old.paragraph.with(key, value), old.text));
          else throw new IllegalArgumentException("Unknown style property");
          return null;
        });
    edit(
        "layout.columns", "count?:number,preset?:string,space?:number,separator?:boolean,apply?:string",
        "Set newspaper columns: count 1-12 or preset one/two/three/left/right, optional space (pt), separator, and apply section/document/forward",
        (s, a) -> {
          float space = a.containsKey("space") ? ((Number) a.get("space")).floatValue() : 36;
          boolean separator = a.containsKey("separator") && Boolean.TRUE.equals(a.get("separator"));
          Columns c =
              a.containsKey("preset")
                  ? Columns.preset(
                      str(a, "preset"), s.activeSection().raw.page.contentWidth(), space)
                  : Columns.equal(a.containsKey("count") ? integer(a, "count") : 1, space, false);
          c = new Columns(c.count, c.space, separator, c.widths());
          s.columns(c, a.containsKey("apply") ? str(a, "apply") : "section");
          return null;
        });
    edit("layout.columnBreak", "",
        "Split at the caret and continue in the next newspaper column", (s, a) -> {
          s.columnBreak();
          return null;
        });
    edit("layout.break", "kind:string",
        "Insert a section break before the selection", (s, a) -> {
          s.sectionBreak(str(a, "kind"));
          return null;
        });
    edit("headerFooter.set", "story:string,text:string,align?:string",
        "Replace a header/footer variant with styled paragraphs", (s, a) -> {
          String key = str(a, "story");
          PageFurniture.check(key);
          var paragraphs = new Sequence<Paragraph>();
          String text = str(a, "text");
          String[] lines = TextUtil.split(text, '\n');
          if (!text.isEmpty())
            for (int i = 0; i < lines.length; i++)
              paragraphs =
                  paragraphs.append(
                      new Paragraph("part" + i, RichText.of(lines[i]))
                          .with("after", "0")
                          .with("align", a.containsKey("align") ? str(a, "align") : "left"));
          s.furniture(s.activeSection().raw.furniture.story(key, paragraphs));
          return null;
        });
    for (final boolean figures : new boolean[] {false, true})
      register(
          figures ? "references.tableOfFigures" : "references.toc",
          figures
              ? "Insert or configure a table of figures"
              : "Insert or configure a table of contents",
          (figures ? "label?:string," : "")
              + "levels?:integer,title?:string,pageNumbers?:boolean,rightAlign?:boolean,tabLeader?:string,hyperlinks?:boolean,headingStyles?:boolean,outlineLevels?:boolean,tcFields?:boolean,styleLevels?:object,tcIdentifier?:string",
          true,
          (s, a) -> {
            String caption = figures ? (a.containsKey("label") ? str(a, "label") : "Figure") : null;
            TableContents.Options old = TableContents.options(s.document(), caption);
            var options =
                new TableContents.Options(
                    a.containsKey("levels") ? integer(a, "levels") : old.levels,
                    a.containsKey("title") ? str(a, "title") : old.title,
                    a.containsKey("pageNumbers")
                        ? Boolean.TRUE.equals(a.get("pageNumbers"))
                        : old.pageNumbers,
                    a.containsKey("rightAlign")
                        ? Boolean.TRUE.equals(a.get("rightAlign"))
                        : old.rightAlign,
                    a.containsKey("tabLeader") ? str(a, "tabLeader") : old.leader,
                    a.containsKey("hyperlinks")
                        ? Boolean.TRUE.equals(a.get("hyperlinks"))
                        : old.hyperlinks,
                    caption,
                    a.containsKey("headingStyles")
                        ? Boolean.TRUE.equals(a.get("headingStyles"))
                        : old.headingStyles,
                    a.containsKey("outlineLevels")
                        ? Boolean.TRUE.equals(a.get("outlineLevels"))
                        : old.outlineLevels,
                    a.containsKey("tcFields")
                        ? Boolean.TRUE.equals(a.get("tcFields"))
                        : old.tcFields,
                    a.containsKey("styleLevels")
                        ? contentsStyleLevels((Map<?, ?>) a.get("styleLevels"))
                        : old.styleLevels,
                    a.containsKey("tcIdentifier") ? str(a, "tcIdentifier") : old.tcIdentifier);
            var engine = layoutEngine;
            TableContents.insert(s, options, d -> engine.layout(d, s.revision()));
            return null;
          });
    edit("references.addText", "level?:integer",
        "Set the selected paragraphs' outline level", (s, a) -> {
          s.paragraph("outlineLevel", "" + (a.containsKey("level") ? integer(a, "level") : 0));
          return null;
        });
    edit("references.sources", "add?:object,remove?:string",
        "Add, edit, delete or list document sources", (s, a) -> {
          if (a.containsKey("add")) Citations.source(s, citationSource((Map<?, ?>) a.get("add")));
          if (a.containsKey("remove")) Citations.removeSource(s, str(a, "remove"));
          var result = new ArrayList<Object>();
          for (CitationSource source : s.document().bibliography.sources.values())
            result.add(source.values);
          return result;
        });
    edit("references.citation", "tag?:string,source?:object,pages?:string",
        "Insert a source citation", (s, a) ->
            Json.object(
                "tag",
                Citations.insert(
                    s,
                    a.containsKey("tag") ? str(a, "tag") : null,
                    a.containsKey("source") ? citationSource((Map<?, ?>) a.get("source")) : null,
                    a.containsKey("pages") ? str(a, "pages") : "")));
    edit("references.citationStyle", "style:string",
        "Change citation style and refresh results", (s, a) -> {
          Citations.style(s, str(a, "style"));
          return Json.object("style", s.document().bibliography.style);
        });
    edit("references.bibliography", "title?:string",
        "Insert or replace the bibliography", (s, a) -> {
          Citations.bibliography(s, a.containsKey("title") ? str(a, "title") : null);
          return null;
        });
    edit("references.updateBibliography", "",
        "Refresh citations and bibliography", (s, a) -> {
          Citations.update(s);
          return null;
        });
    edit("references.removeBibliography", "",
        "Remove bibliography, keeping sources and citations", (s, a) -> {
          Citations.remove(s);
          return null;
        });
    edit("references.markCitation", "entry?:string",
        "Mark an authority citation after the selection", (s, a) -> {
          String entry = a.containsKey("entry") ? str(a, "entry") : s.selectedText();
          TableAuthorities.mark(s, entry);
          return Json.object("entry", entry);
        });
    edit("references.tableOfAuthorities", "title?:string,tabLeader?:string",
        "Insert or configure a table of authorities", (s, a) -> {
          DocumentIndex.Options old = TableAuthorities.options(s.document());
          TableAuthorities.insert(
              s,
              a.containsKey("title") ? str(a, "title") : old.title,
              a.containsKey("tabLeader") ? str(a, "tabLeader") : old.leader,
              d -> layoutEngine.layout(d, s.revision()));
          return null;
        });
    edit("references.updateAuthorities", "",
        "Update the table of authorities", (s, a) -> {
          TableAuthorities.update(s, d -> layoutEngine.layout(d, s.revision()));
          return null;
        });
    edit("references.removeAuthorities", "",
        "Remove the table and retain source citations", (s, a) -> {
          TableAuthorities.remove(s);
          return null;
        });
    edit(
        "references.markEntry", "entry?:string,subentry?:string,crossReference?:string,bookmark?:string,bold?:boolean,italic?:boolean,all?:boolean,text?:string",
        "Mark an index entry without removing the selection", (s, a) -> {
          String entry = a.containsKey("entry") ? str(a, "entry") : s.selectedText();
          var selected = s.selectedText().trim();
          int marked =
              DocumentIndex.mark(
                  s,
                  entry,
                  a.containsKey("subentry") ? str(a, "subentry") : "",
                  a.containsKey("crossReference") ? str(a, "crossReference") : "",
                  a.containsKey("bookmark") ? str(a, "bookmark") : "",
                  Boolean.TRUE.equals(a.get("bold")),
                  Boolean.TRUE.equals(a.get("italic")),
                  Boolean.TRUE.equals(a.get("all")),
                  a.containsKey("text") ? str(a, "text") : selected.isEmpty() ? entry : selected);
          return Json.object("entry", entry, "marked", marked);
        });
    edit(
        "references.index", "title?:string,type?:string,columns?:integer,headings?:boolean,rightAlign?:boolean,tabLeader?:string",
        "Insert or configure the index", (s, a) -> {
          if (a.containsKey("columns") && integer(a, "columns") != 1)
            throw new IllegalArgumentException("Multiple index columns are not implemented yet");
          if (a.containsKey("type") && !Arrays.asList("indented", "runIn").contains(str(a, "type")))
            throw new IllegalArgumentException("Unknown index type");
          DocumentIndex.Options old = DocumentIndex.options(s.document());
          DocumentIndex.insert(
              s,
              new DocumentIndex.Options(
                  a.containsKey("title") ? str(a, "title") : old.title,
                  a.containsKey("type") ? "runIn".equals(str(a, "type")) : old.runIn,
                  a.containsKey("headings") ? Boolean.TRUE.equals(a.get("headings")) : old.headings,
                  a.containsKey("rightAlign")
                      ? Boolean.TRUE.equals(a.get("rightAlign"))
                      : old.rightAlign,
                  a.containsKey("tabLeader") ? str(a, "tabLeader") : old.leader),
              d -> layoutEngine.layout(d, s.revision()));
          return null;
        });
    edit("references.updateIndex", "",
        "Update index entries and page references", (s, a) -> {
          DocumentIndex.update(s, d -> layoutEngine.layout(d, s.revision()));
          return null;
        });
    edit("references.removeIndex", "",
        "Remove the index and retain its source marks", (s, a) -> {
          DocumentIndex.remove(s);
          return null;
        });
    edit("references.markToc", "text?:string,level?:integer,identifier?:string",
        "Mark selected text as a contents entry without removing it", (s, a) -> {
          TableContents.markEntry(
              s,
              a.containsKey("text") ? str(a, "text") : s.selectedText(),
              a.containsKey("level") ? integer(a, "level") : 1,
              a.containsKey("identifier") ? str(a, "identifier") : "C");
          return null;
        });
    edit("references.updateToc", "",
        "Update the table of contents", (s, a) -> {
          var engine = layoutEngine;
          TableContents.update(s, d -> engine.layout(d, s.revision()));
          return null;
        });
    edit("references.removeToc", "",
        "Remove the table of contents", (s, a) -> {
          TableContents.remove(s);
          return null;
        });
    edit("references.updateFigures", "",
        "Update tables of figures", (s, a) -> {
          var engine = layoutEngine;
          TableContents.updateFigures(s, d -> engine.layout(d, s.revision()));
          return null;
        });
    edit("references.removeFigures", "label?:string",
        "Remove a table of figures", (s, a) -> {
          TableContents.remove(s, a.containsKey("label") ? str(a, "label") : "Figure");
          return null;
        });
    edit(
        "references.noteOptions", "kind?:string,scope?:string,numFmt?:string,numStart?:integer,numRestart?:string,pos?:string,footnoteFormat?:string,endnoteFormat?:string",
        "Set note numbering, placement and section overrides", (s, a) -> {
          String kind = a.containsKey("kind") ? str(a, "kind") : "footnote";
          String scope = a.containsKey("scope") ? str(a, "scope") : "document";
          if (!Arrays.asList("document", "section").contains(scope))
            throw new IllegalArgumentException("Unknown note scope");
          int start = a.containsKey("numStart") ? integer(a, "numStart") : 0;
          if (a.containsKey("numStart") && start < 1)
            throw new IllegalArgumentException("Note start must be 1 through 32767");
          s.noteOptions(
              kind,
              new NoteOptions.Properties(
                  a.containsKey("numFmt") ? str(a, "numFmt") : null,
                  start,
                  a.containsKey("numRestart") ? str(a, "numRestart") : null,
                  a.containsKey("pos") ? str(a, "pos") : null),
              "document".equals(scope));
          for (String k : new String[] {"footnote", "endnote"})
            if (a.containsKey(k + "Format"))
              s.noteOptions(
                  k, new NoteOptions.Properties(str(a, k + "Format"), 0, null, null), true);
          var options = s.activeSection().resolved.notes;
          return Json.object(
              "footnote",
              noteProperties(options.footnote),
              "endnote",
              noteProperties(options.endnote),
              "footnoteFormat",
              s.document().notes.footnote.numFmt,
              "endnoteFormat",
              s.document().notes.endnote.numFmt);
        });
    for (String noteKind : new String[] {"footnote", "endnote"})
      register(
          "references." + noteKind,
          "Insert " + noteKind,
          "text?:string,mark?:string",
          true,
          (s, a) -> {
            s.insertNote(
                noteKind,
                a.containsKey("text") ? str(a, "text") : "",
                a.containsKey("mark") ? str(a, "mark") : "");
            return null;
          });
    query("references.nextFootnote", "",
        "Select the next note reference", (s, a) -> {
          s.nextNote(false);
          return null;
        });
    edit("references.convertNotes", "to:string",
        "Convert footnotes/endnotes together", (s, a) -> {
          String to = str(a, "to");
          if (!Arrays.asList("footnote", "endnote", "swap").contains(to))
            throw new IllegalArgumentException("Invalid note conversion");
          int count = 0;
          for (Note note : Notes.all(s.document())) {
            String kind =
                "swap".equals(to) ? ("footnote".equals(note.kind) ? "endnote" : "footnote") : to;
            if (!kind.equals(note.kind)) {
              s.updateNote(note.id, note.kind(kind));
              count++;
            }
          }
          return Json.object("converted", count);
        });
    edit("references.insertNote", "kind:string,text:string,mark?:string",
        "Insert a footnote or endnote at the selection", (s, a) -> {
          s.insertNote(str(a, "kind"), str(a, "text"), a.containsKey("mark") ? str(a, "mark") : "");
          return null;
        });
    query("references.notes", "",
        "List editable footnotes and endnotes", (s, a) -> {
          var result = new ArrayList<Object>();
          for (Note note : Notes.all(layoutEngine.layout(s.document(), s.revision()).document))
            result.add(
                Json.object(
                    "id",
                    note.id,
                    "kind",
                    note.kind,
                    "label",
                    note.label,
                    "mark",
                    note.mark,
                    "text",
                    note.plainText()));
          return result;
        });
    query("references.selectNote", "id:string",
        "Select a note reference", (s, a) -> {
          s.selectNote(str(a, "id"));
          return null;
        });
    edit("references.deleteNote", "id:string",
        "Delete a note and its reference", (s, a) -> {
          s.deleteNote(str(a, "id"));
          return null;
        });
    edit("references.updateNote", "id:string,text?:string,kind?:string,mark?:string",
        "Edit or convert a note", (s, a) -> {
          Note found = null;
          for (Note note : Notes.all(s.document())) if (note.id.equals(str(a, "id"))) found = note;
          if (found == null) throw new IllegalArgumentException("Unknown note");
          Sequence<Paragraph> story = found.paragraphs;
          if (a.containsKey("text")) {
            var blocks = new Sequence<Block>();
            for (Paragraph p : story) blocks = blocks.append(p);
            var draft =
                new Session(
                    new Document(blocks, s.document().page, "Note", s.document().nextId)
                        .styles(s.document().styles)
                        .trackChanges(s.document().trackChanges));
            draft.reviewAuthor(s.reviewAuthor());
            int last = blocks.size() - 1;
            draft.selectRange(0, 0, last, ((Paragraph) blocks.get(last)).text.length());
            draft.replace(str(a, "text"));
            story = new Sequence<>();
            for (Block block : draft.document().blocks) story = story.append((Paragraph) block);
          }
          s.updateNote(
              found.id,
              new Note(
                  found.id,
                  a.containsKey("kind") ? str(a, "kind") : found.kind,
                  a.containsKey("mark") ? str(a, "mark") : found.mark,
                  found.label,
                  story));
          return null;
        });
    edit(
        "references.caption", "label?:string,text?:string,position?:string,format?:string,excludeLabel?:boolean,chapter?:integer,separator?:string",
        "Insert an automatically numbered caption", (s, a) -> {
          String position = a.containsKey("position") ? str(a, "position") : "below";
          if (!"above".equals(position) && !"below".equals(position))
            throw new IllegalArgumentException("Caption position must be above or below");
          s.insertCaption(
              a.containsKey("label") ? str(a, "label") : "Figure",
              a.containsKey("text") ? str(a, "text") : "",
              "above".equals(position),
              a.containsKey("format") ? str(a, "format") : "ARABIC",
              Boolean.TRUE.equals(a.get("excludeLabel")),
              a.containsKey("chapter") ? integer(a, "chapter") : 0,
              a.containsKey("separator") ? str(a, "separator") : "-");
          return null;
        });
    edit("references.updateFields", "",
        "Update sequence and pagination fields", (s, a) -> {
          var engine = layoutEngine;
          Citations.update(s);
          TableContents.update(s, d -> engine.layout(d, s.revision()));
          TableContents.updateFigures(s, d -> engine.layout(d, s.revision()));
          DocumentIndex.update(s, d -> engine.layout(d, s.revision()));
          TableAuthorities.update(s, d -> engine.layout(d, s.revision()));
          for (int pass = 0; pass < 4; pass++) {
            var before = s.document();
            s.mapFields(
                DocumentFields.resolver(
                    before,
                    engine.layout(before, s.revision()),
                    Collections.<String, String>emptyMap()));
            if (before == s.document()) break;
          }
          return null;
        });
    edit("bookmark.add", "name:string",
        "Name the selected body range", (s, a) -> {
          s.addBookmark(str(a, "name"));
          return null;
        });
    edit("bookmark.delete", "name:string",
        "Remove a bookmark without deleting its text", (s, a) -> {
          s.deleteBookmark(str(a, "name"));
          return null;
        });
    query("bookmark.goto", "name:string",
        "Select a named bookmark range", (s, a) -> {
          s.goToBookmark(str(a, "name"));
          return null;
        });
    query("bookmark.list", "",
        "List names and current bookmark text", (s, a) -> {
          var marks = new ArrayList<Object>();
          for (Bookmark mark : s.document().bookmarks.values())
            marks.add(
                Json.object(
                    "name",
                    mark.name,
                    "text",
                    mark.text(s.document()),
                    "startId",
                    mark.startId,
                    "start",
                    mark.start,
                    "endId",
                    mark.endId,
                    "end",
                    mark.end));
          return marks;
        });
    query("reference.targets", "kind:string",
        "List heading, caption or bookmark targets", (s, a) ->
            new ArrayList<String>(CrossReferences.targets(s.document(), str(a, "kind")).keySet()));
    edit("reference.insert", "kind:string,target:string,show?:string",
        "Insert a bookmark-backed cross-reference", (s, a) -> {
          CrossReferences.insert(
              s,
              str(a, "kind"),
              str(a, "target"),
              a.containsKey("show") ? str(a, "show") : "text",
              layoutEngine);
          return null;
        });
    edit("field.insert", "instruction:string,result?:string",
        "Insert a document field", (s, a) -> {
          s.insertField(
              new InlineField(
                  str(a, "instruction"),
                  a.containsKey("result") ? str(a, "result") : "",
                  false,
                  false));
          return null;
        });
    edit("picture.insert", "base64:string,width:number,height:number,alt:string",
        "Insert embedded PNG or JPEG data at the selection", (s, a) -> {
          s.insertPicture(
              com.codename1.util.Base64.decode(str(a, "base64").getBytes()),
              ((Number) a.get("width")).floatValue(),
              ((Number) a.get("height")).floatValue(),
              str(a, "alt"));
          return null;
        });
    edit("picture.update", "block:integer,width:number,height:number,alt:string,align:string",
        "Set picture size, alternative text and alignment", (s, a) -> {
          s.updatePicture(
              integer(a, "block"),
              ((Number) a.get("width")).floatValue(),
              ((Number) a.get("height")).floatValue(),
              str(a, "alt"),
              str(a, "align"),
              false);
          return null;
        });
    edit("picture.delete", "block:integer",
        "Delete an embedded picture", (s, a) -> {
          int block = integer(a, "block");
          var pic = (Picture) s.document().blocks.get(block);
          s.updatePicture(block, pic.width, pic.height, pic.alt, pic.align, true);
          return null;
        });
    edit("styles.delete", "id:string",
        "Delete a custom style, retaining paragraph formatting", (s, a) -> {
          s.deleteStyle(str(a, "id"));
          return null;
        });
    edit("paragraph.spacing", "rule:string,value:number",
        "Set line spacing as a multiple, exact points or minimum points", (s, a) -> {
          s.paragraph("lineRule", str(a, "rule"));
          s.paragraph("spacing", String.valueOf(a.get("value")));
          return null;
        });
    edit("paragraph.set", "property:string,value:string",
        "Set paragraph properties", (s, a) -> {
          String k = str(a, "property");
          if (!Arrays.asList(
                  "style",
                  "align",
                  "list",
                  "before",
                  "after",
                  "indent",
                  "spacing",
                  "lineRule",
                  "pageBreak",
                  "keepNext",
                  "rightIndent",
                  "firstIndent",
                  "tabInterval",
                  "tabs",
                  "keepLines",
                  "widowControl",
                  "outlineLevel",
                  "listLevel",
                  "listStart",
                  "listFormat")
              .contains(k)) throw new IllegalArgumentException("Unknown paragraph property");
          s.paragraph(k, str(a, "value"));
          return null;
        });
    query(
        "table.selectCells", "block:integer,row:integer,column:integer,endRow:integer,endColumn:integer",
        "Select a rectangular range of whole table cells", (s, a) -> {
          s.selectCells(
              integer(a, "block"),
              integer(a, "row"),
              integer(a, "column"),
              integer(a, "endRow"),
              integer(a, "endColumn"));
          return null;
        });
    for (final String scope : Arrays.asList("Cell", "Row", "Column", "Table"))
      register(
          "table.select" + scope,
          "Select " + scope.toLowerCase(),
          "",
          false,
          (s, a) -> {
            if (!s.inCell()) throw new IllegalArgumentException("Click inside a table first");
            int block = s.cellBlock(), row = s.cellRow(), col = s.cellColumn();
            var t = (Table) s.document().blocks.get(block);
            s.selectCells(
                block,
                "Column".equals(scope) || "Table".equals(scope) ? 0 : row,
                "Row".equals(scope) || "Table".equals(scope) ? 0 : col,
                "Column".equals(scope) || "Table".equals(scope) ? t.rows() - 1 : row,
                "Row".equals(scope) || "Table".equals(scope) ? t.columns() - 1 : col);
            return null;
          });
    for (final boolean rows : new boolean[] {true, false})
      register(
          rows ? "table.deleteRows" : "table.deleteColumns",
          "Delete selected " + (rows ? "rows" : "columns"),
          "",
          true,
          (s, a) -> {
            if (!s.inCell()) throw new IllegalArgumentException("Click inside a table first");
            int block = s.cellBlock(), row = s.cellRow(), col = s.cellColumn();
            var table = (Table) s.document().blocks.get(block);
            var selected = s.cellSelection();
            int first =
                selected == null
                    ? (rows ? row : col)
                    : rows ? selected.firstRow : selected.firstColumn;
            int last = selected == null ? first : rows ? selected.lastRow : selected.lastColumn;
            if (last - first + 1 == (rows ? table.rows() : table.columns())) s.deleteTable(block);
            else {
              for (int i = last; i >= first; i--)
                s.tableStructure(block, rows ? "deleteRow" : "deleteColumn", i);
              table = (Table) s.document().blocks.get(block);
              s.enterCell(
                  block,
                  Math.min(rows ? first : row, table.rows() - 1),
                  Math.min(rows ? col : first, table.columns() - 1));
            }
            return null;
          });
    edit("table.clearCells", "",
        "Clear selected cell contents without deleting the grid", (s, a) -> {
          if (s.cellSelection() == null) throw new IllegalArgumentException("Select cells first");
          s.replace("");
          return null;
        });
    edit("table.mergeSelected", "",
        "Merge the selected rectangle", (s, a) -> {
          var x = s.cellSelection();
          if (x == null) throw new IllegalArgumentException("Select cells first");
          s.mergeCells(
              x.block,
              x.firstRow,
              x.firstColumn,
              x.lastRow - x.firstRow + 1,
              x.lastColumn - x.firstColumn + 1);
          s.enterCell(x.block, x.firstRow, x.firstColumn);
          return null;
        });
    edit("edit.pasteText", "text:string",
        "Paste text or a rectangular TSV grid into table cells", (s, a) -> {
          s.pasteText(str(a, "text"));
          return null;
        });
    edit("table.insert", "rows:integer,columns:integer",
        "Insert a rectangular table after selection", (s, a) -> {
          s.insertTable(integer(a, "rows"), integer(a, "columns"));
          return null;
        });
    edit("table.setCell", "block:integer,row:integer,column:integer,text:string",
        "Replace a table cell with plain text", (s, a) -> {
          s.setCell(integer(a, "block"), integer(a, "row"), integer(a, "column"), str(a, "text"));
          return null;
        });

    edit(
        "table.format", "block:integer,row:integer,column:integer,background?:string,borderColor?:string,borderWidth?:number,horizontalPadding?:number,verticalPadding?:number,vertical?:string,columnPercent?:number,minHeight?:number,headerRows?:integer",
        "Set table geometry and cell appearance", (s, a) -> {
          int block = integer(a, "block"), row = integer(a, "row"), column = integer(a, "column");
          var table = (Table) s.document().blocks.get(block);
          var f = table.format;
          var old = f.cell(row, column);
          f =
              f.cell(
                  row,
                  column,
                  new TableFormat.Cell(
                      a.containsKey("background")
                          ? Integer.parseInt(str(a, "background"), 16)
                          : old.background,
                      a.containsKey("borderColor")
                          ? Integer.parseInt(str(a, "borderColor"), 16)
                          : old.borderColor,
                      a.containsKey("borderWidth")
                          ? ((Number) a.get("borderWidth")).floatValue()
                          : old.borderWidth,
                      a.containsKey("horizontalPadding")
                          ? ((Number) a.get("horizontalPadding")).floatValue()
                          : old.horizontalPadding,
                      a.containsKey("verticalPadding")
                          ? ((Number) a.get("verticalPadding")).floatValue()
                          : old.verticalPadding,
                      a.containsKey("vertical") ? str(a, "vertical") : old.vertical,
                      old.sides));
          if (a.containsKey("columnPercent"))
            f = f.columnFraction(column, ((Number) a.get("columnPercent")).floatValue() / 100);
          if (a.containsKey("minHeight"))
            f = f.minHeight(row, ((Number) a.get("minHeight")).floatValue());
          if (a.containsKey("headerRows")) f = f.headerRows(integer(a, "headerRows"));
          s.formatTable(block, f);
          return null;
        });
    edit("table.structure", "block:integer,action:string,index:integer",
        "Insert or delete a table row/column", (s, a) -> {
          s.tableStructure(integer(a, "block"), str(a, "action"), integer(a, "index"));
          return null;
        });
    edit("table.merge", "block:integer,row:integer,column:integer,rows:integer,columns:integer",
        "Merge a rectangular cell range", (s, a) -> {
          s.mergeCells(
              integer(a, "block"),
              integer(a, "row"),
              integer(a, "column"),
              integer(a, "rows"),
              integer(a, "columns"));
          return null;
        });
    edit("table.split", "block?:integer,row?:integer,column?:integer,columns?:integer",
        "Split a cell into columns; explicit coordinates without columns unmerge for compatibility",
        (s, a) -> {
          boolean explicit =
              a.containsKey("block") || a.containsKey("row") || a.containsKey("column");
          if (explicit
              && !(a.containsKey("block") && a.containsKey("row") && a.containsKey("column")))
            throw new IllegalArgumentException("Supply block, row and column together");
          if (!explicit && !s.inCell())
            throw new IllegalArgumentException("Click inside a table first");
          int block = explicit ? integer(a, "block") : s.cellBlock();
          int row = explicit ? integer(a, "row") : s.cellRow();
          int col = explicit ? integer(a, "column") : s.cellColumn();
          if (explicit && !a.containsKey("columns")) s.splitCell(block, row, col);
          else s.splitCell(block, row, col, a.containsKey("columns") ? integer(a, "columns") : 2);
          return null;
        });
    edit(
        "table.sort", "block:integer,column:integer,descending:boolean,header:boolean,numeric:boolean",
        "Stable text or numeric table sort", (s, a) -> {
          s.sortTable(
              integer(a, "block"),
              integer(a, "column"),
              (Boolean) a.get("descending"),
              (Boolean) a.get("header"),
              (Boolean) a.get("numeric"));
          return null;
        });
    edit("table.toText", "block:integer,separator:string",
        "Convert table rows to styled paragraphs", (s, a) -> {
          s.tableToText(integer(a, "block"), str(a, "separator"));
          return null;
        });
    edit("table.delete", "block:integer",
        "Delete a table", (s, a) -> {
          s.deleteTable(integer(a, "block"));
          return null;
        });

    query("review.trackingOptions", "trackFormatting?:boolean,author?:string",
        "Read or change review preferences", (s, a) -> {
          if (a.containsKey("author")) s.reviewAuthor(str(a, "author"));
          if (a.containsKey("trackFormatting"))
            s.trackFormatting((Boolean) a.get("trackFormatting"));
          return Json.object("trackFormatting", s.trackFormatting(), "author", s.reviewAuthor());
        });
    edit("review.trackChanges", "value?:boolean,author?:string",
        "Enable or disable tracking", (s, a) -> {
          if (a.containsKey("author")) s.reviewAuthor(str(a, "author"));
          s.trackChanges(
              a.containsKey("value") ? (Boolean) a.get("value") : !s.document().trackChanges);
          return s.document().trackChanges;
        });
    edit("review.acceptAll", "",
        "Accept all revisions", (s, a) -> {
          s.resolveRevision(null, true);
          return null;
        });
    edit("review.rejectAll", "",
        "Reject all revisions", (s, a) -> {
          s.resolveRevision(null, false);
          return null;
        });
    edit("review.accept", "id?:string",
        "Accept selected revision", (s, a) -> {
          if (a.containsKey("id")) s.resolveRevision(str(a, "id"), true);
          else s.resolveSelectedRevisions(true);
          return null;
        });
    edit("review.reject", "id?:string",
        "Reject selected revision", (s, a) -> {
          if (a.containsKey("id")) s.resolveRevision(str(a, "id"), false);
          else s.resolveSelectedRevisions(false);
          return null;
        });
    query("review.nextChange", "",
        "Select next revision", (s, a) -> s.nextRevision(false).revision.id);
    query("review.previousChange", "",
        "Select previous revision", (s, a) -> s.nextRevision(true).revision.id);
    query("review.changes", "",
        "List document revisions", (s, a) -> {
          var out = new ArrayList<Object>();
          for (Revisions.Change c : Revisions.list(s.document()))
            out.add(
                Json.object(
                    "id",
                    c.revision.id,
                    "kind",
                    c.revision.kind,
                    "author",
                    c.revision.author,
                    "date",
                    c.revision.date,
                    "text",
                    c.text,
                    "paragraph",
                    c.paragraph,
                    "start",
                    c.start,
                    "end",
                    c.end,
                    "story",
                    c.story));
          return out;
        });

    edit("comment.add", "author:string,text:string",
        "Comment on the selected range", (s, a) -> {
          s.addComment(str(a, "author"), str(a, "text"));
          return null;
        });
    edit("comment.resolve", "id:string,resolved:boolean",
        "Resolve or reopen a comment", (s, a) -> {
          s.updateComment(str(a, "id"), (Boolean) a.get("resolved"), false);
          return null;
        });
    edit("comment.delete", "id:string",
        "Delete a comment", (s, a) -> {
          s.updateComment(str(a, "id"), false, true);
          return null;
        });
    query("comment.select", "id:string",
        "Select a comment's anchored text", (s, a) -> {
          s.selectComment(str(a, "id"));
          return null;
        });
    edit("comment.edit", "id:string,text:string,reply?:integer",
        "Edit comment or reply text", (s, a) -> {
          s.editComment(
              str(a, "id"), a.containsKey("reply") ? integer(a, "reply") : -1, str(a, "text"));
          return null;
        });
    query("comment.next", "",
        "Move to the next comment, wrapping at the end", (s, a) -> s.navigateComment(false));
    query("comment.previous", "",
        "Move to the previous comment, wrapping at the start", (s, a) -> s.navigateComment(true));
    query("comment.list", "",
        "List document comments", (s, a) -> {
          var out = new ArrayList<Object>();
          for (ReviewComment c : s.document().comments)
            out.add(
                Json.object(
                    "id",
                    c.id,
                    "author",
                    c.author,
                    "text",
                    c.text,
                    "replies",
                    replies(c),
                    "resolved",
                    c.resolved,
                    "startId",
                    c.startId,
                    "start",
                    c.start,
                    "endId",
                    c.endId,
                    "end",
                    c.end));
          return out;
        });
    edit("comment.reply", "id:string,author:string,text:string",
        "Reply to a comment thread", (s, a) -> {
          s.replyComment(str(a, "id"), str(a, "author"), str(a, "text"));
          return null;
        });
    query("edit.undo", "",
        "Undo the last transaction", (s, a) -> {
          s.undo();
          return null;
        });
    query("edit.redo", "",
        "Redo the last transaction", (s, a) -> {
          s.redo();
          return null;
        });
    query("find.all", "text:string",
        "Find literal text in paragraphs and table cells",
        (s, a) -> find(s.document(), str(a, "text")));
    edit("replace.all", "text:string,replacement:string",
        "Replace literal text in paragraphs and table cells", (s, a) -> {
          String needle = str(a, "text");
          var matches = find(s.document(), needle);
          if (matches.size() > 10000) throw new IllegalArgumentException("Too many matches");
          for (int i = matches.size() - 1; i >= 0; i--) {
            var m = matches.get(i);
            selectMatch(s, m);
            s.replace(str(a, "replacement"));
          }
          return matches.size();
        });
  }

  private static List<Object> replies(ReviewComment c) {
    var out = new ArrayList<Object>();
    for (ReviewComment.Reply reply : c.replies)
      out.add(Json.object("author", reply.author, "text", reply.text));
    return out;
  }

  public static List<Map<String, Object>> find(Document d, String needle) {
    if (needle.isEmpty()) throw new IllegalArgumentException("Search text is empty");
    var out = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < d.blocks.size(); i++) {
      var b = d.blocks.get(i);
      if (b instanceof Paragraph paragraph1) findParagraph(out, paragraph1, needle, i, -1, -1, 0);
      else if (b instanceof Table t) {
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++) {
            if (t.covered(r, c)) continue;
            int p = 0;
            for (Paragraph paragraph : t.cell(r, c))
              findParagraph(out, paragraph, needle, i, r, c, p++);
          }
      }
    }
    return out;
  }

  private static void findParagraph(
      List<Map<String, Object>> out,
      Paragraph p,
      String needle,
      int block,
      int row,
      int column,
      int paragraph) {
    var text = p.text.toString();
    int start = 0;
    while ((start = text.indexOf(needle, start)) >= 0) {
      var match =
          Json.object("block", block, "start", start, "end", start + needle.length());
      if (row >= 0) {
        match.put("row", row);
        match.put("column", column);
        match.put("paragraph", paragraph);
      }
      out.add(match);
      start += needle.length();
    }
  }

  public static Paragraph matchedParagraph(Document d, Map<String, Object> match) {
    return match.containsKey("row")
        ? ((Table) d.blocks.get(integer(match, "block")))
            .cell(integer(match, "row"), integer(match, "column"))
            .get(integer(match, "paragraph"))
        : d.paragraph(integer(match, "block"));
  }

  public static void selectMatch(Session s, Map<String, Object> match) {
    int block = integer(match, "block");
    if (match.containsKey("row")) {
      s.enterCell(block, integer(match, "row"), integer(match, "column"));
      block = integer(match, "paragraph");
    } else s.leaveCell();
    s.select(block, integer(match, "start"), integer(match, "end"));
  }

  public static boolean followsSelection(Session s, Map<String, Object> match) {
    var x = s.selection();
    int[] current = {
      s.inCell() ? s.cellBlock() : x.block,
      s.cellRow(),
      s.cellColumn(),
      s.inCell() ? x.block : 0,
      x.end()
    };
    int[] found = {
      integer(match, "block"),
      match.containsKey("row") ? integer(match, "row") : -1,
      match.containsKey("column") ? integer(match, "column") : -1,
      match.containsKey("paragraph") ? integer(match, "paragraph") : 0,
      integer(match, "start")
    };
    for (int i = 0; i < current.length; i++) {
      if (found[i] > current[i]) return true;
      if (found[i] < current[i]) return false;
    }
    return true;
  }

  private static CitationSource citationSource(Map<?, ?> values) {
    var data = new LinkedHashMap<String, String>();
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String))
        throw new IllegalArgumentException("Source fields must be strings");
      data.put((String) entry.getKey(), (String) entry.getValue());
    }
    return new CitationSource(data);
  }

  public static Map<String, Object> inspect(Session s) {
    var blocks = new ArrayList<Object>();
    for (Block b : s.document().blocks) {
      if (b instanceof Paragraph p) {
        blocks.add(
            Json.object(
                "id",
                p.id,
                "type",
                "paragraph",
                "text",
                p.text.toString(),
                "style",
                p.style,
                "alignment",
                p.align));
      } else if (b instanceof Table t) {
        blocks.add(
            Json.object(
                "id",
                t.id(),
                "type",
                "table",
                "rows",
                t.rows(),
                "columns",
                t.columns(),
                "title",
                t.format.title,
                "description",
                t.format.description));
      }
    }
    var x = s.selection();
    return Json.object(
        "apiVersion",
        1,
        "revision",
        s.revision(),
        "title",
        s.document().title,
        "blocks",
        blocks,
        "selection",
        Json.object(
            "block", x.block, "anchorBlock", x.anchorBlock, "anchor", x.anchor, "caret", x.caret));
  }
}
