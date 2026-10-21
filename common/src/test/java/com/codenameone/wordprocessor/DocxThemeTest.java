package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

/** Theme fonts and colours in DOCX runs resolve through the theme part, as Wordcraft. */
public class DocxThemeTest {
  @Test
  public void themeFontsAndColorsSupersedeFixedValues() throws Exception {
    var ns = " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
    var a = " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"";
    var parts = new LinkedHashMap<String, String>();
    parts.put("word/document.xml", "<w:document" + ns + "><w:body><w:p>"
        + "<w:r><w:rPr><w:rFonts w:asciiTheme=\"majorHAnsi\" w:ascii=\"Arial\"/>"
        + "<w:color w:val=\"000000\" w:themeColor=\"accent1\"/></w:rPr><w:t>A</w:t></w:r>"
        + "<w:r><w:rPr><w:rFonts w:hAnsiTheme=\"minorHAnsi\"/>"
        + "<w:color w:val=\"000000\" w:themeColor=\"text1\" w:themeTint=\"80\"/>"
        + "<w:shd w:val=\"clear\" w:fill=\"FFFFFF\" w:themeFill=\"accent2\" w:themeFillShade=\"80\"/>"
        + "</w:rPr><w:t>B</w:t></w:r></w:p></w:body></w:document>");
    parts.put("word/theme/theme1.xml", "<a:theme" + a + "><a:themeElements><a:clrScheme>"
        + "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>"
        + "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>"
        + "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>"
        + "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1>"
        + "<a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>"
        + "</a:clrScheme><a:fontScheme><a:majorFont><a:latin typeface=\"Calibri Light\"/></a:majorFont>"
        + "<a:minorFont><a:latin typeface=\"Calibri\"/></a:minorFont></a:fontScheme>"
        + "</a:themeElements></a:theme>");
    // text1 maps to dark2 here.
    parts.put("word/settings.xml", "<w:settings" + ns + "><w:clrSchemeMapping w:t1=\"dark2\"/>"
        + "</w:settings>");
    var out = new ByteArrayOutputStream();
    var zip = new ZipOutputStream(out);
    for (var e : parts.entrySet()) {
      zip.putNextEntry(new ZipEntry(e.getKey()));
      zip.write(e.getValue().getBytes("UTF-8"));
      zip.closeEntry();
    }
    zip.finish();
    var doc = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    // The theme's accents are kept for SmartArt previews, Wordcraft's defaults filling the rest.
    assertArrayEquals(new int[] {0x4472c4, 0xed7d31, 0x196b24, 0x0f9ed5, 0xa02b93, 0x4ea72e},
        DocxTheme.accents(doc.properties));
    var p = doc.paragraph(0);
    assertEquals("Calibri Light", p.text.styleAt(0).font);
    assertEquals(0x4472c4, p.text.styleAt(0).color);
    assertEquals("Calibri", p.text.styleAt(1).font);
    // 44546A with tint 0x80: luminance l * t + (1 - t) in HSL, as Word and Wordcraft.
    assertEquals(0x98a8bd, p.text.styleAt(1).color);
    // ED7D31 with shade 0x80: luminance l * s.
    assertEquals(0x843c0c, p.text.styleAt(1).background);
  }
}
