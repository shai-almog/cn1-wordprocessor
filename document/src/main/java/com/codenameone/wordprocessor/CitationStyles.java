package com.codenameone.wordprocessor;

import java.util.*;

/** Deterministic citation formatting for the source fields exposed by the editor. */
public final class CitationStyles {
  private CitationStyles() {}
  public static final String[] NAMES = {"APA", "Chicago", "GB/T 7714", "GOST (name sort)", "GOST (title sort)",
      "Harvard (Anglia)", "IEEE", "ISO 690 (author-date)", "ISO 690 (numerical)", "MLA", "SIST02", "Turabian"};
  private static final String[] ALIASES = {"apa", "chicago", "gb", "gostname", "gosttitle", "harvard", "ieee", "iso690", "iso690num", "mla", "sist", "turabian"};
  public static int index(String name) {
    for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equalsIgnoreCase(name.trim())) return i;
    var key = new StringBuilder();
    for (char c : name.toLowerCase().toCharArray()) if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') key.append(c);
    var text = key.toString(); int found = -1, length = -1;
    for (int i = 0; i < ALIASES.length; i++) if (text.startsWith(ALIASES[i]) && ALIASES[i].length() > length) {
      found = i; length = ALIASES[i].length();
    }
    if (found < 0 && text.startsWith("gost")) found = 3;
    if (text.startsWith("iso690nmerical")) found = 8;
    if (found < 0) throw new IllegalArgumentException("Choose a supported citation style");
    return found;
  }
  public static boolean numbered(int style) { return style == 2 || style == 3 || style == 4 || style == 6 || style == 8 || style == 10; }
  public static boolean citedOrder(int style) { return style == 2 || style == 6 || style == 8 || style == 10; }
  public static String title(int style) { return style == 9 ? "Works Cited" : style == 11 ? "Bibliography" : "References"; }
  private static String join(String left, String separator, String right) {
    return left.isEmpty() || right.isEmpty() ? left + right : left + separator + right;
  }
  private static String initials(String name, boolean dots) {
    var result = new StringBuilder();
    for (String part : TextUtil.split(name.trim(), ' ')) if (!part.isEmpty()) {
      if (result.length() > 0) result.append(' ');
      int length = Character.isHighSurrogate(part.charAt(0)) && part.length() > 1 && Character.isLowSurrogate(part.charAt(1)) ? 2 : 1;
      result.append(part.substring(0, length)); if (dots) result.append('.');
    }
    return result.toString();
  }
  private static String author(String[] pair, int style, int position) {
    String last = pair[0], first = pair[1];
    if (style == 0 || style == 5) return join(last, ", ", initials(first, true));
    if (style == 6) return join(initials(first, true), " ", last);
    if (style == 3 || style == 4) return join(last, " ", initials(first, true));
    if (style == 2) return join(last.toUpperCase(), " ", initials(first, false));
    if (style == 7 || style == 8) return join(last.toUpperCase(), ", ", first);
    if ((style == 1 || style == 11) && position > 0) return join(first, " ", last);
    return join(last, ", ", first);
  }
  public static String authors(CitationSource source, int style) {
    var names = CitationSource.names(source.get("author"));
    int limit = style == 2 ? 3 : style == 9 ? 1 : names.size(), shown = Math.min(limit, names.size());
    var out = new StringBuilder();
    for (int i = 0; i < shown; i++) {
      if (i > 0) {
        if (style == 10) out.append("; ");
        else if (i == shown - 1 && style == 0) out.append(", & ");
        else if (i == shown - 1 && (style == 1 || style == 11)) out.append(", and ");
        else if (i == shown - 1 && (style == 5 || style == 7 || style == 8)) out.append(" and ");
        else out.append(", ");
      }
      out.append(author(names.get(i), style, i));
    }
    if (shown < names.size()) out.append(", et al.");
    return out.toString();
  }
  public static String citation(CitationSource source, int style, int number, String pages) {
    if (numbered(style)) {
      String suffix = pages.isEmpty() || style == 6 ? "" : (style == 2 ? ", " : ", p. ") + pages;
      return style == 8 || style == 10 ? "(" + number + suffix + ")" : "[" + number + suffix + "]";
    }
    var names = CitationSource.names(source.get("author"));
    var who = source.get("title"); int maximum = style == 5 || style == 7 || style == 11 ? 4 : 3;
    if (!names.isEmpty()) {
      who = names.get(0)[0];
      if (names.size() >= maximum) who += " et al.";
      else for (int i = 1; i < names.size(); i++) who += (i == names.size() - 1 ? style == 0 ? " & " : " and " : ", ") + names.get(i)[0];
    }
    if (style == 9) return "(" + who + (pages.isEmpty() ? "" : " " + pages) + ")";
    String sep = style == 0 || style == 5 ? ", " : " ";
    String pageSep = style == 1 || style == 11 ? ", " : ", p. ";
    return "(" + who + sep + source.get("year") + (pages.isEmpty() ? "" : pageSep + pages) + ")";
  }
  private static String optional(String value, String prefix, String suffix) { return value.isEmpty() ? "" : prefix + value + suffix; }
  private static String sentence(String value) { return value.isEmpty() ? "" : value.endsWith(".") ? value + " " : value + ". "; }
  public static String entry(CitationSource source, int style, int number) {
    String names = authors(source, style), title = source.get("title"), year = source.get("year");
    if (year.isEmpty()) year = "n.d.";
    String city = source.get("city"), publisher = source.get("publisher"), pub = join(city, ": ", publisher);
    String journal = source.get("journal"), volume = source.get("volume"), pages = source.get("pages"), url = source.get("url");
    boolean article = !journal.isEmpty(); String result;
    var container = journal + optional(volume, ", ", "") + optional(pages, ", ", "");
    switch (style) {
      case 0:
        result = optional(names, "", " ") + "(" + year + "). " + sentence(title)
            + (article ? sentence(container) : sentence(publisher)) + url; break;
      case 1:
        result = sentence(names) + sentence(year) + sentence(title) + sentence(article ? container : pub) + url; break;
      case 2:
        var kind = source.get("kind").toLowerCase();
        String type = kind.equals("web") || kind.equals("website") ? "EB/OL"
            : (article || kind.equals("article") ? "J" : kind.equals("book") ? "M" : kind.equals("report") ? "R" : "Z") + (url.isEmpty() ? "" : "/OL");
        result = "[" + number + "] " + sentence(names) + title + "[" + type + "]. "
            + (article ? journal + ", " + year + optional(volume, ", ", "") + optional(pages, ": ", "") + ". "
                : optional(pub, "", ", ") + sentence(year)) + optional(url, "", "."); break;
      case 3: case 4:
        result = number + ". " + optional(names, "", " ") + title
            + (article ? " // " + journal + ". – " + year + "." + optional(volume, " – Vol. ", ".") + optional(pages, " – P. ", ".")
                : ". –" + optional(city, " ", " :") + optional(publisher, " ", ",") + " " + year + ".")
            + optional(url, " – URL: ", "."); break;
      case 5:
        result = optional(names, "", ", ") + sentence(year) + sentence(title)
            + sentence(article ? journal + optional(volume, ", ", "") + optional(pages, ", pp. ", "") : pub)
            + optional(url, "Available at: <", ">."); break;
      case 6:
        result = "[" + number + "] " + optional(names, "", ", ") + "“" + title + ",” "
            + (article ? container + ", " : optional(pub, "", ", ")) + sentence(year) + url; break;
      case 7:
        result = optional(names, "", ", ") + sentence(year) + sentence(title) + sentence(article ? container : pub)
            + optional(url, "Available from: ", ""); break;
      case 8:
        result = number + ". " + sentence(names) + sentence(title)
            + (article ? sentence(journal) + year + optional(volume, ", ", "") + optional(pages, ", ", "") + ". "
                : optional(pub, "", ", ") + sentence(year)) + optional(url, "Available from: ", ""); break;
      case 9:
        result = sentence(names) + "“" + title + ".” " + (article ? container + ", " : optional(publisher, "", ", ")) + sentence(year) + url; break;
      case 10:
        result = number + ") " + sentence(names) + sentence(title)
            + (article ? sentence(journal) + year + optional(volume, ", vol. ", "") + optional(pages, ", p. ", "") + ". "
                : optional(city, "", ", ") + optional(publisher, "", ", ") + sentence(year)) + url; break;
      default:
        result = sentence(names) + (article ? "“" + title + ".” " + journal + optional(volume, " ", "") + " (" + year + ")" + optional(pages, ": ", "") + ". "
            : sentence(title) + optional(pub, "", ", ") + sentence(year)) + optional(url, "", ".");
    }
    return result.trim();
  }
}
