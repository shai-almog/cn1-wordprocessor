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
            break;
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
    if ("PAGE".equals(kind) && page > 0)
      result =
          "decimal".equals(pageFormat) || field.instruction.indexOf("\\*") >= 0
              ? "" + page
              : ListNumbers.format(page, pageFormat);
    else if ("NUMPAGES".equals(kind)) result = "" + count;
    else if ("TITLE".equals(kind)) result = title;
    else if ("MERGEFIELD".equals(kind) && tokens.size() > 1) result = values.get(tokens.get(1));
    else if ("DOCPROPERTY".equals(kind) && tokens.size() > 1)
      result = "Title".equalsIgnoreCase(tokens.get(1)) ? title : values.get(tokens.get(1));
    else result = values.get(kind);
    if (result == null) return field;
    final var numeric = result;
    for (int i = 1; i + 1 < tokens.size(); i++)
      if ("\\*".equals(tokens.get(i))) {
        var format = tokens.get(++i);
        if ("Upper".equalsIgnoreCase(format)) result = result.toUpperCase();
        else if ("Lower".equalsIgnoreCase(format)) result = result.toLowerCase();
        else if ("PAGE".equals(kind) || "NUMPAGES".equals(kind)) {
          int number = Integer.parseInt(numeric);
          if ("ROMAN".equals(format)) result = ListNumbers.format(number, "upperRoman");
          else if ("roman".equals(format)) result = ListNumbers.format(number, "lowerRoman");
          else if ("ALPHABETIC".equals(format)) result = ListNumbers.format(number, "upperLetter");
          else if ("alphabetic".equals(format)) result = ListNumbers.format(number, "lowerLetter");
        }
      }
    return field.result(result);
  }

  public static List<String> tokens(String instruction) {
    return FieldCode.tokens(instruction);
  }
}
