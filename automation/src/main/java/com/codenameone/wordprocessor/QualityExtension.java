package com.codenameone.wordprocessor;

import java.util.*;

/** Example extension: deterministic writing checks, without an online service. */
public final class QualityExtension implements Commands.Extension {
  public void register(Commands commands) {
    commands.register(
        "quality.inspect",
        "Find repeated spaces, long paragraphs and vague link labels",
        "",
        false,
        (session, args) -> {
          var issues = new ArrayList<Object>();
          for (int i = 0; i < session.document().blocks.size(); i++) {
            var b = session.document().blocks.get(i);
            if (!(b instanceof Paragraph)) continue;
            var p = (Paragraph) b;
            var text = p.text.toString();
            if (text.contains("  "))
              issues.add(
                  Json.object("block", i, "code", "repeated-space", "message", "Repeated spaces"));
            if (text.length() > 1000)
              issues.add(
                  Json.object(
                      "block",
                      i,
                      "code",
                      "long-paragraph",
                      "message",
                      "Paragraph exceeds 1,000 characters"));
            final int block = i;
            p.text.visit(
                (o, t, s) -> {
                  if (!s.link.isEmpty() && "click here".equalsIgnoreCase(t.trim()))
                    issues.add(
                        Json.object(
                            "block",
                            block,
                            "start",
                            o,
                            "code",
                            "link-label",
                            "message",
                            "Use a descriptive link label"));
                });
          }
          return issues;
        });
  }
}
