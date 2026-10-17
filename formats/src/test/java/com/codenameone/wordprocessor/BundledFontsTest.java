package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import org.junit.Test;

public class BundledFontsTest {
  @Test public void headlessExportUsesAllFacesAndLoadsEachOnce() throws Exception {
    final Map<String,Integer> loads = new HashMap<String,Integer>();
    PdfExporter.FontProvider provider = BundledFonts.provider(path -> {
      loads.put(path, loads.containsKey(path) ? loads.get(path)+1 : 1);
      return getClass().getResourceAsStream(path);
    });
    Sequence<Block> paragraphs = new Sequence<Block>();
    long next = 1;
    for (String family : new String[]{"Serif","SansSerif","Monospaced"})
      for (int variant = 0; variant < 4; variant++) {
        TextStyle style = TextStyle.DEFAULT.with("font",family)
            .with("bold",""+((variant&1)!=0)).with("italic",""+((variant&2)!=0));
        for (int repeat=0; repeat<3; repeat++) paragraphs = paragraphs.append(new Paragraph(
            "p"+next++,RichText.of("Résumé Ελληνικά Кириллица",style)));
      }
    byte[] data = PdfExporter.write(new Document(paragraphs,PageSpec.A4,"Fonts",next),provider);
    String pdf = new String(data,"ISO-8859-1");
    assertEquals(12,loads.size());
    for (Map.Entry<String,Integer> face:loads.entrySet()) {
      assertEquals(face.getKey(),Integer.valueOf(1),face.getValue());
      assertTrue(pdf.contains("/BaseFont /"+face.getKey().substring(1,face.getKey().length()-4)));
    }
    assertTrue(pdf.contains("/ToUnicode"));
    assertTrue(pdf.contains("<03bb>"));
    assertTrue(pdf.contains("/FontFile2"));
  }

  @Test public void missingResourcesFailWithTheFontName() throws Exception {
    Session s = new Session(Document.empty()); s.replace("Hello");
    try {
      PdfExporter.write(s.document(),BundledFonts.provider(path -> null)); fail();
    } catch(IOException expected) { assertTrue(expected.getMessage().contains("Missing bundled font")); }
  }
}
