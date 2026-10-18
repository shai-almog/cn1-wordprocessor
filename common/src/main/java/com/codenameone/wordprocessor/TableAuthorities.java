package com.codenameone.wordprocessor;

/** Standard authority citations and a paginated, editable table of authorities. */
public final class TableAuthorities {
  private TableAuthorities() {}
  public static int[] range(Document document) { return DocumentIndex.range(document, "TOA"); }
  public static DocumentIndex.Options options(Document document) { return DocumentIndex.options(document, "TOA"); }
  public static void insert(Session session, String title, String leader, TableContents.Paginator paginator) {
    DocumentIndex.insert(session, new DocumentIndex.Options(title, false, false, true, leader), paginator, "TOA");
  }
  public static void update(Session session, TableContents.Paginator paginator) { DocumentIndex.update(session, paginator, "TOA"); }
  public static void remove(Session session) { DocumentIndex.remove(session, "TOA"); }
  public static void mark(Session session, String entry) {
    if (entry == null || entry.trim().isEmpty() || entry.length() > 2000)
      throw new IllegalArgumentException("Enter a citation of 1 to 2000 characters");
    for (int i = 0; i < entry.length(); i++) if (entry.charAt(i) < 32 || entry.charAt(i) >= 127 && entry.charAt(i) <= 159)
      throw new IllegalArgumentException("A citation cannot contain control characters");
    var instruction = "TA \\l \"" + entry.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    session.transact(s -> {
      var selected = s.selection(); s.select(selected.endBlock(), selected.end(), selected.end());
      s.insertField(new InlineField(instruction, "", false, false));
    });
  }
}
