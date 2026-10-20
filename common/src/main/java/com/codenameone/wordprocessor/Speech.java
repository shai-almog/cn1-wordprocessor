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
}
