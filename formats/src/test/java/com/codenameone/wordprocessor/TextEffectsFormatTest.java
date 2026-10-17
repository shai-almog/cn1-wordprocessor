package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class TextEffectsFormatTest {
  private Document document(TextStyle s) {
    return Document.empty()
        .blocks(new Sequence<Block>().append(new Paragraph("p1", RichText.of("Alpha beta", s))), 2);
  }

  private Document docx(Document d) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    return opened.document;
  }

  @Test
  public void everyUnderlineAndColorRoundTripsInNativeAndDocx() throws Exception {
    for (String type : CharacterFormat.UNDERLINES) {
      TextStyle style =
          TextStyle.DEFAULT.with("underlineStyle", type).with("underlineColor", "a020c0");
      Document d = document(style);
      assertEquals(
          type, style, DocumentXml.read(DocumentXml.write(d)).paragraph(0).text.styleAt(0));
      assertEquals(type, style, docx(d).paragraph(0).text.styleAt(0));
    }
  }

  @Test
  public void allEffectsRoundTripWithTrackedPreviousStateAndStyleDefaults() throws Exception {
    TextStyle original =
        TextStyle.DEFAULT.with("underlineStyle", "wavyDouble").with("underlineColor", "cc1100");
    TextStyle edited = original;
    for (String key : new String[] {"outline", "shadow", "emboss", "engrave"})
      edited = edited.with(key, "true");
    edited =
        edited.trackedProperties(original, new Revision("r1", "format", "Author", "2026-10-17"));
    Document d = document(edited);
    for (Document copy : Arrays.asList(DocumentXml.read(DocumentXml.write(d)), docx(d))) {
      TextStyle actual = copy.paragraph(0).text.styleAt(0);
      assertEquals(edited, actual);
      assertEquals(original, actual.reviewFormat(null, false));
    }
  }

  @Test
  public void oldNativeSingleAndDoubleUnderlinesAreNotLost() throws Exception {
    for (String type : new String[] {"single", "double"}) {
      TextStyle style = TextStyle.DEFAULT.with("underlineStyle", type);
      String old = DocumentXml.write(document(style)).replaceAll(" underlineStyle=\"[^\"]*\"", "");
      assertEquals(style, DocumentXml.read(old).paragraph(0).text.styleAt(0));
    }
  }

  @Test
  public void underlineToggleClearsEveryVariantWithoutLatentState() {
    for (String type : CharacterFormat.UNDERLINES) {
      TextStyle style = TextStyle.DEFAULT.with("underlineStyle", type);
      assertEquals(!"none".equals(type), style.hasUnderline());
      assertFalse(style.with("underline", "false").hasUnderline());
      assertEquals("single", style.with("underline", "true").characters.underlineStyle);
      assertEquals("double", style.with("doubleUnderline", "true").characters.underlineStyle);
    }
  }

  @Test
  public void invalidStylesAndColorsRejectWithoutChangingDocument() {
    Session s = new Session(document(TextStyle.DEFAULT));
    s.select(0, 0, 5);
    Document before = s.document();
    for (String[] pair :
        new String[][] {
          {"underlineStyle", "bogus"},
          {"underlineColor", "-2"},
          {"underlineColor", "1000000"},
          {"underlineColor", "NaN"}
        }) {
      try {
        s.transact(
            t -> {
              t.format("shadow", "true");
              t.format(pair[0], pair[1]);
            });
        fail();
      } catch (IllegalArgumentException expected) {
      }
      assertSame(before, s.document());
    }
  }

  @Test
  public void directUnderlineVariantsSurviveNamedStyleChanges() {
    Session s = new Session(document(TextStyle.DEFAULT));
    s.select(0, 0, 5);
    s.format("underlineStyle", "dotDash");
    ParagraphStyle normal = s.document().styles.get("Normal");
    s.defineStyle(
        new ParagraphStyle(
            normal.id,
            normal.name,
            normal.paragraph,
            normal.text.with("underlineStyle", "wave").with("shadow", "true")));
    assertEquals("dotDash", s.document().paragraph(0).text.styleAt(0).characters.underlineStyle);
    assertEquals("wave", s.document().paragraph(0).text.styleAt(7).characters.underlineStyle);
    assertTrue(s.document().paragraph(0).text.styleAt(0).characters.shadow);
  }

  @Test
  public void pdfOutlineUsesStrokeModeAndFollowingTextResetsIt() throws Exception {
    RichText text = RichText.of("Outline", TextStyle.DEFAULT.with("outline", "true"));
    text = text.replace(text.length(), text.length(), RichText.of(" Plain", TextStyle.DEFAULT));
    Document d =
        Document.empty().blocks(new Sequence<Block>().append(new Paragraph("p1", text)), 2);
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains("1 Tr"));
    assertTrue(pdf.contains("0 Tr"));
    assertTrue(pdf.contains("(Outline) Tj ET Q"));
    assertTrue(pdf.contains("( Plain) Tj ET Q"));
  }

  @Test
  public void coloredPatternIsIndependentOfGlyphColor() throws Exception {
    String pdf =
        new String(
            PdfExporter.write(
                document(
                    TextStyle.DEFAULT
                        .with("underlineStyle", "dash")
                        .with("underlineColor", "ff0000"))),
            "ISO-8859-1");
    assertTrue(pdf.contains("1.000 0.000 0.000 RG"));
    assertTrue(pdf.split(" l S", -1).length > 4);
  }
}
