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
}
