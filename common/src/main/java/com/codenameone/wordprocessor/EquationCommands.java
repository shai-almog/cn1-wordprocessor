package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** In-place equation editing for the ribbon, macros, CLI and MCP (Wordcraft's equation.* edits). */
public final class EquationCommands implements Commands.Extension {
  public void register(Commands c) {
    c.edit("equation.edit", "block?:integer,steps:string,normal?:boolean",
        "Edit the equation at the selection in place as one undo step. Steps are separated by a"
            + " newline: 'type:<text>' (Math AutoCorrect and build-up on space/relations as in Word),"
            + " 'template:<linear>' (a Structures gallery template; the caret goes to its first empty"
            + " slot), 'left', 'right', 'up', 'down', 'home', 'end', 'next', 'prev' (Tab between empty"
            + " slots), 'backspace', 'delete', 'enter' (build up)", (s, a) -> {
          var e = EquationEditing.at(s, a.containsKey("block") ? Commands.integer(a, "block") : -1);
          if (e == null) throw new IllegalArgumentException("Select an equation");
          e.normalText = Commands.flag(a, "normal", false);
          EquationEditor ed = e.editor;
          for (String step : TextUtil.split(Commands.str(a, "steps"), '\n')) {
            if (step.startsWith("type:")) ed.type(step.substring(5), e.normalText);
            else if (step.startsWith("template:")) ed.insertTemplate(step.substring(9), "");
            else if ("backspace".equals(step)) ed.deleteBack();
            else if ("delete".equals(step)) ed.deleteForward();
            else if ("enter".equals(step)) ed.finish();
            else if (!step.isEmpty()) {
              if (!e.normalText) ed.finish();
              if (!move(ed, step)) break;
            }
          }
          e.commit();
          return Json.object("text", e.stored(), "caret", ed.pos.toString());
        });
    c.query("equation.gallery", "",
        "The Equation tab's Structures galleries (id, label, sections of templates) and Symbols sets",
        (s, a) -> gallery());
  }

  private static boolean move(EquationEditor ed, String dir) {
    switch (dir) {
      case "left": return ed.moveLeft();
      case "right": return ed.moveRight();
      case "up": ed.moveVertical(true); return true;
      case "down": ed.moveVertical(false); return true;
      case "home": ed.homeEnd(false); return true;
      case "end": ed.homeEnd(true); return true;
      case "next": ed.nextPlaceholder(false); return true;
      case "prev": ed.nextPlaceholder(true); return true;
      default: throw new IllegalArgumentException("Unknown equation step: " + dir);
    }
  }

  static Map<String, Object> gallery() {
    List<Object> structures = new ArrayList<>();
    List<Object> sections = null, templates = null;
    for (String[] row : EquationGallery.STRUCTURES) {
      if ("G".equals(row[0])) {
        sections = new ArrayList<>();
        structures.add(Json.object("id", row[1], "label", row[2], "sections", sections));
      } else if ("S".equals(row[0]) && sections != null) {
        templates = new ArrayList<>();
        sections.add(Json.object("name", row[1], "templates", templates));
      } else if ("T".equals(row[0]) && templates != null)
        templates.add(Json.object("id", row[1], "label", row[2], "linear", row[3]));
    }
    List<Object> symbols = new ArrayList<>();
    for (String[] set : EquationGallery.SYMBOLS) symbols.add(Json.object("name", set[0], "chars", set[1]));
    return Json.object("structures", structures, "symbols", symbols);
  }
}
