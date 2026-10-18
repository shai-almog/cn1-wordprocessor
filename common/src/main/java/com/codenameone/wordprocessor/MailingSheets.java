package com.codenameone.wordprocessor;

import java.util.*;

/** Physical envelope and label-sheet geometry, in points, shared by screen and print. */
public final class MailingSheets {
  private MailingSheets() {}

  public static final String[] ENVELOPES = {
    "Envelope #10",
    "Envelope #9",
    "Envelope Monarch",
    "Envelope DL",
    "Envelope C4",
    "Envelope C5",
    "Envelope C6",
    "Envelope B5"
  };
  private static final float[][] SIZES = {
    {684, 297},
    {639, 279},
    {540, 279},
    {623.6f, 311.8f},
    {918.4f, 649.1f},
    {649.1f, 459.2f},
    {459.2f, 323.1f},
    {708.7f, 498.9f}
  };
  public static final String[] PRODUCTS = {
    "letter-3x10",
    "letter-2x10",
    "letter-2x7",
    "letter-2x5",
    "letter-4x20",
    "a4-3x7",
    "a4-2x7",
    "a4-3x8",
    "a4-2x4"
  };
  // columns, rows, label width/height, top/side margins, horizontal/vertical pitch.
  private static final float MM = 72f / 25.4f;
  private static final float[][] LABELS = {
    {3, 10, 189, 72, 36, 13.5f, 198, 72},
    {2, 10, 288, 72, 36, 11.25f, 301.5f, 72},
    {2, 7, 288, 96, 60, 11.25f, 301.5f, 96},
    {2, 5, 288, 144, 36, 11.25f, 301.5f, 144},
    {4, 20, 126, 36, 36, 21.6f, 147.6f, 36},
    {3, 7, 63.5f * MM, 38.1f * MM, 15.15f * MM, 7.21f * MM, 66.04f * MM, 38.1f * MM},
    {2, 7, 99.1f * MM, 38.1f * MM, 15.15f * MM, 4.65f * MM, 101.6f * MM, 38.1f * MM},
    {3, 8, 63.5f * MM, 33.9f * MM, 12.9f * MM, 7.21f * MM, 66.04f * MM, 33.9f * MM},
    {2, 4, 99.1f * MM, 67.7f * MM, 13.1f * MM, 4.65f * MM, 101.6f * MM, 67.7f * MM}
  };

  private static String[] lines(String text) {
    if (text.length() > 10000) throw new IllegalArgumentException("Address is too long");
    String[] lines = TextUtil.split(text.replace("\r\n", "\n").replace('\r', '\n'), '\n');
    if (lines.length > 20)
      throw new IllegalArgumentException("Addresses can contain at most 20 lines");
    return lines;
  }

  private static Paragraph paragraph(String id, String text, float size) {
    return new Paragraph(id, RichText.of(text, TextStyle.DEFAULT.with("size", "" + size)))
        .with("after", "0")
        .with("spacing", "1")
        .with("style", "NoSpacing");
  }

  public static void envelope(
      Session s, String delivery, String returned, String size, boolean add) {
    int index = -1;
    for (int i = 0; i < ENVELOPES.length; i++)
      if (ENVELOPES[i].equalsIgnoreCase(size) || ENVELOPES[i].substring(9).equalsIgnoreCase(size))
        index = i;
    if (index < 0) throw new IllegalArgumentException("Unknown envelope size: " + size);
    envelope(s, delivery, returned, SIZES[index][0], SIZES[index][1], add);
  }

  public static void envelope(
      Session s, String delivery, String returned, float width, float height, boolean add) {
    if (width < 144 || height < 144 || width > 1584 || height > 1584)
      throw new IllegalArgumentException("Envelope dimensions must be 144–1,584 points");
    float w = Math.max(width, height), h = Math.min(width, height);
    var page = new PageSpec(w, h, 22, 22, 22, 22);
    if (delivery == null)
      delivery =
          s.mergeData().rows.isEmpty()
              ? "Recipient Name\nStreet Address\nCity, ST 00000"
              : s.mergeData().address(s.mergeData().record);
    var old = s.document();
    long next = old.nextId;
    var blocks = new Sequence<Block>();
    String[] returns = returned.isEmpty() ? new String[0] : lines(returned);
    for (String line : returns) blocks = blocks.append(paragraph("p" + next++, line, 10));
    int spacer = Math.max(1, 6 - Math.min(returns.length, 6)) + 2;
    for (int i = 0; i < spacer; i++) blocks = blocks.append(paragraph("p" + next++, "", 12));
    for (String line : lines(delivery))
      blocks = blocks.append(paragraph("p" + next++, line, 12).with("indent", "" + (w * .42f)));
    if (blocks.size() == 0) blocks = blocks.append(paragraph("p" + next++, "", 12));
    var boundary = blocks.get(blocks.size() - 1).id();
    var envelope = new Section(page, PageFurniture.EMPTY, "nextPage", 0);
    Document output;
    if (add) {
      for (Block b : old.blocks) blocks = blocks.append(b);
      var sections = new LinkedHashMap<String, Section>(old.sections);
      sections.put(boundary, envelope);
      var first = old.sectionRanges().get(0);
      var last = old.lastSection;
      if (old.sections.containsKey(old.blocks.get(first.last).id()))
        sections.put(old.blocks.get(first.last).id(), first.raw.start("nextPage"));
      else last = last.start("nextPage");
      output = old.blocks(blocks, next).sections(sections, last);
    } else
      output =
          new Document(blocks, page, "Envelope", next)
              .sections(Collections.<String, Section>emptyMap(), envelope);
    s.replaceDocument(output);
  }

  public static int labels(
      Session s,
      String product,
      String text,
      boolean fromRecipients,
      int rows,
      int columns,
      int singleRow,
      int singleColumn) {
    int index = Arrays.asList(PRODUCTS).indexOf(product);
    if (index < 0) throw new IllegalArgumentException("Unknown label product");
    var p = LABELS[index];
    if (rows == 0) rows = (int) p[1];
    if (columns == 0) columns = (int) p[0];
    if (rows < 1 || rows > 40 || columns < 1 || columns > 10)
      throw new IllegalArgumentException("Invalid label grid");
    if (singleRow < 0
        || singleRow > rows
        || singleColumn < 0
        || singleColumn > columns
        || (singleRow == 0) != (singleColumn == 0))
      throw new IllegalArgumentException("Choose a valid label position");
    if (fromRecipients && s.mergeData().rows.isEmpty())
      throw new IllegalArgumentException("Select recipients first");
    float pageW = index < 5 ? 612 : 595.3f,
        pageH = index < 5 ? 792 : 841.9f,
        gap = Math.max(0, p[6] - p[2]);
    float used = p[6] * (columns - 1) + p[2], bottom = p[4] + p[7] * (rows - 1) + p[3];
    if (used + p[5] > pageW + .1f || bottom > pageH + .1f)
      throw new IllegalArgumentException("Label grid does not fit the selected sheet");
    int gridColumns = gap > .5f ? columns * 2 - 1 : columns;
    long next = s.document().nextId;
    var format = new TableFormat(rows, gridColumns);
    for (int c = 0; c < gridColumns; c++)
      format = format.column(c, gap > .5f && c % 2 == 1 ? gap : p[2]);
    for (int row = 0; row < rows; row++) {
      format = format.minHeight(row, p[3]);
      for (int c = 0; c < gridColumns; c++) {
        boolean spacer = gap > .5f && c % 2 == 1;
        format =
            format.cell(
                row, c, new TableFormat.Cell(0xffffff, 0xffffff, 0, spacer ? 0 : 9, 0, "center"));
      }
    }
    int capacity = rows * columns;
    int sheets =
        fromRecipients && singleRow == 0
            ? (s.mergeData().rows.size() + capacity - 1) / capacity
            : 1;
    if ((long) sheets * rows * gridColumns > 200000)
      throw new IllegalArgumentException("Too many labels; select fewer recipients");
    var page = new PageSpec(pageW, pageH, p[4], Math.max(0, pageW - p[5] - used), 0, p[5]);
    var furniture =
        new PageFurniture(Collections.<String, Sequence<Paragraph>>emptyMap(), false, false, 0, 0);
    var section = new Section(page, furniture, "nextPage", 0);
    var sections = new LinkedHashMap<String, Section>();
    var blocks = new Sequence<Block>();
    int record = 0, filled = 0;
    long textSize = 0;
    for (int sheet = 0; sheet < sheets; sheet++) {
      var cells = new ArrayList<List<Sequence<Paragraph>>>();
      for (int row = 0; row < rows; row++) {
        var values = new ArrayList<Sequence<Paragraph>>();
        for (int c = 0; c < gridColumns; c++) {
          boolean spacer = gap > .5f && c % 2 == 1;
          int labelColumn = gap > .5f ? c / 2 : c;
          var value = "";
          if (!spacer
              && (singleRow == 0 || singleRow == row + 1 && singleColumn == labelColumn + 1)) {
            if (!fromRecipients) value = text;
            else if (record < s.mergeData().rows.size()) value = s.mergeData().address(record++);
            if (!value.isEmpty()) filled++;
          }
          if ((textSize += value.length()) > 16000000)
            throw new IllegalArgumentException(
                "Label output is too large; select fewer recipients");
          var story = new Sequence<Paragraph>();
          for (String line : lines(value)) story = story.append(paragraph("p" + next++, line, 12));
          if (story.size() == 0) story = story.append(paragraph("p" + next++, "", 12));
          values.add(story);
        }
        cells.add(values);
      }
      var table = new Table("t" + next++, cells, Collections.<Table.Merge>emptyList(), format);
      // Word requires a paragraph after a table. Keep the sheet boundary small enough
      // to fit below the last label, without inserting a blank sheet during pagination.
      Paragraph boundary = paragraph("p" + next++, "", 1);
      blocks = blocks.append(table).append(boundary);
      if (sheet + 1 < sheets) sections.put(boundary.id, section);
    }
    var output = new Document(blocks, page, "Labels", next).sections(sections, section);
    s.replaceDocument(output);
    return filled;
  }
}
