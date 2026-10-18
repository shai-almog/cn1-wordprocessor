package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Bounded XLSX/XLSM/ODS recipient reader. Formula caches are read; formulas/macros are never run.
 */
public final class RecipientWorkbook {
  private final Map<String, byte[]> parts = new LinkedHashMap<>();
  private final List<String> names = new ArrayList<String>(), paths = new ArrayList<String>();
  private List<Element> ods;
  private Element workbook;
  private String base = "xl/";

  public RecipientWorkbook(byte[] bytes) throws IOException {
    if (bytes.length > 64 * 1024 * 1024) throw new IOException("Workbook exceeds 64 MiB");
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      long total = 0;
      byte[] buffer = new byte[8192];
      while ((entry = zip.getNextEntry()) != null) {
        if (parts.size() >= 4096) throw new IOException("Too many workbook parts");
        if (entry.isDirectory()) continue;
        var out = new ByteArrayOutputStream();
        int n;
        while ((n = zip.read(buffer)) != -1) {
          total += n;
          if (out.size() + n > 32 * 1024 * 1024 || total > 64 * 1024 * 1024)
            throw new IOException("Workbook expands beyond memory limit");
          out.write(buffer, 0, n);
        }
        if (parts.put(entry.getName(), out.toByteArray()) != null)
          throw new IOException("Duplicate workbook part");
      }
    }
    var main = "xl/workbook.xml";
    if (parts.containsKey("_rels/.rels"))
      for (Element rel : Xml.children(xml("_rels/.rels"), "Relationship"))
        if (Xml.attr(rel, "Type", "").endsWith("/officeDocument")
            && !"External".equals(Xml.attr(rel, "TargetMode", "")))
          main = resolve("", Xml.attr(rel, "Target", ""));
    if (parts.containsKey(main) && !"word/document.xml".equals(main)) {
      workbook = xml(main);
      if (!"workbook".equals(Xml.local(workbook)))
        throw new IOException("Not a spreadsheet workbook");
      base = main.substring(0, main.lastIndexOf('/') + 1);
      var relPath = base + "_rels/" + main.substring(main.lastIndexOf('/') + 1) + ".rels";
      var targets = new HashMap<String, String>();
      for (Element rel : Xml.children(xml(relPath), "Relationship"))
        if (!"External".equals(Xml.attr(rel, "TargetMode", ""))) {
          String type = Xml.attr(rel, "Type", "");
          String target = resolve(base, Xml.attr(rel, "Target", ""));
          if (type.endsWith("/worksheet")) targets.put(Xml.attr(rel, "Id", ""), target);
          if (type.endsWith("/styles")) stylePath = target;
          if (type.endsWith("/sharedStrings")) stringPath = target;
        }
      Element sheets = Xml.child(workbook, "sheets");
      if (sheets != null)
        for (Element sheet : Xml.children(sheets, "sheet")) {
          var path = targets.get(Xml.attr(sheet, "id", ""));
          if (path == null) throw new IOException("Missing worksheet relationship");
          names.add(Xml.attr(sheet, "name", "Sheet " + (names.size() + 1)));
          paths.add(path);
        }
    } else if (parts.containsKey("content.xml")) {
      Element body = Xml.child(xml("content.xml"), "body"),
          spreadsheet = body == null ? null : Xml.child(body, "spreadsheet");
      if (spreadsheet == null) throw new IOException("Not an OpenDocument spreadsheet");
      ods = Xml.children(spreadsheet, "table");
      for (Element sheet : ods) names.add(Xml.attr(sheet, "name", "Sheet " + (names.size() + 1)));
    } else throw new IOException("Not an XLSX, XLSM or ODS workbook");
    if (names.isEmpty() || names.size() > 1000)
      throw new IOException("Workbook needs between 1 and 1,000 sheets");
  }

  private String stylePath = "xl/styles.xml", stringPath = "xl/sharedStrings.xml";

  public List<String> sheets() {
    return Collections.unmodifiableList(names);
  }

  private Element xml(String name) throws IOException {
    var bytes = parts.get(name);
    if (bytes == null) throw new IOException("Missing workbook part: " + name);
    return Xml.parse(bytes);
  }

  private static String resolve(String base, String target) throws IOException {
    if (target.indexOf(':') >= 0 || target.indexOf('\\') >= 0)
      throw new IOException("External or invalid workbook relationship");
    String combined = target.startsWith("/") ? target.substring(1) : base + target;
    var segments = new ArrayList<String>();
    for (String segment : TextUtil.split(combined, '/')) {
      if (segment.isEmpty() || ".".equals(segment)) continue;
      if ("..".equals(segment)) {
        if (segments.isEmpty()) throw new IOException("Invalid workbook path");
        segments.remove(segments.size() - 1);
      } else segments.add(segment);
    }
    var out = new StringBuilder();
    for (String segment : segments) {
      if (out.length() > 0) out.append('/');
      out.append(segment);
    }
    return out.toString();
  }

  public MergeData read(String sheet, boolean headers) throws IOException {
    int index = -1;
    for (int i = 0; i < names.size(); i++)
      if (names.get(i).equals(sheet)) {
        index = i;
        break;
      }
    if (index < 0)
      for (int i = 0; i < names.size(); i++)
        if (names.get(i).equalsIgnoreCase(sheet.trim())) {
          index = i;
          break;
        }
    if (index < 0) throw new IOException("Unknown worksheet: " + sheet);
    return read(index, headers);
  }

  public MergeData read(int index, boolean headers) throws IOException {
    if (index < 0 || index >= names.size())
      throw new IOException("Worksheet index is outside the workbook");
    List<List<String>> rows = ods == null ? xlsx(paths.get(index)) : ods(ods.get(index));
    if (rows.isEmpty()) throw new IOException("Worksheet is empty");
    int width = 0;
    for (List<String> row : rows) width = Math.max(width, row.size());
    var fields = new ArrayList<String>();
    if (headers) {
      fields.addAll(rows.remove(0));
      while (fields.size() < width) fields.add("");
    } else for (int i = 0; i < width; i++) fields.add("Column " + (i + 1));
    return new MergeData(fields, rows);
  }

  private static String rich(Element root) {
    if (root == null) return "";
    var out = new StringBuilder();
    for (Element child : Xml.children(root, null)) {
      if ("t".equals(Xml.local(child))) out.append(Xml.text(child));
      else if ("r".equals(Xml.local(child))) out.append(rich(child));
    }
    return out.toString();
  }

  private static void add(List<List<String>> rows, List<String> row, int repeat)
      throws IOException {
    while (!row.isEmpty() && row.get(row.size() - 1).isEmpty()) row.remove(row.size() - 1);
    if (row.isEmpty()) return;
    if (repeat < 1
        || repeat > 100000
        || rows.size() + repeat > 100001
        || (long) (rows.size() + repeat) * row.size() > 1000000)
      throw new IOException("Recipient sheet exceeds row/cell limits");
    for (String value : row)
      if (value.length() > 32767)
        throw new IOException("Spreadsheet cell exceeds 32,767 characters");
    for (int i = 0; i < repeat; i++) rows.add(new ArrayList<String>(row));
  }

  private static int number(Element e, String key, int fallback) throws IOException {
    try {
      return Integer.parseInt(Xml.attr(e, key, "" + fallback));
    } catch (NumberFormatException ex) {
      throw new IOException("Invalid spreadsheet integer: " + key);
    }
  }

  private List<List<String>> xlsx(String path) throws IOException {
    var strings = new ArrayList<String>();
    if (parts.containsKey(stringPath))
      for (Element item : Xml.children(xml(stringPath), "si")) {
        strings.add(rich(item));
        if (strings.size() > 1000000) throw new IOException("Too many shared strings");
      }
    var formats = new ArrayList<String>();
    var custom = new HashMap<Integer, String>();
    if (parts.containsKey(stylePath)) {
      Element styles = xml(stylePath),
          nums = Xml.child(styles, "numFmts"),
          xfs = Xml.child(styles, "cellXfs");
      if (nums != null)
        for (Element f : Xml.children(nums, "numFmt"))
          custom.put(number(f, "numFmtId", 0), Xml.attr(f, "formatCode", ""));
      if (xfs != null)
        for (Element xf : Xml.children(xfs, "xf")) {
          int id = number(xf, "numFmtId", 0);
          formats.add(custom.containsKey(id) ? custom.get(id) : builtin(id));
        }
    }
    Element properties = Xml.child(workbook, "workbookPr");
    boolean date1904 =
        properties != null
            && Arrays.asList("1", "true").contains(Xml.attr(properties, "date1904", "0"));
    var rows = new ArrayList<List<String>>();
    Element data = Xml.child(xml(path), "sheetData");
    if (data == null) return rows;
    for (Element row : Xml.children(data, "row")) {
      var values = new ArrayList<String>();
      for (Element c : Xml.children(row, "c")) {
        String reference = Xml.attr(c, "r", "");
        int column = 0;
        for (int i = 0; i < reference.length() && Character.isLetter(reference.charAt(i)); i++) {
          column = column * 26 + Character.toUpperCase(reference.charAt(i)) - 'A' + 1;
          if (column > 1000) throw new IOException("Worksheet exceeds 1,000 columns");
        }
        if (column == 0) column = values.size() + 1;
        if (column <= values.size())
          throw new IOException("Duplicate or unordered worksheet cells");
        while (values.size() < column - 1) values.add("");
        String type = Xml.attr(c, "t", "");
        Element v = Xml.child(c, "v");
        String value = v == null ? "" : Xml.text(v);
        if ("s".equals(type)) {
          int at;
          try {
            at = Integer.parseInt(value);
          } catch (NumberFormatException ex) {
            throw new IOException("Invalid shared string index");
          }
          if (at < 0 || at >= strings.size()) throw new IOException("Missing shared string");
          value = strings.get(at);
        } else if ("inlineStr".equals(type)) value = rich(Xml.child(c, "is"));
        else if ("b".equals(type)) value = "1".equals(value) ? "TRUE" : "FALSE";
        else if ((type.isEmpty() || "n".equals(type)) && !value.isEmpty()) {
          int style = number(c, "s", 0);
          String format = style < formats.size() && style >= 0 ? formats.get(style) : "";
          value = numberText(value, format, date1904);
        }
        values.add(value);
      }
      add(rows, values, 1);
    }
    return rows;
  }

  private static String builtin(int id) {
    if (id == 22) return "yyyy-mm-dd hh:mm";
    if (id >= 14 && id <= 17 || id >= 27 && id <= 36 || id >= 50 && id <= 58) return "yyyy-mm-dd";
    if (id >= 18 && id <= 21 || id >= 45 && id <= 47) return "hh:mm:ss";
    return "";
  }

  private static String numberText(String raw, String format, boolean date1904) throws IOException {
    var pattern = new StringBuilder();
    boolean quote = false, bracket = false;
    for (int i = 0; i < format.length(); i++) {
      char c = Character.toLowerCase(format.charAt(i));
      if (c == '"') quote = !quote;
      else if (c == '[') bracket = true;
      else if (c == ']') bracket = false;
      else if (c == '\\') i++;
      else if (!quote && !bracket) pattern.append(c);
    }
    var p = pattern.toString();
    boolean date = p.indexOf('d') >= 0 || p.indexOf('y') >= 0,
        time = p.indexOf('h') >= 0 || p.indexOf('s') >= 0;
    if (!date && !time) return raw;
    double serial;
    try {
      serial = Double.parseDouble(raw);
    } catch (NumberFormatException ex) {
      throw new IOException("Invalid spreadsheet date");
    }
    if (Double.isNaN(serial) || Double.isInfinite(serial) || serial < 0 || serial > 2958465)
      throw new IOException("Spreadsheet date is outside supported range");
    long days = (long) serial, seconds = Math.round((serial - days) * 86400);
    if (seconds == 86400) {
      days++;
      seconds = 0;
    }
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
    long epoch = date1904 ? -2082844800000L : -2209075200000L;
    cal.setTime(new Date(epoch + (days - (!date1904 && days >= 60 ? 1 : 0)) * 86400000L));
    String d =
        !date1904 && days == 60
            ? "1900-02-29"
            : pad(cal.get(Calendar.YEAR), 4)
                + "-"
                + pad(cal.get(Calendar.MONTH) + 1, 2)
                + "-"
                + pad(cal.get(Calendar.DAY_OF_MONTH), 2);
    var t =
        pad((int) (seconds / 3600), 2)
            + ":"
            + pad((int) (seconds / 60 % 60), 2)
            + (seconds % 60 == 0 ? "" : ":" + pad((int) (seconds % 60), 2));
    return date ? d + (time ? " " + t : "") : t;
  }

  private static String pad(int n, int width) {
    var value = "" + n;
    while (value.length() < width) value = "0" + value;
    return value;
  }

  private List<List<String>> ods(Element sheet) throws IOException {
    var rows = new ArrayList<List<String>>();
    var pending = new ArrayList<Element>();
    pending.add(sheet);
    while (!pending.isEmpty()) {
      var e = pending.remove(pending.size() - 1);
      String tag = Xml.local(e);
      if (Arrays.asList("table", "table-row-group", "table-header-rows", "table-rows")
          .contains(tag)) {
        var children = Xml.children(e, null);
        for (int i = children.size() - 1; i >= 0; i--) pending.add(children.get(i));
        continue;
      }
      if (!"table-row".equals(tag)) continue;
      var row = new ArrayList<String>();
      for (Element c : Xml.children(e, null)) {
        if (!Arrays.asList("table-cell", "covered-table-cell").contains(Xml.local(c))) continue;
        String type = Xml.attr(c, "value-type", "");
        var value = "";
        if (!"covered-table-cell".equals(Xml.local(c))) {
          if ("float".equals(type)) value = Xml.attr(c, "value", "");
          else if ("boolean".equals(type))
            value = "true".equals(Xml.attr(c, "boolean-value", "false")) ? "TRUE" : "FALSE";
          else if ("date".equals(type)) value = Xml.attr(c, "date-value", "").replace('T', ' ');
          else if ("time".equals(type)) value = duration(Xml.attr(c, "time-value", ""));
          else {
            var out = new StringBuilder();
            for (Element p : Xml.children(c, "p")) {
              if (out.length() > 0) out.append('\n');
              out.append(Xml.text(p));
            }
            value = out.toString();
          }
        }
        int repeat = number(c, "number-columns-repeated", 1);
        // Blank trailing columns are common in saved spreadsheets; defer them until a value needs
        // them.
        if (repeat < 1) throw new IOException("Invalid repeated cell count");
        if (row.size() + repeat > 1000) {
          if (value.isEmpty()) break;
          throw new IOException("Worksheet exceeds 1,000 columns");
        }
        for (int i = 0; i < repeat; i++) row.add(value);
      }
      add(rows, row, number(e, "number-rows-repeated", 1));
    }
    return rows;
  }

  private static String duration(String value) throws IOException {
    if (!value.startsWith("PT")) return value;
    double seconds = 0;
    int start = 2;
    try {
      for (int i = 2; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c == 'H' || c == 'M' || c == 'S') {
          double n = Double.parseDouble(value.substring(start, i));
          seconds += n * (c == 'H' ? 3600 : c == 'M' ? 60 : 1);
          start = i + 1;
        }
      }
    } catch (NumberFormatException ex) {
      throw new IOException("Invalid spreadsheet time");
    }
    long n = Math.round(seconds);
    return pad((int) (n / 3600), 2)
        + ":"
        + pad((int) (n / 60 % 60), 2)
        + (n % 60 == 0 ? "" : ":" + pad((int) (n % 60), 2));
  }
}
