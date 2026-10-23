package com.codenameone.wordprocessor.app;

import com.codename1.io.FileSystemStorage;
import com.codename1.io.filechooser.FileTypeFilter;
import com.codename1.io.filechooser.SaveFileRequest;
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
            choose(callback, "cn1doc,docx,doc,odt,txt,md,html,rtf,adoc,tex");
          }

          public void openRecipients(WordProcessor.OpenCallback callback) {
            choose(callback, "csv,tsv,txt,xlsx,xlsm,ods");
          }

          public void openImage(WordProcessor.OpenCallback callback) {
            choose(callback, "png,jpg,jpeg,gif,bmp,tif,tiff,webp");
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
            if (existingPath == null) {
              saveAs(name, bytes, callback);
              return;
            }
            // Save rewrites the chosen location; one that can no longer be written (moved, or
            // outside the iOS sandbox) asks again.
            Display.getInstance()
                .scheduleBackgroundTask(
                    () -> {
                      try (OutputStream out =
                          FileSystemStorage.getInstance().openOutputStream(existingPath)) {
                        out.write(bytes);
                      } catch (IOException ex) {
                        Display.getInstance().callSerially(() -> saveAs(name, bytes, callback));
                        return;
                      }
                      done(callback, true, existingPath, existingPath);
                    });
          }
        });
  }

  private static void saveAs(String name, byte[] bytes, WordProcessor.SaveCallback callback) {
    String ext = name.substring(name.lastIndexOf('.') + 1);
    Display.getInstance()
        .showSaveDialog(
            new SaveFileRequest()
                .suggestedName(name)
                .confirmOverwrite(true)
                .addFilter(new FileTypeFilter(ext.toUpperCase()).extensions(ext).mimeTypes(mime(name)))
                .content((result, out) -> out.write(bytes)),
            result -> {
              if (result.isCancelled()) callback.saved(false, null, null);
              else if (result.getError() != null)
                callback.saved(false, null, String.valueOf(result.getError().getMessage()));
              else callback.saved(true, result.getPath(), result.getPath());
            });
  }

  private static void done(WordProcessor.SaveCallback callback, boolean ok, String path, String message) {
    Display.getInstance().callSerially(() -> callback.saved(ok, path, message));
  }

  private static String mime(String name) {
    if (name.endsWith(".pdf")) return "application/pdf";
    if (name.endsWith(".docx"))
      return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (name.endsWith(".odt")) return "application/vnd.oasis.opendocument.text";
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
