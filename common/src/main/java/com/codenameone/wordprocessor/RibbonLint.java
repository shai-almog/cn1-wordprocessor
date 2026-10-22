package com.codenameone.wordprocessor;

import com.codename1.ui.Component;
import com.codename1.ui.Container;
import com.codename1.ui.Label;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds ribbon controls laid out badly: squeezed below their preferred size (their icon and text
 * then paint over their neighbors), overlapping a sibling, or cut off by a fixed container.
 * Accessibility bounds cannot show a squeezed control, so this inspects the live components.
 */
final class RibbonLint {
  private RibbonLint() {}

  /** Every tab's defects, selecting each visible tab in turn and restoring the first selection. */
  static List<String> check(OfficeRibbon ribbon, Runnable relayout) {
    return check(ribbon, relayout, true);
  }

  /**
   * {@code squeeze} off skips the preferred-size rule, for the simulator's test theme whose
   * metrics differ from the native look and feel the ribbon is sized for.
   */
  static List<String> check(OfficeRibbon ribbon, Runnable relayout, boolean squeeze) {
    var out = new ArrayList<String>();
    var titles = ribbon.titles();
    int restore = ribbon.selectedIndex();
    for (int i = 0; i < titles.size(); i++) {
      if (!ribbon.tabVisible(titles.get(i))) continue;
      ribbon.select(i);
      relayout.run();
      defects(ribbon, titles.get(i), out, squeeze);
    }
    if (restore >= 0) ribbon.select(restore);
    relayout.run();
    return out;
  }

  static void defects(Container c, String tab, List<String> out, boolean squeeze) {
    var kids = new ArrayList<Component>();
    for (int i = 0; i < c.getComponentCount(); i++) {
      var k = c.getComponentAt(i);
      if (k.isVisible() && !k.isHidden() && k.getWidth() > 0 && k.getHeight() > 0) kids.add(k);
    }
    for (int i = 0; i < kids.size(); i++) {
      var a = kids.get(i);
      // Padding may give way; the icon and text inside it may not.
      int content = a.getPreferredH() - a.getStyle().getPaddingTop() - a.getStyle().getPaddingBottom();
      if (squeeze && !(a instanceof Container) && a.getHeight() + 1 < content)
        out.add(tab + ": " + name(a) + " is squeezed to " + a.getHeight() + "px; its content needs " + content + "px");
      // A row wider than the window folds groups or scrolls behind the ribbon's arrows, so only
      // a fixed container can cut a control off sideways.
      if (a.getY() + a.getHeight() > c.getHeight() + 1
          || !c.isScrollableX() && a.getX() + a.getWidth() > c.getWidth() + 1)
        out.add(tab + ": " + name(a) + " is cut off by its container");
      for (int j = i + 1; j < kids.size(); j++) {
        var b = kids.get(j);
        boolean overlap = a.getX() < b.getX() + b.getWidth() - 1 && b.getX() < a.getX() + a.getWidth() - 1
            && a.getY() < b.getY() + b.getHeight() - 1 && b.getY() < a.getY() + a.getHeight() - 1;
        if (overlap) out.add(tab + ": " + name(a) + " overlaps " + name(b));
      }
      if (a instanceof Container k) defects(k, tab, out, squeeze);
    }
  }

  private static String name(Component c) {
    String t = text(c);
    return t.isEmpty() ? c.getClass().getSimpleName() : "\"" + t + "\"";
  }

  private static String text(Component c) {
    if (c instanceof Label l && l.getText() != null && !l.getText().isEmpty()) return l.getText();
    if (c.getName() != null) return c.getName();
    if (c instanceof Container k)
      for (int i = 0; i < k.getComponentCount(); i++) {
        String t = text(k.getComponentAt(i));
        if (!t.isEmpty()) return t;
      }
    return "";
  }
}
