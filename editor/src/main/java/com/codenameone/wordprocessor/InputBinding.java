package com.codenameone.wordprocessor;

import com.codename1.ui.*;

/** One native input session. Queued callbacks from a closed session cannot edit its successor. */
final class InputBinding implements TextInputClient, TextInputGeometry.Provider {
  private final TextInputClient client;
  private boolean active = true;

  InputBinding(TextInputClient client) { this.client = client; }
  void close() { active = false; }
  public void commitText(String text) { if (active) client.commitText(text); }
  public void setComposingText(String text, int caret) { if (active) client.setComposingText(text, caret); }
  public void finishComposing() { if (active) client.finishComposing(); }
  public void deleteSurroundingText(int before, int after) { if (active) client.deleteSurroundingText(before, after); }
  public void onKeyCommand(int command, int modifiers) { if (active) client.onKeyCommand(command, modifiers); }
  public void onEditorAction(int action) { if (active) client.onEditorAction(action); }
  public void inputFocusGained() { if (active) client.inputFocusGained(); }
  public void inputFocusLost() {
    if (active) { active = false; client.inputFocusLost(); }
  }
  public void replaceRange(int start, int end, String text) { if (active) client.replaceRange(start, end, text); }
  public void setSelectionRange(int start, int end) { if (active) client.setSelectionRange(start, end); }
  public TextInputState getEditingState() { return client.getEditingState(); }
  public int[] getCaretRect() { return client.getCaretRect(); }
  public TextInputConfig getConfig() { return client.getConfig(); }
  public int getTextLength() { return client.getTextLength(); }
  public String getTextRange(int start, int end) { return client.getTextRange(start, end); }
  public int[] rectForOffset(int offset) { return client.rectForOffset(offset); }
  public int offsetAtPoint(int x, int y) { return client.offsetAtPoint(x, y); }
  public int[] selectionRects(int start, int end) { return client.selectionRects(start, end); }
  public TextInputGeometry getGeometrySnapshot() {
    return ((TextInputGeometry.Provider) client).getGeometrySnapshot();
  }
}
