package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import org.junit.Test;

public class TableContentsTest {
  private final LayoutEngine engine = new LayoutEngine(new LayoutEngine.Metrics() {
    public float width(String text, TextStyle style) { return text.length() * 6; }
    public float height(TextStyle style) { return 12; }
  });
  private TableContents.Paginator pages() { return d -> engine.layout(d, 0); }
  private Session document() {
    Session s = new Session(Document.empty());
    s.replace("\nOverview\nBody\nDetails\nMore text");
    s.select(1, 0, 8); s.applyStyle("Heading1");
    s.select(3, 0, 7); s.applyStyle("Heading2");
    s.select(0, 0, 0);
    return s;
  }
  @Test public void contentsUpdatesWithoutDuplicatesAndUndoIsAtomic() {
    Session s = document();
    Document before = s.document();
    TableContents.insert(s, TableContents.options(before), pages());
    assertEquals("Contents", s.document().paragraph(0).text.plainText());
    assertEquals("Overview\t1", s.document().paragraph(1).text.plainText());
    assertEquals("Details\t1", s.document().paragraph(2).text.plainText());
    assertEquals(2, s.document().bookmarks.size());
    assertEquals("dot", s.document().paragraph(1).format.tabs.get(0).leader);
    s.undo(); assertSame(before, s.document()); s.redo();
    int heading = ReviewComment.block(s.document(), before.paragraph(1).id);
    s.select(heading, 0, 8); s.replace("Introduction");
    TableContents.update(s, pages());
    assertEquals("Introduction\t1", s.document().paragraph(1).text.plainText());
    assertEquals(2, s.document().bookmarks.size());
    assertEquals(8, s.document().blocks.size());
    Document withContents = s.document();
    TableContents.remove(s); assertNull(TableContents.range(s.document()));
    assertTrue(s.document().plainText().contains("Introduction"));
    s.undo(); assertSame(withContents, s.document());
  }
  @Test public void settingsReplaceInPlaceAndKeepOnlyRequestedHeadingLevels() {
    Session s = document();
    TableContents.insert(s, TableContents.options(s.document()), pages());
    s.select(s.document().blocks.size()-1, 0, 0);
    TableContents.insert(s, new TableContents.Options(1,"Index",false,false,"none",false), pages());
    assertArrayEquals(new int[]{0,2}, TableContents.range(s.document()));
    assertEquals("Index", s.document().paragraph(0).text.plainText());
    assertEquals("Overview", s.document().paragraph(1).text.plainText());
    assertTrue(s.document().paragraph(1).format.tabs.isEmpty());
    assertFalse(TableContents.options(s.document()).pageNumbers);
  }
  @Test public void pageNumbersIncludeContentsPaginationAndSectionRestarts() {
    Session s = document();
    Document d = s.document().page(new PageSpec(300,100,10,10,10,10));
    d = d.section(3, new Section(d.page,PageFurniture.EMPTY,"nextPage",7));
    s.replaceDocument(d);
    TableContents.insert(s, TableContents.options(d), pages());
    LayoutEngine.Result layout = pages().layout(s.document());
    for (int i=1; i<=2; i++) {
      Paragraph entry=s.document().paragraph(i);
      InlineField page=entry.text.fieldAt(entry.text.length()-1);
      Bookmark anchor=s.document().bookmarks.get(new FieldCode(page.instruction).arguments.get(0));
      int expected=-1;
      for (LayoutEngine.Page p:layout.pages) for(LayoutEngine.Line l:p.lines)
        if(l.paragraphId.equals(anchor.startId)) expected=p.number;
      assertEquals(""+expected,page.result);
    }
  }
  @Test public void insertionSplitsTextAndEmptyContentsRemainRemovable() {
    Session s=new Session(Document.empty()); s.replace("BeforeAfter"); s.select(0,6,6);
    Document before=s.document();
    TableContents.insert(s,TableContents.options(before),pages());
    assertEquals("Before\nContents\nAfter",s.document().plainText());
    TableContents.remove(s);
    assertEquals("Before\nAfter",s.document().plainText());
    s.undo(); s.undo(); assertSame(before,s.document());
  }
  @Test public void headerMarkerIsNotAnEntryAndInvalidOptionsPreserveDocument() {
    Session s=document(); Document before=s.document();
    try { TableContents.insert(s,new TableContents.Options(0,"Bad",true,true,"dot",true),pages()); fail(); }
    catch(IllegalArgumentException expected) { assertSame(before,s.document()); }
    TableContents.insert(s,TableContents.options(before),pages());
    TableContents.update(s,pages());
    assertEquals("Contents",s.document().paragraph(0).text.plainText());
    assertEquals(3,TableContents.range(s.document())[1]);
  }

  @Test public void updateFromTableCellPreservesSelectionAndUndo() {
    Session s=document();
    s.select(4,0,0); s.insertTable(1,1);
    s.enterCell(5,0,0); s.replace("Cell text"); s.leaveCell(); s.select(0,0,0);
    TableContents.insert(s,TableContents.options(s.document()),pages());
    s.enterCell(8,0,0); s.select(0,2,6);
    Document before=s.document();
    TableContents.update(s,pages());
    assertTrue(s.inCell()); assertEquals(8,s.cellBlock());
    assertEquals(2,s.selection().anchor); assertEquals(6,s.selection().caret);
    assertEquals("Cell text",s.editingDocument().plainText());
    s.undo(); assertEquals(before.plainText(),s.document().plainText()); assertTrue(s.inCell());
    assertEquals(2,s.selection().anchor); assertEquals(6,s.selection().caret);
  }
  @Test public void headingFieldResultsAreRefreshedBeforeContentsPaginationSettles() {
    Session s = new Session(Document.empty().page(new PageSpec(300, 160, 10, 10, 10, 10)));
    s.replace("\nCurrent heading text\nHeading ");
    s.select(1, 0, 20); s.addBookmark("Source");
    s.select(2, 8, 8); s.applyStyle("Heading1");
    s.insertField(new InlineField("REF Source", "Stale label", false, false));
    s.insertField(new InlineField("PAGEREF Source", "99", false, false));
    s.select(0, 0, 0);
    TableContents.insert(s, TableContents.options(s.document()), pages());
    int[] range = TableContents.range(s.document());
    String heading = s.document().paragraph(s.document().blocks.size() - 1).text.plainText();
    assertTrue(heading.startsWith("Heading Current heading text"));
    String entry = s.document().paragraph(range[0] + 1).text.plainText();
    assertTrue(entry, entry.startsWith(heading + "\t"));
    TableContents.update(s, pages());
    assertEquals(entry, s.document().paragraph(range[0] + 1).text.plainText());
  }

  private TableContents.Options sources(boolean headings, boolean outlines, boolean marked,
      java.util.Map<String,Integer> styles, String identifier) {
    return new TableContents.Options(3, "Contents", true, true, "dot", true, null,
        headings, outlines, marked, styles, identifier);
  }
  private java.util.List<String> entries(Document d) {
    java.util.List<String> out = new java.util.ArrayList<String>();
    int[] range = TableContents.range(d);
    for (int i = range[0] + 1; i < range[1]; i++) out.add(d.paragraph(i).style + ":" + d.paragraph(i).text.plainText());
    return out;
  }
  @Test public void styleMappingsOutrankOutlineExclusionAndCanUseHigherLevels() {
    Session s = document();
    s.select(2,0,0); s.defineStyle(new ParagraphStyle("Topic", "Special Topic", s.document().paragraph(2), TextStyle.DEFAULT));
    s.applyStyle("Topic"); s.paragraph("outlineLevel", "0");
    s.select(1,0,0); s.paragraph("outlineLevel", "0");
    s.select(4,0,0); s.paragraph("outlineLevel", "3"); s.select(0,0,0);
    java.util.Map<String,Integer> styles = new java.util.LinkedHashMap<String,Integer>();
    styles.put("special topic", 6);
    TableContents.insert(s, sources(true,true,false,styles,""), pages());
    assertEquals(java.util.Arrays.asList("TOC6:Body\t1", "TOC2:Details\t1", "TOC3:More text\t1"), entries(s.document()));
    assertEquals(styles,TableContents.options(s.document()).styleLevels);
    TableContents.update(s,pages());
    assertEquals(3, entries(s.document()).size());
    styles.clear(); styles.put("Topic",5);
    TableContents.insert(s,sources(false,false,false,styles,""),pages());
    assertEquals(java.util.Arrays.asList("TOC5:Body\t1"),entries(s.document()));
  }
  @Test public void outlineOnlyIncludesLevelNineAndExclusionDoesNotHideHeadingOnlySource() {
    Session s = document(); s.select(1,0,0); s.paragraph("outlineLevel","0");
    s.select(4,0,0); s.paragraph("outlineLevel","9"); s.select(0,0,0);
    TableContents.insert(s,sources(false,true,false,java.util.Collections.<String,Integer>emptyMap(),""),pages());
    assertEquals(java.util.Arrays.asList("TOC9:More text\t1"),entries(s.document()));
    TableContents.insert(s,sources(true,false,false,java.util.Collections.<String,Integer>emptyMap(),""),pages());
    assertEquals(java.util.Arrays.asList("TOC1:Overview\t1","TOC2:Details\t1"),entries(s.document()));
  }
  @Test public void markedEntriesPreserveSourceAndUseDistinctAnchorsAndFilters() {
    Session s = document(); s.select(2,0,4); Document before = s.document();
    TableContents.markEntry(s,"Quoted \"topic\" \\ path",2,"C");
    assertEquals("Body",s.document().paragraph(2).text.plainText());
    assertEquals("Quoted \"topic\" \\ path",new FieldCode(s.document().paragraph(2).text.fieldAt(4).instruction).arguments.get(0));
    s.undo(); assertSame(before,s.document()); s.redo();
    TableContents.markEntry(s,"Other category",1,"B");
    TableContents.markEntry(s,"Too deep",4,"C");
    s.insertField(new InlineField("TC \"Invalid level falls back\" \\l nonsense", "", false,false));
    s.select(0,0,0);
    TableContents.insert(s,sources(false,false,true,java.util.Collections.<String,Integer>emptyMap(),"c"),pages());
    assertEquals(java.util.Arrays.asList("TOC2:Quoted \"topic\" \\ path\t1","TOC1:Invalid level falls back\t1"),entries(s.document()));
    assertEquals(2,s.document().bookmarks.size());
    java.util.Set<Integer> starts = new java.util.HashSet<Integer>();
    for (Bookmark b:s.document().bookmarks.values()) { starts.add(b.start); assertEquals(b.start+1,b.end); }
    assertEquals(2,starts.size());
    java.util.List<String> expected=entries(s.document());
    TableContents.update(s,pages()); assertEquals(expected,entries(s.document())); assertEquals(2,s.document().bookmarks.size());
    TableContents.remove(s); assertNull(TableContents.range(s.document()));
    assertTrue(s.document().plainText().contains("Body"));
  }
}
