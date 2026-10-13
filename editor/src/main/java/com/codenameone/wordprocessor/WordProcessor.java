package com.codenameone.wordprocessor;

import com.codename1.io.*;
import com.codename1.ui.*;
import com.codename1.ui.layouts.*;
import com.codename1.ui.plaf.*;
import com.codename1.ui.util.UITimer;
import java.io.*;
import java.util.*;

/** Shared application lifecycle for desktop, mobile and web launchers. */
public class WordProcessor {
  public interface Files {
    void open(OpenCallback callback);

    void save(String suggested, byte[] data, SaveCallback callback);
  }

  public interface OpenCallback {
    void opened(String name, byte[] bytes);
  }

  public interface SaveCallback {
    void saved(boolean success, String message);
  }

  private Form form;
  private final DocumentStats statistics = new DocumentStats();
  private String outlineSignature = "";
  private Session session;
  private final Commands commands = new Commands();
  private EditorSurface editor;
  private Label status, title;
  private RecoveryStore recovery;
  private long savedRevision = -1, lastEdit;
  private boolean saving;
  private Docx.Opened source;
  private Files files;
  private final Container outline = new Container(BoxLayout.y());

  public void setFiles(Files files) {
    this.files = files;
  }

  public void init(Object context) {
    Map<String, Object> theme = new HashMap<String, Object>();
    theme.put("Form.bgColor", "f5f7fa");
    theme.put("Label.fgColor", "243447");
    theme.put("Label.bgTransparency", "0");
    theme.put("Label.border", Border.createEmpty());
    theme.put(
        "Tab.font", Font.createTrueTypeFont("native:MainRegular").derive(14, Font.STYLE_PLAIN));
    theme.put(
        "Label.font", Font.createTrueTypeFont("native:MainRegular").derive(14, Font.STYLE_PLAIN));
    theme.put(
        "Button.font", Font.createTrueTypeFont("native:MainRegular").derive(14, Font.STYLE_PLAIN));
    theme.put("Button.fgColor", "243447");
    theme.put("Button.bgColor", "ffffff");
    theme.put("Button.bgTransparency", "255");
    theme.put("Button.padding", "8,8,12,12");
    theme.put("Button.margin", "2,2,2,2");
    theme.put("Button.border", Border.createEmpty());
    theme.put("Label.padding", "4,4,6,6");
    theme.put("TextField.bgColor", "ffffff");
    theme.put("TextField.fgColor", "243447");
    theme.put("TextField.border", Border.createLineBorder(1, 0xd5dee7));
    UIManager.getInstance().setThemeProps(new Hashtable(theme));
    Toolbar.setGlobalToolbar(false);
    recovery = RecoveryStore.local();
  }

  public void start() {
    if (form != null) {
      form.show();
      return;
    }
    Document recovered = recovery.recover();
    source = recovery.recoveredSource();
    session = new Session(recovered == null ? sample() : recovered);
    form = new Form(new BorderLayout());
    form.setTitle("CN1 Word Processor");
    title = new Label(session.document().title);
    title.getAllStyles().setFgColor(0xffffff);
    title.getAllStyles().setBgTransparency(0);
    title.getAllStyles().setBorder(Border.createEmpty());
    title
        .getAllStyles()
        .setFont(Font.createSystemFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_LARGE));
    Label brand = new Label("WRITE / ONE");
    brand.getAllStyles().setFgColor(0x81d8d0);
    brand.getAllStyles().setBgTransparency(0);
    brand.getAllStyles().setBorder(Border.createEmpty());
    Container top = new Container(new BorderLayout());
    top.getAllStyles().setBgColor(0x152a3c);
    top.getAllStyles().setBgTransparency(255);
    top.getAllStyles().setPadding(12, 12, 16, 16);
    top.add(BorderLayout.WEST, brand).add(BorderLayout.CENTER, title);
    Button rename =
        button(
            "Rename",
            () ->
                prompt(
                    "Document name",
                    session.document().title,
                    text -> run("document.title", Json.object("title", text))));
    top.add(BorderLayout.EAST, rename);
    editor = new EditorSurface(session, commands, this::edited, this::editCell);
    Tabs ribbon = new Tabs();
    ribbon.setUIID("Ribbon");
    ribbon.addTab("Home", home());
    ribbon.addTab("Insert", insert());
    ribbon.addTab("Layout", pageLayout());
    ribbon.addTab("Automate", automation());
    ribbon.addTab("File", file());
    Container north = BoxLayout.encloseY(top, ribbon);
    form.add(BorderLayout.NORTH, north);
    Container main = new Container(new BorderLayout());
    outline.getAllStyles().setPadding(14, 14, 12, 12);
    outline.getAllStyles().setBgColor(0xf5f7fa);
    outline.getAllStyles().setBgTransparency(255);
    outline.setPreferredW(230);
    outline.setScrollableY(true);
    if (Display.getInstance().getDisplayWidth() > 800) main.add(BorderLayout.WEST, outline);
    main.add(BorderLayout.CENTER, editor);
    form.add(BorderLayout.CENTER, main);
    status = new Label();
    Container footer = new Container(new BorderLayout());
    footer.getAllStyles().setPadding(4, 4, 8, 8);
    footer.add(BorderLayout.CENTER, status);
    Container zoom =
        FlowLayout.encloseCenter(
            button(
                "−",
                () -> {
                  editor.setZoom(editor.getZoom() - .1f);
                  updateStatus();
                }),
            button(
                "Fit page",
                () -> {
                  editor.fitWidth();
                  updateStatus();
                }),
            button(
                "+",
                () -> {
                  editor.setZoom(editor.getZoom() + .1f);
                  updateStatus();
                }));
    footer.add(BorderLayout.EAST, zoom);
    form.add(BorderLayout.SOUTH, footer);
    updateOutline();
    updateStatus();
    form.show();
    editor.fitWidth();
    updateStatus();
    new UITimer(() -> autosave()).schedule(1000, true, form);
  }

  private Container home() {
    return FlowLayout.encloseIn(
        button("Undo", () -> run("edit.undo", Json.object())),
        button("Redo", () -> run("edit.redo", Json.object())),
        button("B", () -> format("bold")),
        button("I", () -> format("italic")),
        button("U", () -> format("underline")),
        button("Strike", () -> format("strike")),
        button(
            "Size",
            () ->
                prompt(
                    "Font size",
                    "12",
                    v -> run("format.set", Json.object("property", "size", "value", v)))),
        button("Body", () -> heading(0)),
        button("Heading 1", () -> heading(1)),
        button("Heading 2", () -> heading(2)),
        button("Left", () -> paragraph("align", "left")),
        button("Center", () -> paragraph("align", "center")),
        button("Right", () -> paragraph("align", "right")),
        button("Find / Replace", this::find));
  }

  private Container insert() {
    return FlowLayout.encloseIn(
        button(
            "Table",
            () -> {
              TextField rows = new TextField("3"), cols = new TextField("3");
              if (showDialog(
                  "Insert table",
                  BoxLayout.encloseY(new Label("Rows"), rows, new Label("Columns"), cols),
                  "Insert",
                  "Cancel"))
                safe(
                    () ->
                        run(
                            "table.insert",
                            Json.object(
                                "rows",
                                Integer.parseInt(rows.getText()),
                                "columns",
                                Integer.parseInt(cols.getText()))));
            }),
        button("Page break", () -> paragraph("pageBreak", "true")),
        button(
            "Link",
            () ->
                prompt(
                    "Link URL",
                    "https://",
                    url -> run("format.set", Json.object("property", "link", "value", url)))));
  }

  private Container pageLayout() {
    return FlowLayout.encloseIn(
        button("A4", () -> setPage(595.28f, 841.89f)),
        button("Letter", () -> setPage(612, 792)),
        button(
            "Landscape",
            () -> setPage(session.document().page.height, session.document().page.width)),
        button(
            "Paragraph spacing",
            () -> prompt("Space after paragraph (pt)", "6", v -> paragraph("after", v))),
        button(
            "Line spacing",
            () -> prompt("Line spacing multiplier", "1.2", v -> paragraph("spacing", v))),
        button("Indent", () -> paragraph("indent", "24")),
        button("No indent", () -> paragraph("indent", "0")));
  }

  private Container automation() {
    return FlowLayout.encloseIn(
        button("Commands", this::commandDialog),
        button("Preview macro", this::macroDialog),
        button(
            "Inspect writing",
            () ->
                safe(
                    () ->
                        showText(
                            "Writing checks",
                            Json.write(commands.execute(session, "quality.inspect"))))),
        button(
            "Fill template",
            () -> {
              TextField key = new TextField("name"), value = new TextField("Ada");
              if (showDialog(
                  "Fill {{field}}",
                  BoxLayout.encloseY(new Label("Field"), key, new Label("Value"), value),
                  "Preview",
                  "Cancel")) {
                Map<String, Object> macro =
                    Json.object(
                        "version",
                        1,
                        "steps",
                        Arrays.asList(
                            Json.object(
                                "command",
                                "template.fill",
                                "args",
                                Json.object(
                                    "values", Json.object(key.getText(), value.getText())))));
                preview(macro);
              }
            }));
  }

  private Container file() {
    return FlowLayout.encloseIn(
        button(
            "New",
            () -> {
              if (showDialog(
                  "New document",
                  "Start a new document? Save or export this document first.",
                  "New",
                  "Cancel")) {
                session.loadDocument(Document.empty());
                editor.resetScroll();
                source = null;
                edited();
              }
            }),
        button("Open", this::open),
        button("Save .cn1doc", () -> save("cn1doc")),
        button("Export DOCX", () -> save("docx")),
        button("Export text", () -> save("txt")),
        button("Export PDF", () -> save("pdf")),
        button("Export HTML", () -> save("html")),
        button("Export Markdown", () -> save("md")),
        button("Export RTF", () -> save("rtf")),
        button(
            "About",
            () ->
                showDialog(
                    "CN1 Word Processor",
                    "An early document-engine implementation. DOCX compatibility is limited."
                        + " Wordcraft parity and cross-platform release validation are still"
                        + " pending.",
                    "OK",
                    null)));
  }

  private static boolean showDialog(String title, String body, String ok, String cancel) {
    return Dialog.show(title, body, ok, cancel);
  }

  private static boolean showDialog(String title, Component body, String ok, String cancel) {
    Command yes = new Command(ok);
    Command result =
        cancel == null
            ? Dialog.show(title, body, yes)
            : Dialog.show(title, body, yes, new Command(cancel));
    return result == yes;
  }

  private void setPage(float w, float h) {
    Selection x = session.selection();
    session.replaceDocument(session.document().page(new PageSpec(w, h, 54, 54, 54, 54)));
    session.selectRange(x.anchorBlock, x.anchor, x.block, x.caret);
    edited();
  }

  private void paragraph(String key, String value) {
    run("paragraph.set", Json.object("property", key, "value", value));
  }

  private void format(String property) {
    Selection x = session.selection();
    TextStyle style = session.activeStyle();
    boolean current =
        "bold".equals(property)
            ? style.bold
            : "italic".equals(property)
                ? style.italic
                : "underline".equals(property) ? style.underline : style.strike;
    run("format.set", Json.object("property", property, "value", Boolean.toString(!current)));
  }

  private void heading(int level) {
    Selection x = session.selection();
    session.transact(
        s -> {
          for (int i = x.startBlock(); i <= x.endBlock(); i++) {
            if (!(s.document().blocks.get(i) instanceof Paragraph)) continue;
            s.select(i, 0, s.document().paragraph(i).text.length());
            s.paragraph("style", level == 0 ? "Normal" : "Heading" + level);
            s.format("size", level == 1 ? "26" : level == 2 ? "18" : "12");
            s.format("bold", Boolean.toString(level > 0));
          }
          s.selectRange(x.anchorBlock, x.anchor, x.block, x.caret);
        });
    edited();
  }

  private interface TextValue {
    void accept(String value);
  }

  private void prompt(String name, String initial, TextValue callback) {
    editor.stopInput();
    TextField field = new TextField(initial);
    if (showDialog(name, field, "Apply", "Cancel")) safe(() -> callback.accept(field.getText()));
  }

  private Button button(String text, Runnable action) {
    Button b = new Button(text);
    b.addActionListener(
        e -> {
          if (editor != null) {
            editor.finishComposing();
            editor.stopInput();
          }
          safe(action);
        });
    return b;
  }

  private void safe(Runnable action) {
    try {
      action.run();
    } catch (Exception ex) {
      showDialog(
          "Cannot complete action",
          ex.getMessage() == null ? ex.toString() : ex.getMessage(),
          "OK",
          null);
    }
  }

  private void run(String id, Map<String, Object> args) {
    safe(
        () -> {
          commands.execute(session, id, args);
          edited();
        });
  }

  private void edited() {
    lastEdit = System.currentTimeMillis();
    title.setText(session.document().title);
    editor.revealSelection();
    updateOutline();
    updateStatus();
  }

  private void updateStatus() {
    int words = statistics.words(session.document());
    status.setText(
        words
            + " words   ·   "
            + editor.layout().pages.size()
            + " pages   ·   "
            + Math.round(editor.getZoom() * 100)
            + "%   ·   "
            + (savedRevision == session.revision() ? "Recovery copy saved" : "Unsaved changes"));
  }

  private void updateOutline() {
    StringBuilder signature = new StringBuilder();
    for (int i = 0; i < session.document().blocks.size(); i++) {
      Block block = session.document().blocks.get(i);
      if (block instanceof Paragraph) {
        Paragraph p = (Paragraph) block;
        if (p.style.startsWith("Heading"))
          signature.append(i).append(':').append(p.id).append(':').append(p.text).append('\n');
      }
    }
    if (outlineSignature.equals(signature.toString())) return;
    outlineSignature = signature.toString();
    outline.removeAll();
    Label label = new Label("DOCUMENT OUTLINE");
    label.getAllStyles().setFgColor(0x748494);
    outline.add(label);
    for (int i = 0; i < session.document().blocks.size(); i++) {
      Block block = session.document().blocks.get(i);
      if (block instanceof Paragraph) {
        Paragraph p = (Paragraph) block;
        if (p.style.startsWith("Heading")) {
          final int index = i;
          Button b =
              button(
                  p.text.toString(),
                  () -> {
                    session.select(index, 0, 0);
                    editor.revealSelection();
                    editor.startInput();
                  });
          b.getAllStyles().setAlignment(Component.LEFT);
          outline.add(b);
        }
      }
    }
    outline.revalidate();
  }

  private void editCell(int block, int row, int col) {
    Table t = (Table) session.document().blocks.get(block);
    prompt(
        "Table · row " + (row + 1) + ", column " + (col + 1),
        t.cell(row, col).get(0).text.toString(),
        value ->
            run(
                "table.setCell",
                Json.object("block", block, "row", row, "column", col, "text", value)));
  }

  private void find() {
    TextField query = new TextField(), replace = new TextField();
    query.setHint("Find text");
    replace.setHint("Replace with");
    Button find =
        button(
            "Find next",
            () -> {
              java.util.List<Map<String, Object>> matches =
                  Commands.find(session.document(), query.getText());
              if (matches.isEmpty()) showDialog("Find", "No matches", "OK", null);
              else {
                Selection current = session.selection();
                Map<String, Object> match = matches.get(0);
                for (Map<String, Object> m : matches)
                  if (Commands.integer(m, "block") > current.block
                      || Commands.integer(m, "block") == current.block
                          && Commands.integer(m, "start") >= current.end()) {
                    match = m;
                    break;
                  }
                session.select(
                    Commands.integer(match, "block"),
                    Commands.integer(match, "start"),
                    Commands.integer(match, "end"));
                editor.refresh();
              }
            });
    Button all =
        button(
            "Replace all",
            () ->
                run(
                    "replace.all",
                    Json.object("text", query.getText(), "replacement", replace.getText())));
    showDialog(
        "Find and replace",
        BoxLayout.encloseY(query, replace, FlowLayout.encloseCenter(find, all)),
        "Close",
        null);
  }

  private void commandDialog() {
    TextField command = new TextField("document.inspect");
    TextArea args = new TextArea("{}", 5, 45);
    StringBuilder available = new StringBuilder();
    for (Commands.Descriptor d : commands.descriptors())
      available.append(d.id).append(" — ").append(d.description).append('\n');
    if (showDialog(
        "Execute command",
        BoxLayout.encloseY(command, args, new SpanLabelCompat(available.toString())),
        "Run",
        "Cancel"))
      safe(
          () -> {
            try {
              Object result =
                  commands.execute(session, command.getText(), Json.parse(args.getText()));
              edited();
              if (result != null) showText("Result", Json.write(result));
            } catch (IOException ex) {
              throw new IllegalArgumentException(ex.getMessage());
            }
          });
  }

  private void macroDialog() {
    TextArea area =
        new TextArea(
            "{\"version\":1,\"steps\":[{\"command\":\"replace.all\",\"args\":{\"text\":\"document\",\"replacement\":\"manuscript\"}}]}",
            9,
            50);
    if (showDialog("Preview a macro", area, "Preview", "Cancel"))
      safe(
          () -> {
            try {
              preview(Json.parse(area.getText()));
            } catch (IOException ex) {
              throw new IllegalArgumentException(ex.getMessage());
            }
          });
  }

  private void preview(Map<String, Object> macro) {
    Macros.Preview p =
        new Macros(commands).preview(session, macro, Collections.<String, String>emptyMap(), null);
    TextArea after = new TextArea(join(p.changes) + "\n" + p.after, 12, 55);
    after.setEditable(false);
    if (showDialog("Proposed document — one undo step", after, "Apply", "Cancel")) {
      p.apply(session);
      edited();
    }
  }

  private void showText(String title, String text) {
    TextArea area = new TextArea(text, 15, 60);
    area.setEditable(false);
    showDialog(title, area, "Close", null);
  }

  private void autosave() {
    if (saving
        || session.isComposing()
        || session.revision() == savedRevision
        || System.currentTimeMillis() - lastEdit < 1000) return;
    final Document snapshot = session.document();
    final long revision = session.revision();
    final Docx.Opened original = source;
    saving = true;
    Display.getInstance()
        .scheduleBackgroundTask(
            () -> {
              String error = null;
              try {
                recovery.save(snapshot, original, revision);
              } catch (IOException ex) {
                error = ex.getMessage();
              }
              final String problem = error;
              Display.getInstance()
                  .callSerially(
                      () -> {
                        saving = false;
                        if (problem == null) {
                          savedRevision = Math.max(savedRevision, revision);
                          updateStatus();
                        } else status.setText("Recovery save failed: " + problem);
                      });
            });
  }

  private void open() {
    if (files == null) {
      showDialog("Open", "A platform file adapter has not been installed.", "OK", null);
      return;
    }
    if (!showDialog(
        "Open document",
        "Opening another file replaces this editing session. Save or export your changes first.",
        "Open",
        "Cancel")) return;
    files.open(
        (name, bytes) ->
            safe(
                () -> {
                  try {
                    Document document;
                    if (name.toLowerCase().endsWith(".docx")) {
                      Docx.Opened opened = Docx.read(new ByteArrayInputStream(bytes));
                      if (!opened.warnings.isEmpty()
                          && !showDialog(
                              "Compatibility report",
                              new SpanLabelCompat(join(opened.warnings)),
                              "Open copy",
                              "Cancel")) return;
                      source = opened;
                      document = opened.document;
                    } else if (name.toLowerCase().endsWith(".cn1doc")) {
                      ProjectFile.Opened project = ProjectFile.read(bytes);
                      document = project.document;
                      source = project.source;
                    } else {
                      String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
                      RichTextFormat format =
                          "md".equals(ext)
                              ? RichTextFormat.MARKDOWN
                              : "rtf".equals(ext)
                                  ? RichTextFormat.RTF
                                  : "html".equals(ext)
                                      ? RichTextFormat.HTML
                                      : "adoc".equals(ext)
                                          ? RichTextFormat.ASCIIDOC
                                          : RichTextFormat.PLAIN_TEXT;
                      document = TextFormats.read(new String(bytes, "UTF-8"), format).title(name);
                      source = null;
                    }
                    session.loadDocument(document);
                    editor.resetScroll();
                    edited();
                  } catch (IOException ex) {
                    throw new IllegalArgumentException(ex.getMessage());
                  }
                }));
  }

  private void save(String format) {
    if (files == null) {
      showDialog("Save", "A platform file adapter has not been installed.", "OK", null);
      return;
    }
    safe(
        () -> {
          try {
            byte[] bytes;
            if ("docx".equals(format)) {
              boolean allow = false;
              java.util.List<String> losses = Docx.conversionWarnings(session.document(), source);
              if (!losses.isEmpty()) {
                allow =
                    showDialog(
                        "Export converted copy",
                        new SpanLabelCompat(
                            "The following features may be lost from the edited copy:\n"
                                + join(losses)),
                        "Export copy",
                        "Cancel");
                if (!allow) return;
              }
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              Docx.write(session.document(), source, out, allow);
              bytes = out.toByteArray();
            } else if ("pdf".equals(format))
              bytes = PdfExporter.write(session.document().page, editor.layout());
            else if ("cn1doc".equals(format)) bytes = ProjectFile.write(session.document(), source);
            else {
              RichTextFormat type =
                  "md".equals(format)
                      ? RichTextFormat.MARKDOWN
                      : "rtf".equals(format)
                          ? RichTextFormat.RTF
                          : "html".equals(format) ? RichTextFormat.HTML : RichTextFormat.PLAIN_TEXT;
              if (type != RichTextFormat.HTML && type != RichTextFormat.PLAIN_TEXT)
                for (Block b : session.document().blocks)
                  if (b instanceof Table)
                    throw new IOException(
                        "Use DOCX, HTML or native project format to retain tables");
              bytes = TextFormats.write(session.document(), type).getBytes("UTF-8");
            }
            files.save(
                session.document().title.replaceAll("[^a-zA-Z0-9_-]", "_") + "." + format,
                bytes,
                (ok, message) ->
                    status.setText(ok ? "Saved " + message : "Save failed: " + message));
          } catch (IOException ex) {
            throw new IllegalArgumentException(ex.getMessage());
          }
        });
  }

  private static String join(java.util.List<String> list) {
    StringBuilder b = new StringBuilder();
    for (String s : list) b.append("• ").append(s).append('\n');
    return b.toString();
  }

  public void stop() {
    if (session != null)
      try {
        editor.finishComposing();
        recovery.save(session.document(), source, session.revision());
      } catch (IOException e) {
        Log.e(e);
      }
  }

  public void destroy() {
    stop();
  }

  private static final class SpanLabelCompat extends com.codename1.components.SpanLabel {
    SpanLabelCompat(String s) {
      super(s);
    }
  }

  public static Document sample() {
    Session s = new Session(Document.empty());
    s.replace(
        "A better place for words.\n"
            + "A document engine built with Codename One\n"
            + "Write, revise, and automate from one shared Java codebase. This canvas uses a"
            + " structured document model, cached paragraph layout, and paginated rendering.\n"
            + "Your ideas, beautifully structured\n"
            + "Select text to format it. Use the Home tab for headings and alignment, Insert for"
            + " tables, and Layout to change the page. Every edit is undoable.\n"
            + "Automation you can inspect\n"
            + "Open Automate and preview a macro. Changes run on an isolated document; applying"
            + " them creates one undo step. If the document changes first, the preview cannot"
            + " overwrite it.\n"
            + "Dear {{name}}, thank you for trying a different kind of document editor.\n"
            + "A foundation with honest boundaries\n"
            + "This development build supports an initial DOCX subset. It reports unsupported"
            + " content before conversion and preserves the original package when exported without"
            + " edits.");
    s.select(0, 0, s.document().paragraph(0).text.length());
    s.format("size", "30");
    s.format("bold", "true");
    s.format("color", "17364a");
    s.paragraph("style", "Heading1");
    s.select(1, 0, s.document().paragraph(1).text.length());
    s.format("size", "15");
    s.format("color", "087f80");
    for (int i : new int[] {3, 5, 8}) {
      s.select(i, 0, s.document().paragraph(i).text.length());
      s.format("size", "18");
      s.format("bold", "true");
      s.format("color", "17364a");
      s.paragraph("style", "Heading2");
      s.paragraph("before", "12");
    }
    s.select(0, 0, 0);
    return s.document().title("The writing studio");
  }
}
