package com.codenameone.wordprocessor;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class NoteCommandsTest {
  @Test public void updateConvertNavigateAndRollback() {
    var s = new Session(Document.empty()); var commands = new Commands();
    s.replace("First "); commands.execute(s, "references.footnote", Json.object("text", "First note"));
    s.replace(" between "); commands.execute(s, "references.endnote", Json.object("text", "Second note"));
    var notes = Notes.all(s.document());
    s.select(0, 9, 9); s.nextNote(false);
    assertEquals(notes.get(1).id, s.editingDocument().paragraph(0).text.noteAt(s.selection().start()).id);
    s.nextNote(true);
    assertEquals(notes.get(0).id, s.editingDocument().paragraph(0).text.noteAt(s.selection().start()).id);
    var before = s.document();
    commands.execute(s, "references.convertNotes", Json.object("to", "swap"));
    assertEquals("endnote", Notes.all(s.document()).get(0).kind);
    assertEquals("footnote", Notes.all(s.document()).get(1).kind);
    s.undo(); assertSame(before, s.document());
    commands.execute(s, "references.updateNote", Json.object("id", notes.get(0).id, "text", "Updated\nSecond paragraph"));
    assertEquals("Updated\nSecond paragraph", Notes.all(s.document()).get(0).plainText());
    before = s.document();
    try { commands.execute(s, "references.updateNote", Json.object("id", notes.get(0).id, "kind", "invalid")); fail(); }
    catch (IllegalArgumentException expected) { assertSame(before, s.document()); }
  }
  @Test public void optionsCommandIsAtomicAndReportsEffectiveSettings() {
    var s = new Session(Document.empty()); var c = new Commands();
    s.insertNote("footnote", "First", ""); s.sectionBreak("nextPage"); s.insertNote("footnote", "Second", "");
    var before = s.document();
    c.execute(s, "references.noteOptions", Json.object("numFmt", "upperRoman", "numStart", 4, "numRestart", "eachPage"));
    List<?> notes = (List<?>) c.execute(s, "references.notes", Collections.<String,Object>emptyMap());
    assertEquals("IV", ((Map<?,?>)notes.get(0)).get("label"));
    assertEquals("IV", ((Map<?,?>)notes.get(1)).get("label"));
    s.undo(); assertSame(before, s.document());
    try { c.execute(s, "references.noteOptions", Json.object("numFmt", "upperRoman", "endnoteFormat", "bad")); fail(); }
    catch (IllegalArgumentException expected) { assertSame(before, s.document()); }
  }
}
