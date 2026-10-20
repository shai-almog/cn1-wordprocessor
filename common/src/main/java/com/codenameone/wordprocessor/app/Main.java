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
    final NativeDesktop menu = NativeLookup.create(NativeDesktop.class);
    if (menu != null && menu.isSupported()) {
      NativeMenuCallback.listener = app::menuSelected;
      app.setMenuBar(
          new WordProcessor.MenuBar() {
            public boolean install(String spec) {
              // Keeps the native callback's Java entry point in the translated build.
              if (spec.isEmpty()) NativeMenuCallback.fire(0);
              return menu.install(spec);
            }

            public void check(int id, boolean on) {
              menu.check(id, on);
            }

            public void about(String version, String credits) {
              menu.about(version, credits);
            }

            public void fitWindow(int widthPercent, int heightPercent) {
              menu.fitWindow(widthPercent, heightPercent);
            }
          });
    }
    app.setFiles(
        new WordProcessor.Files() {
          public void open(WordProcessor.OpenCallback callback) {
            choose(callback, "cn1doc,docx,doc,txt,md,html,rtf,adoc");
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
            Display.getInstance()
                .scheduleBackgroundTask(
                    () -> {
                      NativeFiles dialog = NativeLookup.create(NativeFiles.class);
                      if (dialog != null && dialog.isSupported()) {
                        // Save rewrites the chosen location; a location that can no longer be
                        // written (moved, or exported outside the iOS sandbox) asks again.
                        if (existingPath != null && dialog.writeFile(existingPath, bytes) == null) {
                          done(callback, true, existingPath, existingPath);
                          return;
                        }
                        String location = dialog.saveAs(name, mime(name), bytes);
                        if (location == null) done(callback, false, null, null);
                        else if (location.startsWith("error:"))
                          done(callback, false, null, location.substring(6));
                        else done(callback, true, location, location);
                        return;
                      }
                      // No Save dialog (e.g. a browser without the File System Access API).
                      if (Display.getInstance().downloadBytesAsFile(name, bytes)) {
                        done(callback, true, null, "download offered: " + name);
                        return;
                      }
                      String path = FileSystemStorage.getInstance().getAppHomePath() + name;
                      try (OutputStream out = FileSystemStorage.getInstance().openOutputStream(path)) {
                        out.write(bytes);
                      } catch (IOException ex) {
                        done(callback, false, null, ex.getMessage());
                        return;
                      }
                      done(callback, true, null, "local export: " + name);
                      Display.getInstance().callSerially(() -> Display.getInstance().share(path, null, mime(name)));
                    });
          }
        });
  }

  private static void done(WordProcessor.SaveCallback callback, boolean ok, String path, String message) {
    Display.getInstance().callSerially(() -> callback.saved(ok, path, message));
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
