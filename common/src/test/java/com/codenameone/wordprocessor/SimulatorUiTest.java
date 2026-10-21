package com.codenameone.wordprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.codename1.ui.Button;
import com.codename1.ui.Command;
import com.codename1.ui.Component;
import com.codename1.ui.Container;
import com.codename1.ui.Dialog;
import com.codename1.ui.Display;
import com.codename1.ui.Form;
import com.codename1.ui.Label;
import com.codename1.ui.TextInputClient;
import com.codename1.ui.editor.EditorView;
import com.codename1.ui.plaf.Style;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Drives the real WordProcessor UI inside the Codename One simulator, in-process only: component
 * methods and EditorSurface input callbacks, never OS-level input or window focus.
 */
public class SimulatorUiTest {
  private static WordProcessor app;
  private static String savedTheme;

  @BeforeClass
  public static void boot() throws Exception {
    if (!Display.isInitialized()) Display.init(null);
    // An uncaught EDT error would open a modal error dialog and hang the run; log it instead.
    Display.getInstance()
        .addEdtErrorHandler(
            e -> {
              System.out.println("EDT_ERROR " + e.getSource());
              if (e.getSource() instanceof Throwable t) t.printStackTrace(System.out);
              e.consume();
            });
    OfficeTheme.mobileOverride = Boolean.FALSE;
    EditorSurface.quietWithoutInput = true;
    savedTheme = AppOptions.get("officeTheme");
    edt(
        () -> {
          app = new WordProcessor();
          app.init(null);
          app.start();
        });
  }

  @AfterClass
  public static void restore() throws Exception {
    edt(
        () -> {
          AppOptions.set("officeTheme", savedTheme == null ? "" : savedTheme);
          OfficeTheme.refreshAppearance();
        });
    OfficeTheme.mobileOverride = null;
  }

  interface Body {
    void run() throws Exception;
  }

  private static void edt(Body body) throws Exception {
    Exception[] error = {null};
    Error[] fail = {null};
    Display.getInstance()
        .callSeriallyAndWait(
            () -> {
              try {
                body.run();
              } catch (Exception e) {
                error[0] = e;
              } catch (Error e) {
                fail[0] = e;
              }
            });
    if (fail[0] != null) throw fail[0];
    if (error[0] != null) throw error[0];
  }

  private static Object field(Object target, String name) throws Exception {
    Field f = WordProcessor.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private static void call(Object target, String name) throws Exception {
    Method m = WordProcessor.class.getDeclaredMethod(name);
    m.setAccessible(true);
    m.invoke(target);
  }

  private static Session session() throws Exception {
    return (Session) field(app, "session");
  }

  private static EditorSurface editor() throws Exception {
    return (EditorSurface) field(app, "editor");
  }

  // ---- 1. Dark mode ------------------------------------------------------------------------

  private static float luminance(int rgb) {
    return (0.2126f * ((rgb >> 16) & 0xff) + 0.7152f * ((rgb >> 8) & 0xff) + 0.0722f * (rgb & 0xff))
        / 255f;
  }

  private static void collectLightChrome(Component c, List<String> out) {
    if (c instanceof EditorSurface || !c.isVisible()) return;
    Style s = c.getUnselectedStyle();
    if ((s.getBgTransparency() & 0xff) > 0 && luminance(s.getBgColor()) > 0.85f)
      out.add("white bg: " + describe(c) + " #" + Integer.toHexString(s.getBgColor()));
    // Dark text is fine on a light accent fill (the mapped Share button); flag it on dark chrome.
    boolean ownLightFill = (s.getBgTransparency() & 0xff) > 0 && luminance(s.getBgColor()) > 0.5f;
    if (c instanceof Label l && l.getText() != null && !l.getText().isEmpty()
        && !ownLightFill && luminance(s.getFgColor()) < 0.15f)
      out.add("black text: " + describe(c) + " #" + Integer.toHexString(s.getFgColor()));
    if (c instanceof EditorView && luminance(s.getBgColor()) > 0.5f)
      out.add("light editor: " + describe(c) + " #" + Integer.toHexString(s.getBgColor()));
    if (c instanceof Container k)
      for (int i = 0; i < k.getComponentCount(); i++) collectLightChrome(k.getComponentAt(i), out);
  }

  private static String describe(Component c) {
    String text = c instanceof Label l ? " '" + l.getText() + "'" : "";
    return c.getClass().getName() + "/" + c.getUIID() + text;
  }

  private static void forceDark() throws Exception {
    AppOptions.set("officeTheme", "dark");
    OfficeTheme.refreshAppearance();
    assertTrue(OfficeTheme.dark());
    Form form = (Form) field(app, "form");
    OfficeTheme.applyAppearance(form);
    form.revalidate();
  }

  @Test
  public void darkModeChromeHasNoLightSurfaces() throws Exception {
    edt(
        () -> {
          forceDark();
          var bad = new ArrayList<String>();
          collectLightChrome((Form) field(app, "form"), bad);
          assertTrue("Light chrome in dark mode: " + bad, bad.isEmpty());
        });
  }

  @Test
  public void darkModeSettingsDialogHasNoLightSurfaces() throws Exception {
    edt(SimulatorUiTest::forceDark);
    Display.getInstance()
        .callSerially(
            () -> {
              try {
                call(app, "preferences");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    Dialog dialog = null;
    for (int i = 0; i < 100 && dialog == null; i++) {
      Thread.sleep(50);
      if (Display.getInstance().getCurrent() instanceof Dialog d) dialog = d;
    }
    assertNotNull("Settings dialog shown", dialog);
    final Dialog shown = dialog;
    try {
      edt(
          () -> {
            var bad = new ArrayList<String>();
            collectLightChrome(shown.getContentPane(), bad);
            assertTrue("Light Settings chrome in dark mode: " + bad, bad.isEmpty());
          });
    } finally {
      edt(shown::dispose);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void normalStyleTileDoesNotFlickerOnEdits() throws Exception {
    edt(
        () -> {
          forceDark();
          var tile = ((Map<String, Container>) field(app, "styleTiles")).get("Normal");
          var session = session();
          var editor = editor();
          session.replaceDocument(Document.empty());
          editor.refresh();
          call(app, "edited");
          int bg = tile.getUnselectedStyle().getBgColor();
          int alpha = tile.getUnselectedStyle().getBgTransparency() & 0xff;
          assertTrue("Selected Normal tile is dark", alpha == 0 || luminance(bg) < 0.5f);
          for (String text : new String[] {"a", "b", " c"}) {
            editor.commitText(text);
            call(app, "edited");
            assertEquals(bg, tile.getUnselectedStyle().getBgColor());
            assertEquals(alpha, tile.getUnselectedStyle().getBgTransparency() & 0xff);
          }
          editor.onKeyCommand(TextInputClient.KEY_LEFT, 0);
          call(app, "edited");
          assertEquals(bg, tile.getUnselectedStyle().getBgColor());
        });
  }

  // ---- 2. Mobile side menu -----------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void sideMenuOnlyOnMobile() throws Exception {
    edt(
        () -> {
          var desktopMenu = (List<Command>) field(app, "sideMenu");
          assertTrue("Desktop keeps menus out of the side menu", desktopMenu.isEmpty());
          Form form = (Form) field(app, "form");
        });
    OfficeTheme.mobileOverride = Boolean.TRUE;
    try {
      final WordProcessor[] phone = {null};
      edt(
          () -> {
            phone[0] = new WordProcessor();
            phone[0].init(null);
            phone[0].start();
            var menu = (List<Command>) field(phone[0], "sideMenu");
            assertTrue("Mobile side menu has the menu commands", menu.size() > 3);
            Form form = (Form) field(phone[0], "form");
            assertNotNull(form.getToolbar());
            int found = 0;
            for (Command c : menu) if (form.getToolbar().findCommandComponent(c) != null) found++;
            assertEquals("Side menu commands are in the toolbar", menu.size(), found);
          });
    } finally {
      OfficeTheme.mobileOverride = Boolean.FALSE;
      edt(() -> ((Form) field(app, "form")).show());
    }
  }

  // ---- 3. Key handling end to end ------------------------------------------------------------

  private static void load(String text) throws Exception {
    var session = session();
    session.replaceDocument(Document.empty());
    editor().refresh();
    editor().commitText(text);
    session.select(0, 0, 0);
  }

  private static void key(int command, int modifiers) throws Exception {
    editor().onKeyCommand(command, modifiers);
  }

  private static void at(int block, int caret) throws Exception {
    var s = session().selection();
    assertEquals("block", block, s.block);
    assertEquals("caret", caret, s.caret);
  }

  @Test
  public void keyCommandsMoveSelectAndDelete() throws Exception {
    edt(
        () -> {
          final int alt = TextInputClient.MOD_ALT,
              ctrl = TextInputClient.MOD_CTRL,
              shift = TextInputClient.MOD_SHIFT;
          load("one two three\nfour five\nsix");
          var session = session();
          key(TextInputClient.KEY_RIGHT, alt);
          int wordEnd = session.selection().caret;
          assertTrue("Alt+Right moves by word", wordEnd >= 3 && wordEnd <= 4);
          key(TextInputClient.KEY_LEFT, alt);
          at(0, 0);
          key(TextInputClient.KEY_WORD_RIGHT, 0);
          at(0, wordEnd);
          key(TextInputClient.KEY_WORD_LEFT, 0);
          at(0, 0);
          key(TextInputClient.KEY_RIGHT, ctrl);
          at(0, 13);
          key(TextInputClient.KEY_LEFT, ctrl);
          at(0, 0);
          key(TextInputClient.KEY_DOWN, ctrl);
          at(2, 3);
          key(TextInputClient.KEY_UP, ctrl);
          at(0, 0);
          session.select(1, 4, 4);
          key(TextInputClient.KEY_UP, alt);
          at(1, 0);
          key(TextInputClient.KEY_DOWN, alt);
          assertTrue("Alt+Down moves forward a paragraph", session.selection().block >= 1
              && (session.selection().block > 1 || session.selection().caret == 9));
          // Shift extends each move.
          session.select(0, 0, 0);
          key(TextInputClient.KEY_RIGHT, alt | shift);
          assertEquals(0, session.selection().anchor);
          assertEquals(wordEnd, session.selection().caret);
          key(TextInputClient.KEY_WORD_RIGHT, shift);
          assertEquals(0, session.selection().anchor);
          assertTrue(session.selection().caret > wordEnd);
          session.select(0, 0, 0);
          key(TextInputClient.KEY_RIGHT, ctrl | shift);
          assertEquals(0, session.selection().anchor);
          at(0, 13);
          session.select(0, 0, 0);
          key(TextInputClient.KEY_DOWN, ctrl | shift);
          assertEquals(0, session.selection().anchorBlock);
          at(2, 3);
          session.select(1, 4, 4);
          key(TextInputClient.KEY_UP, alt | shift);
          assertEquals(4, session.selection().anchor);
          at(1, 0);
          // Alt+Backspace deletes the previous word; undo restores it.
          session.select(0, 13, 13);
          key(TextInputClient.KEY_BACKSPACE, alt);
          String after = session.document().plainText();
          assertTrue(after, after.startsWith("one two") && !after.startsWith("one two three"));
          key(TextInputClient.KEY_UNDO, 0);
          assertEquals("one two three\nfour five\nsix", session.document().plainText());
        });
  }

  @Test
  public void rtlParagraphSwapsHorizontalKeys() throws Exception {
    edt(
        () -> {
          load("alpha beta gamma");
          var session = session();
          session.paragraph("rtl", "true");
          assertTrue(session.editingDocument().paragraph(0).format.rtl);
          session.select(0, 0, 0);
          key(TextInputClient.KEY_LEFT, TextInputClient.MOD_ALT);
          assertTrue("Alt+Left goes forward in RTL", session.selection().caret > 0);
          key(TextInputClient.KEY_RIGHT, TextInputClient.MOD_ALT);
          at(0, 0);
          key(TextInputClient.KEY_LEFT, 0);
          at(0, 1);
          key(TextInputClient.KEY_LEFT, TextInputClient.MOD_CTRL);
          at(0, 16);
          key(TextInputClient.KEY_WORD_RIGHT, 0);
          assertTrue("Word right goes back in RTL", session.selection().caret < 16);
        });
  }

  @Test
  public void doubleAndTripleClickSelectWordAndParagraph() throws Exception {
    final int[] point = new int[2];
    edt(() -> ((Form) field(app, "form")).show());
    for (int i = 0; i < 100 && Display.getInstance().getCurrent() != field(app, "form"); i++)
      Thread.sleep(50);
    edt(
        () -> {
          load("one two three\nfour");
          Form form = (Form) field(app, "form");
          form.revalidate();
          editor().resetScroll();
          session().select(0, 5, 5);
          editor().refresh();
          int[] r = editor().getCaretRect();
          point[0] = r[0] + 1;
          point[1] = r[1] + Math.max(1, r[3] / 2);
          session().select(1, 0, 0);
        });
    edt(
        () -> {
          var editor = editor();
          editor.pointerPressed(point[0], point[1]);
          editor.pointerReleased(point[0], point[1]);
          at(0, 5);
          editor.pointerPressed(point[0], point[1]);
          editor.pointerReleased(point[0], point[1]);
          var s = session().selection();
          assertEquals("double click word start", 4, s.start());
          assertEquals("double click word end", 7, s.end());
          editor.pointerPressed(point[0], point[1]);
          editor.pointerReleased(point[0], point[1]);
          s = session().selection();
          assertEquals(0, s.start());
          assertEquals(13, s.end());
        });
  }

  @Test
  public void clickEntersNestedTableCellAndDrawsItsCaret() throws Exception {
    final int[] point = new int[2];
    final int[][] path = new int[1][];
    edt(() -> ((Form) field(app, "form")).show());
    for (int i = 0; i < 100 && Display.getInstance().getCurrent() != field(app, "form"); i++)
      Thread.sleep(50);
    edt(
        () -> {
          load("Body");
          var s = session();
          s.select(0, 4, 4);
          s.insertTable(1, 2);
          s.replace("outer");
          s.insertTable(2, 2);
          s.nextCell(false);
          s.replace("inner");
          path[0] = s.cellPath();
          assertEquals(6, path[0].length);
          Form form = (Form) field(app, "form");
          form.revalidate();
          editor().resetScroll();
          editor().refresh();
          s.select(0, 2, 2);
          int[] r = editor().getCaretRect();
          assertNotNull("caret drawn in the nested cell", r);
          point[0] = r[0] + 1;
          point[1] = r[1] + Math.max(1, r[3] / 2);
          s.leaveCell();
          editor().refresh();
        });
    edt(
        () -> {
          var editor = editor();
          editor.pointerPressed(point[0], point[1]);
          editor.pointerReleased(point[0], point[1]);
          assertArrayEquals(path[0], session().cellPath());
          assertEquals(2, session().selection().caret);
        });
  }

  // ---- 4. Raw key fallback and the Speech group --------------------------------------------

  @Test
  public void rawKeysNavigateOnlyWithoutTextInput() throws Exception {
    edt(
        () -> {
          load("one two");
          var editor = editor();
          var d = Display.getInstance();
          int right = d.getKeyCode(Display.GAME_RIGHT);
          if (editor.textInputActive()) {
            org.junit.Assert.assertFalse("Active input owns the keys", editor.rawKey(right));
            return;
          }
          editor.keyPressed(right);
          at(0, 1);
          editor.keyRepeated(right);
          at(0, 2);
          editor.keyPressed(d.getKeyCode(Display.GAME_LEFT));
          at(0, 1);
          editor.keyPressed(127);
          assertEquals("oe two", session().document().plainText().trim());
          editor.keyPressed(8);
          assertEquals("e two", session().document().plainText().trim());
          at(0, 0);
          org.junit.Assert.assertFalse("Letters are not raw editing keys", editor.rawKey('a'));
        });
  }

  private static Component named(Component c, String name) {
    if (name.equals(c.getName()) || c instanceof Button b && name.equals(b.getText())) return c;
    if (c instanceof Container k)
      for (int i = 0; i < k.getComponentCount(); i++) {
        Component f = named(k.getComponentAt(i), name);
        if (f != null) return f;
      }
    return null;
  }

  @Test
  public void reviewRibbonHasSpeechGroupWithPersistedSpeed() throws Exception {
    String saved = AppOptions.get("readAloudRate");
    try {
      edt(
          () -> {
            Speech.rate(1.5f);
            Object tracking = field(app, "trackingButton");
            Method m = WordProcessor.class.getDeclaredMethod("review");
            m.setAccessible(true);
            Container review = (Container) m.invoke(app);
            Field f = WordProcessor.class.getDeclaredField("trackingButton");
            f.setAccessible(true);
            f.set(app, tracking);
            assertNotNull("Speech group", named(review, "Reading speed"));
            @SuppressWarnings("unchecked")
            com.codename1.ui.ComboBox<String> speed = (com.codename1.ui.ComboBox<String>) named(review, "Reading speed");
            assertEquals("1.5x", speed.getSelectedItem());
            speed.setSelectedIndex(0);
            assertEquals(.5f, Speech.rate(), .001f);
            // Wordcraft's player controls and its 3x top speed.
            assertEquals("3x", speed.getModel().getItemAt(speed.getModel().getSize() - 1));
            for (String n : new String[] {"Previous sentence", "Pause or resume reading", "Next sentence", "Stop Reading", "Read Aloud status"})
              assertNotNull(n, named(review, n));
            com.codename1.ui.CheckBox skip = (com.codename1.ui.CheckBox) named(review, "Skip citations");
            assertTrue(skip.isSelected());
            skip.pressed();
            skip.released();
            assertFalse(Speech.skipCitations());
          });
    } finally {
      AppOptions.set("readAloudRate", saved);
      Speech.skipCitations(true);
    }
  }

  @Test
  public void insertRibbonOffersSmartArt() throws Exception {
    edt(
        () -> {
          Method m = WordProcessor.class.getDeclaredMethod("insert");
          m.setAccessible(true);
          Container insert = (Container) m.invoke(app);
          assertNotNull("SmartArt button", named(insert, "SmartArt"));
        });
  }

  @Test
  public void equationTabAndInEquationCaret() throws Exception {
    edt(
        () -> {
          Method m = WordProcessor.class.getDeclaredMethod("equationRibbon");
          m.setAccessible(true);
          Container tab = (Container) m.invoke(app);
          for (String n : new String[] {"Edit in Place", "Structure\u2026", "Done"}) assertNotNull(n, named(tab, n));
          var session = session();
          session.replaceDocument(Document.empty());
          new Commands().execute(session, "insert.equation", Json.object("text", "a/b", "display", true));
          editor().refresh();
          int block = -1;
          for (int i = 0; i < session.document().blocks.size(); i++)
            if (session.document().blocks.get(i) instanceof Picture) block = i;
          // A click on the equation enters it with the caret at the clicked slot (the
          // denominator, below the middle), through the editor's own click handling.
          var e0 = EquationEditing.at(session, block);
          var d = ((Picture) session.document().blocks.get(block)).drawing;
          e0.editor.pos = new EquationEditor.Pos(new int[] {0, 1}, 1);
          LayoutEngine.PlacedPicture pic = null;
          for (var page : editor().layout().pages)
            for (var p : page.pictures) if (p.block() == block) pic = p;
          assertNotNull(pic);
          float[] den = e0.caret(d, pic.width(), pic.height(), new PdfMetrics());
          assertTrue(editor().clickEquationForTest(block, den[0], den[1] + den[2] / 2));
          var e = editor().equationEditing();
          assertNotNull(e);
          assertArrayEquals(new int[] {0, 1}, e.editor.pos.path);
          editor().commitText("2");
          assertEquals("a/(b2)", ((Picture) session.document().blocks.get(block)).drawing.get("text", ""));
          // Up moves to the numerator; Escape leaves.
          key(TextInputClient.KEY_UP, 0);
          assertArrayEquals(new int[] {0, 0}, editor().equationEditing().editor.pos.path);
          assertTrue(editor().equationStructure(0, "frac.skewed"));
          assertTrue(((Picture) session.document().blocks.get(block)).drawing.get("text", "").contains("\u2044"));
          editor().endEquation(false);
          assertNull(editor().equationEditing());
        });
  }

  @Test
  public void inlineEquationClickAndLatexInput() throws Exception {
    edt(
        () -> {
          Method m = WordProcessor.class.getDeclaredMethod("equationRibbon");
          m.setAccessible(true);
          Container tab = (Container) m.invoke(app);
          var session = session();
          session.replaceDocument(Document.empty());
          editor().commitText("x ");
          new Commands().execute(session, "insert.equation", Json.object("text", "a/b", "display", false));
          editor().commitText(" y");
          editor().refresh();
          int at = -1;
          var para = (Paragraph) session.document().blocks.get(0);
          for (int i = 0; i < para.text.length(); i++) if (Equation.inline(para.text.fieldAt(i))) at = i;
          assertTrue(at > 0);
          // The denominator's caret position, in the inline drawing's own coordinates.
          var e0 = EquationEditing.inline(session, 0, at);
          e0.editor.pos = new EquationEditor.Pos(new int[] {0, 1}, 1);
          var d = Equation.inlineDrawing(para.text.fieldAt(at), TextStyle.DEFAULT);
          float[] den = e0.caret(d, 20, 1, new PdfMetrics());
          int[] pt = editor().inlineEquationPointForTest(0, at, den[0], den[1] + den[2] / 2);
          assertNotNull(pt);
          editor().clickForTest(pt[0], pt[1]);
          var e = editor().equationEditing();
          assertNotNull("a click on an inline equation enters it", e);
          assertEquals(at, e.field);
          assertArrayEquals(new int[] {0, 1}, e.editor.pos.path);
          editor().endEquation(false);
          // The Equation tab's LaTeX toggle: typed LaTeX builds up once its braces balance.
          ((Button) named(tab, "LaTeX")).released();
          assertTrue(session.mathLatex);
          assertTrue(editor().editEquation() || editor().equationEditing() != null);
          editor().equationEditing().editor.homeEnd(true);
          editor().commitText("+\\frac{1}{2} ");
          assertTrue(editor().equationEditing().stored().endsWith("1/2"));
          editor().endEquation(false);
          ((Button) named(tab, "Unicode")).released();
          assertFalse(session.mathLatex);
        });
  }
}
