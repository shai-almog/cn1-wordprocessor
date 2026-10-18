package com.codenameone.wordprocessor;

import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import com.codename1.ui.Display;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Loads bundled proofing resources (raw-deflate or plain). */
final class ProofData {
  private ProofData() {}

  static byte[] bytes(String name, boolean deflated) {
    InputStream in = ProofData.class.getResourceAsStream(name);
    // Native Codename One ports find bundle files only through Display. Android keeps the
    // folder (assets/proof/en-us.dic); the Apple ports flatten it ("/en-us.dic"). Without this
    // the native dictionary was empty: nearly every word was flagged and nothing hyphenated.
    if (in == null && Display.isInitialized()) {
      in = Display.getInstance().getResourceAsStream(ProofData.class, name);
      if (in == null)
        in = Display.getInstance().getResourceAsStream(ProofData.class, name.substring(name.lastIndexOf('/')));
    }
    if (in == null) return new byte[0];
    try (var closing = in) {
      if (deflated) in = new InflaterInputStream(in, new Inflater(true));
      ByteArrayOutputStream out = new ByteArrayOutputStream(deflated ? 1 << 21 : 1 << 17);
      byte[] buffer = new byte[16384];
      for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) out.write(buffer, 0, n);
      return out.toByteArray();
    } catch (Exception e) {
      return new byte[0];
    }
  }

  static String text(String name) {
    return new String(bytes(name, false), java.nio.charset.StandardCharsets.UTF_8);
  }
}
