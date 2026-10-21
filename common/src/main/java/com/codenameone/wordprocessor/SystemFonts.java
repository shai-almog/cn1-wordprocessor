package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;

/**
 * Installed fonts as a last-resort PDF fallback, as Wordcraft's fontdb does: the platform font
 * folders are indexed once by family name (reading only each file's table directory and 'name'
 * table), and for a character no bundled face covers, candidates are tried in a per-script
 * preference order (Wordcraft's script lists, then families named after the script, then the
 * general system fallback list, then the rest) until one maps it. SFNT faces
 * (.ttf, .ttc, .otf, with TrueType, CFF or CFF2 outlines) qualify.
 * Files are read through Codename One's FileSystemStorage; where that is unavailable (web, tests
 * without a platform) the index is simply empty.
 */
final class SystemFonts {
  private SystemFonts() {}

  /** File access, replaceable for tests. */
  interface Files {
    String[] list(String dir) throws IOException;

    boolean isDirectory(String path);

    InputStream open(String path) throws IOException;
  }

  static final class Entry {
    final String path, family;
    final int index, number;

    Entry(String path, int index, String family, int number) {
      this.path = path;
      this.index = index;
      this.family = family;
      this.number = number;
    }

    /** A PDF-safe unique font resource name. */
    String pdfName() {
      var sb = new StringBuilder();
      for (int i = 0; i < family.length() && sb.length() < 48; i++) {
        char c = family.charAt(i);
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') sb.append(c);
      }
      return "Sys" + sb + "-" + number;
    }
  }

  private static final int MAX_FILE = 64 * 1024 * 1024, MAX_TRIES = 24;

  static final String[] RTL = {
    "Vazirmatn", "Noto Naskh Arabic", "Noto Sans Arabic", "Noto Sans Arabic UI", "Sahel",
    "Shabnam", "Samim", "Noto Sans Hebrew", "Segoe UI", "Tahoma", "Geeza Pro", "SF Arabic",
    "Arial", "DejaVu Sans"
  };

  static final String[] GENERAL = {
    "Helvetica Neue", "Arial", "Segoe UI", "Noto Sans", "DejaVu Sans", "PingFang SC",
    "Hiragino Sans", "Hiragino Kaku Gothic ProN", "Apple SD Gothic Neo", "Heiti SC", "STHeiti",
    "Microsoft YaHei", "Yu Gothic", "Malgun Gothic", "Noto Sans CJK SC", "Noto Sans CJK JP",
    "Arial Unicode MS", "Apple Symbols", "Segoe UI Symbol", "Noto Sans Symbols",
    "Noto Sans Symbols2", "Noto Emoji", "Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji"
  };

  private static Files files;
  private static List<String> dirs;
  private static List<Entry> index;
  private static final Map<Integer, Entry> found = new HashMap<>();
  private static final Map<String, PdfFonts.TrueType> loaded = new LinkedHashMap<>();

  /** Replaces the file access and search folders (tests); null restores the platform's. */
  static synchronized void configure(Files f, List<String> folders) {
    files = f;
    dirs = folders;
    index = null;
    found.clear();
    loaded.clear();
  }

  static List<String> defaultDirs() {
    var out = new ArrayList<String>();
    out.add("/System/Library/Fonts");
    out.add("/Library/Fonts");
    String home = null, windir = null, local = null;
    try {
      home = System.getProperty("user.home");
      windir = System.getProperty("cn1.windir");
    } catch (Throwable unavailable) {
      // No system properties on this platform.
    }
    if (windir == null) windir = "C:/Windows";
    out.add(windir + "/Fonts");
    if (home != null) {
      out.add(home + "/Library/Fonts");
      out.add(home + "/AppData/Local/Microsoft/Windows/Fonts");
      out.add(home + "/.fonts");
      out.add(home + "/.local/share/fonts");
    }
    out.add("/usr/share/fonts");
    out.add("/usr/local/share/fonts");
    return out;
  }

  private static Files platform() {
    return new Files() {
      final com.codename1.io.FileSystemStorage fs = com.codename1.io.FileSystemStorage.getInstance();

      public String[] list(String dir) throws IOException {
        return fs.listFiles(dir);
      }

      public boolean isDirectory(String path) {
        return fs.isDirectory(path);
      }

      public InputStream open(String path) throws IOException {
        return fs.openInputStream(path);
      }
    };
  }

  /** The indexed installed faces (built on first use). */
  static synchronized List<Entry> entries() {
    if (index != null) return index;
    index = new ArrayList<>();
    try {
      if (files == null) files = platform();
      if (dirs == null) dirs = defaultDirs();
      for (String dir : dirs) scan(dir, 0);
    } catch (Throwable unavailable) {
      // No file system on this platform: no system fallback.
    }
    return index;
  }

  private static void scan(String dir, int depth) {
    if (depth > 4 || index.size() > 4000) return;
    String[] names;
    try {
      if (!files.isDirectory(dir)) return;
      names = files.list(dir);
    } catch (Throwable missing) {
      return;
    }
    if (names == null) return;
    Arrays.sort(names);
    for (String name : names) {
      var clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
      var path = dir.endsWith("/") ? dir + clean : dir + "/" + clean;
      var lower = clean.toLowerCase();
      if (lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")) {
        try {
          addFaces(path);
        } catch (Throwable unreadable) {
          // Skip fonts that cannot be read or parsed.
        }
      } else if (lower.indexOf('.') < 0 || name.endsWith("/")) scan(path, depth + 1);
    }
  }

  private static void addFaces(String path) throws IOException {
    byte[] head = range(path, 0, 12);
    int count = 1;
    int[] offsets = {0};
    if (head[0] == 't' && head[1] == 't' && head[2] == 'c' && head[3] == 'f') {
      count = Math.min(64, FontSubset.be32(head, 8));
      byte[] dir = range(path, 12, 4 * count);
      offsets = new int[count];
      for (int i = 0; i < count; i++) offsets[i] = FontSubset.be32(dir, 4 * i);
    }
    for (int i = 0; i < count; i++) {
      byte[] h = range(path, offsets[i], 12);
      int version = FontSubset.be32(h, 0);
      if (version != 0x10000 && version != 0x4f54544f) continue;
      int tables = FontSubset.be16(h, 4);
      byte[] records = range(path, offsets[i] + 12, 16 * tables);
      int nameAt = -1, nameLength = 0;
      boolean glyf = false;
      for (int t = 0; t < tables; t++) {
        var tag = new String(records, 16 * t, 4, "ISO-8859-1");
        if (tag.equals("glyf") || tag.equals("CFF ") || tag.equals("CFF2")) glyf = true;
        if (tag.equals("name")) {
          nameAt = FontSubset.be32(records, 16 * t + 8);
          nameLength = FontSubset.be32(records, 16 * t + 12);
        }
      }
      if (!glyf || nameAt < 0 || nameLength > 1 << 20) continue;
      var family = family(range(path, nameAt, nameLength));
      if (family != null) index.add(new Entry(path, i, family, index.size()));
    }
  }

  /** The typographic (16) or legacy (1) family name from a 'name' table. */
  static String family(byte[] name) throws IOException {
    if (name.length < 6) return null;
    int count = FontSubset.be16(name, 2), strings = FontSubset.be16(name, 4);
    String legacy = null, typographic = null;
    for (int i = 0; i < count && 6 + 12 * i + 12 <= name.length; i++) {
      int at = 6 + 12 * i, platform = FontSubset.be16(name, at), id = FontSubset.be16(name, at + 6),
          length = FontSubset.be16(name, at + 8), offset = strings + FontSubset.be16(name, at + 10);
      if (id != 1 && id != 16 || offset + length > name.length) continue;
      String value;
      if (platform == 3 || platform == 0) {
        var sb = new StringBuilder();
        for (int k = 0; k + 1 < length; k += 2) sb.append((char) FontSubset.be16(name, offset + k));
        value = sb.toString();
      } else if (platform == 1) value = new String(name, offset, length, "ISO-8859-1");
      else continue;
      if (id == 16 && (typographic == null || platform == 3)) typographic = value;
      if (id == 1 && (legacy == null || platform == 3)) legacy = value;
    }
    return typographic != null ? typographic : legacy;
  }

  private static byte[] range(String path, int offset, int length) throws IOException {
    if (offset < 0 || length < 0 || length > MAX_FILE) throw new IOException("Invalid font range");
    try (var in = files.open(path)) {
      long skip = offset;
      while (skip > 0) {
        long n = in.skip(skip);
        if (n <= 0) {
          if (in.read() < 0) throw new EOFException();
          n = 1;
        }
        skip -= n;
      }
      byte[] out = new byte[length];
      for (int at = 0; at < length; ) {
        int n = in.read(out, at, length - at);
        if (n < 0) throw new EOFException();
        at += n;
      }
      return out;
    }
  }

  private static byte[] read(String path) throws IOException {
    try (var in = files.open(path)) {
      var out = new ByteArrayOutputStream();
      byte[] buf = new byte[65536];
      for (int n; (n = in.read(buf)) > 0; ) {
        out.write(buf, 0, n);
        if (out.size() > MAX_FILE) throw new IOException("Font file too large");
      }
      return out.toByteArray();
    }
  }

  /** Standalone font bytes of an indexed face. */
  static synchronized byte[] bytes(Entry e) throws IOException {
    return FontSubset.face(read(e.path), e.index);
  }

  private static synchronized PdfFonts.TrueType font(Entry e) throws IOException {
    var key = e.path + "#" + e.index;
    var f = loaded.get(key);
    if (f == null) {
      f = new PdfFonts.TrueType(bytes(e));
      if (loaded.size() >= 6) loaded.remove(loaded.keySet().iterator().next());
      loaded.put(key, f);
    }
    return f;
  }

  /** The best-ranked installed face mapping {@code cp}, or null. */
  static synchronized Entry find(int cp) {
    if (found.containsKey(cp)) return found.get(cp);
    var candidates = new ArrayList<>(entries());
    final int c = cp;
    Collections.sort(candidates, (a, b) -> rank(c, a.family) - rank(c, b.family));
    Entry result = null;
    int tries = 0;
    for (var e : candidates) {
      if (tries++ >= MAX_TRIES) break;
      try {
        if (font(e).glyph(cp) != 0) {
          result = e;
          break;
        }
      } catch (IOException | RuntimeException unusable) {
        // Not embeddable or malformed: try the next candidate.
      }
    }
    if (found.size() > 20000) found.clear();
    found.put(cp, result);
    return result;
  }

  /** Wordcraft's per-script preferred families for complex scripts. */
  static String[] preferred(int cp) {
    if (cp >= 0x1000 && cp <= 0x109f)
      return new String[] {"Myanmar Text", "Noto Sans Myanmar", "Padauk", "Pyidaungsu",
        "Noto Serif Myanmar", "Myanmar Sangam MN", "Myanmar MN"};
    if (cp >= 0x1780 && cp <= 0x17ff)
      return new String[] {"Leelawadee UI", "Khmer UI", "Noto Sans Khmer", "Noto Serif Khmer",
        "Khmer OS", "Khmer Sangam MN", "Khmer MN"};
    if (cp >= 0x0900 && cp <= 0x097f)
      return new String[] {"Nirmala UI", "Mangal", "Kohinoor Devanagari", "Noto Sans Devanagari",
        "Noto Serif Devanagari", "Lohit Devanagari"};
    if (cp >= 0x0980 && cp <= 0x09ff)
      return new String[] {"Nirmala UI", "Vrinda", "Kohinoor Bangla", "Noto Sans Bengali",
        "Noto Serif Bengali", "Lohit Bengali"};
    if (cp >= 0x0e00 && cp <= 0x0e7f)
      return new String[] {"Leelawadee UI", "Leelawadee", "Tahoma", "Thonburi", "Noto Sans Thai",
        "Noto Serif Thai"};
    if (cp >= 0x0e80 && cp <= 0x0eff)
      return new String[] {"Leelawadee UI", "Lao UI", "Noto Sans Lao", "Noto Serif Lao",
        "Lao Sangam MN"};
    if (cp >= 0x0a00 && cp <= 0x0d7f) return new String[] {"Nirmala UI"};
    if ("arab".equals(PdfShaping.script(cp)) || "hebr".equals(PdfShaping.script(cp))) return RTL;
    return new String[0];
  }

  /** Name of the complex script {@code cp} belongs to, for "Noto Sans Tamil"-style matches. */
  static String scriptName(int cp) {
    String[] names = {"Devanagari", "Bengali", "Gurmukhi", "Gujarati", "Oriya", "Tamil", "Telugu",
      "Kannada", "Malayalam", "Sinhala", "Thai", "Lao", "Tibetan", "Tibetan"};
    if (cp >= 0x0900 && cp < 0x1000) return names[(cp - 0x0900) >> 7];
    if (cp >= 0x1000 && cp <= 0x109f) return "Myanmar";
    if (cp >= 0x1780 && cp <= 0x17ff) return "Khmer";
    if ("arab".equals(PdfShaping.script(cp))) return "Arabic";
    if ("hebr".equals(PdfShaping.script(cp))) return "Hebrew";
    return null;
  }

  /** Lower is better, as Wordcraft's fallback_rank, with the general list breaking later ties. */
  static int rank(int cp, String family) {
    var list = preferred(cp);
    for (int i = 0; i < list.length; i++) if (list[i].equalsIgnoreCase(family)) return i;
    var script = scriptName(cp);
    if (script != null && family.toLowerCase().indexOf(script.toLowerCase()) >= 0)
      return list.length;
    for (int i = 0; i < GENERAL.length; i++)
      if (GENERAL[i].equalsIgnoreCase(family)) return list.length + 1 + i;
    return list.length + 1 + GENERAL.length;
  }
}
