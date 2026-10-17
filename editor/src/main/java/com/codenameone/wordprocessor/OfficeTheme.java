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

  static void icon(Button b, char glyph, int size, int color) {
    Style s = new Style();
    s.setFgColor(color);
    s.setBgTransparency(0);
    b.setIcon(FontImage.createMaterial(glyph, s).scaled(px(size), px(size)));
    b.setGap(px(5));
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
}
