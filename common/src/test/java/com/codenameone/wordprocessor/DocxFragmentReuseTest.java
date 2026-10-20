package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Test;

public class DocxFragmentReuseTest {
  private final Commands commands = new Commands();

  private static byte[] save(Document d, boolean fresh) throws IOException {
    if (fresh) Docx.forgetFragments();
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    return out.toByteArray();
  }

  private static void assertReuseMatchesFullSave(Document d) throws IOException {
    byte[] reused = save(d, false), full = save(d, true);
    assertArrayEquals(full, reused);
    assertArrayEquals("a second reuse", full, save(d, false));
  }

  @Test
  public void savesAfterEditsMatchAFullSerialization() throws IOException {
    for (String fixture : new String[] {"/wordcraft-contents.docx", "/wordcraft-index.docx"}) {
      Document d;
      try (var in = getClass().getResourceAsStream(fixture)) {
        d = Docx.read(in).document;
      }
      var s = new Session(d);
      save(s.document(), true);
      assertReuseMatchesFullSave(s.document());
      s.select(0, 0, 0);
      s.replace("Edited ");
      assertReuseMatchesFullSave(s.document());
      s.select(0, 3, 3);
      s.replace("split\ninserted paragraph\n");
      assertReuseMatchesFullSave(s.document());
      s.undo();
      assertReuseMatchesFullSave(s.document());
      s.undo();
      assertReuseMatchesFullSave(s.document());
      // Reading back what was written from reused fragments gives the same text.
      var back = Docx.read(new ByteArrayInputStream(save(s.document(), false))).document;
      assertEquals(s.document().plainText(), back.plainText());
    }
  }

  @Test
  public void styleAndListChangesAreNotServedStale() throws IOException {
    var s = new Session(Document.empty());
    s.replace("one\ntwo\nthree");
    save(s.document(), true);
    s.select(1, 0, 3);
    commands.execute(s, "para.style", Json.object("style", "Heading1"));
    assertReuseMatchesFullSave(s.document());
    s.select(0, 0, 0);
    commands.execute(s, "para.bullets", Json.object());
    assertReuseMatchesFullSave(s.document());
  }
}
