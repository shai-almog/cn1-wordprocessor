package com.codenameone.wordprocessor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Backstage and application workflows: options, AutoSave, AutoCorrect, numerals, print markup,
 * share, version history, recovery, templates, Tell Me search, macro recording, Researcher, Read
 * Aloud, comments, combine, ink and the alt text pane.
 */
final class OfficeCommands implements Commands.Extension {
  /** Online research provider (installed by the app); headless use has none. */
  public interface Research {
    List<Map<String, String>> search(String query) throws IOException;
  }

  /** Persistent template library; the editor installs storage, headless keeps them in memory. */
  public interface TemplateStore {
    void save(String name, String xml) throws IOException;

    void delete(String name) throws IOException;
  }

  private static Research research;
  private static TemplateStore templateStore;
  private static final TreeMap<String, String> TEMPLATES = new TreeMap<String, String>();

  static final class Version {
    final String label, date;
    final Document document;

    Version(String label, Document document) {
      this.label = label;
      this.document = document;
      this.date = iso(System.currentTimeMillis());
    }
  }

  private static final SessionMap<List<Version>> VERSIONS = new SessionMap<List<Version>>();
  private static final SessionMap<List<Object>> RECORDING = new SessionMap<List<Object>>();
  private static final SessionMap<int[]> READING = new SessionMap<int[]>();
  private static final SessionMap<String> PASSWORDS = new SessionMap<String>();

  /** The password Word documents saved from this session are encrypted with, or null. */
  public static String password(Session s) {
    synchronized (PASSWORDS) {
      return PASSWORDS.get(s);
    }
  }

  public static void password(Session s, String password) {
    synchronized (PASSWORDS) {
      if (password == null || password.isEmpty()) PASSWORDS.remove(s);
      else PASSWORDS.put(s, password);
    }
  }

  public static void research(Research provider) {
    research = provider;
  }

  public static synchronized void templates(TemplateStore store, Map<String, String> loaded) {
    templateStore = store;
    TEMPLATES.clear();
    if (loaded != null) TEMPLATES.putAll(loaded);
  }

  /** Snapshot for version history; the editor calls this on save and AutoSave. */
  public static void snapshot(Session s, String label) {
    synchronized (VERSIONS) {
      List<Version> list = VERSIONS.get(s);
      if (list == null) VERSIONS.put(s, list = new ArrayList<Version>());
      if (!list.isEmpty() && list.get(list.size() - 1).document == s.document()) return;
      list.add(new Version(label, s.document()));
      if (list.size() > 50) list.remove(0);
    }
  }

  static List<Version> versions(Session s) {
    synchronized (VERSIONS) {
      List<Version> list = VERSIONS.get(s);
      return list == null ? new ArrayList<Version>() : new ArrayList<Version>(list);
    }
  }

  /** The document as printed: tracked changes resolved to their final text unless markup prints. */
  public static Document printable(Document d) {
    if (AppOptions.flag("printMarkup")) return d;
    for (Revisions.Change c : Revisions.list(d)) d = Revisions.resolve(d, c.revision.id, true);
    return d.comments(Collections.<ReviewComment>emptyList());
  }

  /** The document as displayed: ink hidden when Hide Ink is on. */
  private static boolean isInk(Block b) {
    return b instanceof Picture && ((Picture) b).drawing != null && "ink".equals(((Picture) b).drawing.kind);
  }

  public static Document shown(Document d) {
    if (!AppOptions.flag("hideInk")) return d;
    // Scan first: rebuilding the block sequence on every edit when there is no ink costs a
    // full copy per keystroke in large documents.
    boolean ink = false;
    for (Block b : d.blocks) if (isInk(b)) ink = true;
    if (!ink) return d;
    Sequence<Block> out = new Sequence<Block>();
    for (Block b : d.blocks) if (!isInk(b)) out = out.append(b);
    if (out.size() == 0) out = out.append(new Paragraph("ink-hidden", new RichText()));
    return d.blocks(out, d.nextId);
  }

  public void register(final Commands c) {
    registerDropCap(c);
    c.register(
        "file.encrypt",
        "Encrypt with Password: Word documents saved from now on are AES-256 encrypted (ECMA-376 agile); an empty password removes it",
        "password?:string",
        false,
        (s, a) -> {
          String pw = a.containsKey("password") ? Commands.str(a, "password") : "";
          if (pw.length() > 255) throw new IllegalArgumentException("Passwords are at most 255 characters");
          password(s, pw);
          return Json.object("encrypted", password(s) != null);
        });
    c.register(
        "file.options",
        "Word Options: list all, or set one option (user name, units, AutoSave, numerals, proofing, AutoCorrect)",
        "key?:string,value?:string,reset?:boolean",
        false,
        (s, a) -> {
          if (Boolean.TRUE.equals(a.get("reset"))) AppOptions.reset();
          if (a.containsKey("key")) {
            String key = Commands.str(a, "key");
            if (a.containsKey("value")) option(key, Commands.str(a, "value"));
            return Json.object("key", key, "value", AppOptions.get(key));
          }
          return new LinkedHashMap<String, Object>(AppOptions.all());
        });
    c.register(
        "file.autosave",
        "AutoSave on or off (toggles without a value) and its interval in minutes",
        "value?:boolean,minutes?:integer",
        false,
        (s, a) -> {
          option("autosave", String.valueOf(a.containsKey("value") ? Boolean.TRUE.equals(a.get("value")) : !AppOptions.flag("autosave")));
          if (a.containsKey("minutes")) option("autosaveMinutes", String.valueOf(Commands.integer(a, "minutes")));
          return Json.object("value", AppOptions.flag("autosave"), "minutes", Integer.parseInt(AppOptions.get("autosaveMinutes")));
        });
    c.register(
        "view.numeral",
        "Numerals: arabic (0-9), hindi (Arabic-Indic digits) or context (Hindi in right-to-left text)",
        "value?:string",
        false,
        (s, a) -> {
          if (a.containsKey("value")) option("numeral", Commands.str(a, "value"));
          return Json.object("value", AppOptions.get("numeral"));
        });
    c.register(
        "file.printMarkup",
        "Print Markup: print tracked changes and comments (true) or the final document (false)",
        "value?:boolean",
        false,
        (s, a) -> {
          option("printMarkup", String.valueOf(a.containsKey("value") ? Boolean.TRUE.equals(a.get("value")) : !AppOptions.flag("printMarkup")));
          return Json.object("value", AppOptions.flag("printMarkup"));
        });
    c.register(
        "review.hideInk",
        "Hide Ink: hide or show ink drawings on screen without deleting them",
        "value?:boolean",
        false,
        (s, a) -> {
          option("hideInk", String.valueOf(a.containsKey("value") ? Boolean.TRUE.equals(a.get("value")) : !AppOptions.flag("hideInk")));
          int ink = 0;
          for (Block b : s.document().blocks)
            if (b instanceof Picture && ((Picture) b).drawing != null && "ink".equals(((Picture) b).drawing.kind)) ink++;
          return Json.object("hidden", AppOptions.flag("hideInk"), "ink", ink);
        });
    c.register(
        "review.comments",
        "Show Comments: list every comment with its replies and show or hide the comment pane",
        "value?:boolean",
        false,
        (s, a) -> {
          if (a.containsKey("value")) option("showComments", String.valueOf(Boolean.TRUE.equals(a.get("value"))));
          List<Object> out = new ArrayList<Object>();
          for (ReviewComment rc : s.document().comments) {
            List<Object> replies = new ArrayList<Object>();
            for (ReviewComment.Reply r : rc.replies) replies.add(Json.object("author", r.author, "text", r.text));
            out.add(Json.object("id", rc.id, "author", rc.author, "text", rc.text, "resolved", rc.resolved, "replies", replies));
          }
          return Json.object("shown", AppOptions.flag("showComments"), "comments", out);
        });
    c.register(
        "file.share",
        "Share: the document as a .docx attachment (name, mime type, base64) for the platform share sheet",
        "name?:string,format?:string",
        false,
        (s, a) -> {
          String format = a.containsKey("format") ? Commands.str(a, "format") : "docx";
          String title = s.document().properties.get("title");
          String name = a.containsKey("name") ? Commands.str(a, "name") : title == null || title.trim().isEmpty() ? "Document" : title.trim();
          byte[] data;
          String mime;
          try {
            if ("pdf".equals(format)) {
              data = PdfExporter.write(printable(s.document()));
              mime = "application/pdf";
            } else if ("docx".equals(format)) {
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              Docx.write(s.document(), out);
              data = out.toByteArray();
              if (password(s) != null) data = OfficeCrypto.encrypt(data, password(s));
              mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            } else throw new IllegalArgumentException("Share format is docx or pdf");
          } catch (IOException e) {
            throw new IllegalStateException("Share failed: " + e.getMessage(), e);
          }
          return Json.object(
              "name", name + "." + format, "mime", mime, "bytes", data.length,
              "base64", new String(com.codename1.util.Base64.encodeNoNewline(data)));
        });
    c.register(
        "file.versions",
        "Version History: save a labelled version, list versions, restore one (undoable) or compare it with the current document",
        "save?:string,restore?:integer,compare?:integer",
        false,
        (s, a) -> {
          if (a.containsKey("save")) snapshot(s, Commands.str(a, "save"));
          List<Version> list = versions(s);
          if (a.containsKey("restore")) {
            final Version v = version(list, Commands.integer(a, "restore"));
            snapshot(s, "Before restoring " + v.label);
            s.transact(x -> x.replaceDocument(v.document));
            list = versions(s);
          }
          if (a.containsKey("compare")) {
            Version v = version(list, Commands.integer(a, "compare"));
            return Json.object("changes", DocumentDiff.describe(v.document, s.document()));
          }
          return listing(list);
        });
    c.register(
        "file.recover",
        "Recover Unsaved Documents: list AutoSave and version snapshots, or open one (undoable)",
        "open?:integer",
        false,
        (s, a) -> {
          List<Version> list = versions(s);
          if (a.containsKey("open")) {
            final Version v = version(list, Commands.integer(a, "open"));
            s.transact(x -> x.replaceDocument(v.document));
          }
          return listing(list);
        });
    c.register(
        "file.saveTemplate",
        "Save as Template: store the document (styles, layout and content) in the template library",
        "name:string",
        false,
        (s, a) -> {
          String name = templateName(Commands.str(a, "name"));
          String xml = DocumentXml.write(s.document());
          synchronized (OfficeCommands.class) {
            if (templateStore != null)
              try {
                templateStore.save(name, xml);
              } catch (IOException e) {
                throw new IllegalStateException("Template save failed: " + e.getMessage(), e);
              }
            TEMPLATES.put(name, xml);
          }
          return Json.object("name", name, "templates", templateNames());
        });
    c.register(
        "file.newFromTemplate",
        "New from Template: start a new document from a built-in or saved template (undoable)",
        "name:string",
        true,
        (s, a) -> {
          Document d = template(Commands.str(a, "name"));
          s.replaceDocument(d.properties(d.properties.withSetting("attachedTemplate", Commands.str(a, "name"))));
          return Json.object("name", Commands.str(a, "name"), "blocks", s.document().blocks.size());
        });
    c.register(
        "tools.templates",
        "Templates and Add-ins: list templates, attach one, copy its styles in (update styles) or delete a saved template",
        "action?:string,name?:string",
        true,
        (s, a) -> {
          String action = a.containsKey("action") ? Commands.str(a, "action") : "list";
          String name = a.containsKey("name") ? Commands.str(a, "name") : null;
          if ("attach".equals(action) || "update".equals(action)) {
            if (name == null) throw new IllegalArgumentException("Template name required");
            Document t = template(name);
            s.documentProperties(s.document().properties.withSetting("attachedTemplate", name));
            if ("update".equals(action)) {
              for (ParagraphStyle style : t.styles.values()) s.defineStyle(style);
            }
          } else if ("delete".equals(action)) {
            if (name == null || !TEMPLATES.containsKey(name)) throw new IllegalArgumentException("No saved template " + name);
            synchronized (OfficeCommands.class) {
              if (templateStore != null)
                try {
                  templateStore.delete(name);
                } catch (IOException e) {
                  throw new IllegalStateException(e.getMessage(), e);
                }
              TEMPLATES.remove(name);
            }
          } else if (!"list".equals(action)) throw new IllegalArgumentException("Action is list, attach, update or delete");
          return Json.object(
              "attached", s.document().properties.setting("attachedTemplate", "Normal"),
              "templates", templateNames());
        });
    c.register(
        "tools.search",
        "Search (Tell Me): find commands by name or description; run the best match with run=true",
        "query:string,run?:boolean,limit?:integer",
        false,
        (s, a) -> {
          String q = Commands.str(a, "query").trim().toLowerCase();
          if (q.isEmpty()) throw new IllegalArgumentException("Search for what?");
          final Map<Commands.Descriptor, Integer> score = new java.util.HashMap<Commands.Descriptor, Integer>();
          String[] words = TextUtil.split(q, ' ');
          for (Commands.Descriptor d : c.descriptors()) {
            String id = d.id.toLowerCase(), desc = d.description.toLowerCase();
            int sc = 0;
            for (String w : words) {
              if (w.isEmpty()) continue;
              if (id.substring(id.indexOf('.') + 1).equals(w)) sc += 10;
              else if (id.indexOf(w) >= 0) sc += 5;
              if (desc.indexOf(w) >= 0) sc += 2;
              else if (sc == 0) {
                sc = -100;
                break;
              }
            }
            if (sc > 0) score.put(d, sc);
          }
          List<Commands.Descriptor> hits = new ArrayList<Commands.Descriptor>(score.keySet());
          Collections.sort(hits, (x, y) -> score.get(y) - score.get(x) != 0 ? score.get(y) - score.get(x) : x.id.compareTo(y.id));
          int limit = a.containsKey("limit") ? Commands.integer(a, "limit") : 10;
          List<Object> out = new ArrayList<Object>();
          for (int i = 0; i < hits.size() && i < limit; i++)
            out.add(Json.object("id", hits.get(i).id, "description", hits.get(i).description, "parameters", new LinkedHashMap<String, Object>(hits.get(i).parameters)));
          Object ran = null;
          if (Boolean.TRUE.equals(a.get("run"))) {
            if (hits.isEmpty()) throw new IllegalArgumentException("No command matches " + q);
            if (!hits.get(0).required.isEmpty())
              throw new IllegalArgumentException(hits.get(0).id + " needs " + hits.get(0).required);
            ran = c.execute(s, hits.get(0).id);
          }
          return Json.object("results", out, "ran", ran);
        });
    c.register(
        "tools.recordMacro",
        "Record Macro: start recording editing commands, stop and save the macro under a name, run or list saved macros",
        "action?:string,name?:string",
        false,
        (s, a) -> {
          String action = a.containsKey("action") ? Commands.str(a, "action") : RECORDING.containsKey(s) ? "stop" : "start";
          if ("start".equals(action)) {
            final List<Object> steps = new ArrayList<Object>();
            final Session owner = s;
            synchronized (RECORDING) {
              if (RECORDING.containsKey(s)) throw new IllegalStateException("Already recording");
              RECORDING.put(s, steps);
            }
            c.listeners.add(new Commands.Listener() {
              public void executed(Session session, String id, Map<String, Object> args) {
                if (session != owner || id.startsWith("tools.") || id.startsWith("edit.undo") || id.startsWith("edit.redo")) return;
                synchronized (RECORDING) {
                  if (RECORDING.get(owner) != steps) {
                    c.listeners.remove(this);
                    return;
                  }
                  steps.add(Json.object("command", id, "args", new LinkedHashMap<String, Object>(args)));
                }
              }
            });
            return Json.object("recording", true);
          }
          if ("stop".equals(action)) {
            List<Object> steps;
            synchronized (RECORDING) {
              steps = RECORDING.remove(s);
            }
            if (steps == null) throw new IllegalStateException("Not recording");
            String name = a.containsKey("name") ? Commands.str(a, "name") : "Macro" + (macros(s).size() + 1);
            if (name.isEmpty() || name.indexOf(' ') >= 0) throw new IllegalArgumentException("Macro names have no spaces");
            String json = Json.write(Json.object("version", 1, "steps", steps));
            s.transact(x -> x.documentProperties(x.document().properties.withSetting("macro." + name, json)));
            return Json.object("recording", false, "name", name, "steps", steps.size());
          }
          if ("run".equals(action)) {
            String json = s.document().properties.setting("macro." + Commands.str(a, "name"), null);
            if (json == null) throw new IllegalArgumentException("No macro " + a.get("name"));
            try {
              new Macros(c).run(s, Json.parse(json), Collections.<String, String>emptyMap(), null);
            } catch (IOException e) {
              throw new IllegalArgumentException("Macro is damaged: " + e.getMessage());
            }
            return Json.object("ran", a.get("name"));
          }
          if (!"list".equals(action)) throw new IllegalArgumentException("Action is start, stop, run or list");
          return Json.object("recording", RECORDING.containsKey(s), "macros", macros(s));
        });
    c.register(
        "tools.autocorrect",
        "AutoCorrect Options: list, add or remove a replacement, toggle an option, or correct a word as typed",
        "action?:string,from?:string,to?:string,option?:string,value?:boolean,text?:string",
        false,
        (s, a) -> {
          String action = a.containsKey("action") ? Commands.str(a, "action") : "list";
          Map<String, String> entries = AppOptions.autocorrect();
          if ("add".equals(action)) {
            entries.put(Commands.str(a, "from"), Commands.str(a, "to"));
            AppOptions.autocorrect(entries);
          } else if ("remove".equals(action)) {
            if (entries.remove(Commands.str(a, "from")) == null) throw new IllegalArgumentException("No entry " + a.get("from"));
            AppOptions.autocorrect(entries);
          } else if ("option".equals(action)) {
            String key = "autocorrect." + Commands.str(a, "option");
            if ("autocorrect.entries".equals(key)) throw new IllegalArgumentException("Use add or remove for entries");
            option(key, String.valueOf(Boolean.TRUE.equals(a.get("value"))));
          } else if ("apply".equals(action)) {
            String text = Commands.str(a, "text"), out = AppOptions.correct(text);
            return Json.object("text", out == null ? text : out, "changed", out != null);
          } else if (!"list".equals(action)) throw new IllegalArgumentException("Action is list, add, remove, option or apply");
          Map<String, Object> out = new LinkedHashMap<String, Object>();
          for (Map.Entry<String, String> e : AppOptions.all().entrySet())
            if (e.getKey().startsWith("autocorrect.") && !e.getKey().equals("autocorrect.entries"))
              out.put(e.getKey().substring(12), Boolean.valueOf(e.getValue()));
          out.put("entries", new LinkedHashMap<String, Object>(AppOptions.autocorrect()));
          return out;
        });
    c.register(
        "references.researcher",
        "Researcher: search online sources for a topic, and add a result as a citation source (cited at the caret)",
        "query:string,cite?:integer",
        true,
        (s, a) -> {
          if (research == null) throw new IllegalStateException("Researcher needs an internet connection provider");
          List<Map<String, String>> results;
          try {
            results = research.search(Commands.str(a, "query"));
          } catch (IOException e) {
            throw new IllegalStateException("Research failed: " + e.getMessage(), e);
          }
          Object tag = null;
          if (a.containsKey("cite")) {
            int i = Commands.integer(a, "cite");
            if (i < 0 || i >= results.size()) throw new IllegalArgumentException("No result " + i);
            Map<String, String> r = results.get(i);
            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("kind", "website");
            for (String k : new String[] {"title", "author", "year", "url"})
              if (r.get(k) != null && !r.get(k).isEmpty()) source.put(k, r.get(k));
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("source", source);
            tag = ((Map<?, ?>) c.execute(s, "references.citation", args)).get("tag");
          }
          List<Object> out = new ArrayList<Object>();
          for (Map<String, String> r : results) out.add(new LinkedHashMap<String, Object>(r));
          return Json.object("results", out, "cited", tag);
        });
    c.register(
        "review.readAloud",
        "Read Aloud: start at the caret, advance to the next or previous sentence, or stop; selects the sentence being read and returns its text and the speaking rate",
        "action?:string,rate?:number",
        false,
        (s, a) -> {
          String action = a.containsKey("action") ? Commands.str(a, "action") : READING.containsKey(s) ? "stop" : "start";
          if ("stop".equals(action)) {
            READING.remove(s);
            return Json.object("reading", false);
          }
          float rate = a.containsKey("rate") ? ((Number) a.get("rate")).floatValue() : 1f;
          if (rate < .25f || rate > 4f) throw new IllegalArgumentException("Rate is 0.25-4");
          Document d = s.document();
          int[] at;
          if ("start".equals(action)) {
            Selection x = s.selection();
            at = new int[] {x.block, Math.min(x.anchor, x.caret)};
          } else {
            at = READING.get(s);
            if (at == null) throw new IllegalStateException("Read Aloud is not running");
            at = "previous".equals(action) ? previousSentence(d, at) : nextSentence(d, at);
          }
          if (at == null) {
            READING.remove(s);
            return Json.object("reading", false, "done", true);
          }
          int[] range = sentence(d, at);
          if (range == null) {
            at = nextSentence(d, at);
            range = at == null ? null : sentence(d, at);
          }
          if (range == null) {
            READING.remove(s);
            return Json.object("reading", false, "done", true);
          }
          READING.put(s, new int[] {range[0], range[1]});
          s.select(range[0], range[1], range[2]);
          return Json.object("reading", true, "block", range[0], "text", s.selectedText(), "rate", rate);
        });
    c.register(
        "review.combine",
        "Combine: merge a reviewer's revised copy (native XML or base64 .docx) into this document as that reviewer's tracked changes",
        "xml?:string,docx?:string,author?:string",
        true,
        (s, a) -> {
          Document revised;
          try {
            if (a.containsKey("xml")) revised = DocumentXml.read(Commands.str(a, "xml"));
            else if (a.containsKey("docx"))
              revised = Docx.read(new java.io.ByteArrayInputStream(com.codename1.util.Base64.decode(Commands.str(a, "docx").getBytes("UTF-8")))).document;
            else throw new IllegalArgumentException("Supply the revised document");
          } catch (IOException e) {
            throw new IllegalArgumentException("Unreadable revised document: " + e.getMessage());
          }
          // The revised copy's own pending changes are its author's final text.
          for (Revisions.Change ch : Revisions.list(revised)) revised = Revisions.resolve(revised, ch.revision.id, true);
          Document base = s.document();
          int pending = Revisions.list(base).size();
          for (Revisions.Change ch : Revisions.list(base)) base = Revisions.resolve(base, ch.revision.id, true);
          DocumentComparison.Options o = new DocumentComparison.Options();
          String author = a.containsKey("author") ? Commands.str(a, "author") : revised.properties.get("lastModifiedBy");
          o.author = author == null || author.trim().isEmpty() ? "Reviewer" : author;
          o.formatting = true;
          DocumentComparison.Result r = DocumentComparison.compare(base, revised, o);
          List<ReviewComment> comments = new ArrayList<ReviewComment>(r.document.comments);
          for (ReviewComment rc : revised.comments) comments.add(rc);
          s.replaceDocument(r.document.comments(comments));
          return Json.object("changes", r.changes, "author", o.author, "acceptedOwnRevisions", pending, "comments", revised.comments.size());
        });
    c.register(
        "view.altTextPane",
        "Alt Text pane: list every picture and object with its alt text, or set one (marks decorative with an empty text)",
        "block?:integer,text?:string",
        true,
        (s, a) -> {
          if (a.containsKey("block")) {
            int b = Commands.integer(a, "block");
            if (b < 0 || b >= s.document().blocks.size() || !(s.document().blocks.get(b) instanceof Picture))
              throw new IllegalArgumentException("Block " + b + " is not a picture or object");
            Picture p = (Picture) s.document().blocks.get(b);
            String text = a.containsKey("text") ? Commands.str(a, "text") : p.alt;
            if (text.length() > 2000) throw new IllegalArgumentException("Alt text is too long");
            s.updatePicture(b, p.width, p.height, text, p.align, false);
          }
          List<Object> out = new ArrayList<Object>();
          int missing = 0;
          for (int i = 0; i < s.document().blocks.size(); i++) {
            Block b = s.document().blocks.get(i);
            if (!(b instanceof Picture)) continue;
            Picture p = (Picture) b;
            if (p.alt == null || p.alt.trim().isEmpty()) missing++;
            out.add(Json.object("block", i, "kind", p.drawing == null ? "picture" : p.drawing.kind, "alt", p.alt == null ? "" : p.alt));
          }
          return Json.object("objects", out, "missing", missing);
        });
  }

  static void registerDropCap(Commands c) {
    c.register(
        "insert.dropCap",
        "Drop Cap: none, dropped into the paragraph or in the margin, with lines to drop (1-10), distance from text and font",
        "position?:string,lines?:integer,distance?:number,font?:string",
        true,
        (s, a) -> {
          String position = a.containsKey("position") ? Commands.str(a, "position") : "drop";
          final String spec =
              "none".equals(position)
                  ? ""
                  : ParagraphFormat.dropCap(
                      position + "," + (a.containsKey("lines") ? Commands.integer(a, "lines") : 3) + ","
                          + (a.containsKey("distance") ? ((Number) a.get("distance")).floatValue() : 0) + ","
                          + (a.containsKey("font") ? Commands.str(a, "font").replace(",", "") : ""));
          if (s.inCell()) throw new IllegalArgumentException("Drop caps apply to body paragraphs, not table cells");
          s.mapParagraphs(p -> p.with("dropCap", spec));
          Paragraph p = s.document().paragraph(s.selection().block);
          return Json.object("position", p.format.dropMode(), "lines", p.format.dropLines(), "distance", p.format.dropDistance(), "font", p.format.dropFont());
        });
  }

  private static void option(String key, String value) {
    if ("numeral".equals(key) && !java.util.Arrays.asList("arabic", "hindi", "context", "system").contains(value))
      throw new IllegalArgumentException("Numerals are arabic, hindi, context or system");
    if ("units".equals(key) && !java.util.Arrays.asList("inches", "centimeters", "millimeters", "points", "picas").contains(value))
      throw new IllegalArgumentException("Units are inches, centimeters, millimeters, points or picas");
    if ("officeTheme".equals(key) && !java.util.Arrays.asList("system", "light", "dark").contains(value))
      throw new IllegalArgumentException("Office Theme is system, light or dark");
    if ("autosaveMinutes".equals(key)) {
      int m;
      try {
        m = Integer.parseInt(value);
      } catch (NumberFormatException e) {
        m = 0;
      }
      if (m < 1 || m > 120) throw new IllegalArgumentException("AutoSave interval is 1-120 minutes");
    }
    String d = AppOptions.DEFAULTS.get(key);
    if (("true".equals(d) || "false".equals(d)) && !"true".equals(value) && !"false".equals(value))
      throw new IllegalArgumentException(key + " is true or false");
    AppOptions.set(key, value);
  }

  private static List<Object> macros(Session s) {
    List<Object> out = new ArrayList<Object>();
    for (Map.Entry<String, String> e : s.document().properties.settings().entrySet())
      if (e.getKey().startsWith("macro.")) out.add(e.getKey().substring(6));
    return out;
  }

  private static Version version(List<Version> list, int i) {
    if (i < 0 || i >= list.size()) throw new IllegalArgumentException("No version " + i);
    return list.get(i);
  }

  private static List<Object> listing(List<Version> list) {
    List<Object> out = new ArrayList<Object>();
    for (int i = 0; i < list.size(); i++)
      out.add(Json.object("index", i, "label", list.get(i).label, "date", list.get(i).date, "words", words(list.get(i).document)));
    return out;
  }

  private static int words(Document d) {
    String t = d.plainText();
    int n = 0;
    boolean in = false;
    for (int i = 0; i < t.length(); i++) {
      boolean w = !Character.isWhitespace(t.charAt(i));
      if (w && !in) n++;
      in = w;
    }
    return n;
  }

  static final List<String> BUILT_IN = java.util.Arrays.asList("Blank", "Letter", "Memo", "Report", "Resume");

  private static String templateName(String name) {
    String n = name.trim();
    if (n.isEmpty() || n.length() > 80 || n.indexOf('/') >= 0 || n.indexOf('\\') >= 0)
      throw new IllegalArgumentException("Invalid template name");
    if (BUILT_IN.contains(n)) throw new IllegalArgumentException(n + " is a built-in template");
    return n;
  }

  private static synchronized List<Object> templateNames() {
    List<Object> out = new ArrayList<Object>(BUILT_IN);
    out.addAll(TEMPLATES.keySet());
    return out;
  }

  static Document template(String name) {
    String xml;
    synchronized (OfficeCommands.class) {
      xml = TEMPLATES.get(name);
    }
    if (xml != null)
      try {
        return DocumentXml.read(xml);
      } catch (IOException e) {
        throw new IllegalStateException("Template " + name + " is damaged: " + e.getMessage(), e);
      }
    if (!BUILT_IN.contains(name)) throw new IllegalArgumentException("No template " + name);
    Session t = new Session(Document.empty());
    if ("Letter".equals(name))
      lines(t, null, "[Your Name]", null, "[Street Address]", null, "[City, ST ZIP]", null, "", null, "[Date]", null, "",
          null, "[Recipient Name]", null, "[Company]", null, "", null, "Dear [Recipient]:", null, "[Body of the letter.]", null, "",
          null, "Sincerely,", null, "", null, "[Your Name]");
    else if ("Memo".equals(name))
      lines(t, "Title", "Memo", null, "To: [Names]", null, "From: [Name]", null, "Date: [Date]", null, "Re: [Subject]",
          "Heading1", "Summary", null, "[Text]");
    else if ("Report".equals(name))
      lines(t, "Title", "[Report Title]", "Subtitle", "[Subtitle]", "Heading1", "Introduction", null, "[Text]",
          "Heading1", "Findings", null, "[Text]", "Heading1", "Conclusion", null, "[Text]");
    else if ("Resume".equals(name))
      lines(t, "Title", "[Your Name]", null, "[Email] | [Phone] | [City]", "Heading1", "Experience", "Heading2",
          "[Job Title], [Company]", null, "[Dates] - [Achievements]", "Heading1", "Education", null, "[Degree], [School]",
          "Heading1", "Skills", null, "[Skills]");
    return t.document();
  }

  /** Alternating style (null = Normal) and text pairs, one paragraph each. */
  private static void lines(Session t, String... pairs) {
    for (int i = 0; i < pairs.length; i += 2) {
      if (i > 0) t.enterParagraph();
      if (!pairs[i + 1].isEmpty()) t.replace(pairs[i + 1]);
      if (pairs[i] != null) t.applyStyle(pairs[i]);
    }
  }

  private static int[] sentence(Document d, int[] at) {
    if (at[0] >= d.blocks.size() || !(d.blocks.get(at[0]) instanceof Paragraph)) return null;
    String t = d.paragraph(at[0]).text.plainText();
    int start = Math.min(at[1], t.length());
    while (start < t.length() && Character.isWhitespace(t.charAt(start))) start++;
    if (start >= t.length()) return null;
    int end = start;
    while (end < t.length()) {
      char ch = t.charAt(end++);
      if ((ch == '.' || ch == '!' || ch == '?') && (end >= t.length() || Character.isWhitespace(t.charAt(end)))) break;
    }
    return new int[] {at[0], start, end};
  }

  private static int[] nextSentence(Document d, int[] at) {
    int[] cur = sentence(d, at);
    if (cur != null) {
      int[] same = sentence(d, new int[] {at[0], cur[2]});
      if (same != null) return new int[] {at[0], same[1]};
    }
    for (int b = at[0] + 1; b < d.blocks.size(); b++)
      if (sentence(d, new int[] {b, 0}) != null) return new int[] {b, 0};
    return null;
  }

  private static int[] previousSentence(Document d, int[] at) {
    int b = at[0], before = at[1];
    while (b >= 0) {
      int[] last = null;
      for (int[] r = sentence(d, new int[] {b, 0}); r != null && r[1] < before; r = sentence(d, new int[] {b, r[2]})) last = r;
      if (last != null) return new int[] {b, last[1]};
      b--;
      before = Integer.MAX_VALUE;
    }
    return null;
  }

  static String iso(long millis) {
    java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
    c.setTime(new java.util.Date(millis));
    return c.get(java.util.Calendar.YEAR) + "-" + TextUtil.padded(c.get(java.util.Calendar.MONTH) + 1, 10, 2) + "-"
        + TextUtil.padded(c.get(java.util.Calendar.DAY_OF_MONTH), 10, 2) + "T"
        + TextUtil.padded(c.get(java.util.Calendar.HOUR_OF_DAY), 10, 2) + ":"
        + TextUtil.padded(c.get(java.util.Calendar.MINUTE), 10, 2) + "Z";
  }
}
