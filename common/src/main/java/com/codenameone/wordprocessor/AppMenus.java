package com.codenameone.wordprocessor;

import com.codename1.ui.Command;
import java.util.ArrayList;
import java.util.List;

/**
 * The desktop menu bar, defined once and published through the framework's native menu bridge:
 * submenus, separators, check marks and shortcuts. Standard entries (Hide, Services, Window) are
 * the platform's own; phones and tablets get the runnable entries in the side menu.
 */
final class AppMenus {
  /** Current state of a check-marked entry. */
  interface State {
    boolean on();
  }

  static final int CMD = 1, SHIFT = 2, ALT = 4;

  private static final class Item {
    final String path, title;
    String role;
    boolean everywhere;
    final char key;
    final int modifiers;
    final Runnable action;
    final State checked;
    final boolean separator;
    Command command;

    Item(String path, String title, char key, int modifiers, Runnable action, String role, State checked,
        boolean separator) {
      this.path = path;
      this.title = title;
      this.key = key;
      this.modifiers = modifiers;
      this.action = action;
      this.role = role;
      this.checked = checked;
      this.separator = separator;
    }
  }

  private final List<Item> items = new ArrayList<>();
  private final List<String> path = new ArrayList<>();
  private boolean started;

  /** Starts a top-level menu; the first one is the application menu. */
  AppMenus menu(String title) {
    path.clear();
    path.add(started ? title : Command.DESKTOP_MENU_APP);
    started = true;
    return this;
  }

  AppMenus submenu(String title) {
    path.add(title.replace("/", "//"));
    return this;
  }

  AppMenus end() {
    path.remove(path.size() - 1);
    return this;
  }

  AppMenus item(String title, Runnable action) {
    return item(title, (char) 0, 0, action);
  }

  AppMenus item(String title, char key, int modifiers, Runnable action) {
    return add(title, key, modifiers, action, null, null);
  }

  AppMenus toggle(String title, State on, Runnable action) {
    return toggle(title, (char) 0, 0, on, action);
  }

  AppMenus toggle(String title, char key, int modifiers, State on, Runnable action) {
    return add(title, key, modifiers, action, null, on);
  }

  /**
   * A standard command ({@code Command.DESKTOP_ROLE_*}). The Mac keeps its own item for the role,
   * which goes through the responder chain; elsewhere {@code fallback} runs, or the entry is left
   * out when it is null.
   */
  AppMenus system(String title, String role, char key, int modifiers, Runnable fallback) {
    return fallback == null ? this : add(title, key, modifiers, fallback, role, null);
  }

  /** Gives the last entry a standard role on every platform, the Mac included. */
  AppMenus role(String role) {
    Item last = items.get(items.size() - 1);
    last.role = role;
    last.everywhere = true;
    return this;
  }

  AppMenus separator() {
    items.add(new Item(menuPath(), "", (char) 0, 0, null, null, null, true));
    return this;
  }

  private AppMenus add(String title, char key, int modifiers, Runnable action, String role, State on) {
    items.add(new Item(menuPath(), title, key, modifiers, action, role, on, false));
    return this;
  }

  private String menuPath() {
    return String.join("/", path);
  }

  /** Pushes check marks that changed; the framework republishes the native menu. */
  void sync() {
    for (Item i : items)
      if (i.checked != null && i.command != null) {
        boolean on = i.checked.on();
        if (on != i.command.isDesktopChecked()) i.command.setDesktopChecked(on);
      }
  }

  /**
   * The entries as framework commands, each running through {@code wrap}. With {@code menuBar}
   * they carry their menu path, separators, check marks and shortcuts; without it only the
   * runnable entries are returned, for a side menu.
   */
  List<Command> commands(java.util.function.Function<Runnable, Runnable> wrap, boolean menuBar) {
    var out = new ArrayList<Command>();
    for (Item i : items) {
      i.command = null;
      if (i.separator) {
        if (menuBar) out.add(Command.createDesktopSeparator(i.path));
        continue;
      }
      final Runnable run = wrap.apply(i.action);
      var c =
          new Command(i.title) {
            @Override
            public void actionPerformed(com.codename1.ui.events.ActionEvent e) {
              run.run();
            }
          };
      i.command = c;
      out.add(c);
      if (!menuBar) continue;
      c.setDesktopMenu(i.path);
      if (i.role != null) c.setDesktopRole(i.role);
      if (i.role != null && !i.everywhere) c.setDesktopPlatforms("win", "linux", "web");
      if (i.key != 0) c.setDesktopShortcut(i.key, toFramework(i.modifiers));
      if (i.checked != null) c.setDesktopChecked(i.checked.on());
    }
    return out;
  }

  private static int toFramework(int m) {
    int out = 0;
    if ((m & CMD) != 0) out |= Command.DESKTOP_SHORTCUT_MODIFIER_PRIMARY;
    if ((m & SHIFT) != 0) out |= Command.DESKTOP_SHORTCUT_MODIFIER_SHIFT;
    if ((m & ALT) != 0) out |= Command.DESKTOP_SHORTCUT_MODIFIER_ALT;
    return out;
  }
}
