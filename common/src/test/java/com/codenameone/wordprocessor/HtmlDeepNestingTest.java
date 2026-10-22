package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import com.codename1.ui.RichTextFormat;
import org.junit.Test;

public class HtmlDeepNestingTest {
  private static String nest(String open, String close, int levels) {
    var b = new StringBuilder("<html><body>");
    for (int i = 0; i < levels; i++) b.append(open);
    b.append("innermost");
    for (int i = 0; i < levels; i++) b.append(close);
    return b.append("<p>after</p></body></html>").toString();
  }

  private static void assertReads(String html) {
    String text = TextFormats.read(html, RichTextFormat.HTML, true).plainText();
    assertTrue(text, text.contains("innermost"));
    assertTrue(text, text.contains("after"));
  }

  @Test
  public void deepListsAreCapped() {
    assertReads(nest("<ul><li>", "</li></ul>", 3000));
  }

  @Test
  public void deepSpansAreCapped() {
    assertReads(nest("<span>", "</span>", 3000));
  }

  @Test
  public void deepBlockquotesAreCapped() {
    assertReads(nest("<blockquote>", "</blockquote>", 3000));
  }

  @Test
  public void deepMixedNestingWithVoidsCommentsAndScriptIsCapped() {
    assertReads(nest("<div><b><br><img src=\"x.png\"/><!-- <i> --><span class='a>b'><script>if (a<b) x='<em>';</script>",
        "</span></b></div>", 3000));
  }

  @Test
  public void unclosedTagsDoNotLoseText() {
    var b = new StringBuilder("<html><body>");
    for (int i = 0; i < 3000; i++) b.append("<i>");
    b.append("innermost<p>after</p></body></html>");
    assertReads(b.toString());
  }

  @Test
  public void capKeepsShallowMarkupAndText() {
    String out = TextFormats.limitHtmlDepth("<p>a<br>b<!-- <x> --><b>c</b></p>", 64);
    assertEquals("<p>a<br>b<!-- <x> --><b>c</b></p>", out);
    assertEquals("<b>x</b>", TextFormats.limitHtmlDepth("<b><i>x</i></b>", 1));
  }
}
