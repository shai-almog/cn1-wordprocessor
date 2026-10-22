package com.codenameone.wordprocessor;

/**
 * An atomic inline field. Instructions are data; applications explicitly resolve supported types.
 */
public final class InlineField {
  public static final String OBJECT = "\ufffc";
  public final String instruction, result;
  public final boolean locked, showCode;

  /**
   * Formatting inside the result, as Wordcraft keeps a Zotero citation's formatted runs between
   * the field's separate and end marks: "length:flags" pieces joined by ';' covering the result,
   * flags being {@link #BOLD}, {@link #ITALIC}, ... Empty when the result is uniformly formatted.
   */
  public final String formats;

  /**
   * {@link #formats} of a range field's start mark. As Wordcraft's FieldStart/FieldEnd, a Zotero
   * field is a start mark (instruction, empty result) and an {@link #END} mark with the result as
   * ordinary editable text between them, possibly spanning paragraphs (bibliography entries).
   */
  public static final String RANGE = "range";

  /** Instruction of a range field's end mark. */
  public static final String END = "FIELD_END";

  public static final int BOLD = 1, ITALIC = 2, UNDERLINE = 4, SMALL_CAPS = 8, SUPER = 16, SUB = 32;

  public InlineField(String instruction, String result, boolean locked, boolean showCode) {
    this(instruction, result, locked, showCode, "");
  }

  public InlineField(String instruction, String result, boolean locked, boolean showCode, String formats) {
    if (!valid(instruction, result)) throw new IllegalArgumentException("Invalid field");
    this.instruction = instruction;
    this.result = result;
    this.locked = locked;
    this.showCode = showCode;
    this.formats = formats == null || formats.length() > 200000 ? "" : formats;
  }

  /**
   * Longest instruction of a citation manager's ADDIN field (Zotero's CSL JSON, EndNote's
   * EN.CITE/EN.REFLIST embedded XML), as Wordcraft's docx reader caps an instruction at
   * MAX_INSTR = 2 MiB (read/story.rs); other field codes stay short.
   */
  public static final int MAX_ADDIN_INSTRUCTION = 2 * 1024 * 1024, MAX_INSTRUCTION = 8192, MAX_RESULT = 1000000;

  /** True when a field with this instruction and result can be built; readers degrade others to text. */
  public static boolean valid(String instruction, String result) {
    return instruction != null
        && !instruction.trim().isEmpty()
        && instruction.length()
            <= (instruction.startsWith(Zotero.PREFIX) || rangeInstruction(instruction)
                ? MAX_ADDIN_INSTRUCTION
                : MAX_INSTRUCTION)
        && result != null
        && result.length() <= MAX_RESULT;
  }

  /** True for the start mark of a range field (its result is the text up to its end mark). */
  public boolean rangeStart() {
    return RANGE.equals(formats);
  }

  /**
   * Field codes kept as range fields (start mark, ordinary content, end mark), as Wordcraft's
   * docx reader keeps citation managers' ADDIN fields (Zotero, Mendeley, EndNote).
   */
  public static boolean rangeInstruction(String instruction) {
    String t = instruction == null ? "" : instruction.trim();
    return t.regionMatches(true, 0, "ADDIN", 0, 5) && (t.length() == 5 || t.charAt(5) == ' ');
  }

  /** True for a range field's end mark. */
  public boolean rangeEnd() {
    return END.equals(instruction);
  }

  /** The start mark of a range field with this instruction. */
  public InlineField start() {
    return new InlineField(instruction, "", locked, false, RANGE);
  }

  /** A range field's end mark. */
  public static InlineField end() {
    return new InlineField(END, "", false, false, "");
  }

  /** A formatted piece of the result: its text (as displayed) and flags. */
  public static final class Piece {
    public final String text;
    public final int flags;

    Piece(String text, int flags) {
      this.text = text;
      this.flags = flags;
    }

    /** {@code base} with this piece's bold, italic, underline, small caps and script applied. */
    public TextStyle style(TextStyle base) {
      if (flags == 0) return base;
      TextStyle s = base;
      if ((flags & BOLD) != 0) s = s.with("bold", "true");
      if ((flags & ITALIC) != 0) s = s.with("italic", "true");
      if ((flags & UNDERLINE) != 0) s = s.with("underline", "true");
      if ((flags & SMALL_CAPS) != 0) s = s.with("smallCaps", "true");
      if ((flags & SUPER) != 0) s = s.with("script", "superscript");
      else if ((flags & SUB) != 0) s = s.with("script", "subscript");
      return s;
    }
  }

  /**
   * The displayed result split into its formatted pieces, or null when it has no inner
   * formatting (or shows its code). Malformed or short encodings leave the rest unformatted.
   */
  public java.util.List<Piece> pieces() {
    return showCode ? null : piecesOf(display());
  }

  /** As {@link #pieces()}, slicing {@code shown} (the result or its display, same length). */
  public java.util.List<Piece> piecesOf(String shown) {
    if (formats.isEmpty() || rangeStart()) return null;
    var out = new java.util.ArrayList<Piece>();
    int at = 0, i = 0;
    while (i < formats.length() && at < shown.length()) {
      int semi = formats.indexOf(';', i);
      if (semi < 0) semi = formats.length();
      int colon = formats.indexOf(':', i);
      if (colon < 0 || colon > semi) break;
      int len = Xml.integer(formats.substring(i, colon), -1), flags = Xml.integer(formats.substring(colon + 1, semi), 0);
      if (len <= 0) break;
      int end = Math.min(shown.length(), at + len);
      out.add(new Piece(shown.substring(at, end), flags & 63));
      at = end;
      i = semi + 1;
    }
    if (at < shown.length()) out.add(new Piece(shown.substring(at), 0));
    return out.size() > 1 || (out.size() == 1 && out.get(0).flags != 0) ? out : null;
  }

  /** Flags of {@code s} that {@code base} lacks, for encoding a formatted result. */
  public static int flags(TextStyle s, TextStyle base) {
    return (s.bold && !base.bold ? BOLD : 0) | (s.italic && !base.italic ? ITALIC : 0)
        | (s.underline && !base.underline ? UNDERLINE : 0)
        | (s.characters.smallCaps && !base.characters.smallCaps ? SMALL_CAPS : 0)
        | ("superscript".equals(s.script) && !s.script.equals(base.script) ? SUPER : 0)
        | ("subscript".equals(s.script) && !s.script.equals(base.script) ? SUB : 0);
  }

  /** {@code text} as one unformatted piece. */
  static java.util.List<Piece> plainPieces(String text) {
    return java.util.Collections.singletonList(new Piece(text, 0));
  }

  /** As {@link #result(String)}, with the result's inner formatting. */
  public InlineField result(String value, String formats) {
    return new InlineField(instruction, value, locked, showCode, formats);
  }

  public String display() {
    if (rangeEnd() || (rangeStart() && !showCode)) return "";
    if (showCode && instruction.startsWith(ContentControl.PREFIX)) {
      // Design mode shows the control's title (or type) as a tag around its content.
      ContentControl c = ContentControl.parse(instruction);
      var tag = c.get("title", c.type);
      return ("[" + tag + "] " + result + " [/" + tag + "]").replace('\n', ' ');
    }
    return (showCode ? "{ " + instruction + " }" : result)
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ');
  }

  public InlineField result(String value) {
    return result.equals(value) ? this : new InlineField(instruction, value, locked, showCode, "");
  }

  public InlineField locked(boolean value) {
    return value == locked ? this : new InlineField(instruction, result, value, showCode, formats);
  }

  public InlineField showCode(boolean value) {
    return value == showCode ? this : new InlineField(instruction, result, locked, value, formats);
  }
}
