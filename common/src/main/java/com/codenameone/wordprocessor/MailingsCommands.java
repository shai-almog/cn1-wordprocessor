package com.codenameone.wordprocessor;

import java.util.*;

/** Mailings commands shared by the ribbon, CLI, macros and the native MCP server. */
public final class MailingsCommands implements Commands.Extension {
  private static String text(Map<String, Object> a, String key, String fallback) {
    return a.containsKey(key) ? (String) a.get(key) : fallback;
  }

  private static boolean bool(Map<String, Object> a, String key, boolean fallback) {
    return a.containsKey(key) ? (Boolean) a.get(key) : fallback;
  }

  private static int integer(Map<String, Object> a, String key, int fallback) {
    return a.containsKey(key) ? ((Number) a.get(key)).intValue() : fallback;
  }

  public static Map<String, Object> state(Session s) {
    var d = s.mergeData();
    return Json.object(
        "kind",
        d.kind,
        "fields",
        d.fields,
        "records",
        d.rows.size(),
        "record",
        d.rows.isEmpty() ? 0 : d.record + 1,
        "preview",
        d.preview,
        "highlight",
        d.highlight,
        "sourceRecords",
        d.sourceRows.size(),
        "sourceIndices",
        d.sourceIndices,
        "excluded",
        new ArrayList<Integer>(d.excluded),
        "sortField",
        d.sortField,
        "descending",
        d.descending,
        "filterField",
        d.filterField,
        "filterOperator",
        d.filterOperator,
        "filterValue",
        d.filterValue);
  }

  private static String cell(Object value) {
    if (value == null) return "";
    if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean))
      throw new IllegalArgumentException("Recipient values must be text, numbers or booleans");
    return value.toString();
  }

  private static Object recipients(Session s, Map<String, Object> a) {
    if (a.containsKey("data")) {
      if (a.containsKey("csv") || a.containsKey("rows"))
        throw new IllegalArgumentException("Supply only one recipient source");
      try {
        var workbook =
            new RecipientWorkbook(
                com.codename1.util.Base64.decode(((String) a.get("data")).getBytes("UTF-8")));
        if (!a.containsKey("sheet") && !a.containsKey("sheetIndex") && workbook.sheets().size() > 1)
          return Json.object("sheets", workbook.sheets(), "chooseSheet", true);
        if (a.containsKey("sheet") && a.containsKey("sheetIndex"))
          throw new IllegalArgumentException("Choose a sheet name or index");
        MergeData data =
            a.containsKey("sheet")
                ? workbook.read((String) a.get("sheet"), bool(a, "headers", true))
                : workbook.read(integer(a, "sheetIndex", 1) - 1, bool(a, "headers", true));
        MailMerge.recipients(s, data);
        return state(s);
      } catch (java.io.IOException ex) {
        throw new IllegalArgumentException(ex.getMessage());
      }
    }
    if (a.containsKey("csv") == a.containsKey("rows"))
      throw new IllegalArgumentException("Supply CSV or recipient rows");
    MergeData data;
    if (a.containsKey("csv")) data = MergeData.csv((String) a.get("csv"), bool(a, "headers", true));
    else {
      var fields = new ArrayList<String>();
      if (a.containsKey("fields"))
        for (Object field : (List<?>) a.get("fields")) {
          if (!(field instanceof String))
            throw new IllegalArgumentException("Recipient field names must be text");
          fields.add((String) field);
        }
      List<?> input = (List<?>) a.get("rows");
      if (fields.isEmpty())
        for (Object row : input) {
          if (!(row instanceof Map))
            throw new IllegalArgumentException("Array rows require field names");
          for (Object key : ((Map<?, ?>) row).keySet()) {
            if (!(key instanceof String))
              throw new IllegalArgumentException("Recipient field names must be text");
            if (!fields.contains(key)) fields.add((String) key);
          }
        }
      var rows = new ArrayList<List<String>>();
      for (Object item : input) {
        var row = new ArrayList<String>();
        if (item instanceof Map<?, ?> map) {
          for (Object key : map.keySet())
            if (!fields.contains(key))
              throw new IllegalArgumentException("Unknown recipient column: " + key);
          for (String field : fields) row.add(cell(map.get(field)));
        } else if (item instanceof List<?> list) for (Object value : list) row.add(cell(value));
        else throw new IllegalArgumentException("Recipient rows must be arrays or objects");
        rows.add(row);
      }
      data = new MergeData(fields, rows);
    }
    MailMerge.recipients(s, data);
    return state(s);
  }

  public void register(Commands c) {
    c.edit(
        "mailings.envelopes", "delivery?:string,return?:string,size?:string,width?:number,height?:number,add?:boolean",
        "Create an envelope, optionally before the current document", (s, a) -> {
          if (a.containsKey("width") != a.containsKey("height"))
            throw new IllegalArgumentException("Both envelope dimensions are required");
          if (a.containsKey("width"))
            MailingSheets.envelope(
                s,
                text(a, "delivery", null),
                text(a, "return", ""),
                ((Number) a.get("width")).floatValue(),
                ((Number) a.get("height")).floatValue(),
                bool(a, "add", false));
          else
            MailingSheets.envelope(
                s,
                text(a, "delivery", null),
                text(a, "return", ""),
                text(a, "size", "Envelope #10"),
                bool(a, "add", false));
          return Json.object(
              "width",
              s.document().sectionAt(0).raw.page.width,
              "height",
              s.document().sectionAt(0).raw.page.height);
        });
    c.edit(
        "mailings.labels", "text?:string,product?:string,rows?:integer,cols?:integer,fromRecipients?:boolean,single?:object",
        "Create label sheets from text or all selected recipients", (s, a) -> {
          int row = 0, column = 0;
          if (a.containsKey("single")) {
            Map<?, ?> single = (Map<?, ?>) a.get("single");
            Object r = single.get("row"), col = single.get("col");
            if (!(r instanceof Number)
                || !(col instanceof Number)
                || ((Number) r).doubleValue() != ((Number) r).intValue()
                || ((Number) col).doubleValue() != ((Number) col).intValue())
              throw new IllegalArgumentException("Label position requires integer row and col");
            row = ((Number) r).intValue();
            column = ((Number) col).intValue();
            if (row < 1 || column < 1)
              throw new IllegalArgumentException("Label positions start at 1");
          }
          int filled =
              MailingSheets.labels(
                  s,
                  text(a, "product", "letter-3x10"),
                  text(a, "text", "Name\nAddress\nCity, ST 00000"),
                  bool(
                      a, "fromRecipients", !a.containsKey("text") && !s.mergeData().rows.isEmpty()),
                  integer(a, "rows", 0),
                  integer(a, "cols", 0),
                  row,
                  column);
          var table = (Table) s.document().blocks.get(0);
          var layout = c.layoutEngine().layout(s.document(), s.revision());
          for (LayoutEngine.Page page : layout.pages)
            for (LayoutEngine.Box box : page.boxes)
              if (box.height > table.format.minHeight(box.row) + .1f)
                throw new IllegalArgumentException(
                    "Label text exceeds the label height; shorten it or choose a taller product");
          return Json.object(
              "filled",
              filled,
              "sheets",
              s.document().sectionRanges().size(),
              "product",
              text(a, "product", "letter-3x10"));
        });
    c.query("mailings.inspect", "",
        "Inspect current recipient and merge state", (s, a) -> state(s));
    c.edit("mailings.start", "kind:string",
        "Choose merged document type", (s, a) -> {
          var d = s.mergeData();
          s.mergeData(d.options((String) a.get("kind"), d.preview, d.highlight, d.record));
          return state(s);
        });
    c.edit(
        "mailings.recipients", "csv?:string,headers?:boolean,rows?:array,fields?:array,data?:string,sheet?:string,sheetIndex?:integer",
        "Load a CSV or typed recipient list", MailingsCommands::recipients);
    c.edit(
        "mailings.editRecipients", "csv?:string,headers?:boolean,rows?:array,fields?:array,data?:string,sheet?:string,sheetIndex?:integer",
        "Read or replace the current recipient list", (s, a) -> {
          if (!a.isEmpty()) return recipients(s, a);
          return Json.object(
              "fields",
              s.mergeData().fields,
              "rows",
              s.mergeData().rows,
              "sourceRows",
              s.mergeData().sourceRows,
              "sourceIndices",
              s.mergeData().sourceIndices);
        });
    c.edit("mailings.insertField", "field:string",
        "Insert a recipient field", (s, a) -> {
          var field = (String) a.get("field");
          if (field.trim().isEmpty()) throw new IllegalArgumentException("Enter a field name");
          MailMerge.insert(s, "MERGEFIELD " + MailMerge.quote(field));
          return null;
        });
    c.edit("mailings.addressBlock", "",
        "Insert a mailing address block", (s, a) -> {
          MailMerge.insert(s, "ADDRESSBLOCK");
          return null;
        });
    c.edit("mailings.greetingLine", "",
        "Insert a personalized greeting", (s, a) -> {
          MailMerge.insert(s, "GREETINGLINE");
          return null;
        });
    c.edit(
        "mailings.rules", "rule:string,field?:string,operator?:string,value?:string,then?:string,else?:string",
        "Insert an IF, NEXT, NEXTIF, SKIPIF or MERGEREC field", (s, a) -> {
          MailMerge.insert(
              s,
              MailMerge.rule(
                  text(a, "rule", ""),
                  text(a, "field", ""),
                  text(a, "operator", "="),
                  text(a, "value", ""),
                  text(a, "then", ""),
                  text(a, "else", "")));
          return null;
        });
    c.edit(
        "mailings.manageRecipients", "sortField?:string,descending?:boolean,filterField?:string,filterOperator?:string,filterValue?:string,excluded?:array",
        "Sort, filter and exclude recipients without deleting source rows", (s, a) -> {
          var d = s.mergeData();
          Collection<Integer> excluded = d.excluded;
          if (a.containsKey("excluded")) {
            excluded = new ArrayList<>();
            for (Object item : (List<?>) a.get("excluded")) {
              if (!(item instanceof Number)
                  || ((Number) item).doubleValue() != ((Number) item).intValue())
                throw new IllegalArgumentException("Excluded source indices must be integers");
              excluded.add(((Number) item).intValue());
            }
          }
          var next =
              d.manage(
                  text(a, "sortField", d.sortField),
                  bool(a, "descending", d.descending),
                  text(a, "filterField", d.filterField),
                  text(a, "filterOperator", d.filterOperator),
                  text(a, "filterValue", d.filterValue),
                  excluded);
          s.mergeData(next);
          MailMerge.refresh(s);
          return state(s);
        });
    c.edit("mailings.matchFields", "mapping?:object",
        "Read or override address field mappings", (s, a) -> {
          if (a.containsKey("mapping")) {
            var map = new LinkedHashMap<String, String>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) a.get("mapping")).entrySet()) {
              if (!(e.getKey() instanceof String) || !(e.getValue() instanceof String))
                throw new IllegalArgumentException("Field mappings must be text");
              map.put((String) e.getKey(), (String) e.getValue());
            }
            s.mergeData(s.mergeData().mapping(map));
            MailMerge.refresh(s);
          }
          return Json.object(
              "fields", s.mergeData().fields, "mapping", s.mergeData().mappedFields());
        });
    c.edit("mailings.highlightFields", "value?:boolean",
        "Toggle merge field shading", (s, a) -> {
          var d = s.mergeData();
          s.mergeData(d.options(d.kind, d.preview, bool(a, "value", !d.highlight), d.record));
          return state(s);
        });
    c.edit("mailings.preview", "value?:boolean,record?:integer",
        "Toggle personalized result preview", (s, a) -> {
          MailMerge.preview(
              s,
              bool(a, "value", !s.mergeData().preview),
              integer(a, "record", s.mergeData().record + 1) - 1);
          return state(s);
        });
    c.edit("mailings.next", "",
        "Preview next recipient", (s, a) -> {
          MailMerge.preview(
              s, true, Math.min(s.mergeData().rows.size() - 1, s.mergeData().record + 1));
          return state(s);
        });
    c.edit("mailings.previous", "",
        "Preview previous recipient", (s, a) -> {
          MailMerge.preview(s, true, Math.max(0, s.mergeData().record - 1));
          return state(s);
        });
    c.edit("mailings.findRecipient", "text:string",
        "Find a recipient containing text", (s, a) -> {
          var query = ((String) a.get("text")).toLowerCase();
          for (int i = 0; i < s.mergeData().rows.size(); i++)
            for (String value : s.mergeData().rows.get(i))
              if (value.toLowerCase().indexOf(query) >= 0) {
                MailMerge.preview(s, true, i);
                return state(s);
              }
          throw new IllegalArgumentException("No matching recipient");
        });
    c.query("mailings.checkErrors", "",
        "Check template fields against recipient columns", (s, a) -> {
          var missing = MailMerge.errors(s.document(), s.mergeData());
          return Json.object(
              "ok",
              missing.isEmpty() && !s.mergeData().rows.isEmpty(),
              "unknownFields",
              missing,
              "records",
              s.mergeData().rows.size());
        });
    c.edit("mailings.finish", "from?:integer,to?:integer",
        "Create an editable merged document; undo restores the template", (s, a) ->
            Json.object(
                "records",
                MailMerge.finish(
                    s, integer(a, "from", 1), integer(a, "to", s.mergeData().rows.size()))));
  }
}
