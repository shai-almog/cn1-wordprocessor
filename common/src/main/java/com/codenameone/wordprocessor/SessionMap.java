package com.codenameone.wordprocessor;

/**
 * Per-session values stored on the session itself, so they are released with it. A portable
 * replacement for WeakHashMap, which ParparVM does not provide.
 */
public final class SessionMap<V> {
  /** Keys of maps whose values belong to the open document, dropped when another is loaded. */
  static final java.util.List<Object> DOCUMENT_KEYS = new java.util.ArrayList<>();

  private final Object key = new Object();

  public SessionMap() {}

  private SessionMap(boolean document) {
    if (document)
      synchronized (DOCUMENT_KEYS) {
        DOCUMENT_KEYS.add(key);
      }
  }

  /**
   * A map for state that describes the open document (its versions, password, reading
   * position): Session.loadDocument clears it, so New/Open never carry it to another document.
   */
  public static <V> SessionMap<V> forDocument() {
    return new SessionMap<>(true);
  }

  @SuppressWarnings("unchecked")
  public V get(Session s) {
    return (V) s.attachments().get(key);
  }

  public boolean containsKey(Session s) {
    return s.attachments().containsKey(key);
  }

  public V put(Session s, V value) {
    var old = get(s);
    s.attachments().put(key, value);
    return old;
  }

  public V remove(Session s) {
    var old = get(s);
    s.attachments().remove(key);
    return old;
  }
}
