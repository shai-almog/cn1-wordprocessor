package com.codenameone.wordprocessor.app;

/** No AppKit here: the framework's native menu bridge carries the commands. */
public class NativeDesktopImpl {
  public boolean install(String spec) {
    return false;
  }

  public void check(int id, boolean on) {}

  public void about(String version, String credits) {}

  public void fitWindow(int widthPercent, int heightPercent) {}

  public boolean isSupported() {
    return false;
  }
}
