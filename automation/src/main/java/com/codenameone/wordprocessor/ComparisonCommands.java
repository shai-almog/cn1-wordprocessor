package com.codenameone.wordprocessor;

import java.util.*;

/** Comparison works on supplied document data; filesystem access stays in the host. */
public final class ComparisonCommands implements Commands.Extension {
  public void register(Commands commands) {
    commands.register(
        "review.compare",
        "Compare text and direct formatting as tracked changes",
        "text?:string,xml?:string,originalXml?:string,author?:string,caseChanges?:boolean,whiteSpace?:boolean,formatting?:boolean,level?:string",
        true,
        (s, a) -> {
          if (a.containsKey("text") == a.containsKey("xml"))
            throw new IllegalArgumentException("Supply revised text or document XML");
          Document original = s.document(), revised;
          try {
            if (a.containsKey("originalXml"))
              original = DocumentXml.read((String) a.get("originalXml"));
            if (a.containsKey("xml")) revised = DocumentXml.read((String) a.get("xml"));
            else {
              Session text = new Session(Document.empty());
              text.replace((String) a.get("text"));
              revised = text.document();
            }
          } catch (java.io.IOException ex) {
            throw new IllegalArgumentException(ex.getMessage());
          }
          DocumentComparison.Options o = new DocumentComparison.Options();
          o.author = a.containsKey("author") ? (String) a.get("author") : s.reviewAuthor();
          String level = a.containsKey("level") ? (String) a.get("level") : "word";
          if (!Arrays.asList("word", "character", "char").contains(level))
            throw new IllegalArgumentException("Comparison level must be word or character");
          o.characters = !"word".equals(level);
          o.ignoreCase = Boolean.FALSE.equals(a.get("caseChanges"));
          o.ignoreWhiteSpace = Boolean.FALSE.equals(a.get("whiteSpace"));
          o.formatting = !Boolean.FALSE.equals(a.get("formatting"));
          DocumentComparison.Result result = DocumentComparison.compare(original, revised, o);
          s.replaceDocument(result.document);
          return Json.object(
              "changes",
              result.changes,
              "author",
              o.author.trim(),
              "coarse",
              result.coarse,
              "notHonoured",
              Arrays.asList(
                  "headersFooters",
                  "pageGeometry",
                  "styleDefinitions",
                  "formattingWithinChangedParagraphs"));
        });
  }
}
