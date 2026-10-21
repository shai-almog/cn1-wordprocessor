package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

/** DOCX numbering formats and legal numbering as Wordcraft's numbering model draws them. */
public class DocxNumberingTest {
  private static Paragraph item(String format, int level) {
    return new Paragraph("p", RichText.of(""), "Normal", "left", "number", 0, 6, 0, 1.2f,
        false, false, level, 0, format);
  }

  @Test
  public void wordcraftNumberFormatsRender() {
    assertEquals("03", ListNumbers.format(3, "decimalZero"));
    assertEquals("12", ListNumbers.format(12, "decimalZero"));
    assertEquals("21st", ListNumbers.format(21, "ordinal"));
    assertEquals("12th", ListNumbers.format(12, "ordinal"));
    assertEquals("Twenty-One", ListNumbers.format(21, "cardinalText"));
    assertEquals("One Hundred Five", ListNumbers.format(105, "cardinalText"));
    assertEquals("Twenty-First", ListNumbers.format(21, "ordinalText"));
    assertEquals("Thirtieth", ListNumbers.format(30, "ordinalText"));
    assertEquals("Twelfth", ListNumbers.format(12, "ordinalText"));
    assertEquals("†", ListNumbers.format(2, "chicago"));
    assertEquals("**", ListNumbers.format(5, "chicago"));
    assertEquals("- 4 -", ListNumbers.format(4, "numberInDash"));
    assertEquals("", ListNumbers.format(4, "none"));
    // Letters repeat past z, as Word and Wordcraft.
    assertEquals("aa", ListNumbers.format(27, "lowerLetter"));
    assertEquals("BB", ListNumbers.format(28, "upperLetter"));
  }

  @Test
  public void numberingPartKeepsFormatsAndLegalLevels() throws Exception {
    String xml = "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:abstractNum w:abstractNumId=\"0\">"
        + "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"upperRoman\"/><w:lvlText w:val=\"%1.\"/></w:lvl>"
        + "<w:lvl w:ilvl=\"1\"><w:start w:val=\"1\"/><w:numFmt w:val=\"lowerLetter\"/><w:isLgl/><w:lvlText w:val=\"%1.%2\"/></w:lvl>"
        + "<w:lvl w:ilvl=\"2\"><w:start w:val=\"1\"/><w:numFmt w:val=\"ordinalText\"/><w:lvlText w:val=\"%3:\"/></w:lvl>"
        + "</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>";
    var warnings = new HashSet<String>();
    var read = DocxLists.read(xml.getBytes(StandardCharsets.UTF_8), warnings);
    var ds = read.definitions.get("1");
    assertEquals("upperRoman", ds[0].format);
    assertEquals("legal|%1.%2", ds[1].format);
    assertEquals("ordinalText|%3:", ds[2].format);
    assertTrue(warnings.toString(), warnings.isEmpty());
    var numbers = new ListNumbers();
    assertEquals("I.", numbers.next(item(ds[0].format, 0)));
    assertEquals("II.", numbers.next(item(ds[0].format, 0)));
    // Legal: the Roman parent shows as 2 and the level itself in digits.
    assertEquals("2.1", numbers.next(item(ds[1].format, 1)));
    assertEquals("First:", numbers.next(item(ds[2].format, 2)));
  }
}
