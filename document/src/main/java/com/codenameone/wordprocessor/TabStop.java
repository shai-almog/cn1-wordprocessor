package com.codenameone.wordprocessor;

import java.util.*;

/** Explicit paragraph tab stop, measured in points from the paragraph text origin. */
public final class TabStop {
  public final float position;
  public final String alignment, leader;

  public TabStop(float position, String alignment, String leader) {
    if (Float.isNaN(position)
        || Float.isInfinite(position)
        || position < 0
        || position > 10000
        || !Arrays.asList("left", "center", "right", "decimal", "bar").contains(alignment)
        || !Arrays.asList("none", "dot", "hyphen", "underscore", "middleDot").contains(leader))
      throw new IllegalArgumentException("Invalid tab stop");
    this.position = position;
    this.alignment = alignment;
    this.leader = leader;
  }

  public String glyph() {
    return "dot".equals(leader)
        ? "."
        : "hyphen".equals(leader)
            ? "-"
            : "underscore".equals(leader) ? "_" : "middleDot".equals(leader) ? "\u00b7" : "";
  }

  public static List<TabStop> normalized(List<TabStop> source) {
    if (source == null || source.size() > 64)
      throw new IllegalArgumentException("At most 64 tab stops");
    var result = new ArrayList<TabStop>();
    for (TabStop stop : source) {
      if (stop == null) throw new IllegalArgumentException("Missing tab stop");
      int i = 0;
      while (i < result.size() && result.get(i).position < stop.position) i++;
      if (i < result.size() && result.get(i).position == stop.position) result.set(i, stop);
      else result.add(i, stop);
    }
    return Collections.unmodifiableList(result);
  }

  public static String encode(List<TabStop> stops) {
    var s = new StringBuilder();
    for (TabStop t : stops) {
      if (s.length() > 0) s.append(';');
      s.append(t.position).append(',').append(t.alignment).append(',').append(t.leader);
    }
    return s.toString();
  }

  public static List<TabStop> decode(String value) {
    var stops = new ArrayList<TabStop>();
    if (!value.isEmpty())
      for (String item : TextUtil.split(value, ';')) {
        String[] p = TextUtil.split(item, ',');
        if (p.length != 3) throw new IllegalArgumentException("Invalid tab stop entry");
        stops.add(new TabStop(Float.parseFloat(p[0]), p[1], p[2]));
      }
    return normalized(stops);
  }
}
