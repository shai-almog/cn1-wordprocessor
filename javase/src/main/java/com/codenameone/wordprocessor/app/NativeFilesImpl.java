package com.codenameone.wordprocessor.app;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.nio.file.Files;

/** The AWT Save dialog for the simulator and JVM desktop builds. */
public class NativeFilesImpl {
  public String saveAs(String name, String mime, byte[] data) {
    final String[] chosen = new String[1];
    try {
      EventQueue.invokeAndWait(() -> {
        FileDialog dialog = new FileDialog((Frame) null, "Save", FileDialog.SAVE);
        dialog.setFile(name);
        dialog.setVisible(true);
        if (dialog.getFile() != null) chosen[0] = new File(dialog.getDirectory(), dialog.getFile()).getPath();
        dialog.dispose();
      });
    } catch (Exception e) {
      return "error:" + e.getMessage();
    }
    if (chosen[0] == null) return null;
    String error = writeFile(chosen[0], data);
    return error == null ? chosen[0] : "error:" + error;
  }

  public String writeFile(String location, byte[] data) {
    try {
      Files.write(new File(location).toPath(), data);
      return null;
    } catch (Exception e) {
      return e.getMessage();
    }
  }

  public boolean isSupported() {
    return !java.awt.GraphicsEnvironment.isHeadless();
  }
}
