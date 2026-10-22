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

  /** The limited HTML parses directly, without the safety net in TextFormats.read. */
  private static void parses(String html) {
    com.codename1.ui.editor.RichTextImporter.parse(TextFormats.limitHtmlDepth(html, TextFormats.MAX_HTML_DEPTH), RichTextFormat.HTML);
  }

  private static void assertReads(String html) {
    parses(html);
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

  private static String repeat(String s, int n) {
    var b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append(s);
    return b.toString();
  }

  @Test
  public void misnestedClosesAreCapped() {
    assertReads("<html><body>" + repeat("<i><b></i>", 3000) + "innermost<p>after</p></body></html>");
    assertReads("<html><body>" + repeat("<span><b></span>", 3000) + "innermost<p>after</p></body></html>");
    assertReads("<html><body>" + repeat("<b></b ></B.c>", 3000) + "innermost<p>after</p></body></html>");
  }

  @Test
  public void tagsInsideStyleTextareaTitleAndScriptAreCounted() {
    for (String t : new String[] {"style", "textarea", "title"})
      // The close does not match the innermost open span, so the parser keeps the rest inside the
      // element (and the importer may hide it); it must not overflow.
      parses("<html><body><" + t + ">" + repeat("<span>", 3000) + "</" + t + ">innermost<p>after</p></body></html>");
    // The parser skips script only up to the exact \"</script>\"; a spaced close does not end it there.
    parses("<html><body><script>" + repeat("<span>", 3000) + "</script >innermost<p>after</p></body></html>");
    // An unclosed script swallows the rest, as the parser does; it must not overflow either.
    parses("<html><body><script a=\">\">" + repeat("<span>", 3000) + "x");
    assertReads("<html><body>" + repeat("<b x'>", 3000) + "innermost<p>after</p></body></html>");
    assertReads("<html><body>" + repeat("<wbr><source><embed>", 1000) + "innermost<p>after</p></body></html>");
  }

  @Test
  public void parserOverflowFallsBackToTagStrippedText() {
    String html = "<html><body>" + repeat("<span>", 20000) + "innermost</body></html>";
    String text = TextFormats.parseImported(html, RichTextFormat.HTML).getText();
    assertTrue(text, text.contains("innermost"));
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
