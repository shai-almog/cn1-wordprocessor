package com.codenameone.wordprocessor.app;

import com.codename1.system.NativeInterface;

/**
 * macOS integration through AppKit. The menu bar: submenus, separators, check marks and the standard
 * application, Window and Help menus, which the framework's flat command bridge cannot express.
 * Selections come back through {@link NativeMenuCallback#fire(int)}.
 */
public interface NativeDesktop extends NativeInterface {
  /**
   * Replaces the menu bar. One line per entry, tab separated: kind (M menu, I item, S separator,
   * A responder-chain selector, V services), depth, id or selector, key, modifiers, title, checked.
   */
  boolean install(String spec);

  /** Sets the check mark of the item with this id. */
  void check(int id, boolean on);

  /** Shows the standard About panel with this version and HTML credits. */
  void about(String version, String credits);

  /**
   * Sizes the main window to these percentages of the screen's usable area and centers it. The
   * window remembers its frame from then on, so this is for a first launch.
   */
  void fitWindow(int widthPercent, int heightPercent);
}
