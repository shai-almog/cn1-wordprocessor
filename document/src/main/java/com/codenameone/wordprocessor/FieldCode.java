package com.codenameone.wordprocessor;

import java.util.*;

/** Parsed field instruction data. No instruction is executed as code. */
public final class FieldCode {
  public final String name;
  public final List<String> arguments = new ArrayList<String>();
  public final Map<String, String> switches = new LinkedHashMap<String, String>();

  public FieldCode(String instruction) {
    List<Token> tokens = lex(instruction);
    name = tokens.isEmpty() ? "" : tokens.get(0).text.toUpperCase();
    for (int i = 1; i < tokens.size(); i++) {
      Token item = tokens.get(i);
      String token = item.text;
      if (item.isSwitch()) {
        String value =
            i + 1 < tokens.size() && !tokens.get(i + 1).isSwitch() ? tokens.get(++i).text : "";
        switches.put(token.substring(1).toLowerCase(), value);
      } else arguments.add(token);
    }
  }

  private static final class Token {
    final String text;
    final boolean quoted;

    Token(String text, boolean quoted) {
      this.text = text;
      this.quoted = quoted;
    }

    boolean isSwitch() {
      return !quoted && text.startsWith("\\") && text.length() == 2;
    }
  }

  public static List<String> tokens(String instruction) {
    List<String> out = new ArrayList<String>();
    for (Token token : lex(instruction)) out.add(token.text);
    return out;
  }

  private static List<Token> lex(String instruction) {
    List<Token> out = new ArrayList<Token>();
    StringBuilder token = new StringBuilder();
    boolean quoted = false, wasQuoted = false, started = false;
    for (int i = 0; i < instruction.length(); i++) {
      char c = instruction.charAt(i);
      if (c == '"') {
        quoted = !quoted;
        wasQuoted = true;
        started = true;
      } else if (quoted
          && c == '\\'
          && i + 1 < instruction.length()
          && (instruction.charAt(i + 1) == '"' || instruction.charAt(i + 1) == '\\')) {
        token.append(instruction.charAt(++i));
        started = true;
      } else if (!quoted && Character.isWhitespace(c)) {
        if (started) {
          out.add(new Token(token.toString(), wasQuoted));
          token.setLength(0);
          started = wasQuoted = false;
        }
      } else {
        token.append(c);
        started = true;
      }
    }
    if (started) out.add(new Token(token.toString(), wasQuoted));
    return out;
  }
}
