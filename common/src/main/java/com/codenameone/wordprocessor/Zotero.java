package com.codenameone.wordprocessor;

import com.codename1.io.Socket;
import com.codename1.io.SocketConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Zotero integration: the word processor plays the part of a Zotero word-processor plugin.
 *
 * <p>Zotero's desktop app listens on 127.0.0.1:23116 for its published plugin wire protocol. The
 * editor sends one integration command ({@link Command}); Zotero then shows its own dialogs and
 * drives the document with calls such as Document_insertField and Field_setText, ending with
 * Document_complete. Every frame is a 32-bit big-endian transaction id, a 32-bit big-endian
 * payload length and a UTF-8 JSON payload ("ERR:" text for a failed call). Commands use id 0 and
 * get no reply; Zotero's calls use non-zero ids that the reply echoes.
 *
 * <p>Citations are stored the way Zotero's Word plugin stores them so documents move between this
 * editor and Word: fields whose instruction is "ADDIN ZOTERO_ITEM CSL_CITATION {...}" or "ADDIN
 * ZOTERO_BIBL {...} CSL_BIBLIOGRAPHY", and document preferences in the custom properties
 * ZOTERO_PREF_1..n (kept as the {@link #PREFS_SETTING} document setting).
 */
public final class Zotero {
  private Zotero() {}

  public static final String HOST = "127.0.0.1";
  public static final int PORT = 23116;
  public static final int MAX_FRAME = 32 * 1024 * 1024;
  public static final int TEMPLATE_VERSION = 1;
  public static final String PREFIX = "ADDIN ZOTERO_";
  public static final String PREF_NAME = "ZOTERO_PREF_";
  public static final int PREF_CHUNK = 255;
  public static final String PREFS_SETTING = "zotero.prefs";

  /** Integration commands, by their wire names. */
  public enum Command {
    ADD_EDIT_CITATION("addEditCitation", "Add/Edit Citation"),
    ADD_EDIT_BIBLIOGRAPHY("addEditBibliography", "Add/Edit Bibliography"),
    ADD_NOTE("addNote", "Add Note"),
    REFRESH("refresh", "Refresh"),
    SET_DOC_PREFS("setDocPrefs", "Document Preferences"),
    REMOVE_CODES("removeCodes", "Unlink Citations");

    public final String wire, label;

    Command(String wire, String label) {
      this.wire = wire;
      this.label = label;
    }

    public static Command named(String name) {
      for (Command c : values()) if (c.wire.equalsIgnoreCase(name)) return c;
      return null;
    }
  }

  /** A connection to Zotero; tests substitute an in-memory transcript. */
  public interface Connection {
    InputStream input();

    OutputStream output();

    void close();
  }

  public interface Connector {
    Connection open() throws IOException;
  }

  /** Answers one call from Zotero; throwing reports "ERR:" to Zotero and continues. */
  public interface Handler {
    Object handle(String method, List<Object> args) throws Exception;
  }

  /** Result of a session: calls answered, whether Zotero completed, and failed calls. */
  public static final class Outcome {
    public int calls;
    public boolean completed;
    public final List<String> errors = new ArrayList<>();
  }

  // ---- fields and preferences ----

  public static boolean is(InlineField f) {
    return f != null && code(f.instruction) != null;
  }

  /** The code Zotero sees (without "ADDIN ZOTERO_"), or null for other fields. */
  public static String code(String instruction) {
    String t = instruction.trim();
    if (t.length() < PREFIX.length() || !t.regionMatches(true, 0, PREFIX, 0, PREFIX.length()))
      return null;
    return t.substring(PREFIX.length()).trim();
  }

  public static String stored(String code) {
    return PREFIX + code.trim();
  }

  /** n for a "ZOTERO_PREF_n" custom property name, else 0. */
  public static int prefIndex(String name) {
    if (name.length() <= PREF_NAME.length()
        || !name.regionMatches(true, 0, PREF_NAME, 0, PREF_NAME.length())) return 0;
    int n = 0;
    for (int i = PREF_NAME.length(); i < name.length(); i++) {
      char c = name.charAt(i);
      if (c < '0' || c > '9' || n > 100000) return 0;
      n = n * 10 + (c - '0');
    }
    return n;
  }

  /** Zotero calls this protocol's field type ReferenceMark; the file keeps Word's "Field". */
  public static String prefs(Document d) {
    return d.properties
        .setting(PREFS_SETTING, "")
        .replace("name=\"fieldType\" value=\"Field\"", "name=\"fieldType\" value=\"ReferenceMark\"");
  }

  public static DocumentProperties prefs(DocumentProperties p, String data) {
    return p.withSetting(
        PREFS_SETTING,
        data.replace("name=\"fieldType\" value=\"ReferenceMark\"", "name=\"fieldType\" value=\"Field\""));
  }

  // ---- wire ----

  /** Reads one frame as {txid, payload}; null at a clean end of stream. */
  public static Object[] readFrame(InputStream in) throws IOException {
    byte[] head = new byte[8];
    int got = 0;
    while (got < 8) {
      int n = in.read(head, got, 8 - got);
      if (n < 0) {
        if (got == 0) return null;
        throw new IOException("Zotero closed the connection inside a frame header");
      }
      got += n;
    }
    long txid = be(head, 0), len = be(head, 4);
    if (len > MAX_FRAME) throw new IOException("Zotero frame of " + len + " bytes exceeds the limit");
    byte[] payload = new byte[(int) len];
    for (int off = 0; off < payload.length; ) {
      int n = in.read(payload, off, payload.length - off);
      if (n < 0) throw new IOException("Zotero closed the connection inside a frame");
      off += n;
    }
    return new Object[] {txid, payload};
  }

  private static long be(byte[] b, int o) {
    return ((long) (b[o] & 255) << 24) | ((b[o + 1] & 255) << 16) | ((b[o + 2] & 255) << 8) | (b[o + 3] & 255);
  }

  public static void writeFrame(OutputStream out, long txid, byte[] payload) throws IOException {
    if (payload.length > MAX_FRAME) throw new IOException("Reply too large for Zotero");
    byte[] b = new byte[8 + payload.length];
    put(b, 0, txid);
    put(b, 4, payload.length);
    System.arraycopy(payload, 0, b, 8, payload.length);
    out.write(b);
    out.flush();
  }

  private static void put(byte[] b, int o, long v) {
    b[o] = (byte) (v >>> 24);
    b[o + 1] = (byte) (v >>> 16);
    b[o + 2] = (byte) (v >>> 8);
    b[o + 3] = (byte) v;
  }

  public static byte[] commandPayload(Command c) throws IOException {
    return json(Json.object("command", c.wire, "templateVersion", TEMPLATE_VERSION)).getBytes("UTF-8");
  }

  /** Parses a call "[method, [args...]]" (a bare "[method, arg, ...]" is accepted too). */
  public static Object[] parseCall(byte[] payload) throws IOException {
    String text = new String(payload, "UTF-8").trim();
    if (!text.startsWith("[") || !text.endsWith("]")) throw new IOException("A call must be a JSON array");
    Map<String, Object> m = Json.parse("{\"v\":" + text + "}");
    Object v = m.get("v");
    if (!(v instanceof List<?> items) || items.isEmpty() || !(items.get(0) instanceof String method))
      throw new IOException("A call must start with the method name");
    var args = new ArrayList<Object>();
    if (items.size() == 2 && items.get(1) instanceof List<?> l) args.addAll(l);
    else args.addAll(items.subList(1, items.size()));
    return new Object[] {method, args};
  }

  /** Minimal JSON writer for replies (strings, numbers, booleans, null, lists, maps). */
  public static String json(Object v) {
    var b = new StringBuilder();
    json(b, v);
    return b.toString();
  }

  private static void json(StringBuilder b, Object v) {
    if (v == null) b.append("null");
    else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) b.append(v);
    else if (v instanceof Number n) {
      double d = n.doubleValue();
      if (Math.abs(d) < 1e15 && d == (long) d) b.append((long) d);
      else b.append(d);
    } else if (v instanceof List<?> l) {
      b.append('[');
      for (int i = 0; i < l.size(); i++) {
        if (i > 0) b.append(',');
        json(b, l.get(i));
      }
      b.append(']');
    } else if (v instanceof Map<?, ?> m) {
      b.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) b.append(',');
        first = false;
        json(b, String.valueOf(e.getKey()));
        b.append(':');
        json(b, e.getValue());
      }
      b.append('}');
    } else {
      String s = String.valueOf(v);
      b.append('"');
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '"' || c == '\\') b.append('\\').append(c);
        else if (c < 0x20 || c > 0x7e) {
          String h = Integer.toHexString(c);
          b.append("\\u");
          for (int k = h.length(); k < 4; k++) b.append('0');
          b.append(h);
        } else b.append(c);
      }
      b.append('"');
    }
  }

  /** Sends the command and answers Zotero's calls until Document_complete or end of stream. */
  public static Outcome run(Connector connector, Command command, Handler handler) throws IOException {
    Connection c;
    try {
      c = connector.open();
    } catch (IOException ex) {
      throw new IOException("Zotero isn't running (start Zotero and try again): " + ex.getMessage());
    }
    try {
      writeFrame(c.output(), 0, commandPayload(command));
      return serve(c.input(), c.output(), handler);
    } finally {
      c.close();
    }
  }

  public static Outcome serve(InputStream in, OutputStream out, Handler handler) throws IOException {
    var outcome = new Outcome();
    Object[] frame;
    while ((frame = readFrame(in)) != null) {
      long txid = (Long) frame[0];
      if (txid == 0) continue;
      outcome.calls++;
      String reply, method = null;
      try {
        Object[] call = parseCall((byte[]) frame[1]);
        method = (String) call[0];
        @SuppressWarnings("unchecked")
        List<Object> args = (List<Object>) call[1];
        reply = json(handler.handle(method, args));
      } catch (Exception ex) {
        String msg = ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
        outcome.errors.add(msg);
        reply = "ERR:" + msg;
      }
      writeFrame(out, txid, reply.getBytes("UTF-8"));
      if ("Document_complete".equals(method)) {
        outcome.completed = true;
        break;
      }
    }
    return outcome;
  }

  /** Connects with Codename One's socket API (desktop and mobile ports that support sockets). */
  public static Connector socket(final String host, final int port, final int timeoutMillis) {
    return () -> {
      if (!Socket.isSupported()) throw new IOException("Sockets are not supported on this platform");
      final Object lock = new Object();
      final Object[] result = new Object[3];
      final Object done = new Object();
      SocketConnection sc =
          new SocketConnection(timeoutMillis) {
            @Override
            public void connectionError(int code, String message) {
              synchronized (lock) {
                result[2] = message == null ? "error " + code : message;
                lock.notifyAll();
              }
            }

            @Override
            public void connectionEstablished(InputStream is, OutputStream os) {
              synchronized (lock) {
                result[0] = is;
                result[1] = os;
                lock.notifyAll();
              }
              // The streams belong to this callback thread's connection; hold it open until closed.
              synchronized (done) {
                while (result[2] == null) {
                  try {
                    done.wait();
                  } catch (InterruptedException ex) {
                    return;
                  }
                }
              }
            }
          };
      Socket.connect(host, port, sc);
      long until = System.currentTimeMillis() + timeoutMillis;
      synchronized (lock) {
        while (result[0] == null && result[2] == null) {
          long left = until - System.currentTimeMillis();
          if (left <= 0) throw new IOException("timed out");
          try {
            lock.wait(left);
          } catch (InterruptedException ex) {
            throw new IOException("interrupted");
          }
        }
        if (result[0] == null) throw new IOException(String.valueOf(result[2]));
      }
      final InputStream is = (InputStream) result[0];
      final OutputStream os = (OutputStream) result[1];
      return new Connection() {
        public InputStream input() {
          return is;
        }

        public OutputStream output() {
          return os;
        }

        public void close() {
          try {
            is.close();
          } catch (IOException ignored) {
          }
          try {
            os.close();
          } catch (IOException ignored) {
          }
          synchronized (done) {
            result[2] = "closed";
            done.notifyAll();
          }
        }
      };
    };
  }
}
