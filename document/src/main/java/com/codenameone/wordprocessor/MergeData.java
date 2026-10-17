package com.codenameone.wordprocessor;

import java.util.*;

/** Immutable session-owned recipient list. Never evaluates spreadsheet formulas or field code. */
public final class MergeData {
  public static final MergeData EMPTY =
      new MergeData(Collections.<String>emptyList(), Collections.<List<String>>emptyList());
  public final List<String> fields;
  public final List<List<String>> rows;

  /** Original import order, retained even when recipients are filtered or excluded. */
  public final List<List<String>> sourceRows;

  /** Zero-based source row indices in the active merge order. */
  public final List<Integer> sourceIndices;

  public final Set<Integer> excluded;
  public final String sortField, filterField, filterOperator, filterValue;
  public final boolean descending;
  public final String kind;
  public final boolean preview, highlight;
  public final int record;
  public final Map<String, String> mapping;

  public MergeData(List<String> fields, List<? extends List<String>> rows) {
    if (fields.size() > 1000
        || rows.size() > 100000
        || (long) fields.size() * rows.size() > 1000000)
      throw new IllegalArgumentException("Recipient list exceeds 100,000 rows or 1,000,000 cells");
    List<String> names = new ArrayList<String>();
    Set<String> used = new HashSet<String>();
    for (int i = 0; i < fields.size(); i++) {
      String base = fields.get(i).trim();
      if (base.isEmpty()) base = "Column " + (i + 1);
      if (base.length() > 200
          || base.indexOf('\n') >= 0
          || base.indexOf('\r') >= 0
          || base.indexOf('\0') >= 0)
        throw new IllegalArgumentException("Invalid recipient field name");
      String name = base;
      int n = 2;
      while (!used.add(name.toLowerCase())) name = base + " " + n++;
      names.add(name);
    }
    if (names.isEmpty() && !rows.isEmpty())
      throw new IllegalArgumentException("Recipient fields are required");
    List<List<String>> copy = new ArrayList<List<String>>();
    long length = 0;
    for (List<String> row : rows) {
      if (row.size() > names.size())
        throw new IllegalArgumentException("A recipient row has more values than fields");
      List<String> values = new ArrayList<String>();
      for (int i = 0; i < names.size(); i++) {
        String value = i < row.size() && row.get(i) != null ? row.get(i) : "";
        length += value.length();
        if (length > 16000000 || value.length() > 100000 || value.indexOf('\0') >= 0)
          throw new IllegalArgumentException(
              "Recipient data is too large or contains NUL characters");
        values.add(value);
      }
      copy.add(Collections.unmodifiableList(values));
    }
    this.fields = Collections.unmodifiableList(names);
    this.rows = Collections.unmodifiableList(copy);
    sourceRows = this.rows;
    List<Integer> indices = new ArrayList<Integer>();
    for (int i = 0; i < rows.size(); i++) indices.add(i);
    sourceIndices = Collections.unmodifiableList(indices);
    excluded = Collections.emptySet();
    sortField = filterField = filterValue = "";
    filterOperator = "contains";
    descending = false;
    kind = "letters";
    preview = highlight = false;
    record = 0;
    mapping = Collections.emptyMap();
  }

  private MergeData(
      MergeData data,
      String kind,
      boolean preview,
      boolean highlight,
      int record,
      Map<String, String> mapping) {
    if (!Arrays.asList("letters", "emails", "envelopes", "labels", "directory", "normal")
        .contains(kind)) throw new IllegalArgumentException("Unknown merge type: " + kind);
    if (record < 0 || record >= Math.max(1, data.rows.size()))
      throw new IllegalArgumentException("Recipient is outside the list");
    fields = data.fields;
    rows = data.rows;
    sourceRows = data.sourceRows;
    sourceIndices = data.sourceIndices;
    excluded = data.excluded;
    sortField = data.sortField;
    filterField = data.filterField;
    filterOperator = data.filterOperator;
    filterValue = data.filterValue;
    descending = data.descending;
    this.kind = kind;
    this.preview = preview;
    this.highlight = highlight;
    this.record = record;
    this.mapping = mapping;
  }

  /** Changes the recipient view without deleting source data or address mappings. */
  public MergeData manage(
      String sort,
      boolean descending,
      String field,
      String operator,
      String value,
      Collection<Integer> excluded) {
    return new MergeData(this, sort, descending, field, operator, value, excluded);
  }

  private MergeData(
      MergeData data,
      String sort,
      boolean descending,
      String field,
      String operator,
      String value,
      Collection<Integer> exclusions) {
    if (sort == null
        || field == null
        || value == null
        || exclusions == null
        || !sort.isEmpty() && data.column(sort) < 0
        || !field.isEmpty() && data.column(field) < 0)
      throw new IllegalArgumentException("Unknown recipient field");
    if (!Arrays.asList("contains", "=", "<>", "<", ">", "<=", ">=", "blank", "notBlank")
        .contains(operator)) throw new IllegalArgumentException("Unknown recipient filter");
    Set<Integer> copy = new LinkedHashSet<Integer>();
    for (Integer i : exclusions) {
      if (i == null || i < 0 || i >= data.sourceRows.size())
        throw new IllegalArgumentException("Excluded recipient is outside the source list");
      copy.add(i);
    }
    fields = data.fields;
    sourceRows = data.sourceRows;
    excluded = Collections.unmodifiableSet(copy);
    sortField = sort;
    filterField = field;
    filterOperator = operator;
    filterValue = value;
    this.descending = descending;
    List<Integer> indices = new ArrayList<Integer>();
    int filterColumn = data.column(field);
    for (int i = 0; i < sourceRows.size(); i++)
      if (!copy.contains(i)
          && (filterColumn < 0 || matches(sourceRows.get(i).get(filterColumn), operator, value)))
        indices.add(i);
    final int sortColumn = data.column(sort);
    final boolean reverse = descending;
    if (sortColumn >= 0)
      Collections.sort(
          indices,
          new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
              int result =
                  sourceRows
                      .get(a)
                      .get(sortColumn)
                      .compareToIgnoreCase(sourceRows.get(b).get(sortColumn));
              return reverse ? -Integer.signum(result) : result;
            }
          });
    sourceIndices = Collections.unmodifiableList(indices);
    List<List<String>> selected = new ArrayList<List<String>>();
    for (Integer i : indices) selected.add(sourceRows.get(i));
    rows = Collections.unmodifiableList(selected);
    int current = data.sourceIndices.isEmpty() ? -1 : data.sourceIndices.get(data.record);
    record = Math.max(0, indices.indexOf(current));
    kind = data.kind;
    preview = data.preview && !rows.isEmpty();
    highlight = data.highlight;
    mapping = data.mapping;
  }

  private static boolean matches(String actual, String operator, String expected) {
    if ("blank".equals(operator)) return actual.trim().isEmpty();
    if ("notBlank".equals(operator)) return !actual.trim().isEmpty();
    if ("contains".equals(operator)) {
      for (int i = 0; i + expected.length() <= actual.length(); i++)
        if (actual.regionMatches(true, i, expected, 0, expected.length())) return true;
      return false;
    }
    int comparison = actual.compareToIgnoreCase(expected);
    // Equality is textual (postal codes and IDs retain their leading zeros).
    if (!"=".equals(operator) && !"<>".equals(operator)) {
      try {
        double a = Double.parseDouble(actual), b = Double.parseDouble(expected);
        if (!Double.isNaN(a) && !Double.isNaN(b) && !Double.isInfinite(a) && !Double.isInfinite(b))
          comparison = a < b ? -1 : a > b ? 1 : 0;
      } catch (NumberFormatException ignored) {
      }
    }
    if ("=".equals(operator)) return comparison == 0;
    if ("<>".equals(operator)) return comparison != 0;
    if ("<".equals(operator)) return comparison < 0;
    if (">".equals(operator)) return comparison > 0;
    if ("<=".equals(operator)) return comparison <= 0;
    return comparison >= 0;
  }

  public MergeData options(String kind, boolean preview, boolean highlight, int record) {
    return new MergeData(this, kind, preview, highlight, record, mapping);
  }

  public MergeData mapping(Map<String, String> value) {
    for (Map.Entry<String, String> e : value.entrySet()) {
      if (!ALIASES.containsKey(e.getKey()) || !e.getValue().isEmpty() && column(e.getValue()) < 0)
        throw new IllegalArgumentException("Unknown address mapping: " + e.getKey());
    }
    return new MergeData(
        this,
        kind,
        preview,
        highlight,
        record,
        Collections.unmodifiableMap(new LinkedHashMap<String, String>(value)));
  }

  public int column(String name) {
    for (int i = 0; i < fields.size(); i++) if (fields.get(i).equalsIgnoreCase(name)) return i;
    return -1;
  }

  public String value(String field, int record) {
    int col = column(field);
    return col < 0 || record < 0 || record >= rows.size() ? "" : rows.get(record).get(col);
  }

  private static final Map<String, String[]> ALIASES = aliases();

  private static Map<String, String[]> aliases() {
    Map<String, String[]> out = new LinkedHashMap<String, String[]>();
    out.put("title", new String[] {"Title", "CourtesyTitle"});
    out.put("firstName", new String[] {"FirstName", "First", "GivenName"});
    out.put("lastName", new String[] {"LastName", "Last", "Surname", "FamilyName"});
    out.put("company", new String[] {"Company", "Organization", "CompanyName"});
    out.put("address", new String[] {"Address", "AddressLine1", "Address1", "Street"});
    out.put("city", new String[] {"City", "Town"});
    out.put("state", new String[] {"State", "Province", "Region"});
    out.put("postal", new String[] {"PostalCode", "ZIP", "ZipCode", "Postcode"});
    out.put("country", new String[] {"Country"});
    return out;
  }

  private Map<String, String> resolvedMapping;

  public Map<String, String> mappedFields() {
    if (resolvedMapping != null) return resolvedMapping;
    Map<String, String> out = new LinkedHashMap<String, String>();
    for (Map.Entry<String, String[]> e : ALIASES.entrySet()) {
      String found = "";
      for (String candidate : e.getValue())
        for (String field : fields)
          if (found.isEmpty()
              && field.replace(" ", "").replace("_", "").equalsIgnoreCase(candidate)) found = field;
      out.put(e.getKey(), mapping.containsKey(e.getKey()) ? mapping.get(e.getKey()) : found);
    }
    resolvedMapping = Collections.unmodifiableMap(out);
    return resolvedMapping;
  }

  private String mapped(String key, int record) {
    return value(mappedFields().get(key), record).trim();
  }

  private static String join(String a, String b) {
    return a.isEmpty() ? b : b.isEmpty() ? a : a + " " + b;
  }

  public String greeting(int record) {
    String name = join(mapped("firstName", record), mapped("lastName", record));
    return "Dear " + (name.isEmpty() ? "Sir or Madam" : name) + ",";
  }

  public String address(int record) {
    StringBuilder out = new StringBuilder();
    String city = mapped("city", record),
        region = join(mapped("state", record), mapped("postal", record));
    String[] lines = {
      join(mapped("title", record), join(mapped("firstName", record), mapped("lastName", record))),
      mapped("company", record),
      mapped("address", record),
      city.isEmpty() ? region : region.isEmpty() ? city : city + ", " + region,
      mapped("country", record)
    };
    for (String line : lines)
      if (!line.isEmpty()) {
        if (out.length() > 0) out.append('\n');
        out.append(line);
      }
    return out.toString();
  }

  public static MergeData csv(String text, boolean headers) {
    List<List<String>> records = parseCsv(text);
    if (records.isEmpty()) throw new IllegalArgumentException("Recipient list is empty");
    List<String> names;
    if (headers) names = records.remove(0);
    else {
      names = new ArrayList<String>();
      int width = 0;
      for (List<String> row : records) width = Math.max(width, row.size());
      for (int i = 0; i < width; i++) names.add("Column " + (i + 1));
    }
    return new MergeData(names, records);
  }

  public static List<List<String>> parseCsv(String text) {
    if (text.length() > 16000000)
      throw new IllegalArgumentException("Recipient file exceeds 16 MB of text");
    if (text.startsWith("\ufeff")) text = text.substring(1);
    char delimiter = ',';
    int[] counts = new int[3];
    char[] candidates = {',', ';', '\t'};
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') quoted = !quoted;
      if (!quoted && (c == '\n' || c == '\r')) break;
      if (!quoted) for (int n = 0; n < 3; n++) if (c == candidates[n]) counts[n]++;
    }
    for (int i = 1, best = 0; i < 3; i++)
      if (counts[i] > counts[best]) {
        delimiter = candidates[i];
        best = i;
      }
    List<List<String>> out = new ArrayList<List<String>>();
    List<String> row = new ArrayList<String>();
    StringBuilder value = new StringBuilder();
    quoted = false;
    boolean closed = false;
    for (int i = 0; i <= text.length(); i++) {
      char c = i == text.length() ? '\n' : text.charAt(i);
      if (quoted) {
        if (i == text.length())
          throw new IllegalArgumentException("Unclosed quote in recipient file");
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            value.append('"');
            i++;
          } else {
            quoted = false;
            closed = true;
          }
        } else value.append(c);
      } else if (c == delimiter || c == '\r' || c == '\n') {
        row.add(value.toString());
        value.setLength(0);
        closed = false;
        if (row.size() > 1000) throw new IllegalArgumentException("Too many recipient fields");
        if (c != delimiter) {
          if (row.size() != 1 || !row.get(0).trim().isEmpty()) out.add(row);
          if (out.size() > 100001) throw new IllegalArgumentException("Too many recipients");
          row = new ArrayList<String>();
          if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        }
      } else if (c == '"' && value.length() == 0 && !closed) quoted = true;
      else if (closed || c == '"')
        throw new IllegalArgumentException("Unexpected text after a quoted recipient value");
      else value.append(c);
    }
    return out;
  }

  public String csv() {
    StringBuilder out = new StringBuilder();
    appendRow(out, fields);
    for (List<String> row : sourceRows) appendRow(out, row);
    return out.toString();
  }

  private static void appendRow(StringBuilder out, List<String> row) {
    for (int i = 0; i < row.size(); i++) {
      if (i > 0) out.append(',');
      out.append('"').append(row.get(i).replace("\"", "\"\"")).append('"');
    }
    out.append('\n');
  }
}
