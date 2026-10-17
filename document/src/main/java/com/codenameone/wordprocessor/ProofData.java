package com.codenameone.wordprocessor;

import com.codename1.io.gzip.Inflater;
import com.codename1.io.gzip.InflaterInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Loads bundled proofing resources (raw-deflate or plain) with Codename One's own inflater. */
final class ProofData {
  private ProofData() {}

  static byte[] bytes(String name, boolean deflated) {
    InputStream in = ProofData.class.getResourceAsStream(name);
    if (in == null) return new byte[0];
    try {
      if (deflated) in = new InflaterInputStream(in, new Inflater(true));
      ByteArrayOutputStream out = new ByteArrayOutputStream(deflated ? 1 << 21 : 1 << 17);
      byte[] buffer = new byte[16384];
      for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) out.write(buffer, 0, n);
      return out.toByteArray();
    } catch (Exception e) {
      return new byte[0];
    } finally {
      try {
        in.close();
      } catch (Exception ignored) {
      }
    }
  }

  static String text(String name) {
    try {
      return new String(bytes(name, false), "UTF-8");
    } catch (Exception e) {
      return "";
    }
  }
}
