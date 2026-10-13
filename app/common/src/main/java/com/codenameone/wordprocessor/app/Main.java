package com.codenameone.wordprocessor.app;

import com.codename1.io.FileSystemStorage;
import com.codename1.ui.Dialog;
import com.codename1.ui.Display;
import com.codenameone.wordprocessor.WordProcessor;
import java.io.*;

/** Native/mobile/browser lifecycle; the application and model are shared Java libraries. */
public class Main extends com.codename1.system.Lifecycle {
  private WordProcessor app;

  public void init(Object context) {
    // The native stub constructs Main before Display.init(); create UI only here.
    app = new WordProcessor();
    app.init(context);
    app.setFiles(
        new WordProcessor.Files() {
          public void open(WordProcessor.OpenCallback callback) {
            Display.getInstance()
                .openFileChooser(
                    event -> {
                      if (!(event.getSource() instanceof String)) return;
                      String path = (String) event.getSource();
                      Display.getInstance()
                          .scheduleBackgroundTask(
                              () -> {
                                try {
                                  ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                                  try (InputStream in =
                                      FileSystemStorage.getInstance().openInputStream(path)) {
                                    byte[] buffer = new byte[8192];
                                    int count;
                                    while ((count = in.read(buffer)) != -1) {
                                      if (bytes.size() + count > 128 * 1024 * 1024)
                                        throw new IOException("File exceeds 128 MiB");
                                      bytes.write(buffer, 0, count);
                                    }
                                  }
                                  byte[] data = bytes.toByteArray();
                                  Display.getInstance()
                                      .callSerially(
                                          () ->
                                              callback.opened(
                                                  path.substring(path.lastIndexOf('/') + 1), data));
                                } catch (IOException ex) {
                                  Display.getInstance()
                                      .callSerially(
                                          () ->
                                              Dialog.show(
                                                  "Open failed", ex.getMessage(), "OK", null));
                                }
                              });
                    },
                    "cn1doc,docx,txt,md,html,rtf,adoc");
          }

          public void save(String name, byte[] bytes, WordProcessor.SaveCallback callback) {
            if (Display.getInstance().downloadBytesAsFile(name, bytes)) {
              callback.saved(true, "download offered: " + name);
              return;
            }
            Display.getInstance()
                .scheduleBackgroundTask(
                    () -> {
                      try {
                        String path = FileSystemStorage.getInstance().getAppHomePath() + name;
                        try (OutputStream out =
                            FileSystemStorage.getInstance().openOutputStream(path)) {
                          out.write(bytes);
                        }
                        Display.getInstance()
                            .callSerially(
                                () -> {
                                  callback.saved(true, "local export: " + name);
                                  Display.getInstance()
                                      .share(path, null, "application/octet-stream");
                                });
                      } catch (IOException ex) {
                        Display.getInstance()
                            .callSerially(() -> callback.saved(false, ex.getMessage()));
                      }
                    });
          }
        });
  }

  public void start() {
    app.start();
  }

  public void stop() {
    app.stop();
  }

  public void destroy() {
    app.destroy();
  }
}
