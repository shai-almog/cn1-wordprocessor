package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;

/** A tiny built-in thesaurus of common words (port of the reference table). */
public final class Thesaurus {
  private static final String[][] TABLE = {
    {"good", "fine", "excellent", "decent", "solid", "worthy"},
    {"bad", "poor", "inferior", "faulty", "unpleasant"},
    {"big", "large", "huge", "sizable", "vast", "major"},
    {"small", "little", "tiny", "compact", "minor", "modest"},
    {"fast", "quick", "rapid", "swift", "speedy"},
    {"slow", "unhurried", "gradual", "leisurely", "sluggish"},
    {"happy", "glad", "cheerful", "content", "joyful", "pleased"},
    {"sad", "unhappy", "sorrowful", "downcast", "gloomy"},
    {"important", "significant", "key", "vital", "essential", "major"},
    {"show", "display", "reveal", "present", "demonstrate"},
    {"make", "create", "build", "produce", "craft", "form"},
    {"use", "employ", "apply", "utilize", "operate"},
    {"help", "assist", "aid", "support", "serve"},
    {"start", "begin", "launch", "open", "initiate"},
    {"end", "finish", "close", "conclude", "stop"},
    {"say", "state", "tell", "remark", "mention"},
    {"think", "believe", "consider", "reckon", "suppose"},
    {"new", "fresh", "novel", "recent", "modern"},
    {"old", "aged", "former", "ancient", "vintage"},
    {"beautiful", "lovely", "attractive", "gorgeous", "stunning"},
    {"easy", "simple", "effortless", "straightforward"},
    {"hard", "difficult", "tough", "demanding", "firm"},
    {"work", "labor", "effort", "job", "task", "operate"},
    {"idea", "concept", "notion", "thought", "plan"},
    {"change", "alter", "modify", "adjust", "shift"},
    {"great", "excellent", "superb", "terrific", "considerable"},
    {"very", "extremely", "highly", "truly", "really"},
    {"shared", "common", "joint", "communal", "mutual"},
    {"community", "group", "society", "collective", "circle"}
  };

  private Thesaurus() {}

  public static List<String> synonyms(String word) {
    String w = word == null ? "" : word.trim().toLowerCase();
    List<String> out = new ArrayList<String>();
    for (String[] row : TABLE)
      if (row[0].equals(w)) for (int i = 1; i < row.length; i++) out.add(row[i]);
    return out;
  }
}
