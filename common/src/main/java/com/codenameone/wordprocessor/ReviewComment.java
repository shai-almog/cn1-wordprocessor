package com.codenameone.wordprocessor;

/** Immutable review comment anchored to a UTF-16 range in stable paragraph IDs. */
public final class ReviewComment {
  public final String id, author, text, startId, endId;
  public final int start, end;
  public final boolean resolved;
  public final java.util.List<Reply> replies;
  /** When the comment was written, ISO 8601 (Wordcraft Comment.date, w:date); "" when unknown. */
  public final String date;
  /** The author's initials (Wordcraft Comment.initials, w:initials); "" when unknown. */
  public final String initials;
  /**
   * The rich comment text (Wordcraft keeps each comment's text as its own document part): its
   * paragraphs with run formatting. Empty for a plain-text comment; {@link #text} is always the
   * plain text, one line per paragraph.
   */
  public final java.util.List<Paragraph> body;

  public static final class Reply {
    public final String author, text;
    /** ISO 8601 or "". */
    public final String date;
    /** w:initials of the reply author, "" when unknown. */
    public String initials = "";
    /**
     * A rich (formatted) reply body, empty for plain text: Wordcraft keeps a reply as a comment
     * with its own story, so its runs survive as the root comment's do.
     */
    public java.util.List<Paragraph> body = java.util.Collections.emptyList();

    /** This reply with the author's initials. */
    public Reply initials(String value) {
      var r = new Reply(author, text, date);
      r.initials = value == null ? "" : value;
      r.body = body;
      return r;
    }

    /** This reply with a rich body; its plain text becomes {@link #text}. */
    public Reply body(java.util.List<Paragraph> paragraphs) {
      var r = new Reply(author, plain(paragraphs), date);
      r.initials = initials;
      r.body = java.util.Collections.unmodifiableList(new java.util.ArrayList<Paragraph>(paragraphs));
      return r;
    }

    /** The reply's paragraphs: its rich body, else one plain paragraph per line. */
    public java.util.List<Paragraph> paragraphs() {
      return body.isEmpty() ? plainParagraphs("reply", text) : body;
    }

    public Reply(String author, String text) {
      this(author, text, "");
    }

    public Reply(String author, String text, String date) {
      if (author == null || text == null || author.trim().isEmpty() || text.trim().isEmpty())
        throw new IllegalArgumentException("Enter a reply author and text");
      this.author = author;
      this.text = text;
      this.date = date == null ? "" : date;
    }
  }

  /** The current UTC time as Word writes w:date (second precision, Z). */
  public static String now() {
    var c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
    c.setTime(new java.util.Date());
    return c.get(java.util.Calendar.YEAR) + "-" + two(c.get(java.util.Calendar.MONTH) + 1) + "-"
        + two(c.get(java.util.Calendar.DAY_OF_MONTH)) + "T" + two(c.get(java.util.Calendar.HOUR_OF_DAY))
        + ":" + two(c.get(java.util.Calendar.MINUTE)) + ":" + two(c.get(java.util.Calendar.SECOND)) + "Z";
  }

  private static String two(int v) {
    return v < 10 ? "0" + v : String.valueOf(v);
  }

  /** A date for display: "2024-03-05 14:07" from ISO 8601, "" when absent or malformed. */
  public static String displayDate(String iso) {
    if (iso == null || iso.length() < 10) return "";
    String day = iso.substring(0, 10);
    if (iso.length() >= 16 && iso.charAt(10) == 'T') return day + " " + iso.substring(11, 16);
    return day;
  }

  public ReviewComment(
      String id,
      String author,
      String text,
      String startId,
      int start,
      String endId,
      int end,
      boolean resolved) {
    this(
        id,
        author,
        text,
        startId,
        start,
        endId,
        end,
        resolved,
        java.util.Collections.<Reply>emptyList());
  }

  public ReviewComment(
      String id,
      String author,
      String text,
      String startId,
      int start,
      String endId,
      int end,
      boolean resolved,
      java.util.List<Reply> replies) {
    this(id, author, text, startId, start, endId, end, resolved, replies, "");
  }

  public ReviewComment(
      String id,
      String author,
      String text,
      String startId,
      int start,
      String endId,
      int end,
      boolean resolved,
      java.util.List<Reply> replies,
      String date) {
    this(id, author, text, startId, start, endId, end, resolved, replies, date, "",
        java.util.Collections.<Paragraph>emptyList());
  }

  public ReviewComment(
      String id,
      String author,
      String text,
      String startId,
      int start,
      String endId,
      int end,
      boolean resolved,
      java.util.List<Reply> replies,
      String date,
      String initials,
      java.util.List<Paragraph> body) {
    this.initials = initials == null ? "" : initials;
    this.body = body == null || body.isEmpty()
        ? java.util.Collections.<Paragraph>emptyList()
        : java.util.Collections.unmodifiableList(new java.util.ArrayList<Paragraph>(body));
    this.date = date == null ? "" : date;
    this.replies = java.util.Collections.unmodifiableList(new java.util.ArrayList<Reply>(replies));
    if (id == null
        || author == null
        || text == null
        || startId == null
        || endId == null
        || start < 0
        || end < 0) throw new IllegalArgumentException("Invalid comment");
    this.id = id;
    this.author = author;
    this.text = text;
    this.startId = startId;
    this.start = start;
    this.endId = endId;
    this.end = end;
    this.resolved = resolved;
  }

  public ReviewComment resolved(boolean value) {
    return new ReviewComment(id, author, text, startId, start, endId, end, value, replies, date, initials, body);
  }

  /**
   * Initials written for a new comment (Wordcraft Session::user_initials): the Initials option,
   * else the first letter of each word of the author's name.
   */
  public static String initialsOf(String author) {
    String set = AppOptions.get("userInitials");
    if (set != null && !set.trim().isEmpty()) return set.trim();
    var b = new StringBuilder();
    boolean start = true;
    for (int i = 0; i < author.length(); i++) {
      char ch = author.charAt(i);
      if (ch == ' ' || ch == '\t') start = true;
      else if (start) {
        b.append(ch);
        start = false;
      }
    }
    return b.toString();
  }

  /** This comment over another range, keeping its replies, date, initials and rich body. */
  public ReviewComment range(String first, int from, String last, int to) {
    return new ReviewComment(id, author, text, first, from, last, to, resolved, replies, date, initials, body);
  }

  /** This comment with the author's initials. */
  public ReviewComment initials(String value) {
    return new ReviewComment(id, author, text, startId, start, endId, end, resolved, replies, date, value, body);
  }

  /** This comment with a rich body; its plain text becomes {@link #text}. */
  public ReviewComment body(java.util.List<Paragraph> paragraphs) {
    return new ReviewComment(id, author, plain(paragraphs), startId, start, endId, end, resolved, replies, date,
        initials, paragraphs);
  }

  /** This comment with its replies replaced (rich reply bodies kept). */
  public ReviewComment replies(java.util.List<Reply> value) {
    return new ReviewComment(id, author, text, startId, start, endId, end, resolved, value, date, initials, body);
  }

  static String plain(java.util.List<Paragraph> paragraphs) {
    var plain = new StringBuilder();
    for (int i = 0; i < paragraphs.size(); i++) {
      if (i > 0) plain.append('\n');
      plain.append(paragraphs.get(i).text.plainText());
    }
    if (plain.toString().trim().isEmpty()) throw new IllegalArgumentException("Enter comment text");
    return plain.toString();
  }

  static java.util.List<Paragraph> plainParagraphs(String prefix, String text) {
    var out = new java.util.ArrayList<Paragraph>();
    String[] lines = TextUtil.split(text, '\n');
    for (int i = 0; i < lines.length; i++) out.add(new Paragraph(prefix + "-p" + i, RichText.of(lines[i])));
    return out;
  }

  /**
   * A rich body retyped as {@code value}: unchanged paragraphs keep their runs and a changed one
   * keeps the runs around the edit (the typed text takes the formatting where it was typed).
   * Wordcraft's comment editor rewrites the comment story only when the text changed (and then
   * as plain paragraphs); here the runs outside the edit are kept so a rich body is not flattened.
   */
  static java.util.List<Paragraph> retyped(java.util.List<Paragraph> body, String value) {
    String[] lines = TextUtil.split(value, '\n');
    var out = new java.util.ArrayList<Paragraph>();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (i >= body.size()) {
        var last = body.get(body.size() - 1);
        var style = last.text.length() == 0 ? TextStyle.DEFAULT : last.text.styleAt(last.text.length() - 1);
        out.add(last.copy(last.id + "-" + i, RichText.of(line, style)));
        continue;
      }
      var p = body.get(i);
      String old = p.text.plainText();
      if (old.equals(line)) {
        out.add(p);
        continue;
      }
      int a = 0, n = Math.min(old.length(), line.length());
      while (a < n && old.charAt(a) == line.charAt(a)) a++;
      int z = 0;
      while (z < n - a && old.charAt(old.length() - 1 - z) == line.charAt(line.length() - 1 - z)) z++;
      // plainText() shows a field (or note) as its display text while it is one RichText
      // position: map plain offsets to positions, widening the edit to whole atoms it touches.
      int[] at = new int[old.length()], atomStart = new int[old.length()], atomEnd = new int[old.length()];
      p.text.visit(
          new RichText.Visitor() {
            int plain;

            public void run(int offset, String text, TextStyle style) {
              for (int k = 0; k < text.length(); k++, plain++) {
                at[plain] = offset + k;
                atomStart[plain] = plain;
                atomEnd[plain] = plain + 1;
              }
            }

            public void field(int offset, InlineField field, TextStyle style) {
              atom(offset, field.display());
            }

            public void note(int offset, Note note, TextStyle style) {
              atom(offset, note.label);
            }

            private void atom(int offset, String shown) {
              int from = plain;
              for (int k = 0; k < shown.length(); k++, plain++) {
                at[plain] = offset;
                atomStart[plain] = from;
                atomEnd[plain] = from + shown.length();
              }
            }
          });
      int e = old.length() - z;
      if (a < old.length()) a = atomStart[a];
      if (e > 0 && e > a) e = atomEnd[e - 1];
      z = old.length() - e;
      int from = a < old.length() ? at[a] : p.text.length();
      int to = e == 0 ? 0 : e >= old.length() ? p.text.length() : at[e];
      if (to < from) to = from;
      var style = p.text.length() == 0 ? TextStyle.DEFAULT : p.text.styleAt(Math.min(p.text.length() - 1, Math.max(0, from - 1)));
      out.add(p.text(p.text.replace(from, to, RichText.of(line.substring(a, line.length() - z), style))));
    }
    return out;
  }

  /** The comment's paragraphs: its rich body, else one plain paragraph per line of text. */
  public java.util.List<Paragraph> paragraphs() {
    if (!body.isEmpty()) return body;
    return plainParagraphs(id, text);
  }

  public ReviewComment reply(String author, String text) {
    var next = reply(author, text, now());
    next.replies.get(next.replies.size() - 1).initials = initialsOf(author);
    return next;
  }

  public ReviewComment reply(String author, String text, String date) {
    var next = new java.util.ArrayList<Reply>(replies);
    next.add(new Reply(author, text, date));
    return new ReviewComment(id, this.author, this.text, startId, start, endId, end, false, next, this.date,
        initials, body);
  }

  /** This comment with its date replaced. */
  public ReviewComment dated(String value) {
    return new ReviewComment(id, author, text, startId, start, endId, end, resolved, replies, value, initials, body);
  }

  /** Replaces thread text or one reply without changing authors, anchors or resolution. */
  public ReviewComment edited(int replyIndex, String value) {
    if (value == null || value.trim().isEmpty())
      throw new IllegalArgumentException("Enter comment text");
    if (replyIndex < -1 || replyIndex >= replies.size())
      throw new IllegalArgumentException("Unknown reply");
    if (replyIndex == -1) {
      if (value.equals(text)) return this;
      // Retyping keeps the runs of a rich body around the edit.
      if (!body.isEmpty()) return body(retyped(body, value));
      return new ReviewComment(id, author, value, startId, start, endId, end, resolved, replies, date, initials, null);
    }
    var old = replies.get(replyIndex);
    if (value.equals(old.text)) return this;
    var next = new java.util.ArrayList<Reply>(replies);
    next.set(replyIndex, old.body.isEmpty()
        ? new Reply(old.author, value, old.date).initials(old.initials)
        : old.body(retyped(old.body, value)));
    return new ReviewComment(id, author, text, startId, start, endId, end, resolved, next, date, initials, body);
  }

  /**
   * A paragraph in the body, one owning table cell, or a footnote/endnote story ({@link #note}
   * set; block/row/column then locate the note's reference). Paragraph IDs survive row moves.
   */
  public static final class Anchor {
    public final int block, row, column, paragraph;
    public final Paragraph value;
    /** The note whose story holds the paragraph, or null. */
    public final Note note;
    /**
     * The header/footer story holding the paragraph ("section:key"), or null: as Wordcraft's
     * CommentStart/CommentEnd marks in any story, a range may sit in a header or footer
     * (block indexes that story's blocks; row/column a cell of a table in it).
     */
    public final String furniture;
    /** The blocks of {@link #furniture}'s story. */
    final Sequence<Block> furnitureBlocks;
    private Anchor(int block, int row, int column, int paragraph, Paragraph value) {
      this(block, row, column, paragraph, value, null);
    }
    private Anchor(int block, int row, int column, int paragraph, Paragraph value, Note note) {
      this(block, row, column, paragraph, value, note, null, null);
    }
    private Anchor(int block, int row, int column, int paragraph, Paragraph value, Note note,
        String furniture, Sequence<Block> furnitureBlocks) {
      this.block = block; this.row = row; this.column = column;
      this.paragraph = paragraph; this.value = value; this.note = note;
      this.furniture = furniture; this.furnitureBlocks = furnitureBlocks;
    }
    public boolean sameStory(Anchor other) {
      if (other == null) return false;
      if (furniture != null || other.furniture != null)
        return furniture != null && furniture.equals(other.furniture)
            && (row < 0 ? other.row < 0 : block == other.block && row == other.row && column == other.column);
      if (note != null || other.note != null)
        return note != null && other.note != null && note.id.equals(other.note.id);
      return row < 0 ? other.row < 0
          : block == other.block && row == other.row && column == other.column;
    }
    /**
     * Whether a range from this paragraph to other can exist: the same note story, or both in
     * the body stream (top-level paragraphs and table cells, as Wordcraft's body story holds
     * cells, so a range may run from the body into or across a table's cells).
     */
    public boolean sameFlow(Anchor other) {
      if (other == null) return false;
      if (furniture != null || other.furniture != null)
        return furniture != null && furniture.equals(other.furniture);
      if (note != null || other.note != null) return sameStory(other);
      return true;
    }
    /** Document order within one flow: block, then row, cell and paragraph. */
    public int compareTo(Anchor o) {
      if (block != o.block) return block < o.block ? -1 : 1;
      if (row != o.row) return row < o.row ? -1 : 1;
      if (column != o.column) return column < o.column ? -1 : 1;
      return Integer.compare(paragraph, o.paragraph);
    }
    Document story(Document root) {
      var paragraphs = new Sequence<Block>();
      if (note != null) {
        for (Paragraph p : note.paragraphs) paragraphs = paragraphs.append(p);
        return new Document(paragraphs, root.page, root.title, root.nextId);
      }
      if (furniture != null) {
        if (row < 0) {
          for (Block b : furnitureBlocks) if (b instanceof Paragraph) paragraphs = paragraphs.append(b);
          return new Document(paragraphs, root.page, root.title, root.nextId);
        }
        for (Paragraph p : ((Table) furnitureBlocks.get(block)).cell(row, column))
          paragraphs = paragraphs.append(p);
        return new Document(paragraphs, root.page, root.title, root.nextId);
      }
      if (row < 0) return root;
      for (Paragraph p : ((Table) root.blocks.get(block)).cell(row, column))
        paragraphs = paragraphs.append(p);
      return new Document(paragraphs, root.page, root.title, root.nextId);
    }
  }

  public static Anchor locate(Document document, String id) {
    return id == null ? null : scan(document, id, null);
  }

  /** One index per immutable root lets painting resolve all anchors without rescanning tables. */
  public static java.util.Map<String, Anchor> index(Document document) {
    var result = new java.util.HashMap<String, Anchor>();
    scan(document, null, result);
    return result;
  }

  private static Anchor scan(Document document, String id, java.util.Map<String, Anchor> result) {
    var found = scanBody(document, id, result);
    if (found != null) return found;
    // Header and footer stories: the document's own and each section's.
    var seen = new java.util.HashSet<PageFurniture>();
    int section = 0;
    var all = new java.util.ArrayList<PageFurniture>();
    all.add(document.furniture);
    for (Section s : document.sections.values()) all.add(s.furniture);
    for (PageFurniture f : all) {
      section++;
      if (f == null || !seen.add(f)) continue;
      for (java.util.Map.Entry<String, Sequence<Block>> e : f.stories.entrySet()) {
        found = scanFurniture(section + ":" + e.getKey(), e.getValue(), id, result);
        if (found != null) return found;
      }
    }
    // Text box stories (Wordcraft's text box parts), each a story of its own.
    for (Picture box : TextBoxes.all(document)) {
      found = scanFurniture(TEXT_BOX + box.id(), box.drawing.story, id, result);
      if (found != null) return found;
    }
    // Group member stories (Wordcraft's member text box parts), keyed by member id.
    var groups = new java.util.ArrayList<Picture>();
    TextBoxes.map(document, p -> {
      if (p.drawing != null && !p.drawing.storyMembers().isEmpty()) groups.add(p);
      return p;
    });
    for (Picture g : groups)
      for (int k : g.drawing.storyMembers()) {
        found = scanFurniture(TEXT_BOX + TextBoxes.memberId(g.id(), k), g.drawing.memberStory(k), id, result);
        if (found != null) return found;
      }
    return null;
  }

  /** The anchor story key prefix of a text box story; the picture id follows. */
  public static final String TEXT_BOX = "textbox:";

  /** The text box picture id of an anchor in a text box story, or null. */
  public static String textBox(Anchor a) {
    return a == null || a.furniture == null || !a.furniture.startsWith(TEXT_BOX)
        ? null : a.furniture.substring(TEXT_BOX.length());
  }

  private static Anchor scanFurniture(
      String key, Sequence<Block> blocks, String id, java.util.Map<String, Anchor> result) {
    int paragraphs = 0;
    for (int b = 0; b < blocks.size(); b++) {
      var block = blocks.get(b);
      if (block instanceof Paragraph p) {
        if (result != null || p.id.equals(id)) {
          var value = new Anchor(b, -1, -1, paragraphs, p, null, key, blocks);
          if (result == null) return value;
          result.put(p.id, value);
        }
        paragraphs++;
      } else if (block instanceof Table table) {
        for (int row = 0; row < table.rows(); row++)
          for (int column = 0; column < table.columns(); column++) {
            if (table.covered(row, column)) continue;
            int index = 0;
            for (Paragraph p : table.cell(row, column)) {
              if (result != null || p.id.equals(id)) {
                var value = new Anchor(b, row, column, index, p, null, key, blocks);
                if (result == null) return value;
                result.put(p.id, value);
              }
              index++;
            }
          }
      }
    }
    return null;
  }

  private static Anchor scanBody(Document document, String id, java.util.Map<String, Anchor> result) {
    for (int b = 0; b < document.blocks.size(); b++) {
      var block = document.blocks.get(b);
      if (block instanceof Paragraph paragraph) {
        if (result != null || block.id().equals(id)) {
          var value = new Anchor(b, -1, -1, b, paragraph);
          if (result == null) return value;
          result.put(block.id(), value);
        }
        var inNote = notes(paragraph, b, -1, -1, id, result);
        if (inNote != null) return inNote;
      }
      if (block instanceof Table table) {
        for (int row = 0; row < table.rows(); row++)
          for (int column = 0; column < table.columns(); column++) {
            if (table.covered(row, column)) continue;
            int index = 0;
            for (Paragraph p : table.cell(row, column)) {
              if (result != null || p.id.equals(id)) {
                var value = new Anchor(b, row, column, index, p);
                if (result == null) return value;
                result.put(p.id, value);
              }
              var inNote = notes(p, b, row, column, id, result);
              if (inNote != null) return inNote;
              index++;
            }
          }
      }
    }
    return null;
  }

  private static Anchor notes(
      Paragraph host, int b, int row, int column, String id, java.util.Map<String, Anchor> result) {
    if (!host.text.hasNotes()) return null;
    final Anchor[] found = {null};
    host.text.mapNotes(
        (offset, note) -> {
          int index = 0;
          for (Paragraph p : note.paragraphs) {
            if (found[0] == null && (result != null || p.id.equals(id))) {
              var value = new Anchor(b, row, column, index, p, note);
              if (result == null) found[0] = value;
              else result.put(p.id, value);
            }
            index++;
          }
          return note;
        });
    return found[0];
  }

  /**
   * Ranges may span paragraphs of the body stream (from body text into or across table cells,
   * as Wordcraft's body story orders cells) or of one footnote/endnote story.
   */
  public static boolean validRange(Document document, String first, int start, String last, int end) {
    Anchor a = locate(document, first), z = locate(document, last);
    if (a == null || !a.sameFlow(z) || start < 0 || end < 0) return false;
    int order = a.sameStory(z) ? Integer.compare(a.paragraph, z.paragraph) : a.compareTo(z);
    return order <= 0 && start <= a.value.text.length()
        && end <= z.value.text.length() && (order != 0 || start <= end);
  }

  public static int block(Document d, String id) {
    for (int i = 0; i < d.blocks.size(); i++) if (d.blocks.get(i).id().equals(id)) return i;
    return -1;
  }

  public static java.util.List<ReviewComment> reanchor(Document before, Document after) {
    if (before.comments.isEmpty() || before.blocks == after.blocks) return before.comments;
    DocumentText old = new DocumentText(before), next = new DocumentText(after);
    if (old.text.equals(next.text) && sameTables(before, after)) {
      boolean valid = true;
      for (ReviewComment c : before.comments)
        if (!validRange(after, c.startId, c.start, c.endId, c.end)) valid = false;
      if (valid) return before.comments;
    }
    return move(before, after, new RangeMapping(before, after, old, next));
  }

  public static java.util.List<ReviewComment> reanchor(
      Document before, Document after, Selection replaced, Selection insertedEnd) {
    return move(before, after, new RangeMapping(before, after, replaced, insertedEnd));
  }

  private static boolean sameTables(Document before, Document after) {
    for (Block block : before.blocks) if (block instanceof Table) {
      int index = block(after, block.id());
      if (index < 0 || after.blocks.get(index) != block) return false;
    }
    return true;
  }

  private static ReviewComment moveCell(Document before, Document after, ReviewComment comment) {
    Anchor old = locate(before, comment.startId);
    if (old == null || old.row < 0) return null;
    var source = old.story(before);
    Anchor target = locate(after, comment.startId);
    if (target == null) target = locate(after, comment.endId);
    // An endpoint may have been removed by a paragraph join. Another stable paragraph
    // in the same cell identifies the surviving story; removing the whole cell drops it.
    if (target == null) for (Block p : source.blocks) {
      target = locate(after, p.id());
      if (target != null) break;
    }
    if (target == null || target.row < 0) return null;
    var destination = target.story(after);
    // Preserve unchanged anchors when cells are merged/reordered around them.
    Anchor first = locate(before, comment.startId), last = locate(before, comment.endId);
    Anchor nextFirst = locate(after, comment.startId), nextLast = locate(after, comment.endId);
    if (first != null && last != null && nextFirst != null && nextFirst.sameStory(nextLast)
        && first.value.text == nextFirst.value.text && last.value.text == nextLast.value.text
        && validRange(after, comment.startId, comment.start, comment.endId, comment.end)) return comment;
    var range = new RangeMapping(source, destination).move(
        comment.startId, comment.start, comment.endId, comment.end, true);
    return range == null ? null : relocated(comment, destination, range);
  }

  private static ReviewComment relocated(ReviewComment comment, Document document, int[] range) {
    return comment.range(
        document.blocks.get(range[0]).id(), range[1], document.blocks.get(range[2]).id(), range[3]);
  }

  private static java.util.List<ReviewComment> move(
      Document before, Document after, RangeMapping mapping) {
    var result = new java.util.ArrayList<ReviewComment>();
    for (ReviewComment c : before.comments) {
      Anchor first = locate(before, c.startId), last = locate(before, c.endId);
      if (first == null || last == null) continue;
      if (first.furniture != null) {
        // A header/footer range is edited with its own story; body edits leave it alone.
        Anchor a = locate(after, c.startId), z = locate(after, c.endId);
        if (a == null || z == null) continue;
        int start = Math.min(c.start, a.value.text.length()), end = Math.min(c.end, z.value.text.length());
        if (validRange(after, c.startId, start, c.endId, end))
          result.add(start == c.start && end == c.end ? c : c.range(c.startId, start, c.endId, end));
        continue;
      }
      if (first.note != null || !first.sameStory(last)) {
        ReviewComment moved = moveAcross(after, mapping, c, first, last);
        if (moved != null) result.add(moved);
      } else if (first.row >= 0) {
        ReviewComment moved = moveCell(before, after, c);
        if (moved != null) result.add(moved);
      } else {
        var range = mapping.move(c.startId, c.start, c.endId, c.end, true);
        if (range != null) result.add(relocated(c, after, range));
      }
    }
    return result;
  }

  /**
   * A note-story range, or one spanning stories of the body stream (body text and cells): its
   * top-level endpoints follow the edit; endpoints in cells or notes (edited in their own
   * story view) keep their paragraph and are clamped to it. Dropped when a paragraph is gone.
   */
  private static ReviewComment moveAcross(
      Document after, RangeMapping mapping, ReviewComment c, Anchor first, Anchor last) {
    String startId = c.startId, endId = c.endId;
    int start = c.start, end = c.end;
    if (first.note == null && first.row < 0) {
      int[] at = mapping.move(c.startId, c.start, c.startId, c.start, false);
      if (at == null) return null;
      startId = after.blocks.get(at[0]).id();
      start = at[1];
    }
    if (last.note == null && last.row < 0) {
      int[] at = mapping.move(c.endId, c.end, c.endId, c.end, false);
      if (at == null) return null;
      endId = after.blocks.get(at[0]).id();
      end = at[1];
    }
    Anchor a = locate(after, startId), z = locate(after, endId);
    if (a == null || z == null) return null;
    start = Math.min(start, a.value.text.length());
    end = Math.min(end, z.value.text.length());
    if (!validRange(after, startId, start, endId, end)) return null;
    return c.range(startId, start, endId, end);
  }
}
