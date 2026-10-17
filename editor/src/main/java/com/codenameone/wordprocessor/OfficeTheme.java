package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import com.codename1.ui.layouts.*;
import com.codename1.ui.plaf.*;

/** Shared desktop ribbon styling in logical pixels, including Retina displays. */
final class OfficeTheme {
  static final int BLUE = 0x185abd, INK = 0x242424, LINE = 0xd9d9d9, PAPER = 0xffffff;

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
    java.util.Hashtable<String, Object> props = new java.util.Hashtable<String, Object>();
    if (Display.getInstance().isDesktop()) props.put("@hideLeftSideMenuBool", "true");
    // The document UI is light. Keep native popup behavior while making its surface
    // consistent with our dark-text renderer, including on a dark OS theme.
    for (String id : new String[]{"ComboBoxPopup", "PopupContentPane", "ComboBoxList"}) {
      props.put(id + ".bgColor", "ffffff");
      props.put(id + ".transparency", "255");
      props.put(id + ".bgType", Byte.valueOf(Style.BACKGROUND_NONE));
      props.put(id + ".border", "ComboBoxPopup".equals(id)
          ? Border.createLineBorder(1, LINE) : Border.createEmpty());
    }
    UIManager.getInstance().addThemeProps(props);
    if (UIManager.getInstance().getLookAndFeel() instanceof DefaultLookAndFeel) {
      Style icon = new Style();
      icon.setFgColor(INK);
      icon.setBgTransparency(0);
      Image unchecked =
          FontImage.createMaterial(FontImage.MATERIAL_CHECK_BOX_OUTLINE_BLANK, icon)
              .scaled(px(18), px(18));
      icon.setFgColor(BLUE);
      Image checked =
          FontImage.createMaterial(FontImage.MATERIAL_CHECK_BOX, icon).scaled(px(18), px(18));
      DefaultLookAndFeel look = (DefaultLookAndFeel) UIManager.getInstance().getLookAndFeel();
      look.setCheckBoxImages(checked, unchecked);
      look.setCheckBoxFocusImages(checked, unchecked, checked, unchecked);
    }
  }

  static void space(Component c, int top, int bottom, int left, int right) {
    Style s = c.getAllStyles();
    s.setPaddingUnit(Style.UNIT_TYPE_PIXELS);
    s.setMarginUnit(Style.UNIT_TYPE_PIXELS);
    s.setPadding(px(top), px(bottom), px(left), px(right));
    s.setMargin(0, 0, 0, 0);
  }

  static void surface(Component c, int color) {
    c.getAllStyles().setBgColor(color);
    c.getAllStyles().setBgTransparency(255);
  }

  static Label label(String text, int size, int color) {
    Label l = new Label(text);
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
    b.getAllStyles().setBorder(Border.createEmpty());
    b.getAllStyles().setFont(font(12, false));
    b.getDisabledStyle().setBgTransparency(0);
    b.getUnselectedStyle().setFgColor(INK);
    b.getUnselectedStyle().setBgTransparency(0);
    b.getSelectedStyle().setBgColor(0xe8f0fb);
    b.getSelectedStyle().setBgTransparency(255);
    b.getPressedStyle().setBgColor(0xd3e2f7);
    b.getPressedStyle().setBgTransparency(255);
  }

  private static final String ICON = "officeTheme.icon";

  static void icon(Button b, char glyph, int size, int color) {
    // Remember the light-theme glyph so applyAppearance can redraw it for dark/contrast.
    b.putClientProperty(ICON, new int[] {glyph, size, color, chrome(color)});
    b.setIcon(glyph(glyph, size, chrome(color)));
    b.setGap(px(5));
  }

  private static Image glyph(int glyph, int size, int color) {
    Style s = new Style();
    s.setFgColor(color);
    s.setBgTransparency(0);
    return FontImage.createMaterial((char) glyph, s).scaled(px(size), px(size));
  }

  static Container row(Component... children) {
    Container c = new Container(BoxLayout.x());
    c.getAllStyles().setBgTransparency(0);
    space(c, 0, 0, 0, 0);
    for (Component child : children) c.add(child);
    return c;
  }

  static Container group(String caption, Component content) {
    Container c = new Container(new BorderLayout());
    space(c, 3, 2, 8, 8);
    surface(c, 0xfafafa);
    c.getAllStyles()
        .setBorder(Border.createCompoundBorder(null, null, null, Border.createLineBorder(1, LINE)));
    c.add(BorderLayout.CENTER, content);
    Label l = label(caption, 11, 0x666666);
    l.setAlignment(Component.CENTER);
    space(l, 3, 0, 0, 0);
    c.add(BorderLayout.SOUTH, l);
    return c;
  }

  static Container group(String caption, Component content, Runnable launch) {
    Container group = group(caption, content);
    Component label = ((BorderLayout) group.getLayout()).getSouth();
    group.removeComponent(label);
    Button button = new Button("↘");
    button(button);
    button.setAccessibilityText(caption + " dialog");
    button.addActionListener(e -> launch.run());
    Container footer = new Container(new BorderLayout());
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
    final GroupScroller row = new GroupScroller(groups);
    surface(row, 0xfafafa);
    space(row, 3, 3, 5, 5);
    row.setScrollableX(true);
    row.setScrollVisible(false);
    Button left = new Button("‹"), right = new Button("›");
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
    Container frame =
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
    frame.setPreferredH(px(mobile() ? 144 : 92));
    return frame;
  }

  static void field(Component c, int width) {
    c.getAllStyles().setFont(font(13, false));
    c.getAllStyles().setFgColor(INK);
    space(c, 4, 4, 6, 6);
    surface(c, 0xfafafa);
    c.getAllStyles().setBorder(Border.createLineBorder(1, 0xbebebe));
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
    Boolean os = CN.isDarkMode();
    boolean d = os != null && os.booleanValue();
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
    // [light fg, light bg, applied fg, applied bg] per style. A color that differs from what
    // this pass applied was set by the app since (a toggle, hover, new state) and becomes the
    // new light original.
    int[] colors = (int[]) root.getClientProperty(LIGHT_COLORS);
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
    int[] icon = (int[]) root.getClientProperty(ICON);
    if (icon != null && icon[3] != chrome(icon[2]) && root instanceof Button) {
      icon[3] = chrome(icon[2]);
      ((Button) root).setIcon(glyph(icon[0], icon[1], icon[3]));
    }
    if (root instanceof ComboBox && ((ComboBox) root).getRenderer() instanceof Component)
      applyAppearance((Component) ((ComboBox) root).getRenderer());
    if (root instanceof EditorSurface || !(root instanceof Container)) return;
    Container c = (Container) root;
    for (int i = 0; i < c.getComponentCount(); i++) applyAppearance(c.getComponentAt(i));
  }

  /** Native popups (combo lists, menus) follow the same appearance as the chrome. */
  static void applyPopupAppearance() {
    java.util.Hashtable<String, Object> props = new java.util.Hashtable<String, Object>();
    String bg = Integer.toHexString(0x1000000 | chrome(PAPER)).substring(1);
    String fg = Integer.toHexString(0x1000000 | chrome(INK)).substring(1);
    for (String id : new String[] {"ComboBoxPopup", "PopupContentPane", "ComboBoxList", "ComboBoxItem"}) {
      props.put(id + ".bgColor", bg);
      props.put(id + ".fgColor", fg);
    }
    UIManager.getInstance().addThemeProps(props);
  }
}
