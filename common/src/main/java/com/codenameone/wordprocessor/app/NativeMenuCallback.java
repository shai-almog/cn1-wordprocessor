package com.codenameone.wordprocessor.app;

/** Entry point the native menu calls on the AppKit main thread; the listener hops to the EDT. */
public final class NativeMenuCallback {
  public interface Listener {
    void selected(int id);
  }

  static volatile Listener listener;

  private NativeMenuCallback() {}

  /** Called from native code. Ids start at 1; anything else is ignored. */
  public static void fire(int id) {
    Listener l = listener;
    if (l != null && id > 0) l.selected(id);
  }
}
