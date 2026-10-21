package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

/** List number formats drawn as Wordcraft's cjk_numbers (values from its tests). */
public class CjkNumbersTest {
  @Test
  public void formatsMatchWordcraft() {
    assertEquals("\u5341\u4e00", ListNumbers.format(11, "chineseCounting"));
    assertEquals("\u4e00\u3007\u4e00", ListNumbers.format(101, "chineseCounting"));
    assertEquals("\u5341\u4e07\u96f6\u4e00\u767e", ListNumbers.format(100100, "chineseCountingThousand"));
    assertEquals("\u4e00\u4ebf\u96f6\u4e00", ListNumbers.format(100000001, "chineseCountingThousand"));
    assertEquals("\u4e8c\u5341\u4e00\u4ebf\u56db\u5343\u4e03\u767e\u56db\u5341\u516b\u4e07\u4e09\u5343\u516d\u767e\u56db\u5341\u4e03", ListNumbers.format(2147483647, "chineseCountingThousand"));
    assertEquals("\u58f9\u4f70\u96f6\u58f9", ListNumbers.format(101, "chineseLegalSimplified"));
    assertEquals("\u58f9\u62fe", ListNumbers.format(10, "chineseLegalSimplified"));
    assertEquals("\u4e00\u842c\u96f6\u4e00", ListNumbers.format(10001, "taiwaneseCountingThousand"));
    assertEquals("\u8cb3\u62fe", ListNumbers.format(20, "ideographLegalTraditional"));
    assertEquals("\u4e00\u3007\u4e00", ListNumbers.format(101, "ideographDigital"));
    assertEquals("\u767e\u4e00", ListNumbers.format(101, "japaneseCounting"));
    assertEquals("\u4e00\u4e07\u4e00", ListNumbers.format(10001, "japaneseCounting"));
    assertEquals("\u58f1\u9621", ListNumbers.format(1000, "japaneseLegal"));
    assertEquals("\u4e00\u5104\u3007\u3007\u3007\u3007\u4e07\u3007\u3007\u3007\u3007", ListNumbers.format(100000000, "japaneseDigitalTenThousand"));
    assertEquals("\u4e00\u4e07\u4e8c\u4e09\u56db\u4e94", ListNumbers.format(12345, "japaneseDigitalTenThousand"));
    assertEquals("\ubc31\uc77c", ListNumbers.format(101, "koreanCounting"));
    assertEquals("11", ListNumbers.format(11, "ideographTraditional"));
    assertEquals("\u7532\u620c", ListNumbers.format(11, "ideographZodiacTraditional"));
    assertEquals("\u2473", ListNumbers.format(20, "decimalEnclosedCircle"));
    assertEquals("\u2488", ListNumbers.format(1, "decimalEnclosedFullstop"));
    assertEquals("\u3280", ListNumbers.format(1, "ideographEnclosedCircle"));
    assertEquals("\uff11\uff10", ListNumbers.format(10, "decimalFullWidth"));
    assertEquals("\u30f3", ListNumbers.format(46, "aiueoFullWidth"));
    assertEquals("\uff72", ListNumbers.format(2, "aiueo"));
    assertEquals("\uff8a", ListNumbers.format(3, "iroha"));
    assertEquals("\u30b9", ListNumbers.format(47, "irohaFullWidth"));
    assertEquals("\uac00", ListNumbers.format(1, "ganada"));
    assertEquals("\u314e", ListNumbers.format(14, "chosung"));
    assertEquals("3", ListNumbers.format(3, "hebrew1"));
    assertEquals("4", ListNumbers.format(4, "russianLower"));
    assertTrue(ListNumbers.BASES.contains("chineseCounting"));
  }
}
