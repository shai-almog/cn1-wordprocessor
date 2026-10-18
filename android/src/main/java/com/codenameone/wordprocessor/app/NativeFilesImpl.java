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
    // "wt" truncates: a shorter document must not leave the old file's tail behind.
    try (OutputStream out = AndroidNativeUtil.getContext().getContentResolver().openOutputStream(Uri.parse(location), "wt")) {
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
