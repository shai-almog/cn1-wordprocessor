package com.codenameone.wordprocessor.app;

import com.codename1.system.NativeInterface;

/**
 * The platform Save dialog, which Codename One's openFileChooser (open only) lacks. Called on a
 * worker thread, never on the CN1 EDT. Locations are opaque: a file URL or path on the
 * desktop, a document URI on Android, a picker handle in the browser.
 */
public interface NativeFiles extends NativeInterface {
  /**
   * Asks where to save {@code name} and writes {@code data} there. Returns the location, null
   * when the user cancels, or "error:" followed by the reason.
   */
  String saveAs(String name, String mime, byte[] data);

  /** Overwrites a location saveAs returned: null on success, otherwise the reason. */
  String writeFile(String location, byte[] data);
}
