package com.codenameone.wordprocessor;

import com.codename1.io.*;
import com.codename1.ui.*;
import com.codename1.ui.accessibility.*;
import com.codename1.ui.layouts.*;
import com.codename1.ui.plaf.*;
import com.codename1.ui.util.UITimer;
import java.io.*;
import java.util.*;

/** Shared application lifecycle for desktop, mobile and web launchers. */
public class WordProcessor {
  public interface Files {
    void open(OpenCallback callback);

    void openImage(OpenCallback callback);

    default void openRecipients(OpenCallback callback) {
      open(callback);
    }

    void save(String suggested, String existingPath, byte[] data, SaveCallback callback);
  }

  public interface OpenCallback {
    void opened(String name, String path, byte[] bytes);
  }

  public interface SaveCallback {
    void saved(boolean success, String path, String message);
  }

  private Form form;
  private final DocumentStats statistics = new DocumentStats();
  private String outlineSignature = "";
  private Session session;
  private final Commands commands = new Commands();
  private EditorSurface editor;
  private Label status, zoomValue;
  private Button readView, printView, webView, language;
  private Label mergeStatus;
  private Container workspace, navigation, footer;
  private boolean backstage;
  private PageRuler ruler;
  private VerticalRuler verticalRuler;
  private Slider zoomSlider;
  private Button quickUndo, quickRedo, documentTitle;
  private final Map<String, Runnable> uiActions = new LinkedHashMap<>();
  private final Map<String, Container> styleTiles = new LinkedHashMap<>();
  private OfficeRibbon ribbon;
  private TextStyle paintStyle;
  private Button painterButton;
  private ComboBox<String> fontChoice, sizeChoice;
  private final Map<String, Button> formatButtons = new HashMap<>();
  private boolean syncingControls;
  private RecoveryStore recovery;
  private long savedRevision = -1, lastEdit;
  private boolean saving;
  private Docx.Opened source;
  private Files files;
  private String filePath, fileFormat = "cn1doc";
  private Document fileDocument;
  /** The password the DOCX at filePath is encrypted with (null when it is not). */
  private String filePassword;
  private int documentEpoch;

  /**
   * Whether the file at filePath holds the current document: the same content and, for DOCX,
   * the same encryption. Adding or removing a password changes only the saved file.
   */
  private boolean saved() {
    return fileDocument == session.document()
        && (!"docx".equals(fileFormat)
            || java.util.Objects.equals(filePassword, OfficeCommands.password(session)));
  }

  private void forgetFile() {
    filePath = null;
    fileFormat = "cn1doc";
    fileDocument = null;
    filePassword = null;
    documentEpoch++;
  }

  private void saveDocument(boolean asNew) {
    if (!asNew) {
      save(fileFormat, false, true);
      return;
    }
    var format =
        new ComboBox<String>("Word document (.docx)", "CN1 document (.cn1doc)");
    format.setSelectedIndex("docx".equals(fileFormat) ? 0 : 1);
    if (showDialog("Save As", format, "Choose location", "Cancel"))
      save(format.getSelectedIndex() == 0 ? "docx" : "cn1doc", true, true);
  }

  private final Container outline = new Container(BoxLayout.y());

  public void setFiles(Files files) {
    this.files = files;
  }

  public void init(Object context) {
    OfficeTheme.install();
    PictureCommands.codec(WordProcessor::resample);
    recovery = RecoveryStore.local();
    installOfficeServices();
  }

  /** Options, templates and Researcher persist in app storage; Researcher queries Wikipedia. */
  private static void installOfficeServices() {
    final Storage storage = Storage.getInstance();
    var options = new LinkedHashMap<String, String>();
    try {
      if (storage.exists("wordprocessor-options.json"))
        for (Map.Entry<String, Object> e : Json.parse(new String(readStorage(storage, "wordprocessor-options.json"), "UTF-8")).entrySet())
          options.put(e.getKey(), String.valueOf(e.getValue()));
    } catch (IOException ex) {
      Log.e(ex);
    }
    AppOptions.install(
        values -> {
          try (var out = storage.createOutputStream("wordprocessor-options.json")) {
            out.write(com.codename1.io.JSONParser.toJson(new LinkedHashMap<String, Object>(values)).getBytes("UTF-8"));
          } catch (IOException ex) {
            Log.e(ex);
          }
        },
        options);
    var templates = new LinkedHashMap<String, String>();
    for (String key : storage.listEntries())
      if (key.startsWith("template-") && key.endsWith(".cn1xml"))
        try {
          templates.put(key.substring(9, key.length() - 7), new String(readStorage(storage, key), "UTF-8"));
        } catch (IOException ex) {
          Log.e(ex);
        }
    OfficeCommands.templates(
        new OfficeCommands.TemplateStore() {
          public void save(String name, String xml) throws IOException {
            try (var out = storage.createOutputStream("template-" + name + ".cn1xml")) {
              out.write(xml.getBytes("UTF-8"));
            }
          }

          public void delete(String name) {
            storage.deleteStorageFile("template-" + name + ".cn1xml");
          }
        },
        templates);
    OfficeCommands.research(WordProcessor::wikipedia);
  }

  private static byte[] readStorage(Storage storage, String key) throws IOException {
    try (var in = storage.createInputStream(key)) {
      return com.codename1.io.Util.readInputStream(in);
    }
  }

  /** Researcher: Wikipedia's search API (title, summary, URL), run off the EDT. */
  static java.util.List<Map<String, String>> wikipedia(String query) throws IOException {
    var r = new ConnectionRequest();
    r.setPost(false);
    r.setUrl("https://en.wikipedia.org/w/api.php");
    r.addArgument("action", "query");
    r.addArgument("list", "search");
    r.addArgument("srsearch", query);
    r.addArgument("srlimit", "8");
    r.addArgument("format", "json");
    r.addRequestHeader("User-Agent", "CN1WordProcessor/1.0");
    r.setFailSilently(true);
    NetworkManager.getInstance().addToQueueAndWait(r);
    if (r.getResponseCode() != 200 || r.getResponseData() == null)
      throw new IOException("Researcher could not reach Wikipedia (" + r.getResponseCode() + ")");
    var json = Json.parse(new String(r.getResponseData(), "UTF-8"));
    var out = new ArrayList<Map<String, String>>();
    var q = json.get("query");
    if (!(q instanceof Map)) return out;
    var hits = ((Map<?, ?>) q).get("search");
    if (!(hits instanceof java.util.List)) return out;
    String year = String.valueOf(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
    for (Object h : (java.util.List<?>) hits) {
      if (!(h instanceof Map)) continue;
      String title = String.valueOf(((Map<?, ?>) h).get("title"));
      String snippet = String.valueOf(((Map<?, ?>) h).get("snippet"));
      var plain = new StringBuilder();
      boolean tag = false;
      for (char c : snippet.toCharArray()) {
        if (c == '<') tag = true;
        else if (c == '>') tag = false;
        else if (!tag) plain.append(c);
      }
      var m = new LinkedHashMap<String, String>();
      m.put("title", title);
      m.put("author", "Wikipedia contributors");
      m.put("year", year);
      m.put("url", "https://en.wikipedia.org/wiki/" + com.codename1.io.Util.encodeUrl(title.replace(' ', '_')));
      m.put("summary", plain.toString().replace("&quot;", "\"").replace("&amp;", "&"));
      out.add(m);
    }
    return out;
  }

  public void start() {
    if (form != null) {
      form.show();
      startAgentDebugging();
      return;
    }
    var recovered = recovery.recover();
    source = recovery.recoveredSource();
    recovered = offerRecovery(recovered);
    session = new Session(recovered == null ? Document.empty().title("Document1") : recovered);
    if (recovered == null) fileDocument = session.document();
    form =
        new Form(new BorderLayout()) {
          @Override
          public String getTitle() {
            return session.document().title + (OfficeTheme.mobile() ? "" : " \u2014 CN1 Word Processor");
          }
        };
    form.setTitle(session.document().title + (OfficeTheme.mobile() ? "" : " \u2014 CN1 Word Processor"));
    if (Display.getInstance().isDesktop()) Display.getInstance().refreshNativeTitle();
    editor = new EditorSurface(session, commands, this::edited, this::editCell);
    editor.setPictureEditor(this::editPicture);
    registerViewCommands();
    registerCaretCommands();
    ribbon = new OfficeRibbon();
    ribbon.addActionTab("File", this::showBackstage);
    ribbon.addTab("Home", home());
    ribbon.addTab("Insert", insert());
    ribbon.addTab("Draw", draw());
    ribbon.addTab("Design", design());
    ribbon.addTab("Layout", pageLayout());
    ribbon.addTab("References", references());
    ribbon.addTab("Mailings", mailings());
    ribbon.addTab("Review", review());
    ribbon.addTab("View", view());
    ribbon.addTab("Developer", developer());
    ribbon.addTab("Automate", automation());
    ribbon.addTab("Help", help());
    ribbon.addTab("Table Layout", tableRibbon());
    ribbon.setTabVisible("Table Layout", false);
    customTab = OfficeTheme.row();
    ribbon.addTab("Custom", customTab);
    applyRibbonOptions();
    ribbon.select(0);
    if (OfficeTheme.mobile()) ribbon.toggleCollapsed();
    var commentsButton = iconButton("Comments", FontImage.MATERIAL_COMMENT, this::comments, false);
    editingButton = iconButton("Editing \u25be", FontImage.MATERIAL_EDIT, this::editingMode, false);
    editingButton.setAccessibilityText("Editing mode");
    var share = iconButton("Share", FontImage.MATERIAL_IOS_SHARE, this::share, false);
    OfficeTheme.framed(commentsButton);
    OfficeTheme.framed(editingButton);
    OfficeTheme.primary(share);
    // Word's tab-row actions: 28pt tall, inset from the strip and 6pt apart.
    commentsButton.setAccessibilityText("Comments");
    share.setAccessibilityText("Share");
    for (Button b : new Button[] {commentsButton, editingButton, share}) {
      // A phone's tab row has no room for labels; the icons carry the meaning there.
      if (OfficeTheme.mobile()) b.setText("");
      OfficeTheme.space(b, 4, 4, 10, 10);
      b.getAllStyles().setMarginUnit(Style.UNIT_TYPE_PIXELS);
      b.getAllStyles().setMargin(OfficeTheme.px(4), OfficeTheme.px(4), OfficeTheme.px(3), OfficeTheme.px(3));
    }
    ribbon.actions(OfficeTheme.row(commentsButton, editingButton, share));
    if (Display.getInstance().isDesktop()) form.setToolbar(new OfficeTitleBar(titleBar()));
    MacScrolling.install(form);
    form.add(BorderLayout.NORTH, ribbon);
    workspace = new Container(new BorderLayout());
    OfficeTheme.surface(workspace, 0xe7e7e7);
    navigation = new Container(new BorderLayout());
    OfficeTheme.surface(navigation, 0xfafafa);
    OfficeTheme.space(navigation, 14, 10, 12, 12);
    navigation.setPreferredW(OfficeTheme.px(210));
    navigation
        .getAllStyles()
        .setBorder(
            Border.createCompoundBorder(null, null, null, Border.createLineBorder(1, 0xd0d0d0)));
    var search = field();
    search.setHint("Search document");
    search.setAccessibilityText("Search document");
    OfficeTheme.field(search, 184);
    search.addFocusListener(
        new com.codename1.ui.events.FocusListener() {
          public void focusGained(Component c) {
            editor.stopInput();
          }

          public void focusLost(Component c) {}
        });
    search.addDataChangedListener((type, index) -> showNavigation(search.getText()));
    Label navTitle = OfficeTheme.label("Navigation", 18, 0x333333);
    OfficeTheme.space(navTitle, 0, 10, 0, 0);
    navigation.add(BorderLayout.NORTH, BoxLayout.encloseY(navTitle, search));
    OfficeTheme.space(outline, 10, 0, 0, 0);
    outline.setScrollableY(true);
    navigation.add(BorderLayout.CENTER, outline);
    var documentPane = new Container(new BorderLayout());
    ruler = new PageRuler(editor, session, this::edited);
    verticalRuler = new VerticalRuler(editor, session, this::edited);
    if (!OfficeTheme.mobile()) {
      documentPane.add(BorderLayout.NORTH, ruler);
      documentPane.add(BorderLayout.WEST, verticalRuler);
    }
    documentPane.add(BorderLayout.CENTER, editor);
    workspace.add(BorderLayout.CENTER, documentPane);
    form.add(BorderLayout.CENTER, workspace);
    status = OfficeTheme.label("", 11, OfficeTheme.DIM);
    zoomValue = OfficeTheme.label("100%", 11, OfficeTheme.DIM);
    footer = new Container(new BorderLayout());
    OfficeTheme.surface(footer, OfficeTheme.STRIP);
    OfficeTheme.space(footer, 4, 4, 12, 12);
    footer
        .getAllStyles()
        .setBorder(
            Border.createCompoundBorder(Border.createLineBorder(1, OfficeTheme.BORDER), null, null, null));
    language = button("English (United States)", () -> runPrompted("review.language"));
    language.setAccessibilityText("Proofing language");
    language.getAllStyles().setFont(OfficeTheme.font(11, false));
    language.getAllStyles().setFgColor(OfficeTheme.DIM);
    OfficeTheme.space(language, 0, 0, 14, 4);
    footer.add(BorderLayout.CENTER, OfficeTheme.row(status, language));
    zoomSlider = new Slider();
    zoomSlider.setEditable(true);
    zoomSlider.setAccessibilityText("Zoom");
    zoomSlider.setMinValue(35);
    zoomSlider.setMaxValue(250);
    zoomSlider.setProgress(100);
    zoomSlider.setPreferredW(OfficeTheme.px(110));
    zoomSlider.setPreferredH(OfficeTheme.px(18));
    OfficeTheme.space(zoomSlider, 6, 6, 0, 0);
    for (Style s :
        new Style[] {
          zoomSlider.getSliderEmptyUnselectedStyle(), zoomSlider.getSliderEmptySelectedStyle()
        }) {
      s.setBgColor(0xc4c4c4);
      s.setBgTransparency(255);
      s.setBorder(Border.createEmpty());
    }
    for (Style s :
        new Style[] {
          zoomSlider.getSliderFullUnselectedStyle(), zoomSlider.getSliderFullSelectedStyle()
        }) {
      s.setBgColor(0xc4c4c4);
      s.setBgTransparency(255);
      s.setBorder(Border.createEmpty());
    }
    // Word's zoom thumb: a small upright white bar with a gray outline, no accent fill.
    Image thumb = Image.createImage(OfficeTheme.px(6), OfficeTheme.px(14), 0);
    var tg = thumb.getGraphics();
    tg.setAntiAliased(true);
    tg.setColor(0xffffff);
    tg.fillRoundRect(0, 0, thumb.getWidth() - 1, thumb.getHeight() - 1, OfficeTheme.px(2), OfficeTheme.px(2));
    tg.setColor(0x707070);
    tg.drawRoundRect(0, 0, thumb.getWidth() - 1, thumb.getHeight() - 1, OfficeTheme.px(2), OfficeTheme.px(2));
    zoomSlider.setThumbImage(thumb);
    zoomSlider.addDataChangedListener(
        (type, index) -> {
          if (!syncingControls) {
            editor.setZoom(zoomSlider.getProgress() / 100f);
            updateStatus();
            ruler.repaint();
          }
        });
    footer.add(
        BorderLayout.EAST,
        OfficeTheme.row(
            focusButton(),
            readView = statusIcon("Read Mode", FontImage.MATERIAL_MENU_BOOK, "view.readMode"),
            printView = statusIcon("Print Layout", FontImage.MATERIAL_DESCRIPTION, "view.printLayout"),
            webView = statusIcon("Web Layout", FontImage.MATERIAL_LANGUAGE, "view.webLayout"),
            button("\u2212", () -> zoom(-.1f)), zoomSlider, button("+", () -> zoom(.1f)), zoomValue));
    if (OfficeTheme.mobile()) {
      footer.removeAll();
      // The desktop status bar nests status beside the language button; detach it from that row.
      status.remove();
      footer.add(BorderLayout.NORTH, status);
      quickUndo =
          iconButton("", FontImage.MATERIAL_UNDO, () -> run("edit.undo", Json.object()), false);
      quickRedo =
          iconButton("", FontImage.MATERIAL_REDO, () -> run("edit.redo", Json.object()), false);
      quickUndo.setAccessibilityText("Undo");
      quickRedo.setAccessibilityText("Redo");
      var touchTools = new Container(new GridLayout(1, 7));
      touchTools.addAll(
          quickUndo,
          quickRedo,
          iconButton("", FontImage.MATERIAL_SAVE, () -> saveDocument(false), false),
          iconButton("", FontImage.MATERIAL_CONTENT_CUT, () -> clipboard("cut"), false),
          iconButton("", FontImage.MATERIAL_CONTENT_COPY, () -> clipboard("copy"), false),
          iconButton("", FontImage.MATERIAL_CONTENT_PASTE, () -> clipboard("paste"), false),
          iconButton("", FontImage.MATERIAL_KEYBOARD_HIDE, editor::stopInput, false));
      footer.add(BorderLayout.CENTER, touchTools);
    }
    form.add(BorderLayout.SOUTH, footer);
    updateOutline();
    updateStatus();
    installCustomKeys();
    installMenu();
    form.addSizeChangedListener(
        e ->
            Display.getInstance()
                .callSerially(
                    () -> {
                      configureMobileViewport();
                      // Desktop keeps the chosen zoom on resize, as Word does; phones refit.
                      if (OfficeTheme.mobile()) editor.fitWidth();
                      updateStatus();
                      ruler.repaint();
                    }));
    if (OfficeTheme.mobile()) {
      form.setFormBottomPaddingEditingMode(true);
      Display.getInstance()
          .addVirtualKeyboardListener(
              event -> {
                if (Boolean.TRUE.equals(event.getSource())) ribbon.collapse();
                Display.getInstance()
                    .callSerially(
                        () -> {
                          form.revalidate();
                          editor.revealSelection();
                        });
              });
    }
    configureMobileViewport();
    OfficeTheme.refreshAppearance();
    applyDarkPage();
    if (pendingRecovery != null) showRecoveryBar();
    OfficeTheme.applyAppearance(form);
    form.show();
    fitWindowToScreen();
    // No appearance-change callback exists, so follow the OS setting with a cheap poll.
    new UITimer(
            () -> {
              boolean changed = OfficeTheme.refreshAppearance();
              if (changed) {
                darkPageOverride = null;
                applyDarkPage();
              }
              if (!changed && !OfficeTheme.mapped()) return;
              // While mapped, also catch chrome created or restyled since the last pass.
              OfficeTheme.applyAppearance(form);
              form.repaint();
            })
        .schedule(500, true, form);
    startAgentDebugging();
    if (OfficeTheme.mobile()) editor.fitWidth();
    else zoomTo(1f);
    updateStatus();
    if (!OfficeTheme.mobile()) editor.startInputAfterPaint();
    new UITimer(this::autosave).schedule(1000, true, form);
  }

  private Document pendingRecovery;
  private Docx.Opened pendingRecoverySource;
  private Container recoveryBar;

  /**
   * Unsaved work from a session that ended without saving is offered on a blank document, as
   * Word's Document Recovery does, rather than opened in place of what the user asked for.
   */
  private Document offerRecovery(Document recovered) {
    if (recovered == null) return null;
    pendingRecovery = recovered;
    pendingRecoverySource = source;
    source = null;
    return null;
  }

  private void showRecoveryBar() {
    recoveryBar = new Container(new BorderLayout());
    OfficeTheme.surface(recoveryBar, 0xfff4ce);
    OfficeTheme.space(recoveryBar, 6, 6, 12, 8);
    recoveryBar.getAllStyles().setBorder(
        Border.createCompoundBorder(null, Border.createLineBorder(1, 0xe8d48a), null, null));
    var icon = new Label();
    icon.setIcon(OfficeTheme.glyphImage(FontImage.MATERIAL_RESTORE, 16, 0x8a6d00));
    var text = OfficeTheme.label(
        "RECOVERED  Unsaved changes to \u201c" + pendingRecovery.title
            + "\u201d from your last session can be restored.", 12, OfficeTheme.INK);
    OfficeTheme.space(text, 0, 0, 8, 8);
    var restore = button("Restore", () -> resolveRecovery(true));
    OfficeTheme.framed(restore);
    var discard = button("Discard", () -> resolveRecovery(false));
    OfficeTheme.button(discard);
    recoveryBar.add(BorderLayout.WEST, icon);
    recoveryBar.add(BorderLayout.CENTER, text);
    recoveryBar.add(BorderLayout.EAST, OfficeTheme.row(restore, discard));
    workspace.add(BorderLayout.NORTH, recoveryBar);
  }

  private void resolveRecovery(boolean restore) {
    var document = pendingRecovery;
    var original = pendingRecoverySource;
    pendingRecovery = null;
    pendingRecoverySource = null;
    if (recoveryBar != null) {
      recoveryBar.remove();
      recoveryBar = null;
      workspace.revalidate();
    }
    if (document == null) return;
    if (!restore) {
      try {
        recovery.forget();
      } catch (IOException ex) {
        Log.e(ex);
      }
      return;
    }
    replaceAfterSaving(() -> {
      forgetFile();
      session.loadDocument(document);
      source = original;
      editor.resetScroll();
      edited();
    });
  }

  private void configureMobileViewport() {
    if (!OfficeTheme.mobile()) return;
    var safe =
        Display.getInstance().getDisplaySafeArea(new com.codename1.ui.geom.Rectangle());
    form.getToolbar().setSafeArea(true);
    form.getToolbar().setPreferredH(OfficeTheme.px(44) + safe.getY());
    form.getContentPane().setSafeArea(true);
    if (Display.getInstance().getDisplayWidth() > Display.getInstance().getDisplayHeight())
      ribbon.collapse();
    form.revalidate();
  }

  private OfficeSwitch autoSave;

  private Container titleBar() {
    quickUndo =
        iconButton("", FontImage.MATERIAL_UNDO, () -> run("edit.undo", Json.object()), false);
    quickUndo.setAccessibilityText("Undo");
    quickRedo =
        iconButton("", FontImage.MATERIAL_REDO, () -> run("edit.redo", Json.object()), false);
    quickRedo.setAccessibilityText("Redo");
    var save = iconButton("", FontImage.MATERIAL_SAVE, () -> saveDocument(false), false);
    save.setAccessibilityText("Save document");
    var header =
        new Container(new BorderLayout(BorderLayout.CENTER_BEHAVIOR_CENTER_ABSOLUTE));
    OfficeTheme.space(header, 0, 0, 0, 0);
    // AutoSave (Word's title-bar switch): saves the open file automatically; with no file yet
    // turning it on asks where to save first.
    Label autoLabel = OfficeTheme.label("AutoSave", 11, OfficeTheme.DIM);
    autoSave = new OfficeSwitch("AutoSave");
    autoSave.setOn(AppOptions.flag("autosave"));
    autoSave.addActionListener(e -> {
      boolean on = !AppOptions.flag("autosave");
      AppOptions.set("autosave", String.valueOf(on));
      autoSave.setOn(on);
      if (on && filePath == null) saveDocument(true);
    });
    Container auto = OfficeTheme.row(autoLabel, autoSave);
    OfficeTheme.space(auto, 0, 0, 0, 8);
    autoLabel.getAllStyles().setMarginUnit(Style.UNIT_TYPE_PIXELS);
    autoLabel.getAllStyles().setMargin(0, 0, 0, OfficeTheme.px(6));
    var more = iconButton("", FontImage.MATERIAL_MORE_HORIZ, this::quickAccessMore, false);
    more.setAccessibilityText("More Quick Access commands");
    header.add(BorderLayout.WEST,
        OfficeTheme.row(auto, save, split(quickUndo, "Undo history", this::undoHistory), quickRedo, more));
    documentTitle = button(session.document().title + " \u25be", this::rename);
    documentTitle.setAccessibilityText("Document name");
    documentTitle.getAllStyles().setFont(OfficeTheme.font(12, true));
    header.add(BorderLayout.CENTER, documentTitle);
    var search =
        iconButton("Search commands and help", FontImage.MATERIAL_SEARCH, this::searchCommands, false);
    search.setAccessibilityText("Search commands");
    search.getAllStyles().setBorder(OfficeBorder.of(OfficeTheme.BORDER_STRONG, 6));
    search.getUnselectedStyle().setFgColor(OfficeTheme.DIM);
    OfficeTheme.surface(search, OfficeTheme.PAPER);
    search.getSelectedStyle().setBgColor(OfficeTheme.PAPER);
    search.getPressedStyle().setBgColor(OfficeTheme.HOVER);
    OfficeTheme.space(search, 3, 3, 10, 10);
    search.getAllStyles().setMarginUnit(Style.UNIT_TYPE_PIXELS);
    search.getAllStyles().setMargin(OfficeTheme.px(4), OfficeTheme.px(4), 0, OfficeTheme.px(8));
    search.setAlignment(Component.LEFT);
    search.setPreferredW(OfficeTheme.px(260));
    header.add(BorderLayout.EAST, search);
    return header;
  }

  private void searchCommands() {
    editor.stopInput();
    var dialog = officeDialog("Search commands");
    dialog.setLayout(new BorderLayout());
    var query = field();
    query.setHint("Find a command, such as table or heading");
    query.setAccessibilityText("Search commands");
    var results = new Container(BoxLayout.y());
    results.setScrollableY(true);
    results.setPreferredH(OfficeTheme.px(320));
    results.setPreferredW(OfficeTheme.px(440));
    Runnable refresh =
        () -> {
          results.removeAll();
          var filter = query.getText().toLowerCase();
          for (Map.Entry<String, Runnable> action : uiActions.entrySet()) {
            if (!action.getKey().toLowerCase().contains(filter)) continue;
            var choice =
                button(
                    action.getKey(),
                    () -> {
                      dialog.dispose();
                      Display.getInstance().callSerially(action.getValue());
                    });
            choice.setAlignment(Component.LEFT);
            results.add(choice);
          }
          // Every registered command is reachable here by its Word name, id or description.
          if (filter.length() >= 2) {
            Map<String, String> labels = commandLabels();
            int shown = 0;
            for (Commands.Descriptor d : commands.descriptors()) {
              String label = labels.containsKey(d.id) ? labels.get(d.id) : d.description;
              if (shown >= 60
                  || !(label.toLowerCase().contains(filter)
                      || d.id.toLowerCase().contains(filter)
                      || d.description.toLowerCase().contains(filter))) continue;
              shown++;
              var choice =
                  button(
                      label,
                      () -> {
                        dialog.dispose();
                        Display.getInstance().callSerially(() -> promptArgs(d, new ArrayList<String>(d.parameters.keySet()), 0, new LinkedHashMap<String, Object>()));
                      });
              choice.setAlignment(Component.LEFT);
              results.add(choice);
            }
          }
          if (results.getComponentCount() == 0)
            results.add(OfficeTheme.label("No matching commands", 13, 0x666666));
          results.revalidate();
        };
    query.addDataChangedListener((type, index) -> refresh.run());
    refresh.run();
    dialog.add(BorderLayout.NORTH, query);
    dialog.add(BorderLayout.CENTER, results);
    dialog.add(BorderLayout.SOUTH, button("Close", dialog::dispose));
    styleDialogBody(dialog.getContentPane());
    showOfficeDialog(dialog);
  }

  private Map<String, String> commandLabels;

  private Map<String, String> commandLabels() {
    if (commandLabels == null) {
      commandLabels = new HashMap<>();
      try (var in =
          Display.getInstance().getResourceAsStream(WordProcessor.class, "/command-labels.properties")) {
        if (in != null) {
          var p = new com.codename1.io.Properties();
          p.load(in);
          for (Object k : p.keySet()) commandLabels.put((String) k, p.getProperty((String) k));
        }
      } catch (java.io.IOException ex) {
        // Fall back to command descriptions.
      }
    }
    return commandLabels;
  }

  /** Ask for required arguments one at a time (optional ones are skipped), then run. */
  private void promptArgs(Commands.Descriptor d, java.util.List<String> keys, int i, Map<String, Object> args) {
    while (i < keys.size() && !d.required.contains(keys.get(i))) i++;
    if (i == keys.size()) {
      run(d.id, args);
      return;
    }
    final var key = keys.get(i);
    final int next = i + 1;
    final var type = d.parameters.get(key);
    prompt(
        key + " (" + type + ")",
        "",
        v -> {
          args.put(key, parseArgument(type, v.trim()));
          promptArgs(d, keys, next, args);
        });
  }

  static Object parseArgument(String type, String v) {
    if ("integer".equals(type)) return Integer.valueOf(Integer.parseInt(v));
    if ("number".equals(type)) return Double.valueOf(Double.parseDouble(v));
    if ("boolean".equals(type)) return Boolean.valueOf("true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v));
    if ("string".equals(type)) return v;
    try {
      return Json.parse("{\"v\":" + v + "}").get("v");
    } catch (java.io.IOException ex) {
      throw new IllegalArgumentException("Enter a JSON value for " + type);
    }
  }

  private void styleCombo(ComboBox<String> combo) {
    var renderer =
        new com.codename1.ui.list.DefaultListCellRenderer<String>(false);
    renderer.getAllStyles().setFont(OfficeTheme.font(12, false));
    renderer.getAllStyles().setFgColor(OfficeTheme.INK);
    renderer.getAllStyles().setBgImage(null);
    renderer.getAllStyles().setBorder(Border.createEmpty());
    renderer.getAllStyles().setBgTransparency(255);
    renderer.getUnselectedStyle().setBgColor(OfficeTheme.PAPER);
    renderer.getSelectedStyle().setBgColor(0xDCE9FB);
    renderer.getSelectedStyle().setFgColor(OfficeTheme.INK);
    var focus = renderer.getListFocusComponent(combo);
    focus.getAllStyles().setBgImage(null);
    focus.getAllStyles().setBorder(Border.createEmpty());
    OfficeTheme.surface(focus, 0xDCE9FB);
    OfficeTheme.space(renderer, 2, 2, 4, 4);
    combo.setRenderer(renderer);
    var arrow = new Style();
    arrow.setFgColor(OfficeTheme.chrome(0x454545));
    arrow.setBgTransparency(0);
    combo.setComboBoxImage(
        FontImage.createMaterial(FontImage.MATERIAL_ARROW_DROP_DOWN, arrow)
            .scaled(OfficeTheme.px(14), OfficeTheme.px(14)));
    combo.setPreferredH(OfficeTheme.px(OfficeTheme.mobile() ? 44 : 24));
  }

  private void applyPaintStyle() {
    if (paintStyle == null || session.cellSelection() == null && session.selection().collapsed())
      return;
    var copied = paintStyle;
    paintStyle = null;
    session.transact(
        s -> {
          s.format("font", copied.font);
          s.format("size", Float.toString(copied.size));
          s.format("bold", String.valueOf(copied.bold));
          s.format("italic", String.valueOf(copied.italic));
          s.format("underline", String.valueOf(copied.underline));
          s.format("strike", String.valueOf(copied.strike));
          s.format("script", copied.script);
          for (String key : CharacterFormat.KEYS) s.format(key, copied.characters.value(key));
          s.format("color", Integer.toHexString(copied.color));
          s.format("background", Integer.toHexString(copied.background));
        });
    painterButton.getUnselectedStyle().setBgTransparency(0);
    edited();
  }

  private void changeCase() {
    var choice =
        new ComboBox<String>(
            "Sentence case", "lowercase", "UPPERCASE", "Capitalize Each Word", "tOGGLE cASE");
    if (!showDialog("Change case", choice, "Apply", "Cancel")) return;
    session.changeCase(
        new String[] {"sentence", "lower", "upper", "title", "toggle"}[choice.getSelectedIndex()]);
    edited();
  }

  private void applyStyle(String name, int level) {
    session.applyStyle(name.replace(" ", ""));
    edited();
  }

  private EditField field() {
    return field("");
  }

  private EditField field(String text) {
    var field = new EditField(text);
    configureField(field, 220);
    return field;
  }

  private EditField field(String text, int rows, int columns) {
    var field = new EditField(text, rows, columns);
    configureField(field, Math.min(560, columns * 8));
    return field;
  }

  private void configureField(EditField field, int width) {
    OfficeTheme.field(field, width);
    field.addActionListener(
        e -> {
          if (OfficeTheme.mobile() && field.isSingleLineTextArea()) {
            field.finishComposing();
            var owner = field.getComponentForm();
            if (owner != null) owner.setFocused(null);
            Display.getInstance().setShowVirtualKeyboard(false);
          }
        });
    field.getSemantics().setRole(AccessibilityRole.TEXT_FIELD).setValue(field.getText());
    field.addDataChangedListener((type, index) -> field.getSemantics().setValue(field.getText()));
    field
        .getSemantics()
        .addAction(
            new AccessibilityAction(
                AccessibilityAction.SET_TEXT,
                "Set text",
                (component, value) -> {
                  if (!field.isEditable() || !(value instanceof String)) return false;
                  field.setText((String) value);
                  return true;
                }));
  }

  private Dialog officeDialog(String title) {
    var dialog =
        new Dialog(title) {
          @Override
          protected void initNativeWindow(Window window) {
            OfficeTheme.surface(window, OfficeTheme.PAPER);
            OfficeTheme.surface(window.getContentPane(), OfficeTheme.PAPER);
          }
        };
    if (OfficeTheme.mobile()) dialog.setFormBottomPaddingEditingMode(true);
    MacScrolling.install(dialog);
    // The shared light dialog surface must also override a native theme's title color.
    dialog.getTitleComponent().getAllStyles().setFgColor(OfficeTheme.INK);
    var frame = dialog.getDialogComponent();
    if (OfficeTheme.mobile()) frame.setSafeArea(true);
    OfficeTheme.surface(frame, OfficeTheme.PAPER);
    OfficeTheme.space(frame, 8, 8, 8, 8);
    frame.getAllStyles().setBorder(Border.createEmpty());
    frame.getAllStyles().setBgImage(null);
    OfficeTheme.space(dialog.getContentPane(), 0, 0, 0, 0);
    return dialog;
  }

  private void showOfficeDialog(Dialog dialog) {
    OfficeTheme.applyAppearance(dialog);
    // A phone keyboard can consume a centered dialog's entire content area. Give mobile
    // forms the full safe viewport; bottom-padding editing keeps actions above the keyboard.
    if (OfficeTheme.mobile()) dialog.show(0, 0, 0, 0, true, true);
    else dialog.showPacked(BorderLayout.CENTER, true);
  }

  private void styleDialogBody(Component body) {
    body.getAllStyles().setFgColor(OfficeTheme.INK);
    if (body instanceof ComboBox) {
      OfficeTheme.field(body, 180);
      styleCombo((ComboBox<String>) body);
    } else if (body instanceof CheckBox box) {
      body.getAllStyles().setFont(OfficeTheme.font(13, false));
      body.getAllStyles().setBgTransparency(0);
      box.setGap(OfficeTheme.px(8));
      body.setPreferredH(OfficeTheme.px(OfficeTheme.mobile() ? 44 : 28));
    }
    if (body instanceof Container container) {
      OfficeTheme.surface(body, OfficeTheme.PAPER);
      for (int i = 0; i < container.getComponentCount(); i++)
        styleDialogBody(container.getComponentAt(i));
    }
  }

  static String languageName(String tag) {
    var parts = com.codename1.util.StringUtil.tokenize(tag, '-').toArray(new String[0]);
    if (parts.length == 0) return tag;
    String[][] known = {
      {"en", "English"}, {"fr", "French"}, {"de", "German"}, {"es", "Spanish"}, {"it", "Italian"},
      {"pt", "Portuguese"}, {"nl", "Dutch"}, {"he", "Hebrew"}, {"ar", "Arabic"}, {"ja", "Japanese"},
      {"zh", "Chinese"}, {"ko", "Korean"}, {"ru", "Russian"}
    };
    String[][] regions = {
      {"US", "United States"}, {"GB", "United Kingdom"}, {"FR", "France"}, {"DE", "Germany"},
      {"ES", "Spain"}, {"IL", "Israel"}, {"CA", "Canada"}, {"AU", "Australia"}, {"BR", "Brazil"}
    };
    var lang = parts[0];
    for (String[] k : known) if (k[0].equalsIgnoreCase(parts[0])) lang = k[1];
    if (parts.length < 2) return lang;
    var region = parts[1];
    for (String[] r : regions) if (r[0].equalsIgnoreCase(parts[1])) region = r[1];
    return lang + " (" + region + ")";
  }

  /** Both rulers; the vertical one exists only in Print Layout, as in Word. */
  private void showRulers(boolean show) {
    ruler.setHidden(!show);
    verticalRuler.setHidden(!show || editor.isReflow());
    ruler.getParent().revalidate();
  }

  /** Status bar Focus: icon and label, as in Word. */
  private Button focusButton() {
    var b = statusIcon("Focus", FontImage.MATERIAL_CENTER_FOCUS_STRONG, "view.focus");
    b.setText("Focus");
    b.getAllStyles().setFont(OfficeTheme.font(11, false));
    b.getAllStyles().setFgColor(OfficeTheme.DIM);
    return b;
  }

  /** Status bar view toggle: 22x20 icon button, rounded 3pt, checked when it is the current view. */
  private Button statusIcon(String name, char icon, String command) {
    var b = iconButton("", icon, () -> run(command, new HashMap<String, Object>()), false);
    b.setAccessibilityText(name);
    OfficeTheme.icon(b, icon, 14, OfficeTheme.DIM);
    OfficeTheme.space(b, 3, 3, 4, 4);
    b.getAllStyles().setBorder(OfficeBorder.of(-1, 3));
    return b;
  }

  private Button iconButton(String name, char icon, Runnable action, boolean large) {
    var b = button(name, action);
    b.setAccessibilityText(name.isEmpty() ? iconName(icon) : name);
    OfficeTheme.icon(b, icon, large ? 27 : 18, OfficeTheme.INK);
    if (large) {
      b.setTextPosition(Component.BOTTOM);
      b.setAlignment(Component.CENTER);
      b.setPreferredW(
          Math.max(
              OfficeTheme.px(66), b.getStyle().getFont().stringWidth(name) + OfficeTheme.px(14)));
      OfficeTheme.space(b, 6, 4, 5, 5);
    }
    return b;
  }

  private String iconName(char icon) {
    if (icon == FontImage.MATERIAL_FORMAT_LIST_BULLETED) return "Bullets";
    if (icon == FontImage.MATERIAL_FORMAT_LIST_NUMBERED) return "Numbering";
    if (icon == FontImage.MATERIAL_FORMAT_COLOR_FILL) return "Highlight color";
    if (icon == FontImage.MATERIAL_FORMAT_COLOR_TEXT) return "Font color";
    if (icon == FontImage.MATERIAL_FORMAT_CLEAR) return "Clear formatting";
    if (icon == FontImage.MATERIAL_FORMAT_INDENT_DECREASE) return "Decrease indent";
    if (icon == FontImage.MATERIAL_FORMAT_INDENT_INCREASE) return "Increase indent";
    if (icon == FontImage.MATERIAL_SAVE) return "Save document";
    if (icon == FontImage.MATERIAL_CONTENT_CUT) return "Cut";
    if (icon == FontImage.MATERIAL_CONTENT_COPY) return "Copy";
    if (icon == FontImage.MATERIAL_CONTENT_PASTE) return "Paste";
    if (icon == FontImage.MATERIAL_KEYBOARD_HIDE) return "Hide keyboard";
    if (icon == FontImage.MATERIAL_FORMAT_ALIGN_LEFT) return "Align left";
    if (icon == FontImage.MATERIAL_FORMAT_ALIGN_CENTER) return "Center";
    if (icon == FontImage.MATERIAL_FORMAT_ALIGN_RIGHT) return "Align right";
    if (icon == FontImage.MATERIAL_FORMAT_ALIGN_JUSTIFY) return "Justify";
    if (icon == FontImage.MATERIAL_FORMAT_LINE_SPACING) return "Line spacing";
    if (icon == FontImage.MATERIAL_BORDER_OUTER) return "Borders and shading";
    return "";
  }

  private Button toggle(String text, char icon, String property) {
    var b = iconButton(text, icon, () -> format(property), false);
    b.setText("");
    b.setAccessibilityText(text);
    b.setPreferredW(OfficeTheme.px(30));
    formatButtons.put(property, b);
    return b;
  }

  private Button scriptButton(String label, String name, String value) {
    var b =
        button(
            label,
            () ->
                run(
                    "format.set",
                    Json.object(
                        "property",
                        "script",
                        "value",
                        value.equals(session.activeStyle().script) ? "baseline" : value)));
    b.setAccessibilityText(name);
    b.setPreferredW(OfficeTheme.px(30));
    formatButtons.put(value, b);
    return b;
  }

  private void togglePainter() {
    paintStyle = paintStyle == null ? session.activeStyle() : null;
    OfficeTheme.checked(painterButton, paintStyle != null);
    status.setText(
        paintStyle == null ? "Format Painter cancelled" : "Select text to apply the copied formatting");
    editor.startInput();
  }

  /** Word's stacked small buttons: icon and label flush left in an even column. */
  private static Container leftColumn(Button... buttons) {
    for (Button b : buttons) b.setAlignment(Component.LEFT);
    return BoxLayout.encloseY(buttons);
  }

  private Container home() {
    painterButton =
        iconButton(
            "Format Painter",
            FontImage.MATERIAL_FORMAT_PAINT,
            this::togglePainter,
            false);
    editor.addPointerReleasedListener(e -> applyPaintStyle());
    Container clipboard =
        OfficeTheme.row(
            split(iconButton("Paste", FontImage.MATERIAL_CONTENT_PASTE, () -> clipboard("paste"), true),
                "Paste options", this::pasteOptions),
            leftColumn(
                iconButton("Cut", FontImage.MATERIAL_CONTENT_CUT, () -> clipboard("cut"), false),
                iconButton("Copy", FontImage.MATERIAL_CONTENT_COPY, () -> clipboard("copy"), false),
                painterButton));
    fontChoice = new ComboBox<>("Source Sans 3", "Source Serif 4", "JetBrains Mono");
    OfficeTheme.field(fontChoice, 122);
    styleCombo(fontChoice);
    sizeChoice =
        new ComboBox<>(
            "8", "9", "10", "11", "12", "14", "16", "18", "20", "24", "28", "36", "48", "72");
    sizeChoice.setSelectedItem("12");
    OfficeTheme.field(sizeChoice, 48);
    styleCombo(sizeChoice);
    fontChoice.addActionListener(
        e -> {
          if (!syncingControls)
            run(
                "format.set",
                Json.object("property", "font", "value", fontChoice.getSelectedItem()));
        });
    sizeChoice.addActionListener(
        e -> {
          if (!syncingControls)
            run(
                "format.set",
                Json.object("property", "size", "value", sizeChoice.getSelectedItem()));
        });
    Container fontTop =
        OfficeTheme.row(
            fontChoice,
            sizeChoice,
            button("A+", () -> changeSize(1)),
            button("A\u2212", () -> changeSize(-1)),
            button("Aa \u25be", this::changeCase),
            iconButton("", FontImage.MATERIAL_FORMAT_CLEAR, this::clearFormatting, false));
    Container fontBottom =
        OfficeTheme.row(
            toggle("Bold", FontImage.MATERIAL_FORMAT_BOLD, "bold"),
            toggle("Italic", FontImage.MATERIAL_FORMAT_ITALIC, "italic"),
            split(
                toggle("Underline", FontImage.MATERIAL_FORMAT_UNDERLINED, "underline"),
                "Underline style",
                this::underlineStyles),
            toggle("Strikethrough", FontImage.MATERIAL_STRIKETHROUGH_S, "strike"),
            scriptButton("x\u2082", "Subscript", "subscript"),
            scriptButton("x\u00b2", "Superscript", "superscript"),
            split(highlightButton = colorButton("Text Highlight Color", FontImage.MATERIAL_BORDER_COLOR, "background"),
                "Highlight colors", () -> color("background")),
            split(fontColorButton = colorButton("Font Color", FontImage.MATERIAL_FORMAT_COLOR_TEXT, "color"),
                "Font colors", () -> color("color")));
    Container paragraphTop =
        OfficeTheme.row(
            split(
                iconButton("", FontImage.MATERIAL_FORMAT_LIST_BULLETED, () -> list("bullet"), false),
                "Bullet Library",
                () -> listLibrary(true)),
            split(
                iconButton("", FontImage.MATERIAL_FORMAT_LIST_NUMBERED, () -> list("number"), false),
                "Numbering Library",
                () -> listLibrary(false)),
            iconButton("", FontImage.MATERIAL_FORMAT_INDENT_DECREASE, () -> indent(-18), false),
            iconButton("", FontImage.MATERIAL_FORMAT_INDENT_INCREASE, () -> indent(18), false));
    Container paragraphBottom =
        OfficeTheme.row(
            iconButton(
                "", FontImage.MATERIAL_FORMAT_ALIGN_LEFT, () -> paragraph("align", "left"), false),
            iconButton(
                "",
                FontImage.MATERIAL_FORMAT_ALIGN_CENTER,
                () -> paragraph("align", "center"),
                false),
            iconButton(
                "",
                FontImage.MATERIAL_FORMAT_ALIGN_RIGHT,
                () -> paragraph("align", "right"),
                false),
            iconButton(
                "",
                FontImage.MATERIAL_FORMAT_ALIGN_JUSTIFY,
                () -> paragraph("align", "both"),
                false),
            button("\u00b6\u2026", this::paragraphDialog),
            iconButton("", FontImage.MATERIAL_FORMAT_LINE_SPACING, this::lineSpacingDialog, false),
            iconButton("", FontImage.MATERIAL_BORDER_OUTER, this::bordersDialog, false),
            toggleMarks());
    Container styles =
        OfficeTheme.row(
            styleTile("Normal", 0, 11),
            styleTile("No Spacing", -1, 11),
            styleTile("Heading 1", 1, 16),
            styleTile("Title", 3, 25),
            styleTile("Heading 2", 2, 13),
            styleTile("Subtitle", 4, 13));
    // Gallery well: six 74pt tiles on a 76pt step inside a rounded white frame.
    OfficeTheme.space(styles, 3, 3, 3, 0);
    OfficeTheme.surface(styles, OfficeTheme.PAPER);
    styles.setPreferredW(OfficeTheme.px(6 * 76 + 6));
    styles.setPreferredH(OfficeTheme.px(66));
    styles.setScrollableX(true);
    styles.setScrollVisible(false);
    // Word's gallery scroller: row up, row down and the expand button, stacked at the right.
    var up = galleryArrow(FontImage.MATERIAL_KEYBOARD_ARROW_UP, "Previous styles", () -> scrollGallery(styles, -1));
    var down = galleryArrow(FontImage.MATERIAL_KEYBOARD_ARROW_DOWN, "Next styles", () -> scrollGallery(styles, 1));
    var expand = galleryArrow(FontImage.MATERIAL_MORE_HORIZ, "More styles", this::moreStyles);
    Container moreStyles = BoxLayout.encloseY(up, down, expand);
    Container gallery = OfficeTheme.row(styles, moreStyles);
    OfficeTheme.surface(gallery, OfficeTheme.PAPER);
    gallery.getAllStyles().setBorder(OfficeBorder.of(OfficeTheme.BORDER_STRONG, 4));
    var select =
        iconButton(
            "Select",
            FontImage.MATERIAL_NEAR_ME,
            () -> {
              editor.selectAll();
              editor.startInput();
            },
            false);
    select.setAccessibilityText("Select all");
    Container editing =
        leftColumn(
            iconButton("Find", FontImage.MATERIAL_SEARCH, this::find, false),
            iconButton("Replace", FontImage.MATERIAL_FIND_REPLACE, this::find, false),
            select);
    return OfficeTheme.groups(
        OfficeTheme.group("Clipboard", clipboard, this::officeClipboard),
        OfficeTheme.group("Font", BoxLayout.encloseY(fontTop, fontBottom), this::fontDialog),
        OfficeTheme.group("Paragraph", BoxLayout.encloseY(paragraphTop, paragraphBottom), this::paragraphDialog),
        OfficeTheme.group("Styles", gallery, this::moreStyles),
        OfficeTheme.group("Editing", editing));
  }

  private Container stylesPane;
  private Runnable reloadStyles;
  private Map<String, ParagraphStyle> displayedStyles;

  private void moreStyles() {
    editor.stopInput();
    if (revisionPane != null) closeRevisions();
    if (stylesPane != null && stylesPane.getParent() != null) {
      stylesPane.remove();
      stylesPane = null;
      workspace.revalidate();
      return;
    }
    var pane = new Container(new BorderLayout());
    OfficeTheme.surface(pane, 0xfafafa);
    OfficeTheme.space(pane, 10, 10, 10, 10);
    var names = new ComboBox<String>();
    var definitions =
        new ArrayList<ParagraphStyle>(session.document().styles.values());
    var current = session.editingDocument().paragraph(session.selection().block).style;
    for (ParagraphStyle definition : definitions) {
      names.addItem(definition.name);
      if (current.equals(definition.id)) names.setSelectedIndex(names.getModel().getSize() - 1);
    }
    Label preview = OfficeTheme.label("AaBbCcDd", 24, OfficeTheme.INK);
    Runnable updatePreview =
        () -> {
          if (names.getSelectedIndex() < 0 || names.getSelectedIndex() >= definitions.size())
            return;
          var style =
              session.document().styles.get(definitions.get(names.getSelectedIndex()).id);
          if (style == null) return;
          preview.setText(style.name);
          preview
              .getAllStyles()
              .setFont(
                  DocumentFonts.font(
                      style.text, OfficeTheme.px(Math.round(Math.min(28, style.text.size)))));
          preview.getAllStyles().setFgColor(style.text.color);
          preview.repaint();
        };
    names.addActionListener(e -> updatePreview.run());
    reloadStyles =
        () -> {
          String selected =
              names.getSelectedIndex() >= 0 && names.getSelectedIndex() < definitions.size()
                  ? definitions.get(names.getSelectedIndex()).id
                  : "Normal";
          displayedStyles = session.document().styles;
          names.setModel(new com.codename1.ui.list.DefaultListModel<String>());
          definitions.clear();
          definitions.addAll(displayedStyles.values());
          int index = 0;
          for (ParagraphStyle definition : definitions) {
            names.addItem(definition.name);
            if (selected.equals(definition.id)) index = names.getModel().getSize() - 1;
          }
          names.setSelectedIndex(index);
          updatePreview.run();
          pane.revalidate();
        };
    updatePreview.run();
    Container actions =
        BoxLayout.encloseY(
            names,
            preview,
            button(
                "Apply style",
                () -> {
                  session.applyStyle(definitions.get(names.getSelectedIndex()).id);
                  edited();
                }),
            button(
                "New style from selection\u2026",
                () -> {
                  editStyle(null);
                  refreshStylesPane();
                }),
            button(
                "Modify\u2026",
                () -> {
                  editStyle(
                      session.document().styles.get(definitions.get(names.getSelectedIndex()).id));
                  refreshStylesPane();
                }),
            button(
                "Update to match selection",
                () -> {
                  var selected = definitions.get(names.getSelectedIndex());
                  var paragraph =
                      session.editingDocument().paragraph(session.selection().block);
                  session.defineStyle(
                      new ParagraphStyle(
                          selected.id, selected.name, paragraph, session.activeStyle()));
                  edited();
                  updatePreview.run();
                }),
            button(
                "Style inspector",
                () -> {
                  var paragraph =
                      session.editingDocument().paragraph(session.selection().block);
                  var text = session.activeStyle();
                  showDialog(
                      "Style inspector",
                      "Paragraph style: "
                          + paragraph.style
                          + "\nFont: "
                          + text.font
                          + " "
                          + text.size
                          + " pt\nBold: "
                          + text.bold
                          + "; italic: "
                          + text.italic
                          + "\nAlignment: "
                          + paragraph.align
                          + "\nSpacing before/after: "
                          + paragraph.before
                          + " / "
                          + paragraph.after
                          + " pt\nLine spacing: "
                          + paragraph.lineSpacing
                          + ("auto".equals(paragraph.format.lineRule)
                              ? " lines"
                              : " pt (" + paragraph.format.lineRule + ")"),
                      "OK",
                      null);
                }),
            button(
                "Delete custom style",
                () -> {
                  var selected = definitions.get(names.getSelectedIndex());
                  if (ParagraphStyle.defaults().containsKey(selected.id)) {
                    showDialog(
                        "Built-in style",
                        "Built-in styles can be modified but cannot be deleted.",
                        "OK",
                        null);
                    return;
                  }
                  if (showDialog(
                      "Delete style",
                      "Paragraphs keep their formatting and use Normal.",
                      "Delete",
                      "Cancel")) {
                    session.deleteStyle(selected.id);
                    edited();
                    refreshStylesPane();
                  }
                }));
    actions.setScrollableY(true);
    pane.add(BorderLayout.CENTER, actions);
    if (OfficeTheme.mobile()) {
      var dialog = officeDialog("Styles");
      dialog.setLayout(new BorderLayout());
      dialog.add(BorderLayout.CENTER, pane);
      dialog.add(BorderLayout.SOUTH, button("Close", dialog::dispose));
      styleDialogBody(dialog.getContentPane());
      pane.setPreferredW(
          Math.min(
              Display.getInstance().getDisplayWidth() - OfficeTheme.px(32), OfficeTheme.px(360)));
      pane.setPreferredH(Display.getInstance().getDisplayHeight() / 2);
      stylesPane = pane;
      displayedStyles = session.document().styles;
      showOfficeDialog(dialog);
      stylesPane = null;
      reloadStyles = null;
    } else {
      pane.setPreferredW(OfficeTheme.px(250));
      pane.add(
          BorderLayout.NORTH,
          OfficeTheme.row(
              OfficeTheme.label("Styles", 18, OfficeTheme.INK), button("Close", this::moreStyles)));
      styleDialogBody(pane);
      stylesPane = pane;
      displayedStyles = session.document().styles;
      workspace.add(BorderLayout.EAST, pane);
      workspace.revalidate();
    }
  }

  private void refreshStylesPane() {
    if (stylesPane != null && stylesPane.getParent() != null) {
      if (reloadStyles != null) reloadStyles.run();
    }
  }

  private void editStyle(ParagraphStyle existing) {
    var active = session.editingDocument().paragraph(session.selection().block);
    Paragraph p = existing == null ? active : existing.paragraph;
    TextStyle text = existing == null ? session.activeStyle() : existing.text;
    EditField name = field(existing == null ? "New style" : existing.name),
        size = field("" + text.size),
        color = field(TextUtil.padded(text.color, 16, 6)),
        before = field("" + p.before),
        after = field("" + p.after),
        spacing = field("" + p.lineSpacing);
    var lineRule = lineRuleChoice(p);
    ComboBox<String>
        font = new ComboBox<String>("Source Sans 3", "Source Serif 4", "JetBrains Mono"),
        align = new ComboBox<String>("left", "center", "right", "justify");
    font.setSelectedItem(DocumentFonts.family(text.font));
    align.setSelectedItem(p.align);
    CheckBox bold = new CheckBox("Bold"), italic = new CheckBox("Italic");
    bold.setSelected(text.bold);
    italic.setSelected(text.italic);
    Container fields =
        BoxLayout.encloseY(
            new Label("Name"),
            name,
            new Label("Font"),
            font,
            new Label("Size (points)"),
            size,
            OfficeTheme.row(bold, italic),
            new Label("Text color (hex)"),
            color,
            new Label("Alignment"),
            align,
            new Label("Space before (points)"),
            before,
            new Label("Space after (points)"),
            after,
            new Label("Line spacing rule"),
            lineRule,
            new Label("Line spacing (multiple or pt)"),
            spacing);
    if (showDialog(existing == null ? "Create style" : "Modify style", fields, "Save", "Cancel")) {
      String id = existing == null ? name.getText().trim().replace(" ", "") : existing.id;
      if (existing == null && session.document().styles.containsKey(id))
        throw new IllegalArgumentException("A style with that name already exists");
      var next =
          text.with("font", font.getSelectedItem())
              .with("size", size.getText())
              .with("color", color.getText())
              .with("bold", "" + bold.isSelected())
              .with("italic", "" + italic.isSelected());
      var template =
          p.with("align", align.getSelectedItem())
              .with("before", before.getText())
              .with("after", after.getText())
              .with("spacing", spacing.getText())
              .with("lineRule", lineRuleValue(lineRule));
      session.defineStyle(new ParagraphStyle(id, name.getText().trim(), template, next));
      if (existing == null) session.applyStyle(id);
      edited();
    }
  }

  private int lastParagraphLength() {
    var b = session.document().blocks.get(session.document().blocks.size() - 1);
    return b instanceof Paragraph p ? p.text.length() : 0;
  }

  private Button galleryArrow(char icon, String name, Runnable action) {
    var b = iconButton("", icon, action, false);
    b.setAccessibilityText(name);
    OfficeTheme.icon(b, icon, 14, OfficeTheme.DIM);
    OfficeTheme.space(b, 1, 1, 2, 2);
    return b;
  }

  private static void scrollGallery(Container styles, int direction) {
    int max = Math.max(0, styles.getScrollDimension().getWidth() - styles.getWidth());
    int x = Math.max(0, Math.min(max, styles.getScrollX() + direction * styles.getWidth()));
    styles.scrollRectToVisible(x, 0, styles.getWidth(), 1, styles);
  }

  private Container styleTile(String name, int level, int size) {
    // Word's gallery previews: Heading 1/2 in the accent teal, Title large, Subtitle gray.
    String sample = level == 3 ? "AaBb" : level == 1 ? "AaBbCc" : "AaBbCcDd";
    int color = level == 1 || level == 2 ? 0x0f4761 : level == 4 ? 0x595959 : OfficeTheme.INK;
    var preview = button(sample, () -> applyStyle(name, level));
    preview.setAccessibilityText(name);
    OfficeTheme.space(preview, 3, 0, 4, 2);
    preview.setAlignment(Component.LEFT);
    preview.getAllStyles().setFont(OfficeTheme.font(size, level <= 0));
    preview.getAllStyles().setFgColor(color);
    preview.getAllStyles().setBgTransparency(0);
    preview.getAllStyles().setBorder(Border.createEmpty());
    Label caption = OfficeTheme.label(name, 11, OfficeTheme.INK);
    caption.setAlignment(Component.CENTER);
    OfficeTheme.space(caption, 0, 3, 0, 0);
    var c = new Container(new BorderLayout());
    c.add(BorderLayout.NORTH, preview).add(BorderLayout.SOUTH, caption);
    OfficeTheme.space(c, 2, 2, 2, 2);
    c.getAllStyles().setMarginUnit(Style.UNIT_TYPE_PIXELS);
    c.getAllStyles().setMargin(0, 0, 0, OfficeTheme.px(2));
    c.setLeadComponent(preview);
    c.setPreferredW(OfficeTheme.px(74));
    c.setPreferredH(OfficeTheme.px(60));
    c.getAllStyles().setBorder(OfficeBorder.of(-1, 3));
    styleTiles.put(name, c);
    return c;
  }

  private Container insert() {
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Pages",
            OfficeTheme.row(
                iconButton(
                    "Page break",
                    FontImage.MATERIAL_INSERT_PAGE_BREAK,
                    () -> {
                      session.replace("\n");
                      paragraph("pageBreak", "true");
                    },
                    true))),
        OfficeTheme.group(
            "Tables",
            OfficeTheme.row(
                iconButton("Table", FontImage.MATERIAL_GRID_ON, this::insertTable, true),
                BoxLayout.encloseY(
                    button("Quick Tables\u2026", this::quickTables),
                    button("Table layout\u2026", this::tableLayout)))),
        OfficeTheme.group(
            "Illustrations",
            OfficeTheme.row(
                iconButton("Pictures", FontImage.MATERIAL_IMAGE, this::insertPicture, true))),
        OfficeTheme.group(
            "Links",
            OfficeTheme.row(
                iconButton(
                    "Link",
                    FontImage.MATERIAL_LINK,
                    () ->
                        prompt(
                            "Link URL",
                            "https://",
                            url ->
                                run("format.set", Json.object("property", "link", "value", url))),
                    true),
                button("Bookmark", this::bookmarkDialog),
                button("Cross-reference", this::crossReferenceDialog))),
        OfficeTheme.group(
            "Header & Footer",
            OfficeTheme.row(
                BoxLayout.encloseY(
                    button("Header\u2026", () -> editFurniture(false)),
                    button("Footer\u2026", () -> editFurniture(true))))),
        OfficeTheme.group(
            "Text",
            OfficeTheme.row(
                iconButton(
                    "Field",
                    FontImage.MATERIAL_DESCRIPTION,
                    () -> fieldDialog(false, "PAGE"),
                    true),
                BoxLayout.encloseY(
                    button("Date & time", () -> fieldDialog(false, "DATE \\@ \"yyyy-MM-dd\"")),
                    button("Edit field\u2026", () -> fieldDialog(true, "PAGE")),
                    button(
                        "Update fields",
                        () -> {
                          updateFields();
                          edited();
                        })))));
  }

  private void editFurniture(boolean footerPart) {
    final var active = session.activeSection();
    final var original = active.resolved.furniture;
    final PageFurniture[] draft = {active.raw.furniture};
    var previous = PageFurniture.EMPTY;
    for (Section.Range range : session.document().sectionRanges()) {
      if (range.first == active.first) break;
      previous = range.resolved.furniture;
    }
    final var inherited = previous;
    final String[] current = {footerPart ? "footer" : "header"};
    var variant = new ComboBox<String>("Default", "First page", "Even pages");
    var linked = new CheckBox("Link to previous section");
    linked.setEnabled(active.first > 0);
    linked.setSelected(active.first > 0 && !draft[0].stories.containsKey(current[0]));
    CheckBox first = new CheckBox("Different first page"),
        even = new CheckBox("Different odd and even pages");
    first.setSelected(original.differentFirst);
    even.setSelected(original.differentEven);
    EditField headerDistance = field("" + original.headerDistance),
        footerDistance = field("" + original.footerDistance);
    var blocks = new Sequence<Block>();
    for (Paragraph para : original.get(current[0])) blocks = blocks.append(para);
    if (blocks.size() == 0)
      blocks = blocks.append(new Paragraph("part1", new RichText()).with("after", "0"));
    final var part =
        new Session(
            new Document(
                    blocks,
                    new PageSpec(500, 360, 12, 12, 12, 12),
                    "Header/footer",
                    session.document().nextId)
                .styles(session.document().styles)
                .trackChanges(session.document().trackChanges));
    part.reviewAuthor(session.reviewAuthor());
    part.trackFormatting(session.trackFormatting());
    final var view = new EditorSurface(part, new Commands(), () -> {}, (b, r, c) -> {});
    view.setPreferredW(OfficeTheme.px(520));
    view.setPreferredH(OfficeTheme.px(200));
    view.setReflow(true);
    linked.addActionListener(
        e -> {
          if (linked.isSelected()) {
            view.stopInput();
            var linkedBlocks = new Sequence<Block>();
            for (Paragraph para : inherited.get(current[0]))
              linkedBlocks = linkedBlocks.append(para);
            if (linkedBlocks.size() == 0)
              linkedBlocks =
                  linkedBlocks.append(new Paragraph("part1", new RichText()).with("after", "0"));
            part.loadDocument(
                new Document(
                        linkedBlocks, part.document().page, "Header/footer", part.document().nextId)
                    .styles(session.document().styles)
                    .trackChanges(session.document().trackChanges));
            view.refresh();
          }
        });
    final Runnable capture =
        () -> {
          view.stopInput();
          var paragraphs = new Sequence<Paragraph>();
          for (Block b : part.document().blocks)
            if (b instanceof Paragraph paragraph) paragraphs = paragraphs.append(paragraph);
          if (linked.isSelected()) {
            var base = inherited.get(current[0]);
            boolean unchanged = base.size() == paragraphs.size();
            for (int i = 0; unchanged && i < base.size(); i++)
              unchanged = base.get(i) == paragraphs.get(i);
            if (!unchanged
                && !(base.size() == 0
                    && paragraphs.size() == 1
                    && paragraphs.get(0).text.length() == 0)) linked.setSelected(false);
          }
          draft[0] =
              linked.isSelected()
                  ? draft[0].inherit(current[0])
                  : draft[0].story(current[0], paragraphs);
        };
    variant.addActionListener(
        e -> {
          capture.run();
          int selected = variant.getSelectedIndex();
          current[0] =
              selected == 0
                  ? (footerPart ? "footer" : "header")
                  : (selected == 1 ? "first" : "even") + (footerPart ? "Footer" : "Header");
          var next = new Sequence<Block>();
          linked.setSelected(active.first > 0 && !draft[0].stories.containsKey(current[0]));
          PageFurniture source = linked.isSelected() ? inherited : draft[0];
          for (Paragraph para : source.get(current[0])) next = next.append(para);
          if (next.size() == 0)
            next = next.append(new Paragraph("part1", new RichText()).with("after", "0"));
          part.loadDocument(
              new Document(next, part.document().page, "Header/footer", part.document().nextId)
                  .styles(session.document().styles)
                  .trackChanges(session.document().trackChanges));
          view.resetScroll();
          view.refresh();
        });
    Container formatting =
        OfficeTheme.row(
            button(
                "B",
                () -> {
                  part.format("bold", "" + !part.activeStyle().bold);
                  view.refresh();
                }),
            button(
                "I",
                () -> {
                  part.format("italic", "" + !part.activeStyle().italic);
                  view.refresh();
                }),
            button(
                "U",
                () -> {
                  part.format("underline", "" + !part.activeStyle().hasUnderline());
                  view.refresh();
                }),
            button(
                "Left",
                () -> {
                  part.paragraph("align", "left");
                  view.refresh();
                }),
            button(
                "Center",
                () -> {
                  part.paragraph("align", "center");
                  view.refresh();
                }),
            button(
                "Right",
                () -> {
                  part.paragraph("align", "right");
                  view.refresh();
                }));
    Container insertion =
        OfficeTheme.row(
            button(
                "Page number",
                () -> {
                  part.insertField(new InlineField("PAGE", "1", false, false));
                  view.refresh();
                }),
            button(
                "Page count",
                () -> {
                  part.insertField(new InlineField("NUMPAGES", "1", false, false));
                  view.refresh();
                }),
            button(
                "Title",
                () -> {
                  part.insertField(
                      new InlineField("TITLE", session.document().title, false, false));
                  view.refresh();
                }),
            button(
                "Undo",
                () -> {
                  part.undo();
                  view.refresh();
                }));
    Container body =
        BoxLayout.encloseY(
            formRow("Applies to", variant),
            linked,
            first,
            even,
            formRow("Header from top (points)", headerDistance),
            formRow("Footer from bottom (points)", footerDistance),
            formatting,
            insertion,
            view);
    boolean apply;
    editingFurniture = true;
    try {
      apply = showDialog(footerPart ? "Footer" : "Header", body, "Apply", "Cancel");
    } finally {
      editingFurniture = false;
    }
    capture.run();
    if (apply) {
      session.furniture(
          draft[0].options(
              first.isSelected(),
              even.isSelected(),
              Float.parseFloat(headerDistance.getText()),
              Float.parseFloat(footerDistance.getText())));
      edited();
    }
  }

  private void fieldDialog(boolean edit, String initial) {
    var block = session.editingDocument().blocks.get(session.selection().block);
    if (!(block instanceof Paragraph)) return;
    var paragraph = (Paragraph) block;
    int caret = session.selection().caret;
    int at = paragraph.text.fieldAt(caret) != null ? caret : caret - 1;
    InlineField existing = edit && at >= 0 ? paragraph.text.fieldAt(at) : null;
    if (edit && existing == null) {
      showDialog("Edit field", "Place the caret next to a field first.", "OK", null);
      return;
    }
    var type =
        new ComboBox<String>(
            "PAGE", "NUMPAGES", "TITLE", "DATE", "TIME", "FILENAME", "MERGEFIELD", "DOCPROPERTY");
    var code = field(existing == null ? initial : existing.instruction);
    var result = field(existing == null ? "" : existing.result);
    CheckBox locked = new CheckBox("Lock field"), showCode = new CheckBox("Show field code");
    locked.setSelected(existing != null && existing.locked);
    showCode.setSelected(existing != null && existing.showCode);
    var tokens = DocumentFields.tokens(code.getText());
    if (!tokens.isEmpty()) type.setSelectedItem(tokens.get(0));
    type.addActionListener(
        e -> {
          var selected = type.getSelectedItem();
          code.setText(
              "DATE".equals(selected)
                  ? "DATE \\@ \"yyyy-MM-dd\""
                  : "TIME".equals(selected)
                      ? "TIME \\@ \"HH:mm\""
                      : "MERGEFIELD".equals(selected)
                          ? "MERGEFIELD Name"
                          : "DOCPROPERTY".equals(selected) ? "DOCPROPERTY Title" : selected);
        });
    Container body =
        BoxLayout.encloseY(
            new Label("Field type"),
            type,
            new Label("Field code"),
            code,
            new Label("Cached result"),
            result,
            locked,
            showCode);
    if (!showDialog(
        edit ? "Edit field" : "Insert field", body, edit ? "Apply" : "Insert", "Cancel")) return;
    final var value =
        new InlineField(
            code.getText(), result.getText(), locked.isSelected(), showCode.isSelected());
    final int offset = at;
    session.transact(
        s -> {
          if (edit) s.changeField(offset, value);
          else s.insertField(value);
          updateFields();
        });
    edited();
  }

  private void updateFields() {
    final var now = new java.util.Date();
    final var values = new java.util.HashMap<String, String>();
    values.put("DATE", new com.codename1.l10n.SimpleDateFormat("yyyy-MM-dd").format(now));
    values.put("TIME", new com.codename1.l10n.SimpleDateFormat("HH:mm").format(now));
    values.put(
        "FILENAME",
        filePath == null
            ? session.document().title
            : filePath.substring(filePath.lastIndexOf('/') + 1));
    session.transact(
        s -> {
          TableContents.update(s, editor::printLayout);
          TableContents.updateFigures(s, editor::printLayout);
          DocumentIndex.update(s, editor::printLayout);
          TableAuthorities.update(s, editor::printLayout);
          for (int pass = 0; pass < 4; pass++) {
            var before = s.document();
            Session.FieldResolver resolver =
                DocumentFields.resolver(before, editor.printLayout(), values);
            s.mapFields(
                (paragraph, offset, value) -> {
                  if (value.locked) return value;
                  var tokens = DocumentFields.tokens(value.instruction);
                  if (!tokens.isEmpty()
                      && ("DATE".equalsIgnoreCase(tokens.get(0))
                          || "TIME".equalsIgnoreCase(tokens.get(0)))) {
                    for (int i = 1; i + 1 < tokens.size(); i++)
                      if ("\\@".equals(tokens.get(i))) {
                        try {
                          return value.result(
                              new com.codename1.l10n.SimpleDateFormat(tokens.get(i + 1))
                                  .format(now));
                        } catch (RuntimeException ex) {
                          return value;
                        }
                      }
                  }
                  return resolver.resolve(paragraph, offset, value);
                });
            if (before == s.document()) break;
          }
        });
  }

  private void insertPicture() {
    if (files == null) return;
    if (session.inCell()) {
      Dialog.show("Insert picture", "Place the caret outside the table first.", "OK", null);
      return;
    }
    int epoch = documentEpoch;
    files.openImage(
        (name, path, bytes) -> {
          if (epoch != documentEpoch) return;
          try {
            int[] size = Picture.shape(bytes);
            if ((long) size[0] * size[1] > 16000000 || bytes.length > 20 * 1024 * 1024)
              throw new IllegalArgumentException("Picture exceeds 16 megapixels or 20 MiB");
            Image.createImage(bytes, 0, bytes.length);
            float width = Math.min(size[0] * .75f, session.activeSection().raw.page.contentWidth());
            session.insertPicture(bytes, width, width * size[1] / size[0], name);
            edited();
          } catch (RuntimeException ex) {
            Dialog.show("Picture could not be inserted", ex.getMessage(), "OK", null);
          }
        });
  }

  private void editPicture(int block) {
    var pic = (Picture) session.document().blocks.get(block);
    EditField width = field(Float.toString(pic.width)),
        height = field(Float.toString(pic.height)),
        alt = field(pic.alt);
    var aspect = new CheckBox("Keep proportions");
    aspect.setSelected(true);
    var align = new ComboBox<String>("left", "center", "right");
    align.setSelectedItem(pic.align);
    Container content =
        BoxLayout.encloseY(
            new Label("Width (points)"),
            width,
            new Label("Height (points)"),
            height,
            aspect,
            new Label("Description"),
            alt,
            new Label("Alignment"),
            align,
            button(
                "Delete picture",
                () -> {
                  if (Dialog.show("Delete picture?", "This can be undone.", "Delete", "Cancel")) {
                    session.updatePicture(block, pic.width, pic.height, pic.alt, pic.align, true);
                    edited();
                    var current = Display.getInstance().getCurrent();
                    if (current instanceof Dialog dialog) dialog.dispose();
                  }
                }));
    if (showDialog("Picture", content, "Apply", "Cancel")) {
      try {
        if (block >= session.document().blocks.size()
            || session.document().blocks.get(block) != pic) return;
        float w = Float.parseFloat(width.getText()), h = Float.parseFloat(height.getText());
        if (aspect.isSelected()) {
          if (w != pic.width) h = w * pic.height / pic.width;
          else w = h * pic.width / pic.height;
        }
        session.updatePicture(block, w, h, alt.getText(), align.getSelectedItem(), false);
        edited();
      } catch (RuntimeException ex) {
        Dialog.show("Invalid picture size", ex.getMessage(), "OK", null);
      }
    }
  }

  private void quickTables() {
    if (session.inCell()) throw new IllegalArgumentException("Nested tables are not supported yet");
    var template = new ComboBox<String>("Tabular list", "Matrix", "Calendar");
    template.setAccessibilityText("Table template");
    String[] kinds = {"tabular", "matrix", "calendar"};
    var preview = new Container(new BorderLayout());
    Runnable update =
        () -> {
          Table table = TableTemplates.create(kinds[template.getSelectedIndex()], 1);
          var grid = new Container(new GridLayout(table.rows(), table.columns()));
          for (int r = 0; r < table.rows(); r++)
            for (int c = 0; c < table.columns(); c++) {
              var cell = new Label(Table.cellText(table.cell(r, c)));
              cell.getAllStyles().setBgTransparency(255);
              cell.getAllStyles().setBgColor(table.format.cell(r, c).background);
              cell.getAllStyles().setFgColor(table.cell(r, c).get(0).text.styleAt(0).color);
              cell.getAllStyles().setPadding(2, 2, 3, 3);
              cell.getAllStyles()
                  .setBorder(
                      com.codename1.ui.plaf.Border.createLineBorder(
                          1, table.format.cell(r, c).borderColor));
              grid.add(cell);
            }
          preview.removeAll();
          preview.add(BorderLayout.CENTER, grid);
          preview.revalidate();
        };
    template.addActionListener(e -> update.run());
    update.run();
    if (showDialog(
        "Quick Tables",
        BoxLayout.encloseY(
            template,
            preview,
            new SpanLabelCompat(
                "Editable tables with a repeating header row. Calendar is a reusable 31-day sample.")),
        "Insert",
        "Cancel")) run("table.quick", Json.object("kind", kinds[template.getSelectedIndex()]));
  }

  private void insertTable() {
    EditField rows = field("3"), cols = field("3");
    if (showDialog(
        "Insert table",
        BoxLayout.encloseY(formRow("Rows", rows), formRow("Columns", cols)),
        "Insert",
        "Cancel"))
      run(
          "table.insert",
          Json.object(
              "rows",
              Integer.parseInt(rows.getText()),
              "columns",
              Integer.parseInt(cols.getText())));
  }

  private Container pageLayout() {
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Page setup",
            OfficeTheme.row(
                iconButton("Margins", FontImage.MATERIAL_BORDER_OUTER, this::margins, true),
                iconButton(
                    "Orientation",
                    FontImage.MATERIAL_SCREEN_ROTATION,
                    () ->
                        setPage(
                            session.activeSection().raw.page.height,
                            session.activeSection().raw.page.width),
                    true),
                iconButton("Columns", FontImage.MATERIAL_VIEW_COLUMN, this::columnsDialog, true),
                BoxLayout.encloseY(
                    button("Column break", () -> run("layout.columnBreak", Json.object())),
                    button("A4", () -> setPage(595.28f, 841.89f)),
                    button("Letter", () -> setPage(612, 792)),
                    button("Section break\u2026", this::sectionBreakDialog),
                    button("Section settings\u2026", this::sectionSettings)))),
        OfficeTheme.group(
            "Paragraph",
            BoxLayout.encloseY(
                OfficeTheme.row(
                    button("Indent +", () -> indent(18)), button("Indent \u2212", () -> indent(-18))),
                button(
                    "Space before / after",
                    () -> {
                      EditField before = field("0"), after = field("6");
                      if (showDialog(
                          "Paragraph spacing",
                          BoxLayout.encloseY(
                              new Label("Before (pt)"), before, new Label("After (pt)"), after),
                          "Apply",
                          "Cancel"))
                        session.transact(
                            s -> {
                              s.paragraph("before", before.getText());
                              s.paragraph("after", after.getText());
                            });
                      edited();
                    }))),
        OfficeTheme.group(
            "Line spacing",
            BoxLayout.encloseY(
                button("Single", () -> setLineSpacing("auto", "1")),
                button("1.5 lines", () -> setLineSpacing("auto", "1.5")),
                button("Double", () -> setLineSpacing("auto", "2")))));
  }

  private void nextNote() {
    var notes = Notes.all(editor.printLayout(session.document()).document);
    if (notes.isEmpty()) {
      showDialog("Notes", new Label("This document has no notes."), "Close", "Close");
      return;
    }
    session.nextNote(false);
    editor.revealSelection();
    editor.refresh();
  }

  private void showNotes() {
    var notes = Notes.all(editor.printLayout(session.document()).document);
    if (notes.isEmpty()) {
      showDialog("Notes", new Label("This document has no notes."), "Close", "Close");
      return;
    }
    String[] labels = new String[notes.size()];
    for (int i = 0; i < labels.length; i++) {
      var note = notes.get(i);
      var preview = note.plainText().replace('\n', ' ');
      if (preview.length() > 36) preview = preview.substring(0, 36) + "\u2026";
      labels[i] =
          ("footnote".equals(note.kind) ? "Footnote " : "Endnote ") + note.label + ": " + preview;
    }
    var chosen = new ComboBox<String>(labels);
    var action = new ComboBox<String>("Edit", "Go to reference", "Delete");
    if (!showDialog(
        "Notes",
        BoxLayout.encloseY(formRow("Note", chosen), formRow("Action", action)),
        "Continue",
        "Cancel")) return;
    var note = notes.get(chosen.getSelectedIndex());
    if (action.getSelectedIndex() == 0) noteDialog(note, note.kind);
    else if (action.getSelectedIndex() == 1) {
      session.selectNote(note.id);
      editor.refresh();
    } else {
      session.deleteNote(note.id);
      edited();
    }
  }

  private void noteOptionsDialog() {
    var type = new ComboBox<String>("Footnotes", "Endnotes");
    var scope = new ComboBox<String>("Whole document", "Selected sections");
    var format =
        new ComboBox<String>(
            "1, 2, 3",
            "i, ii, iii",
            "I, II, III",
            "a, b, c",
            "A, B, C",
            "01, 02, 03",
            "1st, 2nd, 3rd",
            "1, 2, \u2026 A, B",
            "*, \u2020, \u2021, \u00a7",
            "- 1 -, - 2 -",
            "No number");
    var start = field("1");
    start.setConstraint(TextArea.NUMERIC);
    var restart = new ComboBox<String>();
    var position = new ComboBox<String>();
    Runnable load =
        () -> {
          boolean foot = type.getSelectedIndex() == 0;
          var p =
              session.activeSection().resolved.notes.get(foot ? "footnote" : "endnote");
          format.setSelectedIndex(NoteOptions.FORMATS.indexOf(p.numFmt));
          start.setText("" + p.numStart);
          restart.setModel(
              new com.codename1.ui.list.DefaultListModel<String>(
                  foot
                      ? new String[] {"Continuous", "Each section", "Each page"}
                      : new String[] {"Continuous", "Each section"}));
          restart.setSelectedIndex(
              "eachPage".equals(p.numRestart) ? 2 : "eachSect".equals(p.numRestart) ? 1 : 0);
          position.setModel(
              new com.codename1.ui.list.DefaultListModel<String>(
                  foot
                      ? new String[] {"Bottom of page", "Below text"}
                      : new String[] {"End of document", "End of section"}));
          position.setSelectedIndex("beneathText".equals(p.pos) || "sectEnd".equals(p.pos) ? 1 : 0);
        };
    load.run();
    type.addActionListener(e -> load.run());
    Container body =
        BoxLayout.encloseY(
            formRow("Notes", type),
            formRow("Apply to", scope),
            formRow("Number format", format),
            formRow("Start at", start),
            formRow("Numbering", restart),
            formRow("Position", position));
    if (!showDialog("Footnote and Endnote", body, "Apply", "Cancel")) return;
    boolean foot = type.getSelectedIndex() == 0;
    int number = Integer.parseInt(start.getText().trim());
    if (number < 1 || number > 32767)
      throw new IllegalArgumentException("Start at must be between 1 and 32767");
    String pos =
        foot
            ? (position.getSelectedIndex() == 0 ? "pageBottom" : "beneathText")
            : (position.getSelectedIndex() == 0 ? "docEnd" : "sectEnd");
    session.noteOptions(
        foot ? "footnote" : "endnote",
        new NoteOptions.Properties(
            NoteOptions.FORMATS.get(format.getSelectedIndex()),
            number,
            new String[] {"continuous", "eachSect", "eachPage"}[restart.getSelectedIndex()],
            pos),
        scope.getSelectedIndex() == 0);
    edited();
  }

  private void noteDialog(Note original, String kind) {
    editor.stopInput();
    var type = new ComboBox<String>("Footnote", "Endnote");
    type.setSelectedIndex("endnote".equals(kind) ? 1 : 0);
    var mark = field(original == null ? "" : original.mark);
    var blocks = new Sequence<Block>();
    if (original != null) for (Paragraph p : original.paragraphs) blocks = blocks.append(p);
    if (blocks.size() == 0)
      blocks =
          blocks.append(
              new Paragraph(
                      "p" + session.document().nextId,
                      RichText.of("", TextStyle.DEFAULT.with("size", "10")))
                  .with("after", "0")
                  .with("spacing", "1"));
    var draft =
        new Session(
            new Document(
                    blocks,
                    new PageSpec(500, 360, 12, 12, 12, 12),
                    "Note",
                    session.document().nextId + 1)
                .styles(session.document().styles)
                .trackChanges(original != null && session.document().trackChanges));
    draft.reviewAuthor(session.reviewAuthor());
    draft.trackFormatting(session.trackFormatting());
    var view = new EditorSurface(draft, new Commands(), () -> {}, (b, r, c) -> {});
    view.setPreferredW(OfficeTheme.px(520));
    view.setPreferredH(OfficeTheme.px(200));
    view.setReflow(true);
    Container formatting =
        OfficeTheme.row(
            button(
                "Bold note",
                () -> {
                  draft.format("bold", "" + !draft.activeStyle().bold);
                  view.refresh();
                }),
            button(
                "Italic note",
                () -> {
                  draft.format("italic", "" + !draft.activeStyle().italic);
                  view.refresh();
                }),
            button(
                "Underline note",
                () -> {
                  draft.format("underline", "" + !draft.activeStyle().hasUnderline());
                  view.refresh();
                }));
    Container body =
        BoxLayout.encloseY(formRow("Type", type), formRow("Custom mark", mark), formatting, view);
    String title =
        original == null
            ? ("footnote".equals(kind) ? "Insert Footnote" : "Insert Endnote")
            : "Edit Note";
    boolean apply = showDialog(title, body, original == null ? "Insert" : "Apply", "Cancel");
    view.stopInput();
    if (!apply) return;
    var paragraphs = new Sequence<Paragraph>();
    for (Block b : draft.document().blocks) paragraphs = paragraphs.append((Paragraph) b);
    final Sequence<Paragraph> story = paragraphs;
    String selectedKind = type.getSelectedIndex() == 0 ? "footnote" : "endnote";
    session.transact(
        s -> {
          if (original == null) {
            s.insertNote(selectedKind, "", mark.getText());
            var selection = s.selection();
            var inserted =
                s.editingDocument().paragraph(selection.block).text.noteAt(selection.caret - 1);
            // Allocate fresh story identities after the reference insertion to avoid ID
            // collisions.
            var owned = new Sequence<Paragraph>();
            long id = s.document().nextId;
            for (Paragraph p : story) owned = owned.append(p.copy("p" + id++, p.text));
            s.updateNote(inserted.id, inserted.paragraphs(owned));
          } else
            s.updateNote(
                original.id,
                new Note(original.id, selectedKind, mark.getText(), original.label, story));
        });
    edited();
  }

  private Container references() {
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Table of Contents",
            OfficeTheme.row(
                iconButton(
                    "Table of Contents",
                    FontImage.MATERIAL_FORMAT_LIST_NUMBERED,
                    this::contentsDialog,
                    true),
                BoxLayout.encloseY(
                    button(
                        "Update Table",
                        () -> {
                          TableContents.update(session, editor::printLayout);
                          edited();
                        }),
                    button("Add Text", this::outlineDialog),
                    button(
                        "Remove Table",
                        () -> {
                          TableContents.remove(session);
                          edited();
                        })))),
        OfficeTheme.group(
            "Citations & Bibliography",
            OfficeTheme.row(
                iconButton(
                    "Insert Citation", FontImage.MATERIAL_FORMAT_QUOTE, this::citationDialog, true),
                BoxLayout.encloseY(
                    button("Manage Sources", this::sourcesDialog),
                    button("Citation Style", this::citationStyleDialog)),
                BoxLayout.encloseY(
                    button("Bibliography", this::bibliographyDialog),
                    button(
                        "Update Bibliography",
                        () -> {
                          Citations.update(session);
                          edited();
                        }),
                    button(
                        "Remove Bibliography",
                        () -> {
                          Citations.remove(session);
                          edited();
                        })))),
        OfficeTheme.group(
            "Index",
            OfficeTheme.row(
                iconButton(
                    "Mark Index Entry", FontImage.MATERIAL_BOOKMARK, this::markIndexDialog, true),
                BoxLayout.encloseY(
                    button("Insert Index", this::indexDialog),
                    button(
                        "Update Index",
                        () -> {
                          DocumentIndex.update(session, editor::printLayout);
                          edited();
                        }),
                    button(
                        "Remove Index",
                        () -> {
                          DocumentIndex.remove(session);
                          edited();
                        })))),
        OfficeTheme.group(
            "Table of Authorities",
            OfficeTheme.row(
                iconButton(
                    "Mark Citation", FontImage.MATERIAL_BOOKMARK, this::markAuthorityDialog, true),
                BoxLayout.encloseY(
                    button("Table of Authorities", this::authoritiesDialog),
                    button(
                        "Update Authorities",
                        () -> {
                          TableAuthorities.update(session, editor::printLayout);
                          edited();
                        }),
                    button(
                        "Remove Authorities",
                        () -> {
                          TableAuthorities.remove(session);
                          edited();
                        })))),
        OfficeTheme.group(
            "Contents entries",
            OfficeTheme.row(
                iconButton(
                    "Mark Entry", FontImage.MATERIAL_BOOKMARK, this::markContentsDialog, true))),
        OfficeTheme.group(
            "Footnotes",
            OfficeTheme.row(
                iconButton(
                    "Insert Footnote",
                    FontImage.MATERIAL_NOTES,
                    () -> noteDialog(null, "footnote"),
                    true),
                BoxLayout.encloseY(
                    button("Insert Endnote", () -> noteDialog(null, "endnote")),
                    button("Next Note", this::nextNote),
                    button("Show Notes", this::showNotes)),
                button("Note Options", this::noteOptionsDialog))),
        OfficeTheme.group(
            "Links",
            OfficeTheme.row(
                button("Bookmark", this::bookmarkDialog),
                button("Cross-reference", this::crossReferenceDialog),
                button("Go to reference", this::followReference))),
        OfficeTheme.group(
            "Captions",
            OfficeTheme.row(
                iconButton("Insert Caption", FontImage.MATERIAL_LABEL, this::captionDialog, true),
                BoxLayout.encloseY(
                    button("Table of Figures", () -> contentsDialog("Figure")),
                    button(
                        "Update figures",
                        () -> {
                          TableContents.updateFigures(session, editor::printLayout);
                          edited();
                        }),
                    button("Remove figures", this::removeFiguresDialog)),
                button(
                    "Update captions",
                    () -> {
                      session.mapFields(SequenceFields.resolver(session.document()));
                      edited();
                    }))),
        OfficeTheme.group(
            "Fields",
            OfficeTheme.row(
                button("Insert field\u2026", () -> fieldDialog(false, "PAGE")),
                button(
                    "Update fields",
                    () -> {
                      updateFields();
                      edited();
                    }))));
  }

  private void mailing(String command, Map<String, Object> args) {
    editor.stopInput();
    commands.execute(session, "mailings." + command, args);
    edited();
  }

  private void mailing(String command) {
    mailing(command, Json.object());
  }

  private Container mailings() {
    mergeStatus = OfficeTheme.label("No recipients", 12, OfficeTheme.INK);
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Create",
            OfficeTheme.row(
                BoxLayout.encloseY(
                    button("Envelopes", this::envelopeDialog),
                    button("Labels", this::labelsDialog)))),
        OfficeTheme.group(
            "Start Mail Merge",
            OfficeTheme.row(
                iconButton(
                    "Start Mail Merge", FontImage.MATERIAL_MAIL, this::mergeStartDialog, true),
                BoxLayout.encloseY(
                    button("Select Recipients", this::recipientsDialog),
                    button("Edit Recipient List", this::editRecipientsDialog)))),
        OfficeTheme.group(
            "Write & Insert Fields",
            OfficeTheme.row(
                iconButton(
                    "Address Block",
                    FontImage.MATERIAL_CONTACT_MAIL,
                    () -> mailing("addressBlock"),
                    true),
                BoxLayout.encloseY(
                    button("Greeting Line", () -> mailing("greetingLine")),
                    button("Insert Merge Field", this::mergeFieldDialog)),
                BoxLayout.encloseY(
                    button("Rules", this::mergeRuleDialog),
                    button("Match Fields", this::mergeMappingDialog),
                    button("Highlight Merge Fields", () -> mailing("highlightFields"))))),
        OfficeTheme.group(
            "Preview Results",
            OfficeTheme.row(
                iconButton(
                    "Preview Results",
                    FontImage.MATERIAL_VISIBILITY,
                    () -> mailing("preview"),
                    true),
                BoxLayout.encloseY(
                    button("Previous Recipient", () -> mailing("previous")),
                    button("Next Recipient", () -> mailing("next"))),
                BoxLayout.encloseY(
                    mergeStatus,
                    button("Find Recipient", this::findRecipientDialog),
                    button("Check for Errors", this::mergeErrorsDialog)))),
        OfficeTheme.group(
            "Finish",
            OfficeTheme.row(
                iconButton(
                    "Finish & Merge",
                    FontImage.MATERIAL_DONE_ALL,
                    this::finishMergeDialog,
                    true))));
  }

  private void envelopeDialog() {
    var data = session.mergeData();
    var args =
        new Fields()
            .choice("size", "Envelope size", MailingSheets.ENVELOPES, null)
            .text("delivery", "Delivery address", data.rows.isEmpty() ? "" : data.address(data.record), 5, 40)
            .text("return", "Return address", "", 3, 40)
            .check("add", "Add before the current document", true)
            .ask("Envelopes", "Create");
    if (args == null) return;
    mailing("envelopes", args);
    if (!(Boolean) args.get("add")) {
      forgetFile();
      source = null;
    }
  }


  private void labelsDialog() {
    var form =
        new Fields()
            .choice("product", "Label product", MailingSheets.PRODUCTS, null)
            .choice(
                "mode",
                "Contents",
                new String[] {"Full sheet of the same label", "One recipient per label", "Single label"},
                new String[] {"sheet", "recipients", "single"},
                "sheet")
            .note("Recipient labels continue onto additional sheets automatically.")
            .text("text", "Label text", "", 5, 40)
            .number("row", "Label row", 1)
            .number("col", "Label column", 1);
    var args = form.ask("Labels", "Create");
    if (args == null) return;
    var mode = args.remove("mode");
    args.put("fromRecipients", "recipients".equals(mode));
    var single = Json.object("row", args.remove("row"), "col", args.remove("col"));
    if ("single".equals(mode)) args.put("single", single);
    mailing("labels", args);
    forgetFile();
    source = null;
  }


  private void mergeStartDialog() {
    var current = session.mergeData().kind;
    var args =
        new Fields()
            .choice(
                "kind",
                "Document type",
                new String[] {"Letters", "Directory", "Envelopes", "Labels", "Emails", "Normal"},
                new String[] {"letters", "directory", "envelopes", "labels", "emails", "normal"},
                current == null ? null : current.toLowerCase())
            .ask("Start mail merge", "Apply");
    if (args != null) mailing("start", args);
  }

  private void recipientsDialog() {
    var f =
        new Fields()
            .index(
                "method",
                "Source",
                new String[] {"Type or paste a list", "Open CSV, TSV or spreadsheet"},
                0);
    if (f.ask("Select recipients", "Continue") == null) return;
    if (f.idx("method") == 0) {
      editRecipientSourceDialog();
      return;
    }
    if (files == null) throw new IllegalArgumentException("File picker is unavailable");
    files.openRecipients(
        (name, path, bytes) -> {
          try {
            if (bytes.length > 2 && bytes[0] == 'P' && bytes[1] == 'K') {
              var workbook = new RecipientWorkbook(bytes);
              var sheets =
                  new ComboBox<String>(
                      workbook.sheets().toArray(new String[workbook.sheets().size()]));
              var headers = check("First row contains field names", true);
              if (showDialog(
                  "Import worksheet",
                  BoxLayout.encloseY(formRow("Worksheet", sheets), headers),
                  "Import",
                  "Cancel")) {
                MailMerge.recipients(
                    session, workbook.read(sheets.getSelectedItem(), headers.isSelected()));
                edited();
              }
              return;
            }
            String encoding =
                bytes.length > 1
                        && ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254
                            || (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255)
                    ? "UTF-16"
                    : "UTF-8";
            var text = new String(bytes, encoding);
            var headers = check("First row contains field names", true);
            if (showDialog(
                "Import recipients",
                BoxLayout.encloseY(OfficeTheme.label(name, 12, OfficeTheme.INK), headers),
                "Import",
                "Cancel"))
              mailing("recipients", Json.object("csv", text, "headers", headers.isSelected()));
          } catch (Exception error) {
            showDialog(
                "Recipient import failed",
                OfficeTheme.label(error.getMessage(), 12, OfficeTheme.INK),
                "OK",
                null);
          }
        });
  }

  private void editRecipientsDialog() {
    final var data = session.mergeData();
    if (data.fields.isEmpty()) {
      editRecipientSourceDialog();
      return;
    }
    var names = new ArrayList<String>();
    names.add("(None)");
    names.addAll(data.fields);
    var sort = new ComboBox<String>(names.toArray(new String[names.size()]));
    var filter = new ComboBox<String>(names.toArray(new String[names.size()]));
    sort.setSelectedIndex(data.column(data.sortField) + 1);
    filter.setSelectedIndex(data.column(data.filterField) + 1);
    String[] operators = {"contains", "=", "<>", "<", ">", "<=", ">=", "blank", "notBlank"};
    var comparison =
        new ComboBox<String>(
            "Contains",
            "Equals",
            "Does not equal",
            "Less than",
            "Greater than",
            "At most",
            "At least",
            "Is blank",
            "Is not blank");
    comparison.setSelectedIndex(java.util.Arrays.asList(operators).indexOf(data.filterOperator));
    var descending = check("Descending order", data.descending);
    var value = field(data.filterValue);
    final var excluded = new java.util.LinkedHashSet<Integer>(data.excluded);
    final int[] page = {0};
    var rows = new Container(BoxLayout.y());
    Label count = OfficeTheme.label("", 12, OfficeTheme.INK);
    Label pageLabel = OfficeTheme.label("", 12, OfficeTheme.INK);
    Runnable render =
        () -> {
          rows.removeAll();
          int start = page[0] * 5, end = Math.min(start + 5, data.sourceRows.size());
          pageLabel.setText(
              data.sourceRows.isEmpty()
                  ? "No source recipients"
                  : "Source recipients "
                      + (start + 1)
                      + "\u2013"
                      + end
                      + " of "
                      + data.sourceRows.size());
          for (int i = start; i < end; i++) {
            final int index = i;
            var include = check(recipientSummary(data, i), !excluded.contains(i));
            include.setAccessibilityText("Include recipient " + (i + 1));
            include.addActionListener(
                e -> {
                  if (include.isSelected()) excluded.remove(index);
                  else excluded.add(index);
                });
            rows.add(include);
          }
          styleDialogBody(rows);
          rows.revalidate();
        };
    Runnable preview =
        () -> {
          var draft =
              data.manage(
                  sort.getSelectedIndex() == 0 ? "" : sort.getSelectedItem(),
                  descending.isSelected(),
                  filter.getSelectedIndex() == 0 ? "" : filter.getSelectedItem(),
                  operators[comparison.getSelectedIndex()],
                  value.getText(),
                  excluded);
          count.setText(
              draft.rows.size() + " of " + data.sourceRows.size() + " recipients selected");
          count.getParent().revalidate();
        };
    Container body =
        BoxLayout.encloseY(
            OfficeTheme.label("Choose recipients, then filter and sort.", 12, OfficeTheme.INK),
            formRow("Sort by", sort),
            descending,
            formRow("Filter field", filter),
            formRow("Comparison", comparison),
            formRow("Compare to", value),
            button("Preview recipient count", preview),
            count,
            pageLabel,
            rows,
            FlowLayout.encloseIn(
                button(
                    "Previous recipients",
                    () -> {
                      if (page[0] > 0) {
                        page[0]--;
                        render.run();
                      }
                    }),
                button(
                    "Next recipients",
                    () -> {
                      if ((page[0] + 1) * 5 < data.sourceRows.size()) {
                        page[0]++;
                        render.run();
                      }
                    })),
            FlowLayout.encloseIn(
                button(
                    "Include all source recipients",
                    () -> {
                      excluded.clear();
                      render.run();
                      preview.run();
                    }),
                button(
                    "Exclude all source recipients",
                    () -> {
                      for (int i = 0; i < data.sourceRows.size(); i++) excluded.add(i);
                      render.run();
                      preview.run();
                    })));
    render.run();
    preview.run();
    if (showDialog("Mail merge recipients", body, "Apply", "Cancel"))
      mailing(
          "manageRecipients",
          Json.object(
              "sortField",
              sort.getSelectedIndex() == 0 ? "" : sort.getSelectedItem(),
              "descending",
              descending.isSelected(),
              "filterField",
              filter.getSelectedIndex() == 0 ? "" : filter.getSelectedItem(),
              "filterOperator",
              operators[comparison.getSelectedIndex()],
              "filterValue",
              value.getText(),
              "excluded",
              new ArrayList<Integer>(excluded)));
  }

  private String recipientSummary(MergeData data, int index) {
    var text = new StringBuilder("" + (index + 1) + ". ");
    for (int i = 0; i < Math.min(OfficeTheme.mobile() ? 1 : 3, data.fields.size()); i++) {
      if (i > 0) text.append(" \u00b7 ");
      var cell = data.sourceRows.get(index).get(i).replace('\n', ' ').replace('\r', ' ');
      text.append(cell.length() > 24 ? cell.substring(0, 24) + "\u2026" : cell);
    }
    return text.toString();
  }

  private void editRecipientSourceDialog() {
    var data = session.mergeData();
    var args =
        new Fields()
            .note("One recipient per row. Quote values containing commas.")
            .text(
                "csv",
                "Recipient CSV",
                data.fields.isEmpty()
                    ? "First Name,Last Name,Address,City,State,PostalCode\n"
                    : data.csv(),
                10,
                50)
            .ask("Edit recipient list", "Apply");
    if (args != null) mailing("recipients", args);
  }

  private String[] recipientFields() {
    if (session.mergeData().fields.isEmpty())
      throw new IllegalArgumentException("Select recipients first");
    return session.mergeData().fields.toArray(new String[session.mergeData().fields.size()]);
  }

  private void mergeFieldDialog() {
    var args =
        new Fields().choice("field", "Field", recipientFields(), null).ask("Insert merge field", "Insert");
    if (args != null) mailing("insertField", args);
  }

  private void mergeRuleDialog() {
    var args =
        new Fields()
            .choice("rule", "Rule", new String[] {"IF", "NEXT", "NEXTIF", "SKIPIF", "MERGEREC"}, null)
            .choice("field", "Field", recipientFields(), null)
            .choice("operator", "Comparison", new String[] {"=", "<>", "<", ">", "<=", ">="}, null)
            .text("value", "Compare to", "")
            .text("then", "If true", "")
            .text("else", "If false", "")
            .ask("Merge rule", "Insert");
    if (args != null) mailing("rules", args);
  }

  private void mergeMappingDialog() {
    recipientFields();
    var inputs = new LinkedHashMap<String, ComboBox<String>>();
    var body = new Container(BoxLayout.y());
    var names = new ArrayList<String>();
    names.add("");
    names.addAll(session.mergeData().fields);
    for (Map.Entry<String, String> entry : session.mergeData().mappedFields().entrySet()) {
      var input = new ComboBox<String>(names.toArray(new String[names.size()]));
      input.setSelectedItem(entry.getValue());
      inputs.put(entry.getKey(), input);
      body.add(formRow(entry.getKey(), input));
    }
    if (showDialog("Match fields", body, "Apply", "Cancel")) {
      var mapping = new LinkedHashMap<String, String>();
      for (Map.Entry<String, ComboBox<String>> e : inputs.entrySet())
        mapping.put(e.getKey(), e.getValue().getSelectedItem());
      mailing("matchFields", Json.object("mapping", mapping));
    }
  }

  private void findRecipientDialog() {
    var args = new Fields().text("text", "Find text", "").ask("Find recipient", "Find");
    if (args != null) mailing("findRecipient", args);
  }

  private void mergeErrorsDialog() {
    var errors = MailMerge.errors(session.document(), session.mergeData());
    String message =
        session.mergeData().rows.isEmpty()
            ? "Select recipients first."
            : errors.isEmpty()
                ? "All fields match. " + session.mergeData().rows.size() + " recipients."
                : "Unknown fields: " + errors;
    showDialog("Merge validation", OfficeTheme.label(message, 12, OfficeTheme.INK), "OK", null);
  }

  private void finishMergeDialog() {
    var args =
        new Fields()
            .note("Create an editable merged document. Undo restores the template.")
            .number("from", "First recipient", 1)
            .number("to", "Last recipient", session.mergeData().rows.size())
            .ask("Finish merge", "Merge");
    if (args == null) return;
    mailing("finish", args);
    forgetFile();
    source = null;
  }

  private CitationSource sourceDialog(CitationSource original) {
    String[] labels = {
      "Source tag",
      "Source type",
      "Author",
      "Editor",
      "Title",
      "Year",
      "Month",
      "Day",
      "City",
      "Publisher",
      "Journal",
      "Edition",
      "Volume",
      "Issue",
      "Source pages",
      "URL",
      "Accessed",
      "DOI"
    };
    var fields = new LinkedHashMap<String, EditField>();
    var body = new Container(BoxLayout.y());
    for (int i = 0; i < CitationSource.KEYS.length; i++) {
      var key = CitationSource.KEYS[i];
      var input =
          field(original == null ? "kind".equals(key) ? "book" : "" : original.get(key));
      if (original != null && "tag".equals(key)) input.setEditable(false);
      fields.put(key, input);
      body.add(formRow(labels[i], input));
    }
    body.add(new Label("Authors: Last, First; Last, First"));
    if (!showDialog(original == null ? "Create source" : "Edit source", body, "Save", "Cancel"))
      return null;
    var values = new LinkedHashMap<String, String>();
    for (Map.Entry<String, EditField> entry : fields.entrySet())
      values.put(entry.getKey(), entry.getValue().getText());
    var result = new CitationSource(values);
    if (original == null && session.document().bibliography.sources.containsKey(result.tag())) {
      if (!values.get("tag").trim().isEmpty())
        throw new IllegalArgumentException(
            "Source tag already exists; use Manage Sources to edit it");
      var base = result.tag();
      if (base.length() > 58) base = base.substring(0, 58);
      int suffix = 2;
      while (session.document().bibliography.sources.containsKey(base + suffix)) suffix++;
      values.put("tag", base + suffix);
      result = new CitationSource(values);
    }
    return result;
  }

  private void sourcesDialog() {
    editor.stopInput();
    var sources =
        new ArrayList<CitationSource>(session.document().bibliography.sources.values());
    String[] labels = new String[Math.max(1, sources.size())];
    if (sources.isEmpty()) labels[0] = "No sources";
    for (int i = 0; i < sources.size(); i++)
      labels[i] = sources.get(i).tag() + " \u2014 " + sources.get(i).get("title");
    var f =
        new Fields()
            .index("source", "Source", labels, 0)
            .choice(
                "action",
                "Action",
                sources.isEmpty()
                    ? new String[] {"New source"}
                    : new String[] {"Edit source", "New source", "Delete source"},
                null);
    if (f.ask("Manage sources", "Continue") == null) return;
    if ("Delete source".equals(f.str("action"))) {
      var selected = sources.get(f.idx("source"));
      if (showDialog(
          "Delete source",
          new Label("Existing citations will show a missing-source message."),
          "Delete",
          "Cancel")) {
        Citations.removeSource(session, selected.tag());
        edited();
      }
    } else {
      var editedSource =
          sourceDialog("New source".equals(f.str("action")) ? null : sources.get(f.idx("source")));
      if (editedSource != null) {
        Citations.source(session, editedSource);
        edited();
      }
    }
  }

  private void citationDialog() {
    editor.stopInput();
    var sources =
        new ArrayList<CitationSource>(session.document().bibliography.sources.values());
    String[] choices = new String[sources.size() + 1];
    choices[0] = "Add new source";
    for (int i = 0; i < sources.size(); i++)
      choices[i + 1] = sources.get(i).tag() + " \u2014 " + sources.get(i).get("title");
    var f =
        new Fields()
            .index("source", "Source", choices, sources.isEmpty() ? 0 : 1)
            .text("pages", "Citation pages", "");
    if (f.ask("Insert citation", "Insert") == null) return;
    int index = f.idx("source");
    CitationSource created = index == 0 ? sourceDialog(null) : null;
    if (index == 0 && created == null) return;
    Citations.insert(
        session, created == null ? sources.get(index - 1).tag() : created.tag(), created, f.str("pages"));
    edited();
  }

  private void citationStyleDialog() {
    var f =
        new Fields()
            .choice("style", "Style", CitationStyles.NAMES, session.document().bibliography.style);
    if (f.ask("Citation style", "Apply") == null) return;
    Citations.style(session, f.str("style"));
    edited();
  }

  private void bibliographyDialog() {
    int[] range = Citations.range(session.document());
    var title =
        range == null
            ? CitationStyles.title(CitationStyles.index(session.document().bibliography.style))
            : session.document().paragraph(range[0]).text.plainText();
    var f = new Fields().text("title", "Title", title);
    if (f.ask("Bibliography", "Apply") == null) return;
    Citations.bibliography(session, f.str("title"));
    edited();
  }

  private void markAuthorityDialog() {
    editor.stopInput();
    var f = new Fields().text("entry", "Citation", session.selectedText());
    if (f.ask("Mark citation", "Mark") == null) return;
    TableAuthorities.mark(session, f.str("entry"));
    edited();
  }

  private void authoritiesDialog() {
    editor.stopInput();
    DocumentIndex.Options old = TableAuthorities.options(session.document());
    var f =
        new Fields()
            .text("title", "Title", old.title)
            .choice(
                "leader",
                "Tab leader",
                new String[] {"None", "Dots", "Hyphens", "Underscores", "Middle dots"},
                new String[] {"none", "dot", "hyphen", "underscore", "middleDot"},
                old.leader);
    if (f.ask("Table of authorities", "Apply") == null) return;
    TableAuthorities.insert(session, f.str("title"), f.str("leader"), editor::printLayout);
    edited();
  }

  private CheckBox check(String text, boolean selected) {
    var result = new CheckBox(text);
    result.setSelected(selected);
    return result;
  }

  private void markIndexDialog() {
    editor.stopInput();
    var f =
        new Fields()
            .text("entry", "Main entry", session.selectedText())
            .text("sub", "Subentry", "")
            .text("cross", "Cross-reference", "")
            .text("bookmark", "Page-range bookmark", "")
            .check("bold", "Bold page numbers", false)
            .check("italic", "Italic page numbers", false)
            .check("all", "Mark all occurrences", false)
            .text("search", "Text to mark", session.selectedText());
    if (f.ask("Mark index entry", "Mark") == null) return;
    var entry = f.str("entry");
    DocumentIndex.mark(
        session, entry, f.str("sub"), f.str("cross"), f.str("bookmark").trim(), f.flag("bold"),
        f.flag("italic"), f.flag("all"), f.str("search").isEmpty() ? entry : f.str("search"));
    edited();
  }


  private void indexDialog() {
    editor.stopInput();
    var old = DocumentIndex.options(session.document());
    var f =
        new Fields()
            .text("title", "Title", old.title)
            .choice("type", "Type", new String[] {"Indented", "Run-in"}, old.runIn ? "Run-in" : "Indented")
            .check("headings", "Letter headings", old.headings)
            .check("right", "Right-align page numbers", old.rightAlign)
            .choice(
                "leader",
                "Tab leader",
                new String[] {"None", "Dots", "Hyphens", "Underscores", "Middle dots"},
                new String[] {"none", "dot", "hyphen", "underscore", "middleDot"},
                old.leader);
    if (f.ask("Index", "Apply") == null) return;
    var options =
        new DocumentIndex.Options(
            f.str("title"), "Run-in".equals(f.str("type")), f.flag("headings"), f.flag("right"), f.str("leader"));
    DocumentIndex.insert(session, options, editor::printLayout);
    edited();
  }


  private void removeFiguresDialog() {
    var f = new Fields().text("label", "Caption label", "Figure");
    if (f.ask("Remove table of figures", "Remove") == null) return;
    TableContents.remove(session, f.str("label").trim());
    edited();
  }

  private void contentsDialog() {
    contentsDialog(null);
  }

  private void contentsDialog(String caption) {
    editor.stopInput();
    TableContents.Options old = TableContents.options(session.document(), caption);
    var captionLabel = field(caption == null ? "" : caption);
    var title = field(old.title);
    title.setAccessibilityText("Contents title");
    var levels = new ComboBox<String>("1", "2", "3", "4", "5", "6", "7", "8", "9");
    levels.setSelectedIndex(old.levels - 1);
    levels.setAccessibilityText("Contents levels");
    var leader =
        new ComboBox<String>("Dot", "Hyphen", "Underscore", "None", "Middle dot");
    String[] leaders = {"dot", "hyphen", "underscore", "none", "middleDot"};
    leader.setSelectedIndex(Arrays.asList(leaders).indexOf(old.leader));
    leader.setAccessibilityText("Contents tab leader");
    var pages = new CheckBox("Show page numbers");
    pages.setSelected(old.pageNumbers);
    var right = new CheckBox("Right align page numbers");
    right.setSelected(old.rightAlign);
    var links = new CheckBox("Link entries to headings");
    links.setSelected(old.hyperlinks);
    CheckBox headings = new CheckBox("Use heading styles"),
        outlines = new CheckBox("Use outline levels"),
        marked = new CheckBox("Use marked entries");
    headings.setSelected(old.headingStyles);
    outlines.setSelected(old.outlineLevels);
    marked.setSelected(old.tcFields);
    var identifier = field(old.tcIdentifier);
    var styleLevels = new LinkedHashMap<String, Integer>(old.styleLevels);
    Container body =
        BoxLayout.encloseY(
            formRow("Title", title),
            caption == null
                ? formRow("Show levels", levels)
                : formRow("Caption label", captionLabel),
            pages,
            right,
            formRow("Tab leader", leader),
            links);
    if (caption == null)
      body.add(
          BoxLayout.encloseY(
              headings,
              outlines,
              marked,
              formRow("Entry identifier (blank for all)", identifier),
              button("Style levels\u2026", () -> contentsStyleDialog(styleLevels))));
    if (!showDialog(
        caption == null ? "Table of contents" : "Table of figures", body, "Insert", "Cancel"))
      return;
    TableContents.insert(
        session,
        new TableContents.Options(
            levels.getSelectedIndex() + 1,
            title.getText(),
            pages.isSelected(),
            right.isSelected(),
            leaders[leader.getSelectedIndex()],
            links.isSelected(),
            caption == null ? null : captionLabel.getText().trim(),
            headings.isSelected(),
            outlines.isSelected(),
            marked.isSelected(),
            styleLevels,
            identifier.getText()),
        editor::printLayout);
    edited();
  }

  private void contentsStyleDialog(Map<String, Integer> levels) {
    var names = new java.util.LinkedHashSet<String>();
    for (ParagraphStyle style : session.document().styles.values())
      if (!style.id.startsWith("TOC") && !"ListEntry".equals(style.id)) names.add(style.name);
    names.addAll(levels.keySet());
    var choices = new LinkedHashMap<String, ComboBox<String>>();
    var body = new Container(BoxLayout.y());
    for (String name : names) {
      var choice =
          new ComboBox<String>("Not included", "1", "2", "3", "4", "5", "6", "7", "8", "9");
      choice.setSelectedIndex(levels.containsKey(name) ? levels.get(name) : 0);
      choices.put(name, choice);
      body.add(formRow(name, choice));
    }
    if (!showDialog("Contents style levels", body, "Apply", "Cancel")) return;
    levels.clear();
    for (Map.Entry<String, ComboBox<String>> entry : choices.entrySet())
      if (entry.getValue().getSelectedIndex() > 0)
        levels.put(entry.getKey(), entry.getValue().getSelectedIndex());
  }

  private void outlineDialog() {
    var p = session.editingDocument().paragraph(session.selection().block);
    var f =
        new Fields()
            .index(
                "level",
                "Outline level",
                new String[] {
                  "Do not show", "Level 1", "Level 2", "Level 3", "Level 4", "Level 5", "Level 6",
                  "Level 7", "Level 8", "Level 9"
                },
                p.format.outlineLevel >= 0 ? p.format.outlineLevel : SequenceFields.headingLevel(p.style));
    if (f.ask("Add text to contents", "Apply") == null) return;
    session.paragraph("outlineLevel", "" + f.idx("level"));
    edited();
  }

  private void markContentsDialog() {
    var f =
        new Fields()
            .text("text", "Entry text", session.selectedText())
            .index("level", "Entry level", LEVELS, 0)
            .text("identifier", "Identifier", "C");
    if (f.ask("Mark contents entry", "Mark") == null) return;
    TableContents.markEntry(session, f.str("text"), f.idx("level") + 1, f.str("identifier"));
    edited();
  }

  private static final String[] LEVELS = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};

  private void bookmarkDialog() {
    editor.stopInput();
    var dialog = officeDialog("Bookmarks");
    dialog.setLayout(new BorderLayout());
    var name = field("");
    name.setAccessibilityText("Bookmark name");
    var names = new ArrayList<String>(session.document().bookmarks.keySet());
    java.util.Collections.sort(names);
    var existing =
        new ComboBox<String>(
            names.isEmpty()
                ? new String[] {"No bookmarks"}
                : names.toArray(new String[names.size()]));
    existing.setAccessibilityText("Existing bookmarks");
    existing.addActionListener(
        e -> {
          if (!names.isEmpty()) name.setText(existing.getSelectedItem());
        });
    var add =
        button(
            "Add",
            () -> {
              session.addBookmark(name.getText().trim());
              dialog.dispose();
              edited();
            });
    var go =
        button(
            "Go To",
            () -> {
              session.goToBookmark(existing.getSelectedItem());
              dialog.dispose();
              edited();
            });
    var remove =
        button(
            "Delete",
            () -> {
              session.deleteBookmark(existing.getSelectedItem());
              dialog.dispose();
              edited();
            });
    go.setEnabled(!names.isEmpty());
    remove.setEnabled(!names.isEmpty());
    dialog.add(
        BorderLayout.CENTER,
        BoxLayout.encloseY(
            new Label("Bookmark name"), name, new Label("Existing bookmarks"), existing));
    dialog.add(
        BorderLayout.SOUTH, OfficeTheme.row(add, go, remove, button("Cancel", dialog::dispose)));
    styleDialogBody(dialog.getContentPane());
    showOfficeDialog(dialog);
  }

  private void crossReferenceDialog() {
    editor.stopInput();
    var kind =
        new ComboBox<String>("Heading", "Bookmark", "Figure", "Table", "Equation");
    kind.setAccessibilityText("Reference type");
    var target = new ComboBox<String>();
    target.setAccessibilityText("Reference target");
    var show =
        new ComboBox<String>("Text", "Page number", "Label and number", "Above or below");
    show.setAccessibilityText("Reference display");
    Runnable refresh =
        () -> {
          var model =
              new com.codename1.ui.list.DefaultListModel<String>();
          for (String label :
              CrossReferences.targets(session.document(), kind.getSelectedItem().toLowerCase())
                  .keySet()) model.addItem(label);
          target.setModel(model);
        };
    kind.addActionListener(e -> refresh.run());
    refresh.run();
    if (!showDialog(
        "Cross-reference",
        BoxLayout.encloseY(
            new Label("Reference type"),
            kind,
            new Label("Insert reference to"),
            show,
            new Label("Target"),
            target),
        "Insert",
        "Cancel")) return;
    if (target.getSelectedItem() == null)
      throw new IllegalArgumentException(
          "No targets of this type; add a heading, caption or bookmark first");
    var display = new String[] {"text", "page", "number", "aboveBelow"}[show.getSelectedIndex()];
    session.transact(
        s -> {
          commands.execute(
              s,
              "reference.insert",
              Json.object(
                  "kind",
                  kind.getSelectedItem().toLowerCase(),
                  "target",
                  target.getSelectedItem(),
                  "show",
                  display));
          updateFields();
        });
    edited();
  }

  private void followReference() {
    var p = session.editingDocument().paragraph(session.selection().block);
    int caret = session.selection().caret;
    InlineField field = caret < p.text.length() ? p.text.fieldAt(caret) : null;
    if (field == null && caret > 0) field = p.text.fieldAt(caret - 1);
    if (field == null && p.text.length() > 0) {
      var link = p.text.styleAt(Math.min(caret, p.text.length() - 1)).link;
      if (link.startsWith("#")) {
        session.goToBookmark(link.substring(1));
        edited();
        return;
      }
    }
    if (field == null)
      throw new IllegalArgumentException(
          "Place the caret in a cross-reference or linked contents entry");
    var code = new FieldCode(field.instruction);
    if (!("REF".equals(code.name) || "PAGEREF".equals(code.name)) || code.arguments.isEmpty())
      throw new IllegalArgumentException("Select a cross-reference field");
    session.goToBookmark(code.arguments.get(0));
    edited();
  }

  private void captionDialog() {
    editor.stopInput();
    var f =
        new Fields()
            .text("label", "Label", "Figure")
            .text("text", "Caption text", "")
            .index("position", "Position", new String[] {"Below selected item", "Above selected item"}, 0)
            .index(
                "format",
                "Number format",
                new String[] {"1, 2, 3", "a, b, c", "A, B, C", "i, ii, iii", "I, II, III"},
                0)
            .check("exclude", "Exclude label from caption", false)
            .index(
                "chapter",
                "Chapter numbering",
                new String[] {
                  "No chapter number", "Heading 1", "Heading 2", "Heading 3", "Heading 4",
                  "Heading 5", "Heading 6", "Heading 7", "Heading 8", "Heading 9"
                },
                0)
            .choice("separator", "Separator", new String[] {"-", ".", ":", "\u2014", "\u2013"}, null);
    f.body.setScrollableY(true);
    if (OfficeTheme.mobile()) f.body.setPreferredH(Display.getInstance().getDisplayHeight() / 2);
    if (f.ask("Insert Caption", "Insert") == null) return;
    session.insertCaption(
        f.str("label"),
        f.str("text"),
        f.idx("position") == 1,
        new String[] {"ARABIC", "alphabetic", "ALPHABETIC", "roman", "ROMAN"}[f.idx("format")],
        f.flag("exclude"),
        f.idx("chapter"),
        f.str("separator"));
    edited();
  }

  private Button trackingButton;

  private void trackingOptions() {
    var enabled = new CheckBox("Track changes");
    enabled.setSelected(session.document().trackChanges);
    var formatting = new CheckBox("Track formatting");
    formatting.setSelected(session.trackFormatting());
    var author = field(session.reviewAuthor());
    author.setAccessibilityText("Revision author");
    if (showDialog(
        "Track Changes",
        BoxLayout.encloseY(enabled, formatting, new Label("Author"), author),
        "Apply",
        "Cancel")) {
      editor.finishComposing();
      session.reviewAuthor(author.getText());
      session.trackFormatting(formatting.isSelected());
      session.trackChanges(enabled.isSelected());
      edited();
    }
  }

  private void revisionAction(boolean accept, boolean all) {
    editor.finishComposing();
    if (all) session.resolveRevision(null, accept);
    else session.resolveSelectedRevisions(accept);
    edited();
  }

  private Container revisionPane, revisionList;
  private Document displayedRevisions;

  private void closeRevisions() {
    if (revisionPane != null) revisionPane.remove();
    revisionPane = revisionList = null;
    displayedRevisions = null;
    workspace.revalidate();
  }

  private void refreshRevisions() {
    if (revisionList == null || displayedRevisions == session.document()) return;
    displayedRevisions = session.document();
    revisionList.removeAll();
    for (Revisions.Change change : Revisions.list(displayedRevisions)) {
      Container row =
          BoxLayout.encloseY(
              OfficeTheme.label(
                  change.revision.author + " \u00b7 " + change.revision.kind, 13, OfficeTheme.INK),
              OfficeTheme.label(change.revision.date, 10, 0x666666),
              new SpanLabelCompat(change.text),
              OfficeTheme.row(
                  button(
                      "Go to",
                      () -> {
                        if (change.story.isEmpty()) {
                          session.selectRevision(change);
                          editor.revealSelection();
                          editor.startInput();
                        } else if (change.story.startsWith("note:")) {
                          for (Note note : Notes.all(session.document()))
                            if (note.id.equals(change.story.substring(5)))
                              noteDialog(note, note.kind);
                        } else {
                          session.leaveCell();
                          session.select(change.block, 0, 0);
                          editFurniture(change.story.toLowerCase().endsWith("footer"));
                        }
                      }),
                  button(
                      "Accept",
                      () -> {
                        session.resolveRevision(change.revision.id, true);
                        edited();
                      }),
                  button(
                      "Reject",
                      () -> {
                        session.resolveRevision(change.revision.id, false);
                        edited();
                      })));
      OfficeTheme.space(row, 6, 10, 6, 6);
      row.getAllStyles()
          .setBorder(
              Border.createCompoundBorder(null, Border.createLineBorder(1, 0xdddddd), null, null));
      revisionList.add(row);
    }
    if (revisionList.getComponentCount() == 0) revisionList.add(new Label("No tracked changes"));
    styleDialogBody(revisionList);
    revisionList.revalidate();
  }

  private void revisionsPane() {
    editor.finishComposing();
    editor.stopInput();
    if (revisionPane != null && revisionPane.getParent() != null) {
      closeRevisions();
      return;
    }
    revisionPane = new Container(new BorderLayout());
    revisionList = BoxLayout.encloseY();
    revisionList.setScrollableY(true);
    revisionPane.add(BorderLayout.CENTER, revisionList);
    displayedRevisions = null;
    refreshRevisions();
    if (OfficeTheme.mobile()) {
      var dialog = officeDialog("Reviewing Pane");
      dialog.setLayout(new BorderLayout());
      revisionPane.setPreferredW(Display.getInstance().getDisplayWidth() - OfficeTheme.px(32));
      revisionPane.setPreferredH(Display.getInstance().getDisplayHeight() / 2);
      dialog.add(BorderLayout.CENTER, revisionPane);
      dialog.add(BorderLayout.SOUTH, button("Close", dialog::dispose));
      styleDialogBody(dialog.getContentPane());
      showOfficeDialog(dialog);
      closeRevisions();
    } else {
      if (stylesPane != null) {
        stylesPane.remove();
        stylesPane = null;
      }
      revisionPane.setPreferredW(OfficeTheme.px(280));
      revisionPane.add(
          BorderLayout.NORTH,
          OfficeTheme.row(
              OfficeTheme.label("Revisions", 18, OfficeTheme.INK),
              button("Close", this::closeRevisions)));
      styleDialogBody(revisionPane);
      workspace.add(BorderLayout.EAST, revisionPane);
      workspace.revalidate();
    }
  }

  private Container review() {
    trackingButton =
        iconButton(
            "Track Changes",
            FontImage.MATERIAL_EDIT,
            () -> {
              editor.finishComposing();
              session.trackChanges(!session.document().trackChanges);
              edited();
            },
            true);
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Proofing",
            OfficeTheme.row(
                iconButton(
                    "Writing checks",
                    FontImage.MATERIAL_SPELLCHECK,
                    () ->
                        showText(
                            "Writing checks",
                            com.codename1.io.JSONParser.toJson(commands.execute(session, "quality.inspect"))),
                    true),
                iconButton(
                    "Word count",
                    FontImage.MATERIAL_FORMAT_LIST_NUMBERED,
                    () ->
                        showText(
                            "Word count",
                            statistics.words(session.document())
                                + " words\n"
                                + session.document().plainText().length()
                                + " characters\n"
                                + editor.layout().pages.size()
                                + " pages"),
                    true))),
        OfficeTheme.group(
            "Comments",
            OfficeTheme.row(
                iconButton("New comment", FontImage.MATERIAL_COMMENT, this::newComment, true),
                iconButton("Show comments", FontImage.MATERIAL_LIST, this::comments, true),
                BoxLayout.encloseY(
                    button("Previous comment", () -> navigateComment(true)),
                    button("Next comment", () -> navigateComment(false))))),
        OfficeTheme.group(
            "Tracking",
            OfficeTheme.row(
                trackingButton,
                BoxLayout.encloseY(
                    button("Reviewing Pane", this::revisionsPane),
                    button("Tracking options\u2026", this::trackingOptions)))),
        OfficeTheme.group(
            "Compare",
            OfficeTheme.row(
                iconButton(
                    "Compare documents", FontImage.MATERIAL_COMPARE, this::compareDialog, true))),
        OfficeTheme.group(
            "Changes",
            OfficeTheme.row(
                BoxLayout.encloseY(
                    button("Accept", () -> revisionAction(true, false)),
                    button("Accept all", () -> revisionAction(true, true))),
                BoxLayout.encloseY(
                    button("Reject", () -> revisionAction(false, false)),
                    button("Reject all", () -> revisionAction(false, true))),
                BoxLayout.encloseY(
                    button(
                        "Previous change",
                        () -> {
                          session.nextRevision(true);
                          edited();
                        }),
                    button(
                        "Next change",
                        () -> {
                          session.nextRevision(false);
                          edited();
                        })))));
  }

  private void compareDialog() {
    editor.stopInput();
    var sourceChoice =
        new ComboBox<String>("Type or paste revised text", "Open revised document");
    if (!showDialog(
        "Compare documents", formRow("Revised source", sourceChoice), "Continue", "Cancel")) return;
    if (sourceChoice.getSelectedIndex() == 0) {
      compareOptions(null);
      return;
    }
    if (files == null) throw new IllegalArgumentException("File picker is unavailable");
    files.open(
        (name, path, bytes) ->
            safe(
                () -> {
                  try {
                    Document revised;
                    if (name.toLowerCase().endsWith(".docx")) {
                      Docx.Opened opened = Docx.read(new ByteArrayInputStream(bytes));
                      if (!opened.warnings.isEmpty()
                          && !showDialog(
                              "Compatibility report",
                              new SpanLabelCompat(join(opened.warnings)),
                              "Compare copy",
                              "Cancel")) return;
                      revised = opened.document;
                    } else if (name.toLowerCase().endsWith(".doc")) {
                      revised = DocBinary.read(bytes).document;
                    } else if (name.toLowerCase().endsWith(".cn1doc"))
                      revised = ProjectFile.read(bytes).document;
                    else if (name.toLowerCase().endsWith(".txt"))
                      revised =
                          TextFormats.read(new String(bytes, "UTF-8"), RichTextFormat.PLAIN_TEXT);
                    else
                      throw new IllegalArgumentException("Choose a DOCX, CN1DOC or text document");
                    compareOptions(revised);
                  } catch (IOException error) {
                    throw new IllegalArgumentException(error.getMessage());
                  }
                }));
  }

  private void compareOptions(Document revised) {
    EditField text = field("", 6, 40), author = field(session.reviewAuthor());
    var level = new ComboBox<String>("Word", "Character");
    CheckBox cases = check("Case changes", true),
        spaces = check("Whitespace changes", true),
        formatting = check("Formatting changes", true);
    Container body =
        BoxLayout.encloseY(
            OfficeTheme.label(
                "Compare the current document with a revised copy.", 12, OfficeTheme.INK));
    if (revised == null) body.add(formRow("Revised text", text));
    body.add(formRow("Label changes with", author))
        .add(formRow("Compare at", level))
        .add(cases)
        .add(spaces)
        .add(formatting);
    body.add(
        new SpanLabelCompat(
            "Compares paragraph and table-cell text and direct formatting. Resolve existing tracked changes first. Page layout and headers use the original document. Undo restores the original."));
    if (!showDialog("Comparison options", body, "Compare", "Cancel")) return;
    var args =
        Json.object(
            "author",
            author.getText(),
            "level",
            level.getSelectedIndex() == 0 ? "word" : "character",
            "caseChanges",
            cases.isSelected(),
            "whiteSpace",
            spaces.isSelected(),
            "formatting",
            formatting.isSelected());
    if (revised == null) args.put("text", text.getText());
    else args.put("xml", DocumentXml.write(revised));
    Map<?, ?> result = (Map<?, ?>) commands.execute(session, "review.compare", args);
    forgetFile();
    source = null;
    editor.resetScroll();
    edited();
    showDialog(
        "Comparison complete",
        result.get("changes")
            + " changed ranges. Use Accept or Reject to review them."
            + (Boolean.TRUE.equals(result.get("coarse"))
                ? " Large unmatched ranges were compared as whole replacements."
                : ""),
        "OK",
        null);
  }

  private void newComment() {
    var author = field("Author");
    var text = field("", 4, 28);
    author.setAccessibilityText("Author");
    text.setAccessibilityText("Comment");
    if (showDialog(
        "New comment",
        BoxLayout.encloseY(new Label("Author"), author, new Label("Comment"), text),
        "Add",
        "Cancel")) {
      session.addComment(author.getText(), text.getText());
      edited();
    }
  }

  private void navigateComment(boolean backwards) {
    editor.stopInput();
    var id = session.navigateComment(backwards);
    if (id == null) {
      showText("Comments", "No comments");
      return;
    }
    editor.revealSelection();
    editor.startInput();
  }

  private void editComment(Dialog parent, ReviewComment comment, int replyIndex) {
    String title = replyIndex < 0 ? "Edit comment" : "Edit reply";
    var text =
        field(replyIndex < 0 ? comment.text : comment.replies.get(replyIndex).text, 4, 28);
    if (showDialog(title, formRow("Text", text), "Save", "Cancel")) {
      session.editComment(comment.id, replyIndex, text.getText());
      parent.dispose();
      edited();
      comments();
    }
  }

  private void comments() {
    editor.stopInput();
    var dialog = officeDialog("Comments");
    dialog.setLayout(new BorderLayout());
    var list = new Container(BoxLayout.y());
    list.setScrollableY(true);
    list.setPreferredW(
        Math.min(
            OfficeTheme.px(420), Display.getInstance().getDisplayWidth() - OfficeTheme.px(32)));
    list.setPreferredH(Display.getInstance().getDisplayHeight() / 2);
    for (ReviewComment c : session.document().comments) {
      var row = new Container(BoxLayout.y());
      OfficeTheme.space(row, 6, 6, 8, 8);
      row.add(new Label(c.author + (c.resolved ? " \u00b7 Resolved" : "")));
      row.add(new SpanLabelCompat(c.text));
      for (int replyIndex = 0; replyIndex < c.replies.size(); replyIndex++) {
        final int index = replyIndex;
        var reply = c.replies.get(index);
        var editReply = button("Edit reply", () -> editComment(dialog, c, index));
        editReply.setAccessibilityText("Edit reply " + (index + 1) + " by " + reply.author);
        Container replyView =
            BoxLayout.encloseY(new Label(reply.author), new SpanLabelCompat(reply.text), editReply);
        OfficeTheme.space(replyView, 4, 4, 16, 0);
        row.add(replyView);
      }
      row.add(
          FlowLayout.encloseIn(
              button("Edit", () -> editComment(dialog, c, -1)),
              button(
                  "Reply",
                  () -> {
                    EditField author = field("Author"), text = field("", 4, 28);
                    if (showDialog(
                        "Reply to comment",
                        BoxLayout.encloseY(formRow("Author", author), formRow("Reply", text)),
                        "Reply",
                        "Cancel")) {
                      session.replyComment(c.id, author.getText(), text.getText());
                      dialog.dispose();
                      edited();
                      comments();
                    }
                  }),
              button(
                  "Go to",
                  () -> {
                    session.selectComment(c.id);
                    dialog.dispose();
                    editor.revealSelection();
                    editor.startInput();
                  }),
              button(
                  c.resolved ? "Reopen" : "Resolve",
                  () -> {
                    session.updateComment(c.id, !c.resolved, false);
                    dialog.dispose();
                    edited();
                    comments();
                  }),
              button(
                  "Delete",
                  () -> {
                    session.updateComment(c.id, false, true);
                    dialog.dispose();
                    edited();
                    comments();
                  })));
      list.add(row);
    }
    if (list.getComponentCount() == 0) list.add(new Label("No comments"));
    dialog.add(BorderLayout.CENTER, list);
    dialog.add(BorderLayout.SOUTH, button("Close", dialog::dispose));
    styleDialogBody(dialog.getContentPane());
    showOfficeDialog(dialog);
  }

  private Container view() {
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Views",
            BoxLayout.encloseY(
                button("Print layout", () -> run("view.printLayout", new HashMap<String, Object>())),
                button("Web layout", () -> run("view.webLayout", new HashMap<String, Object>())))),
        OfficeTheme.group(
            "Show",
            BoxLayout.encloseY(
                button("Formatting marks", () -> run("view.marks", new HashMap<String, Object>())),
                button(
                    "Navigation pane",
                    () -> {
                      if (navigation.getParent() != null) workspace.removeComponent(navigation);
                      else workspace.add(BorderLayout.WEST, navigation);
                      workspace.revalidate();
                    }),
                button(
                    "Ruler",
                    () -> showRulers(ruler.isHidden())))),
        OfficeTheme.group(
            "Zoom",
            OfficeTheme.row(
                iconButton(
                    "100%",
                    FontImage.MATERIAL_ZOOM_IN,
                    () -> {
                      editor.setZoom(1);
                      updateStatus();
                      ruler.repaint();
                    },
                    true),
                iconButton(
                    "Page width",
                    FontImage.MATERIAL_FIT_SCREEN,
                    () -> {
                      configureMobileViewport();
                      editor.fitWidth();
                      updateStatus();
                      ruler.repaint();
                    },
                    true),
                iconButton(
                    "One page",
                    FontImage.MATERIAL_DESCRIPTION,
                    () -> run("view.onePage", new HashMap<String, Object>()),
                    true),
                iconButton(
                    "Multiple pages",
                    FontImage.MATERIAL_AUTO_STORIES,
                    () -> run("view.multiplePages", new HashMap<String, Object>()),
                    true))));
  }

  /** View tab catalog ids; they change only the window, never the document. */
  private void registerViewCommands() {
    view("view.zoom", "Zoom to a percentage (35-250)", "percent:number", a -> zoomTo(((Number) a.get("percent")).floatValue() / 100f));
    view("view.zoomIn", "Zoom in by 10%", "", a -> zoomTo(Math.round(editor.getZoom() * 10 + 1) / 10f));
    view("view.zoomOut", "Zoom out by 10%", "", a -> zoomTo(Math.round(editor.getZoom() * 10 - 1) / 10f));
    view("view.zoom100", "Zoom to 100%", "", a -> {
      editor.setPagesAcross(1);
      zoomTo(1);
    });
    view("view.pageWidth", "Zoom to the page width", "", a -> {
      editor.fitWidth();
      zoomTo(editor.getZoom());
    });
    view("view.onePage", "Fit one whole page", "", a -> {
      editor.fitPages(1);
      zoomTo(editor.getZoom());
    });
    view("view.multiplePages", "Show pages side by side (default 2)", "count?:integer", a -> {
      editor.fitPages(Commands.integer(a, "count", 2));
      zoomTo(editor.getZoom());
    });
    view("view.marks", "Show or hide formatting marks", "value?:boolean", a ->
        editor.setShowMarks(Commands.flag(a, "value", !editor.isShowMarks())));
    view("view.ruler", "Show or hide the ruler", "value?:boolean", a -> {
      showRulers(Commands.flag(a, "value", ruler.isHidden()));
    });
    view("view.navigationPane", "Show or hide the navigation pane", "value?:boolean", a -> {
      boolean shown = navigation.getParent() != null;
      boolean show = Commands.flag(a, "value", !shown);
      if (show && !shown) workspace.add(BorderLayout.WEST, navigation);
      else if (!show && shown) workspace.removeComponent(navigation);
      workspace.revalidate();
    });
    view("view.printLayout", "Paginated print layout", "", a -> {
      if (editor.isReflow()) editor.setReflow(false);
      showRulers(!ruler.isHidden());
      zoomTo(editor.getZoom());
    });
    view("view.webLayout", "Reflow the document to the window width", "", a -> {
      if (!editor.isReflow()) editor.setReflow(true);
      showRulers(!ruler.isHidden());
      zoomTo(editor.getZoom());
    });
    view("view.draft", "Draft: reflowed text without headers, footers or page decoration", "value?:boolean", a -> {
      boolean on = Commands.flag(a, "value", !editor.isDraft());
      editor.setDraft(on);
      if (editor.isReflow() != on) editor.setReflow(on);
      zoomTo(editor.getZoom());
    });
    view("view.darkMode", "Switch the page between light and dark", "value?:boolean", a -> {
      darkPageOverride = Boolean.valueOf(Commands.flag(a, "value", !editor.isDarkPage()));
      applyDarkPage();
    });
    view("view.gridlines", "Show or hide 18pt gridlines", "value?:boolean", a ->
        editor.setGridlines(Commands.flag(a, "value", !editor.isGridlines())));
    view("table.viewGridlines", "Show or hide dotted table gridlines on borderless cell edges", "value?:boolean", a ->
        editor.setTableGridlines(Commands.flag(a, "value", !editor.isTableGridlines())));
    view("view.outline", "Outline view: paragraphs indented by level with expand/collapse", "value?:boolean", a ->
        outlineView(Commands.flag(a, "value", editor.getOutline() == null)));
    view("outline.close", "Close Outline view", "", a -> outlineView(false));
    view("outline.expand", "Expand the heading at the caret, or all headings", "all?:boolean", a ->
        outlineCollapse(Boolean.TRUE.equals(a.get("all")), false));
    view("outline.collapse", "Collapse the heading at the caret, or all headings", "all?:boolean", a ->
        outlineCollapse(Boolean.TRUE.equals(a.get("all")), true));
    view("outline.showLevel", "Show outline levels 1-9, or all (level 10)", "level:integer", a -> {
      outlineView(true);
      editor.setOutline(editor.getOutline().showLevel(((Number) a.get("level")).intValue()));
    });
    view("outline.showFormatting", "Show text formatting in Outline view", "value?:boolean", a -> {
      outlineView(true);
      var o = editor.getOutline();
      editor.setOutline(o.showFormatting(Commands.flag(a, "value", !o.showFormatting)));
    });
    view("outline.firstLineOnly", "Show only the first line of body paragraphs", "value?:boolean", a -> {
      outlineView(true);
      var o = editor.getOutline();
      editor.setOutline(o.firstLineOnly(Commands.flag(a, "value", !o.firstLineOnly)));
    });
    view("view.focus", "Focus mode: hide the ribbon and status bar", "value?:boolean", a ->
        focus(Commands.flag(a, "value", !ribbon.isHidden())));
    view("view.readMode", "Read mode: full screen, pages side by side, no editing", "value?:boolean", a -> {
      boolean on = Commands.flag(a, "value", !editor.isViewOnly());
      editor.setViewOnly(on);
      focus(on);
      editor.fitPages(on ? 2 : 1);
      zoomTo(editor.getZoom());
    });
    view("view.immersive", "Immersive reader: focus mode with reflowed, enlarged text", "value?:boolean", a -> {
      boolean on = Commands.flag(a, "value", !(ribbon.isHidden() && editor.isReflow()));
      focus(on);
      editor.setReflow(on);
      zoomTo(on ? Math.max(1.5f, editor.getZoom()) : 1);
    });
    view("view.sideToSide", "Lay pages out horizontally", "", a -> {
      editor.setPagesAcross(Math.max(1, editor.layout().pages.size()));
      zoomTo(editor.getZoom());
    });
    view("review.markup", "Markup display: all, simple, none (final) or original", "mode?:string", a ->
        editor.setMarkup(a.containsKey("mode") ? Commands.str(a, "mode") : "all".equals(editor.getMarkup()) ? "simple" : "all"));
    view("review.showMarkup", "Show or hide revision markup", "value?:boolean", a ->
        editor.setMarkup(
            (Commands.flag(a, "value", !"all".equals(editor.getMarkup()))) ? "all" : "none"));
    view("insert.editHeader", "Edit the page header", "", a -> Display.getInstance().callSerially(() -> editFurniture(false)));
    view("insert.editFooter", "Edit the page footer", "", a -> Display.getInstance().callSerially(() -> editFurniture(true)));
    view("insert.closeHeader", "Close Header and Footer: keep the edits and return to the document body", "", a -> {
      if (acceptOpenDialog == null || !editingFurniture) throw new IllegalStateException("No header or footer is being edited");
      Display.getInstance().callSerially(acceptOpenDialog);
    });
    view("view.newWindow", "New Window: open (or close) a second synchronized view of this document", "", a -> toggleSecondView(BorderLayout.EAST));
    view("view.split", "Split: show (or remove) a second pane of this document below the first", "", a -> toggleSecondView(BorderLayout.SOUTH));
    // Word ids for actions that are otherwise only ribbon, backstage or menu items.
    view("file.new", "New blank document", "", a -> newDocument());
    view("file.open", "Open a document with the platform file picker", "", a -> open());
    view("file.save", "Save the document", "", a -> saveDocument(false));
    view("file.saveAs", "Save As with the platform file picker", "", a -> saveDocument(true));
    view("file.print", "Print through the platform print dialog", "", a -> printDocument());
    view("file.close", "Close the document (asking to save changes) and start a blank one", "", a -> newDocument());
    view("edit.formatPainter", "Format Painter: copy the formatting at the selection, apply to the next selection", "", a -> togglePainter());
    view("edit.find", "Find and Replace pane", "", a -> find());
    view("edit.replace", "Find and Replace pane", "", a -> find());
    view("insert.picture", "Insert a picture with the platform file picker", "", a -> insertPicture());
    view("insert.crossReference", "Cross-reference dialog", "", a -> crossReferenceDialog());
    view("tools.macros", "Macros: record, preview and run command macros", "", a -> macroDialog());
    view("para.tabs", "Tabs dialog for the selected paragraphs", "", a -> {
      final java.util.List<TabStop>[] tabs = new java.util.List[] {session.document().paragraph(session.selection().block).format.tabs};
      editTabs(tabs);
      paragraph("tabs", TabStop.encode(tabs[0]));
    });
    commands.query("file.exportPng", "page?:integer,width?:integer",
        "Export the current page (or page N, 1-based) as a PNG image; returns base64", (x, a) -> {
      int page = a.containsKey("page") ? ((Number) a.get("page")).intValue() - 1 : currentPage();
      int width = a.containsKey("width") ? ((Number) a.get("width")).intValue() : 1240;
      if (width < 64 || width > 6000) throw new IllegalArgumentException("Width is 64-6000 pixels");
      var image = editor.renderPage(page, width);
      var out = new ByteArrayOutputStream();
      try {
        com.codename1.ui.util.ImageIO.getImageIO().save(image, out, com.codename1.ui.util.ImageIO.FORMAT_PNG, 1);
      } catch (IOException ex) {
        throw new IllegalStateException("PNG export failed: " + ex.getMessage(), ex);
      }
      return Json.object("page", page + 1, "width", image.getWidth(), "height", image.getHeight(), "bytes", out.size(),
          "base64", new String(com.codename1.util.Base64.encodeNoNewline(out.toByteArray())));
    });
    view("tools.customizeRibbon", "Customize Ribbon: hide or show a tab, add or remove a command on the Custom tab, or reset", "hide?:string,show?:string,add?:string,remove?:string,reset?:boolean", a -> {
      java.util.List<String> hidden = optionList("ribbon.hidden"), custom = optionList("ribbon.custom");
      if (Boolean.TRUE.equals(a.get("reset"))) {
        hidden.clear();
        custom.clear();
      }
      if (a.containsKey("hide")) {
        var t = (String) a.get("hide");
        if (!ribbon.titles().contains(t) || "Custom".equals(t)) throw new IllegalArgumentException("No ribbon tab " + t);
        if (!hidden.contains(t)) hidden.add(t);
      }
      if (a.containsKey("show")) hidden.remove(a.get("show"));
      if (a.containsKey("add")) {
        var id = (String) a.get("add");
        if (!commandIds().contains(id)) throw new IllegalArgumentException("Unknown command " + id);
        if (!custom.contains(id)) custom.add(id);
      }
      if (a.containsKey("remove") && !custom.remove(a.get("remove"))) throw new IllegalArgumentException("Not on the Custom tab: " + a.get("remove"));
      AppOptions.set("ribbon.hidden", joinComma(hidden));
      AppOptions.set("ribbon.custom", joinComma(custom));
      applyRibbonOptions();
    });
    view("tools.customizeKeyboard", "Customize Keyboard: assign a shortcut (e.g. ctrl+shift+K) to a command, remove one or list them", "keys?:string,command?:string,remove?:boolean", a -> {
      var bindings = optionList("keys.custom");
      if (a.containsKey("keys")) {
        String keys = normalizeKeys((String) a.get("keys"));
        for (int i = bindings.size() - 1; i >= 0; i--) if (bindings.get(i).startsWith(keys + "=")) bindings.remove(i);
        if (!Boolean.TRUE.equals(a.get("remove"))) {
          var id = (String) a.get("command");
          if (id == null || !commandIds().contains(id)) throw new IllegalArgumentException("Unknown command " + id);
          bindings.add(keys + "=" + id);
        }
        AppOptions.set("keys.custom", joinComma(bindings));
        installCustomKeys();
      } else {
        Display.getInstance().callSerially(this::customizeKeyboardDialog);
      }
    });
    view("view.vertical", "Lay pages out vertically", "", a -> {
      editor.setPagesAcross(1);
      zoomTo(editor.getZoom());
    });
  }

  /** Accepts the dialog currently shown by showDialog (Close Header and Footer). */
  private Runnable acceptOpenDialog;

  private boolean editingFurniture;

  private EditorSurface secondView;

  private Container secondPane;

  private void toggleSecondView(String side) {
    var documentPane = editor.getParent();
    if (secondView != null) {
      secondPane.remove();
      secondView = null;
      secondPane = null;
    } else {
      secondView = new EditorSurface(session, commands, this::edited, this::editCell);
      secondView.setZoom(editor.getZoom());
      secondPane = new Container(new BorderLayout());
      Label title = OfficeTheme.label(session.document().title + ":2", 11, 0x555555);
      secondPane.add(BorderLayout.NORTH, title);
      secondPane.add(BorderLayout.CENTER, secondView);
      secondPane.setPreferredW(Math.max(OfficeTheme.px(240), documentPane.getWidth() / 2));
      if (BorderLayout.SOUTH.equals(side))
        secondPane.setPreferredH(Math.max(OfficeTheme.px(200), documentPane.getHeight() / 2));
      documentPane.add(side, secondPane);
    }
    form.revalidate();
  }

  private int currentPage() {
    var r = editor.layout();
    var x = session.selection();
    for (int p = 0; p < r.pages.size(); p++)
      for (LayoutEngine.Line line : r.pages.get(p).lines)
        if (line.block == x.block && x.caret >= line.start && x.caret <= line.end) return p;
    return 0;
  }

  private static java.util.List<String> optionList(String key) {
    var out = new ArrayList<String>();
    for (String v : TextUtil.split(AppOptions.get(key), ',')) if (!v.isEmpty()) out.add(v);
    return out;
  }

  private static String joinComma(java.util.List<String> values) {
    var b = new StringBuilder();
    for (String v : values) b.append(b.length() == 0 ? "" : ",").append(v);
    return b.toString();
  }

  /** Customize Keyboard dialog: current assignments plus a shortcut and the command it runs. */
  private void customizeKeyboardDialog() {
    Container body = BoxLayout.encloseY();
    var bindings = optionList("keys.custom");
    body.add(new Label(bindings.isEmpty() ? "No custom shortcuts" : "Current shortcuts"));
    for (String b : bindings) body.add(new Label(b.replace("=", "  ->  ")));
    var keys = new TextField("", "Press new shortcut, e.g. ctrl+shift+K", 24, TextField.ANY);
    var command = new TextField("", "Command, e.g. format.bold", 24, TextField.ANY);
    keys.setAccessibilityText("Shortcut");
    command.setAccessibilityText("Command");
    var remove = new com.codename1.ui.CheckBox("Remove this shortcut");
    body.add(new Label("Shortcut")).add(keys).add(new Label("Command")).add(command).add(remove);
    if (!showDialog("Customize Keyboard", body, "Assign", "Close") || keys.getText().trim().isEmpty()) return;
    try {
      commands.execute(session, "tools.customizeKeyboard", Json.object(
          "keys", keys.getText().trim(), "command", command.getText().trim(), "remove", remove.isSelected()));
      status.setText(remove.isSelected() ? "Shortcut removed" : "Shortcut assigned");
    } catch (IllegalArgumentException ex) {
      showDialog("Customize Keyboard", ex.getMessage(), "OK", null);
    }
  }

  private java.util.List<String> commandIds() {
    var out = new ArrayList<String>();
    for (Commands.Descriptor d : commands.descriptors()) out.add(d.id);
    return out;
  }

  private Container customTab;

  private void applyRibbonOptions() {
    var hidden = optionList("ribbon.hidden");
    for (String t : ribbon.titles())
      if (!"Table Layout".equals(t) && !"Custom".equals(t)) ribbon.setTabVisible(t, !hidden.contains(t));
    customTab.removeAll();
    var custom = optionList("ribbon.custom");
    for (final String id : custom) {
      var label = id.substring(id.indexOf('.') + 1);
      customTab.add(button(Character.toUpperCase(label.charAt(0)) + label.substring(1), () -> run(id, new LinkedHashMap<String, Object>())));
    }
    ribbon.setTabVisible("Custom", !custom.isEmpty());
    ribbon.revalidate();
  }

  static String normalizeKeys(String keys) {
    String[] parts = TextUtil.split(keys.trim().toLowerCase(), '+');
    boolean ctrl = false, shift = false, alt = false;
    String key = null;
    for (String p : parts) {
      if ("ctrl".equals(p) || "cmd".equals(p) || "control".equals(p) || "command".equals(p)) ctrl = true;
      else if ("shift".equals(p)) shift = true;
      else if ("alt".equals(p) || "option".equals(p)) alt = true;
      else if (p.length() == 1 && key == null) key = p;
      else throw new IllegalArgumentException("Shortcut is modifiers plus one key, e.g. ctrl+shift+K");
    }
    if (key == null || !ctrl && !alt) throw new IllegalArgumentException("Shortcuts need ctrl/cmd or alt and one key");
    return (ctrl ? "ctrl+" : "") + (alt ? "alt+" : "") + (shift ? "shift+" : "") + key;
  }

  private final java.util.List<String> installedKeys = new ArrayList<>();

  /** Keyboard shortcuts from Customize Keyboard live in Tools > Custom Shortcuts. */
  private void installCustomKeys() {
    var bindings = optionList("keys.custom");
    if (bindings.equals(installedKeys)) return;
    installedKeys.clear();
    installedKeys.addAll(bindings);
    if (menus != null) installMenu();
  }

  private void customShortcuts(AppMenus m) {
    if (installedKeys.isEmpty()) return;
    m.submenu("Custom Shortcuts");
    for (String binding : installedKeys) {
      int eq = binding.indexOf('=');
      var keys = binding.substring(0, eq);
      final var id = binding.substring(eq + 1);
      int mods = (keys.indexOf("ctrl+") >= 0 ? AppMenus.CMD : 0)
          | (keys.indexOf("shift+") >= 0 ? AppMenus.SHIFT : 0)
          | (keys.indexOf("alt+") >= 0 ? AppMenus.ALT : 0);
      m.item(id, keys.charAt(keys.length() - 1), mods, menuAction(keys + "  " + id, () -> {
        // A later reassignment or removal disables this binding.
        if (optionList("keys.custom").contains(binding)) run(id, new LinkedHashMap<>());
      }));
    }
    m.end();
  }

  /** Line- and screen-relative caret movement needs the editor's layout, so it lives here. */
  private void registerCaretCommands() {
    caretKey("caret.home", "Move to the start of the visual line", EditorSurface.KEY_HOME);
    caretKey("caret.end", "Move to the end of the visual line", EditorSurface.KEY_END);
    caretKey("caret.pageUp", "Move up one screen", EditorSurface.KEY_PAGE_UP);
    caretKey("caret.pageDown", "Move down one screen", EditorSurface.KEY_PAGE_DOWN);
  }

  private void caretKey(String id, String description, final int key) {
    commands.register(
        id,
        description,
        "extend?:boolean",
        false,
        (s, a) -> {
          editor.onKeyCommand(
              key, Boolean.TRUE.equals(a.get("extend")) ? EditorSurface.MOD_SHIFT : 0);
          var x = s.selection();
          return Json.object("block", x.block, "caret", x.caret, "anchor", x.anchor);
        });
  }

  private interface ViewAction {
    void run(Map<String, Object> args);
  }

  private void view(String id, String description, String spec, ViewAction action) {
    commands.register(
        id,
        description,
        spec,
        false,
        (s, a) -> {
          action.run(a);
          return Json.object(
              "zoom", Math.round(editor.getZoom() * 100),
              "pagesAcross", editor.getPagesAcross(),
              "reflow", editor.isReflow(),
              "marks", editor.isShowMarks(),
              "dark", editor.isDarkPage(),
              "draft", editor.isDraft(),
              "gridlines", editor.isGridlines(),
              "readMode", editor.isViewOnly(),
              "markup", editor.getMarkup(),
              "tableGridlines", editor.isTableGridlines(),
              "outline", editor.getOutline() != null,
              "painter", paintStyle != null,
              "focus", ribbon.isHidden());
        });
  }

  private void focus(boolean on) {
    ribbon.setHidden(on);
    footer.setHidden(on);
    form.revalidate();
  }

  private void zoomTo(float zoom) {
    editor.setZoom(zoom);
    updateStatus();
    ruler.repaint();
  }

  /** The Quick Access "more" menu: the commands Word offers there. */
  private void quickAccessMore() {
    String[] labels = {"New", "Open", "Print", "Customize Ribbon"};
    String[] ids = {"file.new", "file.open", "file.print", "tools.customizeRibbon"};
    var pick = new ComboBox<String>(labels);
    if (showDialog("Quick Access", pick, "Run", "Cancel")) runPrompted(ids[pick.getSelectedIndex()]);
  }

  /** Office Clipboard: the last copies, newest first; pick one to paste it, or clear them. */
  private void officeClipboard() {
    var listed = commands.execute(session, "edit.clipboardPane", Json.object("action", "list"));
    java.util.List<?> items = listed instanceof java.util.List<?> l ? l : new ArrayList<Object>();
    if (items.isEmpty()) {
      showDialog("Clipboard", new Label("Copy or cut to collect items here."), "OK", null);
      return;
    }
    String[] labels = new String[items.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = String.valueOf(items.get(i)).replace('\n', ' ');
    var pick = new ComboBox<String>(labels);
    var clear = new CheckBox("Clear all instead");
    if (!showDialog("Clipboard", BoxLayout.encloseY(pick, clear), "Paste", "Cancel")) return;
    if (clear.isSelected()) run("edit.clipboardPane", Json.object("action", "clear"));
    else run("edit.clipboardPane", Json.object("action", "paste", "index", pick.getSelectedIndex()));
  }

  private Button editingButton;

  /** Word's mode switcher: Editing, Reviewing (Track Changes on) or Viewing (read only). */
  private void editingMode() {
    var mode = new ComboBox<String>("Editing", "Reviewing", "Viewing");
    mode.setSelectedIndex(editor.isViewOnly() ? 2 : session.document().trackChanges ? 1 : 0);
    if (!showDialog("Mode", mode, "Apply", "Cancel")) return;
    int m = mode.getSelectedIndex();
    editor.finishComposing();
    if (session.document().trackChanges != (m == 1)) session.trackChanges(m == 1);
    editor.setViewOnly(m == 2);
    if (!OfficeTheme.mobile()) editingButton.setText(mode.getSelectedItem() + " \u25be");
    edited();
  }

  /** Share sheet with the .docx; where the platform has none, offer Save As instead. */
  private void share() {
    if (!Display.getInstance().isNativeShareSupported()) {
      saveDocument(true);
      return;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> r =
          (Map<String, Object>) commands.execute(session, "file.share", new HashMap<>());
      com.codename1.io.FileSystemStorage fs = com.codename1.io.FileSystemStorage.getInstance();
      var path = fs.getAppHomePath() + String.valueOf(r.get("name"));
      try (var out = fs.openOutputStream(path)) {
        out.write(com.codename1.util.Base64.decode(String.valueOf(r.get("base64")).getBytes("UTF-8")));
      }
      Display.getInstance().share(null, path, String.valueOf(r.get("mime")));
    } catch (java.io.IOException ex) {
      status.setText("Share failed: " + ex.getMessage());
    }
  }

  /** Ribbon button for a registered command; required arguments are asked for as Search does. */
  private Button command(String label, char icon, String id, boolean large) {
    return iconButton(label, icon, () -> runPrompted(id), large);
  }

  private void runPrompted(String id) {
    for (Commands.Descriptor d : commands.descriptors())
      if (d.id.equals(id)) {
        promptArgs(d, new ArrayList<>(d.parameters.keySet()), 0, new LinkedHashMap<>());
        return;
      }
  }

  /** Drop-down button: pick one of the names, then run the command with it as "name". */
  private Button choices(String label, char icon, String id, java.util.List<String> names) {
    return iconButton(
        label + " \u25be",
        icon,
        () -> {
          var pick = new ComboBox<String>(names.toArray(new String[0]));
          if (showDialog(label, pick, "Apply", "Cancel"))
            run(id, Json.object("name", pick.getSelectedItem()));
        },
        true);
  }

  private static java.util.List<String> names(Object[][] table) {
    var out = new ArrayList<String>();
    for (Object[] row : table) out.add(String.valueOf(row[0]));
    return out;
  }

  private final java.util.List<Button> drawTools = new ArrayList<>();
  private String drawTool = "";

  private Container draw() {
    drawTools.clear();
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Drawing Tools",
            OfficeTheme.row(
                drawTool("Select", FontImage.MATERIAL_NEAR_ME, ""),
                drawTool("Pen", FontImage.MATERIAL_EDIT, "pen"),
                drawTool("Pencil", FontImage.MATERIAL_CREATE, "pencil"),
                drawTool("Highlighter", FontImage.MATERIAL_BORDER_COLOR, "highlighter"),
                drawTool("Eraser", FontImage.MATERIAL_AUTO_FIX_NORMAL, "eraser"),
                command("Add Pen", FontImage.MATERIAL_ADD, "draw.addPen", true))),
        OfficeTheme.group(
            "Insert",
            OfficeTheme.row(
                iconButton("Drawing Canvas", FontImage.MATERIAL_CROP_FREE, () -> run("insert.canvas", new HashMap<String, Object>()), true))),
        OfficeTheme.group(
            "Convert",
            OfficeTheme.row(
                iconButton("Ink to Shape", FontImage.MATERIAL_CATEGORY, () -> run("draw.inkToShape", new HashMap<String, Object>()), true))),
        OfficeTheme.group(
            "Replay",
            OfficeTheme.row(
                iconButton("Ink Replay", FontImage.MATERIAL_REPLAY, () -> run("draw.replay", new HashMap<String, Object>()), true))));
  }

  private Button drawTool(String label, char icon, String tool) {
    var b = iconButton(label, icon, () -> selectDrawTool(tool), true);
    b.putClientProperty("drawTool", tool);
    drawTools.add(b);
    OfficeTheme.checked(b, tool.isEmpty());
    return b;
  }

  /**
   * Draw mode. A pen tool turns pointer drags over an ink object or drawing canvas into strokes
   * (one undo step each); if the caret is not at ink, a drawing canvas is inserted first, as
   * Word starts an ink region. Select leaves draw mode.
   */
  private void selectDrawTool(String tool) {
    drawTool = tool;
    for (Button b : drawTools) OfficeTheme.checked(b, tool.equals(b.getClientProperty("drawTool")));
    if (tool.isEmpty()) {
      editor.setInk(null, 0);
      status.setText("");
      return;
    }
    if (!inkAtCaret()) run("insert.canvas", new HashMap<>());
    editor.setInk(this::inkStroke, "highlighter".equals(tool) ? 0xffd800 : "eraser".equals(tool) ? 0x9e9e9e : 0x000000);
    status.setText("eraser".equals(tool) ? "Drag over ink to erase" : "Draw inside the canvas");
    editor.repaint();
  }

  private boolean inkAtCaret() {
    var d = session.document();
    int b = session.selection().block;
    for (int i = Math.min(b, d.blocks.size() - 1); i >= 0 && i >= b - 2; i--) {
      var block = d.blocks.get(i);
      if (block instanceof Picture picture && picture.drawing != null
          && ("ink".equals(picture.drawing.kind) || "canvas".equals(picture.drawing.kind)))
        return true;
    }
    return false;
  }

  private void inkStroke(int block, String points) {
    if ("eraser".equals(drawTool)) {
      safe(() -> {
        session.transact(t -> {
          for (String pt : com.codename1.util.StringUtil.tokenize(points, ' ')) {
            var xy = com.codename1.util.StringUtil.tokenize(pt, ',');
            if (xy.size() < 2) continue;
            try {
              commands.execute(t, "draw.eraser", Json.object("block", block,
                  "x", Double.valueOf(xy.get(0)), "y", Double.valueOf(xy.get(1))));
            } catch (IllegalArgumentException nothingNear) {
              // no stroke under this point
            }
          }
        });
        edited();
      });
      return;
    }
    run("draw." + drawTool, Json.object("block", block, "points", points));
  }

  private Container design() {
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Document Formatting",
            OfficeTheme.row(
                choices("Themes", FontImage.MATERIAL_PALETTE, "design.theme", names(LayoutDesignCommands.THEMES)),
                choices("Style Set", FontImage.MATERIAL_STYLE, "design.styleSet", LayoutDesignCommands.STYLE_SETS),
                BoxLayout.encloseY(
                    choices("Colors", FontImage.MATERIAL_COLOR_LENS, "design.themeColors", names(LayoutDesignCommands.COLORS)),
                    choices("Fonts", FontImage.MATERIAL_FONT_DOWNLOAD, "design.themeFonts", names(LayoutDesignCommands.FONTS))),
                BoxLayout.encloseY(
                    choices("Paragraph Spacing", FontImage.MATERIAL_FORMAT_LINE_SPACING, "design.paragraphSpacing", names(LayoutDesignCommands.SPACING)),
                    choices("Effects", FontImage.MATERIAL_AUTO_AWESOME, "design.effects", LayoutDesignCommands.EFFECTS)),
                command("Set as Default", FontImage.MATERIAL_CHECK, "design.setDefault", false))),
        OfficeTheme.group(
            "Page Background",
            OfficeTheme.row(
                command("Watermark", FontImage.MATERIAL_BRANDING_WATERMARK, "design.watermark", true),
                command("Page Color", FontImage.MATERIAL_FORMAT_COLOR_FILL, "design.pageColor", true),
                command("Page Borders", FontImage.MATERIAL_BORDER_OUTER, "design.pageBorders", true))));
  }

  private Container developer() {
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Code",
            OfficeTheme.row(
                iconButton("Macros", FontImage.MATERIAL_PLAY_ARROW, this::macroDialog, true))),
        OfficeTheme.group(
            "Controls",
            OfficeTheme.row(
                BoxLayout.encloseY(
                    command("Rich Text", FontImage.MATERIAL_TEXT_FIELDS, "developer.richText", false),
                    command("Plain Text", FontImage.MATERIAL_SHORT_TEXT, "developer.plainText", false),
                    command("Picture", FontImage.MATERIAL_IMAGE, "developer.picture", false)),
                BoxLayout.encloseY(
                    command("Check Box", FontImage.MATERIAL_CHECK_BOX, "developer.checkBox", false),
                    command("Combo Box", FontImage.MATERIAL_ARROW_DROP_DOWN_CIRCLE, "developer.comboBox", false),
                    command("Drop-Down List", FontImage.MATERIAL_LIST, "developer.dropDown", false)),
                BoxLayout.encloseY(
                    command("Date Picker", FontImage.MATERIAL_EVENT, "developer.datePicker", false),
                    command("Building Block", FontImage.MATERIAL_VIEW_MODULE, "developer.buildingBlock", false),
                    command("Repeating Section", FontImage.MATERIAL_REPEAT, "developer.repeatingSection", false)),
                BoxLayout.encloseY(
                    command("Design Mode", FontImage.MATERIAL_DESIGN_SERVICES, "developer.designMode", false),
                    command("Properties", FontImage.MATERIAL_TUNE, "developer.properties", false)))));
  }

  private Container help() {
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Help",
            OfficeTheme.row(
                iconButton("Help", FontImage.MATERIAL_HELP_OUTLINE, this::searchCommands, true),
                iconButton("Keyboard Shortcuts", FontImage.MATERIAL_KEYBOARD, this::customizeKeyboardDialog, true))));
  }

  private Container automation() {
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Macros",
            OfficeTheme.row(
                iconButton("Commands", FontImage.MATERIAL_TERMINAL, this::commandDialog, true),
                iconButton(
                    "Preview macro", FontImage.MATERIAL_PLAY_ARROW, this::macroDialog, true))),
        OfficeTheme.group(
            "Templates",
            OfficeTheme.row(
                iconButton(
                    "Fill template",
                    FontImage.MATERIAL_DESCRIPTION,
                    () -> {
                      EditField key = field("name"), value = field("Ada");
                      if (showDialog(
                          "Fill {{field}}",
                          BoxLayout.encloseY(new Label("Field"), key, new Label("Value"), value),
                          "Preview",
                          "Cancel"))
                        preview(
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
                                            "values",
                                            Json.object(key.getText(), value.getText()))))));
                    },
                    true))));
  }

  private void showBackstage() {
    editor.finishComposing();
    editor.stopInput();
    backstage = true;
    var panel = new Container(new BorderLayout());
    var rail = new Container(OfficeTheme.mobile() ? BoxLayout.x() : BoxLayout.y());
    rail.setScrollableX(OfficeTheme.mobile());
    OfficeTheme.surface(rail, OfficeTheme.STRIP);
    OfficeTheme.space(rail, 12, 12, 8, 8);
    if (!OfficeTheme.mobile()) {
      rail.setPreferredW(OfficeTheme.px(190));
      rail.getAllStyles().setBorder(
          Border.createCompoundBorder(null, null, null, Border.createLineBorder(1, OfficeTheme.BORDER)));
    }
    var page = new Container(new BorderLayout());
    OfficeTheme.surface(page, OfficeTheme.PAPER);
    OfficeTheme.space(page, 12, 12, OfficeTheme.mobile() ? 12 : 32, 12);
    page.setScrollableY(true);
    String[] labels = {
      "\u2039 Back", "New", "Open", "Info", "Save", "Save As", "Print", "Export"
    };
    var sections = new ArrayList<Button>();
    for (String label : labels) {
      final Button[] self = new Button[1];
      var item =
          button(
              label,
              () -> {
                if (label.startsWith("\u2039")) {
                  closeBackstage();
                  return;
                }
                if ("Open".equals(label)) {
                  closeBackstage();
                  open();
                  return;
                }
                if ("Print".equals(label)) {
                  closeBackstage();
                  printDocument();
                  return;
                }
                if ("Save As".equals(label)) {
                  closeBackstage();
                  saveDocument(true);
                  return;
                }
                if ("Save".equals(label)) {
                  closeBackstage();
                  saveDocument(false);
                  return;
                }
                for (Button b : sections) OfficeTheme.checked(b, b == self[0]);
                backstagePage(page, label);
                OfficeTheme.applyAppearance(rail);
              });
      self[0] = item;
      sections.add(item);
      OfficeTheme.space(item, 9, 9, 12, 12);
      item.getAllStyles().setFont(OfficeTheme.font(14, "New".equals(label)));
      item.setAlignment(Component.LEFT);
      if ("New".equals(label)) OfficeTheme.checked(item, true);
      rail.add(item);
    }
    panel.add(OfficeTheme.mobile() ? BorderLayout.NORTH : BorderLayout.WEST, rail);
    panel.add(BorderLayout.CENTER, page);
    backstagePage(page, "New");
    // Colored for the active appearance before it is attached: the first frame is final.
    OfficeTheme.applyAppearance(panel);
    form.removeAll();
    form.add(BorderLayout.CENTER, panel);
    form.revalidate();
  }

  private void closeBackstage() {
    if (!backstage) return;
    backstage = false;
    form.removeAll();
    form.add(BorderLayout.NORTH, ribbon);
    form.add(BorderLayout.CENTER, workspace);
    form.add(BorderLayout.SOUTH, footer);
    form.revalidate();
    editor.startInput();
  }

  private void backstagePage(Container page, String section) {
    page.removeAll();
    Label title = OfficeTheme.label(section, 28, OfficeTheme.INK);
    OfficeTheme.space(title, 0, 24, 0, 0);
    page.add(BorderLayout.NORTH, title);
    var content = new Container(BoxLayout.y());
    content.setScrollableY(true);
    if ("New".equals(section)) {
      var choices = new Container(new FlowLayout(Component.LEFT));
      for (String name : new String[] {"Blank document", "Writing studio"}) {
        var preview =
            button(
                "Blank document".equals(name)
                    ? ""
                    : """
                        The writing studio
                        
                        A better place for words.
                        
                        Your ideas, beautifully structured""",
                () -> {
                  replaceAfterSaving(
                      () -> {
                        forgetFile();
                        session.loadDocument(
                            "Blank document".equals(name)
                                ? Document.empty().title("Document1")
                                : sample());
                        fileDocument = session.document();
                        source = null;
                        closeBackstage();
                        editor.resetScroll();
                        edited();
                      });
                });
        preview.setText("");
        preview.setIcon(templatePreview("Blank document".equals(name)));
        preview.setAccessibilityText("Create " + name);
        preview.setPreferredSize(
            new com.codename1.ui.geom.Dimension(OfficeTheme.px(150), OfficeTheme.px(190)));
        OfficeTheme.surface(preview, 0xffffff);
        preview.getAllStyles().setBorder(Border.createLineBorder(1, 0xc4c4c4));
        Container card = BoxLayout.encloseY(preview, OfficeTheme.label(name, 13, OfficeTheme.INK));
        OfficeTheme.space(card, 0, 12, 0, 20);
        choices.add(card);
      }
      content.add(choices);
    } else if ("Info".equals(section)) {
      content.add(OfficeTheme.label(session.document().title, 20, OfficeTheme.INK));
      content.add(button("Rename document", this::rename));
      content.add(button("Properties", this::propertiesDialog));
      content.add(
          button(
              "Protect document ("
                  + session.document().properties.protection
                  + ")",
              this::protectDialog));
      content.add(button("Inspect document", this::inspectDialog));
      content.add(
          button(
              "Check accessibility",
              () -> report("Accessibility", "file.accessibility")));
      content.add(
          button(
              "Check compatibility",
              () -> report("Compatibility", "file.compatibility")));
      content.add(OfficeTheme.label(statistics.words(session.document()) + " words", 14, 0x555555));
      content.add(OfficeTheme.label(editor.layout().pages.size() + " pages", 14, 0x555555));
      content.add(
          OfficeTheme.label(session.document().comments.size() + " comments", 14, 0x555555));
      content.add(
          OfficeTheme.label(
              "Recovery is saved locally. Export a file to keep or share a copy.", 12, 0x555555));
    } else {
      String[][] formats = {
        {"Word document (.docx)", "docx"},
        {"PDF document (.pdf)", "pdf"},
        {"Native document (.cn1doc)", "cn1doc"},
        {"Rich text (.rtf)", "rtf"},
        {"Web page (.html)", "html"},
        {"Markdown (.md)", "md"},
        {"Plain text (.txt)", "txt"}
      };
      for (String[] format : formats)
        content.add(
            button(
                format[0],
                () -> {
                  closeBackstage();
                  save(format[1]);
                }));
    }
    page.add(BorderLayout.CENTER, content);
    OfficeTheme.applyAppearance(page);
    page.revalidate();
  }

  private Image blankPreview, samplePreview;

  /** Template thumbnails are laid out once; opening the File tab again reuses them. */
  private Image templatePreview(boolean blank) {
    if (blank) {
      if (blankPreview == null) blankPreview = templatePreview(Document.empty());
      return blankPreview;
    }
    if (samplePreview == null) samplePreview = templatePreview(sample());
    return samplePreview;
  }

  private Image templatePreview(Document document) {
    int width = OfficeTheme.px(138), height = OfficeTheme.px(180);
    Image image = Image.createImage(width, height, 0xffffff);
    var g = image.getGraphics();
    g.setAntiAliased(true);
    var engine =
        new LayoutEngine(
            new LayoutEngine.Metrics() {
              public float width(String text, TextStyle style) {
                return DocumentFonts.font(style, style.size * 2).stringWidth(text) / 2f;
              }

              public float height(TextStyle style) {
                return DocumentFonts.font(style, style.size * 2).getHeight() / 2f;
              }
            });
    var pages = engine.layout(document, 0);
    float scale = width / pages.pages.get(0).spec.width;
    for (LayoutEngine.Line line : pages.pages.get(0).lines) {
      for (LayoutEngine.Span span : line.spans) {
        if ("\t".equals(span.text)) continue;
        g.setFont(DocumentFonts.font(span.style, Math.max(1, span.style.size * scale)));
        g.setColor(span.style.color);
        g.drawString(
            span.displayText, Math.round((line.x + span.x) * scale), Math.round(line.y * scale));
      }
    }
    return image;
  }

  private void newDocument() {
    replaceAfterSaving(
        () -> {
          forgetFile();
          session.loadDocument(Document.empty().title("Document1"));
          fileDocument = session.document();
          source = null;
          editor.resetScroll();
          edited();
        });
  }

  private void replaceAfterSaving(Runnable next) {
    editor.finishComposing();
    editor.stopInput();
    if (saved()) {
      next.run();
      return;
    }
    editor.stopInput();
    var dialog = officeDialog("Save changes?");
    dialog.setLayout(new BorderLayout());
    dialog.add(
        BorderLayout.CENTER,
        new SpanLabelCompat(
            "Save changes to \u201c" + session.document().title + "\u201d before continuing?"));
    var actions = new Container(new FlowLayout(Component.RIGHT));
    actions.add(button("Cancel", dialog::dispose));
    actions.add(
        button(
            "Don't Save",
            () -> {
              dialog.dispose();
              next.run();
            }));
    actions.add(
        button(
            "Save",
            () -> {
              dialog.dispose();
              save(fileFormat, false, true, next);
            }));
    dialog.add(BorderLayout.SOUTH, actions);
    styleDialogBody(dialog.getContentPane());
    showOfficeDialog(dialog);
  }

  private void propertiesDialog() {
    editor.stopInput();
    var form = new Container(BoxLayout.y());
    var fields = new java.util.LinkedHashMap<String, EditField>();
    for (String key : DocumentProperties.KEYS) {
      var f = field(session.document().properties.get(key));
      var label = Character.toUpperCase(key.charAt(0)) + key.substring(1);
      f.setAccessibilityText(label);
      form.add(OfficeTheme.label(label, 12, 0x555555));
      form.add(f);
      fields.put(key, f);
    }
    if (showDialog("Properties", form, "Apply", "Cancel")) {
      var args = new java.util.LinkedHashMap<String, Object>();
      for (Map.Entry<String, EditField> e : fields.entrySet()) args.put(e.getKey(), e.getValue().getText());
      run("file.properties", args);
    }
  }

  private void protectDialog() {
    editor.stopInput();
    String[][] modes = {
      {"none", "No restrictions"},
      {"readOnly", "Read-only"},
      {"comments", "Comments only"},
      {"trackedChanges", "Tracked changes only"}
    };
    var list = new Container(BoxLayout.y());
    final Dialog[] shown = new Dialog[1];
    for (String[] mode : modes) {
      var b =
          button(
              (mode[0].equals(session.document().properties.protection) ? "\u2713 " : "") + mode[1],
              () -> {
                if (shown[0] != null) shown[0].dispose();
                run("file.protect", Json.object("mode", mode[0]));
                closeBackstage();
              });
      list.add(b);
    }
    var d = officeDialog("Restrict editing");
    shown[0] = d;
    d.setLayout(new BorderLayout());
    d.add(BorderLayout.CENTER, list);
    d.add(BorderLayout.SOUTH, button("Cancel", d::dispose));
    d.show();
  }

  private void inspectDialog() {
    editor.stopInput();
    var found = commands.execute(session, "file.inspect", Json.object());
    Map<?, ?> counts = (Map<?, ?>) ((Map<?, ?>) found).get("found");
    var text = new StringBuilder();
    for (Map.Entry<?, ?> e : counts.entrySet())
      text.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
    var area = new com.codename1.ui.TextArea(text.toString());
    area.setEditable(false);
    if (showDialog("Inspect document", area, "Remove comments, hidden text and properties", "Close"))
      run(
          "file.inspect",
          Json.object(
              "remove", java.util.Arrays.<Object>asList("comments", "hidden", "properties")));
  }

  private void report(String title, String id) {
    editor.stopInput();
    Map<?, ?> r = (Map<?, ?>) commands.execute(session, id, Json.object());
    var text = new StringBuilder();
    for (Object o : (java.util.List<?>) r.get("issues")) {
      if (o instanceof Map<?, ?> i) {
        text.append(i.get("severity")).append(": ").append(i.get("message"));
        if (((Number) i.get("block")).intValue() >= 0)
          text.append(" (paragraph ").append(((Number) i.get("block")).intValue() + 1).append(')');
      } else text.append(o);
      text.append('\n');
    }
    if (text.length() == 0) text.append("No issues found.");
    var area = new com.codename1.ui.TextArea(text.toString());
    area.setEditable(false);
    showDialog(title, area, "Close", null);
  }

  private void rename() {
    prompt(
        "Document name",
        session.document().title,
        v -> run("document.title", Json.object("title", v)));
  }

  /** Platform menu bar with the full structure (the Mac's AppKit menu), set by the launcher. */
  public interface MenuBar {
    boolean install(String spec);

    void check(int id, boolean on);

    void about(String version, String credits);

    void fitWindow(int widthPercent, int heightPercent);
  }

  private MenuBar menuBar;

  /**
   * The first launch opens a window sized for writing (most of the screen, centered) rather than
   * the port's small default; afterwards the window keeps the frame the user gives it.
   */
  private void fitWindowToScreen() {
    if (menuBar == null || com.codename1.io.Preferences.get("wordprocessor.windowFitted", false)) return;
    com.codename1.io.Preferences.set("wordprocessor.windowFitted", true);
    menuBar.fitWindow(80, 90);
  }
  private AppMenus menus;

  public void setMenuBar(MenuBar menuBar) {
    this.menuBar = menuBar;
  }

  /** A native menu selection; may arrive on any thread. */
  public void menuSelected(int id) {
    Display.getInstance().callSerially(() -> {
      if (menus != null) menus.fire(id);
    });
  }

  private Runnable menuAction(String name, Runnable action) {
    uiActions.put(name, action);
    return () -> {
      if (backstage) closeBackstage();
      editor.finishComposing();
      safe(action);
      if (menus != null) menus.sync();
    };
  }

  private Runnable cmd(String id) {
    return () -> runPrompted(id);
  }

  private Runnable viewCmd(String id) {
    return () -> run(id, Json.object());
  }

  /**
   * The menu bar, following Word for Mac: application, File, Edit, View, Insert, Format, Tools,
   * Table, Window and Help. The Mac gets it natively with submenus and check marks; other desktops
   * get the same items through the framework's menu bridge. Phones and tablets use the ribbon.
   */
  private void installMenu() {
    if (OfficeTheme.mobile()) return;
    final int cmd = AppMenus.CMD, shift = AppMenus.CMD | AppMenus.SHIFT, alt = AppMenus.CMD | AppMenus.ALT;
    var m = new AppMenus();
    m.menu(APP_NAME)
        .item("About " + APP_NAME, menuAction("About", this::about))
        .separator()
        .item("Settings…", ',', cmd, menuAction("Settings", this::preferences))
        .separator()
        .services()
        .separator()
        .system("Hide " + APP_NAME, "hide:", 'h', cmd, null)
        .system("Hide Others", "hideOtherApplications:", 'h', alt, null)
        .system("Show All", "unhideAllApplications:", (char) 0, 0, null)
        .separator()
        .system("Quit " + APP_NAME, "terminate:", 'q', cmd, () -> Display.getInstance().exitApplication());
    m.menu("File")
        .item("New Document", 'n', cmd, menuAction("New Document", this::newDocument))
        .item("New from Template…", 'p', shift, menuAction("New from Template", cmd("file.newFromTemplate")))
        .item("Open…", 'o', cmd, menuAction("Open", this::open))
        .separator()
        .item("Close", 'w', cmd, menuAction("Close", viewCmd("file.close")))
        .item("Save", 's', cmd, menuAction("Save", () -> saveDocument(false)))
        .item("Save As…", 's', shift, menuAction("Save As", () -> saveDocument(true)))
        .item("Save as Template…", menuAction("Save as Template", cmd("file.saveTemplate")))
        .item("Browse Version History", menuAction("Version History", cmd("file.versions")))
        .separator()
        .submenu("Export")
        .item("PDF…", menuAction("Export PDF", () -> save("pdf")))
        .item("Word Document (.docx)…", menuAction("Export Word Document", () -> save("docx")))
        .item("Rich Text Format (.rtf)…", menuAction("Export Rich Text", () -> save("rtf")))
        .item("Web Page (.html)…", menuAction("Export Web Page", () -> save("html")))
        .item("Markdown (.md)…", menuAction("Export Markdown", () -> save("md")))
        .item("Plain Text (.txt)…", menuAction("Export Plain Text", () -> save("txt")))
        .end()
        .item("Share…", menuAction("Share", this::share))
        .separator()
        .item("Protect Document…", menuAction("Protect Document", this::protectDialog))
        .item("Inspect Document…", menuAction("Inspect Document", this::inspectDialog))
        .item("Check Accessibility", menuAction("Check Accessibility", () -> report("Accessibility", "file.accessibility")))
        .separator()
        .item("Page Setup…", menuAction("Page Setup", this::margins))
        .item("Print…", 'p', cmd, menuAction("Print", this::printDocument))
        .separator()
        .item("Properties…", menuAction("Properties", this::propertiesDialog))
        .item("Rename…", menuAction("Rename", this::rename));
    m.menu("Edit")
        .system("Undo", "undo:", 'z', cmd, () -> run("edit.undo", Json.object()))
        .system("Redo", "redo:", 'z', shift, () -> run("edit.redo", Json.object()))
        .item("Undo History…", menuAction("Undo History", this::undoHistory))
        .separator()
        .system("Cut", "cut:", 'x', cmd, () -> clipboard("cut"))
        .system("Copy", "copy:", 'c', cmd, () -> clipboard("copy"))
        .system("Paste", "paste:", 'v', cmd, () -> clipboard("paste"))
        .item("Paste Special…", menuAction("Paste Special", this::pasteOptions))
        .item("Office Clipboard", menuAction("Office Clipboard", this::officeClipboard))
        .item("Clear Formatting", menuAction("Clear Formatting", this::clearFormatting))
        .system("Select All", "selectAll:", 'a', cmd, null)
        .separator()
        .submenu("Find")
        .item("Find…", 'f', cmd, menuAction("Find", this::find))
        .item("Find Next", 'g', cmd, menuAction("Find Next", viewCmd("edit.findNext")))
        .item("Find Previous", 'g', shift, menuAction("Find Previous", viewCmd("edit.findPrevious")))
        .item("Replace…", 'h', shift, menuAction("Find and Replace", this::find))
        .item("Go To…", 'g', alt, menuAction("Go To", cmd("edit.goto")))
        .end();
    m.menu("View")
        .toggle("Draft", () -> editor.isDraft(), menuAction("Draft", viewCmd("view.draft")))
        .toggle("Web Layout", () -> editor.isReflow() && !editor.isDraft(), menuAction("Web Layout", viewCmd("view.webLayout")))
        .toggle("Outline", () -> editor.getOutline() != null, menuAction("Outline", viewCmd("view.outline")))
        .toggle("Print Layout", () -> !editor.isReflow(), menuAction("Print Layout", viewCmd("view.printLayout")))
        .toggle("Read Mode", () -> editor.isViewOnly(), menuAction("Read Mode", viewCmd("view.readMode")))
        .toggle("Focus", () -> ribbon.isHidden(), menuAction("Focus", viewCmd("view.focus")))
        .separator()
        .toggle("Ruler", () -> !ruler.isHidden(), menuAction("Ruler", viewCmd("view.ruler")))
        .toggle("Navigation Pane", () -> navigation.getParent() != null, menuAction("Navigation Pane", viewCmd("view.navigationPane")))
        .toggle("Formatting Marks", '8', cmd, () -> editor.isShowMarks(), menuAction("Formatting Marks", viewCmd("view.marks")))
        .toggle("Gridlines", () -> editor.isGridlines(), menuAction("Gridlines", viewCmd("view.gridlines")))
        .separator()
        .item("Header and Footer", menuAction("Header and Footer", viewCmd("insert.editHeader")))
        .item("Footnotes", menuAction("Footnotes", this::showNotes))
        .item("Comments", menuAction("Comments", this::comments))
        .item("Reviewing Pane", menuAction("Reviewing Pane", this::revisionsPane))
        .separator()
        .toggle("Dark Page", () -> editor.isDarkPage(), menuAction("Dark Page", this::toggleDarkPage))
        .separator()
        .submenu("Zoom")
        .item("Zoom In", menuAction("Zoom In", viewCmd("view.zoomIn")))
        .item("Zoom Out", menuAction("Zoom Out", viewCmd("view.zoomOut")))
        .item("Actual Size", '0', cmd, menuAction("Actual Size", viewCmd("view.zoom100")))
        .item("Page Width", menuAction("Page Width", viewCmd("view.pageWidth")))
        .item("One Page", menuAction("One Page", viewCmd("view.onePage")))
        .item("Multiple Pages", menuAction("Multiple Pages", viewCmd("view.multiplePages")))
        .end()
        .item("Split", menuAction("Split", viewCmd("view.split")));
    m.menu("Insert")
        .submenu("Break")
        .item("Page Break", menuAction("Page Break", viewCmd("insert.pageBreak")))
        .item("Column Break", menuAction("Column Break", viewCmd("layout.columnBreak")))
        .item("Section Break…", menuAction("Section Break", this::sectionBreakDialog))
        .end()
        .item("Page Numbers…", menuAction("Page Numbers", cmd("insert.pageNumber")))
        .item("Date and Time…", menuAction("Date and Time", cmd("insert.dateTime")))
        .item("Field…", menuAction("Field", cmd("insert.field")))
        .item("Bookmark…", menuAction("Bookmark", this::bookmarkDialog))
        .separator()
        .item("Footnote…", menuAction("Footnote", () -> noteDialog(null, "footnote")))
        .item("Endnote…", menuAction("Endnote", () -> noteDialog(null, "endnote")))
        .item("Caption…", menuAction("Caption", this::captionDialog))
        .item("Cross-reference…", menuAction("Cross-reference", this::crossReferenceDialog))
        .submenu("Index and Tables")
        .item("Table of Contents…", menuAction("Table of Contents", this::contentsDialog))
        .item("Table of Figures…", menuAction("Table of Figures", () -> contentsDialog("Figure")))
        .item("Mark Contents Entry…", menuAction("Mark Contents Entry", this::markContentsDialog))
        .item("Index…", menuAction("Index", this::indexDialog))
        .item("Citation…", menuAction("Citation", this::citationDialog))
        .item("Bibliography…", menuAction("Bibliography", this::bibliographyDialog))
        .end()
        .separator()
        .item("Table…", menuAction("Table", this::insertTable))
        .item("Quick Tables…", menuAction("Quick Tables", this::quickTables))
        .item("Picture…", menuAction("Picture", this::insertPicture))
        .item("Chart…", menuAction("Chart", cmd("insert.chart")))
        .item("Shape…", menuAction("Shape", cmd("insert.shape")))
        .item("Text Box", menuAction("Text Box", cmd("insert.textBox")))
        .item("Equation", menuAction("Equation", cmd("insert.equation")))
        .item("Symbol…", menuAction("Symbol", cmd("insert.symbol")))
        .separator()
        .item("New Comment", 'a', alt, menuAction("New Comment", this::newComment))
        .item("Link…", 'k', cmd, menuAction("Link", cmd("insert.link")));
    m.menu("Format")
        .item("Font…", 'd', cmd, menuAction("Font", this::fontDialog))
        .item("Paragraph…", 'm', alt, menuAction("Paragraph", this::paragraphDialog))
        .item("Line Spacing…", menuAction("Line Spacing", this::lineSpacingDialog))
        .item("Document…", menuAction("Document", this::sectionSettings))
        .item("Number Format…", menuAction("Number Format", this::chooseNumberFormat))
        .item("Borders and Shading…", menuAction("Borders and Shading", this::bordersDialog))
        .item("Columns…", menuAction("Columns", this::columnsDialog))
        .item("Tabs…", menuAction("Tabs", viewCmd("para.tabs")))
        .item("Drop Cap…", menuAction("Drop Cap", cmd("insert.dropCap")))
        .item("Change Case…", menuAction("Change Case", this::changeCase))
        .item("Footnote and Endnote…", menuAction("Footnote and Endnote", this::noteOptionsDialog))
        .separator()
        .submenu("Character")
        .item("Bold", 'b', cmd, menuAction("Bold", () -> format("bold")))
        .item("Italic", 'i', cmd, menuAction("Italic", () -> format("italic")))
        .item("Underline", 'u', cmd, menuAction("Underline", () -> format("underline")))
        .item("Strikethrough", 'x', shift, menuAction("Strikethrough", () -> format("strike")))
        .item("Superscript", '=', shift, menuAction("Superscript", viewCmd("format.superscript")))
        .item("Subscript", '=', cmd, menuAction("Subscript", viewCmd("format.subscript")))
        .end()
        .submenu("Style")
        .item("Normal", 'n', alt, menuAction("Normal", () -> heading(0)))
        .item("Heading 1", '1', alt, menuAction("Heading 1", () -> heading(1)))
        .item("Heading 2", '2', alt, menuAction("Heading 2", () -> heading(2)))
        .item("Heading 3", '3', alt, menuAction("Heading 3", () -> heading(3)))
        .separator()
        .item("Styles…", menuAction("Styles", this::moreStyles))
        .end()
        .item("Theme…", menuAction("Theme", cmd("design.theme")))
        .item("Keep with Next", menuAction("Keep with Next", () -> paragraph(
            "keepNext", String.valueOf(!session.editingDocument().paragraph(session.selection().block).keepNext))))
        .item("Restart Numbering…", menuAction("Restart Numbering",
            () -> prompt("Start at", "1", v -> paragraph("listStart", v))));
    m.menu("Tools")
        .item("Spelling and Grammar…", 'l', alt, menuAction("Spelling and Grammar", cmd("review.spelling")))
        .item("Thesaurus…", menuAction("Thesaurus", cmd("review.thesaurus")))
        .item("Hyphenation…", menuAction("Hyphenation", cmd("layout.hyphenation")))
        .item("Language…", menuAction("Language", cmd("review.language")))
        .item("Word Count…", 'g', shift | AppMenus.ALT, menuAction("Word Count",
            () -> showText("Word count", statistics.words(session.document()) + " words")))
        .item("AutoCorrect Options…", menuAction("AutoCorrect Options", () -> preferences("AutoCorrect")))
        .separator()
        .toggle("Track Changes", 'e', shift, () -> session.document().trackChanges, menuAction("Track Changes", () -> {
          session.trackChanges(!session.document().trackChanges);
          edited();
        }))
        .item("Accept Change", menuAction("Accept Change", () -> revisionAction(true, false)))
        .item("Reject Change", menuAction("Reject Change", () -> revisionAction(false, false)))
        .item("Change Tracking Options…", menuAction("Change Tracking Options", this::trackingOptions))
        .item("Compare Documents…", menuAction("Compare Documents", this::compareDialog))
        .separator()
        .item("Mail Merge…", menuAction("Mail Merge", this::mergeStartDialog))
        .item("Envelopes…", menuAction("Envelopes", this::envelopeDialog))
        .item("Labels…", menuAction("Labels", this::labelsDialog))
        .separator()
        .item("Macros…", menuAction("Macros", this::macroDialog))
        .item("Commands…", menuAction("Commands", this::commandDialog))
        .item("Customize Keyboard…", menuAction("Customize Keyboard", this::customizeKeyboardDialog));
    m.menu("Table")
        .item("Insert Table…", menuAction("Insert Table", this::insertTable))
        .item("Quick Tables…", menuAction("Quick Tables", this::quickTables))
        .item("Insert Row Below", menuAction("Insert Row Below", viewCmd("table.insertRowBelow")))
        .submenu("Delete")
        .item("Rows", menuAction("Delete Rows", viewCmd("table.deleteRow")))
        .item("Columns", menuAction("Delete Columns", viewCmd("table.deleteColumn")))
        .item("Table", menuAction("Delete Table", viewCmd("table.deleteTable")))
        .end()
        .item("Select Cells…", menuAction("Select Cells", this::selectCellRange))
        .separator()
        .item("Merge Cells", menuAction("Merge Cells", viewCmd("table.mergeSelected")))
        .item("Split Cells…", menuAction("Split Cells", this::splitCells))
        .item("Split Table", menuAction("Split Table", viewCmd("table.splitTable")))
        .separator()
        .item("Sort…", menuAction("Sort", cmd("table.sort")))
        .item("Formula…", menuAction("Formula", this::tableFormula))
        .submenu("Convert")
        .item("Text to Table", menuAction("Text to Table", cmd("table.fromText")))
        .item("Table to Text", menuAction("Table to Text", cmd("table.toText")))
        .end()
        .toggle("Gridlines", () -> editor.isTableGridlines(), menuAction("Table Gridlines", viewCmd("table.viewGridlines")))
        .separator()
        .item("Table Properties…", menuAction("Table Properties", this::tableLayout));
    m.menu("Window")
        .system("Minimize", "performMiniaturize:", 'm', cmd, null)
        .system("Zoom", "performZoom:", (char) 0, 0, null)
        .separator()
        .item("New Window", menuAction("New Window", viewCmd("view.newWindow")))
        .separator()
        .system("Bring All to Front", "arrangeInFront:", (char) 0, 0, null);
    m.menu("Help")
        .item("Search Commands…", menuAction("Search Commands", this::searchCommands))
        .item("Keyboard Shortcuts…", menuAction("Keyboard Shortcuts", this::customizeKeyboardDialog))
        .separator()
        .item("Project Website", menuAction("Project Website", () -> Display.getInstance().execute(PROJECT_URL)))
        .item("Report a Problem", menuAction("Report a Problem", () -> Display.getInstance().execute(PROJECT_URL + "/issues")));
    menus = m;
    final MenuBar bar = menuBar;
    AppMenus.Native mac = bar == null ? null : new AppMenus.Native() {
      public boolean install(String spec) {
        return bar.install(spec);
      }

      public void check(int id, boolean on) {
        bar.check(id, on);
      }
    };
    if (!m.install(mac)) {
      var commands = m.commands(r -> r);
      if (form.getToolbar() instanceof OfficeTitleBar title) title.nativeMenu(commands);
    }
  }

  /** Session override from View > Dark Page; null follows the appearance and Settings. */
  private Boolean darkPageOverride;

  private void applyDarkPage() {
    boolean want =
        darkPageOverride != null
            ? darkPageOverride.booleanValue()
            : OfficeTheme.dark() && AppOptions.flag("darkPage");
    if (editor.isDarkPage() != want) editor.setDarkPage(want);
    if (menus != null) menus.sync();
  }

  private void toggleDarkPage() {
    darkPageOverride = Boolean.valueOf(!editor.isDarkPage());
    applyDarkPage();
  }

  private static final String ABOUT_CREDITS =
      "<div style=\"font-family:-apple-system,sans-serif;font-size:11px;text-align:center\">"
          + "A word processor written in Java with Codename One and compiled to native code."
          + "<br><br>Opens and saves Word (.docx), RTF, HTML, Markdown and plain text, and exports PDF."
          + "<br><br><a href=\"" + "https://github.com/shai-almog/cn1-wordprocessor" + "\">Project website</a>"
          + " &middot; <a href=\"" + "https://github.com/shai-almog/cn1-wordprocessor/issues" + "\">Report a problem</a>"
          + "<br><br>Source Sans 3 and JetBrains Mono fonts: SIL Open Font License 1.1."
          + "<br>Built with <a href=\"https://www.codenameone.com\">Codename One</a>.</div>";

  /** The platform About panel on the Mac; elsewhere a dialog with the same content. */
  private void about() {
    String version = Display.getInstance().getProperty("AppVersion", "1.0");
    if (menuBar != null) {
      menuBar.about(version, ABOUT_CREDITS);
      return;
    }
    var body = new Container(BoxLayout.y());
    var name = OfficeTheme.label(APP_NAME, 20, OfficeTheme.INK);
    name.getAllStyles().setFont(OfficeTheme.font(20, true));
    body.add(name);
    body.add(OfficeTheme.label("Version " + version, 12, OfficeTheme.DIM));
    var text = new SpanLabelCompat(
        "A word processor written in Java with Codename One and compiled to native code.\n\n"
            + "Opens and saves Word (.docx), RTF, HTML, Markdown and plain text, and exports PDF.\n\n"
            + "Source Sans 3 and JetBrains Mono fonts: SIL Open Font License 1.1.");
    OfficeTheme.space(text, 12, 12, 0, 0);
    body.add(text);
    body.add(OfficeTheme.row(
        button("Project website", () -> Display.getInstance().execute(PROJECT_URL)),
        button("Report a problem", () -> Display.getInstance().execute(PROJECT_URL + "/issues"))));
    showDialog("About " + APP_NAME, body, "Close", null);
  }

  private void preferences() {
    preferences("General");
  }

  /** Settings (Preferences), grouped as Word's: each change applies immediately. */
  private void preferences(String start) {
    editor.stopInput();
    var dialog = officeDialog("Settings");
    dialog.setLayout(new BorderLayout());
    var rail = new Container(BoxLayout.y());
    OfficeTheme.space(rail, 4, 4, 0, 12);
    var pane = new Container(BoxLayout.y());
    pane.setScrollableY(true);
    OfficeTheme.space(pane, 4, 4, 16, 8);
    pane.setPreferredW(OfficeTheme.px(460));
    pane.setPreferredH(OfficeTheme.px(400));
    String[] sections = {"General", "View", "AutoCorrect", "Spelling & Grammar", "Save", "Print", "Ribbon"};
    var tabs = new ArrayList<Button>();
    final Runnable[] select = new Runnable[sections.length];
    for (int i = 0; i < sections.length; i++) {
      final String section = sections[i];
      final int index = i;
      var tab = button(section, () -> select[index].run());
      tab.setAlignment(Component.LEFT);
      tab.setPreferredW(OfficeTheme.px(150));
      tabs.add(tab);
      rail.add(tab);
      select[i] = () -> {
        pane.removeAll();
        settingsSection(section, pane);
        for (int t = 0; t < tabs.size(); t++) OfficeTheme.checked(tabs.get(t), t == index);
        styleDialogBody(pane);
        OfficeTheme.applyAppearance(dialog);
        pane.scrollRectToVisible(0, 0, 1, 1, pane);
        dialog.revalidate();
      };
    }
    dialog.add(BorderLayout.WEST, rail);
    dialog.add(BorderLayout.CENTER, pane);
    var done =
        new Command("Done") {
          public void actionPerformed(com.codename1.ui.events.ActionEvent e) {
            dialog.dispose();
          }
        };
    var doneButton = new Button(done);
    OfficeTheme.button(doneButton);
    dialog.add(BorderLayout.SOUTH, FlowLayout.encloseRight(doneButton));
    dialog.setBackCommand(done);
    dialog.setDefaultCommand(done);
    int first = Math.max(0, Arrays.asList(sections).indexOf(start));
    select[first].run();
    styleDialogBody(dialog.getContentPane());
    showOfficeDialog(dialog);
  }

  private static Label settingsHeading(String text) {
    var l = OfficeTheme.label(text, 13, OfficeTheme.INK);
    l.getAllStyles().setFont(OfficeTheme.font(13, true));
    OfficeTheme.space(l, 14, 6, 0, 0);
    return l;
  }

  private CheckBox settingsFlag(String label, String key) {
    var box = new CheckBox(label);
    box.setSelected(AppOptions.flag(key));
    box.addActionListener(e -> setOption(key, String.valueOf(box.isSelected())));
    return box;
  }

  private ComboBox<String> settingsChoice(String key, String[] labels, String[] values) {
    var box = new ComboBox<String>(labels);
    box.setSelectedIndex(Math.max(0, Arrays.asList(values).indexOf(AppOptions.get(key))));
    box.addActionListener(e -> setOption(key, values[box.getSelectedIndex()]));
    return box;
  }

  private EditField settingsText(String key) {
    var f = field(AppOptions.get(key));
    f.addDataChangedListener((type, index) -> setOption(key, f.getText()));
    return f;
  }

  private void settingsSection(String section, Container pane) {
    switch (section) {
      case "General" -> {
        pane.add(settingsHeading("Personalize"));
        pane.add(formRow("Name", settingsText("userName")));
        pane.add(formRow("Initials", settingsText("userInitials")));
        pane.add(settingsHeading("Appearance"));
        pane.add(formRow("Theme", settingsChoice("officeTheme",
            new String[] {"Match System", "Light", "Dark"}, new String[] {"system", "light", "dark"})));
        pane.add(settingsFlag("Turn the page dark in Dark Mode", "darkPage"));
        pane.add(settingsHeading("Units"));
        pane.add(formRow("Measure in", settingsChoice("units",
            new String[] {"Inches", "Centimeters", "Millimeters", "Points", "Picas"},
            new String[] {"inches", "centimeters", "millimeters", "points", "picas"})));
        pane.add(formRow("Numerals", settingsChoice("numeral",
            new String[] {"Arabic", "Hindi", "Context", "System"},
            new String[] {"arabic", "hindi", "context", "system"})));
      }
      case "View" -> {
        pane.add(settingsHeading("Show in document"));
        pane.add(settingsFlag("Comments", "showComments"));
        pane.add(settingsFlag("Hide ink annotations", "hideInk"));
      }
      case "AutoCorrect" -> {
        pane.add(settingsHeading("As you type"));
        pane.add(settingsFlag("Replace text as you type", "autocorrect.replaceText"));
        pane.add(settingsFlag("Capitalize first letter of sentences", "autocorrect.capitalizeSentences"));
        pane.add(settingsFlag("Capitalize names of days", "autocorrect.capitalizeDays"));
        pane.add(settingsFlag("Correct TWo INitial CApitals", "autocorrect.twoInitialCaps"));
        pane.add(settingsFlag("Replace straight quotes with smart quotes", "autocorrect.smartQuotes"));
        pane.add(settingsHeading("Replacements (one per line: typed=replacement)"));
        var entries = field(AppOptions.get("autocorrect.entries").replace('|', '\n'), 8, 30);
        entries.addDataChangedListener((type, index) ->
            setOption("autocorrect.entries", entries.getText().trim().replace('\n', '|')));
        pane.add(entries);
      }
      case "Spelling & Grammar" -> {
        pane.add(settingsHeading("Proofing"));
        pane.add(settingsFlag("Check spelling as you type", "checkSpelling"));
        pane.add(settingsFlag("Check grammar as you type", "checkGrammar"));
      }
      case "Save" -> {
        pane.add(settingsHeading("AutoSave"));
        pane.add(settingsFlag("Turn on AutoSave by default", "autosave"));
        pane.add(formRow("Save every", settingsChoice("autosaveMinutes",
            new String[] {"1 minute", "2 minutes", "5 minutes", "10 minutes", "15 minutes", "30 minutes"},
            new String[] {"1", "2", "5", "10", "15", "30"})));
      }
      case "Print" -> {
        pane.add(settingsHeading("Include with document"));
        pane.add(settingsFlag("Print comments and tracked changes", "printMarkup"));
      }
      default -> {
        pane.add(settingsHeading("Show these ribbon tabs"));
        var hidden = new ArrayList<String>(Arrays.asList(TextUtil.split(AppOptions.get("ribbon.hidden"), ',')));
        for (String title : ribbon.titles()) {
          if ("Table Layout".equals(title) || "Custom".equals(title)) continue;
          var box = new CheckBox(title);
          box.setSelected(!hidden.contains(title));
          box.addActionListener(e -> {
            var now = new ArrayList<String>(Arrays.asList(TextUtil.split(AppOptions.get("ribbon.hidden"), ',')));
            now.remove("");
            now.remove(title);
            if (!box.isSelected()) now.add(title);
            setOption("ribbon.hidden", joinComma(now));
          });
          pane.add(box);
        }
        pane.add(settingsHeading("Keyboard"));
        pane.add(button("Customize Keyboard…", this::customizeKeyboardDialog));
      }
    }
  }

  /** Stores a validated option and applies it to the open window. */
  private void setOption(String key, String value) {
    try {
      commands.execute(session, "file.options", Json.object("key", key, "value", value));
    } catch (IllegalArgumentException ex) {
      status.setText(ex.getMessage());
      return;
    }
    switch (key) {
      case "officeTheme" -> {
        OfficeTheme.refreshAppearance();
        darkPageOverride = null;
        applyDarkPage();
        OfficeTheme.applyAppearance(form);
        Form current = Display.getInstance().getCurrent();
        if (current != form) OfficeTheme.applyAppearance(current);
        form.repaint();
      }
      case "darkPage" -> {
        darkPageOverride = null;
        applyDarkPage();
      }
      case "autosave" -> autoSave.setOn(AppOptions.flag("autosave"));
      case "ribbon.hidden" -> applyRibbonOptions();
      default -> {
        editor.repaint();
        ruler.repaint();
        updateStatus();
      }
    }
  }

  static final String APP_NAME = "CN1 Word Processor";
  static final String PROJECT_URL = "https://github.com/shai-almog/cn1-wordprocessor";

  private void zoom(float delta) {
    editor.setZoom(editor.getZoom() + delta);
    updateStatus();
    ruler.repaint();
  }

  private void clipboard(String action) {
    editor.clipboard(action);
  }

  private void changeSize(int delta) {
    run(
        "format.set",
        Json.object(
            "property",
            "size",
            "value",
            String.valueOf(Math.max(1, session.activeStyle().size + delta))));
  }

  private void indent(int delta) {
    var b = session.editingDocument().blocks.get(session.selection().block);
    if (b instanceof Paragraph p) {
      if (!p.list.isEmpty())
        paragraph(
            "listLevel",
            String.valueOf(Math.max(0, Math.min(8, p.listLevel + (delta > 0 ? 1 : -1)))));
      else paragraph("indent", String.valueOf(Math.max(0, p.indent + delta)));
    }
  }

  private void chooseNumberFormat() {
    var choice =
        new ComboBox<String>("decimal", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman");
    if (showDialog("Number format", choice, "Apply", "Cancel"))
      paragraph("listFormat", choice.getSelectedItem());
  }

  private void list(String kind) {
    var b = session.editingDocument().blocks.get(session.selection().block);
    paragraph("list", b instanceof Paragraph p && kind.equals(p.list) ? "" : kind);
  }

  private void clearFormatting() {
    session.transact(
        s -> {
          for (String[] pair :
              new String[][] {
                {"bold", "false"},
                {"italic", "false"},
                {"underline", "false"},
                {"strike", "false"},
                {"script", "baseline"},
                {"size", "12"},
                {"color", "242424"},
                {"background", "ffffff"},
                {"font", "Source Sans 3"}
              }) s.format(pair[0], pair[1]);
          for (String key : CharacterFormat.KEYS) s.format(key, CharacterFormat.DEFAULT.value(key));
        });
    edited();
  }

  private Button highlightButton, fontColorButton;
  private int highlight = 0xffff00, fontColor = 0xc00000;

  /** Word's split button: the main control plus a narrow chevron that opens its options. */
  private Container split(Button main, String more, Runnable options) {
    var chevron = iconButton("", FontImage.MATERIAL_ARROW_DROP_DOWN, options, false);
    chevron.setAccessibilityText(more);
    OfficeTheme.icon(chevron, FontImage.MATERIAL_ARROW_DROP_DOWN, 12, OfficeTheme.DIM);
    OfficeTheme.space(chevron, 4, 4, 0, 1);
    Container c = OfficeTheme.row(main, chevron);
    c.getAllStyles().setPadding(0, 0, 0, 0);
    return c;
  }

  /** Highlight / font color: applies the last color used; the bar under the glyph shows it. */
  private Button colorButton(String name, char glyph, String property) {
    Runnable apply = () -> {
      int c = "background".equals(property) ? highlight : fontColor;
      run("format.set", Json.object("property", property, "value", Integer.toHexString(0x1000000 | c).substring(1)));
    };
    var b = new Button("") {
      @Override
      public void paint(Graphics g) {
        super.paint(g);
        var c = getClientProperty("barColor");
        if (!(c instanceof Integer)) return;
        int w = OfficeTheme.px(14), h = OfficeTheme.px(3);
        g.setColor(((Integer) c).intValue());
        g.fillRect(getX() + (getWidth() - w) / 2, getY() + getHeight() - h - OfficeTheme.px(3), w, h);
      }
    };
    OfficeTheme.button(b);
    b.addActionListener(e -> apply.run());
    b.setAccessibilityText(name);
    OfficeTheme.icon(b, glyph, 16, OfficeTheme.INK);
    OfficeTheme.space(b, 3, 6, 4, 4);
    paintColorIcon(b, "background".equals(property) ? highlight : fontColor);
    return b;
  }

  private void paintColorIcon(Button b, int color) {
    b.putClientProperty("barColor", Integer.valueOf(color));
    b.repaint();
  }

  private void rememberColor(String property, int color) {
    if ("background".equals(property)) {
      highlight = color;
      if (highlightButton != null) paintColorIcon(highlightButton, color);
    } else if ("color".equals(property)) {
      fontColor = color;
      if (fontColorButton != null) paintColorIcon(fontColorButton, color);
    }
  }

  private void pasteOptions() {
    var pick =
        new ComboBox<String>("Keep Source Formatting", "Keep Text Only", "Office Clipboard...");
    if (!showDialog("Paste Options", pick, "Paste", "Cancel")) return;
    int i = pick.getSelectedIndex();
    if (i == 2) officeClipboard();
    else clipboard(i == 0 ? "paste" : "pasteText");
  }

  /** Title-bar undo history: undoes the chosen number of steps at once. */
  private void undoHistory() {
    int depth = session.undoDepth();
    if (depth == 0) return;
    String[] steps = new String[Math.min(depth, 20)];
    for (int i = 0; i < steps.length; i++) steps[i] = i == 0 ? "Undo 1 action" : "Undo " + (i + 1) + " actions";
    var pick = new ComboBox<String>(steps);
    if (!showDialog("Undo", pick, "Undo", "Cancel")) return;
    for (int i = 0; i <= pick.getSelectedIndex(); i++) run("edit.undo", Json.object());
  }

  private void underlineStyles() {
    String[] styles = {"single", "double", "thick", "dotted", "dash", "dotDash", "wave", "none"};
    String[] names = {"Single", "Double", "Thick", "Dotted", "Dashed", "Dot-dash", "Wave", "None"};
    var pick = new ComboBox<String>(names);
    if (showDialog("Underline", pick, "Apply", "Cancel"))
      run("format.underline", Json.object("style", styles[pick.getSelectedIndex()]));
  }

  /** Bullet and Numbering libraries: one list kind plus a label template, as one undo step. */
  private void listLibrary(boolean bullets) {
    String[] labels = bullets
        ? new String[] {"\u2022", "\u25e6", "\u25aa", "\u25c6", "\u27a2", "\u2713", "\u2013"}
        : new String[] {"1.", "1)", "I.", "A.", "a)", "a.", "i."};
    String[] formats = bullets
        ? new String[] {"bullet", "bullet|\u25e6", "bullet|\u25aa", "bullet|\u25c6", "bullet|\u27a2", "bullet|\u2713", "bullet|\u2013"}
        : new String[] {"decimal", "decimal|%1)", "upperRoman", "upperLetter", "lowerLetter|%1)", "lowerLetter", "lowerRoman"};
    var pick = new ComboBox<String>(labels);
    if (!showDialog(bullets ? "Bullet Library" : "Numbering Library", pick, "Apply", "Cancel")) return;
    var format = formats[pick.getSelectedIndex()];
    editor.finishComposing();
    safe(() -> {
      session.transact(x -> {
        x.paragraph("list", bullets ? "bullet" : "number");
        x.paragraph("listFormat", format);
      });
      edited();
    });
  }

  private void color(String property) {
    var d = officeDialog("background".equals(property) ? "Text highlight color" : "Font color");
    d.setLayout(new BorderLayout());
    var grid = new Container(new GridLayout(3, 6));
    for (String hex :
        new String[] {
          "242424", "ffffff", "185abd", "c00000", "ffc000", "008450", "7030a0", "4472c4", "ed7d31",
          "70ad47", "ff66aa", "00b0f0", "fff2cc", "e2efd9", "dae3f3", "fbe4d5", "e4dfec", "ffff00"
        }) {
      var swatch =
          button(
              "  ",
              () -> {
                d.dispose();
                rememberColor(property, Integer.parseInt(hex, 16));
                run("format.set", Json.object("property", property, "value", hex));
              });
      OfficeTheme.surface(swatch, Integer.parseInt(hex, 16));
      swatch.setPreferredW(OfficeTheme.px(32));
      swatch.setPreferredH(OfficeTheme.px(28));
      swatch.setAccessibilityText(hex);
      grid.add(swatch);
    }
    d.add(BorderLayout.CENTER, grid);
    d.add(BorderLayout.SOUTH, button("Cancel", d::dispose));
    styleDialogBody(d.getContentPane());
    showOfficeDialog(d);
  }

  /** Layout > Columns: Word's presets, a count, spacing, a separator and the apply scope. */
  private void columnsDialog() {
    var current = session.activeSection().raw.columns;
    var preset =
        new ComboBox<String>("One", "Two", "Three", "Left", "Right", "Custom count");
    preset.setSelectedIndex(
        current.equalWidth() && current.count <= 3
            ? current.count - 1
            : current.equalWidth() ? 5 : current.widths()[0] < current.widths()[2] ? 3 : 4);
    var count = field(String.valueOf(current.count));
    var space = field(String.valueOf(current.space));
    var separator = new CheckBox("Line between");
    separator.setSelected(current.separator);
    var apply =
        new ComboBox<String>("This section", "Whole document", "This point forward");
    for (Component c : new Component[] {preset, count, space, separator, apply})
      styleDialogBody(c);
    if (!showDialog(
        "Columns",
        BoxLayout.encloseY(
            new Label("Presets"), preset,
            new Label("Number of columns (custom)"), count,
            new Label("Spacing (points)"), space,
            separator,
            new Label("Apply to"), apply),
        "Apply",
        "Cancel")) return;
    Map<String, Object> args = Json.object();
    String[] presets = {"one", "two", "three", "left", "right"};
    try {
      if (preset.getSelectedIndex() < 5) args.put("preset", presets[preset.getSelectedIndex()]);
      else args.put("count", Integer.valueOf(Integer.parseInt(count.getText().trim())));
      args.put("space", Float.valueOf(Float.parseFloat(space.getText().trim())));
    } catch (NumberFormatException e) {
      Dialog.show("Columns", "Enter whole columns and spacing in points.", "OK", null);
      return;
    }
    args.put("separator", Boolean.valueOf(separator.isSelected()));
    args.put("apply", new String[] {"section", "document", "forward"}[apply.getSelectedIndex()]);
    run("layout.columns", args);
  }

  private void margins() {
    var p = session.activeSection().raw.page;
    EditField top = field(String.valueOf(p.top)),
        bottom = field(String.valueOf(p.bottom)),
        left = field(String.valueOf(p.left)),
        right = field(String.valueOf(p.right));
    if (showDialog(
        "Page margins (points)",
        BoxLayout.encloseY(
            new Label("Top"),
            top,
            new Label("Bottom"),
            bottom,
            new Label("Left"),
            left,
            new Label("Right"),
            right),
        "Apply",
        "Cancel")) {
      session.section(
          session
              .activeSection()
              .raw
              .page(
                  new PageSpec(
                      p.width,
                      p.height,
                      Float.parseFloat(top.getText()),
                      Float.parseFloat(right.getText()),
                      Float.parseFloat(bottom.getText()),
                      Float.parseFloat(left.getText()))));
      edited();
      ruler.repaint();
    }
  }

  private void showNavigation(String query) {
    if (query.trim().length() == 0) {
      outlineSignature = "!";
      updateOutline();
      return;
    }
    outline.removeAll();
    for (Map<String, Object> match : Commands.find(session.document(), query)) {
      int block = Commands.integer(match, "block"),
          start = Commands.integer(match, "start"),
          end = Commands.integer(match, "end");
      var result =
          button(
              Commands.matchedParagraph(session.document(), match).text.toString(),
              () -> {
                Commands.selectMatch(session, match);
                editor.revealSelection();
                editor.refresh();
              });
      result.setAlignment(Component.LEFT);
      outline.add(result);
    }
    if (outline.getComponentCount() == 0)
      outline.add(OfficeTheme.label("No results", 12, 0x777777));
    outline.revalidate();
  }

  private boolean showDialog(String title, String body, String ok, String cancel) {
    return showDialog(title, new SpanLabelCompat(body), ok, cancel);
  }

  private boolean showDialog(String title, Component body, String ok, String cancel) {
    if (editor != null) editor.stopInput();
    final boolean[] accepted = {false};
    var dialog = officeDialog(title);
    dialog.setLayout(new BorderLayout());
    styleDialogBody(body);
    Container content = BoxLayout.encloseY(body);
    content.setScrollableY(true);
    int width =
        Math.min(
            Math.max(body.getPreferredW(), OfficeTheme.px(300)),
            Display.getInstance().getDisplayWidth() - OfficeTheme.px(32));
    content.setPreferredW(width);
    content.setPreferredH(
        Math.min(body.getPreferredH(), Display.getInstance().getDisplayHeight() * 2 / 3));
    dialog.add(BorderLayout.CENTER, content);
    var actions = new Container(new FlowLayout(Component.RIGHT));
    if (cancel != null) {
      var back =
          new Command(cancel) {
            public void actionPerformed(com.codename1.ui.events.ActionEvent e) {
              dialog.dispose();
            }
          };
      var cancelButton = new Button(back);
      OfficeTheme.button(cancelButton);
      actions.add(cancelButton);
      dialog.setBackCommand(back);
    }
    var accept =
        new Command(ok) {
          public void actionPerformed(com.codename1.ui.events.ActionEvent e) {
            accepted[0] = true;
            dialog.dispose();
          }
        };
    acceptOpenDialog = () -> accept.actionPerformed(null);
    var acceptButton = new Button(accept);
    OfficeTheme.button(acceptButton);
    actions.add(acceptButton);
    dialog.setDefaultCommand(accept);
    dialog.add(BorderLayout.SOUTH, actions);
    styleDialogBody(dialog.getContentPane());
    try {
      showOfficeDialog(dialog);
    } finally {
      acceptOpenDialog = null;
    }
    return accepted[0];
  }

  private void setPage(float w, float h) {
    var p = session.activeSection().raw.page;
    session.section(
        session.activeSection().raw.page(new PageSpec(w, h, p.top, p.right, p.bottom, p.left)));
    edited();
  }

  private void sectionBreakDialog() {
    var type =
        new ComboBox<String>("Next page", "Continuous", "Odd page", "Even page");
    if (showDialog(
        "Insert section break", formRow("Start new section", type), "Insert", "Cancel")) {
      session.sectionBreak(
          new String[] {"nextPage", "continuous", "oddPage", "evenPage"}[type.getSelectedIndex()]);
      edited();
    }
  }

  private void sectionSettings() {
    var current = session.activeSection().raw;
    String[] types = {"nextPage", "continuous", "oddPage", "evenPage"};
    var start =
        new ComboBox<String>("Next page", "Continuous", "Odd page", "Even page");
    for (int i = 0; i < types.length; i++)
      if (types[i].equals(current.start)) start.setSelectedIndex(i);
    var number = field("" + current.pageStart);
    if (showDialog(
        "Section settings",
        BoxLayout.encloseY(
            formRow("Section start", start), formRow("Page numbering (0 continues)", number)),
        "Apply",
        "Cancel")) {
      session.section(
          current
              .start(types[start.getSelectedIndex()])
              .numbering(Integer.parseInt(number.getText())));
      edited();
    }
  }

  /**
   * A declarative dialog form. Each field is keyed by the command argument it fills, so the
   * accepted values go straight to {@code commands.execute} without per-dialog plumbing.
   */
  final class Fields {
    final Container body = new Container(BoxLayout.y());
    private final Map<String, java.util.function.Supplier<Object>> values = new LinkedHashMap<>();

    Fields text(String key, String label, String initial) {
      return text(key, label, field(initial));
    }

    Fields text(String key, String label, String initial, int rows, int columns) {
      return text(key, label, field(initial, rows, columns));
    }

    private Fields text(String key, String label, EditField f) {
      body.add(formRow(label, f));
      values.put(key, f::getText);
      return this;
    }

    Fields number(String key, String label, int initial) {
      var f = field(String.valueOf(initial));
      body.add(formRow(label, f));
      values.put(key, () -> Integer.parseInt(f.getText().trim()));
      return this;
    }

    Fields check(String key, String label, boolean selected) {
      var c = WordProcessor.this.check(label, selected);
      body.add(c);
      values.put(key, c::isSelected);
      return this;
    }

    /** A choice whose value is the selected item itself. */
    Fields choice(String key, String label, String[] items, String selected) {
      return choice(key, label, items, items, selected);
    }

    /** A choice showing {@code labels} and yielding the matching entry of {@code keys}. */
    Fields choice(String key, String label, String[] labels, String[] keys, String selected) {
      var box = new ComboBox<String>(labels);
      box.setSelectedIndex(Math.max(0, Arrays.asList(keys).indexOf(selected)));
      body.add(formRow(label, box));
      values.put(key, () -> keys[box.getSelectedIndex()]);
      return this;
    }

    /** A choice yielding the selected index as an Integer. */
    Fields index(String key, String label, String[] items, int selected) {
      var box = new ComboBox<String>(items);
      box.setSelectedIndex(selected);
      body.add(formRow(label, box));
      values.put(key, box::getSelectedIndex);
      return this;
    }

    int idx(String key) {
      return (Integer) get(key);
    }

    Fields note(String text) {
      body.add(OfficeTheme.label(text, 12, OfficeTheme.INK));
      return this;
    }

    Object get(String key) {
      return values.get(key).get();
    }

    String str(String key) {
      return (String) get(key);
    }

    boolean flag(String key) {
      return (Boolean) get(key);
    }

    /** Shows the form; the field values keyed by argument name, or null when cancelled. */
    Map<String, Object> ask(String title, String ok) {
      if (!showDialog(title, body, ok, "Cancel")) return null;
      var out = new LinkedHashMap<String, Object>();
      values.forEach((k, v) -> out.put(k, v.get()));
      return out;
    }
  }

  private Container formRow(String label, Component field) {
    field.getSemantics().setLabel(label);
    Label caption = OfficeTheme.label(label, 12, OfficeTheme.INK);
    if (OfficeTheme.mobile()) return BoxLayout.encloseY(caption, field);
    var row = new Container(new BorderLayout());
    caption.setPreferredW(OfficeTheme.px(170));
    row.add(BorderLayout.WEST, caption);
    row.add(BorderLayout.CENTER, field);
    OfficeTheme.space(row, 3, 3, 0, 0);
    return row;
  }

  private void fontDialog() {
    var style = session.activeStyle();
    var family =
        new ComboBox<String>("Source Sans 3", "Source Serif 4", "JetBrains Mono");
    family.setSelectedItem(DocumentFonts.family(style.font));
    var size = field(Float.toString(style.size));
    size.setAccessibilityText("Font size in points");
    var baseline = new ComboBox<String>("Normal", "Superscript", "Subscript");
    baseline.setSelectedIndex(
        "superscript".equals(style.script) ? 1 : "subscript".equals(style.script) ? 2 : 0);
    CheckBox bold = new CheckBox("Bold"),
        italic = new CheckBox("Italic"),
        underline = new CheckBox("Underline"),
        strike = new CheckBox("Strikethrough");
    bold.setSelected(style.bold);
    italic.setSelected(style.italic);
    underline.setSelected(style.underline);
    strike.setSelected(style.strike);
    String[] names = {
      "Double underline",
      "Double strikethrough",
      "Small caps",
      "All caps",
      "Hidden text",
      "Outline",
      "Shadow",
      "Emboss",
      "Engrave"
    };
    String[] effectKeys = {
      "doubleUnderline",
      "doubleStrike",
      "smallCaps",
      "allCaps",
      "hidden",
      "outline",
      "shadow",
      "emboss",
      "engrave"
    };
    CheckBox[] effects = new CheckBox[names.length];
    var effectsPanel = new Container(new GridLayout((names.length + 5) / 2, 2));
    effectsPanel.add(bold).add(italic).add(underline).add(strike);
    for (int i = 0; i < names.length; i++) {
      effects[i] = new CheckBox(names[i]);
      effects[i].setSelected(Boolean.parseBoolean(style.characters.value(effectKeys[i])));
      effectsPanel.add(effects[i]);
    }
    var underlineType =
        new ComboBox<String>(
            "None",
            "Single",
            "Words only",
            "Double",
            "Thick",
            "Dotted",
            "Heavy dotted",
            "Dashed",
            "Heavy dashed",
            "Long dash",
            "Heavy long dash",
            "Dash-dot",
            "Heavy dash-dot",
            "Dash-dot-dot",
            "Heavy dash-dot-dot",
            "Wavy",
            "Heavy wavy",
            "Double wavy");
    for (int i = 0; i < CharacterFormat.UNDERLINES.length; i++)
      if (CharacterFormat.UNDERLINES[i].equals(style.characters.underlineStyle))
        underlineType.setSelectedIndex(i);
    var underlineColor = field(style.characters.value("underlineColor"));
    underline.addActionListener(
        e -> {
          if (underline.isSelected()) {
            effects[0].setSelected(false);
            underlineType.setSelectedIndex(1);
          } else if (underlineType.getSelectedIndex() == 1) underlineType.setSelectedIndex(0);
        });
    effects[0].addActionListener(
        e -> {
          if (effects[0].isSelected()) {
            underline.setSelected(false);
            underlineType.setSelectedIndex(3);
          } else if (underlineType.getSelectedIndex() == 3) underlineType.setSelectedIndex(0);
        });
    underlineType.addActionListener(
        e -> {
          underline.setSelected(underlineType.getSelectedIndex() == 1);
          effects[0].setSelected(underlineType.getSelectedIndex() == 3);
        });
    EditField spacing = field("" + style.characters.spacing),
        stretch = field("" + style.characters.scale),
        position = field("" + style.characters.position);
    spacing.setAccessibilityText("Character spacing in points");
    stretch.setAccessibilityText("Character scale percent");
    position.setAccessibilityText("Character position in points");
    Container body =
        BoxLayout.encloseY(
            formRow("Font", family),
            formRow("Size (pt)", size),
            formRow("Baseline", baseline),
            effectsPanel,
            formRow("Underline style", underlineType),
            formRow("Underline color (hex or auto)", underlineColor),
            formRow("Spacing (pt)", spacing),
            formRow("Scale (%)", stretch),
            formRow("Position (pt)", position));
    body.setScrollableY(true);
    if (!showDialog("Font", body, "Apply", "Cancel")) return;
    session.transact(
        s -> {
          s.format("font", family.getSelectedItem());
          s.format("size", size.getText());
          s.format(
              "script",
              new String[] {"baseline", "superscript", "subscript"}[baseline.getSelectedIndex()]);
          s.format("bold", String.valueOf(bold.isSelected()));
          s.format("italic", String.valueOf(italic.isSelected()));
          s.format("underline", String.valueOf(underline.isSelected()));
          s.format("strike", String.valueOf(strike.isSelected()));
          for (int i = 0; i < effects.length; i++)
            s.format(effectKeys[i], String.valueOf(effects[i].isSelected()));
          s.format("underlineStyle", CharacterFormat.UNDERLINES[underlineType.getSelectedIndex()]);
          s.format("underlineColor", underlineColor.getText());
          s.format("characterSpacing", spacing.getText());
          s.format("characterScale", stretch.getText());
          s.format("characterPosition", position.getText());
        });
    edited();
  }

  private ComboBox<String> lineRuleChoice(Paragraph p) {
    var rule = new ComboBox<String>("Multiple", "At least", "Exactly");
    rule.setAccessibilityText("Line spacing rule");
    rule.setSelectedIndex(
        "exact".equals(p.format.lineRule) ? 2 : "atLeast".equals(p.format.lineRule) ? 1 : 0);
    return rule;
  }

  private String lineRuleValue(ComboBox<String> rule) {
    return new String[] {"auto", "atLeast", "exact"}[rule.getSelectedIndex()];
  }

  private void setLineSpacing(String rule, String value) {
    session.transact(
        s -> {
          s.paragraph("lineRule", rule);
          s.paragraph("spacing", value);
        });
    edited();
  }

  private Button toggleMarks() {
    var b = button("\u00b6", () -> run("view.marks", new HashMap<String, Object>()));
    b.setAccessibilityText("Show formatting marks");
    return b;
  }

  /** Word's Borders and Shading: one kind/style/width/color for the sides plus a fill. */
  private void bordersDialog() {
    if (!(session.editingDocument().blocks.get(session.selection().block) instanceof Paragraph))
      return;
    var p = session.editingDocument().paragraph(session.selection().block);
    var kind =
        new ComboBox<String>("outside", "top", "bottom", "left", "right", "none");
    var style = new ComboBox<String>(ParagraphBorders.STYLES);
    EditField width = field("0.5"), color = field("000000"), shading = field("");
    shading.setHint("RRGGBB or empty");
    shading.setText(
        p.format.shading < 0 ? "" : Integer.toHexString(0x1000000 | p.format.shading).substring(1));
    for (EditField f : new EditField[] {width, color, shading}) OfficeTheme.applyAppearance(f);
    Container fields =
        BoxLayout.encloseY(
            formRow("Borders", kind),
            formRow("Style", style),
            formRow("Width (pt)", width),
            formRow("Color", color),
            formRow("Shading", shading));
    if (!showDialog("Borders and shading", fields, "Apply", "Cancel")) return;
    session.transact(
        s -> {
          commands.execute(
              s,
              "para.borders",
              Json.object(
                  "kind", kind.getSelectedItem(),
                  "style", style.getSelectedItem(),
                  "width", Double.parseDouble(width.getText().trim()),
                  "color", color.getText().trim()));
          var fill = shading.getText().trim();
          commands.execute(
              s, "para.shading", Json.object("color", fill.isEmpty() ? "none" : fill));
        });
    edited();
  }

  private void lineSpacingDialog() {
    var p = session.editingDocument().paragraph(session.selection().block);
    if (p == null) return;
    var rule = lineRuleChoice(p);
    var value = field("" + p.lineSpacing);
    value.setAccessibilityText("Line spacing");
    var unit = new Label();
    Runnable update =
        () -> unit.setText(rule.getSelectedIndex() == 0 ? "Multiple of single line" : "Points");
    rule.addActionListener(e -> update.run());
    update.run();
    Container fields = BoxLayout.encloseY(formRow("Line spacing rule", rule), unit, value);
    if (showDialog("Line spacing", fields, "Apply", "Cancel"))
      setLineSpacing(lineRuleValue(rule), value.getText());
  }

  private void paragraphDialog() {
    if (!(session.editingDocument().blocks.get(session.selection().block) instanceof Paragraph))
      return;
    var p = session.editingDocument().paragraph(session.selection().block);
    String[] keys = {
      "indent", "rightIndent", "firstIndent", "before", "after", "spacing", "tabInterval"
    };
    String[] labels = {
      "Left indent (pt)",
      "Right indent (pt)",
      "First line / hanging (pt)",
      "Before (pt)",
      "After (pt)",
      "Line spacing",
      "Tab interval (pt)"
    };
    float[] values = {
      p.indent,
      p.format.rightIndent,
      p.format.firstIndent,
      p.before,
      p.after,
      p.lineSpacing,
      p.format.tabInterval
    };
    var body = new Container(BoxLayout.y());
    body.setScrollableY(true);
    EditField[] inputs = new EditField[keys.length];
    for (int i = 0; i < keys.length; i++) {
      inputs[i] = field(String.valueOf(values[i]));
      inputs[i].setAccessibilityText(labels[i]);
      body.add(formRow(labels[i], inputs[i]));
    }
    var lineRule = lineRuleChoice(p);
    body.add(formRow("Line spacing rule", lineRule));
    body.add(new Label("Multiple uses lines; At least / Exactly use points."));
    var alignment =
        new ComboBox<String>("Left", "Center", "Right", "Justified", "Distributed");
    String[] align = {"left", "center", "right", "both", "distribute"};
    for (int i = 0; i < align.length; i++)
      if (align[i].equals(p.align)) alignment.setSelectedIndex(i);
    body.add(alignment);
    CheckBox keep = new CheckBox("Keep lines together"),
        widow = new CheckBox("Widow / orphan control"),
        next = new CheckBox("Keep with next"),
        page = new CheckBox("Page break before");
    keep.setSelected(p.format.keepLines);
    widow.setSelected(p.format.widowControl);
    next.setSelected(p.keepNext);
    page.setSelected(p.pageBreak);
    body.addAll(keep, widow, next, page);
    final java.util.List<TabStop>[] tabs = new java.util.List[] {p.format.tabs};
    body.add(button("Tabs\u2026", () -> editTabs(tabs)));
    if (!showDialog("Paragraph", body, "Apply", "Cancel")) return;
    session.transact(
        s -> {
          for (int i = 0; i < keys.length; i++) s.paragraph(keys[i], inputs[i].getText());
          s.paragraph("lineRule", lineRuleValue(lineRule));
          s.paragraph("align", align[alignment.getSelectedIndex()]);
          s.paragraph("tabs", TabStop.encode(tabs[0]));
          s.paragraph("keepLines", String.valueOf(keep.isSelected()));
          s.paragraph("widowControl", String.valueOf(widow.isSelected()));
          s.paragraph("keepNext", String.valueOf(next.isSelected()));
          s.paragraph("pageBreak", String.valueOf(page.isSelected()));
        });
    edited();
  }

  private void editTabs(final java.util.List<TabStop>[] result) {
    var stops = new java.util.ArrayList<TabStop>(result[0]);
    var position = field("72");
    var alignment = new ComboBox<String>("left", "center", "right", "decimal", "bar");
    var leader =
        new ComboBox<String>("none", "dot", "hyphen", "underscore", "middleDot");
    var existing = new ComboBox<String>();
    Runnable reload =
        () -> {
          while (existing.getModel().getSize() > 0) existing.getModel().removeItem(0);
          for (TabStop stop : stops)
            existing
                .getModel()
                .addItem(stop.position + " pt \u00b7 " + stop.alignment + " \u00b7 " + stop.leader);
        };
    reload.run();
    existing.addActionListener(
        e -> {
          int i = existing.getSelectedIndex();
          if (i >= 0 && i < stops.size()) {
            var stop = stops.get(i);
            position.setText(String.valueOf(stop.position));
            alignment.setSelectedItem(stop.alignment);
            leader.setSelectedItem(stop.leader);
          }
        });
    if (!stops.isEmpty()) {
      var first = stops.get(0);
      position.setText(String.valueOf(first.position));
      alignment.setSelectedItem(first.alignment);
      leader.setSelectedItem(first.leader);
    }
    Container body =
        BoxLayout.encloseY(
            formRow("Tab stops", existing),
            formRow("Position (pt)", position),
            formRow("Alignment", alignment),
            formRow("Leader", leader));
    body.add(
        button(
            "Set",
            () -> {
              var stop =
                  new TabStop(
                      Float.parseFloat(position.getText()),
                      alignment.getSelectedItem(),
                      leader.getSelectedItem());
              for (int i = stops.size() - 1; i >= 0; i--)
                if (stops.get(i).position == stop.position) stops.remove(i);
              if (stops.size() >= 64) throw new IllegalArgumentException("At most 64 tab stops");
              stops.add(stop);
              var sorted = TabStop.normalized(stops);
              stops.clear();
              stops.addAll(sorted);
              reload.run();
              body.revalidate();
            }));
    body.add(
        button(
            "Clear selected",
            () -> {
              int i = existing.getSelectedIndex();
              if (i >= 0 && i < stops.size()) stops.remove(i);
              reload.run();
              body.revalidate();
            }));
    body.add(
        button(
            "Clear all",
            () -> {
              stops.clear();
              reload.run();
              body.revalidate();
            }));
    body.setScrollableY(true);
    if (OfficeTheme.mobile()) body.setPreferredH(Display.getInstance().getDisplayHeight() / 2);
    if (showDialog("Tabs", body, "OK", "Cancel")) result[0] = TabStop.normalized(stops);
  }

  private void paragraph(String key, String value) {
    run("paragraph.set", Json.object("property", key, "value", value));
  }

  private void format(String property) {
    var x = session.selection();
    var style = session.activeStyle();
    boolean current =
        "superscript".equals(property) || "subscript".equals(property)
            ? property.equals(style.script)
            : "bold".equals(property)
                ? style.bold
                : "italic".equals(property)
                    ? style.italic
                    : "underline".equals(property) ? style.hasUnderline() : style.strike;
    run("format.set", Json.object("property", property, "value", String.valueOf(!current)));
  }

  private void heading(int level) {
    session.applyStyle(level == 0 ? "Normal" : "Heading" + level);
    edited();
  }

  private interface TextValue {
    void accept(String value);
  }

  private void prompt(String name, String initial, TextValue callback) {
    editor.stopInput();
    var field = field(initial);
    field.setAccessibilityText(name);
    if (showDialog(name, field, "Apply", "Cancel")) safe(() -> callback.accept(field.getText()));
  }

  private Button button(String text, Runnable action) {
    var b = new Button(text);
    OfficeTheme.button(b);
    b.addActionListener(
        e -> {
          if (editor != null) {
            editor.finishComposing();
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
    edited(true);
  }

  private void edited(boolean resumeKeyboard) {
    var merge = session.mergeData();
    mergeStatus.setText(
        merge.rows.isEmpty()
            ? "No recipients"
            : (merge.preview ? "Preview " : "Recipient ")
                + (merge.record + 1)
                + " of "
                + merge.rows.size());
    lastEdit = System.currentTimeMillis();
    form.setTitle(session.document().title + (OfficeTheme.mobile() ? "" : " \u2014 CN1 Word Processor"));
    if (Display.getInstance().isDesktop()) Display.getInstance().refreshNativeTitle();
    editor.revealSelection();
    updateOutline();
    updateStatus();
    if (ruler != null) ruler.sync();
    if (verticalRuler != null) verticalRuler.sync();
    Display.getInstance()
        .callSerially(
            () -> {
              if (resumeKeyboard
                  && Display.getInstance().getCurrent() == form
                  && (!Desktop.isSupported() || !Desktop.getInstance().isWindowInputBlocked(0))) {
                editor.requestFocus();
                editor.resumeInput();
              }
            });
  }

  private void updateStatus() {
    if (menus != null) menus.sync();
    ribbon.setTabVisible("Table Layout", session.inCell());
    refreshRevisions();
    if (stylesPane != null
        && stylesPane.getParent() != null
        && displayedStyles != session.document().styles) refreshStylesPane();
    int words = statistics.words(session.document());
    status.setText(
        "Page "
            + editor.currentPage()
            + " of "
            + editor.layout().pages.size()
            + "    |    "
            + words
            + " words"
            + (editor.layout().warnings.isEmpty()
                ? ""
                : "    |    " + editor.layout().warnings.get(0))
            + (filePath != null && saved()
                ? "    |    Saved"
                : ""));
    if (trackingButton != null) {
      OfficeTheme.checked(trackingButton, session.document().trackChanges);
      trackingButton.setAccessibilityText(
          session.document().trackChanges ? "Track Changes on" : "Track Changes off");
    }
    syncingControls = true;
    // The indent markers follow the caret paragraph.
    if (ruler != null) ruler.sync();
    if (verticalRuler != null) verticalRuler.sync();
    if (language != null) {
      var tag = session.activeStyle().characters.lang;
      String name = tag == null || tag.isEmpty() ? "English (United States)" : languageName(tag);
      if (!name.equals(language.getText())) language.setText(name);
    }
    if (printView != null) {
      boolean read = editor.isViewOnly();
      OfficeTheme.checked(readView, read);
      OfficeTheme.checked(printView, !read && !editor.isReflow());
      OfficeTheme.checked(webView, !read && editor.isReflow());
    }
    if (zoomValue != null) zoomValue.setText(Math.round(editor.getZoom() * 100) + "%");
    if (zoomSlider != null) zoomSlider.setProgress(Math.round(editor.getZoom() * 100));
    var style = session.activeStyle();
    if (fontChoice != null) fontChoice.setSelectedItem(DocumentFonts.family(style.font));
    if (sizeChoice != null) {
      String size =
          style.size == Math.round(style.size)
              ? Integer.toString(Math.round(style.size))
              : Float.toString(style.size);
      boolean found = false;
      for (int i = 0; i < sizeChoice.getModel().getSize(); i++)
        if (size.equals(sizeChoice.getModel().getItemAt(i))) found = true;
      if (!found) sizeChoice.addItem(size);
      sizeChoice.setSelectedItem(size);
    }
    for (String property : formatButtons.keySet()) {
      boolean active =
          "superscript".equals(property) || "subscript".equals(property)
              ? property.equals(style.script)
              : "bold".equals(property)
                  ? style.bold
                  : "italic".equals(property)
                      ? style.italic
                      : "underline".equals(property) ? style.hasUnderline() : style.strike;
      var b = formatButtons.get(property);
      OfficeTheme.checked(b, active);
    }
    if (documentTitle != null) {
      var title =
          session.document().title
              + (filePath != null && saved()
                  ? " \u2014 Saved"
                  : filePath != null || session.canUndo() ? " \u2014 Edited" : " \u2014 Not saved")
              + " \u25be";
      if (!title.equals(documentTitle.getText())) {
        documentTitle.setText(title);
        documentTitle.getParent().revalidate();
      }
    }
    if (autoSave != null) autoSave.setOn(AppOptions.flag("autosave"));
    if (quickUndo != null) quickUndo.setEnabled(session.canUndo());
    if (quickRedo != null) quickRedo.setEnabled(session.canRedo());
    var selectedBlock = session.editingDocument().blocks.get(session.selection().block);
    String selectedStyle =
        selectedBlock instanceof Paragraph p ? p.style : "";
    for (Map.Entry<String, Container> entry : styleTiles.entrySet()) {
      boolean selected = entry.getKey().replace(" ", "").equals(selectedStyle.replace(" ", ""));
      var tile = entry.getValue();
      tile.getAllStyles().setBgColor(OfficeTheme.CHECKED);
      tile.getAllStyles().setBgTransparency(selected ? 255 : 0);
      tile.getAllStyles().setBorder(OfficeBorder.of(selected ? OfficeTheme.ACCENT : -1, 3));
    }
    syncingControls = false;
    ribbon.repaint();
  }

  private void updateOutline() {
    var signature = new StringBuilder();
    for (int i = 0; i < session.document().blocks.size(); i++) {
      var block = session.document().blocks.get(i);
      if (block instanceof Paragraph p) {
        if (p.style.startsWith("Heading") && p.text.length() > 0)
          signature.append(i).append(':').append(p.id).append(':').append(p.text).append('\n');
      }
    }
    if (outlineSignature.equals(signature.toString()) && outline.getComponentCount() > 0) return;
    outlineSignature = signature.toString();
    outline.removeAll();
    Label label = OfficeTheme.label("Headings", 12, OfficeTheme.BLUE);
    OfficeTheme.space(label, 0, 10, 0, 0);
    label.getAllStyles().setFgColor(0x748494);
    outline.add(label);
    for (int i = 0; i < session.document().blocks.size(); i++) {
      var block = session.document().blocks.get(i);
      if (block instanceof Paragraph p) {
        if (p.style.startsWith("Heading") && p.text.length() > 0) {
          final int index = i;
          var b =
              button(
                  p.text.toString(),
                  () -> {
                    session.leaveCell();
                    session.select(index, 0, 0);
                    editor.revealSelection();
                    editor.startInput();
                  });
          b.getAllStyles().setAlignment(Component.LEFT);
          outline.add(b);
        }
      }
    }
    if (outline.getComponentCount() == 1)
      outline.add(
          new SpanLabelCompat("Add headings from the Styles gallery to navigate your document."));
    outline.revalidate();
  }

  private void tableCommand(String command) {
    run("table." + command, Collections.<String, Object>emptyMap());
  }

  private Container tableRibbon() {
    return OfficeTheme.groups(
        OfficeTheme.group(
            "Select",
            BoxLayout.encloseY(
                OfficeTheme.row(
                    button("Select Cell", () -> tableCommand("selectCell")),
                    button("Select Row", () -> tableCommand("selectRow"))),
                OfficeTheme.row(
                    button("Select Column", () -> tableCommand("selectColumn")),
                    button("Select Table", () -> tableCommand("selectTable"))))),
        OfficeTheme.group(
            "Cells",
            BoxLayout.encloseY(
                button("Select range\u2026", this::selectCellRange),
                button("Clear Cell Contents", () -> tableCommand("clearCells")),
                button("Merge Selected Cells", () -> tableCommand("mergeSelected")))),
        OfficeTheme.group(
            "Split",
            BoxLayout.encloseY(
                button("Split Cells\u2026", this::splitCells),
                button("Split Table", () -> tableCommand("splitTable")))),
        OfficeTheme.group(
            "Insert",
            BoxLayout.encloseY(
                OfficeTheme.row(
                    button("Insert Above", () -> tableCommand("insertRowAbove")),
                    button("Insert Below", () -> tableCommand("insertRowBelow"))),
                OfficeTheme.row(
                    button("Insert Left", () -> tableCommand("insertColumnLeft")),
                    button("Insert Right", () -> tableCommand("insertColumnRight"))))),
        OfficeTheme.group(
            "Cell Size",
            BoxLayout.encloseY(
                button("Row height\u2026", this::tableRowHeight),
                button("Distribute Rows", () -> tableCommand("distributeRows")),
                button("Distribute Columns", () -> tableCommand("distributeColumns")))),
        OfficeTheme.group(
            "AutoFit",
            BoxLayout.encloseY(
                button(
                    "AutoFit Contents",
                    () -> run("table.autofit", Json.object("mode", "contents"))),
                button("AutoFit Window", () -> run("table.autofit", Json.object("mode", "window"))),
                button(
                    "Fixed Column Width",
                    () -> run("table.autofit", Json.object("mode", "fixed"))))),
        OfficeTheme.group("Alignment", cellAlignmentControls()),
        OfficeTheme.group(
            "Data",
            BoxLayout.encloseY(
                button("Formula\u2026", this::tableFormula),
                button(
                    "Update Formulas",
                    () -> {
                      editor.finishComposing();
                      session.mapFields(TableFormulas.resolver(session.document()));
                      edited();
                    }))),
        OfficeTheme.group(
            "Delete",
            BoxLayout.encloseY(
                button("Delete Selected Rows", () -> tableCommand("deleteRows")),
                button("Delete Selected Columns", () -> tableCommand("deleteColumns")))),
        OfficeTheme.group(
            "Table",
            BoxLayout.encloseY(
                button(
                    "Table properties\u2026",
                    () -> {
                      if (!session.inCell())
                        throw new IllegalArgumentException("Click inside a table first");
                      tableProperties(session.cellBlock(), session.cellRow(), session.cellColumn());
                    }),
                button("Rows and Columns\u2026", this::tableLayout))));
  }

  private void tableRowHeight() {
    if (!session.inCell()) throw new IllegalArgumentException("Click inside a table first");
    var row =
        ((Table) session.document().blocks.get(session.cellBlock())).format.row(session.cellRow());
    var height = field("" + (row.height == 0 ? 18 : row.height));
    var rule = new ComboBox<String>("At least", "Exactly");
    rule.setSelectedIndex("exact".equals(row.rule) ? 1 : 0);
    if (showDialog(
        "Row height",
        BoxLayout.encloseY(formRow("Row height (pt)", height), formRow("Row height rule", rule)),
        "Apply",
        "Cancel"))
      run(
          "table.rowHeight",
          Json.object(
              "height",
              Float.parseFloat(height.getText()),
              "rule",
              rule.getSelectedIndex() == 1 ? "exact" : "atLeast"));
  }

  private void tableFormula() {
    if (!session.inCell()) throw new IllegalArgumentException("Click inside a table first");
    var formula = field("=SUM(ABOVE)");
    Container body =
        BoxLayout.encloseY(
            formRow("Formula", formula),
            new SpanLabelCompat(
                "Use SUM, AVERAGE, COUNT, MIN, MAX or PRODUCT with ABOVE, BELOW, LEFT, RIGHT, or cell ranges such as A1:B3. Numbers use a decimal point."));
    if (showDialog("Table Formula", body, "Insert", "Cancel"))
      run("table.formula", Json.object("formula", formula.getText()));
  }

  private void splitCells() {
    if (!session.inCell()) throw new IllegalArgumentException("Click inside a table first");
    var columns = field("2");
    columns.setAccessibilityText("Number of split columns");
    if (showDialog("Split Cells", formRow("Number of columns", columns), "Split", "Cancel"))
      run("table.split", Json.object("columns", Integer.parseInt(columns.getText())));
  }

  private Container cellAlignmentControls() {
    var grid = new Container(new GridLayout(3, 3));
    String[] values = {
      "topLeft",
      "topCenter",
      "topRight",
      "centerLeft",
      "center",
      "centerRight",
      "bottomLeft",
      "bottomCenter",
      "bottomRight"
    };
    String[] names = {
      "Align Top Left",
      "Align Top Center",
      "Align Top Right",
      "Align Center Left",
      "Align Center",
      "Align Center Right",
      "Align Bottom Left",
      "Align Bottom Center",
      "Align Bottom Right"
    };
    for (int i = 0; i < values.length; i++) {
      final var value = values[i];
      final int alignment = i;
      var control =
          new Button() {
            public void paint(Graphics graphics) {
              super.paint(graphics);
              int side = OfficeTheme.px(20),
                  x = getX() + (getWidth() - side) / 2,
                  y = getY() + (getHeight() - side) / 2;
              graphics.setColor(0xb1b5ba);
              graphics.drawRect(x, y, side - 1, side - 1);
              graphics.setColor(OfficeTheme.INK);
              int unit = Math.max(1, side / 20);
              int top =
                  alignment / 3 == 0
                      ? 3 * unit
                      : alignment / 3 == 1 ? (side - 7 * unit) / 2 : side - 10 * unit;
              for (int line = 0; line < 3; line++) {
                int width = (line == 1 ? 8 : 12) * unit;
                int left =
                    alignment % 3 == 0
                        ? 3 * unit
                        : alignment % 3 == 1 ? (side - width) / 2 : side - 3 * unit - width;
                graphics.fillRect(x + left, y + top + 3 * unit * line, width, unit);
              }
            }
          };
      OfficeTheme.button(control);
      control.setPreferredW(OfficeTheme.px(28));
      control.setPreferredH(OfficeTheme.px(24));
      control.setAccessibilityText(names[i]);
      control.addActionListener(
          e -> {
            editor.finishComposing();
            run("table.cellAlign", Json.object("value", value));
          });
      grid.add(control);
    }
    return grid;
  }

  private void selectCellRange() {
    if (!session.inCell()) throw new IllegalArgumentException("Click inside a table first");
    var range = session.cellSelection();
    var firstRow = field("" + (range == null ? session.cellRow() + 1 : range.firstRow + 1));
    var firstColumn =
        field("" + (range == null ? session.cellColumn() + 1 : range.firstColumn + 1));
    var lastRow = field("" + (range == null ? session.cellRow() + 1 : range.lastRow + 1));
    var lastColumn =
        field("" + (range == null ? session.cellColumn() + 1 : range.lastColumn + 1));
    if (showDialog(
        "Select table cells",
        BoxLayout.encloseY(
            formRow("First row", firstRow), formRow("First column", firstColumn),
            formRow("Last row", lastRow), formRow("Last column", lastColumn)),
        "Select",
        "Cancel")) {
      session.selectCells(
          session.cellBlock(),
          Integer.parseInt(firstRow.getText()) - 1,
          Integer.parseInt(firstColumn.getText()) - 1,
          Integer.parseInt(lastRow.getText()) - 1,
          Integer.parseInt(lastColumn.getText()) - 1);
      edited();
    }
  }

  private void tableLayout() {
    if (!session.inCell()) {
      showDialog("Table layout", "Click inside a table first.", "OK", null);
      return;
    }
    editCell(session.cellBlock(), session.cellRow(), session.cellColumn());
  }

  private void editCell(int block, int row, int col) {
    editor.stopInput();
    var t = (Table) session.document().blocks.get(block);
    var dialog = officeDialog("Table \u00b7 row " + (row + 1) + ", column " + (col + 1));
    dialog.setLayout(new BorderLayout());
    var actions = new Container(new GridLayout(OfficeTheme.mobile() ? 2 : 3));
    for (String action : new String[] {"insertRow", "deleteRow", "insertColumn", "deleteColumn"}) {
      String label =
          "insertRow".equals(action)
              ? "Insert row below"
              : "deleteRow".equals(action)
                  ? "Delete row"
                  : "insertColumn".equals(action) ? "Insert column right" : "Delete column";
      actions.add(
          button(
              label,
              () -> {
                int index = action.endsWith("Row") ? row : col;
                if (action.startsWith("insert")) index++;
                session.tableStructure(block, action, index);
                dialog.dispose();
                edited();
              }));
    }
    actions.add(
        button(
            "Table properties\u2026",
            () -> {
              dialog.dispose();
              tableProperties(block, row, col);
            }));
    actions.add(
        button(
            "Merge cells\u2026",
            () -> {
              EditField rows = field("1"), columns = field("2");
              rows.setAccessibilityText("Rows to merge");
              columns.setAccessibilityText("Columns to merge");
              if (showDialog(
                  "Merge cells from this cell",
                  BoxLayout.encloseY(formRow("Rows", rows), formRow("Columns", columns)),
                  "Merge",
                  "Cancel")) {
                session.mergeCells(
                    block,
                    row,
                    col,
                    Integer.parseInt(rows.getText()),
                    Integer.parseInt(columns.getText()));
                dialog.dispose();
                edited();
              }
            }));
    if (t.span(row, col).rows > 1 || t.span(row, col).columns > 1)
      actions.add(
          button(
              "Split merged cell",
              () -> {
                session.splitCell(block, row, col);
                dialog.dispose();
                edited();
              }));
    actions.add(
        button(
            "Sort\u2026",
            () -> {
              var order = new ComboBox<String>("Ascending", "Descending");
              CheckBox header = new CheckBox("Keep first row as header"),
                  numeric = new CheckBox("Numeric values");
              if (showDialog(
                  "Sort column " + (col + 1),
                  BoxLayout.encloseY(order, header, numeric),
                  "Sort",
                  "Cancel")) {
                session.sortTable(
                    block,
                    col,
                    order.getSelectedIndex() == 1,
                    header.isSelected(),
                    numeric.isSelected());
                dialog.dispose();
                edited();
              }
            }));
    actions.add(
        button(
            "Convert to text",
            () -> {
              session.tableToText(block, "\t");
              dialog.dispose();
              edited();
            }));
    actions.add(
        button(
            "Delete table",
            () -> {
              session.deleteTable(block);
              dialog.dispose();
              edited();
            }));
    actions.add(button("Cancel", dialog::dispose));
    dialog.add(BorderLayout.CENTER, actions);
    styleDialogBody(dialog.getContentPane());
    showOfficeDialog(dialog);
  }

  private void tableProperties(int block, int row, int column) {
    var t = (Table) session.document().blocks.get(block);
    var selectedCells = session.cellSelection();
    var cell = t.format.cell(row, column);
    var size = t.format.sizing;
    EditField preferredWidth = field("" + size.width), tableIndent = field("" + size.indent);
    ComboBox<String> widthUnit = new ComboBox<String>("Percent", "Points", "Contents"),
        tableAlign = new ComboBox<String>("left", "center", "right");
    widthUnit.setSelectedItem(
        "percent".equals(size.unit)
            ? "Percent"
            : "points".equals(size.unit) ? "Points" : "Contents");
    tableAlign.setSelectedItem(size.align);
    EditField title = field(t.format.title), description = field(t.format.description, 3, 30);
    EditField width = field("" + (t.format.columnFraction(column) * 100)),
        height = field("" + t.format.minHeight(row)),
        background = field(TextUtil.padded(cell.background, 16, 6)),
        border = field(TextUtil.padded(cell.borderColor, 16, 6)),
        stroke = field("" + cell.borderWidth),
        horizontal = field("" + cell.horizontalPadding),
        vertical = field("" + cell.verticalPadding),
        headers = field("" + t.format.headerRows);
    var rowRule = new ComboBox<String>("Automatic", "At least", "Exactly");
    rowRule.setSelectedItem(
        "exact".equals(t.format.row(row).rule)
            ? "Exactly"
            : "atLeast".equals(t.format.row(row).rule) ? "At least" : "Automatic");
    var allowBreak = new CheckBox("Allow row to break across pages");
    allowBreak.setSelected(t.format.row(row).allowBreak);
    ComboBox<String> alignment = new ComboBox<String>("top", "center", "bottom"),
        scope =
            new ComboBox<String>(
                selectedCells == null ? "Selected cell" : "Selected cells", "Row", "Entire table");
    alignment.setSelectedItem(cell.vertical);
    Container body =
        BoxLayout.encloseY(
            formRow("Table width units", widthUnit),
            formRow("Preferred table width", preferredWidth),
            formRow("Table alignment", tableAlign),
            formRow("Table indent (pt; left alignment)", tableIndent),
            formRow("Apply cell appearance to", scope),
            formRow("Cell fill (hex)", background),
            formRow("Border color (hex)", border),
            formRow("Border width (pt; 0 hides)", stroke),
            formRow("Vertical alignment", alignment),
            formRow("Horizontal padding (pt)", horizontal),
            formRow("Vertical padding (pt)", vertical),
            formRow("Column width (%)", width),
            formRow("Row height rule", rowRule),
            formRow("Row height (pt)", height),
            allowBreak,
            formRow("Repeat first rows", headers),
            formRow("Table title", title),
            formRow("Table description", description));
    body.setScrollableY(true);
    if (OfficeTheme.mobile()) body.setPreferredH(Display.getInstance().getDisplayHeight() / 2);
    if (showDialog("Table properties", body, "Apply", "Cancel")) {
      var value =
          new TableFormat.Cell(
              Integer.parseInt(background.getText(), 16),
              Integer.parseInt(border.getText(), 16),
              Float.parseFloat(stroke.getText()),
              Float.parseFloat(horizontal.getText()),
              Float.parseFloat(vertical.getText()),
              alignment.getSelectedItem(),
              t.format.cell(row, column).sides);
      var format =
          t.format
              .columnFraction(column, Float.parseFloat(width.getText()) / 100)
              .row(
                  row,
                  new TableFormat.Row(
                      Float.parseFloat(height.getText()),
                      rowRule.getSelectedIndex() == 0
                          ? "auto"
                          : rowRule.getSelectedIndex() == 1 ? "atLeast" : "exact",
                      allowBreak.isSelected()))
              .headerRows(Integer.parseInt(headers.getText()))
              .altText(title.getText(), description.getText())
              .sizing(
                  new TableFormat.Sizing(
                      widthUnit.getSelectedIndex() == 0
                          ? "percent"
                          : widthUnit.getSelectedIndex() == 1 ? "points" : "auto",
                      widthUnit.getSelectedIndex() == 2
                          ? 0
                          : Float.parseFloat(preferredWidth.getText()),
                      tableAlign.getSelectedItem(),
                      Float.parseFloat(tableIndent.getText()),
                      widthUnit.getSelectedIndex() != 2 && size.fixed));
      int choice = scope.getSelectedIndex();
      for (int r = 0; r < t.rows(); r++)
        for (int c = 0; c < t.columns(); c++)
          if (choice == 2
              || choice == 1 && r == row
              || choice == 0
                  && (selectedCells == null
                      ? r == row && c == column
                      : selectedCells.contains(block, r, c))) format = format.cell(r, c, value);
      session.formatTable(block, format);
      edited();
    }
  }

  private void find() {
    EditField query = field(), replace = field();
    query.setHint("Find text");
    query.setAccessibilityText("Find text");
    replace.setHint("Replace with");
    replace.setAccessibilityText("Replace with");
    var find =
        button(
            "Find next",
            () -> {
              var matches =
                  Commands.find(session.document(), query.getText());
              if (matches.isEmpty()) showDialog("Find", "No matches", "OK", null);
              else {
                var match = matches.get(0);
                for (Map<String, Object> m : matches)
                  if (Commands.followsSelection(session, m)) {
                    match = m;
                    break;
                  }
                Commands.selectMatch(session, match);
                editor.revealSelection();
                editor.refresh();
              }
            });
    var all =
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
    var command = field("document.inspect");
    var args = field("{}", 5, 45);
    var available = new StringBuilder();
    for (Commands.Descriptor d : commands.descriptors())
      available.append(d.id).append(" \u2014 ").append(d.description).append('\n');
    if (showDialog(
        "Execute command",
        BoxLayout.encloseY(command, args, new SpanLabelCompat(available.toString())),
        "Run",
        "Cancel"))
      safe(
          () -> {
            try {
              var result =
                  commands.execute(session, command.getText(), Json.parse(args.getText()));
              edited();
              if (result != null) showText("Result", com.codename1.io.JSONParser.toJson(result));
            } catch (IOException ex) {
              throw new IllegalArgumentException(ex.getMessage());
            }
          });
  }

  private void macroDialog() {
    var area =
        field(
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
    var p =
        new Macros(commands).preview(session, macro, Collections.<String, String>emptyMap(), null);
    var after = field(join(p.changes) + "\n" + p.after, 12, 55);
    after.setEditable(false);
    if (showDialog("Proposed document \u2014 one undo step", after, "Apply", "Cancel")) {
      p.apply(session);
      edited();
    }
  }

  private void showText(String title, String text) {
    var area = field(text, 15, 60);
    area.setEditable(false);
    showDialog(title, area, "Close", null);
  }

  private long lastFileAutosave = System.currentTimeMillis();

  private void autosave() {
    long now = System.currentTimeMillis();
    if (AppOptions.flag("autosave") && filePath != null && !saving && !saved()
        && !session.isComposing() && now - lastEdit >= 1000
        && now - lastFileAutosave >= Integer.parseInt(AppOptions.get("autosaveMinutes")) * 60000L) {
      lastFileAutosave = now;
      OfficeCommands.snapshot(session, "AutoSave");
      save(fileFormat, false, true);
      return;
    }
    if (saving
        || pendingRecovery != null
        || session.isComposing()
        || session.revision() == savedRevision
        || System.currentTimeMillis() - lastEdit < 1000) return;
    final var snapshot = session.document();
    final long revision = session.revision();
    final var original = packageSource();
    final boolean protectedDocument = OfficeCommands.password(session) != null;
    saving = true;
    Display.getInstance()
        .scheduleBackgroundTask(
            () -> {
              String error = null;
              try {
                if (protectedDocument) recovery.forget();
                else recovery.save(snapshot, original, revision);
              } catch (IOException ex) {
                error = ex.getMessage();
              }
              final var problem = error;
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
    replaceAfterSaving(this::chooseDocument);
  }

  private void chooseDocument() {
    editor.stopInput();
    files.open(
        (name, path, bytes) ->
            safe(
                () -> {
                  try {
                    Document document;
                    String password = null;
                    if (name.toLowerCase().endsWith(".docx")) {
                      var plain = bytes;
                      if (OfficeCrypto.isEncrypted(bytes)) {
                        password = askPassword(name);
                        if (password == null) return;
                        plain = OfficeCrypto.decrypt(bytes, password);
                      }
                      Docx.Opened opened = Docx.read(new ByteArrayInputStream(plain));
                      if (!opened.warnings.isEmpty()
                          && !showDialog(
                              "Compatibility report",
                              new SpanLabelCompat(join(opened.warnings)),
                              "Open copy",
                              "Cancel")) return;
                      source = opened;
                      document = opened.document;
                    } else if (name.toLowerCase().endsWith(".doc")) {
                      // Word 97-2003 imports through DOCX; it is saved as a new file, never back to .doc.
                      Docx.Opened opened = DocBinary.read(bytes);
                      if (!opened.warnings.isEmpty()
                          && !showDialog(
                              "Compatibility report",
                              new SpanLabelCompat(join(opened.warnings)),
                              "Open copy",
                              "Cancel")) return;
                      source = opened;
                      document = opened.document.title(name);
                    } else if (name.toLowerCase().endsWith(".cn1doc")) {
                      ProjectFile.Opened project = ProjectFile.read(bytes);
                      document = project.document;
                      source = project.source;
                    } else {
                      var ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
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
                    forgetFile();
                    session.loadDocument(document);
                    // An encrypted original stays encrypted when saved back.
                    OfficeCommands.password(session, password);
                    fileDocument = document;
                    filePassword = password;
                    if (name.toLowerCase().endsWith(".cn1doc")
                        || name.toLowerCase().endsWith(".docx")) {
                      filePath = path;
                      fileFormat = name.toLowerCase().endsWith(".docx") ? "docx" : "cn1doc";
                      fileDocument = document;
                    }
                    editor.resetScroll();
                    edited();
                  } catch (IOException ex) {
                    throw new IllegalArgumentException(ex.getMessage());
                  }
                }));
  }

  private void save(String format) {
    save(format, true, false);
  }

  /** Prompts for an encrypted document's password; null when cancelled. */
  private String askPassword(String name) {
    var field = new TextField("", "Password", 20, TextArea.PASSWORD);
    field.setAccessibilityText("Password");
    return showDialog("\"" + name + "\" is protected", field, "Open", "Cancel") ? field.getText() : null;
  }

  private void save(String format, boolean asNew, boolean workingFile) {
    save(format, asNew, workingFile, null);
  }

  private boolean printing;

  private void printDocument() {
    if (printing) return;
    if (!Display.getInstance().isPrintingSupported()) {
      showDialog(
          "Print",
          "Printing is not available on this platform. Export a PDF to print elsewhere.",
          "OK",
          null);
      return;
    }
    editor.finishComposing();
    editor.stopInput();
    final var spec = session.document().page;
    final var pages = editor.printLayout();
    final var path =
        FileSystemStorage.getInstance().getAppHomePath()
            + "wordprocessor-print-"
            + System.currentTimeMillis()
            + ".pdf";
    printing = true;
    status.setText("Preparing print preview\u2026");
    Display.getInstance()
        .scheduleBackgroundTask(
            () -> {
              try {
                byte[] pdf = PdfExporter.write(spec, pages, DocumentFonts.PDF);
                try (var out = FileSystemStorage.getInstance().openOutputStream(path)) {
                  out.write(pdf);
                }
                Display.getInstance()
                    .callSerially(
                        () -> {
                          try {
                            Display.getInstance()
                                .print(
                                    path,
                                    "application/pdf",
                                    result -> {
                                      printing = false;
                                      FileSystemStorage.getInstance().delete(path);
                                      status.setText(
                                          result.isCancelled()
                                              ? "Print" + " cancelled"
                                              : result.isFailed()
                                                  ? "Print" + " failed:" + " " + result.getError()
                                                  : "Document sent to printing system");
                                    });
                          } catch (RuntimeException ex) {
                            printing = false;
                            FileSystemStorage.getInstance().delete(path);
                            showDialog("Print failed", ex.getMessage(), "OK", null);
                          }
                        });
              } catch (Exception ex) {
                FileSystemStorage.getInstance().delete(path);
                Display.getInstance()
                    .callSerially(
                        () -> {
                          printing = false;
                          showDialog("Print failed", ex.getMessage(), "OK", null);
                          updateStatus();
                        });
              }
            });
  }

  private void save(String format, boolean asNew, boolean workingFile, Runnable afterSave) {
    if (files == null) {
      showDialog("Save", "A platform file adapter has not been installed.", "OK", null);
      return;
    }
    editor.finishComposing();
    editor.stopInput();
    final var snapshot = session.document();
    final var pkg = packageSource();
    final int epoch = documentEpoch;
    safe(
        () -> {
          try {
            byte[] bytes;
            if ("docx".equals(format)) {
              boolean allow = false;
              var losses = Docx.conversionWarnings(snapshot, pkg);
              if (!losses.isEmpty()) {
                allow =
                    showDialog(
                        "Export converted copy",
                        new SpanLabelCompat(
                            "The following features may be lost from"
                                + " the edited copy:\n"
                                + join(losses)),
                        "Export copy",
                        "Cancel");
                if (!allow) return;
              }
              var out = new ByteArrayOutputStream();
              Docx.write(snapshot, pkg, out, allow);
              bytes = out.toByteArray();
              String password = OfficeCommands.password(session);
              if (password != null) bytes = OfficeCrypto.encrypt(bytes, password);
            } else if ("pdf".equals(format))
              bytes = PdfExporter.write(snapshot.page, editor.printLayout(), DocumentFonts.PDF);
            else if ("cn1doc".equals(format)) bytes = ProjectFile.write(snapshot, pkg);
            else {
              RichTextFormat type =
                  "md".equals(format)
                      ? RichTextFormat.MARKDOWN
                      : "rtf".equals(format)
                          ? RichTextFormat.RTF
                          : "html".equals(format) ? RichTextFormat.HTML : RichTextFormat.PLAIN_TEXT;
              if (type != RichTextFormat.HTML && type != RichTextFormat.PLAIN_TEXT)
                for (Block b : snapshot.blocks)
                  if (b instanceof Table)
                    throw new IOException(
                        "Use DOCX, HTML or native project format to retain tables");
              bytes = TextFormats.write(snapshot, type).getBytes("UTF-8");
            }
            final String writtenPassword = "docx".equals(format) ? OfficeCommands.password(session) : null;
            files.save(
                TextUtil.fileName(snapshot.title) + "." + format,
                !asNew && workingFile ? filePath : null,
                bytes,
                (ok, path, message) -> {
                  if (ok && workingFile && path != null && epoch == documentEpoch) {
                    filePath = path;
                    fileFormat = format;
                    fileDocument = snapshot;
                    filePassword = writtenPassword;
                    updateStatus();
                  }
                  status.setText(
                      ok
                          ? "Saved " + message
                          : message == null ? "Save cancelled" : "Save failed: " + message);
                  if (ok && afterSave != null && epoch == documentEpoch) {
                    if (session.document() == snapshot) afterSave.run();
                    else
                      showDialog(
                          "Document changed",
                          "The saved copy is complete. Save the newer changes before continuing.",
                          "OK",
                          null);
                  }
                });
          } catch (IOException ex) {
            throw new IllegalArgumentException(ex.getMessage());
          }
        });
  }

  private static String join(java.util.List<String> list) {
    var b = new StringBuilder();
    for (String s : list) b.append("\u2022 ").append(s).append('\n');
    return b.toString();
  }

  private void startAgentDebugging() {
    try {
      WordMcp.start(
          commands,
          new WordMcp.Context() {
            public Session session() {
              return session;
            }

            public Map<String, Object> layout() {
              return editor.inspectLayout();
            }

            public void beforeCommand() {
              if (Display.getInstance().getCurrent() != form
                  || Desktop.isSupported() && Desktop.getInstance().isWindowInputBlocked(0))
                throw new IllegalStateException(
                    "Close the active dialog before editing the document");
              // Programmatic commands own the selection. A live IME must not mark an
              // old word for recomposition between selection.set and text.insert.
              editor.stopInput();
            }

            public void changed() {
              edited(false);
            }
          });
    } catch (Exception ex) {
      Log.e(ex);
    }
  }

  /** The opened package to preserve on save, or null once the inspector sanitized the document. */
  private Docx.Opened packageSource() {
    return FileCommands.inspected(session) ? null : source;
  }

  public void stop() {
    WordMcp.stop();
    if (session != null)
      try {
        editor.finishComposing();
        // An offered recovery stays until the user opens or discards it. Otherwise only unsaved
        // work is kept: a saved or untouched document leaves nothing to recover next launch.
        if (pendingRecovery != null) return;
        if (OfficeCommands.password(session) != null || saved()) recovery.forget();
        else recovery.save(session.document(), packageSource(), session.revision());
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
      getTextAllStyles().setFgColor(0x303030);
    }
  }

  public static Document sample() {
    var s = new Session(Document.empty());
    s.replace(
        """
        A better place for words.
        A document engine built with Codename One
        Write, revise, and automate from one shared Java codebase. This canvas uses\
         a structured document model, cached paragraph layout, and paginated\
         rendering.
        Your ideas, beautifully structured
        Select text to format it. Use the Home tab for headings and alignment,\
         Insert for tables, and Layout to change the page. Every edit is undoable.
        Automation you can inspect
        Open Automate and preview a macro. Changes run on an isolated document;\
         applying them creates one undo step. If the document changes first, the\
         preview cannot overwrite it.
        Dear {{name}}, thank you for trying a different kind of document editor.
        A foundation with honest boundaries
        This development build supports an initial DOCX subset. It reports\
         unsupported content before conversion and preserves the original package\
         when exported without edits.""");
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
  private void outlineView(boolean on) {
    if (on == (editor.getOutline() != null)) return;
    editor.setOutline(on ? OutlineView.ALL : null);
    if (editor.isDraft() != on) {
      editor.setDraft(on);
      if (editor.isReflow() != on) editor.setReflow(on);
      zoomTo(editor.getZoom());
    }
  }

  /** Collapses or expands the heading that owns the caret (or every heading). */
  private void outlineCollapse(boolean all, boolean collapse) {
    outlineView(true);
    var d = session.document();
    var o = editor.getOutline();
    if (all) {
      for (int i = 0; i < d.blocks.size(); i++)
        if (OutlineView.hasChildren(d, i)) o = o.collapse(d.blocks.get(i).id(), collapse);
    } else {
      int at = Math.min(session.selection().block, d.blocks.size() - 1);
      while (at > 0
          && !(d.blocks.get(at) instanceof Paragraph
              && OutlineView.level(d, (Paragraph) d.blocks.get(at)) < OutlineView.BODY)) at--;
      if (OutlineView.hasChildren(d, at)) o = o.collapse(d.blocks.get(at).id(), collapse);
    }
    editor.setOutline(o);
  }


  /** Crops and downsamples a picture with the native codec; PNG stays PNG, others become JPEG 0.85. */
  static byte[] resample(
      byte[] data, String mime, float cl, float ct, float cr, float cb, int maxW, int maxH) {
    try {
      com.codename1.ui.Image img = com.codename1.ui.Image.createImage(data, 0, data.length);
      int w = img.getWidth(), h = img.getHeight();
      int x = Math.round(w * cl), y = Math.round(h * ct);
      int cw = Math.max(1, w - x - Math.round(w * cr)), ch = Math.max(1, h - y - Math.round(h * cb));
      if (x > 0 || y > 0 || cw < w || ch < h) img = img.subImage(x, y, cw, ch, true);
      if (cw > maxW || ch > maxH) {
        float f = Math.min(maxW / (float) cw, maxH / (float) ch);
        img = img.scaled(Math.max(1, Math.round(cw * f)), Math.max(1, Math.round(ch * f)));
      }
      boolean png = mime != null && mime.indexOf("png") >= 0;
      var out = new java.io.ByteArrayOutputStream();
      com.codename1.ui.util.ImageIO.getImageIO()
          .save(img, out, png ? com.codename1.ui.util.ImageIO.FORMAT_PNG : com.codename1.ui.util.ImageIO.FORMAT_JPEG, .85f);
      return out.toByteArray();
    } catch (java.io.IOException | RuntimeException e) {
      return null;
    }
  }
}
