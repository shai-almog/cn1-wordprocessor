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
      Map<String, Object> props = new LinkedHashMap<String, Object>();
      for (Map.Entry<String, String> e : parameters.entrySet())
        props.put(e.getKey(), Json.object("type", e.getValue()));
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
      new LinkedHashMap<String, Descriptor>();

  public Commands() {
    builtins();
    new QualityExtension().register(this);
    new TemplateExtension().register(this);
  }

  public void register(
      String id, String description, String spec, boolean transactional, Handler handler) {
    if (commands.containsKey(id) || !id.matches("[a-z][a-zA-Z0-9.]+"))
      throw new IllegalArgumentException("Duplicate/invalid command " + id);
    Map<String, String> params = new LinkedHashMap<String, String>();
    Set<String> required = new HashSet<String>();
    if (!spec.isEmpty())
      for (String p : spec.split(",")) {
        String[] kv = p.split(":");
        String key = kv[0];
        if (!key.endsWith("?")) required.add(key);
        else key = key.substring(0, key.length() - 1);
        params.put(key, kv[1]);
      }
    commands.put(id, new Descriptor(id, description, params, required, transactional, handler));
  }

  public Collection<Descriptor> descriptors() {
    return Collections.unmodifiableCollection(commands.values());
  }

  public Object execute(final Session session, String id, final Map<String, Object> args) {
    final Descriptor d = commands.get(id);
    if (d == null) throw new IllegalArgumentException("Unknown command: " + id);
    for (String required : d.required)
      if (!args.containsKey(required))
        throw new IllegalArgumentException("Missing argument: " + required);
    for (Map.Entry<String, Object> e : args.entrySet()) {
      String type = d.parameters.get(e.getKey());
      if (type == null) throw new IllegalArgumentException("Unknown argument: " + e.getKey());
      Object v = e.getValue();
      if ("string".equals(type) && !(v instanceof String)
          || "integer".equals(type)
              && (!(v instanceof Number) || ((Number) v).doubleValue() != ((Number) v).intValue())
          || "object".equals(type) && !(v instanceof Map)
          || "array".equals(type) && !(v instanceof List)
          || "boolean".equals(type) && !(v instanceof Boolean))
        throw new IllegalArgumentException("Wrong type for " + e.getKey());
    }
    if (!d.transactional) return d.handler.execute(session, args);
    final Object[] result = new Object[1];
    session.transact(s -> result[0] = d.handler.execute(s, args));
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

  private void builtins() {
    register(
        "document.inspect",
        "Inspect document structure and selection",
        "",
        false,
        (s, a) -> inspect(s));
    register(
        "document.text", "Return document text", "", false, (s, a) -> s.document().plainText());
    register(
        "document.title",
        "Set document title",
        "title:string",
        true,
        (s, a) -> {
          Selection x = s.selection();
          s.replaceDocument(s.document().title(str(a, "title")));
          s.selectRange(x.anchorBlock, x.anchor, x.block, x.caret);
          return null;
        });
    register(
        "selection.set",
        "Select a UTF-16 range across paragraphs",
        "block:integer,start:integer,end:integer,endBlock?:integer",
        false,
        (s, a) -> {
          s.selectRange(
              integer(a, "block"),
              integer(a, "start"),
              a.containsKey("endBlock") ? integer(a, "endBlock") : integer(a, "block"),
              integer(a, "end"));
          return null;
        });
    register(
        "text.insert",
        "Replace selection with text",
        "text:string",
        true,
        (s, a) -> {
          String text = str(a, "text");
          if (text.length() > 1024 * 1024) throw new IllegalArgumentException("Text exceeds 1 MiB");
          s.replace(text);
          return null;
        });
    register(
        "text.backspace",
        "Delete selection or previous code point",
        "",
        true,
        (s, a) -> {
          s.deleteBackward();
          return null;
        });
    register(
        "format.set",
        "Apply character formatting to selection",
        "property:string,value:string",
        true,
        (s, a) -> {
          String k = str(a, "property");
          if (!Arrays.asList(
                  "bold",
                  "italic",
                  "underline",
                  "strike",
                  "font",
                  "size",
                  "color",
                  "background",
                  "link")
              .contains(k)) throw new IllegalArgumentException("Unknown format property");
          s.format(k, str(a, "value"));
          return null;
        });
    register(
        "paragraph.set",
        "Set paragraph properties",
        "property:string,value:string",
        true,
        (s, a) -> {
          String k = str(a, "property");
          if (!Arrays.asList(
                  "style",
                  "align",
                  "list",
                  "before",
                  "after",
                  "indent",
                  "spacing",
                  "pageBreak",
                  "keepNext")
              .contains(k)) throw new IllegalArgumentException("Unknown paragraph property");
          s.paragraph(k, str(a, "value"));
          return null;
        });
    register(
        "table.insert",
        "Insert a rectangular table after selection",
        "rows:integer,columns:integer",
        true,
        (s, a) -> {
          s.insertTable(integer(a, "rows"), integer(a, "columns"));
          return null;
        });
    register(
        "table.setCell",
        "Replace a table cell with plain text",
        "block:integer,row:integer,column:integer,text:string",
        true,
        (s, a) -> {
          s.setCell(integer(a, "block"), integer(a, "row"), integer(a, "column"), str(a, "text"));
          return null;
        });
    register(
        "edit.undo",
        "Undo the last transaction",
        "",
        false,
        (s, a) -> {
          s.undo();
          return null;
        });
    register(
        "edit.redo",
        "Redo the last transaction",
        "",
        false,
        (s, a) -> {
          s.redo();
          return null;
        });
    register(
        "find.all",
        "Find literal text in top-level paragraphs",
        "text:string",
        false,
        (s, a) -> find(s.document(), str(a, "text")));
    register(
        "replace.all",
        "Replace literal text in top-level paragraphs",
        "text:string,replacement:string",
        true,
        (s, a) -> {
          String needle = str(a, "text");
          List<Map<String, Object>> matches = find(s.document(), needle);
          if (matches.size() > 10000) throw new IllegalArgumentException("Too many matches");
          for (int i = matches.size() - 1; i >= 0; i--) {
            Map<String, Object> m = matches.get(i);
            s.select(integer(m, "block"), integer(m, "start"), integer(m, "end"));
            s.replace(str(a, "replacement"));
          }
          return matches.size();
        });
  }

  public static List<Map<String, Object>> find(Document d, String needle) {
    if (needle.isEmpty()) throw new IllegalArgumentException("Search text is empty");
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < d.blocks.size(); i++) {
      Block b = d.blocks.get(i);
      if (!(b instanceof Paragraph)) continue;
      String text = ((Paragraph) b).text.toString();
      int start = 0;
      while ((start = text.indexOf(needle, start)) >= 0) {
        out.add(Json.object("block", i, "start", start, "end", start + needle.length()));
        start += needle.length();
      }
    }
    return out;
  }

  public static Map<String, Object> inspect(Session s) {
    List<Object> blocks = new ArrayList<Object>();
    for (Block b : s.document().blocks) {
      if (b instanceof Paragraph) {
        Paragraph p = (Paragraph) b;
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
      } else if (b instanceof Table) {
        Table t = (Table) b;
        blocks.add(
            Json.object("id", t.id(), "type", "table", "rows", t.rows(), "columns", t.columns()));
      }
    }
    Selection x = s.selection();
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
