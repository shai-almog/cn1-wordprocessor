package com.codenameone.wordprocessor.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import com.codename1.impl.android.AndroidNativeUtil;
import java.io.OutputStream;

/** Storage Access Framework: ACTION_CREATE_DOCUMENT shows the system Save dialog. */
public class NativeFilesImpl {
  public String saveAs(String name, String mime, byte[] data) {
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType(mime);
    intent.putExtra(Intent.EXTRA_TITLE, name);
    final Object lock = new Object();
    final Uri[] chosen = new Uri[1];
    final boolean[] done = new boolean[1];
    AndroidNativeUtil.startActivityForResult(intent, (requestCode, resultCode, result) -> {
      synchronized (lock) {
        if (resultCode == Activity.RESULT_OK && result != null) chosen[0] = result.getData();
        done[0] = true;
        lock.notify();
      }
    });
    synchronized (lock) {
      while (!done[0]) {
        try {
          lock.wait();
        } catch (InterruptedException e) {
          return null;
        }
      }
    }
    if (chosen[0] == null) return null;
    String error = writeFile(chosen[0].toString(), data);
    return error == null ? chosen[0].toString() : "error:" + error;
  }

  public String writeFile(String location, byte[] data) {
    // Document providers cannot replace a file atomically, and "wt" truncates before writing.
    // So the current contents are kept first; if the new write fails they are written back,
    // and if even that fails the copy stays in app storage as a recoverable previous version.
    android.content.ContentResolver resolver = AndroidNativeUtil.getContext().getContentResolver();
    Uri uri = Uri.parse(location);
    byte[] previous = null;
    try (java.io.InputStream in = resolver.openInputStream(uri)) {
      if (in != null) {
        java.io.ByteArrayOutputStream copy = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) != -1; ) copy.write(buffer, 0, n);
        previous = copy.toByteArray();
      }
    } catch (Exception newFile) {
      // nothing to preserve
    }
    String error = write(resolver, uri, data);
    if (error == null || previous == null) return error;
    if (write(resolver, uri, previous) == null) return error + " (the previous version was restored)";
    java.io.File kept = new java.io.File(AndroidNativeUtil.getContext().getFilesDir(), "previous-version.bin");
    try (OutputStream out = new java.io.FileOutputStream(kept)) {
      out.write(previous);
      return error + " (the previous version was kept in app storage)";
    } catch (Exception lost) {
      return error;
    }
  }

  private static String write(android.content.ContentResolver resolver, Uri uri, byte[] data) {
    // "wt" truncates: a shorter document must not leave the old file's tail behind.
    try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
      if (out == null) return "The document provider refused the file";
      out.write(data);
      return null;
    } catch (Exception e) {
      return e.getMessage() == null ? e.toString() : e.getMessage();
    }
  }

  public boolean isSupported() {
    return true;
  }
}
