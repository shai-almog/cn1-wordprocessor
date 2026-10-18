package com.codenameone.wordprocessor;

import com.codename1.security.Cipher;
import com.codename1.security.Hash;
import com.codename1.security.SecretKey;
import com.codename1.security.Hmac;
import com.codename1.util.Base64;
import com.codename1.xml.Element;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Word "Encrypt with Password": ECMA-376 / MS-OFFCRYPTO Agile Encryption (AES-256-CBC, SHA-512, spinCount 100000)
 * in pure Java, because the ParparVM target has no javax.crypto, java.security, java.util.zip or regex.
 *
 * <p>Randomness: the target has no guaranteed SecureRandom, so salts and keys come from a java.util.Random seeded
 * from System.nanoTime() xor currentTimeMillis xor an identity hash and a process-wide counter, and every output
 * block is mixed through SHA-512 of (seed material, counter). This is weaker than an OS entropy source; the
 * confidentiality of a document still rests mainly on the password.
 *
 * <p>Reading supports AES (128/192/256) with CBC chaining and SHA-512 password key encryptors, which is what Word
 * 2013+ writes. Older SHA-1 agile files and the legacy RC4/standard encryption are rejected with an IOException.
 */
public final class OfficeCrypto {
  private static final int HASH_SIZE = 64;

  /** AES-CBC without padding; agile encryption pads segments itself. */
  private static byte[] aes(boolean encrypt, byte[] key, byte[] iv, byte[] data) {
    var k = new SecretKey("AES", key);
    return encrypt
        ? Cipher.aesEncrypt(Cipher.AES_CBC, k, iv, null, data)
        : Cipher.aesDecrypt(Cipher.AES_CBC, k, iv, null, data);
  }

  /** SHA-512 of the concatenated parts. */
  static byte[] sha512(byte[]... parts) {
    var h = Hash.create(Hash.SHA512);
    for (byte[] p : parts) h.update(p);
    return h.digest();
  }

  public static final int DEFAULT_SPIN_COUNT = 100000;
  private static final int MAX_SPIN_COUNT = 10000000;
  private static final int SEGMENT = 4096;
  private static final int BLOCK = 16;
  private static final byte[] BK_VERIFIER_INPUT = bytes(0xfe, 0xa7, 0xd2, 0x76, 0x3b, 0x4b, 0x9e, 0x79);
  private static final byte[] BK_VERIFIER_VALUE = bytes(0xd7, 0xaa, 0x0f, 0x6d, 0x30, 0x61, 0x34, 0x4e);
  private static final byte[] BK_KEY_VALUE = bytes(0x14, 0x6e, 0x0b, 0xe7, 0xab, 0xac, 0xd0, 0xd6);
  private static final byte[] BK_HMAC_KEY = bytes(0x5f, 0xb2, 0xad, 0x01, 0x0c, 0xb9, 0xe1, 0xf6);
  private static final byte[] BK_HMAC_VALUE = bytes(0xa0, 0x67, 0x7f, 0x02, 0xb2, 0x2c, 0x84, 0x33);
  private static final String NS_ENC = "http://schemas.microsoft.com/office/2006/encryption";
  private static final String NS_PWD = "http://schemas.microsoft.com/office/2006/keyEncryptor/password";
  private static int counter;

  private OfficeCrypto() {}

  private static byte[] bytes(int... v) {
    byte[] b = new byte[v.length];
    for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
    return b;
  }

  public static boolean isEncrypted(byte[] data) {
    return CompoundFile.hasSignature(data);
  }

  public static void checkPassword(String password) {
    if (password == null || password.length() < 1 || password.length() > 255)
      throw new IllegalArgumentException("A password must be 1 to 255 characters");
  }

  // ------------------------------------------------------------ primitives

  /** H0 = H(salt + UTF-16LE password), Hn = H(iterator LE32 + Hn-1) (MS-OFFCRYPTO 2.3.4.11). */
  static byte[] iterate(byte[] salt, String password, int spin) {
    byte[] pw = new byte[password.length() * 2];
    for (int i = 0; i < password.length(); i++) {
      pw[i * 2] = (byte) password.charAt(i);
      pw[i * 2 + 1] = (byte) (password.charAt(i) >> 8);
    }
    byte[] h = sha512(salt, pw);
    byte[] it = new byte[4];
    for (int i = 0; i < spin; i++) {
      it[0] = (byte) i;
      it[1] = (byte) (i >> 8);
      it[2] = (byte) (i >> 16);
      it[3] = (byte) (i >> 24);
      h = sha512(it, h);
    }
    return h;
  }

  private static byte[] fit(byte[] b, int len) {
    byte[] r = new byte[len];
    for (int i = 0; i < len; i++) r[i] = i < b.length ? b[i] : (byte) 0x36;
    return r;
  }

  private static byte[] deriveKey(byte[] hn, byte[] blockKey, int len) {
    return fit(sha512(hn, blockKey), len);
  }

  private static byte[] makeIv(byte[] salt, byte[] blockKey) {
    return fit(blockKey == null ? salt : sha512(salt, blockKey), BLOCK);
  }

  private static byte[] padded(byte[] d) {
    byte[] r = new byte[(d.length + BLOCK - 1) / BLOCK * BLOCK];
    System.arraycopy(d, 0, r, 0, d.length);
    return r;
  }

  private static byte[] head(byte[] d, int n) throws IOException {
    if (d.length < n) throw new IOException("Damaged encryption data");
    byte[] r = new byte[n];
    System.arraycopy(d, 0, r, 0, n);
    return r;
  }

  private static boolean same(byte[] a, byte[] b) {
    if (a.length != b.length) return false;
    int x = 0;
    for (int i = 0; i < a.length; i++) x |= a[i] ^ b[i];
    return x == 0;
  }

  private static byte[] le32(int v) {
    return bytes(v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >> 24) & 0xff);
  }

  private static byte[] random(int n) {
    int tag;
    synchronized (OfficeCrypto.class) {
      tag = ++counter;
    }
    long seed = System.nanoTime() ^ System.currentTimeMillis() ^ System.identityHashCode(new Object()) ^ ((long) tag << 32);
    var r = new Random(seed);
    byte[] pool = new byte[64];
    r.nextBytes(pool);
    byte[] out = new byte[n];
    int pos = 0;
    for (int block = 0; pos < n; block++) {
      byte[] mix = sha512(pool, le32(block + r.nextInt()));
      for (int i = 0; i < mix.length && pos < n; i++) out[pos++] = mix[i];
    }
    return out;
  }

  // ------------------------------------------------------------- encrypt

  public static byte[] encrypt(byte[] docx, String password) {
    return encrypt(docx, password, DEFAULT_SPIN_COUNT);
  }

  /** Test hook: a chosen spin count (files should use the default). */
  static byte[] encrypt(byte[] docx, String password, int spin) {
    checkPassword(password);
    final int keyBytes = 32;
    byte[] keyDataSalt = random(16), passwordSalt = random(16), key = random(keyBytes);
    byte[] verifier = random(16), hmacSalt = random(HASH_SIZE);

    int segs = (docx.length + SEGMENT - 1) / SEGMENT;
    byte[] stream = new byte[8 + (docx.length + BLOCK - 1) / BLOCK * BLOCK];
    long len = docx.length;
    for (int i = 0; i < 8; i++) stream[i] = (byte) (len >>> (8 * i));
    for (int s = 0; s < segs; s++) {
      int from = s * SEGMENT, n = Math.min(SEGMENT, docx.length - from);
      byte[] seg = new byte[n];
      System.arraycopy(docx, from, seg, 0, n);
      byte[] enc = aes(true, key, makeIv(keyDataSalt, le32(s)), padded(seg));
      System.arraycopy(enc, 0, stream, 8 + from, enc.length);
    }

    byte[] hn = iterate(passwordSalt, password, spin);
    byte[] iv = makeIv(passwordSalt, null);
    byte[] encVerifierInput = aes(true, deriveKey(hn, BK_VERIFIER_INPUT, keyBytes), iv, padded(verifier));
    byte[] encVerifierHash =
        aes(true, deriveKey(hn, BK_VERIFIER_VALUE, keyBytes), iv, padded(sha512(verifier)));
    byte[] encKeyValue = aes(true, deriveKey(hn, BK_KEY_VALUE, keyBytes), iv, padded(key));

    byte[] encHmacKey = aes(true, key, makeIv(keyDataSalt, BK_HMAC_KEY), padded(hmacSalt));
    byte[] hmac = Hmac.sha512(hmacSalt, stream);
    byte[] encHmacValue = aes(true, key, makeIv(keyDataSalt, BK_HMAC_VALUE), padded(hmac));

    var params = """
        saltSize="16" blockSize="16" keyBits="256" hashSize="64" cipherAlgorithm="AES"\
         cipherChaining="ChainingModeCBC" hashAlgorithm="SHA512"\
        """;
    var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n<encryption xmlns=\"" + NS_ENC
        + "\" xmlns:p=\"" + NS_PWD + "\"><keyData " + params + " saltValue=\"" + b64(keyDataSalt) + "\"/>"
        + "<dataIntegrity encryptedHmacKey=\"" + b64(encHmacKey) + "\" encryptedHmacValue=\"" + b64(encHmacValue)
        + "\"/><keyEncryptors><keyEncryptor uri=\"" + NS_PWD + "\"><p:encryptedKey spinCount=\"" + spin + "\" "
        + params + " saltValue=\"" + b64(passwordSalt) + "\" encryptedVerifierHashInput=\"" + b64(encVerifierInput)
        + "\" encryptedVerifierHashValue=\"" + b64(encVerifierHash) + "\" encryptedKeyValue=\"" + b64(encKeyValue)
        + "\"/></keyEncryptor></keyEncryptors></encryption>";
    byte[] xmlBytes = utf8(xml);
    byte[] info = new byte[8 + xmlBytes.length];
    info[0] = 4;
    info[2] = 4;
    info[4] = 0x40;
    System.arraycopy(xmlBytes, 0, info, 8, xmlBytes.length);

    var files = new LinkedHashMap<String, byte[]>();
    files.put("\u0006DataSpaces/Version", dataSpaceVersion());
    files.put("\u0006DataSpaces/DataSpaceMap", dataSpaceMap());
    files.put("\u0006DataSpaces/DataSpaceInfo/StrongEncryptionDataSpace", dataSpaceDefinition());
    files.put("\u0006DataSpaces/TransformInfo/StrongEncryptionTransform/\u0006Primary", primaryTransform());
    files.put("EncryptionInfo", info);
    files.put("EncryptedPackage", stream);
    return CompoundFile.write(files);
  }

  private static String b64(byte[] b) {
    return Base64.encodeNoNewline(b);
  }

  private static byte[] utf8(String s) {
    try {
      return s.getBytes("UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** UNICODE-LP-P4 string (MS-OFFCRYPTO 2.1.2): byte length, UTF-16LE, zero padded to 4 bytes. */
  private static void lpP4(java.io.ByteArrayOutputStream o, String s) {
    put32(o, s.length() * 2);
    for (int i = 0; i < s.length(); i++) {
      o.write(s.charAt(i));
      o.write(s.charAt(i) >> 8);
    }
    for (int n = s.length() * 2; n % 4 != 0; n++) o.write(0);
  }

  private static void put32(java.io.ByteArrayOutputStream o, int v) {
    o.write(v);
    o.write(v >> 8);
    o.write(v >> 16);
    o.write(v >> 24);
  }

  private static byte[] dataSpaceVersion() {
    var o = new java.io.ByteArrayOutputStream();
    lpP4(o, "Microsoft.Container.DataSpaces");
    for (int i = 0; i < 3; i++) put32(o, 1);
    return o.toByteArray();
  }

  private static byte[] dataSpaceMap() {
    var entry = new java.io.ByteArrayOutputStream();
    put32(entry, 1);
    put32(entry, 0);
    lpP4(entry, "EncryptedPackage");
    lpP4(entry, "StrongEncryptionDataSpace");
    var e = entry.toByteArray();
    var o = new java.io.ByteArrayOutputStream();
    put32(o, 8);
    put32(o, 1);
    put32(o, e.length + 4);
    o.write(e, 0, e.length);
    return o.toByteArray();
  }

  private static byte[] dataSpaceDefinition() {
    var o = new java.io.ByteArrayOutputStream();
    put32(o, 8);
    put32(o, 1);
    lpP4(o, "StrongEncryptionTransform");
    return o.toByteArray();
  }

  private static byte[] primaryTransform() {
    var id = new java.io.ByteArrayOutputStream();
    lpP4(id, "{FF9A3F03-56EF-4613-BDD5-5A41C1D07246}");
    var o = new java.io.ByteArrayOutputStream();
    put32(o, 8 + id.size());
    put32(o, 1);
    var idb = id.toByteArray();
    o.write(idb, 0, idb.length);
    lpP4(o, "Microsoft.Container.EncryptionTransform");
    for (int i = 0; i < 3; i++) put32(o, 1);
    put32(o, 0);
    put32(o, BLOCK);
    put32(o, 0);
    put32(o, 4);
    return o.toByteArray();
  }

  // ------------------------------------------------------------- decrypt

  public static byte[] decrypt(byte[] cfb, String password) throws IOException {
    if (password == null || password.length() < 1) throw new IOException("Wrong password");
    var files = CompoundFile.read(cfb);
    var info = files.get("EncryptionInfo");
    var pkg = files.get("EncryptedPackage");
    if (info == null || pkg == null) throw new IOException("Not an encrypted Office package");
    if (info.length < 12 || info[0] != 4 || info[2] != 4)
      throw new IOException("Unsupported encryption version (only Agile Encryption 4.4 is supported)");
    int end = info.length;
    while (end > 8 && info[end - 1] != '>') end--;
    String xml;
    try {
      xml = new String(info, 8, end - 8, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IOException(e.toString());
    }
    Element root = Xml.parse(xml);
    Element keyData = Xml.child(root, "keyData");
    Element integrity = Xml.child(root, "dataIntegrity");
    Element encs = Xml.child(root, "keyEncryptors");
    if (keyData == null || encs == null) throw new IOException("Damaged encryption information");
    Element ek = null;
    for (Element enc : Xml.children(encs, "keyEncryptor")) {
      Element c = Xml.child(enc, "encryptedKey");
      if (c != null && c.getAttribute("spinCount") != null) ek = c;
    }
    if (ek == null) throw new IOException("No password key encryptor");
    checkParams(keyData);
    checkParams(ek);
    int spin = number(ek, "spinCount");
    if (spin < 0 || spin > MAX_SPIN_COUNT) throw new IOException("spinCount out of range");
    int pwKeyBytes = number(ek, "keyBits") / 8, dataKeyBytes = number(keyData, "keyBits") / 8;
    byte[] pwSalt = b64attr(ek, "saltValue"), dataSalt = b64attr(keyData, "saltValue");

    byte[] hn = iterate(pwSalt, password, spin);
    byte[] iv = makeIv(pwSalt, null);
    byte[] input = head(aes(false, deriveKey(hn, BK_VERIFIER_INPUT, pwKeyBytes), iv,
        b64attr(ek, "encryptedVerifierHashInput")), pwSalt.length);
    byte[] value = head(aes(false, deriveKey(hn, BK_VERIFIER_VALUE, pwKeyBytes), iv,
        b64attr(ek, "encryptedVerifierHashValue")), HASH_SIZE);
    if (!same(sha512(input), value)) throw new IOException("Wrong password");
    byte[] key = head(aes(false, deriveKey(hn, BK_KEY_VALUE, pwKeyBytes), iv, b64attr(ek, "encryptedKeyValue")),
        dataKeyBytes);

    if (integrity == null) throw new IOException("The document has no integrity check");
    byte[] hmacKey = head(aes(false, key, makeIv(dataSalt, BK_HMAC_KEY), b64attr(integrity, "encryptedHmacKey")),
        HASH_SIZE);
    byte[] expected = head(
        aes(false, key, makeIv(dataSalt, BK_HMAC_VALUE), b64attr(integrity, "encryptedHmacValue")), HASH_SIZE);
    if (!same(Hmac.sha512(hmacKey, pkg), expected))
      throw new IOException("The document is damaged (its integrity check failed)");

    if (pkg.length < 8) throw new IOException("The encrypted package is truncated");
    long size = 0;
    for (int i = 7; i >= 0; i--) size = (size << 8) | (pkg[i] & 0xffL);
    long avail = pkg.length - 8;
    long needed = (size + BLOCK - 1) / BLOCK * BLOCK;
    if (size < 0 || needed > avail || size > Integer.MAX_VALUE)
      throw new IOException("The encrypted package is truncated");
    byte[] out = new byte[(int) needed];
    for (int s = 0, from = 0; from < needed; s++, from += SEGMENT) {
      int n = (int) Math.min(SEGMENT, needed - from);
      byte[] seg = new byte[n];
      System.arraycopy(pkg, 8 + from, seg, 0, n);
      byte[] dec = aes(false, key, makeIv(dataSalt, le32(s)), seg);
      System.arraycopy(dec, 0, out, from, n);
    }
    byte[] r = new byte[(int) size];
    System.arraycopy(out, 0, r, 0, r.length);
    return r;
  }

  private static void checkParams(Element e) throws IOException {
    if (!"AES".equals(e.getAttribute("cipherAlgorithm"))) throw new IOException("Unsupported cipher");
    if (!"ChainingModeCBC".equals(e.getAttribute("cipherChaining"))) throw new IOException("Unsupported chaining mode");
    if (!"SHA512".equals(e.getAttribute("hashAlgorithm")))
      throw new IOException("Unsupported hash algorithm (only SHA512 is supported)");
    if (number(e, "blockSize") != BLOCK) throw new IOException("Unsupported block size");
    int bits = number(e, "keyBits");
    if (bits != 128 && bits != 192 && bits != 256) throw new IOException("Unsupported AES key size");
    if (number(e, "hashSize") != HASH_SIZE) throw new IOException("hashSize does not match SHA512");
    if (b64attr(e, "saltValue").length != number(e, "saltSize")) throw new IOException("saltSize mismatch");
  }

  private static int number(Element e, String name) throws IOException {
    var v = e.getAttribute(name);
    if (v == null) throw new IOException(name + " is missing");
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException x) {
      throw new IOException(name + " is not a number");
    }
  }

  private static byte[] b64attr(Element e, String name) throws IOException {
    var v = e.getAttribute(name);
    if (v == null) throw new IOException(name + " is missing");
    var sb = new StringBuilder();
    for (int i = 0; i < v.length(); i++) if (v.charAt(i) > ' ') sb.append(v.charAt(i));
    try {
      return Base64.decode(utf8(sb.toString()));
    } catch (RuntimeException x) {
      throw new IOException(name + " is not valid base64");
    }
  }
}
