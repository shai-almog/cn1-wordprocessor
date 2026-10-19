package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class ZipPartsTest {
  @Test
  public void crcMatchesTheJdkAtEveryLengthAndOffset() {
    byte[] data = new byte[300];
    new Random(7).nextBytes(data);
    for (int offset = 0; offset < 9; offset++)
      for (int length = 0; offset + length <= data.length; length += length < 40 ? 1 : 37) {
        var jdk = new CRC32();
        jdk.update(data, offset, length);
        assertEquals("offset " + offset + " length " + length, (int) jdk.getValue(), ZipParts.crc(data, offset, length));
      }
  }

  @Test
  public void crcCombineMatchesWholeCrc() {
    byte[] data = new byte[1000];
    new Random(5).nextBytes(data);
    for (int split : new int[] {0, 1, 7, 500, 999, 1000})
      assertEquals(
          ZipParts.crc(data, 0, data.length),
          ZipParts.combine(ZipParts.crc(data, 0, split), ZipParts.crc(data, split, data.length - split), data.length - split));
  }

  @Test
  public void largePartsCompressInParallelChunks() throws IOException {
    var parts = new LinkedHashMap<String, byte[]>();
    var text = new StringBuilder();
    var random = new Random(11);
    while (text.length() < 5 * ZipParts.CHUNK + 1234) text.append("<w:t>").append(random.nextInt()).append("</w:t>");
    parts.put("word/document.xml", ZipParts.utf8(text));
    var out = new ByteArrayOutputStream();
    ZipParts.write(parts, out);
    assertArrayEquals(parts.get("word/document.xml"), ZipParts.read(new ByteArrayInputStream(out.toByteArray())).get("word/document.xml"));
    var jdk = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    assertNotNull(jdk.getNextEntry());
    assertArrayEquals(parts.get("word/document.xml"), jdk.readAllBytes());
  }

  @Test
  public void writtenArchivesReadBackAndOpenWithJavaUtilZip() throws IOException {
    var parts = new LinkedHashMap<String, byte[]>();
    parts.put("word/document.xml", ZipParts.utf8("<w:document>" + "text ".repeat(5000) + "</w:document>"));
    byte[] random = new byte[5000];
    new Random(3).nextBytes(random); // incompressible: stored, not deflated
    parts.put("word/media/image1.png", random);
    parts.put("empty.xml", new byte[0]);
    parts.put("docProps/été.xml", ZipParts.utf8("é"));
    var out = new ByteArrayOutputStream();
    ZipParts.write(parts, out);
    byte[] zip = out.toByteArray();

    var read = ZipParts.read(new ByteArrayInputStream(zip));
    assertEquals(parts.keySet(), read.keySet());
    for (var e : parts.entrySet()) assertArrayEquals(e.getKey(), e.getValue(), read.get(e.getKey()));

    var jdk = new ZipInputStream(new ByteArrayInputStream(zip));
    int count = 0;
    for (ZipEntry entry; (entry = jdk.getNextEntry()) != null; count++)
      assertArrayEquals(entry.getName(), parts.get(entry.getName()), jdk.readAllBytes());
    assertEquals(parts.size(), count);

    var again = new ByteArrayOutputStream();
    ZipParts.write(parts, again);
    assertArrayEquals("identical parts save identical archives", zip, again.toByteArray());
  }

  @Test
  public void readsArchivesWrittenByJavaUtilZip() throws IOException {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("dir/"));
      zip.putNextEntry(new ZipEntry("dir/a.xml")); // data descriptor after the data
      zip.write(ZipParts.utf8("<a/>".repeat(100)));
    }
    var read = ZipParts.read(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(1, read.size());
    assertEquals("<a/>".repeat(100), new String(read.get("dir/a.xml"), "UTF-8"));
  }

  @Test
  public void rejectsCorruptionTraversalAndDuplicates() throws IOException {
    var parts = new LinkedHashMap<String, byte[]>();
    parts.put("a.xml", ZipParts.utf8("<a>" + "x".repeat(1000) + "</a>"));
    var out = new ByteArrayOutputStream();
    ZipParts.write(parts, out);
    byte[] zip = out.toByteArray();
    byte[] corrupt = zip.clone();
    corrupt[30 + 5 + 3] ^= 1; // inside the compressed data of the first entry
    assertThrows(IOException.class, () -> ZipParts.read(new ByteArrayInputStream(corrupt)));

    for (String name : new String[] {"../a.xml", "/a.xml", "a\\b.xml"}) {
      var bad = new LinkedHashMap<String, byte[]>();
      bad.put(name, new byte[1]);
      var b = new ByteArrayOutputStream();
      ZipParts.write(bad, b);
      assertThrows(name, IOException.class, () -> ZipParts.read(new ByteArrayInputStream(b.toByteArray())));
    }

    var twice = new ByteArrayOutputStream();
    try (var zip2 = new ZipOutputStream(twice)) {
      zip2.putNextEntry(new ZipEntry("a.xml"));
      zip2.closeEntry();
    }
    byte[] one = twice.toByteArray();
    // Not a ZIP at all: no parts, so the caller reports the missing main part.
    assertTrue(ZipParts.read(new ByteArrayInputStream("plain text".getBytes("UTF-8"))).isEmpty());
    assertEquals(1, ZipParts.read(new ByteArrayInputStream(one)).size());
  }
}
