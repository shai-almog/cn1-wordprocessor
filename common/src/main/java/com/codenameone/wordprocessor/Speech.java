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
   * Aloud advances to the next sentence after this estimate (Wordcraft's 180 words per minute at 1x).
   */
  static long duration(String text, float rate) {
    return ReadAloud.duration(text, rate);
  }

  /** Read Aloud speeds offered in the ribbon: Wordcraft's 0.5x..3x speed range. */
  static final float[] RATES = {.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f};

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
    return ReadAloud.clamp(r);
  }

  /** Whether Read Aloud leaves out citations and the bibliography (AppOptions, default on as in Wordcraft). */
  static boolean skipCitations() {
    return !"false".equals(AppOptions.get("readAloudSkipCitations"));
  }

  static void skipCitations(boolean on) {
    AppOptions.set("readAloudSkipCitations", String.valueOf(on));
  }
}
