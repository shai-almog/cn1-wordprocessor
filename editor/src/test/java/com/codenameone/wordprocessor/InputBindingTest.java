package com.codenameone.wordprocessor;
import com.codename1.ui.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class InputBindingTest {
  @Test public void oldSessionCannotChangeNewSelectionTextOrFocus() {
    var client = new Client(); var old = new InputBinding(client);
    old.commitText("old"); old.setSelectionRange(1,2); assertEquals(2,client.edits);
    old.close(); var current = new InputBinding(client);
    old.setSelectionRange(0,3); old.commitText("late"); old.setComposingText("late",1);
    old.finishComposing(); old.deleteSurroundingText(1,0); old.replaceRange(0,3,"late");
    old.onKeyCommand(TextInputClient.KEY_UNDO,0); old.onEditorAction(0);
    old.inputFocusGained(); old.inputFocusLost();
    assertEquals(2,client.edits); assertEquals(0,client.lost);
    current.commitText("current"); assertEquals(3,client.edits);
    current.inputFocusLost(); current.commitText("late");
    assertEquals(3,client.edits); assertEquals(1,client.lost);
    assertSame(client.geometry,current.getGeometrySnapshot());
  }
  private static final class Client implements TextInputClient, TextInputGeometry.Provider {
    int edits,lost;
    final TextInputGeometry geometry=new TextInputGeometry(new int[0],0,0,new int[0],new int[]{0,0,1,1});
    public void commitText(String t) { edits++; }
    public void setComposingText(String t,int c) { edits++; }
    public void finishComposing() { edits++; }
    public void deleteSurroundingText(int a,int b) { edits++; }
    public void onKeyCommand(int a,int b) { edits++; }
    public void onEditorAction(int a) { edits++; }
    public TextInputState getEditingState() { return new TextInputState("abc",0,0); }
    public int[] getCaretRect() { return new int[]{0,0,1,1}; }
    public TextInputConfig getConfig() { return new TextInputConfig(); }
    public void inputFocusGained() { edits++; }
    public void inputFocusLost() { lost++; }
    public int getTextLength() { return 3; }
    public String getTextRange(int a,int b) { return "abc".substring(a,b); }
    public int[] rectForOffset(int a) { return getCaretRect(); }
    public int offsetAtPoint(int x,int y) { return 0; }
    public int[] selectionRects(int a,int b) { return new int[0]; }
    public void replaceRange(int a,int b,String t) { edits++; }
    public void setSelectionRange(int a,int b) { edits++; }
    public TextInputGeometry getGeometrySnapshot() { return geometry; }
  }
}
