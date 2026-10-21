package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import com.codename1.ui.RichTextFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class HtmlExternalImagesTest {
  private static int pictures(Document d) {
    int n = 0;
    for (Block b : d.blocks) if (b instanceof Picture) n++;
    return n;
  }

  @Test
  public void externalAndRelativePicturesAreFetchedAndFailuresWarned() throws Exception {
    var requested = new ArrayList<String>();
    final byte[] png = PictureTest.rgba();
    TextFormats.ImageSource source =
        (url, limit) -> {
          requested.add(url);
          assertEquals(TextBlocks.MAX_EXTERNAL_PICTURE, limit);
          if (url.endsWith("missing.png")) throw new IOException("HTTP 404");
          if (url.endsWith("notimage.png")) return new byte[] {1, 2, 3};
          return png;
        };
    var warnings = new ArrayList<String>();
    Document d =
        TextFormats.read(
            "<p>a</p><img src=\"https://example.com/x.png?a=1&amp;b=2\" alt=\"X\">"
                + "<img src=\"../img/y.png\" width=\"20\" height=\"10\"><img src=\"file:///tmp/z.png\">"
                + "<img src=\"http://e.org/missing.png\"><img src=\"notimage.png\"><img src=\"cid:part1\"><p>b</p>",
            RichTextFormat.HTML,
            "/docs/site/page.html",
            source,
            warnings);
    assertEquals(
        List.of(
            "https://example.com/x.png?a=1&b=2",
            "/docs/img/y.png",
            "file:///tmp/z.png",
            "http://e.org/missing.png",
            "/docs/site/notimage.png"),
        requested);
    assertEquals(3, pictures(d));
    assertEquals(2, warnings.size());
    assertTrue(warnings.get(0), warnings.get(0).contains("missing.png") && warnings.get(0).contains("404"));
    assertTrue(warnings.get(1), warnings.get(1).contains("notimage.png"));
    // Without a source, external pictures are skipped exactly as before.
    assertEquals(0, pictures(TextFormats.read("<img src=\"https://example.com/x.png\">", RichTextFormat.HTML)));
  }

  @Test
  public void resolutionAndSizeLimit() throws Exception {
    assertEquals("https://h.com/a/c.png", TextBlocks.resolve("https://h.com/a/b/page.html", "../c.png"));
    assertEquals("https://h.com/c.png", TextBlocks.resolve("https://h.com/a/page.html", "/c.png"));
    assertEquals("C:/d/p.png", TextBlocks.resolve("C:/d/page.html", "./p.png"));
    assertNull(TextBlocks.resolve(null, "rel.png"));
    assertNull(TextBlocks.resolve("/a/b.html", "mailto:x@y"));
    assertEquals(3, TextFormats.readLimited(new ByteArrayInputStream(new byte[3]), 3).length);
    try {
      TextFormats.readLimited(new ByteArrayInputStream(new byte[4]), 3);
      fail();
    } catch (IOException expected) {
    }
  }
}
