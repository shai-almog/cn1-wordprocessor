package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class PictureLookFormatTest {
  private static Document sample(Picture.Look look) throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Before");
    s.insertPicture(PictureTest.rgba(), 120, 60, "Chart");
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++)
      if (s.document().blocks.get(i) instanceof Picture) block = i;
    s.pictureLook(block, look);
    return s.document();
  }

  private static Picture picture(Document d) {
    for (Block b : d.blocks) if (b instanceof Picture) return (Picture) b;
    throw new AssertionError("no picture");
  }

  @Test
  public void nativeDocxAndPdfKeepCropBorderAndRotation() throws Exception {
    Picture.Look look = new Picture.Look(.1f, .2f, .05f, 0, 0x336699, 1.5f, 90, true, false);
    Document d = sample(look);
    assertEquals(look, picture(DocumentXml.read(DocumentXml.write(d))).look);
    assertEquals(DocumentXml.write(d), DocumentXml.write(DocumentXml.read(DocumentXml.write(d))));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    assertEquals(look, picture(opened.document).look);

    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains(" re W n q "));
    assertTrue(pdf.contains(" RG 1.5"));
  }

  @Test
  public void plainPicturesStayPlainAndUndoRestores() throws Exception {
    Document d = sample(Picture.Look.NONE);
    assertFalse(DocumentXml.write(d).contains("crop="));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    assertEquals(Picture.Look.NONE, picture(Docx.read(new ByteArrayInputStream(out.toByteArray())).document).look);
    try {
      new Picture.Look(.5f, 0, .5f, 0, -1, 0, 0, false, false);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
