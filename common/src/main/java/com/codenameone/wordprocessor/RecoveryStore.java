package com.codenameone.wordprocessor;

import com.codename1.io.Storage;
import java.io.*;
import java.util.zip.CRC32;

/**
 * Two independent checksummed slots; a torn newest write leaves the previous document recoverable.
 */
public final class RecoveryStore {
  public interface Backend {
    byte[] read(String key) throws IOException;

    void write(String key, byte[] bytes) throws IOException;
  }

  private final Backend backend;
  private long generation;
  private long latestRevision = -1;
  private Docx.Opened recoveredSource;

  public Docx.Opened recoveredSource() {
    return recoveredSource;
  }

  public RecoveryStore(Backend backend) {
    this.backend = backend;
  }

  public static RecoveryStore local() {
    return new RecoveryStore(
        new Backend() {
          public byte[] read(String key) throws IOException {
            if (!Storage.getInstance().exists(key)) return null;
            var in = Storage.getInstance().createInputStream(key);
            try {
              var out = new ByteArrayOutputStream();
              byte[] b = new byte[8192];
              int n;
              while ((n = in.read(b)) != -1) {
                if (out.size() + n > 128 * 1024 * 1024)
                  throw new IOException("Recovery file too large");
                out.write(b, 0, n);
              }
              return out.toByteArray();
            } finally {
              in.close();
            }
          }

          public void write(String key, byte[] bytes) throws IOException {
            var out = Storage.getInstance().createOutputStream(key);
            try {
              out.write(bytes);
              out.flush();
            } finally {
              out.close();
            }
          }
        });
  }

  private static final class Record {
    final long sequence;
    final Document document;
    final Docx.Opened source;

    Record(long s, ProjectFile.Opened d) {
      sequence = s;
      document = d.document;
      source = d.source;
    }
  }

  private Record slot(int n) {
    try {
      var data = backend.read("wordprocessor-recovery-" + n);
      if (data == null) return null;
      var in = new DataInputStream(new ByteArrayInputStream(data));
      if (in.readInt() != 0x57503132) return null;
      long seq = in.readLong(), checksum = in.readLong();
      int length = in.readInt();
      if (length < 0 || length > 128 * 1024 * 1024 || length != data.length - 24) return null;
      byte[] xml = new byte[length];
      in.readFully(xml);
      var crc = new CRC32();
      crc.update(xml);
      if (crc.getValue() != checksum) return null;
      return new Record(seq, ProjectFile.read(xml));
    } catch (Exception e) {
      return null;
    }
  }

  public synchronized Document recover() {
    Record a = slot(0), b = slot(1);
    Record best = a == null ? b : b == null ? a : a.sequence > b.sequence ? a : b;
    if (best == null) return null;
    generation = best.sequence;
    recoveredSource = best.source;
    return best.document;
  }

  public void save(Document doc) throws IOException {
    save(doc, null);
  }

  public synchronized void save(Document doc, Docx.Opened source) throws IOException {
    save(doc, source, latestRevision + 1);
  }

  public synchronized void save(Document doc, Docx.Opened source, long revision)
      throws IOException {
    if (revision < latestRevision) return;
    byte[] xml = ProjectFile.write(doc, source);
    if (xml.length > 128 * 1024 * 1024) throw new IOException("Recovery document exceeds 128 MiB");
    var crc = new CRC32();
    crc.update(xml);
    long next = generation + 1;
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeInt(0x57503132);
    out.writeLong(next);
    out.writeLong(crc.getValue());
    out.writeInt(xml.length);
    out.write(xml);
    out.flush();
    int index = (int) (next % 2);
    backend.write("wordprocessor-recovery-" + index, bytes.toByteArray());
    var check = slot(index);
    if (check == null || check.sequence != next)
      throw new IOException("Recovery verification failed");
    generation = next;
    latestRevision = revision;
  }
}
