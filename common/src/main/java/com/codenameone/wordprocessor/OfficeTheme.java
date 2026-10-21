package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import com.codename1.ui.layouts.*;
import com.codename1.ui.plaf.*;

/** Shared desktop ribbon styling in logical pixels, including Retina displays. */
final class OfficeTheme {
  static final int BLUE = 0x185abd, INK = 0x242424, LINE = 0xd9d9d9, PAPER = 0xffffff;
  // Office palette (light); dark/contrast go through chrome().
  static final int ACCENT = 0x3b5bdb, ACCENT_TEXT = 0x2b47b5, RIBBON = 0xf9f9f9, STRIP = 0xf0f0f0,
      CANVAS = 0xe6e6e6, BORDER = 0xe1e1e1, BORDER_STRONG = 0xc8c8c8, INPUT_BORDER = 0x8a8a8a,
      DIM = 0x616161, GROUP_LABEL = 0x707070, HOVER = 0xe6e6e6, PRESSED = 0xd6d6d6, CHECKED = 0xdde3f8;

  static boolean mobile() {
    return !Display.getInstance().isDesktop()
        && ("ios".equals(Display.getInstance().getPlatformName())
            || "and".equals(Display.getInstance().getPlatformName())
            || "HTML5".equals(Display.getInstance().getPlatformName()));
  }

  static float ratio() {
    return Math.max(1, Display.getInstance().getDevicePixelRatio());
  }

  static int px(float value) {
    return Math.round(value * ratio());
  }

  static Font font(float size, boolean bold) {
    return Font.createTrueTypeFont(bold ? "native:MainBold" : "native:MainRegular")
        .derive(px(size), bold ? Font.STYLE_BOLD : Font.STYLE_PLAIN);
  }

  static void install() {
    refreshAppearance();
    // Keep the native theme's desktop title/menu contract.
    Display.getInstance().installNativeTheme();
    Toolbar.setGlobalToolbar(true);
    if (UIManager.getInstance().getLookAndFeel() instanceof DefaultLookAndFeel) {
      var icon = new Style();
      icon.setFgColor(INK);
      icon.setBgTransparency(0);
      var unchecked =
          FontImage.createMaterial(FontImage.MATERIAL_CHECK_BOX_OUTLINE_BLANK, icon)
              .scaled(px(18), px(18));
      icon.setFgColor(BLUE);
      var checked =
          FontImage.createMaterial(FontImage.MATERIAL_CHECK_BOX, icon).scaled(px(18), px(18));
      var look = (DefaultLookAndFeel) UIManager.getInstance().getLookAndFeel();
      look.setCheckBoxImages(checked, unchecked);
      look.setCheckBoxFocusImages(checked, unchecked, checked, unchecked);
    }
  }

  static void space(Component c, int top, int bottom, int left, int right) {
    var s = c.getAllStyles();
    s.setPaddingUnit(Style.UNIT_TYPE_PIXELS);
    s.setMarginUnit(Style.UNIT_TYPE_PIXELS);
    s.setPadding(px(top), px(bottom), px(left), px(right));
    s.setMargin(0, 0, 0, 0);
  }

  static void surface(Component c, int color) {
    c.getAllStyles().setBgColor(color);
    c.getAllStyles().setBgTransparency(255);
  }

  static Label label(String text, float size, int color) {
    var l = new Label(text);
    l.getAllStyles().setBgTransparency(0);
    space(l, 0, 0, 0, 0);
    l.getAllStyles().setFont(font(size, false));
    l.getAllStyles().setFgColor(color);
    return l;
  }

  static void button(Button b) {
    space(b, mobile() ? 8 : 3, mobile() ? 8 : 3, 5, 5);
    if (mobile()) {
      b.setPreferredH(px(44));
      b.setFocusable(false);
    }
    // Rounded 4pt state fills: transparent at rest, hover, pressed (Office ribbon buttons).
    b.getAllStyles().setBorder(OfficeBorder.of(-1, 4));
    b.getAllStyles().setFont(font(12, false));
    b.getDisabledStyle().setBgTransparency(0);
    // Every state gets a light-theme ink: a state left to the native theme carries its Dark Mode
    // colors, which the appearance mapping would invert into dark text on a dark hover fill.
    b.getUnselectedStyle().setFgColor(INK);
    b.getSelectedStyle().setFgColor(INK);
    b.getPressedStyle().setFgColor(INK);
    b.getDisabledStyle().setFgColor(0xa0a0a0);
    b.getUnselectedStyle().setBgTransparency(0);
    b.getSelectedStyle().setBgColor(HOVER);
    b.getSelectedStyle().setBgTransparency(255);
    b.getPressedStyle().setBgColor(PRESSED);
    b.getPressedStyle().setBgTransparency(255);
  }

  /** Checked toggle (Bold, Format Painter, current view): checked fill with a soft accent edge. */
  static void checked(Button b, boolean on) {
    b.getUnselectedStyle().setBgTransparency(on ? 255 : 0);
    b.getUnselectedStyle().setBgColor(CHECKED);
    b.getUnselectedStyle().setBorder(OfficeBorder.of(on ? 0x8c9dea : -1, 4));
  }

  /** Primary action (Share): accent fill, white text, rounded 4pt. */
  static void primary(Button b) {
    button(b);
    for (Style st : new Style[] {b.getUnselectedStyle(), b.getSelectedStyle(), b.getPressedStyle()}) {
      st.setBgColor(ACCENT);
      st.setBgTransparency(255);
      st.setFgColor(0xffffff);
    }
    b.getPressedStyle().setBgColor(ACCENT_TEXT);
    b.getAllStyles().setBorder(OfficeBorder.of(-1, 4));
    var glyph = b.getClientProperty("officeTheme.icon");
    if (glyph instanceof int[] ints) icon(b, (char) ints[0], ints[1], 0xffffff);
  }

  /** Framed button (Comments, Editing): white with an input border, rounded 4pt. */
  static void framed(Button b) {
    button(b);
    b.getUnselectedStyle().setBgColor(PAPER);
    b.getUnselectedStyle().setBgTransparency(255);
    b.getAllStyles().setBorder(OfficeBorder.of(INPUT_BORDER, 4));
    b.getSelectedStyle().setBorder(OfficeBorder.of(BORDER_STRONG, 4));
  }

  private static final String ICON = "officeTheme.icon";

  static void icon(Button b, char glyph, int size, int color) {
    // Remember the light-theme glyph so applyAppearance can redraw it for dark/contrast.
    b.putClientProperty(ICON, new int[] {glyph, size, color, chrome(color)});
    b.setIcon(glyph(glyph, size, chrome(color)));
    b.setGap(px(5));
  }

  static Image glyphImage(int glyph, int size, int color) {
    return glyph(glyph, size, color);
  }

  private static Image glyph(int glyph, int size, int color) {
    var s = new Style();
    s.setFgColor(color);
    s.setBgTransparency(0);
    return FontImage.createMaterial((char) glyph, s).scaled(px(size), px(size));
  }

  static Container row(Component... children) {
    var c = new Container(BoxLayout.x());
    c.getAllStyles().setBgTransparency(0);
    space(c, 0, 0, 0, 0);
    for (Component child : children) c.add(child);
    return c;
  }

  static Container group(String caption, Component content) {
    var c = new Container(new BorderLayout());
    space(c, 3, 2, 8, 8);
    surface(c, RIBBON);
    c.getAllStyles()
        .setBorder(Border.createCompoundBorder(null, null, null, Border.createLineBorder(1, BORDER)));
    c.add(BorderLayout.CENTER, content);
    Label l = label(caption, 11, GROUP_LABEL);
    l.setAlignment(Component.CENTER);
    space(l, 3, 0, 0, 0);
    c.add(BorderLayout.SOUTH, l);
    return c;
  }

  static Container group(String caption, Component content, Runnable launch) {
    Container group = group(caption, content);
    var label = ((BorderLayout) group.getLayout()).getSouth();
    group.removeComponent(label);
    var button = new Button("\u2198");
    button(button);
    button.setAccessibilityText(caption + " dialog");
    button.addActionListener(e -> launch.run());
    var footer = new Container(new BorderLayout());
    footer.add(BorderLayout.CENTER, label);
    footer.add(BorderLayout.EAST, button);
    group.add(BorderLayout.SOUTH, footer);
    return group;
  }

  private static final class GroupScroller extends Container {
    GroupScroller(Component[] groups) {
      super(BoxLayout.x());
      for (Component group : groups) add(group);
    }

    void move(int direction) {
      setScrollX(
          Math.max(
              0,
              Math.min(
                  getScrollDimension().getWidth() - getWidth(),
                  getScrollX() + direction * Math.max(px(150), getWidth() / 2))));
      repaint();
    }
  }

  static Container groups(Component... groups) {
    final var row = new GroupScroller(groups);
    surface(row, RIBBON);
    space(row, 3, 3, 5, 5);
    row.setScrollableX(true);
    row.setScrollVisible(false);
    Button left = new Button("\u2039"), right = new Button("\u203a");
    for (Button arrow : new Button[] {left, right}) {
      button(arrow);
      arrow.setPreferredW(px(18));
      arrow.setHidden(true);
      arrow.setVisible(false);
    }
    left.setAccessibilityText("Previous ribbon groups");
    right.setAccessibilityText("More ribbon groups");
    left.addActionListener(e -> row.move(-1));
    right.addActionListener(e -> row.move(1));
    var frame =
        new Container(new BorderLayout()) {
          protected void laidOut() {
            super.laidOut();
            int needed = 0;
            for (Component group : groups) needed += group.getPreferredW();
            boolean overflow = needed + px(10) > getWidth();
            if (left.isVisible() != overflow) {
              left.setHidden(!overflow);
              left.setVisible(overflow);
              right.setHidden(!overflow);
              right.setVisible(overflow);
              revalidate();
            }
          }
        };
    frame.add(BorderLayout.WEST, left).add(BorderLayout.CENTER, row).add(BorderLayout.EAST, right);
    surface(frame, RIBBON);
    frame.getAllStyles().setBorder(Border.createCompoundBorder(null, Border.createLineBorder(1, BORDER), null, null));
    frame.setPreferredH(px(mobile() ? 144 : 94));
    return frame;
  }

  static void field(Component c, int width) {
    c.getAllStyles().setFont(font(13, false));
    c.getAllStyles().setFgColor(INK);
    space(c, 3, 3, 6, 6);
    surface(c, PAPER);
    c.getAllStyles().setBorder(OfficeBorder.of(INPUT_BORDER, 4));
    c.setPreferredW(px(width));
  }

  // ---- Appearance (light/dark/high contrast) ----
  //
  // The chrome is styled imperatively with light colors. Rather than duplicating every
  // literal, each style's original light colors are remembered on the component and mapped
  // through chrome(int) for the active appearance, so the OS setting can flip live. The
  // document page is paper and keeps its print colors in every appearance.

  private static final String LIGHT_COLORS = "officeTheme.light";
  private static boolean dark, contrast;

  static boolean dark() {
    return dark;
  }

  /** True when the chrome needs mapping (anything other than the default light theme). */
  static boolean mapped() {
    return dark || contrast;
  }

  /** Reads the OS appearance; returns true when it changed since the last call. */
  static boolean refreshAppearance() {
    // Office Theme (File > Options): follow the OS, or pin the chrome light or dark.
    String theme = AppOptions.get("officeTheme");
    // Read the OS appearance, then pin the framework to ours so native-theme styles ("$Dark"
    // variants, dialogs) agree with the chrome when Office Theme overrides the OS.
    CN.setDarkMode(null);
    Boolean os = CN.isDarkMode();
    boolean d = "dark".equals(theme) || !"light".equals(theme) && os != null && os.booleanValue();
    CN.setDarkMode(Boolean.valueOf(d));
    boolean c = Display.getInstance().isHighContrastEnabled();
    if (d == dark && c == contrast) return false;
    dark = d;
    contrast = c;
    return true;
  }

  /** Maps a light-theme chrome color to the active appearance. */
  static int chrome(int light) {
    if (!dark && !contrast) return light;
    int r = (light >> 16) & 0xff, g = (light >> 8) & 0xff, b = light & 0xff;
    int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
    float l = (max + min) / 510f;
    if (!dark) {
      // High contrast light: push neutrals to the extremes, keep accents.
      if (max - min > 24) return light;
      return l > .5f ? 0xffffff : 0x000000;
    }
    // Invert lightness, keep hue; leave a little headroom so surfaces stay distinguishable.
    float target = contrast ? (l > .5f ? 0f : 1f) : .08f + (1 - l) * .84f;
    if (max == min) {
      int v = Math.round(target * 255);
      return (v << 16) | (v << 8) | v;
    }
    float d = (max - min) / 255f;
    float sat = l > .5f ? d / (2 - 2 * l) : d / (2 * l);
    float hue;
    if (max == r) hue = ((g - b) / 255f) / d + (g < b ? 6 : 0);
    else if (max == g) hue = ((b - r) / 255f) / d + 2;
    else hue = ((r - g) / 255f) / d + 4;
    hue /= 6;
    float q = target < .5f ? target * (1 + sat) : target + sat - target * sat;
    float pp = 2 * target - q;
    return (channel(pp, q, hue + 1f / 3) << 16) | (channel(pp, q, hue) << 8)
        | channel(pp, q, hue - 1f / 3);
  }

  private static int channel(float p, float q, float t) {
    if (t < 0) t += 1;
    if (t > 1) t -= 1;
    float v = t < 1f / 6 ? p + (q - p) * 6 * t : t < .5f ? q : t < 2f / 3 ? p + (q - p) * (2f / 3 - t) * 6 : p;
    return Math.max(0, Math.min(255, Math.round(v * 255)));
  }

  /**
   * Recolors a chrome tree for the active appearance. Components that paint the document
   * (and anything inside the page, such as cell editors) keep their own background only.
   */
  static void applyAppearance(Component root) {
    Style[] styles = {
      root.getUnselectedStyle(), root.getSelectedStyle(), root.getPressedStyle(),
      root.getDisabledStyle()
    };
    if (nativeChrome(root, styles)) {
      recurse(root);
      return;
    }
    // [light fg, light bg, applied fg, applied bg] per style. A color that differs from what
    // this pass applied was set by the app since (a toggle, hover, new state) and becomes the
    // new light original.
    var colors = (int[]) root.getClientProperty(LIGHT_COLORS);
    if (colors == null) {
      colors = new int[styles.length * 4];
      for (int i = 0; i < styles.length; i++) {
        colors[i * 4] = colors[i * 4 + 2] = styles[i].getFgColor();
        colors[i * 4 + 1] = colors[i * 4 + 3] = styles[i].getBgColor();
      }
      root.putClientProperty(LIGHT_COLORS, colors);
    }
    for (int i = 0; i < styles.length; i++) {
      int fg = styles[i].getFgColor(), bg = styles[i].getBgColor();
      if (fg != colors[i * 4 + 2]) colors[i * 4] = fg;
      if (bg != colors[i * 4 + 3]) colors[i * 4 + 1] = bg;
      colors[i * 4 + 2] = chrome(colors[i * 4]);
      colors[i * 4 + 3] = chrome(colors[i * 4 + 1]);
      if (fg != colors[i * 4 + 2]) styles[i].setFgColor(colors[i * 4 + 2]);
      if (bg != colors[i * 4 + 3]) styles[i].setBgColor(colors[i * 4 + 3]);
    }
    // The framework's text editors paint their own colors rather than the style's, so a
    // white field stayed white in dark mode. Hand them the mapped style colors.
    if (root instanceof com.codename1.ui.editor.EditorView field) {
      if (styles[0].getBgTransparency() != 0) field.setBackgroundColor(styles[0].getBgColor());
      field.setTextColor(styles[0].getFgColor());
      field.setSelectionColor(chrome(0xb3d4fc));
    }
    var icon = (int[]) root.getClientProperty(ICON);
    if (icon != null && icon[3] != chrome(icon[2]) && root instanceof Button button) {
      icon[3] = chrome(icon[2]);
      button.setIcon(glyph(icon[0], icon[1], icon[3]));
    }
    if (root instanceof ComboBox box && ((ComboBox) root).getRenderer() instanceof Component)
      applyAppearance((Component) box.getRenderer());
    recurse(root);
  }

  private static void recurse(Component root) {
    if (root instanceof EditorSurface || !(root instanceof Container)) return;
    var c = (Container) root;
    for (int i = 0; i < c.getComponentCount(); i++) applyAppearance(c.getComponentAt(i));
  }

  /**
   * The native theme paints the toolbar and status bar with images and gradients and
   * re-themes them itself on appearance changes, which a color mapping cannot follow. Paint
   * them as flat Office surfaces in every appearance instead.
   */
  private static boolean nativeChrome(Component c, Style[] styles) {
    var id = c.getUIID();
    if (!"TitleArea".equals(id) && !"Toolbar".equals(id) && !"StatusBar".equals(id)
        && !"Title".equals(id) && !"TitleCommand".equals(id) && !"BackCommand".equals(id))
      return false;
    for (Style st : styles) {
      st.setBgImage(null);
      st.setBackgroundType(Style.BACKGROUND_NONE);
      st.setBgTransparency(255);
      st.setBorder(Border.createEmpty());
      st.setBgColor(chrome(0xf7f7f7));
      st.setFgColor(chrome(INK));
    }
    return true;
  }
}
