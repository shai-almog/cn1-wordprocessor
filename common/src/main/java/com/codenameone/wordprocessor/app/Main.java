package com.codenameone.wordprocessor.app;

import com.codename1.io.FileSystemStorage;
import com.codename1.system.NativeLookup;
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
            choose(callback, "cn1doc,docx,txt,md,html,rtf,adoc");
          }

          public void openRecipients(WordProcessor.OpenCallback callback) {
            choose(callback, "csv,tsv,txt,xlsx,xlsm,ods");
          }

          public void openImage(WordProcessor.OpenCallback callback) {
            choose(callback, "png,jpg,jpeg");
          }

          private void choose(WordProcessor.OpenCallback callback, String extensions) {
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
                                                  path.substring(path.lastIndexOf('/') + 1),
                                                  path,
                                                  data));
                                } catch (IOException ex) {
                                  Display.getInstance()
                                      .callSerially(
                                          () ->
                                              Dialog.show(
                                                  "Open failed", ex.getMessage(), "OK", null));
                                }
                              });
                    },
                    extensions);
          }

          public void save(
              String name, String existingPath, byte[] bytes, WordProcessor.SaveCallback callback) {
            if (Display.getInstance().downloadBytesAsFile(name, bytes)) {
              callback.saved(true, null, "download offered: " + name);
              return;
            }
            Display.getInstance()
                .scheduleBackgroundTask(
                    () -> {
                      try {
                        NativeFiles chooser = NativeLookup.create(NativeFiles.class);
                        boolean nativeSave = chooser != null && chooser.isSupported();
                        String selected = existingPath;
                        if (nativeSave && selected == null) selected = chooser.chooseSaveFile(name);
                        if (nativeSave && selected == null) {
                          Display.getInstance()
                              .callSerially(() -> callback.saved(false, null, null));
                          return;
                        }
                        final String path =
                            nativeSave
                                ? selected
                                : FileSystemStorage.getInstance().getAppHomePath() + name;
                        if (nativeSave) {
                          String error = chooser.writeFile(path, bytes);
                          if (error != null) throw new IOException(error);
                        } else
                          try (OutputStream out =
                              FileSystemStorage.getInstance().openOutputStream(path)) {
                            out.write(bytes);
                          }
                        Display.getInstance()
                            .callSerially(
                                () -> {
                                  callback.saved(
                                      true,
                                      nativeSave ? path : null,
                                      nativeSave ? path : "local export: " + name);
                                  if (!nativeSave)
                                    Display.getInstance().share(path, null, mime(name));
                                });
                      } catch (IOException ex) {
                        Display.getInstance()
                            .callSerially(() -> callback.saved(false, null, ex.getMessage()));
                      }
                    });
          }
        });
  }

  private static String mime(String name) {
    if (name.endsWith(".pdf")) return "application/pdf";
    if (name.endsWith(".docx"))
      return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (name.endsWith(".txt")) return "text/plain";
    if (name.endsWith(".html")) return "text/html";
    return "application/octet-stream";
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
