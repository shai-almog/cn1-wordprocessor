package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import org.junit.Test;

/** Reference page numbers must describe the PDF the CLI actually exports. */
public class PaginationTest {
  @Test public void contentsAndPageFieldsUseExportFonts() throws Exception {
    Session session = new Session(Document.empty().page(new PageSpec(240, 200, 20, 20, 20, 20)));
    StringBuilder body = new StringBuilder("\n");
    for (int i = 0; i < 24; i++) body.append("iiiiiiiiii iiiiiiiiii iiiiiiiiii iiiiiiiiii iiiiiiiiii\n");
    body.append("Destination");
    session.format("font", "Monospaced");
    session.replace(body.toString());
    session.applyStyle("Heading1");
    session.select(0, 0, 0);
    Commands commands = DocumentFiles.commands();
    commands.execute(session, "references.toc", Json.object());
    commands.execute(session, "references.updateFields", Json.object());
    Document document = session.document();
    LayoutEngine engine = PdfExporter.layoutEngine(
        BundledFonts.provider(path -> getClass().getResourceAsStream(path)));
    LayoutEngine.Result pages = engine.layout(document, 0);
    InlineField reference = document.paragraph(1).text.fieldAt(document.paragraph(1).text.length() - 1);
    assertEquals("" + pages.pages.size(), reference.result);
    assertNotEquals("Fixture must expose the old Helvetica mismatch", pages.pages.size(),
        new LayoutEngine(new PdfMetrics()).layout(document, 0).pages.size());
    String pdf = new String(DocumentFiles.write(document, null, "pdf", false), "ISO-8859-1");
    assertTrue(pdf.contains("/Type /Pages /Count " + pages.pages.size() + " /Kids"));
  }

  @Test public void hostCanBindItsNativePrintMetrics() {
    Session session = new Session(Document.empty().page(new PageSpec(240, 200, 20, 20, 20, 20)));
    session.replace("\nBody one\nBody two\nBody three\nHeading");
    session.applyStyle("Heading1");
    session.select(0, 0, 0);
    Commands commands = new Commands();
    commands.execute(session, "references.toc", Json.object());
    String originalEntry = session.document().paragraph(1).text.plainText();
    LayoutEngine tall = new LayoutEngine(new LayoutEngine.Metrics() {
      public float width(String text, TextStyle style) { return text.length() * 5; }
      public float height(TextStyle style) { return 60; }
    });
    commands.setLayoutEngine(tall);
    commands.execute(session, "references.updateToc", Json.object());
    int pages = tall.layout(session.document(), 0).pages.size();
    assertTrue(pages > 1);
    assertNotEquals(originalEntry, session.document().paragraph(1).text.plainText());
    assertEquals("Heading\t" + pages, session.document().paragraph(1).text.plainText());
  }
}
