package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

public class CharacterFormatTest {
  private static final String[] VALUES = {
    "true", "true", "true", "true", "true", "1.5", "135", "-3.5"
  };

  private TextStyle advanced() {
    TextStyle style = TextStyle.DEFAULT;
    for (int i = 0; i < VALUES.length; i++) style = style.with(CharacterFormat.KEYS[i], VALUES[i]);
    return style;
  }

  private Document document(TextStyle style) {
    Document d = Document.empty();
    return d.blocks(
        new Sequence<Block>().append(new Paragraph("p1", RichText.of("Alpha ß omega", style))), 2);
  }

  private Document docx(Document d) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    return opened.document;
  }

  @Test
  public void allPropertiesSurviveNativeAndDocxWithSourceCaseIntact() throws Exception {
    Document d = document(advanced());
    for (Document copy : Arrays.asList(DocumentXml.read(DocumentXml.write(d)), docx(d))) {
      assertEquals(d.plainText(), copy.plainText());
      assertEquals(advanced(), copy.paragraph(0).text.styleAt(0));
    }
  }

  @Test
  public void eachPropertyCanBeIndependentlyDisabledThroughDocx() throws Exception {
    for (String key : CharacterFormat.KEYS) {
      TextStyle style = advanced().with(key, CharacterFormat.DEFAULT.value(key));
      assertEquals(key, style, docx(document(style)).paragraph(0).text.styleAt(0));
    }
  }

  @Test
  public void trackedPreviousPropertiesSurviveBothFormatsAndReject() throws Exception {
    TextStyle before = advanced();
    TextStyle after =
        before
            .with("characterSpacing", "0")
            .with("smallCaps", "false")
            .trackedProperties(before, new Revision("r1", "format", "Author", "2026-10-17"));
    Document d = document(after);
    for (Document copy : Arrays.asList(DocumentXml.read(DocumentXml.write(d)), docx(d))) {
      TextStyle style = copy.paragraph(0).text.styleAt(0);
      assertEquals(before, style.previousStyle);
      assertEquals(before, style.reviewFormat(null, false));
      assertEquals(after.clean(), style.reviewFormat(null, true));
    }
  }

  @Test
  public void missingNativeAttributesHaveLegacyDefaults() throws Exception {
    String xml = DocumentXml.write(document(TextStyle.DEFAULT));
    for (String key : CharacterFormat.KEYS) xml = xml.replaceAll(" " + key + "=\"[^\"]*\"", "");
    assertEquals(
        CharacterFormat.DEFAULT, DocumentXml.read(xml).paragraph(0).text.styleAt(0).characters);
  }

  @Test
  public void geometryRejectsNonFiniteAndOutOfBounds() {
    for (String key : new String[] {"characterSpacing", "characterScale", "characterPosition"})
      for (String value : new String[] {"NaN", "Infinity", "-Infinity", "99999"}) {
        try {
          TextStyle.DEFAULT.with(key, value);
          fail(key + value);
        } catch (IllegalArgumentException expected) {
        }
      }
  }

  @Test
  public void pdfHasScalingBothDecorationsAndNoHiddenGlyphs() throws Exception {
    TextStyle style =
        TextStyle.DEFAULT
            .with("characterScale", "125")
            .with("doubleUnderline", "true")
            .with("doubleStrike", "true");
    String pdf = new String(PdfExporter.write(document(style)), "ISO-8859-1");
    assertTrue(pdf, pdf.matches("(?s).*125(?:\\.0+)? Tz.*"));
    assertEquals(4, pdf.split(" l S\\n", -1).length - 1);
    pdf = new String(PdfExporter.write(document(style.with("hidden", "true"))), "ISO-8859-1");
    assertFalse(pdf.contains("Alpha"));
  }

  @Test
  public void smallCapsUseOnePdfBaselineForMixedCaseRuns() throws Exception {
    Document d =
        document(TextStyle.DEFAULT.with("smallCaps", "true").with("doubleUnderline", "true"));
    LayoutEngine.Result result = PdfExporter.layoutEngine(null).layout(d, 0);
    LayoutEngine.Line line = result.pages.get(0).lines.get(0);
    float baseline = line.spans.get(0).top + line.spans.get(0).glyphStyle.size;
    for (LayoutEngine.Span span : line.spans)
      assertEquals(baseline, span.top + span.glyphStyle.size, .001);
  }

  @Test
  public void htmlRetainsCaseAndEffectsAsPresentation() {
    String html = TextFormats.write(document(advanced()), com.codename1.ui.RichTextFormat.HTML);
    assertTrue(html.contains("font-variant:small-caps"));
    assertTrue(html.contains("text-transform:uppercase"));
    assertTrue(html.contains("display:none"));
    assertTrue(html.contains("Alpha ß omega"));
  }

  @Test
  public void underlineModesReplaceOneAnotherWithoutLatentRoundTripState() throws Exception {
    TextStyle doubleLine =
        TextStyle.DEFAULT.with("underline", "true").with("doubleUnderline", "true");
    assertFalse(doubleLine.underline);
    assertTrue(doubleLine.characters.doubleUnderline);
    assertEquals(doubleLine, docx(document(doubleLine)).paragraph(0).text.styleAt(0));
    TextStyle single = doubleLine.with("underline", "true");
    assertTrue(single.underline);
    assertFalse(single.characters.doubleUnderline);
    assertEquals(single, docx(document(single)).paragraph(0).text.styleAt(0));
    TextStyle none = doubleLine.with("underline", "false");
    assertFalse(none.underline);
    assertFalse(none.characters.doubleUnderline);
  }

  @Test
  public void spacedSmallCapFieldPdfDrawsItsResultRatherThanObjectPlaceholder() throws Exception {
    TextStyle style = TextStyle.DEFAULT.with("smallCaps", "true").with("characterSpacing", "2");
    Document d =
        Document.empty()
            .blocks(
                new Sequence<Block>()
                    .append(
                        new Paragraph(
                            "p1",
                            RichText.field(
                                new InlineField("MERGEFIELD Name", "aB", true, false), style))),
                2);
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains("(A) Tj"));
    assertTrue(pdf.contains("(B) Tj"));
    assertTrue(pdf.contains("9.600 Tf"));
    assertTrue(pdf.contains("12.000 Tf"));
  }

  @Test
  public void scaledSpacedTabLeadersFitTheirAllocatedWidth() throws Exception {
    TextStyle style = TextStyle.DEFAULT.with("characterScale", "200").with("characterSpacing", "1");
    Paragraph p = new Paragraph("p1", RichText.of("A\tB", style)).with("tabs", "120,left,dot");
    Document d = Document.empty().blocks(new Sequence<Block>().append(p), 2);
    LayoutEngine.Line line = PdfExporter.layoutEngine(null).layout(d, 0).pages.get(0).lines.get(0);
    LayoutEngine.Span gap = line.spans.get(1);
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertEquals(gap.displayText.length(), pdf.split("\\(\\.\\) Tj", -1).length - 1);
    float advance = CharacterMetrics.width(new PdfMetrics(), ".", style);
    assertTrue(gap.displayText.length() * advance <= gap.width + .001);
  }
}
