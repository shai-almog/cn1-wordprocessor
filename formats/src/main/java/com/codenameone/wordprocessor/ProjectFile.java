package com.codenameone.wordprocessor;

import java.io.*;
import java.util.zip.*;

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
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ZipOutputStream zip = new ZipOutputStream(bytes);
    entry(zip, "document.xml", DocumentXml.write(d).getBytes("UTF-8"));
    if (source != null) {
      ByteArrayOutputStream original = new ByteArrayOutputStream();
      Docx.write(source.document, source, original, false);
      entry(zip, "original.docx", original.toByteArray());
    }
    zip.finish();
    return bytes.toByteArray();
  }

  private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setTime(0);
    zip.putNextEntry(entry);
    zip.write(data);
    zip.closeEntry();
  }

  public static Opened read(byte[] bytes) throws IOException {
    ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes));
    byte[] xml = null, original = null;
    int total = 0, count = 0;
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      if (++count > 2) throw new IOException("Unexpected native project entry");
      String name = entry.getName();
      if (!"document.xml".equals(name) && !"original.docx".equals(name)
          || "document.xml".equals(name) && xml != null
          || "original.docx".equals(name) && original != null)
        throw new IOException("Unknown or duplicate project part");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = zip.read(buffer)) != -1) {
        total += n;
        if (total > 128 * 1024 * 1024) throw new IOException("Project exceeds 128 MiB");
        out.write(buffer, 0, n);
      }
      if ("document.xml".equals(name)) xml = out.toByteArray();
      else original = out.toByteArray();
    }
    if (xml == null) throw new IOException("Missing document.xml");
    Document d = DocumentXml.read(new String(xml, "UTF-8"));
    Docx.Opened source = original == null ? null : Docx.read(new ByteArrayInputStream(original));
    if (source != null && DocumentXml.write(source.document).equals(DocumentXml.write(d)))
      d = source.document;
    return new Opened(d, source);
  }
}
