package com.codenameone.wordprocessor;

import com.codename1.ui.Command;
import java.util.ArrayList;
import java.util.List;

/**
 * The desktop menu bar, defined once. The Mac builds it natively (submenus, separators, check
 * marks, the application/Window/Help menus); other desktops get the same entries as framework
 * commands, one native menu per top-level title.
 */
final class AppMenus {
  /** A platform menu bar that can show the full structure. */
  interface Native {
    boolean install(String spec);

    void check(int id, boolean on);
  }

  /** Current state of a check-marked entry. */
  interface State {
    boolean on();
  }

  static final int CMD = 1, SHIFT = 2, ALT = 4;

  private static final class Item {
    final String kind, title, selector;
    final char key;
    final int modifiers, depth;
    final Runnable action;
    final State checked;
    int id;
    boolean shown;

    Item(String kind, int depth, String title, char key, int modifiers, Runnable action,
        String selector, State checked) {
      this.kind = kind;
      this.depth = depth;
      this.title = title;
      this.key = key;
      this.modifiers = modifiers;
      this.action = action;
      this.selector = selector;
      this.checked = checked;
    }
  }

  private final List<Item> items = new ArrayList<>();
  private final List<Item> byId = new ArrayList<>();
  private int depth;
  private Native installed;

  /** Starts a top-level menu; the first one is the application menu. */
  AppMenus menu(String title) {
    items.add(new Item("M", 0, title, (char) 0, 0, null, null, null));
    depth = 1;
    return this;
  }

  AppMenus submenu(String title) {
    items.add(new Item("M", depth, title, (char) 0, 0, null, null, null));
    depth++;
    return this;
  }

  AppMenus end() {
    depth--;
    return this;
  }

  AppMenus item(String title, Runnable action) {
    return item(title, (char) 0, 0, action);
  }

  AppMenus item(String title, char key, int modifiers, Runnable action) {
    return add(new Item("I", depth, title, key, modifiers, action, null, null));
  }

  AppMenus toggle(String title, State on, Runnable action) {
    return toggle(title, (char) 0, 0, on, action);
  }

  AppMenus toggle(String title, char key, int modifiers, State on, Runnable action) {
    return add(new Item("I", depth, title, key, modifiers, action, null, on));
  }

  /**
   * An AppKit responder-chain action (copy:, undo:, performMiniaturize:) on the Mac; elsewhere
   * {@code fallback}, or nothing when it is null.
   */
  AppMenus system(String title, String selector, char key, int modifiers, Runnable fallback) {
    items.add(new Item("A", depth, title, key, modifiers, fallback, selector, null));
    return this;
  }

  /** The Services submenu AppKit fills in. */
  AppMenus services() {
    items.add(new Item("V", depth, "Services", (char) 0, 0, null, null, null));
    return this;
  }

  AppMenus separator() {
    items.add(new Item("S", depth, "", (char) 0, 0, null, null, null));
    return this;
  }

  private AppMenus add(Item item) {
    byId.add(item);
    item.id = byId.size();
    items.add(item);
    return this;
  }

  /** The tab-separated spec {@link Native#install} reads. */
  String spec() {
    var b = new StringBuilder();
    for (Item i : items) {
      if (b.length() > 0) b.append('\n');
      i.shown = i.checked != null && i.checked.on();
      b.append(i.kind).append('\t').append(i.depth).append('\t')
          .append("A".equals(i.kind) ? i.selector : String.valueOf(i.id)).append('\t')
          .append((int) i.key).append('\t').append(i.modifiers).append('\t')
          .append(i.title.replace('\t', ' ').replace('\n', ' ')).append('\t')
          .append(i.shown ? '1' : '0');
    }
    return b.toString();
  }

  /** Installs natively; false when the platform cannot, so {@link #commands} should be used. */
  boolean install(Native menuBar) {
    if (menuBar == null || !menuBar.install(spec())) return false;
    installed = menuBar;
    return true;
  }

  /** Runs the item a native selection names. */
  void fire(int id) {
    if (id >= 1 && id <= byId.size() && byId.get(id - 1).action != null) byId.get(id - 1).action.run();
  }

  /** Pushes check marks that changed since the last call. */
  void sync() {
    if (installed == null) return;
    for (Item i : byId)
      if (i.checked != null) {
        boolean on = i.checked.on();
        if (on != i.shown) {
          i.shown = on;
          installed.check(i.id, on);
        }
      }
  }

  /**
   * The entries as framework commands for desktops without the native structure: each runnable
   * item is placed in the native menu named by its top-level menu.
   */
  List<Command> commands(java.util.function.Function<Runnable, Runnable> wrap) {
    var out = new ArrayList<Command>();
    String menu = "";
    boolean app = true;
    for (Item i : items) {
      if ("M".equals(i.kind) && i.depth == 0) {
        app = menu.isEmpty();
        menu = i.title;
        continue;
      }
      if (i.action == null || "M".equals(i.kind)) continue;
      final Runnable run = wrap.apply(i.action);
      var c =
          new Command(i.title) {
            @Override
            public void actionPerformed(com.codename1.ui.events.ActionEvent e) {
              run.run();
            }
          };
      if (app)
        c.setDesktopMenu(
            i.title.startsWith("About") ? Command.DESKTOP_MENU_ABOUT
                : i.title.startsWith("Preferences") || i.title.startsWith("Settings")
                    ? Command.DESKTOP_MENU_PREFERENCES
                    : i.title.startsWith("Quit") ? Command.DESKTOP_MENU_QUIT : Command.DESKTOP_MENU_APP);
      else c.setDesktopMenu(menu);
      if (i.key != 0) c.setDesktopShortcut(i.key, toFramework(i.modifiers));
      out.add(c);
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

  /** Titles of every runnable entry, for command search. */
  List<String[]> titles() {
    var out = new ArrayList<String[]>();
    for (Item i : byId) out.add(new String[] {i.title, String.valueOf(i.id)});
    return out;
  }
}
