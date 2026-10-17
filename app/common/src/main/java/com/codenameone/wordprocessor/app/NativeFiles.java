package com.codenameone.wordprocessor.app;

import com.codename1.system.NativeInterface;

/** Native destination chooser. Called on a worker thread, never on the CN1 EDT. */
public interface NativeFiles extends NativeInterface {
  String chooseSaveFile(String suggestedName);

  String writeFile(String path, byte[] data);
}
