package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import java.util.List;
import java.util.Vector;

/**
 * Uses CN1's desktop title-bar integration, retaining the OS menu and window controls. Menu
 * commands go only to the native menu bar: added to the side menu they would also appear as a
 * hamburger button with a phone-style drawer in the title area.
 */
final class OfficeTitleBar extends Toolbar {
  private Component content;
  private final Vector<Command> nativeMenu = new Vector<>();

  OfficeTitleBar(Component content) {
    this.content = content;
    setTitleCentered(false);
    OfficeTheme.surface(this, OfficeTheme.STRIP);
    // Toolbar adds measured native-control insets from the current OS/DPI.
    OfficeTheme.space(this, 2, 2, 8, 8);
    getAllStyles().setBorder(com.codename1.ui.plaf.Border.createEmpty());
  }

  /**
   * Commands for the platform menu bar (Windows and Linux; the Mac builds its own).
   */
  void nativeMenu(List<Command> commands) {
    nativeMenu.clear();
    nativeMenu.addAll(commands);
    // Any command-list change republishes the menu once the form is on screen.
    removeCommand(REPUBLISH);
  }

  private static final Command REPUBLISH = new Command("");

  @Override
  public Vector getAllNativeMenuCommands() {
    Vector all = super.getAllNativeMenuCommands();
    for (Command c : nativeMenu) if (!all.contains(c)) all.addElement(c);
    return all;
  }

  @Override
  public void setTitle(String title) {
    super.setTitle(title);
    if (content != null) setTitleComponent(content);
  }
}
