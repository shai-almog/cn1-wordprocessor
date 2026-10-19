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
    // A temporary sibling moved over the target: a failed save leaves the old document intact.
    java.nio.file.Path target = new File(location).toPath().toAbsolutePath(), temp = null;
    try {
      temp = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
      try (java.nio.channels.FileChannel out =
          java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) out.write(buffer);
        out.force(true);
      }
      try {
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return null;
    } catch (Exception e) {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (Exception ignored) {
          // best effort
        }
      return e.getMessage() == null ? e.toString() : e.getMessage();
    }
  }

  public boolean isSupported() {
    return !java.awt.GraphicsEnvironment.isHeadless();
  }
}
