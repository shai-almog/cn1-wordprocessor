package com.codenameone.wordprocessor;

import com.codename1.media.RecognitionCallback;
import com.codename1.media.SpeechRecognizer;
import com.codename1.media.TextToSpeech;
import com.codename1.media.TtsOptions;

/**
 * Speech port for Read Aloud and Dictate. The default engine is Codename One's TextToSpeech and
 * SpeechRecognizer; on a port without speech support every call is a no-op and {@link
 * #speechSupported()} / {@link #dictationSupported()} report false so the UI can say so. Tests
 * install a recording engine with {@link #install(Engine)}.
 */
final class Speech {
  interface Engine {
    boolean speechSupported();

    void speak(String text, float rate);

    void stop();

    boolean dictationSupported();

    /** Recognizes one utterance and passes the final text (null on failure) to the listener. */
    void dictate(java.util.function.Consumer<String> result);
  }

  /** Codename One media APIs; any platform failure degrades to "unsupported". */
  static final class Platform implements Engine {
    public boolean speechSupported() {
      try {
        return TextToSpeech.isSupported();
      } catch (RuntimeException e) {
        return false;
      }
    }

    public void speak(String text, float rate) {
      if (!speechSupported()) return;
      TextToSpeech.speak(text, new TtsOptions().setRate(rate));
    }

    public void stop() {
      if (speechSupported()) TextToSpeech.stop();
    }

    public boolean dictationSupported() {
      try {
        return SpeechRecognizer.isSupported();
      } catch (RuntimeException e) {
        return false;
      }
    }

    public void dictate(java.util.function.Consumer<String> result) {
      if (!dictationSupported()) {
        result.accept(null);
        return;
      }
      SpeechRecognizer.recognizeOnce(
          new RecognitionCallback() {
            boolean done;

            public void onPartialResult(String partial) {}

            public void onResult(String text, float confidence, String[] alternatives) {
              if (!done) {
                done = true;
                result.accept(text);
              }
            }

            public void onEnd() {
              if (!done) {
                done = true;
                result.accept(null);
              }
            }

            public void onError(Throwable t) {
              if (!done) {
                done = true;
                result.accept(null);
              }
            }
          });
    }
  }

  private static Engine engine = new Platform();

  private Speech() {}

  static Engine engine() {
    return engine;
  }

  static void install(Engine e) {
    engine = e == null ? new Platform() : e;
  }

  /**
   * Estimated speaking time in milliseconds. TextToSpeech has no completion callback, so Read
   * Aloud advances to the next sentence after this estimate (about 160 words per minute at 1x).
   */
  static long duration(String text, float rate) {
    int words = 0;
    boolean in = false;
    for (int i = 0; i < text.length(); i++) {
      boolean space = text.charAt(i) <= ' ';
      if (!space && !in) words++;
      in = !space;
    }
    long ms = (long) (Math.max(1, words) * 375 / Math.max(.25f, rate));
    return ms + 400;
  }

  /** Read Aloud speeds offered in the ribbon, as Word's 0.5x..2x reading speed slider. */
  static final float[] RATES = {.5f, .75f, 1f, 1.25f, 1.5f, 2f};

  /** Ribbon label for a rate, for example "1.25x". */
  static String label(float rate) {
    int hundredths = Math.round(rate * 100);
    String s = (hundredths / 100) + "." + (hundredths % 100 < 10 ? "0" : "") + (hundredths % 100);
    if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
    if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
    return s + "x";
  }

  /** The persisted Read Aloud rate (AppOptions "readAloudRate"), clamped to the offered range. */
  static float rate() {
    try {
      return clamp(Float.parseFloat(AppOptions.get("readAloudRate")));
    } catch (RuntimeException e) {
      return 1f;
    }
  }

  static void rate(float r) {
    AppOptions.set("readAloudRate", String.valueOf(clamp(r)));
  }

  static float clamp(float r) {
    if (Float.isNaN(r)) return 1f;
    return Math.max(RATES[0], Math.min(RATES[RATES.length - 1], r));
  }
}
