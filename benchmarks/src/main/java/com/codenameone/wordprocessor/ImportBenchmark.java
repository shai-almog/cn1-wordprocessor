package com.codenameone.wordprocessor;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real DOCX parse plus plain-text extraction, excluding startup, file I/O and layout. */
public final class ImportBenchmark {
  public static void main(String[] args) throws Exception {
    byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
    int warmup = Integer.parseInt(args[1]), iterations = Integer.parseInt(args[2]);
    if (warmup < 0 || iterations < 1 || iterations > 10000) throw new IllegalArgumentException();
    var samples = new ArrayList<Double>();
    var text = "";
    for (int i = -warmup; i < iterations; i++) {
      long start = System.nanoTime();
      text = Docx.read(new ByteArrayInputStream(bytes)).document.plainText();
      long elapsed = System.nanoTime() - start;
      if (i >= 0) samples.add(elapsed / 1000000.0);
    }
    Files.write(Paths.get(args[3]), text.getBytes("UTF-8"));
    System.out.println(Json.write(Json.object("samplesMs", samples)));
  }
}
