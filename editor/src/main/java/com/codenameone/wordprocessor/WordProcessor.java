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
  private Label mergeStatus;
  private Container workspace, navigation, footer;
  private boolean backstage;
  private PageRuler ruler;
  private Slider zoomSlider;
  private Button quickUndo, quickRedo, documentTitle;
  private final Map<String, Runnable> uiActions = new LinkedHashMap<String, Runnable>();
  private final Map<String, Container> styleTiles = new LinkedHashMap<String, Container>();
  private OfficeRibbon ribbon;
  private TextStyle paintStyle;
  private Button painterButton;
  private ComboBox<String> fontChoice, sizeChoice;
  private final Map<String, Button> formatButtons = new HashMap<String, Button>();
  private boolean syncingControls;
  private RecoveryStore recovery;
  private long savedRevision = -1, lastEdit;
  private boolean saving;
  private Docx.Opened source;
  private Files files;
  private String filePath, fileFormat = "cn1doc";
  private Document fileDocument;
  private int documentEpoch;

  private void forgetFile() {
    filePath = null;
    fileFormat = "cn1doc";
    fileDocument = null;
    documentEpoch++;
  }

  private void saveDocument(boolean asNew) {
    if (!asNew) {
      save(fileFormat, false, true);
      return;
    }
    ComboBox<String> format =
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
    recovery = RecoveryStore.local();
  }

  public void start() {
    if (form != null) {
      form.show();
      startAgentDebugging();
      return;
    }
    Document recovered = recovery.recover();
    source = recovery.recoveredSource();
    session = new Session(recovered == null ? Document.empty().title("Document1") : recovered);
    if (recovered == null) fileDocument = session.document();
    form =
        new Form(new BorderLayout()) {
          @Override
          public String getTitle() {
            return session.document().title + (OfficeTheme.mobile() ? "" : " — CN1 Word Processor");
          }
        };
    form.setTitle(session.document().title + (OfficeTheme.mobile() ? "" : " — CN1 Word Processor"));
    if (Display.getInstance().isDesktop()) Display.getInstance().refreshNativeTitle();
    editor = new EditorSurface(session, commands, this::edited, this::editCell);
    editor.setPictureEditor(this::editPicture);
    registerViewCommands();
    ribbon = new OfficeRibbon();
    ribbon.addActionTab("File", this::showBackstage);
    ribbon.addTab("Home", home());
    ribbon.addTab("Insert", insert());
    ribbon.addTab("Layout", pageLayout());
    ribbon.addTab("References", references());
    ribbon.addTab("Mailings", mailings());
    ribbon.addTab("Review", review());
    ribbon.addTab("View", view());
    ribbon.addTab("Automate", automation());
    ribbon.addTab("Table Layout", tableRibbon());
    ribbon.setTabVisible("Table Layout", false);
    ribbon.select(0);
    if (OfficeTheme.mobile()) ribbon.toggleCollapsed();
    ribbon.actions(
        OfficeTheme.row(iconButton("Comments", FontImage.MATERIAL_COMMENT, this::comments, false)));
    if (Display.getInstance().isDesktop()) form.setToolbar(new OfficeTitleBar(titleBar()));
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
    EditField search = field();
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
    Container documentPane = new Container(new BorderLayout());
    ruler = new PageRuler(editor, session, this::edited);
    if (!OfficeTheme.mobile()) documentPane.add(BorderLayout.NORTH, ruler);
    documentPane.add(BorderLayout.CENTER, editor);
    workspace.add(BorderLayout.CENTER, documentPane);
    form.add(BorderLayout.CENTER, workspace);
    status = OfficeTheme.label("", 11, 0x555555);
    zoomValue = OfficeTheme.label("100%", 11, 0x444444);
    footer = new Container(new BorderLayout());
    OfficeTheme.surface(footer, 0xf7f7f7);
    OfficeTheme.space(footer, 3, 3, 12, 12);
    footer
        .getAllStyles()
        .setBorder(
            Border.createCompoundBorder(Border.createLineBorder(1, 0xd6d6d6), null, null, null));
    footer.add(BorderLayout.CENTER, status);
    zoomSlider = new Slider();
    zoomSlider.setEditable(true);
    zoomSlider.setAccessibilityText("Zoom");
    zoomSlider.setMinValue(35);
    zoomSlider.setMaxValue(250);
    zoomSlider.setProgress(100);
    zoomSlider.setPreferredW(OfficeTheme.px(115));
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
      s.setBgColor(OfficeTheme.BLUE);
      s.setBgTransparency(255);
      s.setBorder(Border.createEmpty());
    }
    Image thumb = Image.createImage(OfficeTheme.px(10), OfficeTheme.px(10), 0);
    Graphics tg = thumb.getGraphics();
    tg.setAntiAliased(true);
    tg.setColor(OfficeTheme.BLUE);
    tg.fillArc(0, 0, thumb.getWidth(), thumb.getHeight(), 0, 360);
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
            button("−", () -> zoom(-.1f)), zoomSlider, button("+", () -> zoom(.1f)), zoomValue));
    if (OfficeTheme.mobile()) {
      footer.removeAll();
      footer.add(BorderLayout.NORTH, status);
      quickUndo =
          iconButton("", FontImage.MATERIAL_UNDO, () -> run("edit.undo", Json.object()), false);
      quickRedo =
          iconButton("", FontImage.MATERIAL_REDO, () -> run("edit.redo", Json.object()), false);
      quickUndo.setAccessibilityText("Undo");
      quickRedo.setAccessibilityText("Redo");
      Container touchTools = new Container(new GridLayout(1, 7));
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
    installMenu();
    form.addSizeChangedListener(
        e ->
            Display.getInstance()
                .callSerially(
                    () -> {
                      configureMobileViewport();
                      editor.fitWidth();
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
    OfficeTheme.applyAppearance(form);
    OfficeTheme.applyPopupAppearance();
    form.show();
    // No appearance-change callback exists, so follow the OS setting with a cheap poll.
    new UITimer(
            () -> {
              boolean changed = OfficeTheme.refreshAppearance();
              if (!changed && !OfficeTheme.mapped()) return;
              // While mapped, also catch chrome created or restyled since the last pass.
              OfficeTheme.applyAppearance(form);
              if (changed) OfficeTheme.applyPopupAppearance();
              form.repaint();
            })
        .schedule(500, true, form);
    startAgentDebugging();
    editor.fitWidth();
    updateStatus();
    Display.getInstance()
        .callSerially(
            () -> {
              if (!OfficeTheme.mobile()) {
                editor.requestFocus();
                editor.startInput();
              }
            });
    new UITimer(() -> autosave()).schedule(1000, true, form);
  }

  private void configureMobileViewport() {
    if (!OfficeTheme.mobile()) return;
    com.codename1.ui.geom.Rectangle safe =
        Display.getInstance().getDisplaySafeArea(new com.codename1.ui.geom.Rectangle());
    form.getToolbar().setSafeArea(true);
    form.getToolbar().setPreferredH(OfficeTheme.px(44) + safe.getY());
    form.getContentPane().setSafeArea(true);
    if (Display.getInstance().getDisplayWidth() > Display.getInstance().getDisplayHeight())
      ribbon.collapse();
    form.revalidate();
  }

  private Container titleBar() {
    quickUndo =
        iconButton("", FontImage.MATERIAL_UNDO, () -> run("edit.undo", Json.object()), false);
    quickUndo.setAccessibilityText("Undo");
    quickRedo =
        iconButton("", FontImage.MATERIAL_REDO, () -> run("edit.redo", Json.object()), false);
    quickRedo.setAccessibilityText("Redo");
    Button save = iconButton("", FontImage.MATERIAL_SAVE, () -> saveDocument(false), false);
    save.setAccessibilityText("Save document");
    Container header =
        new Container(new BorderLayout(BorderLayout.CENTER_BEHAVIOR_CENTER_ABSOLUTE));
    OfficeTheme.space(header, 0, 0, 0, 0);
    header.add(BorderLayout.WEST, OfficeTheme.row(save, quickUndo, quickRedo));
    documentTitle = button(session.document().title + " ▾", this::rename);
    documentTitle.setAccessibilityText("Document name");
    documentTitle.getAllStyles().setFont(OfficeTheme.font(12, true));
    header.add(BorderLayout.CENTER, documentTitle);
    Button search =
        iconButton("Search commands", FontImage.MATERIAL_SEARCH, this::searchCommands, false);
    search.getAllStyles().setBorder(Border.createLineBorder(1, 0xd5d5d5));
    OfficeTheme.surface(search, 0xfafafa);
    header.add(BorderLayout.EAST, search);
    return header;
  }

  private void searchCommands() {
    editor.stopInput();
    Dialog dialog = officeDialog("Search commands");
    dialog.setLayout(new BorderLayout());
    EditField query = field();
    query.setHint("Find a command, such as table or heading");
    query.setAccessibilityText("Search commands");
    Container results = new Container(BoxLayout.y());
    results.setScrollableY(true);
    results.setPreferredH(OfficeTheme.px(320));
    results.setPreferredW(OfficeTheme.px(440));
    Runnable refresh =
        () -> {
          results.removeAll();
          String filter = query.getText().toLowerCase();
          for (Map.Entry<String, Runnable> action : uiActions.entrySet()) {
            if (!action.getKey().toLowerCase().contains(filter)) continue;
            Button choice =
                button(
                    action.getKey(),
                    () -> {
                      dialog.dispose();
                      Display.getInstance().callSerially(action.getValue());
                    });
            choice.setAlignment(Component.LEFT);
            results.add(choice);
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

  private void styleCombo(ComboBox<String> combo) {
    com.codename1.ui.list.DefaultListCellRenderer<String> renderer =
        new com.codename1.ui.list.DefaultListCellRenderer<String>(false);
    renderer.getAllStyles().setFont(OfficeTheme.font(12, false));
    renderer.getAllStyles().setFgColor(OfficeTheme.INK);
    renderer.getAllStyles().setBgImage(null);
    renderer.getAllStyles().setBorder(Border.createEmpty());
    renderer.getAllStyles().setBgTransparency(255);
    renderer.getUnselectedStyle().setBgColor(OfficeTheme.PAPER);
    renderer.getSelectedStyle().setBgColor(0xDCE9FB);
    renderer.getSelectedStyle().setFgColor(OfficeTheme.INK);
    Component focus = renderer.getListFocusComponent(combo);
    focus.getAllStyles().setBgImage(null);
    focus.getAllStyles().setBorder(Border.createEmpty());
    OfficeTheme.surface(focus, 0xDCE9FB);
    OfficeTheme.space(renderer, 2, 2, 4, 4);
    combo.setRenderer(renderer);
    Style arrow = new Style();
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
    TextStyle copied = paintStyle;
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
    ComboBox<String> choice =
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
    EditField field = new EditField(text);
    configureField(field, 220);
    return field;
  }

  private EditField field(String text, int rows, int columns) {
    EditField field = new EditField(text, rows, columns);
    configureField(field, Math.min(560, columns * 8));
    return field;
  }

  private void configureField(EditField field, int width) {
    OfficeTheme.field(field, width);
    field.addActionListener(
        e -> {
          if (OfficeTheme.mobile() && field.isSingleLineTextArea()) {
            field.finishComposing();
            Form owner = field.getComponentForm();
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
    Dialog dialog =
        new Dialog(title) {
          @Override
          protected void initNativeWindow(Window window) {
            OfficeTheme.surface(window, OfficeTheme.PAPER);
            OfficeTheme.surface(window.getContentPane(), OfficeTheme.PAPER);
          }
        };
    if (OfficeTheme.mobile()) dialog.setFormBottomPaddingEditingMode(true);
    // The shared light dialog surface must also override a native theme's title color.
    dialog.getTitleComponent().getAllStyles().setFgColor(OfficeTheme.INK);
    Container frame = dialog.getDialogComponent();
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
    } else if (body instanceof CheckBox) {
      body.getAllStyles().setFont(OfficeTheme.font(13, false));
      body.getAllStyles().setBgTransparency(0);
      ((CheckBox) body).setGap(OfficeTheme.px(8));
      body.setPreferredH(OfficeTheme.px(OfficeTheme.mobile() ? 44 : 28));
    }
    if (body instanceof Container) {
      OfficeTheme.surface(body, OfficeTheme.PAPER);
      Container container = (Container) body;
      for (int i = 0; i < container.getComponentCount(); i++)
        styleDialogBody(container.getComponentAt(i));
    }
  }

  private Button iconButton(String name, char icon, Runnable action, boolean large) {
    Button b = button(name, action);
    b.setAccessibilityText(name.isEmpty() ? iconName(icon) : name);
    OfficeTheme.icon(b, icon, large ? 27 : 16, OfficeTheme.INK);
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
    return "";
  }

  private Button toggle(String text, char icon, String property) {
    Button b = iconButton(text, icon, () -> format(property), false);
    b.setText("");
    b.setAccessibilityText(text);
    b.setPreferredW(OfficeTheme.px(30));
    formatButtons.put(property, b);
    return b;
  }

  private Button scriptButton(String label, String name, String value) {
    Button b =
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

  private Container home() {
    painterButton =
        iconButton(
            "Format Painter",
            FontImage.MATERIAL_FORMAT_PAINT,
            () -> {
              paintStyle = paintStyle == null ? session.activeStyle() : null;
              painterButton.getUnselectedStyle().setBgTransparency(paintStyle == null ? 0 : 255);
              painterButton.getUnselectedStyle().setBgColor(0xdce8fa);
              status.setText(
                  paintStyle == null
                      ? "Format Painter cancelled"
                      : "Select text to apply the copied formatting");
              editor.startInput();
            },
            false);
    editor.addPointerReleasedListener(e -> applyPaintStyle());
    Container clipboard =
        OfficeTheme.row(
            iconButton("Paste", FontImage.MATERIAL_CONTENT_PASTE, () -> clipboard("paste"), true),
            BoxLayout.encloseY(
                iconButton("Cut", FontImage.MATERIAL_CONTENT_CUT, () -> clipboard("cut"), false),
                iconButton("Copy", FontImage.MATERIAL_CONTENT_COPY, () -> clipboard("copy"), false),
                painterButton));
    fontChoice = new ComboBox<String>("Source Sans 3", "Source Serif 4", "JetBrains Mono");
    OfficeTheme.field(fontChoice, 122);
    styleCombo(fontChoice);
    sizeChoice =
        new ComboBox<String>(
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
            button("A−", () -> changeSize(-1)),
            button("Aa ▾", this::changeCase),
            iconButton("", FontImage.MATERIAL_FORMAT_CLEAR, this::clearFormatting, false));
    Container fontBottom =
        OfficeTheme.row(
            toggle("Bold", FontImage.MATERIAL_FORMAT_BOLD, "bold"),
            toggle("Italic", FontImage.MATERIAL_FORMAT_ITALIC, "italic"),
            toggle("Underline", FontImage.MATERIAL_FORMAT_UNDERLINED, "underline"),
            toggle("Strikethrough", FontImage.MATERIAL_STRIKETHROUGH_S, "strike"),
            scriptButton("x²", "Superscript", "superscript"),
            scriptButton("x₂", "Subscript", "subscript"),
            iconButton("", FontImage.MATERIAL_FORMAT_COLOR_FILL, () -> color("background"), false),
            iconButton("", FontImage.MATERIAL_FORMAT_COLOR_TEXT, () -> color("color"), false));
    Container paragraphTop =
        OfficeTheme.row(
            iconButton("", FontImage.MATERIAL_FORMAT_LIST_BULLETED, () -> list("bullet"), false),
            iconButton("", FontImage.MATERIAL_FORMAT_LIST_NUMBERED, () -> list("number"), false),
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
            button("¶…", this::paragraphDialog),
            iconButton("", FontImage.MATERIAL_FORMAT_LINE_SPACING, this::lineSpacingDialog, false));
    Container styles =
        OfficeTheme.row(
            styleTile("Normal", 0, 15),
            styleTile("No Spacing", -1, 15),
            styleTile("Heading 1", 1, 19),
            styleTile("Heading 2", 2, 17),
            styleTile("Title", 3, 23),
            styleTile("Subtitle", 4, 16));
    styles.setPreferredW(OfficeTheme.px(180));
    styles.setScrollableX(true);
    styles.setScrollVisible(false);
    Button moreStyles = button("▾", this::moreStyles);
    moreStyles.setAccessibilityText("More styles");
    Container editing =
        BoxLayout.encloseY(
            iconButton("Find", FontImage.MATERIAL_SEARCH, this::find, false),
            iconButton("Replace", FontImage.MATERIAL_FIND_REPLACE, this::find, false),
            button(
                "Select all",
                () -> {
                  editor.selectAll();
                  editor.startInput();
                }));
    return OfficeTheme.groups(
        OfficeTheme.group("Clipboard", clipboard),
        OfficeTheme.group("Font", BoxLayout.encloseY(fontTop, fontBottom), this::fontDialog),
        OfficeTheme.group("Paragraph", BoxLayout.encloseY(paragraphTop, paragraphBottom)),
        OfficeTheme.group("Styles", OfficeTheme.row(styles, moreStyles)),
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
    Container pane = new Container(new BorderLayout());
    OfficeTheme.surface(pane, 0xfafafa);
    OfficeTheme.space(pane, 10, 10, 10, 10);
    ComboBox<String> names = new ComboBox<String>();
    java.util.List<ParagraphStyle> definitions =
        new ArrayList<ParagraphStyle>(session.document().styles.values());
    String current = session.editingDocument().paragraph(session.selection().block).style;
    for (ParagraphStyle definition : definitions) {
      names.addItem(definition.name);
      if (current.equals(definition.id)) names.setSelectedIndex(names.getModel().getSize() - 1);
    }
    Label preview = OfficeTheme.label("AaBbCcDd", 24, OfficeTheme.INK);
    Runnable updatePreview =
        () -> {
          if (names.getSelectedIndex() < 0 || names.getSelectedIndex() >= definitions.size())
            return;
          ParagraphStyle style =
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
                "New style from selection…",
                () -> {
                  editStyle(null);
                  refreshStylesPane();
                }),
            button(
                "Modify…",
                () -> {
                  editStyle(
                      session.document().styles.get(definitions.get(names.getSelectedIndex()).id));
                  refreshStylesPane();
                }),
            button(
                "Update to match selection",
                () -> {
                  ParagraphStyle selected = definitions.get(names.getSelectedIndex());
                  Paragraph paragraph =
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
                  Paragraph paragraph =
                      session.editingDocument().paragraph(session.selection().block);
                  TextStyle text = session.activeStyle();
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
                  ParagraphStyle selected = definitions.get(names.getSelectedIndex());
                  if (ParagraphStyle.defaults().containsKey(selected.id)) {
                    showDialog(
                        "Built-in style",
                        "Built-in styles can be modified but cannot be" + " deleted.",
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
      Dialog dialog = officeDialog("Styles");
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
    Paragraph active = session.editingDocument().paragraph(session.selection().block);
    Paragraph p = existing == null ? active : existing.paragraph;
    TextStyle text = existing == null ? session.activeStyle() : existing.text;
    EditField name = field(existing == null ? "New style" : existing.name),
        size = field("" + text.size),
        color = field(TextUtil.padded(text.color, 16, 6)),
        before = field("" + p.before),
        after = field("" + p.after),
        spacing = field("" + p.lineSpacing);
    ComboBox<String> lineRule = lineRuleChoice(p);
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
      TextStyle next =
          text.with("font", font.getSelectedItem())
              .with("size", size.getText())
              .with("color", color.getText())
              .with("bold", "" + bold.isSelected())
              .with("italic", "" + italic.isSelected());
      Paragraph template =
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
    Block b = session.document().blocks.get(session.document().blocks.size() - 1);
    return b instanceof Paragraph ? ((Paragraph) b).text.length() : 0;
  }

  private Container styleTile(String name, int level, int size) {
    Button preview = button("AaBbCcDd", () -> applyStyle(name, level));
    preview.setAccessibilityText(name);
    OfficeTheme.space(preview, 5, 5, 6, 6);
    preview.getAllStyles().setFont(OfficeTheme.font(size, level == 1 || level == 2));
    preview.getAllStyles().setFgColor(level == 1 || level == 2 ? 0x185568 : OfficeTheme.INK);
    Label caption = OfficeTheme.label(name, 11, 0x444444);
    caption.setAlignment(Component.CENTER);
    Container c = BoxLayout.encloseY(preview, caption);
    OfficeTheme.space(c, 4, 4, 3, 3);
    c.setLeadComponent(preview);
    c.setPreferredW(OfficeTheme.px(86));
    c.setPreferredH(OfficeTheme.px(61));
    c.getAllStyles().setBorder(Border.createLineBorder(1, 0xe0e0e0));
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
                    button("Quick Tables…", this::quickTables),
                    button("Table layout…", this::tableLayout)))),
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
                    button("Header…", () -> editFurniture(false)),
                    button("Footer…", () -> editFurniture(true))))),
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
                    button("Edit field…", () -> fieldDialog(true, "PAGE")),
                    button(
                        "Update fields",
                        () -> {
                          updateFields();
                          edited();
                        })))));
  }

  private void editFurniture(boolean footerPart) {
    final Section.Range active = session.activeSection();
    final PageFurniture original = active.resolved.furniture;
    final PageFurniture[] draft = {active.raw.furniture};
    PageFurniture previous = PageFurniture.EMPTY;
    for (Section.Range range : session.document().sectionRanges()) {
      if (range.first == active.first) break;
      previous = range.resolved.furniture;
    }
    final PageFurniture inherited = previous;
    final String[] current = {footerPart ? "footer" : "header"};
    ComboBox<String> variant = new ComboBox<String>("Default", "First page", "Even pages");
    CheckBox linked = new CheckBox("Link to previous section");
    linked.setEnabled(active.first > 0);
    linked.setSelected(active.first > 0 && !draft[0].stories.containsKey(current[0]));
    CheckBox first = new CheckBox("Different first page"),
        even = new CheckBox("Different odd and even pages");
    first.setSelected(original.differentFirst);
    even.setSelected(original.differentEven);
    EditField headerDistance = field("" + original.headerDistance),
        footerDistance = field("" + original.footerDistance);
    Sequence<Block> blocks = new Sequence<Block>();
    for (Paragraph para : original.get(current[0])) blocks = blocks.append(para);
    if (blocks.size() == 0)
      blocks = blocks.append(new Paragraph("part1", new RichText()).with("after", "0"));
    final Session part =
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
    final EditorSurface view = new EditorSurface(part, new Commands(), () -> {}, (b, r, c) -> {});
    view.setPreferredW(OfficeTheme.px(520));
    view.setPreferredH(OfficeTheme.px(200));
    view.setReflow(true);
    linked.addActionListener(
        e -> {
          if (linked.isSelected()) {
            view.stopInput();
            Sequence<Block> linkedBlocks = new Sequence<Block>();
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
          Sequence<Paragraph> paragraphs = new Sequence<Paragraph>();
          for (Block b : part.document().blocks)
            if (b instanceof Paragraph) paragraphs = paragraphs.append((Paragraph) b);
          if (linked.isSelected()) {
            Sequence<Paragraph> base = inherited.get(current[0]);
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
          Sequence<Block> next = new Sequence<Block>();
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
    boolean apply = showDialog(footerPart ? "Footer" : "Header", body, "Apply", "Cancel");
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
    Block block = session.editingDocument().blocks.get(session.selection().block);
    if (!(block instanceof Paragraph)) return;
    Paragraph paragraph = (Paragraph) block;
    int caret = session.selection().caret;
    int at = paragraph.text.fieldAt(caret) != null ? caret : caret - 1;
    InlineField existing = edit && at >= 0 ? paragraph.text.fieldAt(at) : null;
    if (edit && existing == null) {
      showDialog("Edit field", "Place the caret next to a field first.", "OK", null);
      return;
    }
    ComboBox<String> type =
        new ComboBox<String>(
            "PAGE", "NUMPAGES", "TITLE", "DATE", "TIME", "FILENAME", "MERGEFIELD", "DOCPROPERTY");
    EditField code = field(existing == null ? initial : existing.instruction);
    EditField result = field(existing == null ? "" : existing.result);
    CheckBox locked = new CheckBox("Lock field"), showCode = new CheckBox("Show field code");
    locked.setSelected(existing != null && existing.locked);
    showCode.setSelected(existing != null && existing.showCode);
    java.util.List<String> tokens = DocumentFields.tokens(code.getText());
    if (!tokens.isEmpty()) type.setSelectedItem(tokens.get(0));
    type.addActionListener(
        e -> {
          String selected = type.getSelectedItem();
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
    final InlineField value =
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
    final java.util.Date now = new java.util.Date();
    final java.util.Map<String, String> values = new java.util.HashMap<String, String>();
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
            Document before = s.document();
            Session.FieldResolver resolver =
                DocumentFields.resolver(before, editor.printLayout(), values);
            s.mapFields(
                (paragraph, offset, value) -> {
                  if (value.locked) return value;
                  java.util.List<String> tokens = DocumentFields.tokens(value.instruction);
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
    Picture pic = (Picture) session.document().blocks.get(block);
    EditField width = field(Float.toString(pic.width)),
        height = field(Float.toString(pic.height)),
        alt = field(pic.alt);
    CheckBox aspect = new CheckBox("Keep proportions");
    aspect.setSelected(true);
    ComboBox<String> align = new ComboBox<String>("left", "center", "right");
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
                    Form current = Display.getInstance().getCurrent();
                    if (current instanceof Dialog) ((Dialog) current).dispose();
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
    ComboBox<String> template = new ComboBox<String>("Tabular list", "Matrix", "Calendar");
    template.setAccessibilityText("Table template");
    String[] kinds = {"tabular", "matrix", "calendar"};
    Container preview = new Container(new BorderLayout());
    Runnable update =
        () -> {
          Table table = TableTemplates.create(kinds[template.getSelectedIndex()], 1);
          Container grid = new Container(new GridLayout(table.rows(), table.columns()));
          for (int r = 0; r < table.rows(); r++)
            for (int c = 0; c < table.columns(); c++) {
              Label cell = new Label(Table.cellText(table.cell(r, c)));
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
                "Editable tables with a repeating header row. Calendar is a"
                    + " reusable 31-day sample.")),
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
                    button("Section break…", this::sectionBreakDialog),
                    button("Section settings…", this::sectionSettings)))),
        OfficeTheme.group(
            "Paragraph",
            BoxLayout.encloseY(
                OfficeTheme.row(
                    button("Indent +", () -> indent(18)), button("Indent −", () -> indent(-18))),
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
    java.util.List<Note> notes = Notes.all(editor.printLayout(session.document()).document);
    if (notes.isEmpty()) {
      showDialog("Notes", new Label("This document has no notes."), "Close", "Close");
      return;
    }
    session.nextNote(false);
    editor.revealSelection();
    editor.refresh();
  }

  private void showNotes() {
    java.util.List<Note> notes = Notes.all(editor.printLayout(session.document()).document);
    if (notes.isEmpty()) {
      showDialog("Notes", new Label("This document has no notes."), "Close", "Close");
      return;
    }
    String[] labels = new String[notes.size()];
    for (int i = 0; i < labels.length; i++) {
      Note note = notes.get(i);
      String preview = note.plainText().replace('\n', ' ');
      if (preview.length() > 36) preview = preview.substring(0, 36) + "…";
      labels[i] =
          ("footnote".equals(note.kind) ? "Footnote " : "Endnote ") + note.label + ": " + preview;
    }
    ComboBox<String> chosen = new ComboBox<String>(labels);
    ComboBox<String> action = new ComboBox<String>("Edit", "Go to reference", "Delete");
    if (!showDialog(
        "Notes",
        BoxLayout.encloseY(formRow("Note", chosen), formRow("Action", action)),
        "Continue",
        "Cancel")) return;
    Note note = notes.get(chosen.getSelectedIndex());
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
    ComboBox<String> type = new ComboBox<String>("Footnotes", "Endnotes");
    ComboBox<String> scope = new ComboBox<String>("Whole document", "Selected sections");
    ComboBox<String> format =
        new ComboBox<String>(
            "1, 2, 3",
            "i, ii, iii",
            "I, II, III",
            "a, b, c",
            "A, B, C",
            "01, 02, 03",
            "1st, 2nd, 3rd",
            "1, 2, … A, B",
            "*, †, ‡, §",
            "- 1 -, - 2 -",
            "No number");
    EditField start = field("1");
    start.setConstraint(TextArea.NUMERIC);
    ComboBox<String> restart = new ComboBox<String>();
    ComboBox<String> position = new ComboBox<String>();
    Runnable load =
        () -> {
          boolean foot = type.getSelectedIndex() == 0;
          NoteOptions.Properties p =
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
    ComboBox<String> type = new ComboBox<String>("Footnote", "Endnote");
    type.setSelectedIndex("endnote".equals(kind) ? 1 : 0);
    EditField mark = field(original == null ? "" : original.mark);
    Sequence<Block> blocks = new Sequence<Block>();
    if (original != null) for (Paragraph p : original.paragraphs) blocks = blocks.append(p);
    if (blocks.size() == 0)
      blocks =
          blocks.append(
              new Paragraph(
                      "p" + session.document().nextId,
                      RichText.of("", TextStyle.DEFAULT.with("size", "10")))
                  .with("after", "0")
                  .with("spacing", "1"));
    Session draft =
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
    EditorSurface view = new EditorSurface(draft, new Commands(), () -> {}, (b, r, c) -> {});
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
    Sequence<Paragraph> paragraphs = new Sequence<Paragraph>();
    for (Block b : draft.document().blocks) paragraphs = paragraphs.append((Paragraph) b);
    final Sequence<Paragraph> story = paragraphs;
    String selectedKind = type.getSelectedIndex() == 0 ? "footnote" : "endnote";
    session.transact(
        s -> {
          if (original == null) {
            s.insertNote(selectedKind, "", mark.getText());
            Selection selection = s.selection();
            Note inserted =
                s.editingDocument().paragraph(selection.block).text.noteAt(selection.caret - 1);
            // Allocate fresh story identities after the reference insertion to avoid ID
            // collisions.
            Sequence<Paragraph> owned = new Sequence<Paragraph>();
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
                button("Insert field…", () -> fieldDialog(false, "PAGE")),
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
    ComboBox<String> size = new ComboBox<String>(MailingSheets.ENVELOPES);
    EditField
        delivery =
            field(
                session.mergeData().rows.isEmpty()
                    ? ""
                    : session.mergeData().address(session.mergeData().record),
                5,
                40),
        returned = field("", 3, 40);
    CheckBox add = check("Add before the current document", true);
    if (showDialog(
        "Envelopes",
        BoxLayout.encloseY(
            formRow("Envelope size", size),
            formRow("Delivery address", delivery),
            formRow("Return address", returned),
            add),
        "Create",
        "Cancel")) {
      mailing(
          "envelopes",
          Json.object(
              "delivery",
              delivery.getText(),
              "return",
              returned.getText(),
              "size",
              size.getSelectedItem(),
              "add",
              add.isSelected()));
      if (!add.isSelected()) {
        forgetFile();
        source = null;
      }
    }
  }

  private void labelsDialog() {
    ComboBox<String> product = new ComboBox<String>(MailingSheets.PRODUCTS),
        mode =
            new ComboBox<String>(
                "Full sheet of the same label", "One recipient per label", "Single label");
    EditField text = field("", 5, 40), row = field("1"), column = field("1");
    if (showDialog(
        "Labels",
        BoxLayout.encloseY(
            formRow("Label product", product),
            formRow("Contents", mode),
            OfficeTheme.label(
                "Recipient labels continue onto additional sheets automatically.",
                12,
                OfficeTheme.INK),
            formRow("Label text", text),
            formRow("Label row", row),
            formRow("Label column", column)),
        "Create",
        "Cancel")) {
      Map<String, Object> args =
          Json.object(
              "product",
              product.getSelectedItem(),
              "text",
              text.getText(),
              "fromRecipients",
              mode.getSelectedIndex() == 1);
      if (mode.getSelectedIndex() == 2)
        args.put(
            "single",
            Json.object(
                "row", Integer.parseInt(row.getText()), "col", Integer.parseInt(column.getText())));
      mailing("labels", args);
      forgetFile();
      source = null;
    }
  }

  private void mergeStartDialog() {
    ComboBox<String> type =
        new ComboBox<String>("Letters", "Directory", "Envelopes", "Labels", "Emails", "Normal");
    String current = session.mergeData().kind;
    for (int i = 0; i < type.getModel().getSize(); i++)
      if (type.getModel().getItemAt(i).equalsIgnoreCase(current)) type.setSelectedIndex(i);
    if (showDialog("Start mail merge", formRow("Document type", type), "Apply", "Cancel"))
      mailing("start", Json.object("kind", type.getSelectedItem().toLowerCase()));
  }

  private void recipientsDialog() {
    ComboBox<String> method =
        new ComboBox<String>("Type or paste a list", "Open CSV, TSV or spreadsheet");
    if (!showDialog("Select recipients", formRow("Source", method), "Continue", "Cancel")) return;
    if (method.getSelectedIndex() == 0) {
      editRecipientSourceDialog();
      return;
    }
    if (files == null) throw new IllegalArgumentException("File picker is unavailable");
    files.openRecipients(
        (name, path, bytes) -> {
          try {
            if (bytes.length > 2 && bytes[0] == 'P' && bytes[1] == 'K') {
              RecipientWorkbook workbook = new RecipientWorkbook(bytes);
              ComboBox<String> sheets =
                  new ComboBox<String>(
                      workbook.sheets().toArray(new String[workbook.sheets().size()]));
              CheckBox headers = check("First row contains field names", true);
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
            String text = new String(bytes, encoding);
            CheckBox headers = check("First row contains field names", true);
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
    final MergeData data = session.mergeData();
    if (data.fields.isEmpty()) {
      editRecipientSourceDialog();
      return;
    }
    java.util.List<String> names = new ArrayList<String>();
    names.add("(None)");
    names.addAll(data.fields);
    ComboBox<String> sort = new ComboBox<String>(names.toArray(new String[names.size()]));
    ComboBox<String> filter = new ComboBox<String>(names.toArray(new String[names.size()]));
    sort.setSelectedIndex(data.column(data.sortField) + 1);
    filter.setSelectedIndex(data.column(data.filterField) + 1);
    String[] operators = {"contains", "=", "<>", "<", ">", "<=", ">=", "blank", "notBlank"};
    ComboBox<String> comparison =
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
    CheckBox descending = check("Descending order", data.descending);
    EditField value = field(data.filterValue);
    final java.util.Set<Integer> excluded = new java.util.LinkedHashSet<Integer>(data.excluded);
    final int[] page = {0};
    Container rows = new Container(BoxLayout.y());
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
                      + "–"
                      + end
                      + " of "
                      + data.sourceRows.size());
          for (int i = start; i < end; i++) {
            final int index = i;
            CheckBox include = check(recipientSummary(data, i), !excluded.contains(i));
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
          MergeData draft =
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
    StringBuilder text = new StringBuilder("" + (index + 1) + ". ");
    for (int i = 0; i < Math.min(OfficeTheme.mobile() ? 1 : 3, data.fields.size()); i++) {
      if (i > 0) text.append(" · ");
      String cell = data.sourceRows.get(index).get(i).replace('\n', ' ').replace('\r', ' ');
      text.append(cell.length() > 24 ? cell.substring(0, 24) + "…" : cell);
    }
    return text.toString();
  }

  private void editRecipientSourceDialog() {
    EditField csv =
        field(
            session.mergeData().fields.isEmpty()
                ? "First Name,Last Name,Address,City,State,PostalCode\n"
                : session.mergeData().csv(),
            10,
            50);
    if (showDialog(
        "Edit recipient list",
        BoxLayout.encloseY(
            OfficeTheme.label(
                "One recipient per row. Quote values containing commas.", 12, OfficeTheme.INK),
            formRow("Recipient CSV", csv)),
        "Apply",
        "Cancel")) mailing("recipients", Json.object("csv", csv.getText()));
  }

  private ComboBox<String> recipientFields() {
    if (session.mergeData().fields.isEmpty())
      throw new IllegalArgumentException("Select recipients first");
    return new ComboBox<String>(
        session.mergeData().fields.toArray(new String[session.mergeData().fields.size()]));
  }

  private void mergeFieldDialog() {
    ComboBox<String> fields = recipientFields();
    if (showDialog("Insert merge field", formRow("Field", fields), "Insert", "Cancel"))
      mailing("insertField", Json.object("field", fields.getSelectedItem()));
  }

  private void mergeRuleDialog() {
    ComboBox<String> rule = new ComboBox<String>("IF", "NEXT", "NEXTIF", "SKIPIF", "MERGEREC"),
        fields = recipientFields(),
        op = new ComboBox<String>("=", "<>", "<", ">", "<=", ">=");
    EditField value = field(""), yes = field(""), no = field("");
    if (showDialog(
        "Merge rule",
        BoxLayout.encloseY(
            formRow("Rule", rule),
            formRow("Field", fields),
            formRow("Comparison", op),
            formRow("Compare to", value),
            formRow("If true", yes),
            formRow("If false", no)),
        "Insert",
        "Cancel"))
      mailing(
          "rules",
          Json.object(
              "rule",
              rule.getSelectedItem(),
              "field",
              fields.getSelectedItem(),
              "operator",
              op.getSelectedItem(),
              "value",
              value.getText(),
              "then",
              yes.getText(),
              "else",
              no.getText()));
  }

  private void mergeMappingDialog() {
    recipientFields();
    Map<String, ComboBox<String>> inputs = new LinkedHashMap<String, ComboBox<String>>();
    Container body = new Container(BoxLayout.y());
    java.util.List<String> names = new ArrayList<String>();
    names.add("");
    names.addAll(session.mergeData().fields);
    for (Map.Entry<String, String> entry : session.mergeData().mappedFields().entrySet()) {
      ComboBox<String> input = new ComboBox<String>(names.toArray(new String[names.size()]));
      input.setSelectedItem(entry.getValue());
      inputs.put(entry.getKey(), input);
      body.add(formRow(entry.getKey(), input));
    }
    if (showDialog("Match fields", body, "Apply", "Cancel")) {
      Map<String, String> mapping = new LinkedHashMap<String, String>();
      for (Map.Entry<String, ComboBox<String>> e : inputs.entrySet())
        mapping.put(e.getKey(), e.getValue().getSelectedItem());
      mailing("matchFields", Json.object("mapping", mapping));
    }
  }

  private void findRecipientDialog() {
    EditField text = field("");
    if (showDialog("Find recipient", formRow("Find text", text), "Find", "Cancel"))
      mailing("findRecipient", Json.object("text", text.getText()));
  }

  private void mergeErrorsDialog() {
    java.util.List<String> errors = MailMerge.errors(session.document(), session.mergeData());
    String message =
        session.mergeData().rows.isEmpty()
            ? "Select recipients first."
            : errors.isEmpty()
                ? "All fields match. " + session.mergeData().rows.size() + " recipients."
                : "Unknown fields: " + errors;
    showDialog("Merge validation", OfficeTheme.label(message, 12, OfficeTheme.INK), "OK", null);
  }

  private void finishMergeDialog() {
    EditField from = field("1"), to = field("" + session.mergeData().rows.size());
    if (showDialog(
        "Finish merge",
        BoxLayout.encloseY(
            OfficeTheme.label(
                "Create an editable merged document. Undo restores the template.",
                12,
                OfficeTheme.INK),
            formRow("First recipient", from),
            formRow("Last recipient", to)),
        "Merge",
        "Cancel")) {
      mailing(
          "finish",
          Json.object(
              "from", Integer.parseInt(from.getText()), "to", Integer.parseInt(to.getText())));
      forgetFile();
      source = null;
    }
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
    Map<String, EditField> fields = new LinkedHashMap<String, EditField>();
    Container body = new Container(BoxLayout.y());
    for (int i = 0; i < CitationSource.KEYS.length; i++) {
      String key = CitationSource.KEYS[i];
      EditField input =
          field(original == null ? "kind".equals(key) ? "book" : "" : original.get(key));
      if (original != null && "tag".equals(key)) input.setEditable(false);
      fields.put(key, input);
      body.add(formRow(labels[i], input));
    }
    body.add(new Label("Authors: Last, First; Last, First"));
    if (!showDialog(original == null ? "Create source" : "Edit source", body, "Save", "Cancel"))
      return null;
    Map<String, String> values = new LinkedHashMap<String, String>();
    for (Map.Entry<String, EditField> entry : fields.entrySet())
      values.put(entry.getKey(), entry.getValue().getText());
    CitationSource result = new CitationSource(values);
    if (original == null && session.document().bibliography.sources.containsKey(result.tag())) {
      if (!values.get("tag").trim().isEmpty())
        throw new IllegalArgumentException(
            "Source tag already exists; use Manage Sources to edit it");
      String base = result.tag();
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
    java.util.List<CitationSource> sources =
        new ArrayList<CitationSource>(session.document().bibliography.sources.values());
    String[] labels = new String[Math.max(1, sources.size())];
    if (sources.isEmpty()) labels[0] = "No sources";
    for (int i = 0; i < sources.size(); i++)
      labels[i] = sources.get(i).tag() + " — " + sources.get(i).get("title");
    ComboBox<String> source = new ComboBox<String>(labels);
    ComboBox<String> action =
        new ComboBox<String>(
            sources.isEmpty()
                ? new String[] {"New source"}
                : new String[] {"Edit source", "New source", "Delete source"});
    if (!showDialog(
        "Manage sources",
        BoxLayout.encloseY(formRow("Source", source), formRow("Action", action)),
        "Continue",
        "Cancel")) return;
    if ("Delete source".equals(action.getSelectedItem())) {
      CitationSource selected = sources.get(source.getSelectedIndex());
      if (showDialog(
          "Delete source",
          new Label("Existing citations will show a missing-source message."),
          "Delete",
          "Cancel")) {
        Citations.removeSource(session, selected.tag());
        edited();
      }
    } else {
      CitationSource editedSource =
          sourceDialog(
              "New source".equals(action.getSelectedItem())
                  ? null
                  : sources.get(source.getSelectedIndex()));
      if (editedSource != null) {
        Citations.source(session, editedSource);
        edited();
      }
    }
  }

  private void citationDialog() {
    editor.stopInput();
    java.util.List<CitationSource> sources =
        new ArrayList<CitationSource>(session.document().bibliography.sources.values());
    String[] choices = new String[sources.size() + 1];
    choices[0] = "Add new source";
    for (int i = 0; i < sources.size(); i++)
      choices[i + 1] = sources.get(i).tag() + " — " + sources.get(i).get("title");
    ComboBox<String> source = new ComboBox<String>(choices);
    if (!sources.isEmpty()) source.setSelectedIndex(1);
    EditField pages = field("");
    if (!showDialog(
        "Insert citation",
        BoxLayout.encloseY(formRow("Source", source), formRow("Citation pages", pages)),
        "Insert",
        "Cancel")) return;
    CitationSource created = source.getSelectedIndex() == 0 ? sourceDialog(null) : null;
    if (source.getSelectedIndex() == 0 && created == null) return;
    Citations.insert(
        session,
        created == null ? sources.get(source.getSelectedIndex() - 1).tag() : created.tag(),
        created,
        pages.getText());
    edited();
  }

  private void citationStyleDialog() {
    ComboBox<String> style = new ComboBox<String>(CitationStyles.NAMES);
    style.setSelectedItem(session.document().bibliography.style);
    if (showDialog("Citation style", formRow("Style", style), "Apply", "Cancel")) {
      Citations.style(session, style.getSelectedItem());
      edited();
    }
  }

  private void bibliographyDialog() {
    int[] range = Citations.range(session.document());
    EditField title =
        field(
            range == null
                ? CitationStyles.title(CitationStyles.index(session.document().bibliography.style))
                : session.document().paragraph(range[0]).text.plainText());
    if (showDialog("Bibliography", formRow("Title", title), "Apply", "Cancel")) {
      Citations.bibliography(session, title.getText());
      edited();
    }
  }

  private void markAuthorityDialog() {
    editor.stopInput();
    EditField entry = field(session.selectedText());
    if (showDialog("Mark citation", formRow("Citation", entry), "Mark", "Cancel")) {
      TableAuthorities.mark(session, entry.getText());
      edited();
    }
  }

  private void authoritiesDialog() {
    editor.stopInput();
    DocumentIndex.Options old = TableAuthorities.options(session.document());
    EditField title = field(old.title);
    String[] leaders = {"none", "dot", "hyphen", "underscore", "middleDot"};
    ComboBox<String> leader =
        new ComboBox<String>("None", "Dots", "Hyphens", "Underscores", "Middle dots");
    leader.setSelectedIndex(Arrays.asList(leaders).indexOf(old.leader));
    if (showDialog(
        "Table of authorities",
        BoxLayout.encloseY(formRow("Title", title), formRow("Tab leader", leader)),
        "Apply",
        "Cancel")) {
      TableAuthorities.insert(
          session, title.getText(), leaders[leader.getSelectedIndex()], editor::printLayout);
      edited();
    }
  }

  private CheckBox check(String text, boolean selected) {
    CheckBox result = new CheckBox(text);
    result.setSelected(selected);
    return result;
  }

  private void markIndexDialog() {
    editor.stopInput();
    EditField entry = field(session.selectedText()),
        sub = field(""),
        cross = field(""),
        bookmark = field("");
    CheckBox bold = check("Bold page numbers", false), italic = check("Italic page numbers", false);
    CheckBox all = check("Mark all occurrences", false);
    EditField search = field(session.selectedText());
    if (!showDialog(
        "Mark index entry",
        BoxLayout.encloseY(
            formRow("Main entry", entry),
            formRow("Subentry", sub),
            formRow("Cross-reference", cross),
            formRow("Page-range bookmark", bookmark),
            bold,
            italic,
            all,
            formRow("Text to mark", search)),
        "Mark",
        "Cancel")) return;
    DocumentIndex.mark(
        session,
        entry.getText(),
        sub.getText(),
        cross.getText(),
        bookmark.getText().trim(),
        bold.isSelected(),
        italic.isSelected(),
        all.isSelected(),
        search.getText().isEmpty() ? entry.getText() : search.getText());
    edited();
  }

  private void indexDialog() {
    editor.stopInput();
    DocumentIndex.Options old = DocumentIndex.options(session.document());
    EditField title = field(old.title);
    ComboBox<String> type = new ComboBox<String>("Indented", "Run-in");
    type.setSelectedIndex(old.runIn ? 1 : 0);
    CheckBox headings = check("Letter headings", old.headings),
        right = check("Right-align page numbers", old.rightAlign);
    String[] leaders = {"none", "dot", "hyphen", "underscore", "middleDot"};
    ComboBox<String> leader =
        new ComboBox<String>("None", "Dots", "Hyphens", "Underscores", "Middle dots");
    leader.setSelectedIndex(Arrays.asList(leaders).indexOf(old.leader));
    if (!showDialog(
        "Index",
        BoxLayout.encloseY(
            formRow("Title", title),
            formRow("Type", type),
            headings,
            right,
            formRow("Tab leader", leader)),
        "Apply",
        "Cancel")) return;
    DocumentIndex.insert(
        session,
        new DocumentIndex.Options(
            title.getText(),
            type.getSelectedIndex() == 1,
            headings.isSelected(),
            right.isSelected(),
            leaders[leader.getSelectedIndex()]),
        editor::printLayout);
    edited();
  }

  private void removeFiguresDialog() {
    EditField label = field("Figure");
    if (showDialog(
        "Remove table of figures", formRow("Caption label", label), "Remove", "Cancel")) {
      TableContents.remove(session, label.getText().trim());
      edited();
    }
  }

  private void contentsDialog() {
    contentsDialog(null);
  }

  private void contentsDialog(String caption) {
    editor.stopInput();
    TableContents.Options old = TableContents.options(session.document(), caption);
    EditField captionLabel = field(caption == null ? "" : caption);
    EditField title = field(old.title);
    title.setAccessibilityText("Contents title");
    ComboBox<String> levels = new ComboBox<String>("1", "2", "3", "4", "5", "6", "7", "8", "9");
    levels.setSelectedIndex(old.levels - 1);
    levels.setAccessibilityText("Contents levels");
    ComboBox<String> leader =
        new ComboBox<String>("Dot", "Hyphen", "Underscore", "None", "Middle dot");
    String[] leaders = {"dot", "hyphen", "underscore", "none", "middleDot"};
    leader.setSelectedIndex(Arrays.asList(leaders).indexOf(old.leader));
    leader.setAccessibilityText("Contents tab leader");
    CheckBox pages = new CheckBox("Show page numbers");
    pages.setSelected(old.pageNumbers);
    CheckBox right = new CheckBox("Right align page numbers");
    right.setSelected(old.rightAlign);
    CheckBox links = new CheckBox("Link entries to headings");
    links.setSelected(old.hyperlinks);
    CheckBox headings = new CheckBox("Use heading styles"),
        outlines = new CheckBox("Use outline levels"),
        marked = new CheckBox("Use marked entries");
    headings.setSelected(old.headingStyles);
    outlines.setSelected(old.outlineLevels);
    marked.setSelected(old.tcFields);
    EditField identifier = field(old.tcIdentifier);
    Map<String, Integer> styleLevels = new LinkedHashMap<String, Integer>(old.styleLevels);
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
              button("Style levels…", () -> contentsStyleDialog(styleLevels))));
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
    java.util.Set<String> names = new java.util.LinkedHashSet<String>();
    for (ParagraphStyle style : session.document().styles.values())
      if (!style.id.startsWith("TOC") && !"ListEntry".equals(style.id)) names.add(style.name);
    names.addAll(levels.keySet());
    Map<String, ComboBox<String>> choices = new LinkedHashMap<String, ComboBox<String>>();
    Container body = new Container(BoxLayout.y());
    for (String name : names) {
      ComboBox<String> choice =
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
    ComboBox<String> level =
        new ComboBox<String>(
            "Do not show",
            "Level 1",
            "Level 2",
            "Level 3",
            "Level 4",
            "Level 5",
            "Level 6",
            "Level 7",
            "Level 8",
            "Level 9");
    Paragraph p = session.editingDocument().paragraph(session.selection().block);
    level.setSelectedIndex(
        p.format.outlineLevel >= 0 ? p.format.outlineLevel : SequenceFields.headingLevel(p.style));
    if (showDialog("Add text to contents", formRow("Outline level", level), "Apply", "Cancel")) {
      session.paragraph("outlineLevel", "" + level.getSelectedIndex());
      edited();
    }
  }

  private void markContentsDialog() {
    EditField text = field(session.selectedText()), identifier = field("C");
    ComboBox<String> level = new ComboBox<String>("1", "2", "3", "4", "5", "6", "7", "8", "9");
    if (showDialog(
        "Mark contents entry",
        BoxLayout.encloseY(
            formRow("Entry text", text),
            formRow("Entry level", level),
            formRow("Identifier", identifier)),
        "Mark",
        "Cancel")) {
      TableContents.markEntry(
          session, text.getText(), level.getSelectedIndex() + 1, identifier.getText());
      edited();
    }
  }

  private void bookmarkDialog() {
    editor.stopInput();
    Dialog dialog = officeDialog("Bookmarks");
    dialog.setLayout(new BorderLayout());
    EditField name = field("");
    name.setAccessibilityText("Bookmark name");
    java.util.List<String> names = new ArrayList<String>(session.document().bookmarks.keySet());
    java.util.Collections.sort(names);
    ComboBox<String> existing =
        new ComboBox<String>(
            names.isEmpty()
                ? new String[] {"No bookmarks"}
                : names.toArray(new String[names.size()]));
    existing.setAccessibilityText("Existing bookmarks");
    existing.addActionListener(
        e -> {
          if (!names.isEmpty()) name.setText(existing.getSelectedItem());
        });
    Button add =
        button(
            "Add",
            () -> {
              session.addBookmark(name.getText().trim());
              dialog.dispose();
              edited();
            });
    Button go =
        button(
            "Go To",
            () -> {
              session.goToBookmark(existing.getSelectedItem());
              dialog.dispose();
              edited();
            });
    Button remove =
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
    ComboBox<String> kind =
        new ComboBox<String>("Heading", "Bookmark", "Figure", "Table", "Equation");
    kind.setAccessibilityText("Reference type");
    ComboBox<String> target = new ComboBox<String>();
    target.setAccessibilityText("Reference target");
    ComboBox<String> show =
        new ComboBox<String>("Text", "Page number", "Label and number", "Above or below");
    show.setAccessibilityText("Reference display");
    Runnable refresh =
        () -> {
          com.codename1.ui.list.DefaultListModel<String> model =
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
    String display = new String[] {"text", "page", "number", "aboveBelow"}[show.getSelectedIndex()];
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
    Paragraph p = session.editingDocument().paragraph(session.selection().block);
    int caret = session.selection().caret;
    InlineField field = caret < p.text.length() ? p.text.fieldAt(caret) : null;
    if (field == null && caret > 0) field = p.text.fieldAt(caret - 1);
    if (field == null && p.text.length() > 0) {
      String link = p.text.styleAt(Math.min(caret, p.text.length() - 1)).link;
      if (link.startsWith("#")) {
        session.goToBookmark(link.substring(1));
        edited();
        return;
      }
    }
    if (field == null)
      throw new IllegalArgumentException(
          "Place the caret in a cross-reference or linked contents entry");
    FieldCode code = new FieldCode(field.instruction);
    if (!("REF".equals(code.name) || "PAGEREF".equals(code.name)) || code.arguments.isEmpty())
      throw new IllegalArgumentException("Select a cross-reference field");
    session.goToBookmark(code.arguments.get(0));
    edited();
  }

  private void captionDialog() {
    editor.stopInput();
    EditField label = field("Figure"), text = field("");
    label.setAccessibilityText("Caption label");
    text.setAccessibilityText("Caption text");
    ComboBox<String> position = new ComboBox<String>("Below selected item", "Above selected item");
    ComboBox<String> format =
        new ComboBox<String>("1, 2, 3", "a, b, c", "A, B, C", "i, ii, iii", "I, II, III");
    ComboBox<String> chapter =
        new ComboBox<String>(
            "No chapter number",
            "Heading 1",
            "Heading 2",
            "Heading 3",
            "Heading 4",
            "Heading 5",
            "Heading 6",
            "Heading 7",
            "Heading 8",
            "Heading 9");
    ComboBox<String> separator = new ComboBox<String>("-", ".", ":", "—", "–");
    CheckBox exclude = new CheckBox("Exclude label from caption");
    Container body =
        BoxLayout.encloseY(
            new Label("Label"),
            label,
            new Label("Caption text"),
            text,
            new Label("Position"),
            position,
            new Label("Number format"),
            format,
            exclude,
            new Label("Chapter numbering"),
            chapter,
            new Label("Separator"),
            separator);
    body.setScrollableY(true);
    if (OfficeTheme.mobile()) body.setPreferredH(Display.getInstance().getDisplayHeight() / 2);
    if (!showDialog("Insert Caption", body, "Insert", "Cancel")) return;
    session.insertCaption(
        label.getText(),
        text.getText(),
        position.getSelectedIndex() == 1,
        new String[] {"ARABIC", "alphabetic", "ALPHABETIC", "roman", "ROMAN"}
            [format.getSelectedIndex()],
        exclude.isSelected(),
        chapter.getSelectedIndex(),
        separator.getSelectedItem());
    edited();
  }

  private Button trackingButton;

  private void trackingOptions() {
    CheckBox enabled = new CheckBox("Track changes");
    enabled.setSelected(session.document().trackChanges);
    CheckBox formatting = new CheckBox("Track formatting");
    formatting.setSelected(session.trackFormatting());
    EditField author = field(session.reviewAuthor());
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
                  change.revision.author + " · " + change.revision.kind, 13, OfficeTheme.INK),
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
      Dialog dialog = officeDialog("Reviewing Pane");
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
                            Json.write(commands.execute(session, "quality.inspect"))),
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
                    button("Tracking options…", this::trackingOptions)))),
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
    ComboBox<String> sourceChoice =
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
    ComboBox<String> level = new ComboBox<String>("Word", "Character");
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
            "Compares paragraph and table-cell text and direct formatting. Resolve"
                + " existing tracked changes first. Page layout and headers use the"
                + " original document. Undo restores the original."));
    if (!showDialog("Comparison options", body, "Compare", "Cancel")) return;
    Map<String, Object> args =
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
    EditField author = field("Author");
    EditField text = field("", 4, 28);
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
    String id = session.navigateComment(backwards);
    if (id == null) {
      showText("Comments", "No comments");
      return;
    }
    editor.revealSelection();
    editor.startInput();
  }

  private void editComment(Dialog parent, ReviewComment comment, int replyIndex) {
    String title = replyIndex < 0 ? "Edit comment" : "Edit reply";
    EditField text =
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
    Dialog dialog = officeDialog("Comments");
    dialog.setLayout(new BorderLayout());
    Container list = new Container(BoxLayout.y());
    list.setScrollableY(true);
    list.setPreferredW(
        Math.min(
            OfficeTheme.px(420), Display.getInstance().getDisplayWidth() - OfficeTheme.px(32)));
    list.setPreferredH(Display.getInstance().getDisplayHeight() / 2);
    for (ReviewComment c : session.document().comments) {
      Container row = new Container(BoxLayout.y());
      OfficeTheme.space(row, 6, 6, 8, 8);
      row.add(new Label(c.author + (c.resolved ? " · Resolved" : "")));
      row.add(new SpanLabelCompat(c.text));
      for (int replyIndex = 0; replyIndex < c.replies.size(); replyIndex++) {
        final int index = replyIndex;
        ReviewComment.Reply reply = c.replies.get(index);
        Button editReply = button("Edit reply", () -> editComment(dialog, c, index));
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
                    () -> {
                      ruler.setHidden(!ruler.isHidden());
                      ruler.getParent().revalidate();
                    }))),
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
      editor.fitPages(a.containsKey("count") ? Commands.integer(a, "count") : 2);
      zoomTo(editor.getZoom());
    });
    view("view.marks", "Show or hide formatting marks", "value?:boolean", a ->
        editor.setShowMarks(a.containsKey("value") ? (Boolean) a.get("value") : !editor.isShowMarks()));
    view("view.ruler", "Show or hide the ruler", "value?:boolean", a -> {
      ruler.setHidden(a.containsKey("value") ? !(Boolean) a.get("value") : !ruler.isHidden());
      ruler.getParent().revalidate();
    });
    view("view.navigationPane", "Show or hide the navigation pane", "value?:boolean", a -> {
      boolean shown = navigation.getParent() != null;
      boolean show = a.containsKey("value") ? (Boolean) a.get("value") : !shown;
      if (show && !shown) workspace.add(BorderLayout.WEST, navigation);
      else if (!show && shown) workspace.removeComponent(navigation);
      workspace.revalidate();
    });
    view("view.printLayout", "Paginated print layout", "", a -> {
      if (editor.isReflow()) editor.setReflow(false);
      zoomTo(editor.getZoom());
    });
    view("view.webLayout", "Reflow the document to the window width", "", a -> {
      if (!editor.isReflow()) editor.setReflow(true);
      zoomTo(editor.getZoom());
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
              "marks", editor.isShowMarks());
        });
  }

  private void zoomTo(float zoom) {
    editor.setZoom(zoom);
    updateStatus();
    ruler.repaint();
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
    Container panel = new Container(new BorderLayout());
    Container rail = new Container(OfficeTheme.mobile() ? BoxLayout.x() : BoxLayout.y());
    rail.setScrollableX(OfficeTheme.mobile());
    OfficeTheme.surface(rail, OfficeTheme.BLUE);
    OfficeTheme.space(rail, 12, 12, 12, 12);
    if (!OfficeTheme.mobile()) rail.setPreferredW(OfficeTheme.px(180));
    Container page = new Container(new BorderLayout());
    OfficeTheme.surface(page, 0xf8f8f8);
    OfficeTheme.space(page, 12, 12, OfficeTheme.mobile() ? 12 : 32, 12);
    page.setScrollableY(true);
    String[] labels = {
      "‹ Back to document", "New", "Open", "Info", "Save", "Save As", "Print", "Export"
    };
    for (String label : labels) {
      Button item =
          button(
              label,
              () -> {
                if (label.startsWith("‹")) {
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
                backstagePage(page, label);
              });
      OfficeTheme.space(item, 12, 12, 10, 10);
      item.getAllStyles().setFgColor(0xffffff);
      item.getAllStyles().setFont(OfficeTheme.font(16, false));
      item.setAlignment(Component.LEFT);
      rail.add(item);
    }
    panel.add(OfficeTheme.mobile() ? BorderLayout.NORTH : BorderLayout.WEST, rail);
    panel.add(BorderLayout.CENTER, page);
    form.removeAll();
    form.add(BorderLayout.CENTER, panel);
    backstagePage(page, "New");
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
    Container content = new Container(BoxLayout.y());
    content.setScrollableY(true);
    if ("New".equals(section)) {
      Container choices = new Container(new FlowLayout(Component.LEFT));
      for (String name : new String[] {"Blank document", "Writing studio"}) {
        Button preview =
            button(
                "Blank document".equals(name)
                    ? ""
                    : "The writing studio\n\n"
                        + "A better place for words.\n\n"
                        + "Your ideas, beautifully structured",
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
        preview.setIcon(
            templatePreview("Blank document".equals(name) ? Document.empty() : sample()));
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
    page.revalidate();
  }

  private Image templatePreview(Document document) {
    int width = OfficeTheme.px(138), height = OfficeTheme.px(180);
    Image image = Image.createImage(width, height, 0xffffff);
    Graphics g = image.getGraphics();
    g.setAntiAliased(true);
    LayoutEngine engine =
        new LayoutEngine(
            new LayoutEngine.Metrics() {
              public float width(String text, TextStyle style) {
                return DocumentFonts.font(style, style.size * 2).stringWidth(text) / 2f;
              }

              public float height(TextStyle style) {
                return DocumentFonts.font(style, style.size * 2).getHeight() / 2f;
              }
            });
    LayoutEngine.Result pages = engine.layout(document, 0);
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
    if (session.document() == fileDocument) {
      next.run();
      return;
    }
    editor.stopInput();
    Dialog dialog = officeDialog("Save changes?");
    dialog.setLayout(new BorderLayout());
    dialog.add(
        BorderLayout.CENTER,
        new SpanLabelCompat(
            "Save changes to “" + session.document().title + "” before continuing?"));
    Container actions = new Container(new FlowLayout(Component.RIGHT));
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

  private void rename() {
    prompt(
        "Document name",
        session.document().title,
        v -> run("document.title", Json.object("title", v)));
  }

  private void menu(String group, String name, char key, int modifiers, Runnable action) {
    uiActions.put(name, action);
    Command command =
        new Command(name) {
          @Override
          public void actionPerformed(com.codename1.ui.events.ActionEvent event) {
            editor.finishComposing();
            safe(action);
          }
        };
    command.setDesktopMenu(group);
    if (key != 0) command.setDesktopShortcut(key, modifiers);
    form.getToolbar().addCommandToSideMenu(command);
  }

  private void installMenu() {
    int primary = Command.DESKTOP_SHORTCUT_MODIFIER_PRIMARY,
        shift = primary | Command.DESKTOP_SHORTCUT_MODIFIER_SHIFT;
    menu("File", "New", 'n', primary, this::newDocument);
    menu("File", "Open…", 'o', primary, this::open);
    menu("File", "Save", 's', primary, () -> saveDocument(false));
    menu("File", "Save As…", 's', shift, () -> saveDocument(true));
    menu("File", "Print…", 'p', primary, this::printDocument);
    menu("File", "Export Word Document…", (char) 0, 0, () -> save("docx"));
    menu("File", "Export PDF…", (char) 0, 0, () -> save("pdf"));
    menu("File", "Rename…", (char) 0, 0, this::rename);
    // Undo/Redo/Cut/Copy/Paste/Select All are supplied by the native Mac Edit menu
    // and reach this editor through TextInputClient. Do not add duplicate items.
    menu("Edit", "Find and Replace…", 'f', primary, this::find);
    menu("Insert", "Comment…", (char) 0, 0, this::newComment);
    menu("Tools", "Comments…", (char) 0, 0, this::comments);
    menu(
        "Tools",
        "Track Changes",
        'e',
        shift,
        () -> {
          session.trackChanges(!session.document().trackChanges);
          edited();
        });
    menu("Tools", "Reviewing Pane", (char) 0, 0, this::revisionsPane);
    menu("Tools", "Accept Change", (char) 0, 0, () -> revisionAction(true, false));
    menu("Tools", "Reject Change", (char) 0, 0, () -> revisionAction(false, false));
    menu("Insert", "Table…", (char) 0, 0, this::insertTable);
    menu("Insert", "Quick Tables…", (char) 0, 0, this::quickTables);
    menu("Insert", "Picture…", (char) 0, 0, this::insertPicture);
    menu("Insert", "Mark Contents Entry…", (char) 0, 0, this::markContentsDialog);
    menu("Insert", "Table of Contents…", (char) 0, 0, this::contentsDialog);
    menu("Insert", "Table of Figures…", (char) 0, 0, () -> contentsDialog("Figure"));
    menu("Insert", "Footnote…", (char) 0, 0, () -> noteDialog(null, "footnote"));
    menu("Insert", "Endnote…", (char) 0, 0, () -> noteDialog(null, "endnote"));
    menu("Insert", "Show Notes…", (char) 0, 0, this::showNotes);
    menu("Format", "Footnote and Endnote…", (char) 0, 0, this::noteOptionsDialog);
    menu("Insert", "Caption…", (char) 0, 0, this::captionDialog);
    menu("Insert", "Bookmark…", (char) 0, 0, this::bookmarkDialog);
    menu("Insert", "Cross-reference…", (char) 0, 0, this::crossReferenceDialog);
    menu("Format", "Styles…", (char) 0, 0, this::moreStyles);
    menu("Format", "Table layout…", (char) 0, 0, this::tableLayout);
    menu(
        "Insert",
        "Page Break",
        (char) 0,
        0,
        () -> {
          session.replace("\n");
          paragraph("pageBreak", "true");
        });
    menu("Format", "Bold", 'b', primary, () -> format("bold"));
    menu("Format", "Italic", 'i', primary, () -> format("italic"));
    menu("Format", "Underline", 'u', primary, () -> format("underline"));
    menu("Format", "Normal", (char) 0, 0, () -> heading(0));
    menu("Format", "Font…", (char) 0, 0, this::fontDialog);
    menu("Format", "Paragraph…", (char) 0, 0, this::paragraphDialog);
    menu("Format", "Heading 1", (char) 0, 0, () -> heading(1));
    menu("Format", "Heading 2", (char) 0, 0, () -> heading(2));
    menu(
        "Format",
        "Keep with Next",
        (char) 0,
        0,
        () ->
            paragraph(
                "keepNext",
                String.valueOf(
                    !session.editingDocument().paragraph(session.selection().block).keepNext)));
    menu(
        "Format",
        "Restart Numbering…",
        (char) 0,
        0,
        () -> prompt("Start at", "1", v -> paragraph("listStart", v)));
    menu("Format", "Number Format…", (char) 0, 0, () -> chooseNumberFormat());
    menu(
        "View",
        "Actual Size",
        '0',
        primary,
        () -> {
          editor.setZoom(1);
          updateStatus();
          ruler.repaint();
        });
    menu(
        "View",
        "Page Width",
        (char) 0,
        0,
        () -> {
          editor.fitWidth();
          updateStatus();
          ruler.repaint();
        });
    menu(
        "Tools",
        "Word Count",
        (char) 0,
        0,
        () -> showText("Word count", statistics.words(session.document()) + " words"));
    menu("Tools", "Commands…", (char) 0, 0, this::commandDialog);
  }

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
    Block b = session.editingDocument().blocks.get(session.selection().block);
    if (b instanceof Paragraph) {
      Paragraph p = (Paragraph) b;
      if (!p.list.isEmpty())
        paragraph(
            "listLevel",
            String.valueOf(Math.max(0, Math.min(8, p.listLevel + (delta > 0 ? 1 : -1)))));
      else paragraph("indent", String.valueOf(Math.max(0, p.indent + delta)));
    }
  }

  private void chooseNumberFormat() {
    ComboBox<String> choice =
        new ComboBox<String>("decimal", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman");
    if (showDialog("Number format", choice, "Apply", "Cancel"))
      paragraph("listFormat", choice.getSelectedItem());
  }

  private void list(String kind) {
    Block b = session.editingDocument().blocks.get(session.selection().block);
    paragraph("list", b instanceof Paragraph && kind.equals(((Paragraph) b).list) ? "" : kind);
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

  private void color(String property) {
    Dialog d = officeDialog("background".equals(property) ? "Text highlight color" : "Font color");
    d.setLayout(new BorderLayout());
    Container grid = new Container(new GridLayout(3, 6));
    for (String hex :
        new String[] {
          "242424", "ffffff", "185abd", "c00000", "ffc000", "008450", "7030a0", "4472c4", "ed7d31",
          "70ad47", "ff66aa", "00b0f0", "fff2cc", "e2efd9", "dae3f3", "fbe4d5", "e4dfec", "ffff00"
        }) {
      Button swatch =
          button(
              "  ",
              () -> {
                d.dispose();
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
    Columns current = session.activeSection().raw.columns;
    ComboBox<String> preset =
        new ComboBox<String>("One", "Two", "Three", "Left", "Right", "Custom count");
    preset.setSelectedIndex(
        current.equalWidth() && current.count <= 3
            ? current.count - 1
            : current.equalWidth() ? 5 : current.widths()[0] < current.widths()[2] ? 3 : 4);
    EditField count = field(String.valueOf(current.count));
    EditField space = field(String.valueOf(current.space));
    CheckBox separator = new CheckBox("Line between");
    separator.setSelected(current.separator);
    ComboBox<String> apply =
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
    PageSpec p = session.activeSection().raw.page;
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
      Button result =
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
    Dialog dialog = officeDialog(title);
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
    Container actions = new Container(new FlowLayout(Component.RIGHT));
    if (cancel != null) {
      Command back =
          new Command(cancel) {
            public void actionPerformed(com.codename1.ui.events.ActionEvent e) {
              dialog.dispose();
            }
          };
      Button cancelButton = new Button(back);
      OfficeTheme.button(cancelButton);
      actions.add(cancelButton);
      dialog.setBackCommand(back);
    }
    Command accept =
        new Command(ok) {
          public void actionPerformed(com.codename1.ui.events.ActionEvent e) {
            accepted[0] = true;
            dialog.dispose();
          }
        };
    Button acceptButton = new Button(accept);
    OfficeTheme.button(acceptButton);
    actions.add(acceptButton);
    dialog.setDefaultCommand(accept);
    dialog.add(BorderLayout.SOUTH, actions);
    styleDialogBody(dialog.getContentPane());
    showOfficeDialog(dialog);
    return accepted[0];
  }

  private void setPage(float w, float h) {
    PageSpec p = session.activeSection().raw.page;
    session.section(
        session.activeSection().raw.page(new PageSpec(w, h, p.top, p.right, p.bottom, p.left)));
    edited();
  }

  private void sectionBreakDialog() {
    ComboBox<String> type =
        new ComboBox<String>("Next page", "Continuous", "Odd page", "Even page");
    if (showDialog(
        "Insert section break", formRow("Start new section", type), "Insert", "Cancel")) {
      session.sectionBreak(
          new String[] {"nextPage", "continuous", "oddPage", "evenPage"}[type.getSelectedIndex()]);
      edited();
    }
  }

  private void sectionSettings() {
    Section current = session.activeSection().raw;
    String[] types = {"nextPage", "continuous", "oddPage", "evenPage"};
    ComboBox<String> start =
        new ComboBox<String>("Next page", "Continuous", "Odd page", "Even page");
    for (int i = 0; i < types.length; i++)
      if (types[i].equals(current.start)) start.setSelectedIndex(i);
    EditField number = field("" + current.pageStart);
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

  private Container formRow(String label, Component field) {
    field.getSemantics().setLabel(label);
    Label caption = OfficeTheme.label(label, 12, OfficeTheme.INK);
    if (OfficeTheme.mobile()) return BoxLayout.encloseY(caption, field);
    Container row = new Container(new BorderLayout());
    caption.setPreferredW(OfficeTheme.px(170));
    row.add(BorderLayout.WEST, caption);
    row.add(BorderLayout.CENTER, field);
    OfficeTheme.space(row, 3, 3, 0, 0);
    return row;
  }

  private void fontDialog() {
    TextStyle style = session.activeStyle();
    ComboBox<String> family =
        new ComboBox<String>("Source Sans 3", "Source Serif 4", "JetBrains Mono");
    family.setSelectedItem(DocumentFonts.family(style.font));
    EditField size = field(Float.toString(style.size));
    size.setAccessibilityText("Font size in points");
    ComboBox<String> baseline = new ComboBox<String>("Normal", "Superscript", "Subscript");
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
    Container effectsPanel = new Container(new GridLayout((names.length + 5) / 2, 2));
    effectsPanel.add(bold).add(italic).add(underline).add(strike);
    for (int i = 0; i < names.length; i++) {
      effects[i] = new CheckBox(names[i]);
      effects[i].setSelected(Boolean.parseBoolean(style.characters.value(effectKeys[i])));
      effectsPanel.add(effects[i]);
    }
    ComboBox<String> underlineType =
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
    EditField underlineColor = field(style.characters.value("underlineColor"));
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
    ComboBox<String> rule = new ComboBox<String>("Multiple", "At least", "Exactly");
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

  private void lineSpacingDialog() {
    Paragraph p = session.editingDocument().paragraph(session.selection().block);
    if (p == null) return;
    ComboBox<String> rule = lineRuleChoice(p);
    EditField value = field("" + p.lineSpacing);
    value.setAccessibilityText("Line spacing");
    Label unit = new Label();
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
    Paragraph p = session.editingDocument().paragraph(session.selection().block);
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
    Container body = new Container(BoxLayout.y());
    body.setScrollableY(true);
    EditField[] inputs = new EditField[keys.length];
    for (int i = 0; i < keys.length; i++) {
      inputs[i] = field(String.valueOf(values[i]));
      inputs[i].setAccessibilityText(labels[i]);
      body.add(formRow(labels[i], inputs[i]));
    }
    ComboBox<String> lineRule = lineRuleChoice(p);
    body.add(formRow("Line spacing rule", lineRule));
    body.add(new Label("Multiple uses lines; At least / Exactly use points."));
    ComboBox<String> alignment =
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
    body.add(button("Tabs…", () -> editTabs(tabs)));
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
    java.util.List<TabStop> stops = new java.util.ArrayList<TabStop>(result[0]);
    EditField position = field("72");
    ComboBox<String> alignment = new ComboBox<String>("left", "center", "right", "decimal", "bar");
    ComboBox<String> leader =
        new ComboBox<String>("none", "dot", "hyphen", "underscore", "middleDot");
    ComboBox<String> existing = new ComboBox<String>();
    Runnable reload =
        () -> {
          while (existing.getModel().getSize() > 0) existing.getModel().removeItem(0);
          for (TabStop stop : stops)
            existing
                .getModel()
                .addItem(stop.position + " pt · " + stop.alignment + " · " + stop.leader);
        };
    reload.run();
    existing.addActionListener(
        e -> {
          int i = existing.getSelectedIndex();
          if (i >= 0 && i < stops.size()) {
            TabStop stop = stops.get(i);
            position.setText(String.valueOf(stop.position));
            alignment.setSelectedItem(stop.alignment);
            leader.setSelectedItem(stop.leader);
          }
        });
    if (!stops.isEmpty()) {
      TabStop first = stops.get(0);
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
              TabStop stop =
                  new TabStop(
                      Float.parseFloat(position.getText()),
                      alignment.getSelectedItem(),
                      leader.getSelectedItem());
              for (int i = stops.size() - 1; i >= 0; i--)
                if (stops.get(i).position == stop.position) stops.remove(i);
              if (stops.size() >= 64) throw new IllegalArgumentException("At most 64 tab stops");
              stops.add(stop);
              java.util.List<TabStop> sorted = TabStop.normalized(stops);
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
    Selection x = session.selection();
    TextStyle style = session.activeStyle();
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
    EditField field = field(initial);
    field.setAccessibilityText(name);
    if (showDialog(name, field, "Apply", "Cancel")) safe(() -> callback.accept(field.getText()));
  }

  private Button button(String text, Runnable action) {
    Button b = new Button(text);
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
    MergeData merge = session.mergeData();
    mergeStatus.setText(
        merge.rows.isEmpty()
            ? "No recipients"
            : (merge.preview ? "Preview " : "Recipient ")
                + (merge.record + 1)
                + " of "
                + merge.rows.size());
    lastEdit = System.currentTimeMillis();
    form.setTitle(session.document().title + (OfficeTheme.mobile() ? "" : " — CN1 Word Processor"));
    if (Display.getInstance().isDesktop()) Display.getInstance().refreshNativeTitle();
    editor.revealSelection();
    updateOutline();
    updateStatus();
    if (ruler != null) ruler.repaint();
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
            + (filePath != null && fileDocument == session.document()
                ? "    |    Saved"
                : savedRevision == session.revision() ? "    |    Recovery saved" : ""));
    if (trackingButton != null) {
      trackingButton
          .getUnselectedStyle()
          .setBgTransparency(session.document().trackChanges ? 255 : 0);
      trackingButton.getUnselectedStyle().setBgColor(0xdce8fa);
      trackingButton.setAccessibilityText(
          session.document().trackChanges ? "Track Changes on" : "Track Changes off");
    }
    syncingControls = true;
    if (zoomValue != null) zoomValue.setText(Math.round(editor.getZoom() * 100) + "%");
    if (zoomSlider != null) zoomSlider.setProgress(Math.round(editor.getZoom() * 100));
    TextStyle style = session.activeStyle();
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
      Button b = formatButtons.get(property);
      b.getUnselectedStyle().setBgTransparency(active ? 255 : 0);
      b.getUnselectedStyle().setBgColor(0xdce8fa);
    }
    if (documentTitle != null) {
      String title =
          session.document().title
              + (filePath != null && fileDocument == session.document()
                  ? " — Saved"
                  : savedRevision == session.revision()
                      ? " — Recovery saved"
                      : " — Unsaved changes")
              + " ▾";
      if (!title.equals(documentTitle.getText())) {
        documentTitle.setText(title);
        documentTitle.getParent().revalidate();
      }
    }
    if (quickUndo != null) quickUndo.setEnabled(session.canUndo());
    if (quickRedo != null) quickRedo.setEnabled(session.canRedo());
    Block selectedBlock = session.editingDocument().blocks.get(session.selection().block);
    String selectedStyle =
        selectedBlock instanceof Paragraph ? ((Paragraph) selectedBlock).style : "";
    for (Map.Entry<String, Container> entry : styleTiles.entrySet()) {
      boolean selected = entry.getKey().replace(" ", "").equals(selectedStyle.replace(" ", ""));
      OfficeTheme.surface(entry.getValue(), selected ? 0xe5eafa : 0xffffff);
      entry
          .getValue()
          .getAllStyles()
          .setBorder(Border.createLineBorder(1, selected ? OfficeTheme.BLUE : 0xe0e0e0));
    }
    syncingControls = false;
    ribbon.repaint();
  }

  private void updateOutline() {
    StringBuilder signature = new StringBuilder();
    for (int i = 0; i < session.document().blocks.size(); i++) {
      Block block = session.document().blocks.get(i);
      if (block instanceof Paragraph) {
        Paragraph p = (Paragraph) block;
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
      Block block = session.document().blocks.get(i);
      if (block instanceof Paragraph) {
        Paragraph p = (Paragraph) block;
        if (p.style.startsWith("Heading") && p.text.length() > 0) {
          final int index = i;
          Button b =
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
                button("Select range…", this::selectCellRange),
                button("Clear Cell Contents", () -> tableCommand("clearCells")),
                button("Merge Selected Cells", () -> tableCommand("mergeSelected")))),
        OfficeTheme.group(
            "Split",
            BoxLayout.encloseY(
                button("Split Cells…", this::splitCells),
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
                button("Row height…", this::tableRowHeight),
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
                button("Formula…", this::tableFormula),
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
                    "Table properties…",
                    () -> {
                      if (!session.inCell())
                        throw new IllegalArgumentException("Click inside a table first");
                      tableProperties(session.cellBlock(), session.cellRow(), session.cellColumn());
                    }),
                button("Rows and Columns…", this::tableLayout))));
  }

  private void tableRowHeight() {
    if (!session.inCell()) throw new IllegalArgumentException("Click inside a table first");
    TableFormat.Row row =
        ((Table) session.document().blocks.get(session.cellBlock())).format.row(session.cellRow());
    EditField height = field("" + (row.height == 0 ? 18 : row.height));
    ComboBox<String> rule = new ComboBox<String>("At least", "Exactly");
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
    EditField formula = field("=SUM(ABOVE)");
    Container body =
        BoxLayout.encloseY(
            formRow("Formula", formula),
            new SpanLabelCompat(
                "Use SUM, AVERAGE, COUNT, MIN, MAX or PRODUCT with ABOVE, BELOW,"
                    + " LEFT, RIGHT, or cell ranges such as A1:B3. Numbers use a"
                    + " decimal point."));
    if (showDialog("Table Formula", body, "Insert", "Cancel"))
      run("table.formula", Json.object("formula", formula.getText()));
  }

  private void splitCells() {
    if (!session.inCell()) throw new IllegalArgumentException("Click inside a table first");
    EditField columns = field("2");
    columns.setAccessibilityText("Number of split columns");
    if (showDialog("Split Cells", formRow("Number of columns", columns), "Split", "Cancel"))
      run("table.split", Json.object("columns", Integer.parseInt(columns.getText())));
  }

  private Container cellAlignmentControls() {
    Container grid = new Container(new GridLayout(3, 3));
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
      final String value = values[i];
      final int alignment = i;
      Button control =
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
    CellSelection range = session.cellSelection();
    EditField firstRow = field("" + (range == null ? session.cellRow() + 1 : range.firstRow + 1));
    EditField firstColumn =
        field("" + (range == null ? session.cellColumn() + 1 : range.firstColumn + 1));
    EditField lastRow = field("" + (range == null ? session.cellRow() + 1 : range.lastRow + 1));
    EditField lastColumn =
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
    Table t = (Table) session.document().blocks.get(block);
    Dialog dialog = officeDialog("Table · row " + (row + 1) + ", column " + (col + 1));
    dialog.setLayout(new BorderLayout());
    Container actions = new Container(new GridLayout(OfficeTheme.mobile() ? 2 : 3));
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
            "Table properties…",
            () -> {
              dialog.dispose();
              tableProperties(block, row, col);
            }));
    actions.add(
        button(
            "Merge cells…",
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
            "Sort…",
            () -> {
              ComboBox<String> order = new ComboBox<String>("Ascending", "Descending");
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
    Table t = (Table) session.document().blocks.get(block);
    CellSelection selectedCells = session.cellSelection();
    TableFormat.Cell cell = t.format.cell(row, column);
    TableFormat.Sizing size = t.format.sizing;
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
    ComboBox<String> rowRule = new ComboBox<String>("Automatic", "At least", "Exactly");
    rowRule.setSelectedItem(
        "exact".equals(t.format.row(row).rule)
            ? "Exactly"
            : "atLeast".equals(t.format.row(row).rule) ? "At least" : "Automatic");
    CheckBox allowBreak = new CheckBox("Allow row to break across pages");
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
      TableFormat.Cell value =
          new TableFormat.Cell(
              Integer.parseInt(background.getText(), 16),
              Integer.parseInt(border.getText(), 16),
              Float.parseFloat(stroke.getText()),
              Float.parseFloat(horizontal.getText()),
              Float.parseFloat(vertical.getText()),
              alignment.getSelectedItem());
      TableFormat format =
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
    Button find =
        button(
            "Find next",
            () -> {
              java.util.List<Map<String, Object>> matches =
                  Commands.find(session.document(), query.getText());
              if (matches.isEmpty()) showDialog("Find", "No matches", "OK", null);
              else {
                Map<String, Object> match = matches.get(0);
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
    EditField command = field("document.inspect");
    EditField args = field("{}", 5, 45);
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
    EditField area =
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
    Macros.Preview p =
        new Macros(commands).preview(session, macro, Collections.<String, String>emptyMap(), null);
    EditField after = field(join(p.changes) + "\n" + p.after, 12, 55);
    after.setEditable(false);
    if (showDialog("Proposed document — one undo step", after, "Apply", "Cancel")) {
      p.apply(session);
      edited();
    }
  }

  private void showText(String title, String text) {
    EditField area = field(text, 15, 60);
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
                    forgetFile();
                    session.loadDocument(document);
                    fileDocument = document;
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
    final PageSpec spec = session.document().page;
    final LayoutEngine.Result pages = editor.printLayout();
    final String path =
        FileSystemStorage.getInstance().getAppHomePath()
            + "wordprocessor-print-"
            + System.currentTimeMillis()
            + ".pdf";
    printing = true;
    status.setText("Preparing print preview…");
    Display.getInstance()
        .scheduleBackgroundTask(
            () -> {
              try {
                byte[] pdf = PdfExporter.write(spec, pages, DocumentFonts.PDF);
                try (OutputStream out = FileSystemStorage.getInstance().openOutputStream(path)) {
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
                                                  : "Document"
                                                      + " sent"
                                                      + " to printing"
                                                      + " system");
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
    final Document snapshot = session.document();
    final int epoch = documentEpoch;
    safe(
        () -> {
          try {
            byte[] bytes;
            if ("docx".equals(format)) {
              boolean allow = false;
              java.util.List<String> losses = Docx.conversionWarnings(snapshot, source);
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
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              Docx.write(snapshot, source, out, allow);
              bytes = out.toByteArray();
            } else if ("pdf".equals(format))
              bytes = PdfExporter.write(snapshot.page, editor.printLayout(), DocumentFonts.PDF);
            else if ("cn1doc".equals(format)) bytes = ProjectFile.write(snapshot, source);
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
                        "Use DOCX, HTML or native project format to retain" + " tables");
              bytes = TextFormats.write(snapshot, type).getBytes("UTF-8");
            }
            files.save(
                TextUtil.fileName(snapshot.title) + "." + format,
                !asNew && workingFile ? filePath : null,
                bytes,
                (ok, path, message) -> {
                  if (ok && workingFile && path != null && epoch == documentEpoch) {
                    filePath = path;
                    fileFormat = format;
                    fileDocument = snapshot;
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
                          "The saved copy is complete. Save the newer"
                              + " changes before continuing.",
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
    StringBuilder b = new StringBuilder();
    for (String s : list) b.append("• ").append(s).append('\n');
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

  public void stop() {
    WordMcp.stop();
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
      getTextAllStyles().setFgColor(0x303030);
    }
  }

  public static Document sample() {
    Session s = new Session(Document.empty());
    s.replace(
        "A better place for words.\n"
            + "A document engine built with Codename One\n"
            + "Write, revise, and automate from one shared Java codebase. This canvas uses"
            + " a structured document model, cached paragraph layout, and paginated"
            + " rendering.\n"
            + "Your ideas, beautifully structured\n"
            + "Select text to format it. Use the Home tab for headings and alignment,"
            + " Insert for tables, and Layout to change the page. Every edit is undoable.\n"
            + "Automation you can inspect\n"
            + "Open Automate and preview a macro. Changes run on an isolated document;"
            + " applying them creates one undo step. If the document changes first, the"
            + " preview cannot overwrite it.\n"
            + "Dear {{name}}, thank you for trying a different kind of document editor.\n"
            + "A foundation with honest boundaries\n"
            + "This development build supports an initial DOCX subset. It reports"
            + " unsupported content before conversion and preserves the original package"
            + " when exported without edits.");
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
