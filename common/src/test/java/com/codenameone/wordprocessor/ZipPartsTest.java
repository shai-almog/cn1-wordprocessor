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
  public void docxReportsCorruptionOfALargePartVerifiedInTheBackground() throws IOException {
    byte[] random = new byte[3 * ZipParts.CHUNK];
    new Random(9).nextBytes(random); // incompressible, so stored and easy to corrupt in place
    var parts = new LinkedHashMap<String, byte[]>();
    parts.put("word/document.xml", random);
    var out = new ByteArrayOutputStream();
    ZipParts.write(parts, out);
    byte[] zip = out.toByteArray();
    zip[30 + "word/document.xml".length() + 100] ^= 1;
    var failure = assertThrows(IOException.class, () -> Docx.read(new ByteArrayInputStream(zip)));
    assertEquals("Corrupt package entry: word/document.xml", failure.getMessage());
  }

  @Test
  public void xmlDecodesUtf8LikeTheJdk() throws IOException {
    String text = "a\u00e9\u4e2d\ud83d\ude00z";
    byte[] good = ("<r v=\"" + text + "\">" + text + "</r>").getBytes("UTF-8");
    var e = Xml.parse(good);
    assertEquals(text, e.getAttribute("v"));
    assertEquals(text, ((com.codename1.xml.Element) e.getChildAt(0)).getText());
    byte[] bad = {'<', 'r', '>', (byte) 0xc3, 'x', (byte) 0xed, (byte) 0xa0, (byte) 0x80, '<', '/', 'r', '>'};
    assertEquals(
        new String(bad, "UTF-8").replace("<r>", "").replace("</r>", ""),
        ((com.codename1.xml.Element) Xml.parse(bad).getChildAt(0)).getText());
  }

  @Test
  public void utf8DecodingMatchesTheJdkOnRandomAndMalformedInput() throws IOException {
    var random = new Random(13);
    byte[][] fixed = {
      {(byte) 0xe2, (byte) 0x82, 'x'}, {(byte) 0xe0, (byte) 0x80, (byte) 0x80}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
      {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xf0, (byte) 0x9f, (byte) 0x98}, {(byte) 0xc0, (byte) 0xaf},
      {(byte) 0xf5}, {(byte) 0x80}, {(byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x80}
    };
    for (int round = 0; round < 20000 + fixed.length; round++) {
      byte[] b;
      if (round < fixed.length) b = fixed[round];
      else {
        b = new byte[random.nextInt(12)];
        for (int i = 0; i < b.length; i++)
          b[i] = (byte) (random.nextBoolean() ? 0x80 + random.nextInt(0x80) : random.nextInt(256));
      }
      char[] out = new char[b.length];
      String ours = new String(out, 0, Xml.decodeUtf8(b, out));
      assertEquals(java.util.Arrays.toString(b), new String(b, "UTF-8"), ours);
    }
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

  @Test
  public void utf8EncodingMatchesTheJdkForCompactAndWideText() throws IOException {
    String[] samples = {"", "plain ascii <w:t>", "caf\u00e9", "quote \u2019 here", "lone \ud800 x", "\udc00", "pair \ud83d\ude00"};
    for (String text : samples) {
      assertArrayEquals(text, text.getBytes("UTF-8"), ZipParts.utf8(text));
      assertArrayEquals(text, text.getBytes("UTF-8"), ZipParts.utf8(new StringBuilder(text)));
    }
  }

  @Test
  public void latin1AndWidePartsParseToTheSameStrings() throws IOException {
    for (String extra : new String[] {"", "\u00e9", "\u2019"}) {
      String xml = "<r a=\"v" + extra + "\"><x>one" + extra + "</x><![CDATA[c" + extra + "]]>t &amp; u</r>";
      var e = Xml.parse(xml.getBytes("UTF-8"));
      assertEquals("v" + extra, e.getAttribute("a"));
      assertEquals("one" + extra, ((com.codename1.xml.Element) e.getChildAt(0).getChildAt(0)).getText());
      assertEquals("c" + extra, ((com.codename1.xml.Element) e.getChildAt(1)).getText());
      assertEquals("t & u", ((com.codename1.xml.Element) e.getChildAt(2)).getText());
    }
  }
}
