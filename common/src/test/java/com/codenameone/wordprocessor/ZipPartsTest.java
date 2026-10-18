package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Test;

public class ZipPartsTest {
  @Test
  public void utf8MatchesStringGetBytes() throws Exception {
    String[] samples = {
      "", "plain <w:t>ascii</w:t>", "café ß", "€ 中文", "😀 emoji",
      "lone \ud800 high", "lone \udc00 low", "end \ud83d"
    };
    for (String s : samples) {
      assertArrayEquals(s, s.getBytes("UTF-8"), ZipParts.utf8(s));
      assertArrayEquals(s, s.getBytes("UTF-8"), ZipParts.utf8(new StringBuilder(s)));
    }
  }

  @Test
  public void archiveReadsBackWithJavaZip() throws Exception {
    var parts = new LinkedHashMap<String, byte[]>();
    var big = new StringBuilder();
    for (int i = 0; i < 2000; i++) big.append("<w:p>paragraph ").append(i).append("</w:p>");
    parts.put("word/document.xml", ZipParts.utf8(big));
    parts.put("tiny", new byte[] {1});
    parts.put("empty", new byte[0]);
    var out = new ByteArrayOutputStream();
    ZipParts.write(parts, out);
    var in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    int count = 0;
    for (ZipEntry e; (e = in.getNextEntry()) != null; count++) {
      var read = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      for (int n; (n = in.read(buffer)) > 0; ) read.write(buffer, 0, n);
      assertArrayEquals(e.getName(), parts.get(e.getName()), read.toByteArray());
    }
    assertEquals(3, count);
  }
}
