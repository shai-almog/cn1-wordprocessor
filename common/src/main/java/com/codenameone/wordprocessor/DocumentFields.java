package com.codenameone.wordprocessor;

import java.util.*;

/** Resolves supported field instructions against a paginated document and explicit host values. */
public final class DocumentFields {
  private DocumentFields() {}

  public static Session.FieldResolver resolver(
      Document doc, LayoutEngine.Result layout, Map<String, String> values) {
    final var notePrefixes = new HashMap<String, Integer>();
    for (Note note : Notes.all(layout.document)) notePrefixes.put(note.paragraphs.get(0).id, note.label.length() + 1);
    var lines = new HashMap<String, List<LayoutEngine.Line>>();
    for (LayoutEngine.Page page : layout.pages)
      for (LayoutEngine.Line line : page.allLines()) {
        var list = lines.get(line.paragraphId);
        if (list == null) lines.put(line.paragraphId, list = new ArrayList<LayoutEngine.Line>());
        list.add(line);
      }
    final Session.FieldResolver sequences = SequenceFields.resolver(doc);
    final Session.FieldResolver formulas = TableFormulas.resolver(doc);
    return (paragraph, offset, field) -> {
      int number = 0;
      var format = "decimal";
      int displayOffset = offset + (notePrefixes.containsKey(paragraph) ? notePrefixes.get(paragraph) : 0);
      var ls = lines.get(paragraph);
      if (ls != null)
        for (LayoutEngine.Line line : ls)
          if (displayOffset >= line.start && displayOffset < line.end) {
            number = layout.pages.get(line.page).number;
            format = layout.pages.get(line.page).decor.numberFormat;
            // SECTION and SECTIONPAGES as Wordcraft's FieldCtx: the page's section (1-based)
            // and how many pages that section has; AUTHOR from the document's properties.
            int section = layout.pages.get(line.page).section, pages = 0;
            for (LayoutEngine.Page pg : layout.pages) if (pg.section == section) pages++;
            var more = new java.util.HashMap<String, String>(values);
            more.put("SECTION", "" + (section + 1));
            more.put("SECTIONPAGES", "" + pages);
            String author = doc.properties.get("author");
            // Wordcraft's field_text: AUTHOR shows the properties' author only when the field has
            // no saved result; a saved result is kept as Word wrote it.
            if (author != null && !author.isEmpty() && (field.result == null || field.result.isEmpty())
                && !tokens(field.instruction).isEmpty()
                && "AUTHOR".equalsIgnoreCase(tokens(field.instruction).get(0)))
              more.putIfAbsent("AUTHOR", author);
            return resolve(CrossReferences.resolve(doc, layout, lines, paragraph, offset,
                sequences.resolve(paragraph, offset, formulas.resolve(paragraph, offset, field))),
                doc.title, number, layout.pages.size(), more, format);
          }
      var sequence = sequences.resolve(paragraph, offset, formulas.resolve(paragraph, offset, field));
      InlineField reference = CrossReferences.resolve(doc, layout, lines, paragraph, offset, sequence);
      return resolve(reference, doc.title, number, layout.pages.size(), values, format);
    };
  }

  public static InlineField resolve(
      InlineField field, String title, int page, int count, Map<String, String> values) {
    return resolve(field, title, page, count, values, "decimal");
  }

  /** pageFormat is the section's page-number format, used by PAGE fields without a \* switch. */
  public static InlineField resolve(
      InlineField field, String title, int page, int count, Map<String, String> values, String pageFormat) {
    if (field.locked || field.rangeStart() || field.rangeEnd()) return field;
    var tokens = tokens(field.instruction);
    if (tokens.isEmpty()) return field;
    String kind = tokens.get(0).toUpperCase(), result = null;
    String numberFormat = formatSwitch(tokens);
    if ("PAGE".equals(kind) && page > 0)
      result = ListNumbers.format(page, numberFormat != null ? numberFormat : pageFormat);
    else if ("NUMPAGES".equals(kind)) result = "" + count;
    else if ("TITLE".equals(kind)) result = title;
    else if ("MERGEFIELD".equals(kind) && tokens.size() > 1) result = values.get(tokens.get(1));
    else if ("DOCPROPERTY".equals(kind) && tokens.size() > 1)
      result = "Title".equalsIgnoreCase(tokens.get(1)) ? title : values.get(tokens.get(1));
    else result = values.get(kind);
    if (result == null) return field;
    boolean numeric = "NUMPAGES".equals(kind) || "SECTION".equals(kind) || "SECTIONPAGES".equals(kind);
    if (numeric && numberFormat != null)
      try {
        result = ListNumbers.format(Integer.parseInt(result.trim()), numberFormat);
      } catch (NumberFormatException ex) {
        // not a number: shown as it is
      }
    for (int i = 1; i + 1 < tokens.size(); i++)
      if ("\\*".equals(tokens.get(i))) {
        var format = tokens.get(++i);
        if ("Upper".equalsIgnoreCase(format)) result = result.toUpperCase();
        else if ("Lower".equalsIgnoreCase(format)) result = result.toLowerCase();
      }
    return field.result(result);
  }

  /**
   * The number format of a \\* switch as Wordcraft's format_switch (roman, ROMAN, alphabetic,
   * ALPHABETIC, CardText, OrdText, Ordinal, Arabic, ChineseNum1-3), or null.
   */
  static String formatSwitch(List<String> tokens) {
    for (int i = 1; i + 1 < tokens.size(); i++) {
      if (!"\\*".equals(tokens.get(i))) continue;
      String v = tokens.get(i + 1);
      switch (v) {
        case "roman": return "lowerRoman";
        case "ROMAN": case "Roman": return "upperRoman";
        case "alphabetic": return "lowerLetter";
        case "ALPHABETIC": case "Alphabetic": return "upperLetter";
        case "CardText": case "cardtext": return "cardinalText";
        case "OrdText": case "ordtext": return "ordinalText";
        case "Ordinal": case "ordinal": return "ordinal";
        case "Arabic": case "arabic": return "decimal";
        default:
          if ("ChineseNum1".equalsIgnoreCase(v)) return "chineseCountingThousand";
          if ("ChineseNum2".equalsIgnoreCase(v)) return "chineseLegalSimplified";
          if ("ChineseNum3".equalsIgnoreCase(v)) return "ideographDigital";
      }
    }
    return null;
  }

  public static List<String> tokens(String instruction) {
    return FieldCode.tokens(instruction);
  }

  /** The \\@ picture of a DATE/TIME field (quoted or a single word), or null (Wordcraft's date_picture). */
  public static String datePicture(String instruction) {
    int at = instruction.indexOf("\\@");
    if (at < 0) return null;
    String rest = instruction.substring(at + 2).trim();
    String pic;
    if (rest.startsWith("\"")) {
      int end = rest.indexOf('"', 1);
      pic = end < 0 ? rest.substring(1) : rest.substring(1, end);
    } else {
      int end = 0;
      while (end < rest.length() && rest.charAt(end) > ' ') end++;
      pic = rest.substring(0, end);
    }
    return pic.isEmpty() ? null : pic;
  }

  private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"};
  private static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday",
    "Friday", "Saturday"};

  private static String pad(int v, int n) {
    String s = "" + v;
    while (s.length() < n) s = "0" + s;
    return s;
  }

  /**
   * Formats a date with a Word picture as Wordcraft's format_date_parts: yyyy/yy, MMMM/MMM/MM/M,
   * dddd/ddd/dd/d, H/HH, h, m/mm, s/ss, am/pm and a/A; other characters are copied.
   * weekday is 0 for Sunday; month is 1-based.
   */
  public static String formatDate(String fmt, int y, int month, int d, int hh, int mm, int ss, int weekday) {
    String monthName = MONTHS[Math.max(0, Math.min(11, month - 1))];
    String day = DAYS[((weekday % 7) + 7) % 7];
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < fmt.length()) {
      char c = fmt.charAt(i);
      if (i + 5 <= fmt.length() && fmt.substring(i, i + 5).equalsIgnoreCase("am/pm")) {
        out.append(hh < 12 ? "AM" : "PM");
        i += 5;
        continue;
      }
      int n = 1;
      while (i + n < fmt.length() && fmt.charAt(i + n) == c) n++;
      switch (c) {
        case 'y': out.append(n >= 4 ? pad(y, 4) : pad(y % 100, 2)); break;
        case 'M': out.append(n >= 4 ? monthName : n == 3 ? monthName.substring(0, 3) : n == 2 ? pad(month, 2) : "" + month); break;
        case 'd': out.append(n >= 4 ? day : n == 3 ? day.substring(0, 3) : n == 2 ? pad(d, 2) : "" + d); break;
        case 'H': out.append(n >= 2 ? pad(hh, 2) : "" + hh); break;
        case 'h': out.append(hh % 12 == 0 ? 12 : hh % 12); break;
        case 'm': out.append(n >= 2 ? pad(mm, 2) : "" + mm); break;
        case 's': out.append(n >= 2 ? pad(ss, 2) : "" + ss); break;
        case 'a': case 'A': out.append(hh < 12 ? "AM" : "PM"); break;
        default: for (int k = 0; k < n; k++) out.append(c);
      }
      i += n;
    }
    return out.toString();
  }

  /** formatDate for a Date in the default time zone. */
  public static String formatDate(String fmt, java.util.Date date) {
    java.util.Calendar c = java.util.Calendar.getInstance();
    c.setTime(date);
    return formatDate(fmt, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
        c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND),
        c.get(java.util.Calendar.DAY_OF_WEEK) - 1);
  }
}
