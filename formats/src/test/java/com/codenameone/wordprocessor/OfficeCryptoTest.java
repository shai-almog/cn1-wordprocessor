package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class OfficeCryptoTest {
  private static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte x : b) s.append(String.format("%02x", x & 0xff));
    return s.toString();
  }

  private static byte[] unhex(String s) {
    byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    return b;
  }

  private static byte[] ascii(String s) throws Exception {
    return s.getBytes("US-ASCII");
  }

  @Test public void sha512KnownAnswers() throws Exception {
    assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd"
        + "454d4423643ce80e2a9ac94fa54ca49f", hex(Sha512.hash(ascii("abc"))));
    assertEquals("cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f"
        + "63b931bd47417a81a538327af927da3e", hex(Sha512.hash(new byte[0])));
    // 112-byte FIPS message exercises the two-block padding path.
    assertEquals("8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018501d289e4900f7e4331b99dec4b5433a"
        + "c7d329eeb6dd26545e96e55b874be909", hex(Sha512.hash(ascii("abcdefghbcdefghicdefghijdefghijkefghijklfghijklm"
        + "ghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"))));
  }

  @Test public void aes256KnownAnswer() {
    byte[] key = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    byte[] pt = unhex("00112233445566778899aabbccddeeff");
    byte[] ct = new byte[16];
    new Aes(key).encryptBlock(pt, 0, ct, 0);
    assertEquals("8ea2b7ca516745bfeafc49904b496089", hex(ct));
    byte[] back = new byte[16];
    new Aes(key).decryptBlock(ct, 0, back, 0);
    assertArrayEquals(pt, back);
    byte[] k128 = unhex("000102030405060708090a0b0c0d0e0f");
    new Aes(k128).encryptBlock(pt, 0, ct, 0);
    assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", hex(ct));
    byte[] data = new byte[64];
    for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7);
    byte[] iv = unhex("0f0e0d0c0b0a09080706050403020100");
    assertArrayEquals(data, Aes.cbcDecrypt(key, iv, Aes.cbcEncrypt(key, iv, data)));
  }

  @Test public void hmacSha512Rfc4231Case2() throws Exception {
    assertEquals("164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554"
        + "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737",
        hex(Hmac.sha512(ascii("Jefe"), ascii("what do ya want for nothing?"))));
  }

  @Test public void compoundFileRoundTrip() throws Exception {
    Map<String, byte[]> in = new LinkedHashMap<String, byte[]>();
    byte[] big = new byte[150000];
    for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31 + i / 251);
    in.put("Big", big);
    in.put("Small", ascii("hello mini stream"));
    in.put("\u0006Dir/Nested/Small2", new byte[100]);
    in.put("\u0006Dir/Empty", new byte[0]);
    in.put("Edge", new byte[4095]);
    for (int i = 0; i < 9; i++) in.put("S" + i, new byte[] {(byte) i});
    byte[] file = CompoundFile.write(in);
    assertTrue(OfficeCrypto.isEncrypted(file));
    Map<String, byte[]> out = CompoundFile.read(file);
    assertEquals(in.keySet().size(), out.size());
    for (String k : in.keySet()) assertArrayEquals(k, in.get(k), out.get(k));
    // Past 109 FAT sectors (needs DIFAT): about 7 MB.
    byte[] huge = new byte[7300000];
    for (int i = 0; i < huge.length; i += 97) huge[i] = (byte) i;
    Map<String, byte[]> h = new LinkedHashMap<String, byte[]>();
    h.put("Huge", huge);
    h.put("Tiny", new byte[] {1, 2, 3});
    Map<String, byte[]> ho = CompoundFile.read(CompoundFile.write(h));
    assertArrayEquals(huge, ho.get("Huge"));
    assertArrayEquals(new byte[] {1, 2, 3}, ho.get("Tiny"));
  }

  private static byte[] docx() throws Exception {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    Docx.write(Document.empty(), o);
    return o.toByteArray();
  }

  @Test public void encryptDecryptRoundTrip() throws Exception {
    byte[] plain = docx();
    assertFalse(OfficeCrypto.isEncrypted(plain));
    long t = System.nanoTime();
    byte[] enc = OfficeCrypto.encrypt(plain, "p\u00e4ssw\u00f6rd");
    System.out.println("encrypt (one key derivation, spin 100000): " + (System.nanoTime() - t) / 1000000 + " ms");
    assertTrue(OfficeCrypto.isEncrypted(enc));
    t = System.nanoTime();
    assertArrayEquals(plain, OfficeCrypto.decrypt(enc, "p\u00e4ssw\u00f6rd"));
    System.out.println("decrypt: " + (System.nanoTime() - t) / 1000000 + " ms");
    if (System.getProperty("crypto.out") != null)
      java.nio.file.Files.write(java.nio.file.Paths.get(System.getProperty("crypto.out")), enc);
    byte[] big = new byte[20000];
    for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 13);
    assertArrayEquals(big, OfficeCrypto.decrypt(OfficeCrypto.encrypt(big, "x", 1000), "x"));
  }

  @Test public void wrongPasswordAndTamperAreRejected() throws Exception {
    byte[] enc = OfficeCrypto.encrypt(docx(), "right", 1000);
    try {
      OfficeCrypto.decrypt(enc, "wrong");
      fail();
    } catch (IOException e) {
      assertEquals("Wrong password", e.getMessage());
    }
    Map<String, byte[]> files = CompoundFile.read(enc);
    byte[] pkg = files.get("EncryptedPackage");
    pkg[pkg.length / 2] ^= 1;
    try {
      OfficeCrypto.decrypt(CompoundFile.write(files), "right");
      fail();
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("integrity"));
    }
    for (String bad : new String[] {null, ""}) {
      try {
        OfficeCrypto.encrypt(new byte[1], bad);
        fail();
      } catch (IllegalArgumentException expected) {
      }
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 256; i++) sb.append('a');
    try {
      OfficeCrypto.encrypt(new byte[1], sb.toString());
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
