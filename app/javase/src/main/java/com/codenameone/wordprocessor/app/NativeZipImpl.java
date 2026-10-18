package com.codenameone.wordprocessor.app;

/** The JVM's own java.util.zip is already native here; ZipParts keeps using it. */
public class NativeZipImpl implements com.codenameone.wordprocessor.app.NativeZip {
  public byte[] deflate(byte[] data) {
    return null;
  }

  public int crc32(byte[] data) {
    return 0;
  }

  public boolean isSupported() {
    return false;
  }
}
