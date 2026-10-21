package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import java.lang.reflect.Field;

/** In-process port smoke: invokes the same text input callbacks the platform delivers. */
public final class UiSmoke {
  public static void run(WordProcessor app) throws Exception {
    Field sf = WordProcessor.class.getDeclaredField("session"),
        ef = WordProcessor.class.getDeclaredField("editor");
    sf.setAccessible(true);
    ef.setAccessible(true);
    var session = (Session) sf.get(app);
    var editor = (EditorSurface) ef.get(app);
    var original = session.document();
    session.replaceDocument(Document.empty());
    editor.refresh();
    editor.commitText("first\nsecond");
    require("first\nsecond".equals(session.document().plainText()), "Typing/newline");
    editor.onKeyCommand(TextInputClient.KEY_SELECT_ALL, 0);
    editor.commitText("replacement");
    require("replacement".equals(session.document().plainText()), "Cross-paragraph select all");
    editor.onKeyCommand(TextInputClient.KEY_UNDO, 0);
    require("first\nsecond".equals(session.document().plainText()), "Undo input");
    session.select(0, 0, 0);
    editor.onKeyCommand(TextInputClient.KEY_WORD_RIGHT, TextInputClient.MOD_SHIFT);
    require(session.selection().anchor == 0 && session.selection().caret > 0, "Option+Shift+Right selects a word");
    editor.onKeyCommand(TextInputClient.KEY_WORD_LEFT, TextInputClient.MOD_SHIFT);
    require(session.selection().caret == 0, "Option+Shift+Left shrinks the word selection");
    session.select(1, 6, 6);
    editor.setComposingText("k", 1);
    editor.setComposingText("漢", 1);
    editor.finishComposing();
    editor.onKeyCommand(TextInputClient.KEY_UNDO, 0);
    require("first\nsecond".equals(session.document().plainText()), "IME undo grouping");
    editor.onKeyCommand(TextInputClient.KEY_SELECT_ALL, 0);
    var text = new StringBuilder();
    for (int i = 0; i < 200; i++) text.append("Scrolling paragraph ").append(i).append(".\n");
    editor.commitText(text.toString());
    require(editor.layout().pages.size() > 3, "Long document pagination");
    Display.getInstance().getCurrent().revalidate();
    require(editor.getScrollY() > 0, "Caret scroll position");
    var caret = editor.getCaretRect();
    require(
        caret[1] >= editor.getAbsoluteY() && caret[1] <= editor.getAbsoluteY() + editor.getHeight(),
        "Caret visible after scrolling");
    session.loadDocument(original);
    var update = WordProcessor.class.getDeclaredMethod("edited");
    update.setAccessible(true);
    update.invoke(app);
    editor.resetScroll();
    System.out.println("UI_SMOKE_OK typing selection undo composition pagination scrolling");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
