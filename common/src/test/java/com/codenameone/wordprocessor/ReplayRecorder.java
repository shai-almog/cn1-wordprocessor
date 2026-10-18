package com.codenameone.wordprocessor;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

/**
 * Records successful outermost command calls (document XML before/after, selection and
 * arguments) as JSON lines when -Dreplay.out is set, so scripts/native-certify.py can replay
 * them in the native app and compare. Inactive otherwise.
 */
public class ReplayRecorder extends RunListener {
  private static final int PER_COMMAND = 4;
  private final Map<String, Integer> counts = new HashMap<>();
  private Writer out;
  private String test = "";

  @Override
  public void testRunStarted(Description d) throws Exception {
    String path = System.getProperty("replay.out");
    if (path == null || path.isEmpty()) return;
    out = new OutputStreamWriter(new FileOutputStream(path, true), "UTF-8");
    Commands.tracer =
        new Commands.Tracer() {
          public Object begin(Session s, String id, Map<String, Object> args) {
            var n = counts.get(id);
            if (n != null && n >= PER_COMMAND) return null;
            try {
              var rec = new LinkedHashMap<String, Object>();
              rec.put("test", test);
              rec.put("command", id);
              rec.put("args", Json.parse(com.codename1.io.JSONParser.toJson(args)));
              rec.put("before", DocumentXml.write(s.document()));
              rec.put("state", state(s));
              return rec;
            } catch (Exception ex) {
              return null; // arguments that are not JSON cannot be replayed over MCP
            }
          }

          public void end(Object token, Session s, Object result, Throwable error) {
            if (token == null || error != null) return;
            Map<String, Object> rec = (Map<String, Object>) token;
            var id = (String) rec.get("command");
            try {
              rec.put("after", DocumentXml.write(s.document()));
              rec.put("stateAfter", state(s));
              out.write(com.codename1.io.JSONParser.toJson(rec));
              out.write('\n');
              var n = counts.get(id);
              counts.put(id, n == null ? 1 : n + 1);
            } catch (Exception ex) {
              // Unserializable results are skipped.
            }
          }
        };
  }

  static Map<String, Object> state(Session s) {
    var x = s.selection();
    var m = new LinkedHashMap<String, Object>();
    m.put("anchorBlock", x.anchorBlock);
    m.put("anchor", x.anchor);
    m.put("block", x.block);
    m.put("caret", x.caret);
    m.put("cellBlock", s.cellBlock());
    m.put("cellRow", s.cellRow());
    m.put("cellColumn", s.cellColumn());
    var c = s.cellSelection();
    if (c != null) {
      var cs = new LinkedHashMap<String, Object>();
      cs.put("block", c.block);
      cs.put("anchorRow", c.anchorRow);
      cs.put("anchorColumn", c.anchorColumn);
      cs.put("focusRow", c.focusRow);
      cs.put("focusColumn", c.focusColumn);
      m.put("cellSelection", cs);
    }
    m.put("reviewAuthor", s.reviewAuthor());
    m.put("trackFormatting", s.trackFormatting());
    return m;
  }

  @Override
  public void testStarted(Description d) {
    test = d.getClassName() + "." + d.getMethodName();
  }

  @Override
  public void testRunFinished(org.junit.runner.Result r) throws Exception {
    if (out != null) out.close();
    Commands.tracer = null;
  }
}
