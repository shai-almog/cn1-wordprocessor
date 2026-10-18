package com.codenameone.wordprocessor.app;

public class NativeFilesImpl implements com.codenameone.wordprocessor.app.NativeFiles {
  public String chooseSaveFile(String param) {
    return null;
  }

  public String writeFile(String path, byte[] data) {
    return "Native file saving unavailable";
  }

  public boolean isSupported() {
    return false;
  }
}
