package com.codenameone.wordprocessor;

import com.codename1.io.JSONParser;
import java.io.*;
import java.util.*;

public final class Json {
  private Json() {}

  public static Map<String, Object> parse(String json) throws IOException {
    if (json.length() > 8 * 1024 * 1024) throw new IOException("JSON exceeds size limit");
    var p = new JSONParser();
    p.setUseBooleanInstance(true);
    p.setUseLongsInstance(true);
    return p.parseJSON(new StringReader(json));
  }

  public static Map<String, Object> object(Object... kv) {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  public static String write(Object value) {
    if (value == null) return "null";
    if (value instanceof String s) {
      var b = new StringBuilder("\"");
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '\"' || c == '\\') b.append('\\').append(c);
        else if (c < ' ') b.append("\\u" + TextUtil.padded(c, 16, 4));
        else b.append(c);
      }
      return b.append('\"').toString();
    }
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map map) {
      var b = new StringBuilder("{");
      boolean comma = false;
      for (Object entry : map.entrySet()) {
        var e = (Map.Entry) entry;
        if (comma) b.append(',');
        comma = true;
        b.append(write(e.getKey().toString())).append(':').append(write(e.getValue()));
      }
      return b.append('}').toString();
    }
    if (value instanceof Iterable iterable) {
      var b = new StringBuilder("[");
      boolean comma = false;
      for (Object v : iterable) {
        if (comma) b.append(',');
        comma = true;
        b.append(write(v));
      }
      return b.append(']').toString();
    }
    throw new IllegalArgumentException("Not JSON: " + value.getClass());
  }
}
