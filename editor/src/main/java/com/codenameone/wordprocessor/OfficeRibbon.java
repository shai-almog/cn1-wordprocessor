package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import com.codename1.ui.accessibility.*;
import com.codename1.ui.layouts.*;
import com.codename1.ui.plaf.Border;
import java.util.ArrayList;
import java.util.List;

/** Grouped ribbon with persistent tab selection and keyboard-focusable commands. */
final class OfficeRibbon extends Container {
  private final Container tabs = OfficeTheme.row(), body = new Container(new BorderLayout());
  private final Container strip = new Container(new BorderLayout());
  private boolean collapsed, selecting;
  private final ComboBox<String> mobileTabs = new ComboBox<String>();
  private final Button collapse = new Button("\u2303");
  private final List<Button> buttons = new ArrayList<Button>();
  private final List<String> titles = new ArrayList<String>();
  private final List<Boolean> visibility = new ArrayList<Boolean>();
  private final List<Integer> mobileIndices = new ArrayList<Integer>();
  private int selectedIndex;
  private final List<Container> pages = new ArrayList<Container>();

  OfficeRibbon() {
    super(new BorderLayout());
    OfficeTheme.surface(this, 0xffffff);
    OfficeTheme.surface(tabs, 0xf5f5f5);
    OfficeTheme.space(tabs, 0, 0, 10, 10);
    tabs.setScrollableX(!OfficeTheme.mobile());
    if (OfficeTheme.mobile()) {
      OfficeTheme.field(mobileTabs, 130);
      mobileTabs.setAccessibilityText("Ribbon tabs");
      mobileTabs.setPreferredH(OfficeTheme.px(44));
      mobileTabs.addActionListener(
          e -> {
            if (!selecting && mobileTabs.getSelectedIndex() >= 0)
              select(mobileIndices.get(mobileTabs.getSelectedIndex()));
          });
    }
    tabs.setScrollVisible(false);
    OfficeTheme.surface(strip, 0xf5f5f5);
    strip.add(BorderLayout.CENTER, tabs);
    OfficeTheme.button(collapse);
    collapse.setAccessibilityText("Collapse ribbon");
    collapse.addActionListener(e -> toggleCollapsed());
    add(BorderLayout.NORTH, strip);
    add(BorderLayout.CENTER, body);
    getAllStyles()
        .setBorder(
            Border.createCompoundBorder(null, Border.createLineBorder(1, 0xc8c8c8), null, null));
  }

  void addActionTab(String title, Runnable action) {
    Button b = new Button(title);
    OfficeTheme.button(b);
    OfficeTheme.space(b, 6, 6, 9, 9);
    b.getAllStyles().setFont(OfficeTheme.font(13, false));
    b.setPreferredW(OfficeTheme.px(52));
    b.addActionListener(e -> action.run());
    tabs.add(b);
    if (OfficeTheme.mobile()) tabs.add(mobileTabs);
  }

  void addTab(String title, Container page) {
    final int index = pages.size();
    pages.add(page);
    titles.add(title);
    visibility.add(true);
    mobileIndices.add(index);
    if (OfficeTheme.mobile()) {
      selecting = true;
      mobileTabs.addItem(title);
      selecting = false;
      return;
    }
    Button b = new Button(title);
    OfficeTheme.button(b);
    OfficeTheme.space(b, 6, 6, 9, 9);
    b.getAllStyles().setFont(OfficeTheme.font(13, false));
    b.addActionListener(e -> select(index));
    b.getSemantics().setRole(AccessibilityRole.TAB).setSelected(Boolean.FALSE);
    tabs.getSemantics().setRole(AccessibilityRole.TAB_LIST).setLabel("Ribbon tabs");
    buttons.add(b);
    tabs.add(b);
  }

  java.util.List<String> titles() {
    return java.util.Collections.unmodifiableList(titles);
  }

  void setTabVisible(String title, boolean visible) {
    int index = titles.indexOf(title);
    if (index < 0 || visibility.get(index) == visible) return;
    visibility.set(index, visible);
    if (OfficeTheme.mobile()) {
      selecting = true;
      while (mobileTabs.getModel().getSize() > 0) mobileTabs.getModel().removeItem(0);
      mobileIndices.clear();
      for (int i = 0; i < titles.size(); i++)
        if (visibility.get(i)) {
          mobileIndices.add(i);
          mobileTabs.addItem(titles.get(i));
        }
      mobileTabs.setSelectedIndex(
          mobileIndices.indexOf(visibility.get(selectedIndex) ? selectedIndex : 0));
      selecting = false;
    } else {
      buttons.get(index).setHidden(!visible);
      buttons.get(index).setVisible(visible);
    }
    if (!visibility.get(selectedIndex)) select(0);
    revalidate();
  }

  void actions(Component actions) {
    strip.add(BorderLayout.EAST, OfficeTheme.row(actions, collapse));
  }

  void collapse() {
    if (!collapsed) toggleCollapsed();
  }

  void toggleCollapsed() {
    collapsed = !collapsed;
    collapse.setText(collapsed ? "\u2304" : "\u2303");
    collapse.setAccessibilityText(collapsed ? "Expand ribbon" : "Collapse ribbon");
    body.setHidden(collapsed);
    body.setVisible(!collapsed);
    revalidate();
  }

  void select(int index) {
    selecting = true;
    selectedIndex = index;
    if (OfficeTheme.mobile()) mobileTabs.setSelectedIndex(mobileIndices.indexOf(index));
    selecting = false;
    collapsed = false;
    collapse.setText("\u2303");
    collapse.setAccessibilityText("Collapse ribbon");
    body.setHidden(false);
    body.setVisible(true);
    body.removeAll();
    body.add(BorderLayout.CENTER, pages.get(index));
    for (int i = 0; i < buttons.size(); i++) {
      Button b = buttons.get(i);
      boolean selected = i == index;
      b.getSemantics().setSelected(Boolean.valueOf(selected));
      b.getUnselectedStyle().setFgColor(selected ? OfficeTheme.BLUE : OfficeTheme.INK);
      b.getUnselectedStyle().setFont(OfficeTheme.font(13, selected));
      b.getUnselectedStyle()
          .setBorder(
              selected
                  ? Border.createCompoundBorder(
                      null,
                      Border.createLineBorder(OfficeTheme.px(3), OfficeTheme.BLUE),
                      null,
                      null)
                  : Border.createEmpty());
    }
    revalidate();
    repaint();
  }
}
