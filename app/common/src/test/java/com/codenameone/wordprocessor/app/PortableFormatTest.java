package com.codenameone.wordprocessor.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.codenameone.wordprocessor.*;
import java.io.*;
import org.junit.jupiter.api.Test;

public class PortableFormatTest {
  @Test
  public void lifecycleCanBeConstructedBeforeDisplayInit() {
    // Matches the generated ParparVM stub, which constructs Main before Display.init().
    new Main();
  }

  @Test
  public void portableDocumentRoundTrip() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Portable text שלום 😀");
    s.select(0, 0, 8);
    s.format("bold", "true");
    byte[] project = ProjectFile.write(s.document(), null);
    Document recovered = ProjectFile.read(project).document;
    assertEquals(s.document().plainText(), recovered.plainText());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Docx.write(recovered, bytes);
    assertEquals(
        recovered.plainText(),
        Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document.plainText());
  }
}
