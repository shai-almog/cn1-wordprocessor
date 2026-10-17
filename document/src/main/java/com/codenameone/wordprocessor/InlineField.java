package com.codenameone.wordprocessor;

/**
 * An atomic inline field. Instructions are data; applications explicitly resolve supported types.
 */
public final class InlineField {
  public static final String OBJECT = "\ufffc";
  public final String instruction, result;
  public final boolean locked, showCode;

  public InlineField(String instruction, String result, boolean locked, boolean showCode) {
    if (instruction == null
        || instruction.trim().isEmpty()
        || instruction.length() > 8192
        || result == null
        || result.length() > 1000000) throw new IllegalArgumentException("Invalid field");
    this.instruction = instruction;
    this.result = result;
    this.locked = locked;
    this.showCode = showCode;
  }

  public String display() {
    if (showCode && instruction.startsWith(ContentControl.PREFIX)) {
      // Design mode shows the control's title (or type) as a tag around its content.
      ContentControl c = ContentControl.parse(instruction);
      String tag = c.get("title", c.type);
      return ("[" + tag + "] " + result + " [/" + tag + "]").replace('\n', ' ');
    }
    return (showCode ? "{ " + instruction + " }" : result)
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ');
  }

  public InlineField result(String value) {
    return result.equals(value) ? this : new InlineField(instruction, value, locked, showCode);
  }

  public InlineField locked(boolean value) {
    return value == locked ? this : new InlineField(instruction, result, value, showCode);
  }

  public InlineField showCode(boolean value) {
    return value == showCode ? this : new InlineField(instruction, result, locked, value);
  }
}
