package com.codenameone.wordprocessor;

import com.codename1.ui.Component;
import com.codename1.ui.Desktop;
import com.codename1.ui.Display;
import com.codename1.ui.Window;
import com.codename1.ui.events.WheelEvent;

/**
 * Corrects scroll direction on the native macOS port, which forwards AppKit's scrolling deltas
 * with the opposite sign to the framework's wheel contract. Content then moved against the
 * fingers on a trackpad (and against the wheel on a mouse), unlike every other Mac
 * application. The wheel is consumed at the root of the window and replayed with inverted
 * deltas, so scroll chaining, edges and component wheel handling all stay the framework's.
 * Remove when the port sends the deltas the contract specifies.
 */
final class MacScrolling {
  private static boolean replaying;

  private MacScrolling() {}

  static void install(Component root) {
    if (!"mac".equals(Display.getInstance().getPlatformName())) return;
    root.addMouseWheelListener(
        e -> {
          if (replaying || !(e instanceof WheelEvent wheel)) return;
          e.consume();
          replaying = true;
          try {
            Desktop.getInstance()
                .windowMouseWheelEvent(windowId(root), wheel.getX(), wheel.getY(),
                    -wheel.getDeltaX(), -wheel.getDeltaY(), wheel.isPrecise(), wheel.getModifiers());
          } finally {
            replaying = false;
          }
        });
  }

  /** The native window hosting {@code c}; 0 is the main window. */
  private static int windowId(Component c) {
    for (Component p = c; p != null; p = p.getParent()) if (p instanceof Window w) return w.getWindowId();
    return 0;
  }
}
