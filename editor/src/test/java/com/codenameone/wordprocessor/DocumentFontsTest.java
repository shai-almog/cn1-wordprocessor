package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class DocumentFontsTest {
  @Test
  public void sansAliasesDoNotMatchTheSerifSubstring() {
    assertEquals("Source Sans 3", DocumentFonts.family("SansSerif"));
    assertEquals("Source Sans 3", DocumentFonts.family("sans-serif"));
    assertEquals("Source Serif 4", DocumentFonts.family("Serif"));
    assertEquals("Source Serif 4", DocumentFonts.family("Source Serif 4"));
    assertEquals("JetBrains Mono", DocumentFonts.family("Monospaced"));
  }
}
