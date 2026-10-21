package com.codenameone.wordprocessor;

import java.util.*;

/**
 * Shared mail merge engine: preview retains atomic fields; finish creates independent rich copies.
 */
public final class MailMerge {
  private MailMerge() {}

  public static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  public static String placeholder(String instruction) {
    var code = new FieldCode(instruction);
    if ("MERGEFIELD".equals(code.name))
      return code.arguments.isEmpty() ? "«Field»" : "«" + code.arguments.get(0) + "»";
    if ("ADDRESSBLOCK".equals(code.name)) return "«AddressBlock»";
    if ("GREETINGLINE".equals(code.name)) return "«GreetingLine»";
    if (Arrays.asList("NEXT", "NEXTIF", "SKIPIF", "MERGEREC").contains(code.name)) return "";
    if ("IF".equals(code.name) && instruction.toUpperCase().indexOf("MERGEFIELD") >= 0) return "";
    return null;
  }

  public static void recipients(Session s, MergeData value) {
    s.transact(
        x -> {
          var old = x.mergeData();
          x.mergeData(
              value.options(old.kind, old.preview && !value.rows.isEmpty(), old.highlight, 0));
          refresh(x);
        });
  }

  public static void preview(Session s, boolean enabled, int record) {
    if (enabled && s.mergeData().rows.isEmpty())
      throw new IllegalArgumentException("Select recipients first");
    s.transact(
        x -> {
          var old = x.mergeData();
          x.mergeData(old.options(old.kind, enabled, old.highlight, record));
          refresh(x);
        });
  }

  public static void insert(Session s, String instruction) {
    String result = placeholder(instruction);
    if (result == null) throw new IllegalArgumentException("Unsupported merge field");
    s.transact(
        x -> {
          x.insertField(new InlineField(instruction, result, false, false));
          refresh(x);
        });
  }

  public static void refresh(Session s) {
    s.transact(
        x -> {
          int cell = x.cellBlock();
          int[] path = x.cellPath();
          var selection = x.selection();
          var transform =
              new Transform(x.document(), x.mergeData(), false, x.document().nextId, "");
          var value = transform.document(x.mergeData().record, x.mergeData().rows.size());
          if (value != x.document()) {
            x.replaceDocument(value);
            if (cell >= 0) x.enterCellPath(path);
            x.selectRange(
                selection.anchorBlock, selection.anchor, selection.block, selection.caret);
          }
        });
  }

  private static final class Cursor {
    int record;
    final int end;
    boolean skip;

    Cursor(int record, int end) {
      this.record = record;
      this.end = end;
    }
  }

  private static String resolve(MergeData data, String instruction, Cursor cursor) {
    String placeholder = placeholder(instruction);
    if (placeholder == null) return null;
    var code = new FieldCode(instruction);
    int record = cursor.record < cursor.end ? cursor.record : data.rows.size();
    if ("MERGEFIELD".equals(code.name)) {
      var value = data.value(code.arguments.isEmpty() ? "" : code.arguments.get(0), record);
      if (!value.isEmpty()) {
        if (code.switches.containsKey("b")) value = code.switches.get("b") + value;
        if (code.switches.containsKey("f")) value += code.switches.get("f");
      }
      return value;
    }
    if ("ADDRESSBLOCK".equals(code.name)) return data.address(record);
    if ("GREETINGLINE".equals(code.name)) return data.greeting(record);
    if ("MERGEREC".equals(code.name)) return record < data.rows.size() ? "" + (record + 1) : "";
    if ("NEXT".equals(code.name)) {
      cursor.record = Math.min(cursor.end, cursor.record + 1);
      return "";
    }
    // The supported conditional grammar is deliberately closed; malformed rules never execute.
    var tokens = FieldCode.tokens(instruction);
    if (tokens.size() < 7
        || !"{".equals(tokens.get(1))
        || !"MERGEFIELD".equalsIgnoreCase(tokens.get(2))
        || !"}".equals(tokens.get(4)))
      throw new IllegalArgumentException("Unsupported mail merge rule: " + instruction);
    String actual = data.value(tokens.get(3), record), expected = tokens.get(6);
    int comparison;
    try {
      double a = Double.parseDouble(actual), b = Double.parseDouble(expected);
      comparison =
          Double.isNaN(a) || Double.isNaN(b)
              ? actual.compareTo(expected)
              : a < b ? -1 : a > b ? 1 : 0;
    } catch (NumberFormatException ex) {
      comparison = actual.compareTo(expected);
    }
    var op = tokens.get(5);
    boolean matched;
    if ("=".equals(op)) matched = comparison == 0;
    else if ("<>".equals(op)) matched = comparison != 0;
    else if ("<".equals(op)) matched = comparison < 0;
    else if (">".equals(op)) matched = comparison > 0;
    else if ("<=".equals(op)) matched = comparison <= 0;
    else if (">=".equals(op)) matched = comparison >= 0;
    else throw new IllegalArgumentException("Unknown merge comparison: " + op);
    if ("IF".equals(code.name)) {
      if (tokens.size() != 9) throw new IllegalArgumentException("IF needs true and false text");
      return tokens.get(matched ? 7 : 8);
    }
    if (tokens.size() != 7) throw new IllegalArgumentException("Unexpected merge rule arguments");
    if (matched && "SKIPIF".equals(code.name)) cursor.skip = true;
    if (matched && "NEXTIF".equals(code.name))
      cursor.record = Math.min(cursor.end, cursor.record + 1);
    return "";
  }

  public static String rule(
      String name, String field, String op, String value, String yes, String no) {
    name = name.toUpperCase();
    if ("NEXT".equals(name) || "MERGEREC".equals(name)) return name;
    if (!Arrays.asList("IF", "NEXTIF", "SKIPIF").contains(name)
        || !Arrays.asList("=", "<>", "<", ">", "<=", ">=").contains(op))
      throw new IllegalArgumentException("Unknown merge rule or comparison");
    return name
        + " { MERGEFIELD "
        + quote(field)
        + " } "
        + op
        + " "
        + quote(value)
        + ("IF".equals(name) ? " " + quote(yes) + " " + quote(no) : "");
  }

  public static List<String> errors(Document document, MergeData data) {
    var unknown = new LinkedHashSet<String>();
    var scan =
        new Transform(document, data, false, document.nextId, "") {
          @Override
          String field(InlineField field, Cursor cursor) {
            var code = new FieldCode(field.instruction);
            if ("MERGEFIELD".equals(code.name)
                && !code.arguments.isEmpty()
                && data.column(code.arguments.get(0)) < 0) unknown.add(code.arguments.get(0));
            var t = FieldCode.tokens(field.instruction);
            if (Arrays.asList("IF", "NEXTIF", "SKIPIF").contains(code.name)
                && t.size() > 3
                && "MERGEFIELD".equalsIgnoreCase(t.get(2))
                && data.column(t.get(3)) < 0) unknown.add(t.get(3));
            return null;
          }
        };
    scan.document(data.record, data.rows.size());
    return new ArrayList<>(unknown);
  }

  public static int finish(Session s, int from, int to) {
    var data = s.mergeData();
    var template = s.document();
    if (from < 1 || to < from || to > data.rows.size())
      throw new IllegalArgumentException("Choose a valid recipient range");
    var missing = errors(template, data);
    if (!missing.isEmpty())
      throw new IllegalArgumentException("Unknown recipient fields: " + missing);
    var blocks = new Sequence<Block>();
    var sections = new LinkedHashMap<String, Section>();
    var comments = new ArrayList<ReviewComment>();
    var bookmarks = new LinkedHashMap<String, Bookmark>();
    long next = template.nextId, textSize = 0;
    int count = 0;
    Section last = null;
    for (int record = from - 1; record < to; ) {
      var transform = new Transform(template, data, true, next, "m" + (record + 1) + "_");
      var copy = transform.document(record, to);
      next = copy.nextId;
      record = Math.max(record + 1, transform.consumed + 1);
      if (transform.skipped) continue;
      if ((long) blocks.size() + copy.blocks.size() > 200000
          || (textSize += transform.textSize) > 16000000)
        throw new IllegalArgumentException(
            "Merged output is too large; choose a smaller recipient range");
      if (last != null) {
        if (!(blocks.get(blocks.size() - 1) instanceof Paragraph))
          blocks = blocks.append(new Paragraph("p" + next++, new RichText()));
        sections.put(blocks.get(blocks.size() - 1).id(), last);
      }
      var copySections = new LinkedHashMap<String, Section>(copy.sections);
      var first =
          copy.sectionRanges()
              .get(0)
              .raw
              .start("directory".equals(data.kind) ? "continuous" : "nextPage");
      if (copySections.isEmpty()) last = first;
      else {
        copySections.put(copy.blocks.get(copy.sectionRanges().get(0).last).id(), first);
        last = copy.lastSection;
      }
      for (Block b : copy.blocks) blocks = blocks.append(b);
      sections.putAll(copySections);
      comments.addAll(copy.comments);
      bookmarks.putAll(copy.bookmarks);
      count++;
    }
    if (blocks.size() == 0) blocks = blocks.append(new Paragraph("p" + next++, new RichText()));
    var output =
        new Document(blocks, template.page, template.title + " — Merged", next, comments)
            .styles(template.styles)
            .notes(template.notes)
            .bibliography(template.bibliography)
            .sections(sections, last == null ? template.lastSection : last)
            .bookmarks(bookmarks);
    s.transact(
        x -> {
          x.replaceDocument(output);
          x.mergeData(data.options(data.kind, false, data.highlight, data.record));
        });
    return count;
  }

  private static class Transform {
    final Document source;
    final MergeData data;
    final boolean flatten;
    final String prefix;
    final Map<String, String> ids = new HashMap<String, String>(),
        names = new HashMap<String, String>();
    final Map<String, List<int[]>> shifts = new HashMap<>();
    long next, textSize;
    int consumed;
    boolean skipped;

    Transform(Document source, MergeData data, boolean flatten, long next, String prefix) {
      this.source = source;
      this.data = data;
      this.flatten = flatten;
      this.next = next;
      this.prefix = prefix;
      int n = 0;
      for (String name : source.bookmarks.keySet())
        names.put(name, flatten ? prefix + "b" + (++n) : name);
    }

    String id(String original, String type) {
      if (!flatten) return original;
      if (!ids.containsKey(original)) ids.put(original, type + next++);
      return ids.get(original);
    }

    String field(InlineField field, Cursor cursor) {
      if (field.locked) return null;
      return flatten || data.preview
          ? resolve(data, field.instruction, cursor)
          : placeholder(field.instruction);
    }

    int offset(String id, int offset) {
      int delta = 0;
      var changes = shifts.get(id);
      if (changes != null) for (int[] change : changes) if (change[0] < offset) delta += change[1];
      return offset + delta;
    }

    TextStyle style(TextStyle original) {
      if (flatten && original.link.startsWith("#") && names.containsKey(original.link.substring(1)))
        return original.with("link", "#" + names.get(original.link.substring(1)));
      return original;
    }

    InlineField reference(InlineField field) {
      if (!flatten) return field;
      var code = new FieldCode(field.instruction);
      if (!Arrays.asList("REF", "PAGEREF", "NOTEREF").contains(code.name)
          || code.arguments.size() != 1
          || !names.containsKey(code.arguments.get(0))) return field;
      var instruction =
          new StringBuilder(code.name).append(' ').append(quote(names.get(code.arguments.get(0))));
      for (Map.Entry<String, String> entry : code.switches.entrySet()) {
        instruction.append(" \\").append(entry.getKey());
        if (!entry.getValue().isEmpty()) instruction.append(' ').append(quote(entry.getValue()));
      }
      return new InlineField(instruction.toString(), field.result, field.locked, field.showCode);
    }

    Paragraph paragraph(Paragraph p, Cursor cursor) {
      if (!flatten) {
        var text =
            p.text.mapFields(
                (offset, f) -> {
                  var value = field(f, cursor);
                  return value == null ? f : f.result(value);
                });
        text =
            text.mapNotes(
                (offset, note) -> {
                  Sequence<Paragraph> story = note.paragraphs;
                  var noteCursor = new Cursor(cursor.record, cursor.end);
                  for (int i = 0; i < story.size(); i++) {
                    Paragraph original = story.get(i), value = paragraph(original, noteCursor);
                    if (value != original) story = story.set(i, value);
                  }
                  return note.paragraphs(story);
                });
        return text == p.text ? p : p.text(text);
      }
      final RichText[] text = {new RichText()};
      final var changes = new ArrayList<int[]>();
      p.text.visit(
          new RichText.Visitor() {
            public void run(int offset, String value, TextStyle s) {
              text[0] = text[0].concat(RichText.of(value, style(s)));
            }

            public void field(int offset, InlineField f, TextStyle s) {
              var value = fieldValue(f, cursor);
              if (flatten && value != null) {
                text[0] = text[0].concat(RichText.of(value, style(s)));
                changes.add(new int[] {offset, value.length() - 1});
              } else
                text[0] =
                    text[0].concat(
                        RichText.field(value == null ? reference(f) : f.result(value), style(s)));
            }

            public void note(int offset, Note note, TextStyle s) {
              var story = new Sequence<Paragraph>();
              var noteCursor = new Cursor(cursor.record, cursor.end);
              for (Paragraph p : note.paragraphs) story = story.append(paragraph(p, noteCursor));
              text[0] =
                  text[0].concat(
                      RichText.note(
                          new Note(id(note.id, "n"), note.kind, note.mark, note.label, story),
                          style(s)));
            }
          });
      shifts.put(p.id, changes);
      textSize += text[0].length();
      return p.copy(id(p.id, "p"), text[0]);
    }

    private String fieldValue(InlineField f, Cursor cursor) {
      if (flatten && cursor.skip) return placeholder(f.instruction) == null ? null : "";
      return field(f, cursor);
    }

    PageFurniture furniture(PageFurniture value, int record, int end) {
      var result = value;
      for (String key : PageFurniture.KEYS)
        if (value.stories.containsKey(key)) {
          var cursor = new Cursor(record, end);
          // Fields in header tables merge too, in reading order.
          var story = PageFurniture.mapBlocks(value.blocks(key), p -> paragraph(p, cursor));
          if (story != value.blocks(key)) result = result.blocks(key, story);
        }
      return result;
    }

    Document previewDocument(int record, int end) {
      var cursor = new Cursor(record, end);
      Sequence<Block> blocks = source.blocks;
      for (int i = 0; i < blocks.size(); i++) {
        Block original = blocks.get(i), updated = original;
        if (original instanceof Paragraph paragraph) updated = paragraph(paragraph, cursor);
        else if (original instanceof Table table) {
          for (int r = 0; r < table.rows(); r++)
            for (int c = 0; c < table.columns(); c++) {
              if (table.covered(r, c)) continue;
              Sequence<Paragraph> cell = table.cell(r, c), changed = cell;
              for (int n = 0; n < cell.size(); n++) {
                Paragraph old = cell.get(n), value = paragraph(old, cursor);
                if (old != value) changed = changed.set(n, value);
              }
              if (changed != cell) table = table.setCell(r, c, changed);
            }
          updated = table;
        }
        if (original != updated) blocks = blocks.set(i, updated);
      }
      Document result = blocks == source.blocks ? source : source.blocks(blocks, source.nextId);
      var sections = new LinkedHashMap<String, Section>(source.sections);
      boolean changed = false;
      for (Map.Entry<String, Section> entry : source.sections.entrySet()) {
        var value = furniture(entry.getValue().furniture, record, end);
        if (value != entry.getValue().furniture) {
          sections.put(entry.getKey(), entry.getValue().furniture(value));
          changed = true;
        }
      }
      var value = furniture(source.furniture, record, end);
      return changed || value != source.furniture
          ? result.sections(sections, source.lastSection.furniture(value))
          : result;
    }

    Document document(int record, int end) {
      if (!flatten) return previewDocument(record, end);
      var cursor = new Cursor(record, end);
      var blocks = new Sequence<Block>();
      for (Block b : source.blocks) {
        if (b instanceof Paragraph paragraph) blocks = blocks.append(paragraph(paragraph, cursor));
        else if (b instanceof Picture picture)
          blocks = blocks.append(flatten ? picture.copy(id(b.id(), "i")) : b);
        else if (b instanceof Table t) {
          var cells = new ArrayList<List<Sequence<Paragraph>>>();
          for (int r = 0; r < t.rows(); r++) {
            var row = new ArrayList<Sequence<Paragraph>>();
            for (int c = 0; c < t.columns(); c++) {
              var cell = new Sequence<Paragraph>();
              for (Paragraph p : t.cell(r, c))
                cell =
                    cell.append(paragraph(p, t.covered(r, c) ? new Cursor(record, end) : cursor));
              row.add(cell);
            }
            cells.add(row);
          }
          blocks = blocks.append(new Table(id(t.id(), "t"), cells, t.merges, t.format));
        }
      }
      consumed = cursor.record;
      skipped = cursor.skip;
      var sections = new LinkedHashMap<String, Section>();
      var last = source.lastSection;
      for (Section.Range range : source.sectionRanges()) {
        // Resolve inherited stories before cloning so later records cannot inherit previous
        // recipients.
        Section section = flatten ? range.resolved : range.raw;
        section = section.furniture(furniture(section.furniture, record, end));
        String old = range.last < 0 ? "" : source.blocks.get(range.last).id();
        if (source.sections.containsKey(old)) sections.put(id(old, "p"), section);
        else last = section;
      }
      var comments = new ArrayList<ReviewComment>();
      for (ReviewComment c : source.comments)
        comments.add(
            new ReviewComment(
                id(c.id, "c"),
                c.author,
                c.text,
                id(c.startId, "p"),
                offset(c.startId, c.start),
                id(c.endId, "p"),
                offset(c.endId, c.end),
                c.resolved,
                c.replies,
                c.date));
      var marks = new LinkedHashMap<String, Bookmark>();
      for (Bookmark b : source.bookmarks.values()) {
        var name = names.get(b.name);
        marks.put(
            name,
            new Bookmark(
                name,
                id(b.startId, "p"),
                offset(b.startId, b.start),
                id(b.endId, "p"),
                offset(b.endId, b.end)));
      }
      return new Document(blocks, source.page, source.title, next, comments)
          .styles(source.styles)
          .notes(source.notes)
          .trackChanges(source.trackChanges)
          .bibliography(source.bibliography)
          .sections(sections, last)
          .bookmarks(marks);
    }
  }
}
