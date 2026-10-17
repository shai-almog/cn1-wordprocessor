package com.codenameone.wordprocessor;

import java.util.*;

/** Resolves supported field instructions against a paginated document and explicit host values. */
public final class DocumentFields {
  private DocumentFields() {}

  public static Session.FieldResolver resolver(
      Document doc, LayoutEngine.Result layout, Map<String, String> values) {
    final Map<String, Integer> notePrefixes = new HashMap<String, Integer>();
    for (Note note : Notes.all(layout.document)) notePrefixes.put(note.paragraphs.get(0).id, note.label.length() + 1);
    Map<String, List<LayoutEngine.Line>> lines = new HashMap<String, List<LayoutEngine.Line>>();
    for (LayoutEngine.Page page : layout.pages)
      for (LayoutEngine.Line line : page.allLines()) {
        List<LayoutEngine.Line> list = lines.get(line.paragraphId);
        if (list == null) lines.put(line.paragraphId, list = new ArrayList<LayoutEngine.Line>());
        list.add(line);
      }
    final Session.FieldResolver sequences = SequenceFields.resolver(doc);
    final Session.FieldResolver formulas = TableFormulas.resolver(doc);
    return (paragraph, offset, field) -> {
      int number = 0;
      int displayOffset = offset + (notePrefixes.containsKey(paragraph) ? notePrefixes.get(paragraph) : 0);
      List<LayoutEngine.Line> ls = lines.get(paragraph);
      if (ls != null)
        for (LayoutEngine.Line line : ls)
          if (displayOffset >= line.start && displayOffset < line.end) {
            number = layout.pages.get(line.page).number;
            break;
          }
      InlineField sequence = sequences.resolve(paragraph, offset, formulas.resolve(paragraph, offset, field));
      InlineField reference = CrossReferences.resolve(doc, layout, lines, paragraph, offset, sequence);
      return resolve(reference, doc.title, number, layout.pages.size(), values);
    };
  }

  public static InlineField resolve(
      InlineField field, String title, int page, int count, Map<String, String> values) {
    if (field.locked) return field;
    List<String> tokens = tokens(field.instruction);
    if (tokens.isEmpty()) return field;
    String kind = tokens.get(0).toUpperCase(), result = null;
    if ("PAGE".equals(kind) && page > 0) result = "" + page;
    else if ("NUMPAGES".equals(kind)) result = "" + count;
    else if ("TITLE".equals(kind)) result = title;
    else if ("MERGEFIELD".equals(kind) && tokens.size() > 1) result = values.get(tokens.get(1));
    else if ("DOCPROPERTY".equals(kind) && tokens.size() > 1)
      result = "Title".equalsIgnoreCase(tokens.get(1)) ? title : values.get(tokens.get(1));
    else result = values.get(kind);
    if (result == null) return field;
    final String numeric = result;
    for (int i = 1; i + 1 < tokens.size(); i++)
      if ("\\*".equals(tokens.get(i))) {
        String format = tokens.get(++i);
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
