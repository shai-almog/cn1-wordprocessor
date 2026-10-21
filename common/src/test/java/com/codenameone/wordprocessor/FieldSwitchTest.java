package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/** Field number switches and section fields as Wordcraft's layout::fields. */
public class FieldSwitchTest {
  private static String r(String code, Map<String, String> values) {
    return DocumentFields.resolve(new InlineField(code, "", false, false), "T", 4, 12, values, "decimal").result;
  }

  @Test
  public void switchesMatchWordcraft() {
    Map<String, String> none = Collections.emptyMap();
    assertEquals("4", r("PAGE", none));
    assertEquals("IV", r(" PAGE \\* ROMAN ", none));
    assertEquals("Twelve", r("NUMPAGES \\* CardText", none));
    assertEquals("十二", r("NUMPAGES \\* CHINESENUM1", none));
    assertEquals("肆", r("PAGE \\* ChineseNum2", none));
    assertEquals("4th", r("PAGE \\* Ordinal", none));
    assertEquals("Fourth", r("PAGE \\* OrdText", none));
    var v = new HashMap<String, String>();
    v.put("SECTION", "2");
    v.put("SECTIONPAGES", "3");
    assertEquals("ii", r("SECTION \\* roman", v));
    assertEquals("Three", r("SECTIONPAGES \\* CardText", v));
  }
}
