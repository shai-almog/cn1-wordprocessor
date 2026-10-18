package com.codenameone.wordprocessor;

import com.codename1.ai.Tool;
import com.codename1.io.Properties;
import com.codename1.mcp.MCP;
import com.codename1.ui.Display;
import java.io.InputStream;
import java.util.*;

/** Opt-in live diagnostics using Codename One's built-in MCP server and UI tools. */
final class WordMcp {
  interface Context {
    Session session();

    Map<String, Object> layout();

    void beforeCommand();

    void changed();
  }

  private static boolean started;

  static void start(Commands commands, Context context) throws Exception {
    if (started) return;
    var config = new Properties();
    try (var in =
        Display.getInstance()
            .getResourceAsStream(WordMcp.class, "/wordprocessor-agent.properties")) {
      if (in == null) return; // Normal builds have no listener or published domain tools.
      config.load(in);
    }
    int port = Integer.parseInt(config.getProperty("port"));
    if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid agent-debug port");
    final var live = new LiveCommands(commands);
    MCP.getServer().setServerInfo("CN1 Word Processor development", "1");
    MCP.addTool(
        new Tool(
            "word_snapshot",
            """
            Inspect the live document, selection and stateId. Optional xml includes native model\
             XML.""",
            "{\"type\":\"object\",\"properties\":{\"xml\":{\"type\":\"boolean\"}}}",
            json ->
                onEdt(
                    () -> {
                      var state = live.snapshot(context.session());
                      if (Boolean.TRUE.equals(Json.parse(json).get("xml")))
                        state.put("xml", DocumentXml.write(context.session().document()));
                      return state;
                    })));
    MCP.addTool(
        new Tool(
            "word_layout",
            """
            Inspect visible native editor cell and line geometry and selection; bounds are display\
             pixels; line heights and clipping heights are also reported in points.""",
            "{\"type\":\"object\",\"properties\":{}}",
            json -> onEdt(context::layout)));
    MCP.addTool(
        new Tool(
            "word_load",
            """
            Replace the live document with native model XML and restore a recorded selection,\
             cell, review author and tracking state (clears history). Used to replay recorded cases.""",
            "{\"type\":\"object\",\"properties\":{\"xml\":{\"type\":\"string\"},\"state\":{\"type\":\"object\"}},\"required\":[\"xml\"]}",
            json ->
                onEdt(
                    () -> {
                      var args = Json.parse(json);
                      context.beforeCommand();
                      var session = context.session();
                      session.leaveCell();
                      session.loadDocument(DocumentXml.read((String) args.get("xml")));
                      Map<String, Object> st = (Map<String, Object>) args.get("state");
                      if (st != null) {
                        if (st.get("reviewAuthor") instanceof String)
                          session.reviewAuthor((String) st.get("reviewAuthor"));
                        session.trackFormatting(Boolean.TRUE.equals(st.get("trackFormatting")));
                        int cell = num(st, "cellBlock");
                        if (cell >= 0)
                          session.enterCell(cell, num(st, "cellRow"), num(st, "cellColumn"));
                        session.selectRange(
                            num(st, "anchorBlock"), num(st, "anchor"), num(st, "block"), num(st, "caret"));
                        Map<String, Object> cs = (Map<String, Object>) st.get("cellSelection");
                        if (cs != null)
                          session.selectCells(
                              num(cs, "block"),
                              num(cs, "anchorRow"),
                              num(cs, "anchorColumn"),
                              num(cs, "focusRow"),
                              num(cs, "focusColumn"));
                      }
                      context.changed();
                      return live.snapshot(session);
                    })));
    MCP.addTool(
        new Tool(
            "word_commands",
            "List the command schemas used by the live editor.",
            "{\"type\":\"object\",\"properties\":{}}",
            json -> {
              var list = new ArrayList<Object>();
              for (Commands.Descriptor d : commands.descriptors())
                list.add(
                    Json.object("id", d.id, "description", d.description, "arguments", d.schema()));
              return Json.write(list);
            }));
    MCP.addTool(
        new Tool(
            "word_command",
            """
            Execute one editor command against the observed stateId. Uses the live document and\
             its normal undo history; refuses stale state and active dialogs.""",
            "{\"type\":\"object\",\"properties\":{\"stateId\":{\"type\":\"string\"},\"command\":{\"type\":\"string\"},\"args\":{\"type\":\"object\"}},\"required\":[\"stateId\",\"command\"]}",
            json ->
                onEdt(
                    () -> {
                      var args = Json.parse(json);
                      context.beforeCommand();
                      var session = context.session();
                      var before = live.stateId(session);
                      var values = args.get("args");
                      if (values != null && !(values instanceof Map))
                        throw new IllegalArgumentException("args must be an object");
                      var response =
                          live.execute(
                              session,
                              (String) args.get("stateId"),
                              (String) args.get("command"),
                              values == null
                                  ? Collections.<String, Object>emptyMap()
                                  : (Map<String, Object>) values);
                      if (!before.equals(live.stateId(session))) context.changed();
                      return response;
                    })));
    // The resource is present only in the explicitly requested local agent-debug profile.
    // Native mac-source Release is used for review, so this controlled build opts in here.
    MCP.setAllowOnReleaseBuilds(true);
    try {
      MCP.startSocketServer(port);
      started = true;
    } catch (RuntimeException ex) {
      MCP.setAllowOnReleaseBuilds(false);
      throw ex;
    }
  }

  private static int num(Map<String, Object> m, String k) {
    var v = m.get(k);
    return v instanceof Number n ? n.intValue() : -1;
  }

  private interface Action {
    Object run() throws Exception;
  }

  private static String onEdt(Action action) throws Exception {
    final Object[] result = new Object[1];
    final Exception[] error = new Exception[1];
    Runnable run =
        () -> {
          try {
            result[0] = action.run();
          } catch (Exception ex) {
            error[0] = ex;
          }
        };
    if (Display.getInstance().isEdt()) run.run();
    else Display.getInstance().callSeriallyAndWait(run);
    if (error[0] != null) throw error[0];
    return Json.write(result[0]);
  }

  static void stop() {
    if (started) {
      MCP.stop();
      started = false;
      MCP.setAllowOnReleaseBuilds(false);
    }
  }
}
