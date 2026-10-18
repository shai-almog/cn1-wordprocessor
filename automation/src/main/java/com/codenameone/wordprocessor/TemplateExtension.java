package com.codenameone.wordprocessor;

import java.util.*;

/** Example compiled extension: substitute {{field}} values, retaining surrounding formatting. */
public final class TemplateExtension implements Commands.Extension {
  public void register(Commands commands) {
    commands.register(
        "template.fill",
        "Fill {{field}} placeholders in paragraphs and table cells",
        "values:object",
        true,
        (s, a) -> {
          Map<?, ?> values = (Map<?, ?>) a.get("values");
          for (Object key : values.keySet())
            if (!(key instanceof String) || !(values.get(key) instanceof String))
              throw new IllegalArgumentException("Template values must be strings");
          var d = s.document();
          Sequence<Block> blocks = d.blocks;
          for (int i = 0; i < blocks.size(); i++) {
            var b = blocks.get(i);
            if (b instanceof Paragraph paragraph) blocks = blocks.set(i, fill(paragraph, values));
            else if (b instanceof Table t) {
              for (int r = 0; r < t.rows(); r++)
                for (int c = 0; c < t.columns(); c++) {
                  var cell = t.cell(r, c);
                  for (int p = 0; p < cell.size(); p++)
                    cell = cell.set(p, fill(cell.get(p), values));
                  t = t.setCell(r, c, cell);
                }
              blocks = blocks.set(i, t);
            }
          }
          s.replaceDocument(d.blocks(blocks, d.nextId));
          return null;
        });
  }

  private Paragraph fill(Paragraph p, Map<?, ?> values) {
    var source = p.text.toString();
    var result = p.text;
    var ranges = new ArrayList<int[]>();
    var replacements = new ArrayList<String>();
    int offset = 0;
    while (true) {
      int a = source.indexOf("{{", offset);
      if (a < 0) break;
      int end = source.indexOf("}}", a + 2);
      if (end < 0) break;
      var key = source.substring(a + 2, end);
      if (values.containsKey(key)) {
        ranges.add(new int[] {a, end + 2});
        replacements.add((String) values.get(key));
      }
      offset = end + 2;
    }
    for (int i = ranges.size() - 1; i >= 0; i--) {
      var r = ranges.get(i);
      result = result.replace(r[0], r[1], RichText.of(replacements.get(i), result.styleAt(r[0])));
    }
    return p.text(result);
  }
}
