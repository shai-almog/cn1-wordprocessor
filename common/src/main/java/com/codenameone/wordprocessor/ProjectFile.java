package com.codenameone.wordprocessor;

import java.io.*;

/** Native project container retains the original DOCX package alongside the editable model. */
public final class ProjectFile {
  public static final class Opened {
    public final Document document;
    public final Docx.Opened source;

    Opened(Document d, Docx.Opened s) {
      document = d;
      source = s;
    }
  }

  private ProjectFile() {}

  public static byte[] write(Document d, Docx.Opened source) throws IOException {
    var parts = new java.util.LinkedHashMap<String, byte[]>();
    parts.put("document.xml", ZipParts.utf8(DocumentXml.write(d)));
    if (source != null) {
      var original = new ByteArrayOutputStream();
      Docx.write(source.document, source, original, false);
      parts.put("original.docx", original.toByteArray());
    }
    var bytes = new ByteArrayOutputStream();
    ZipParts.write(parts, bytes);
    return bytes.toByteArray();
  }

  public static Opened read(byte[] bytes) throws IOException {
    var parts = ZipParts.read(new ByteArrayInputStream(bytes));
    if (parts.size() > 2) throw new IOException("Unexpected native project entry");
    for (String name : parts.keySet())
      if (!"document.xml".equals(name) && !"original.docx".equals(name))
        throw new IOException("Unknown or duplicate project part");
    byte[] xml = parts.get("document.xml"), original = parts.get("original.docx");
    if (xml == null) throw new IOException("Missing document.xml");
    Document d = DocumentXml.read(new String(xml, "UTF-8"));
    Docx.Opened source = original == null ? null : Docx.read(new ByteArrayInputStream(original));
    if (source != null && DocumentXml.write(source.document).equals(DocumentXml.write(d)))
      d = source.document;
    return new Opened(d, source);
  }
}
