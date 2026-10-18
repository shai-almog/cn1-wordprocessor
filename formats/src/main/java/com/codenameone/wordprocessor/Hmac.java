package com.codenameone.wordprocessor;

/** HMAC-SHA512 (RFC 2104 / RFC 4231). */
public final class Hmac {
  private Hmac() {}

  public static byte[] sha512(byte[] key, byte[] msg) {
    if (key.length > Sha512.BLOCK) key = Sha512.hash(key);
    byte[] ipad = new byte[Sha512.BLOCK];
    byte[] opad = new byte[Sha512.BLOCK];
    for (int i = 0; i < Sha512.BLOCK; i++) {
      int k = i < key.length ? key[i] : 0;
      ipad[i] = (byte) (k ^ 0x36);
      opad[i] = (byte) (k ^ 0x5c);
    }
    var in = new Sha512();
    in.update(ipad, 0, ipad.length);
    in.update(msg, 0, msg.length);
    var inner = in.digest();
    var out = new Sha512();
    out.update(opad, 0, opad.length);
    out.update(inner, 0, inner.length);
    return out.digest();
  }
}
