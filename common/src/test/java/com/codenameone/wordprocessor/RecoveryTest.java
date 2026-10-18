package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

public class RecoveryTest {
  @Test
  public void tornWriteRecoversPreviousGeneration() throws Exception {
    final var data = new HashMap<String, byte[]>();
    var b =
        new RecoveryStore.Backend() {
          public byte[] read(String k) {
            return data.get(k);
          }

          public void write(String k, byte[] bytes) {
            data.put(k, bytes);
          }
        };
    var store = new RecoveryStore(b);
    var s = new Session(Document.empty());
    s.replace("first");
    store.save(s.document());
    s.replace(" second");
    store.save(s.document());
    data.put("wordprocessor-recovery-0", new byte[] {1, 2, 3});
    assertEquals("first", new RecoveryStore(b).recover().plainText());
  }

  @Test
  public void checksumDetectsCorruption() throws Exception {
    final var data = new HashMap<String, byte[]>();
    var store =
        new RecoveryStore(
            new RecoveryStore.Backend() {
              public byte[] read(String k) {
                return data.get(k);
              }

              public void write(String k, byte[] bytes) {
                data.put(k, bytes);
              }
            });
    store.save(Document.empty());
    var bytes = data.get("wordprocessor-recovery-1");
    bytes[bytes.length - 10] ^= 1;
    assertNull(store.recover());
  }

  @Test
  public void delayedAutosaveCannotOverwriteNewerSave() throws Exception {
    final var data = new HashMap<String, byte[]>();
    var store =
        new RecoveryStore(
            new RecoveryStore.Backend() {
              public byte[] read(String k) {
                return data.get(k);
              }

              public void write(String k, byte[] bytes) {
                data.put(k, bytes);
              }
            });
    var s = new Session(Document.empty());
    s.replace("old");
    var old = s.document();
    s.replace(" latest");
    store.save(s.document(), null, 2);
    store.save(old, null, 1);
    assertEquals("old latest", store.recover().plainText());
  }
}
