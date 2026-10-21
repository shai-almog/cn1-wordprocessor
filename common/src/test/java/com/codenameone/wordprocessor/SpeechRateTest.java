package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import com.codename1.ui.Display;
import com.codename1.ui.TextInputClient;
import org.junit.Test;

public class SpeechRateTest {
  @Test
  public void labelsAndClamp() {
    assertEquals("0.5x", Speech.label(.5f));
    assertEquals("0.75x", Speech.label(.75f));
    assertEquals("1x", Speech.label(1f));
    assertEquals("1.25x", Speech.label(1.25f));
    assertEquals("2x", Speech.label(2f));
    assertEquals(.5f, Speech.clamp(.1f), 0);
    assertEquals(3f, Speech.clamp(9f), 0);
    assertEquals("3x", Speech.label(3f));
    assertEquals(1f, Speech.clamp(Float.NaN), 0);
  }

  @Test
  public void rateIsPersistedAndScalesTheSentenceEstimate() {
    String saved = AppOptions.get("readAloudRate");
    try {
      Speech.rate(2f);
      assertEquals(2f, Speech.rate(), 0);
      Speech.rate(7f);
      assertEquals(3f, Speech.rate(), 0);
      AppOptions.set("readAloudRate", "junk");
      assertEquals(1f, Speech.rate(), 0);
      String s = "one two three four five six seven eight";
      long slow = Speech.duration(s, .5f), normal = Speech.duration(s, 1f), fast = Speech.duration(s, 2f);
      assertEquals(8 * 60000 / 180 + 400, normal); // Wordcraft: 180 words per minute at 1x
      assertEquals(8 * 60000 / 90 + 400, slow);
      assertEquals(8 * 60000 / 360 + 400, fast);
    } finally {
      AppOptions.set("readAloudRate", saved);
    }
  }

  @Test
  public void rawKeyMapping() {
    assertEquals(TextInputClient.KEY_LEFT, EditKeys.rawCommand(0, Display.GAME_LEFT));
    assertEquals(TextInputClient.KEY_DOWN, EditKeys.rawCommand(0, Display.GAME_DOWN));
    assertEquals(TextInputClient.KEY_BACKSPACE, EditKeys.rawCommand(8, 0));
    assertEquals(TextInputClient.KEY_DELETE, EditKeys.rawCommand(127, 0));
    assertEquals(-1, EditKeys.rawCommand('a', 0));
    assertEquals(TextInputClient.MOD_CTRL, EditKeys.rawModifiers(false, false, true, false));
    assertEquals(TextInputClient.MOD_CTRL, EditKeys.rawModifiers(false, false, false, true));
    assertEquals(TextInputClient.MOD_SHIFT | TextInputClient.MOD_ALT, EditKeys.rawModifiers(true, true, false, false));
  }
}
