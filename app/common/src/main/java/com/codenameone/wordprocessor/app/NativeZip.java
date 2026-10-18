package com.codenameone.wordprocessor.app;

import com.codename1.system.NativeInterface;

/** System zlib for DOCX saves where the platform has it; see ZipParts. */
public interface NativeZip extends NativeInterface {
  /** Raw (headerless) deflate at the fastest level. */
  byte[] deflate(byte[] data);

  int crc32(byte[] data);
}
