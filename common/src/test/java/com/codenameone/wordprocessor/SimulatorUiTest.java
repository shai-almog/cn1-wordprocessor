package com.codenameone.wordprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;
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
    // The simulator needs a display; without one (a bare CI runner) Display.init never starts the
    // EDT and every call below would wait forever.
    org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
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
    if (app == null) return;
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
    boolean[] done = {false};
    // Bounded so a stuck EDT fails the test instead of hanging the build.
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
              done[0] = true;
            },
            120000);
    if (!done[0]) throw new AssertionError("EDT call did not finish within 120s");
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


  private static void interactiveScroll(boolean on) throws Exception {
    var laf = editor().getUIManager().getLookAndFeel();
    Field f = com.codename1.ui.plaf.LookAndFeel.class.getDeclaredField("interactiveScroll");
    f.setAccessible(true);
    f.setBoolean(laf, on);
  }

  private static int thumb(String name) throws Exception {
    Field f = Component.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.getInt(editor());
  }

  @Test
  public void scrollbarThumbDragScrollsAndTrackPagesWithoutMovingTheCaret() throws Exception {
    edt(() -> ((Form) field(app, "form")).show());
    for (int i = 0; i < 100 && Display.getInstance().getCurrent() != field(app, "form"); i++)
      Thread.sleep(50);
    // The native desktop themes (macos-aqua, windows-fluent, gnome-adwaita) set
    // interactiveScrollBool; the simulator's test theme does not.
    edt(() -> interactiveScroll(true));
    edt(
        () -> {
          var b = new StringBuilder();
          for (int i = 0; i < 300; i++) b.append("Paragraph ").append(i).append('\n');
          load(b.toString());
          ((Form) field(app, "form")).revalidate();
          editor().resetScroll();
          editor().refresh();
          editor().repaint();
        });
    // The thumb's bounds are recorded when the scrollbar is painted.
    for (int i = 0; i < 100 && thumb("scrollThumbH") <= 0; i++) {
      // A paint queued while another test's form was still showing can be dropped.
      edt(() -> ((Form) field(app, "form")).repaint());
      Thread.sleep(50);
    }
    var e = editor();
    assertTrue("vertical scrollbar painted: current " + Display.getInstance().getCurrent().getClass().getSimpleName()
        + " editor " + e.getWidth() + "x" + e.getHeight() + " scroll " + e.getScrollDimension().getHeight()
        + " scrollableY " + e.isScrollableY() + " visible " + e.isScrollVisible() + " shown " + e.isVisible()
        + " interactive " + e.getUIManager().getLookAndFeel().isInteractiveScroll(), thumb("scrollThumbH") > 0);
    edt(
        () -> {
          var editor = editor();
          int x = editor.getAbsoluteX() + thumb("scrollThumbX") + thumb("scrollThumbW") / 2;
          int y = screenTop(editor) + thumb("scrollThumbY") + thumb("scrollThumbH") / 2;
          var before = session().selection();
          editor.pointerPressed(x, y);
          editor.pointerDragged(x, y + 40);
          editor.pointerDragged(x, y + 120);
          editor.pointerReleased(x, y + 120);
          assertTrue("thumb drag scrolls", editor.getScrollY() > 0);
          var after = session().selection();
          assertEquals("thumb drag leaves the caret", before.block, after.block);
          assertEquals(before.caret, after.caret);
          assertEquals("thumb drag selects nothing", "", session().selectedText());
        });
    Thread.sleep(450);
    final int[] start = new int[1];
    edt(
        () -> {
          var editor = editor();
          start[0] = editor.getScrollY();
          int x = editor.getAbsoluteX() + thumb("scrollThumbX") + thumb("scrollThumbW") / 2;
          int y = screenTop(editor) + thumb("scrollThumbY") + thumb("scrollThumbH") + 10;
          assertTrue("track below the thumb", y < screenTop(editor) + editor.getHeight());
          editor.pointerPressed(x, y);
          editor.pointerReleased(x, y);
          assertEquals("track click leaves the caret", 0, session().selection().block);
        });
    for (int i = 0; i < 60 && editor().getScrollY() <= start[0]; i++) Thread.sleep(50);
    assertTrue("track click pages down", editor().getScrollY() > start[0]);
    // The page scroll animates; later tests must not start while it still moves the view.
    for (int last = -1, i = 0; i < 60 && editor().getScrollY() != last; i++) {
      last = editor().getScrollY();
      Thread.sleep(150);
    }
    // Scrolled away from the top, the thumb still grabs (the framework hit-tests it against a
    // scrolled origin; the editor compensates) and dragging it up scrolls back.
    edt(
        () -> {
          var editor = editor();
          int from = editor.getScrollY();
          assertTrue(from > 0);
          int x = editor.getAbsoluteX() + thumb("scrollThumbX") + thumb("scrollThumbW") / 2;
          int y = screenTop(editor) + thumb("scrollThumbY") + thumb("scrollThumbH") / 2;
          var before = session().selection();
          editor.pointerPressed(x, y);
          editor.pointerDragged(x, y - 20);
          editor.pointerDragged(x, y - 60);
          editor.pointerReleased(x, y - 60);
          assertTrue("scrolled thumb drag scrolls up", editor.getScrollY() < from);
          assertEquals("scrolled thumb drag leaves the caret", before.block, session().selection().block);
          assertEquals("", session().selectedText());
        });
    Thread.sleep(450);
  }

  /** The editor's top on screen: getAbsoluteY() subtracts its own scroll. */
  private static int screenTop(EditorSurface editor) {
    return editor.getAbsoluteY() + editor.getScrollY();
  }

  @Test
  public void anObjectDraggedUpItsPageWrapsTheTextItLandsOn() throws Exception {
    edt(() -> ((Form) field(app, "form")).show());
    for (int i = 0; i < 100 && Display.getInstance().getCurrent() != field(app, "form"); i++)
      Thread.sleep(50);
    edt(
        () -> {
          var b = new StringBuilder();
          for (int i = 0; i < 12; i++) b.append("Line ").append(i).append(" with enough words to reach across the page width\n");
          load(b.toString());
          var blocks = session().document().blocks;
          var square = Picture.drawing("i900", Drawing.of("shape").with("wrap", "square"), 120, 60, "", "left");
          session().replaceDocument(session().document().blocks(blocks.append(square), session().document().nextId));
          ((Form) field(app, "form")).revalidate();
          editor().resetScroll();
          editor().refresh();
        });
    Thread.sleep(300);
    edt(
        () -> {
          var editor = editor();
          int last = session().document().blocks.size() - 1;
          int[] r = editor.pictureRect(last);
          int cx = r[0] + r[2] / 2, cy = r[1] + r[3] / 2;
          editor.pointerPressed(cx, cy);
          editor.pointerReleased(cx, cy);
          Thread.sleep(600);
          editor.pointerPressed(cx, cy);
          editor.pointerDragged(cx + 100, cy - 60);
          editor.pointerDragged(cx + 200, cy - (r[1] - editor.getAbsoluteY() - editor.getScrollY()) + 40);
          editor.pointerReleased(cx + 200, cy - (r[1] - editor.getAbsoluteY() - editor.getScrollY()) + 40);
          editor.refresh();
          int at = -1;
          var blocks = session().document().blocks;
          for (int i = 0; i < blocks.size(); i++) if (blocks.get(i) instanceof Picture) at = i;
          assertTrue("the anchor moves up with the object (now block " + at + ")", at >= 0 && at < 6);
        });
    Thread.sleep(450);
  }

  @Test
  public void narrowRibbonFoldsGroupsFromTheRight() throws Exception {
    edt(
        () -> {
          Component[] groups = new Component[3];
          for (int i = 0; i < 3; i++) {
            var wide = new Label("x");
            wide.setPreferredW(OfficeTheme.px(200));
            groups[i] = OfficeTheme.group("Group " + i, wide);
          }
          var frame = OfficeTheme.groups(groups);
          var host = new Form(new com.codename1.ui.layouts.BorderLayout());
          host.add(com.codename1.ui.layouts.BorderLayout.CENTER, frame);
          host.setWidth(OfficeTheme.px(480));
          host.setHeight(OfficeTheme.px(200));
          host.getContentPane().setWidth(OfficeTheme.px(480));
          host.getContentPane().setHeight(OfficeTheme.px(200));
          host.getContentPane().layoutContainer();
          frame.layoutContainer();
          assertTrue("the first group stays", groups[0].isVisible());
          assertFalse("the last group folds into a button", groups[2].isVisible());
          org.junit.Assert.assertSame("a folded group stays in the tree for appearance updates", frame, groups[2].getParent().getParent());
          var buttons = new java.util.ArrayList<String>();
          collectButtons(frame, buttons);
          assertTrue("its button carries the caption: " + buttons, buttons.stream().anyMatch(t -> t.startsWith("Group 2")));
          host.setWidth(OfficeTheme.px(900));
          host.getContentPane().setWidth(OfficeTheme.px(900));
          host.getContentPane().invalidate();
          host.getContentPane().layoutContainer();
          frame.layoutContainer();
          assertTrue("a wide window unfolds it", groups[2].isVisible());
        });
  }

  private static void collectButtons(Container c, java.util.List<String> out) {
    for (int i = 0; i < c.getComponentCount(); i++) {
      var child = c.getComponentAt(i);
      if (child instanceof Button b && b.isVisible()) out.add(b.getText());
      if (child instanceof Container k) collectButtons(k, out);
    }
  }

  @Test
  public void selectedObjectResizesByItsHandles() throws Exception {
    edt(() -> ((Form) field(app, "form")).show());
    for (int i = 0; i < 100 && Display.getInstance().getCurrent() != field(app, "form"); i++)
      Thread.sleep(50);
    final int[] canvas = {-1};
    edt(
        () -> {
          load("Before");
          ((Form) field(app, "form")).revalidate();
          editor().resetScroll();
          Method pen = WordProcessor.class.getDeclaredMethod("selectDrawTool", String.class);
          pen.setAccessible(true);
          pen.invoke(app, "pen");
          pen.invoke(app, "");
          editor().refresh();
          assertFalse(editor().isInking());
          var blocks = session().document().blocks;
          for (int i = 0; i < blocks.size(); i++)
            if (blocks.get(i) instanceof Picture p && p.drawing != null && "canvas".equals(p.drawing.kind))
              canvas[0] = i;
          assertTrue(canvas[0] >= 0);
        });
    Thread.sleep(450);
    edt(
        () -> {
          var editor = editor();
          int[] r = editor.pictureRect(canvas[0]);
          var before = (Picture) session().document().blocks.get(canvas[0]);
          // A click selects; the bottom-right handle then drags the size, keeping the ratio.
          editor.pointerPressed(r[0] + r[2] / 2, r[1] + r[3] / 2);
          editor.pointerReleased(r[0] + r[2] / 2, r[1] + r[3] / 2);
          // Twice its size: wider than the page, so the layout draws it scaled down (as on CI's
          // smaller window, where the side handle below then changed the height).
          editor.pointerPressed(r[0] + r[2], r[1] + r[3]);
          editor.pointerDragged(r[0] + r[2] + 20, r[1] + r[3] + 20);
          editor.pointerDragged(r[0] + 2 * r[2], r[1] + 2 * r[3]);
          editor.pointerReleased(r[0] + 2 * r[2], r[1] + 2 * r[3]);
          editor.refresh();
          var after = (Picture) session().document().blocks.get(canvas[0]);
          assertTrue("the corner handle enlarges the object", after.width > before.width + 5);
          assertEquals("a corner keeps the aspect ratio", before.width / before.height, after.width / after.height, .01);
          // A side handle changes one dimension only.
          int[] s = editor.pictureRect(canvas[0]);
          editor.pointerPressed(s[0] + s[2], s[1] + s[3] / 2);
          editor.pointerDragged(s[0] + s[2] - 30, s[1] + s[3] / 2);
          editor.pointerReleased(s[0] + s[2] - 30, s[1] + s[3] / 2);
          var narrow = (Picture) session().document().blocks.get(canvas[0]);
          assertTrue("the right handle narrows it", narrow.width < after.width - 5);
          assertEquals("the right handle keeps the height", after.height, narrow.height, .01);
          session().undo();
          assertEquals("one undo step", after.width, ((Picture) session().document().blocks.get(canvas[0])).width, .01);
        });
    // Past the double-click interval, so the next click selects instead of opening the settings.
    Thread.sleep(600);
    edt(
        () -> {
          var editor = editor();
          // The rotation handle above the top edge turns it to where it is dragged: due right of
          // the center is a quarter turn.
          editor.refresh();
          int[] u = editor.pictureRect(canvas[0]);
          editor.pointerPressed(u[0] + u[2] / 2, u[1] + u[3] / 2);
          editor.pointerReleased(u[0] + u[2] / 2, u[1] + u[3] / 2);
          int top = u[1] - OfficeTheme.px(18);
          editor.pointerPressed(u[0] + u[2] / 2, top);
          editor.pointerDragged(u[0] + u[2] / 2 + 40, top);
          editor.pointerDragged(u[0] + u[2] + 80, u[1] + u[3] / 2 + 2);
          editor.pointerReleased(u[0] + u[2] + 80, u[1] + u[3] / 2 + 2);
          var turned = (Picture) session().document().blocks.get(canvas[0]);
          assertEquals("the rotation handle turns it, snapping to 90", 90, turned.drawing.num("rotation", 0), .01);
          // Turned, the handle sits right of the center: dragging it below turns it upside down.
          editor.refresh();
          int cx = u[0] + u[2] / 2, cy = u[1] + u[3] / 2;
          int hx = cx + u[3] / 2 + OfficeTheme.px(18);
          editor.pointerPressed(hx, cy);
          editor.pointerDragged(hx, cy + 40);
          editor.pointerDragged(cx + 1, cy + u[3] + 60);
          editor.pointerReleased(cx + 1, cy + u[3] + 60);
          var half = (Picture) session().document().blocks.get(canvas[0]);
          assertEquals("the turned rotation handle is where it is drawn", 180, half.drawing.num("rotation", 0), .01);
        });
    Thread.sleep(600);
    OfficeTheme.mobileOverride = Boolean.TRUE;
    try {
      edt(
          () -> {
            var editor = editor();
            // Back to unturned: both rotations.
            session().undo();
            session().undo();
            editor.refresh();
            int[] r = editor.pictureRect(canvas[0]);
            var before = (Picture) session().document().blocks.get(canvas[0]);
            // Touch: a tap selects; a finger landing near, not on, a corner handle resizes.
            editor.pointerPressed(r[0] + r[2] / 2, r[1] + r[3] / 2);
            editor.pointerReleased(r[0] + r[2] / 2, r[1] + r[3] / 2);
            int off = OfficeTheme.px(10);
            editor.pointerPressed(r[0] + r[2] + off, r[1] + r[3] + off);
            editor.pointerDragged(r[0] + r[2] + off + 30, r[1] + r[3] + off + 30);
            editor.pointerReleased(r[0] + r[2] + off + 30, r[1] + r[3] + off + 30);
            var after = (Picture) session().document().blocks.get(canvas[0]);
            assertTrue("a touch on the corner handle resizes", after.width > before.width + 5);
          });
    } finally {
      OfficeTheme.mobileOverride = Boolean.FALSE;
    }
    Thread.sleep(450);
  }

  @Test
  public void drawModeKeepsTheCanvasMovableAndEscapeLeavesIt() throws Exception {
    edt(() -> ((Form) field(app, "form")).show());
    for (int i = 0; i < 100 && Display.getInstance().getCurrent() != field(app, "form"); i++)
      Thread.sleep(50);
    final int[] canvas = {-1};
    edt(
        () -> {
          load("Before");
          ((Form) field(app, "form")).revalidate();
          editor().resetScroll();
          Method pen = WordProcessor.class.getDeclaredMethod("selectDrawTool", String.class);
          pen.setAccessible(true);
          pen.invoke(app, "pen");
          editor().refresh();
          assertTrue("pen starts draw mode", editor().isInking());
          var blocks = session().document().blocks;
          for (int i = 0; i < blocks.size(); i++)
            if (blocks.get(i) instanceof Picture p && p.drawing != null && "canvas".equals(p.drawing.kind))
              canvas[0] = i;
          assertTrue("pen inserts a canvas", canvas[0] >= 0);
        });
    Thread.sleep(450);
    edt(
        () -> {
          var editor = editor();
          int[] r = editor.pictureRect(canvas[0]);
          assertNotNull(r);
          String strokes = DocumentXml.write(session().document());
          // A press in the middle draws.
          editor.pointerPressed(r[0] + r[2] / 2, r[1] + r[3] / 2);
          editor.pointerDragged(r[0] + r[2] / 2 + 20, r[1] + r[3] / 2 + 10);
          editor.pointerReleased(r[0] + r[2] / 2 + 20, r[1] + r[3] / 2 + 10);
          assertNotEquals("the stroke lands in the canvas", strokes, DocumentXml.write(session().document()));
        });
    Thread.sleep(450);
    edt(
        () -> {
          var editor = editor();
          int[] r = editor.pictureRect(canvas[0]);
          // Dragging the canvas by its edge moves it instead of drawing.
          String before = DocumentXml.write(session().document());
          editor.pointerPressed(r[0] + 2, r[1] + r[3] / 2);
          editor.pointerDragged(r[0] + 30, r[1] + r[3] / 2 + 40);
          editor.pointerDragged(r[0] + 80, r[1] + r[3] / 2 + 80);
          editor.pointerReleased(r[0] + 80, r[1] + r[3] / 2 + 80);
          editor.refresh();
          int[] moved = null;
          var blocks = session().document().blocks;
          for (int i = 0; i < blocks.size(); i++)
            if (blocks.get(i) instanceof Picture p && p.drawing != null && "canvas".equals(p.drawing.kind))
              moved = editor.pictureRect(i);
          assertTrue("edge drag moves the canvas",
              moved != null && (moved[0] != r[0] || moved[1] != r[1]) || !before.equals(DocumentXml.write(session().document())));
          // Escape leaves draw mode, through the raw key path a port uses with no input session.
          editor.stopInput();
          editor.keyPressed(27);
          assertFalse("Escape leaves draw mode", editor.isInking());
        });
    edt(
        () -> {
          // The Mac port reports Escape as the input session's editor action (cancelOperation:);
          // it leaves draw mode and, as it once did, must not break the paragraph.
          Method pen = WordProcessor.class.getDeclaredMethod("selectDrawTool", String.class);
          pen.setAccessible(true);
          pen.invoke(app, "pen");
          assertTrue(editor().isInking());
          int blocks = session().document().blocks.size();
          editor().onEditorAction(0);
          assertFalse("the Mac's Escape leaves draw mode", editor().isInking());
          editor().onEditorAction(0);
          assertEquals("Escape adds no paragraph", blocks, session().document().blocks.size());
        });
    Thread.sleep(450);
  }

  @Test
  public void everyRibbonTabLaysOutWithoutOverlapOrClipping() throws Exception {
    edt(() -> ((Form) field(app, "form")).show());
    for (int i = 0; i < 100 && Display.getInstance().getCurrent() != field(app, "form"); i++)
      Thread.sleep(50);
    edt(
        () -> {
          var ribbon = (OfficeRibbon) field(app, "ribbon");
          var out = RibbonLint.check(ribbon, ((Form) field(app, "form"))::revalidate, false);
          assertTrue(String.join("\n", out), out.isEmpty());
        });
  }

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
    // Let any click from an earlier test fall outside the multi-click window.
    Thread.sleep(450);
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
  public void dragAndShiftArrowsSelectFromBodyIntoTable() throws Exception {
    final int[] from = new int[2], to = new int[2];
    edt(() -> interactiveScroll(true));
    edt(() -> ((Form) field(app, "form")).show());
    for (int i = 0; i < 100 && Display.getInstance().getCurrent() != field(app, "form"); i++)
      Thread.sleep(50);
    edt(
        () -> {
          load("Body");
          var s = session();
          s.select(0, 4, 4);
          s.insertTable(1, 2);
          s.replace("cell");
          Form form = (Form) field(app, "form");
          form.revalidate();
          editor().resetScroll();
          editor().refresh();
          s.select(0, 2, 2);
          int[] r = editor().getCaretRect();
          to[0] = r[0] + 1;
          to[1] = r[1] + Math.max(1, r[3] / 2);
          s.leaveCell();
          s.select(0, 1, 1);
          editor().refresh();
          r = editor().getCaretRect();
          from[0] = r[0] + 1;
          from[1] = r[1] + Math.max(1, r[3] / 2);
        });
    edt(
        () -> {
          var editor = editor();
          editor.pointerPressed(from[0], from[1]);
          editor.pointerDragged((from[0] + to[0]) / 2, (from[1] + to[1]) / 2);
          editor.pointerDragged(to[0], to[1]);
          editor.pointerReleased(to[0], to[1]);
          assertNotNull("drag into the table selects across stories", session().mixedSelection());
          assertEquals("ody\nce", session().selectedText());
          // Painting the cross-story highlight must not fail.
          var image = com.codename1.ui.Image.createImage(editor.getWidth(), editor.getHeight());
          editor.paintComponent(image.getGraphics(), false);
          final int shift = TextInputClient.MOD_SHIFT;
          key(TextInputClient.KEY_RIGHT, shift);
          assertEquals("ody\ncel", session().selectedText());
          key(TextInputClient.KEY_RIGHT, 0);
          assertNull(session().mixedSelection());
          // Shift+Right at the end of the body paragraph crosses into the first cell.
          session().leaveCell();
          session().select(0, 4, 4);
          key(TextInputClient.KEY_RIGHT, shift);
          key(TextInputClient.KEY_RIGHT, shift);
          assertEquals("\nc", session().selectedText());
          key(TextInputClient.KEY_LEFT, shift);
          key(TextInputClient.KEY_LEFT, shift);
          assertNull(session().mixedSelection());
          assertFalse(session().inCell());
          key(TextInputClient.KEY_RIGHT, shift);
          key(TextInputClient.KEY_RIGHT, shift);
          key(TextInputClient.KEY_DELETE, 0);
          assertEquals("ell", ((Table) session().document().blocks.get(1)).cell(0, 0).get(0).text.toString());
          session().undo();
          assertEquals("cell", ((Table) session().document().blocks.get(1)).cell(0, 0).get(0).text.toString());
        });
    // Let the click counter lapse so later tests' first click is a single click.
    Thread.sleep(450);
  }

  @Test
  public void clickSelectsDragsAndFormatsACellPictureThroughItsCellPath() throws Exception {
    final int[][] at = new int[2][];
    final int[] picked = {-1};
    final Object[] saved = new Object[1];
    edt(() -> ((Form) field(app, "form")).show());
    for (int i = 0; i < 100 && Display.getInstance().getCurrent() != field(app, "form"); i++)
      Thread.sleep(50);
    edt(
        () -> {
          load("Body");
          var s = session();
          s.select(0, 4, 4);
          s.insertTable(1, 2);
          s.replace("cell");
          // Insert Picture with the caret in a cell inserts into the cell.
          int k = s.insertPicture(Picture.drawing("pending", Drawing.of("shape").with("shape", "rect"), 60, 30, "box", "left"));
          assertTrue(s.inCell());
          assertTrue(s.editingDocument().blocks.get(k) instanceof Picture);
          s.leaveCell();
          Form form = (Form) field(app, "form");
          form.revalidate();
          editor().resetScroll();
          editor().refresh();
          var pe = EditorSurface.class.getDeclaredField("pictureEditor");
          pe.setAccessible(true);
          saved[0] = pe.get(editor());
          editor().setPictureEditor(b -> picked[0] = b);
          LayoutEngine.PlacedPicture pic = null;
          for (var page : editor().layout().pages) for (var p : page.pictures) if (p.inCell()) pic = p;
          assertNotNull(pic);
          at[0] = editor().screenPointForTest(pic.page(), pic.x() + pic.width() / 2, pic.y() + pic.height() / 2);
          at[1] = new int[] {k};
        });
    Thread.sleep(450);
    edt(
        () -> {
          var editor = editor();
          editor.pointerPressed(at[0][0], at[0][1]);
          editor.pointerReleased(at[0][0], at[0][1]);
          var s = session();
          assertTrue("click selects the cell picture in its cell", s.inCell());
          assertEquals("a single click only selects", -1, picked[0]);
          editor.pointerPressed(at[0][0], at[0][1]);
          editor.pointerReleased(at[0][0], at[0][1]);
          assertEquals("a double-click opens the picture settings", at[1][0], picked[0]);
          new Commands().execute(s, "picture.border", Json.object("color", "ff0000", "width", 2));
          var p = (Picture) s.editingDocument().blocks.get(picked[0]);
          assertEquals(0xff0000, p.look.borderColor);
          // A drag moves it within its cell: Square wrapped, offset from the cell's content.
          editor.pointerPressed(at[0][0], at[0][1]);
          editor.pointerDragged(at[0][0] + 20, at[0][1]);
          editor.pointerReleased(at[0][0] + 20, at[0][1]);
          p = (Picture) session().editingDocument().blocks.get(picked[0]);
          assertEquals("square", p.wrap());
          assertTrue(p.position(), FloatPosition.parse(p.position()).x > 0);
          session().undo();
          session().undo();
          Picture back = null;
          for (Block b : ((Table) session().document().blocks.get(1)).blocks(0, 0)) if (b instanceof Picture x) back = x;
          assertNotNull(back);
          assertEquals("inline", back.wrap());
          assertTrue(back.look.borderColor != 0xff0000);
          editor.setPictureEditor((EditorSurface.PictureEditor) saved[0]);
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

  @Test
  public void enteredNoteStoryDrawsCaretAndSelectionInNoteArea() throws Exception {
    final int[] point = new int[2];
    edt(() -> ((Form) field(app, "form")).show());
    for (int i = 0; i < 100 && Display.getInstance().getCurrent() != field(app, "form"); i++)
      Thread.sleep(50);
    edt(
        () -> {
          load("Claim");
          var s = session();
          s.select(0, 5, 5);
          s.insertNote("footnote", "Source text", "");
          ((Form) field(app, "form")).revalidate();
          editor().resetScroll();
          editor().refresh();
          s.select(0, 0, 0);
          int bodyY = editor().getCaretRect()[1];
          s.enterNote(Notes.references(s.document()).get(0).note.id);
          s.select(0, 6, 6);
          editor().refresh();
          int[] r = editor().getCaretRect();
          assertNotNull("caret drawn in the note area", r);
          assertTrue("note caret below the body text", r[1] > bodyY);
          point[0] = r[0] + 1;
          point[1] = r[1] + Math.max(1, r[3] / 2);
          s.select(0, 0, 0);
        });
    edt(
        () -> {
          var editor = editor();
          editor.pointerPressed(point[0], point[1]);
          editor.pointerReleased(point[0], point[1]);
          assertNotNull("click keeps the note story", session().noteStory());
          assertEquals(6, session().selection().caret);
          session().leaveCell();
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
          // The simulator reports modifiers from the last real input its window got; pin them.
          EditorSurface.rawModifiersForTest = 0;
          boolean quiet = EditorSurface.quietWithoutInput;
          try {
          if (editor.textInputActive()) {
            org.junit.Assert.assertFalse("Active input owns the keys", editor.rawKey(right));
            editor.stopInput();
          }
          org.junit.Assert.assertFalse("Input stopped", editor.textInputActive());
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
          EditorSurface.rawModifiersForTest = TextInputClient.MOD_SHIFT;
          editor.keyPressed(right);
          assertEquals("Shift extends the selection", 1, session().selection().end() - session().selection().start());
          } finally {
            EditorSurface.rawModifiersForTest = -1;
            EditorSurface.quietWithoutInput = quiet;
          }
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
  public void readAloudOpensFloatingPlayerUntilStop() throws Exception {
    Speech.install(
        new Speech.Engine() {
          public boolean speechSupported() { return true; }
          public void speak(String text, float rate) {}
          public void stop() {}
          public boolean dictationSupported() { return false; }
          public void dictate(java.util.function.Consumer<String> result) { result.accept(null); }
        });
    try {
      edt(
          () -> {
            var session = session();
            session.replaceDocument(Document.empty());
            session.replace("One sentence. Two sentence.");
            session.select(0, 0, 0);
            editor().refresh();
            Method read = WordProcessor.class.getDeclaredMethod("readAloud", String.class);
            read.setAccessible(true);
            read.invoke(app, "start");
            // Wordcraft's floating player: transport, status, speed with a 1x reset, skip citations.
            var player = (Container) field(app, "readAloudPlayer");
            assertNotNull("player open", player);
            assertNotNull("player floats over the form", player.getParent());
            for (String n : new String[] {"Player Previous sentence", "Player Pause", "Player Next sentence",
                "Player Stop", "Player status", "Player speed", "Normal speed", "Player skip citations", "Close player"})
              assertNotNull(n, named(player, n));
            assertEquals("Sentence 1 / 2", ((com.codename1.ui.Label) named(player, "Player status")).getText());
            read.invoke(app, "playPause");
            assertEquals("Paused", ((com.codename1.ui.Label) named(player, "Player status")).getText());
            org.junit.Assert.assertSame(player, field(app, "readAloudPlayer"));
            ((com.codename1.ui.Button) named(player, "Player Stop")).released();
            assertNull("stop closes the player", field(app, "readAloudPlayer"));
            // The ribbon controls remain.
            assertNotNull(field(app, "readAloudStatus"));
          });
    } finally {
      edt(() -> {
        Method read = WordProcessor.class.getDeclaredMethod("readAloud", String.class);
        read.setAccessible(true);
        read.invoke(app, "stop");
      });
      Speech.install(null);
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
  public void smartArtDesignTabWithLayoutPreviews() throws Exception {
    edt(
        () -> {
          var session = session();
          session.replaceDocument(Document.empty());
          var ribbon = (OfficeRibbon) field(app, "ribbon");
          call(app, "updateStatus");
          assertFalse(ribbon.tabVisible("SmartArt Design"));
          new Commands().execute(session, "insert.smartArt", Json.object("layout", "cycle"));
          editor().refresh();
          call(app, "updateStatus");
          // Wordcraft's contextual SmartArt Design tab with its Layouts gallery of drawn previews.
          assertTrue(ribbon.tabVisible("SmartArt Design"));
          var thumbs = (com.codename1.ui.Button[]) field(app, "smartArtThumbs");
          assertEquals(8, thumbs.length);
          for (var b : thumbs) assertNotNull(b.getName(), b.getIcon());
          assertEquals(Boolean.TRUE, thumbs[2].getClientProperty("selected"));
          thumbs[4].released();
          assertEquals("pyramid", ((Map<?, ?>) new Commands().execute(session, "smartArt.get", Json.object())).get("layout"));
          call(app, "updateStatus");
          assertEquals(Boolean.TRUE, thumbs[4].getClientProperty("selected"));
          assertNull(thumbs[2].getClientProperty("selected"));
          // A preview draws the layout's shapes, in Wordcraft's default theme accents.
          assertTrue(hasColor(WordProcessor.smartArtPreview("matrix", "accent2", 46, 31), 0xe97132));
          // ... or the document theme's (Wordcraft's doc.settings.theme_colors).
          session.documentProperties(session.document().properties.withSetting("theme.accents",
              "112233,445566,778899,AABBCC,DDEEFF,123456"));
          assertTrue(hasColor(WordProcessor.smartArtPreview("matrix", "accent2",
              DocxTheme.accents(session.document().properties), 46, 31), 0x445566));
          call(app, "updateStatus");
          assertTrue(hasColor(thumbs[0].getIcon(), 0x112233));
          // Ribbon item commands act on the Text Pane's current item, else the last (Wordcraft).
          new Commands().execute(session, "smartArt.textPane", Json.object("nodes", "A\nB\nC"));
          Method rb = WordProcessor.class.getDeclaredMethod("smartArtRibbon");
          rb.setAccessible(true);
          Container tab = (Container) rb.invoke(app);
          app.smartArtItem = 0;
          ((com.codename1.ui.Button) named(tab, "Add Shape")).released();
          assertEquals("A\nText\nB\nC", String.valueOf(new Commands().execute(session, "smartArt.textPane", Json.object())).trim());
          app.smartArtItem = 2;
          ((com.codename1.ui.Button) named(tab, "Demote")).released();
          assertEquals("A\nText\n\tB\nC", String.valueOf(new Commands().execute(session, "smartArt.textPane", Json.object())).trim());
          app.smartArtItem = 9;
          ((com.codename1.ui.Button) named(tab, "Add Shape")).released();
          assertTrue(String.valueOf(new Commands().execute(session, "smartArt.textPane", Json.object())).trim().endsWith("C\nText"));
          session.replaceDocument(Document.empty());
          call(app, "updateStatus");
          assertFalse(ribbon.tabVisible("SmartArt Design"));
        });
  }

  private static boolean hasColor(com.codename1.ui.Image img, int color) {
    for (int v : img.getRGB()) if ((v & 0xffffff) == color && (v >>> 24) != 0) return true;
    return false;
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
