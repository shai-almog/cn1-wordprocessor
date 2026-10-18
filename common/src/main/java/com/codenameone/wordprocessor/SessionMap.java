package com.codenameone.wordprocessor;

/**
 * Per-session values stored on the session itself, so they are released with it. A portable
 * replacement for WeakHashMap, which ParparVM does not provide.
 */
public final class SessionMap<V> {
  private final Object key = new Object();

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
