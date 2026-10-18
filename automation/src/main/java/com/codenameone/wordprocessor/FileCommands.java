package com.codenameone.wordprocessor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Backstage commands that work on the session alone: properties, protection, the document
 * inspector, the accessibility and compatibility checkers and PDF export. Exports return the bytes
 * as base64; the app decides where they go.
 */
final class FileCommands implements Commands.Extension {
  public void register(final Commands c) {
    var spec = new StringBuilder();
    for (String key : DocumentProperties.KEYS) spec.append(',').append(key).append("?:string");
    c.register(
        "file.properties",
        "Read or set summary properties (title, author, subject, keywords, description, category, company, manager)",
        spec.toString().substring(1),
        true,
        (s, a) -> {
          var p = s.document().properties;
          for (Map.Entry<String, Object> e : a.entrySet()) p = p.with(e.getKey(), (String) e.getValue());
          if (!p.equals(s.document().properties)) s.replaceDocument(s.document().properties(p));
          return properties(s.document().properties);
        });
    c.register(
        "file.protect",
        "Restrict editing: none, readOnly, comments or trackedChanges",
        "mode:string",
        true,
        (s, a) -> {
          String mode = Commands.str(a, "mode");
          if (!DocumentProperties.PROTECTION.contains(mode))
            throw new IllegalArgumentException("Unknown protection mode: " + mode);
          var keep = s.selection();
          var d = s.document();
          d = d.properties(d.properties.protection(mode));
          if ("trackedChanges".equals(mode)) d = d.trackChanges(true);
          s.replaceDocument(d);
          s.selectRange(keep.anchorBlock, keep.anchor, keep.block, keep.caret);
          return mode;
        });
    c.register(
        "review.restrict",
        "Restrict editing (same as File > Protect): none, readOnly, comments or trackedChanges",
        "mode:string",
        true,
        (s, a) -> c.execute(s, "file.protect", a));
    c.register(
        "file.inspect",
        "Document inspector: report comments, revisions, hidden text, properties and headers; remove listed kinds",
        "remove?:array",
        true,
        (s, a) -> {
          @SuppressWarnings("unchecked")
          List<Object> remove =
              a.containsKey("remove") ? (List<Object>) a.get("remove") : Collections.emptyList();
          var before = inspect(s.document());
          var d = s.document();
          if (remove.contains("comments")) d = d.comments(Collections.<ReviewComment>emptyList());
          if (remove.contains("properties"))
            d = d.properties(DocumentProperties.EMPTY.protection(d.properties.protection));
          if (d != s.document()) {
            var keep = s.selection();
            s.replaceDocument(d);
            s.selectRange(keep.anchorBlock, keep.anchor, keep.block, keep.caret);
          }
          if (remove.contains("hidden")) removeHidden(s);
          return Json.object("found", before, "after", inspect(s.document()));
        });
    c.register(
        "file.accessibility",
        "Accessibility checker: missing alt text, heading order, table headers, contrast, title and link text",
        "",
        false,
        (s, a) -> accessibility(s.document()));
    c.register(
        "file.compatibility",
        "Compatibility checker: features that change when saved as Word (.docx)",
        "",
        false,
        (s, a) -> {
          var out = new ByteArrayOutputStream();
          try {
            Docx.write(s.document(), out);
            Docx.Opened back = Docx.read(new ByteArrayInputStream(out.toByteArray()));
            var issues = new ArrayList<Object>();
            for (String w : back.warnings) issues.add(w);
            if (!s.document().lastSection.decor.watermark.isEmpty())
              issues.add("Watermark is not saved to .docx");
            return Json.object("format", "docx", "issues", issues);
          } catch (IOException ex) {
            throw new IllegalStateException("Compatibility check failed: " + ex.getMessage(), ex);
          }
        });
    c.register(
        "file.exportPdf",
        "Export the document as PDF (base64)",
        "",
        false,
        (s, a) -> {
          try {
            byte[] pdf = PdfExporter.write(OfficeCommands.printable(s.document()));
            return Json.object(
                "bytes", pdf.length,
                "base64", new String(com.codename1.util.Base64.encodeNoNewline(pdf)));
          } catch (IOException ex) {
            throw new IllegalStateException("PDF export failed: " + ex.getMessage(), ex);
          }
        });
  }

  private static Map<String, Object> properties(DocumentProperties p) {
    var out = Json.object("protection", p.protection);
    for (String key : DocumentProperties.KEYS) out.put(key, p.get(key));
    return out;
  }

  private static Map<String, Object> inspect(Document d) {
    final int[] revisions = {0}, hidden = {0};
    for (Block b : d.blocks) {
      if (!(b instanceof Paragraph)) continue;
      var p = (Paragraph) b;
      if (p.breakRevision != null || p.formatRevision != null) revisions[0]++;
      p.text.visit(
          (o, t, style) -> {
            if (style.revision != null || style.formatRevision != null) revisions[0]++;
            if (style.characters.hidden) hidden[0] += t.length();
          });
    }
    int props = 0;
    for (String key : DocumentProperties.KEYS) if (!d.properties.get(key).isEmpty()) props++;
    boolean furniture = hasText(d.furniture);
    for (Section section : d.sections.values()) furniture |= hasText(section.furniture);
    return Json.object(
        "comments", d.comments.size(),
        "revisions", revisions[0],
        "hiddenCharacters", hidden[0],
        "properties", props,
        "headersFooters", furniture,
        "bookmarks", d.bookmarks.size(),
        "watermark", !d.lastSection.decor.watermark.isEmpty());
  }

  private static boolean hasText(PageFurniture f) {
    for (Sequence<Paragraph> story : f.stories.values())
      for (Paragraph p : story) if (!p.text.plainText().trim().isEmpty()) return true;
    return false;
  }

  /** Deletes hidden runs in body paragraphs, last first so earlier offsets stay valid. */
  private static void removeHidden(Session s) {
    var d = s.document();
    for (int i = d.blocks.size() - 1; i >= 0; i--) {
      if (!(d.blocks.get(i) instanceof Paragraph)) continue;
      final var runs = new ArrayList<int[]>();
      ((Paragraph) d.blocks.get(i))
          .text.visit(
              (o, t, style) -> {
                if (style.characters.hidden) runs.add(new int[] {o, o + t.length()});
              });
      for (int k = runs.size() - 1; k >= 0; k--) {
        s.select(i, runs.get(k)[0], runs.get(k)[1]);
        s.replace("");
      }
    }
  }

  private static Map<String, Object> accessibility(Document d) {
    var issues = new ArrayList<Object>();
    if (d.properties.get("title").isEmpty())
      issues.add(issue("tip", -1, "missing-title", "Set a document title in File > Properties"));
    int page = d.lastSection.decor.pageColor < 0 ? 0xffffff : d.lastSection.decor.pageColor;
    int lastHeading = 0;
    for (int i = 0; i < d.blocks.size(); i++) {
      var b = d.blocks.get(i);
      if (b instanceof Picture picture) {
        if (picture.alt.trim().isEmpty())
          issues.add(issue("error", i, "missing-alt-text", "Picture has no alternative text"));
      } else if (b instanceof Table t) {
        if (t.format.headerRows == 0)
          issues.add(issue("warning", i, "table-header", "Table has no header row"));
      } else if (b instanceof Paragraph p) {
        final int block = i;
        int level =
            p.style.startsWith("Heading") && p.style.length() == 8
                ? p.style.charAt(7) - '0'
                : 0;
        if (level > 0) {
          if (p.text.plainText().trim().isEmpty())
            issues.add(issue("warning", i, "empty-heading", "Heading is empty"));
          if (level > lastHeading + 1)
            issues.add(
                issue(
                    "warning",
                    i,
                    "heading-order",
                    "Heading " + level + " follows heading " + lastHeading));
          lastHeading = level;
        }
        final int background = p.format.shading >= 0 ? p.format.shading : page;
        final boolean[] reported = {false, false};
        p.text.visit(
            (o, t, style) -> {
              int bg = style.background >= 0 && style.background != 0xffffff ? style.background : background;
              if (!reported[0] && !t.trim().isEmpty() && contrast(style.color, bg) < 4.5) {
                reported[0] = true;
                issues.add(
                    issue("warning", block, "contrast", "Text contrast is below 4.5:1"));
              }
              if (!reported[1] && !style.link.isEmpty()
                  && ("click here".equalsIgnoreCase(t.trim()) || "here".equalsIgnoreCase(t.trim())
                      || style.link.equals(t.trim()))) {
                reported[1] = true;
                issues.add(
                    issue("tip", block, "link-text", "Link text does not describe its target"));
              }
            });
        if (p.text.plainText().contains("    ") || p.text.plainText().contains("\t\t\t"))
          issues.add(
              issue("tip", i, "repeated-blanks", "Repeated blank characters used for layout"));
      }
    }
    int errors = 0;
    for (Object o : issues) if ("error".equals(((Map<?, ?>) o).get("severity"))) errors++;
    return Json.object("errors", errors, "issues", issues);
  }

  private static Map<String, Object> issue(String severity, int block, String code, String message) {
    return Json.object("severity", severity, "block", block, "code", code, "message", message);
  }

  /** WCAG 2 contrast ratio. */
  static double contrast(int a, int b) {
    double la = luminance(a), lb = luminance(b);
    return (Math.max(la, lb) + .05) / (Math.min(la, lb) + .05);
  }

  private static double luminance(int rgb) {
    return .2126 * channel(rgb >> 16 & 255) + .7152 * channel(rgb >> 8 & 255) + .0722 * channel(rgb & 255);
  }

  private static double channel(int v) {
    double c = v / 255.0;
    return c <= .03928 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
  }
}
