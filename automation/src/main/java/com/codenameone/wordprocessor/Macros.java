package com.codenameone.wordprocessor;

import java.util.*;

/** Portable parameterized command macros. No script evaluator or arbitrary filesystem access. */
public final class Macros {
  public interface Cancellation {
    boolean cancelled();
  }

  public static final class Preview {
    public final long baseRevision;
    public final String before, after;
    public final List<String> changes;
    private final Session result;
    private final Session owner;

    Preview(Session owner, Session result, long revision) {
      this.owner = owner;
      this.result = result;
      baseRevision = revision;
      before = owner.document().plainText();
      after = result.document().plainText();
      changes = DocumentDiff.describe(owner.document(), result.document());
    }

    public void apply(Session target) {
      if (target != owner) throw new IllegalArgumentException("Preview belongs to another session");
      target.applyPreview(result, baseRevision);
    }
  }

  private final Commands commands;

  public Macros(Commands c) {
    commands = c;
  }

  public Preview preview(
      Session session,
      Map<String, Object> macro,
      Map<String, String> parameters,
      Cancellation cancel) {
    Session fork = session.fork();
    run(fork, macro, parameters, cancel);
    return new Preview(session, fork, session.revision());
  }

  public void run(
      final Session session,
      Map<String, Object> macro,
      final Map<String, String> parameters,
      final Cancellation cancel) {
    if (!(macro.get("version") instanceof Number)
        || ((Number) macro.get("version")).doubleValue() != 1)
      throw new IllegalArgumentException("Unsupported macro version");
    if (!(macro.get("steps") instanceof List))
      throw new IllegalArgumentException("Macro steps must be an array");
    final List<?> steps = (List<?>) macro.get("steps");
    if (steps.size() > 1000) throw new IllegalArgumentException("Macro exceeds 1,000 commands");
    session.transact(
        s -> {
          for (Object item : steps) {
            if (cancel != null && cancel.cancelled())
              throw new IllegalStateException("Macro cancelled");
            if (!(item instanceof Map)) throw new IllegalArgumentException("Invalid macro step");
            Map<?, ?> step = (Map<?, ?>) item;
            String id = (String) step.get("command");
            if ("edit.undo".equals(id) || "edit.redo".equals(id))
              throw new IllegalArgumentException("History commands cannot run inside a macro");
            Object raw = step.get("args");
            if (!(raw instanceof Map))
              throw new IllegalArgumentException("Macro args must be an object");
            commands.execute(s, id, (Map<String, Object>) expand(raw, parameters));
          }
        });
  }

  private Object expand(Object value, Map<String, String> params) {
    if (value instanceof String) {
      String source = (String) value;
      StringBuilder b = new StringBuilder();
      int offset = 0;
      while (true) {
        int a = source.indexOf("${", offset);
        if (a < 0) {
          b.append(source.substring(offset));
          break;
        }
        int end = source.indexOf('}', a + 2);
        if (end < 0) throw new IllegalArgumentException("Unclosed macro parameter");
        String key = source.substring(a + 2, end);
        if (!params.containsKey(key))
          throw new IllegalArgumentException("Missing macro parameter: " + key);
        b.append(source.substring(offset, a)).append(params.get(key));
        offset = end + 1;
      }
      return b.toString();
    }
    if (value instanceof Map) {
      Map<String, Object> out = new LinkedHashMap<String, Object>();
      for (Object item : ((Map) value).entrySet()) {
        Map.Entry e = (Map.Entry) item;
        out.put((String) e.getKey(), expand(e.getValue(), params));
      }
      return out;
    }
    if (value instanceof List) {
      List<Object> out = new ArrayList<Object>();
      for (Object v : (List) value) out.add(expand(v, params));
      return out;
    }
    return value;
  }
}
