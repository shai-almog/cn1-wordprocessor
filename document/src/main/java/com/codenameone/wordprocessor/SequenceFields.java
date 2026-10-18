package com.codenameone.wordprocessor;

import java.util.*;

/** Body sequence and heading-reference fields evaluated in document order. */
public final class SequenceFields {
  private final Map<String, Integer> counters = new HashMap<>();
  private final Map<String, Integer> epochs = new HashMap<>();
  private final Map<String, Map<Integer, String>> results =
      new HashMap<>();
  private final int[] headings = new int[9];
  private final String[] headingNumbers = new String[9], headingText = new String[9];
  private final ListNumbers lists = new ListNumbers();

  private SequenceFields(Document document) {
    Arrays.fill(headingNumbers, "");
    Arrays.fill(headingText, "");
    for (Block block : document.blocks) {
      if (block instanceof Paragraph paragraph) paragraph(paragraph);
      else if (block instanceof Table table) {
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++)
            if (!table.covered(r, c)) for (Paragraph p : table.cell(r, c)) paragraph(p);
      }
    }
  }

  public static Session.FieldResolver resolver(Document document) {
    final var fields = new SequenceFields(document);
    return (paragraph, offset, field) -> {
      var values = fields.results.get(paragraph);
      String result = values == null ? null : values.get(offset);
      return result == null || field.locked ? field : field.result(result);
    };
  }

  private void paragraph(final Paragraph p) {
    var label = lists.next(p);
    int level = headingLevel(p.style);
    if (level > 0) {
      headings[level - 1]++;
      headingText[level - 1] = p.text.plainText().trim();
      headingNumbers[level - 1] =
          label.isEmpty() || "bullet".equals(p.list)
              ? "" + headings[level - 1]
              : label.substring(0, label.length() - 1);
    }
    p.text.visit(
        new RichText.Visitor() {
          public void run(int offset, String text, TextStyle style) {}

          public void field(int offset, InlineField field, TextStyle style) {
            if (field.locked || style.revision != null && !style.revision.inserted()) return;
            var code = new FieldCode(field.instruction);
            if (code.arguments.isEmpty()) return;
            String result = null;
            if ("SEQ".equals(code.name)) {
              var key = code.arguments.get(0);
              int n = counters.containsKey(key) ? counters.get(key) : 0;
              var restart = code.switches.get("s");
              if (restart != null) {
                int level = headingLevel(restart);
                if (level == 0) return;
                int epoch = headings[level - 1];
                if (!epochs.containsKey(key) || epochs.get(key) != epoch) {
                  n = 0;
                  epochs.put(key, epoch);
                }
              }
              var reset = code.switches.get("r");
              if (reset != null) {
                try {
                  n = Integer.parseInt(reset);
                  if (n < 0) return;
                } catch (NumberFormatException invalid) {
                  return;
                }
              } else if (!code.switches.containsKey("c")) n = n == Integer.MAX_VALUE ? n : n + 1;
              counters.put(key, n);
              result = code.switches.containsKey("h") ? "" : number(n, code.switches.get("*"));
            } else if ("STYLEREF".equals(code.name)) {
              int level = headingLevel(code.arguments.get(0));
              if (level > 0)
                result =
                    code.switches.containsKey("s") || code.switches.containsKey("n")
                        ? headingNumbers[level - 1]
                        : headingText[level - 1];
            }
            if (result != null) {
              var values = results.get(p.id);
              if (values == null) results.put(p.id, values = new HashMap<Integer, String>());
              values.put(offset, result);
            }
          }
        });
  }

  public static int headingLevel(String style) {
    String value = style.startsWith("Heading") ? style.substring(7).trim() : style;
    return value.length() == 1 && value.charAt(0) >= '1' && value.charAt(0) <= '9'
        ? value.charAt(0) - '0'
        : 0;
  }

  public static String number(int n, String format) {
    if (n == 0) return "0";
    return ListNumbers.format(
        n,
        "ROMAN".equals(format)
            ? "upperRoman"
            : "roman".equals(format)
                ? "lowerRoman"
                : "ALPHABETIC".equals(format)
                    ? "upperLetter"
                    : "alphabetic".equals(format) ? "lowerLetter" : "decimal");
  }
}
