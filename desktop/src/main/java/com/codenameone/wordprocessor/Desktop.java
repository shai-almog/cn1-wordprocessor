package com.codenameone.wordprocessor;

import com.codename1.impl.javase.JavaSEPort;
import com.codename1.ui.Display;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import javax.swing.*;

/** Minimal desktop host; all application UI and document editing are Codename One. */
public final class Desktop {
  public static void main(String[] args) throws Exception {
    JavaSEPort.setAppHomeDir(System.getProperty("wordprocessor.home", ".cn1-wordprocessor"));
    JavaSEPort.setExposeFilesystem(true);
    JavaSEPort.setTablet(true);
    JavaSEPort.setUseNativeInput(true);
    JavaSEPort.setShowEDTWarnings(false);
    JavaSEPort.setShowEDTViolationStacks(false);
    JavaSEPort.setNativeTheme("/NativeTheme.res");
    JFrame frame = new JFrame("CN1 Word Processor");
    frame.setSize(1280, 900);
    frame.setLocationByPlatform(true);
    Display.init(frame.getContentPane());
    WordProcessor app = new WordProcessor();
    app.setFiles(
        new WordProcessor.Files() {
          public void open(WordProcessor.OpenCallback callback) {
            SwingUtilities.invokeLater(
                () -> {
                  JFileChooser chooser = new JFileChooser();
                  if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    File file = chooser.getSelectedFile();
                    try {
                      if (file.length() > 128 * 1024 * 1024)
                        throw new IOException("File exceeds 128 MiB");
                      byte[] bytes = Files.readAllBytes(file.toPath());
                      Display.getInstance()
                          .callSerially(() -> callback.opened(file.getName(), bytes));
                    } catch (IOException e) {
                      JOptionPane.showMessageDialog(frame, e.getMessage());
                    }
                  }
                });
          }

          public void save(String name, byte[] data, WordProcessor.SaveCallback callback) {
            SwingUtilities.invokeLater(
                () -> {
                  JFileChooser chooser = new JFileChooser();
                  chooser.setSelectedFile(new File(name));
                  if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
                  Path target = chooser.getSelectedFile().toPath().toAbsolutePath();
                  if (Files.exists(target)
                      && JOptionPane.showConfirmDialog(
                              frame,
                              "Replace " + target.getFileName() + "?",
                              "Replace file",
                              JOptionPane.OK_CANCEL_OPTION)
                          != JOptionPane.OK_OPTION) return;
                  try {
                    atomicWrite(target, data);
                    Display.getInstance()
                        .callSerially(() -> callback.saved(true, target.toString()));
                  } catch (IOException ex) {
                    Display.getInstance()
                        .callSerially(() -> callback.saved(false, ex.getMessage()));
                  }
                });
          }
        });
    frame.addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent event) {
            Display.getInstance()
                .callSerially(
                    () -> {
                      app.stop();
                      Display.getInstance().exitApplication();
                    });
          }
        });
    frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    SwingUtilities.invokeLater(() -> frame.setVisible(true));
    Display.getInstance()
        .callSerially(
            () -> {
              app.init(null);
              app.start();
              if (Boolean.getBoolean("wordprocessor.smoke")) {
                try {
                  Class.forName("com.codenameone.wordprocessor.UiSmoke")
                      .getMethod("run", WordProcessor.class)
                      .invoke(null, app);
                } catch (Exception e) {
                  e.printStackTrace();
                  System.exit(1);
                }
              }
              String capture = System.getProperty("wordprocessor.capture");
              if (capture != null)
                new com.codename1.ui.util.UITimer(
                        () -> {
                          try {
                            com.codename1.ui.Form form = Display.getInstance().getCurrent();
                            form.revalidate();
                            com.codename1.ui.Image image =
                                com.codename1.ui.Image.createImage(
                                    form.getWidth(), form.getHeight());
                            form.paintComponent(image.getGraphics(), true);
                            try (OutputStream out = new FileOutputStream(capture)) {
                              com.codename1.ui.util.ImageIO.getImageIO()
                                  .save(image, out, com.codename1.ui.util.ImageIO.FORMAT_PNG, 1);
                            }
                            System.out.println(
                                "RENDER_CAPTURE_OK " + form.getWidth() + "x" + form.getHeight());
                            System.exit(0);
                          } catch (Exception e) {
                            e.printStackTrace();
                            System.exit(1);
                          }
                        })
                    .schedule(1500, false, Display.getInstance().getCurrent());
            });
  }

  static void atomicWrite(Path target, byte[] bytes) throws IOException {
    Path tmp = Files.createTempFile(target.getParent(), ".wordprocessor-", ".tmp");
    try {
      try (java.nio.channels.FileChannel c =
          java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) c.write(buffer);
        c.force(true);
      }
      try {
        Files.move(
            tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
