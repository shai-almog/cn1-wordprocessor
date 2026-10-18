package com.codenameone.wordprocessor;

import com.codename1.ui.*;

/** Uses CN1's desktop title-bar integration, retaining the OS menu and window controls. */
final class OfficeTitleBar extends Toolbar {
  private Component content;

  OfficeTitleBar(Component content) {
    this.content = content;
    setTitleCentered(false);
    OfficeTheme.surface(this, OfficeTheme.STRIP);
    // Toolbar adds measured native-control insets from the current OS/DPI.
    OfficeTheme.space(this, 2, 2, 8, 8);
    getAllStyles().setBorder(com.codename1.ui.plaf.Border.createEmpty());
  }

  @Override
  public void setTitle(String title) {
    super.setTitle(title);
    if (content != null) setTitleComponent(content);
  }
}
