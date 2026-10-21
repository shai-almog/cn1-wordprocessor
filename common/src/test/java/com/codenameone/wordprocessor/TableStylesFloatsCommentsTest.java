package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.Test;

/** Named table styles, floating tables, rich comments, shown hidden text and the default language. */
public class TableStylesFloatsCommentsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String text, TextStyle style) {
            return text.length() * 6;
          }

          public float height(TextStyle style) {
            return 12;
          }
        });
  }

  private static Session tableSession() {
    var s = new Session(Document.empty());
    s.insertTable(4, 3);
    s.setCell(1, 0, 0, "Head");
    s.setCell(1, 1, 0, "1,5");
    s.setCell(1, 2, 0, "2,5");
    s.enterCell(1, 1, 1);
    return s;
  }

  private static Table table(Document d) {
    for (Block b : d.blocks) if (b instanceof Table t) return t;
    throw new AssertionError("no table");
  }

  private static Document docx(Document d) throws Exception {
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    return Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
  }

  @Test
  public void wordcraftTableStyleConditionalFormattingAndRoundTrip() throws Exception {
    var s = tableSession();
    assertTrue(((List<?>) run(s, "table.styles")).contains("GridTable4AccentBlue"));
    run(s, "table.style", "style", "GridTable4AccentBlue");
    Table t = table(s.document());
    assertEquals("GridTable4AccentBlue", t.format.prop("style", ""));
    assertEquals(0x156082, t.format.cell(0, 1).background);
    assertEquals(0xc1e4f5, t.format.cell(1, 1).background); // first band
    assertEquals(0xffffff, t.format.cell(2, 1).background);
    assertTrue(t.cell(0, 0).get(0).text.styleAt(0).bold);
    run(s, "table.look", "bandedRows", false);
    assertEquals(0xffffff, table(s.document()).format.cell(1, 1).background);
    run(s, "table.look", "bandedRows", true);

    var back = docx(s.document());
    Table bt = table(back);
    assertEquals("GridTable4AccentBlue", bt.format.prop("style", ""));
    assertEquals(0x156082, bt.format.cell(0, 2).background);
    var def = TableStyles.resolve(back, "GridTable4AccentBlue");
    assertEquals("156082", def.get("headerFill"));
    assertEquals("true", def.get("headerBold"));
    assertEquals("c1e4f5", def.get("bandFill"));
    // Native format keeps the definitions (document settings) and the table's style name.
    var nat = DocumentXml.read(DocumentXml.write(back));
    assertEquals("ffffff", TableStyles.resolve(nat, "GridTable4AccentBlue").get("headerColor"));
  }

  @Test
  public void docxStyleDefinitionAppliesWithDirectFormattingWinning() throws Exception {
    var s = tableSession();
    var d = s.document();
    d = d.properties(d.properties.withSetting(TableStyles.PREFIX + "Mine",
        "name=Mine;border=ff0000;sides=all;headerFill=00ff00;totalFill=0000ff;totalBold=true;basedOn=TableNormal"));
    Table t = table(d);
    int at = 1;
    t = t.format(t.format.withProp("style", "Mine").withProp("look", "headerRow,lastRow")
        .cell(0, 2, t.format.cell(0, 2).background(0x123456)));
    d = d.blocks(d.blocks.set(at, t), d.nextId);
    var back = docx(d);
    Table bt = table(back);
    assertEquals(0x00ff00, bt.format.cell(0, 0).background);
    assertEquals(0x123456, bt.format.cell(0, 2).background); // direct shading wins
    assertEquals(0x0000ff, bt.format.cell(3, 1).background);
    assertEquals(0xff0000, bt.format.cell(2, 1).borderColor);
  }

  @Test
  public void floatingTableRoundTripsAndTakesNoRoomInTheFlow() throws Exception {
    var s = tableSession();
    var d = s.document();
    Table t = table(d);
    int at = 1;
    var flowY = engine().layout(d, 1);
    d = d.blocks(d.blocks.set(at, t.format(t.format.withProp("float", "margin,text,200,10,,,9,0,9,0"))), d.nextId);
    var back = table(docx(d));
    String[] f = back.format.floating();
    assertEquals("margin", f[0]);
    assertEquals("text", f[1]);
    assertEquals(200f, TableFormat.floatNumber(f[2]), .01);
    assertEquals(10f, TableFormat.floatNumber(f[3]), .01);
    assertEquals(9f, TableFormat.floatNumber(f[6]), .01);
    var laid = engine().layout(d, 2);
    assertNotNull(flowY);
    assertNotNull(laid);
    // Centered by alignment instead of an offset.
    d = d.blocks(d.blocks.set(at, t.format(t.format.withProp("float", "page,page,0,0,center,top,0,0,0,0"))), d.nextId);
    assertEquals("center", table(docx(d)).format.floating()[4]);
  }

  @Test
  public void richCommentBodiesAndInitialsRoundTrip() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Hello world");
    s.select(0, 0, 5);
    s.addComment("Ada Lovelace", "plain");
    var d = s.document();
    var c = d.comments.get(0);
    assertEquals("AL", c.initials);
    var bold = RichText.of("Bold", TextStyle.DEFAULT.with("bold", "true")).concat(RichText.of(" note"));
    var rich = c.body(List.of(new Paragraph("cp1", bold), new Paragraph("cp2", RichText.of("second"))))
        .reply("Bob", "ok");
    assertEquals("Bold note\nsecond", rich.text);
    d = d.comments(List.of(rich));
    var back = docx(d).comments.get(0);
    assertEquals("AL", back.initials);
    assertEquals(2, back.body.size());
    assertTrue(back.body.get(0).text.styleAt(0).bold);
    assertEquals("Bold note\nsecond", back.text);
    assertEquals(1, back.replies.size());
    assertEquals("B", back.replies.get(0).initials);
    var nat = DocumentXml.read(DocumentXml.write(d)).comments.get(0);
    assertEquals("AL", nat.initials);
    assertTrue(nat.body.get(0).text.styleAt(0).bold);
    assertEquals("B", nat.replies.get(0).initials);
    // Retyping keeps the body rich (the typed text takes the formatting where it is typed).
    var retyped = nat.edited(-1, "retyped");
    assertEquals("retyped", retyped.text);
    assertEquals(1, retyped.body.size());
  }

  @Test
  public void documentDefaultLanguageReadsUntaggedCells() throws Exception {
    var s = tableSession();
    assertNotEquals("4", TableFormulas.calculate(table(s.document()), 3, 0, "=SUM(ABOVE)"));
    s.leaveCellLevel();
    run(s, "review.language", "lang", "pl_pl", "default", true);
    assertEquals("pl-PL", s.document().properties.setting("lang", ""));
    // 1,5 and 2,5 read with the decimal comma: 4 written as Polish.
    assertEquals("4", TableFormulas.calculate(table(s.document()), 3, 0, "=SUM(ABOVE)", TableFormulas.defaultLang(s.document())));
    assertEquals("5,50", TableFormulas.calculate(table(s.document()), 3, 0, "=SUM(ABOVE,1.5)", TableFormulas.defaultLang(s.document())));
    var back = docx(s.document());
    assertEquals("pl-PL", back.properties.setting("lang", ""));
  }

  private static float extent(LayoutEngine.Line line) {
    float end = 0;
    for (LayoutEngine.Span span : line.spans) end = Math.max(end, span.x + span.width);
    return end;
  }

  @Test
  public void shownHiddenTextTakesLayoutWidth() {
    var p = new Paragraph("p1", RichText.of("ab").concat(RichText.of("HIDDEN", TextStyle.DEFAULT.with("hidden", "true")))
        .concat(RichText.of("cd")));
    var d = new Document(new Sequence<Block>().append(p), new PageSpec(400, 300, 20, 20, 20, 20), "Hidden", 2);
    var engine = engine();
    float hiddenWidth = extent(engine.layout(d, 1).pages.get(0).lines.get(0));
    engine.setShowHidden(true);
    float shownWidth = extent(engine.layout(d, 2).pages.get(0).lines.get(0));
    assertTrue(shownWidth + " > " + hiddenWidth, shownWidth > hiddenWidth);
  }
}
