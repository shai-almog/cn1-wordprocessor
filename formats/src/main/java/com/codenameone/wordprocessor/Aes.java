package com.codenameone.wordprocessor;

/** Pure Java AES (FIPS 197) with CBC helpers that do no padding; the ParparVM target has no javax.crypto. */
public final class Aes {
  private static final int[] SBOX = new int[256];
  private static final int[] INV = new int[256];

  static {
    int p = 1, q = 1;
    do {
      p = p ^ ((p << 1) & 0xff) ^ ((p & 0x80) != 0 ? 0x1b : 0);
      q ^= q << 1;
      q ^= q << 2;
      q ^= q << 4;
      q &= 0xff;
      if ((q & 0x80) != 0) q ^= 0x09;
      int x = q ^ rot8(q, 1) ^ rot8(q, 2) ^ rot8(q, 3) ^ rot8(q, 4);
      SBOX[p] = (x ^ 0x63) & 0xff;
    } while (p != 1);
    SBOX[0] = 0x63;
    for (int i = 0; i < 256; i++) INV[SBOX[i]] = i;
  }

  private static int rot8(int x, int n) {
    return ((x << n) | (x >> (8 - n))) & 0xff;
  }

  private final int[] rk;
  private final int rounds;

  public Aes(byte[] key) {
    int nk = key.length / 4;
    if (key.length != 16 && key.length != 24 && key.length != 32)
      throw new IllegalArgumentException("AES key must be 16, 24 or 32 bytes");
    rounds = nk + 6;
    rk = new int[16 * (rounds + 1)];
    for (int i = 0; i < key.length; i++) rk[i] = key[i] & 0xff;
    int rcon = 1;
    for (int i = nk; i < 4 * (rounds + 1); i++) {
      int[] t = new int[4];
      for (int j = 0; j < 4; j++) t[j] = rk[(i - 1) * 4 + j];
      if (i % nk == 0) {
        int t0 = t[0];
        t[0] = SBOX[t[1]] ^ rcon;
        t[1] = SBOX[t[2]];
        t[2] = SBOX[t[3]];
        t[3] = SBOX[t0];
        rcon = ((rcon << 1) ^ ((rcon & 0x80) != 0 ? 0x11b : 0)) & 0xff;
      } else if (nk > 6 && i % nk == 4) {
        for (int j = 0; j < 4; j++) t[j] = SBOX[t[j]];
      }
      for (int j = 0; j < 4; j++) rk[i * 4 + j] = rk[(i - nk) * 4 + j] ^ t[j];
    }
  }

  private static int mul(int a, int b) {
    int r = 0;
    while (b != 0) {
      if ((b & 1) != 0) r ^= a;
      a = ((a << 1) ^ ((a & 0x80) != 0 ? 0x11b : 0)) & 0xff;
      b >>= 1;
    }
    return r;
  }

  /** Encrypts one 16-byte block from in[io] to out[oo] (may alias). */
  public void encryptBlock(byte[] in, int io, byte[] out, int oo) {
    int[] s = new int[16];
    for (int i = 0; i < 16; i++) s[i] = (in[io + i] & 0xff) ^ rk[i];
    int[] t = new int[16];
    for (int r = 1; r <= rounds; r++) {
      for (int c = 0; c < 4; c++)
        for (int row = 0; row < 4; row++) t[c * 4 + row] = SBOX[s[((c + row) & 3) * 4 + row]];
      if (r < rounds) {
        for (int c = 0; c < 4; c++) {
          int a0 = t[c * 4], a1 = t[c * 4 + 1], a2 = t[c * 4 + 2], a3 = t[c * 4 + 3];
          s[c * 4] = mul(a0, 2) ^ mul(a1, 3) ^ a2 ^ a3;
          s[c * 4 + 1] = a0 ^ mul(a1, 2) ^ mul(a2, 3) ^ a3;
          s[c * 4 + 2] = a0 ^ a1 ^ mul(a2, 2) ^ mul(a3, 3);
          s[c * 4 + 3] = mul(a0, 3) ^ a1 ^ a2 ^ mul(a3, 2);
        }
      } else System.arraycopy(t, 0, s, 0, 16);
      for (int i = 0; i < 16; i++) s[i] ^= rk[r * 16 + i];
    }
    for (int i = 0; i < 16; i++) out[oo + i] = (byte) s[i];
  }

  public void decryptBlock(byte[] in, int io, byte[] out, int oo) {
    int[] s = new int[16];
    for (int i = 0; i < 16; i++) s[i] = (in[io + i] & 0xff) ^ rk[rounds * 16 + i];
    int[] t = new int[16];
    for (int r = rounds - 1; r >= 0; r--) {
      for (int c = 0; c < 4; c++)
        for (int row = 0; row < 4; row++) t[((c + row) & 3) * 4 + row] = INV[s[c * 4 + row]];
      for (int i = 0; i < 16; i++) t[i] ^= rk[r * 16 + i];
      if (r > 0) {
        for (int c = 0; c < 4; c++) {
          int a0 = t[c * 4], a1 = t[c * 4 + 1], a2 = t[c * 4 + 2], a3 = t[c * 4 + 3];
          s[c * 4] = mul(a0, 14) ^ mul(a1, 11) ^ mul(a2, 13) ^ mul(a3, 9);
          s[c * 4 + 1] = mul(a0, 9) ^ mul(a1, 14) ^ mul(a2, 11) ^ mul(a3, 13);
          s[c * 4 + 2] = mul(a0, 13) ^ mul(a1, 9) ^ mul(a2, 14) ^ mul(a3, 11);
          s[c * 4 + 3] = mul(a0, 11) ^ mul(a1, 13) ^ mul(a2, 9) ^ mul(a3, 14);
        }
      } else System.arraycopy(t, 0, s, 0, 16);
    }
    for (int i = 0; i < 16; i++) out[oo + i] = (byte) s[i];
  }

  /** CBC encrypt; data length must be a multiple of 16 (no padding is added). */
  public static byte[] cbcEncrypt(byte[] key, byte[] iv, byte[] data) {
    if (data.length % 16 != 0 || iv.length != 16) throw new IllegalArgumentException("CBC needs whole blocks");
    Aes aes = new Aes(key);
    byte[] out = new byte[data.length];
    byte[] prev = iv.clone();
    byte[] x = new byte[16];
    for (int o = 0; o < data.length; o += 16) {
      for (int i = 0; i < 16; i++) x[i] = (byte) (data[o + i] ^ prev[i]);
      aes.encryptBlock(x, 0, out, o);
      System.arraycopy(out, o, prev, 0, 16);
    }
    return out;
  }

  public static byte[] cbcDecrypt(byte[] key, byte[] iv, byte[] data) {
    if (data.length % 16 != 0 || iv.length != 16) throw new IllegalArgumentException("CBC needs whole blocks");
    Aes aes = new Aes(key);
    byte[] out = new byte[data.length];
    byte[] prev = iv.clone();
    for (int o = 0; o < data.length; o += 16) {
      aes.decryptBlock(data, o, out, o);
      for (int i = 0; i < 16; i++) out[o + i] ^= prev[i];
      System.arraycopy(data, o, prev, 0, 16);
    }
    return out;
  }
}
