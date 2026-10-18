package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Developer tab content controls. A control is an atomic field (see {@link ContentControl}); its
 * value is changed with developer.properties, design mode shows each control's tag around it.
 */
final class DeveloperCommands implements Commands.Extension {
  public void register(final Commands c) {
    control(c, "developer.richText", "richText", "Insert a rich text content control");
    control(c, "developer.plainText", "plainText", "Insert a plain text content control");
    control(c, "developer.picture", "picture", "Insert a picture content control");
    control(c, "developer.buildingBlock", "buildingBlock", "Insert a building block gallery content control");
    control(c, "developer.checkBox", "checkBox", "Insert a check box content control");
    control(c, "developer.comboBox", "comboBox", "Insert a combo box content control (items separated by |)");
    control(c, "developer.dropDown", "dropDown", "Insert a drop-down list content control (items separated by |)");
    control(c, "developer.datePicker", "datePicker", "Insert a date picker content control (date yyyy-mm-dd)");
    control(c, "developer.repeatingSection", "repeatingSection", "Insert a repeating section content control (items separated by |)");
    c.register(
        "developer.designMode",
        "Design mode: show each content control's tag around its content",
        "value?:boolean",
        true,
        (s, a) -> {
          final boolean[] any = {false};
          s.mapFields(
              (p, offset, f) -> {
                any[0] |= ContentControl.is(f) && f.showCode;
                return f;
              });
          final boolean on = a.containsKey("value") ? (Boolean) a.get("value") : !any[0];
          s.mapFields((p, offset, f) -> ContentControl.is(f) ? f.showCode(on) : f);
          return on;
        });
    c.register(
        "developer.properties",
        "Read or change the content control at the caret: title, tag, lock, items, value, checked, date, format",
        "title?:string,tag?:string,lock?:string,items?:string,value?:string,checked?:boolean,date?:string,format?:string,gallery?:string",
        true,
        (s, a) -> {
          final var found = new ArrayList<Object>();
          final var para = s.document().paragraph(s.selection().block).id;
          final int from = s.selection().start(), to = s.selection().end();
          final Map<String, Object> args = a;
          s.mapFields(
              (p, offset, f) -> {
                boolean inside =
                    para.equals(p)
                        && (from == to ? offset == from || offset == from - 1 : offset >= from && offset < to);
                if (!inside || !ContentControl.is(f) || !found.isEmpty()) return f;
                ContentControl control = ContentControl.parse(f.instruction);
                var result = f.result;
                for (String key : new String[] {"title", "tag", "lock", "items", "date", "format", "gallery"})
                  if (args.containsKey(key)) control = control.with(key, Commands.str(args, key));
                if (args.containsKey("checked")) {
                  if (!"checkBox".equals(control.type)) throw new IllegalArgumentException("Not a check box");
                  boolean on = (Boolean) args.get("checked");
                  control = control.with("checked", String.valueOf(on));
                  result = on ? ContentControl.CHECKED : ContentControl.UNCHECKED;
                }
                if (args.containsKey("value")) {
                  if (control.contentLocked()) throw new IllegalArgumentException("The control's contents are locked");
                  String value = Commands.str(args, "value");
                  if ("dropDown".equals(control.type) && !control.items().contains(value))
                    throw new IllegalArgumentException("Choose one of " + control.items());
                  result = value;
                }
                if (args.containsKey("date") || args.containsKey("format") && "datePicker".equals(control.type))
                  result = formatDate(control.get("date", ""), control.get("format", "M/d/yyyy"));
                found.add(Json.object("type", control.type, "properties", control.properties(), "value", result));
                var next = control.field(result);
                return f.showCode ? next.showCode(true) : next;
              });
          if (found.isEmpty()) throw new IllegalArgumentException("Place the caret on a content control");
          return found.get(0);
        });
  }

  private static void control(Commands c, String id, final String type, String description) {
    c.register(
        id,
        description,
        "title?:string,tag?:string,text?:string,items?:string,checked?:boolean,date?:string,format?:string",
        true,
        (s, a) -> {
          ContentControl control = ContentControl.of(type);
          for (String key : new String[] {"title", "tag", "items", "date", "format"})
            if (a.containsKey(key)) control = control.with(key, Commands.str(a, key));
          String result = a.containsKey("text") ? Commands.str(a, "text") : "";
          if ("checkBox".equals(type)) {
            boolean on = a.containsKey("checked") && (Boolean) a.get("checked");
            control = control.with("checked", String.valueOf(on));
            result = on ? ContentControl.CHECKED : ContentControl.UNCHECKED;
          } else if ("datePicker".equals(type) && a.containsKey("date"))
            result = formatDate(control.get("date", ""), control.get("format", "M/d/yyyy"));
          else if ("dropDown".equals(type) && !result.isEmpty() && !control.items().contains(result))
            throw new IllegalArgumentException("Choose one of " + control.items());
          else if ("repeatingSection".equals(type) && result.isEmpty() && a.containsKey("items"))
            result = control.get("items", "").replace("|", "; ");
          s.insertField(control.field(result));
          return control.instruction();
        });
  }

  private static final String[] MONTHS = {
    "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
    "November", "December"
  };

  /** Formats yyyy-mm-dd with M, MM, MMMM, d, dd and yyyy/yy tokens. */
  static String formatDate(String iso, String format) {
    if (iso.isEmpty()) return "";
    if (iso.length() != 10 || iso.charAt(4) != '-' || iso.charAt(7) != '-')
      throw new IllegalArgumentException("Date must be yyyy-mm-dd");
    int y, m, d;
    try {
      y = Integer.parseInt(iso.substring(0, 4));
      m = Integer.parseInt(iso.substring(5, 7));
      d = Integer.parseInt(iso.substring(8, 10));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Date must be yyyy-mm-dd");
    }
    if (m < 1 || m > 12 || d < 1 || d > 31) throw new IllegalArgumentException("Invalid date");
    var b = new StringBuilder();
    for (int i = 0; i < format.length(); ) {
      char ch = format.charAt(i);
      int n = 1;
      while (i + n < format.length() && format.charAt(i + n) == ch) n++;
      if (ch == 'M') b.append(n >= 4 ? MONTHS[m - 1] : n == 3 ? MONTHS[m - 1].substring(0, 3) : n == 2 && m < 10 ? "0" + m : String.valueOf(m));
      else if (ch == 'd') b.append(n == 2 && d < 10 ? "0" + d : String.valueOf(d));
      else if (ch == 'y') b.append(n <= 2 ? String.valueOf(y % 100 < 10 ? "0" + y % 100 : y % 100) : String.valueOf(y));
      else for (int k = 0; k < n; k++) b.append(ch);
      i += n;
    }
    return b.toString();
  }
}
