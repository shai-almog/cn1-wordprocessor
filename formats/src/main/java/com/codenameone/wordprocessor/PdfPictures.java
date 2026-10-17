package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** PDF image XObjects. PNG samples are unfiltered once; alpha is a separate soft mask. */
final class PdfPictures {
  static int add(List<byte[]> objects, Picture picture) throws IOException {
    byte[] data = picture.bytes();
    if ("image/jpeg".equals(picture.mime)) {
      if (picture.channels != 1 && picture.channels != 3 && picture.channels != 4)
        throw new IOException("Unsupported JPEG color space");
      String space =
          picture.channels == 1 ? "DeviceGray" : picture.channels == 3 ? "DeviceRGB" : "DeviceCMYK";
      objects.add(
          stream(
              picture, "/ColorSpace /" + space + " /BitsPerComponent 8 /Filter /DCTDecode", data));
      return objects.size();
    }
    Png png = new Png(data, picture);
    String mask = "";
    if (png.alpha != null) {
      objects.add(
          stream(
              picture,
              "/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode",
              compress(png.alpha)));
      mask = " /SMask " + objects.size() + " 0 R";
    }
    objects.add(
        stream(
            picture,
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode" + mask,
            compress(png.rgb)));
    return objects.size();
  }

  private static byte[] compress(byte[] b) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DeflaterOutputStream z = new DeflaterOutputStream(out);
    z.write(b);
    z.close();
    return out.toByteArray();
  }

  private static byte[] stream(Picture p, String properties, byte[] b) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(
        ("<< /Type /XObject /Subtype /Image /Width "
                + p.pixelWidth
                + " /Height "
                + p.pixelHeight
                + " "
                + properties
                + " /Length "
                + b.length
                + " >>\nstream\n")
            .getBytes("ISO-8859-1"));
    out.write(b);
    out.write("\nendstream".getBytes("ISO-8859-1"));
    return out.toByteArray();
  }

  static final class Png {
    final byte[] rgb, alpha;

    Png(byte[] data, Picture p) throws IOException {
      int bits = data[24] & 255,
          type = data[25] & 255,
          channels = type == 0 || type == 3 ? 1 : type == 2 ? 3 : type == 4 ? 2 : type == 6 ? 4 : 0;
      if (channels == 0
          || !(bits == 1 || bits == 2 || bits == 4 || bits == 8 || bits == 16)
          || (type == 2 || type == 4 || type == 6) && bits < 8
          || type == 3 && bits == 16
          || data[26] != 0
          || data[27] != 0
          || data[28] != 0)
        throw new IOException("PDF requires a non-interlaced PNG with a standard color format");
      byte[] palette = null, transparency = null;
      ByteArrayOutputStream compressed = new ByteArrayOutputStream();
      boolean ended = false;
      for (int at = 8; at + 12 <= data.length; ) {
        int n = integer(data, at);
        if (n < 0 || n > data.length - at - 12) throw new IOException("Invalid PNG chunk");
        CRC32 crc = new CRC32();
        crc.update(data, at + 4, n + 4);
        if ((int) crc.getValue() != integer(data, at + 8 + n))
          throw new IOException("PNG checksum mismatch");
        String name = new String(data, at + 4, 4, "ISO-8859-1");
        if ("IDAT".equals(name)) compressed.write(data, at + 8, n);
        else if ("PLTE".equals(name)) {
          palette = new byte[n];
          System.arraycopy(data, at + 8, palette, 0, n);
        } else if ("tRNS".equals(name)) {
          transparency = new byte[n];
          System.arraycopy(data, at + 8, transparency, 0, n);
        } else if ("IEND".equals(name)) {
          ended = true;
          break;
        }
        at += n + 12;
      }
      if (!ended || type == 3 && (palette == null || palette.length % 3 != 0))
        throw new IOException("Incomplete PNG");
      if (transparency != null
          && (type == 0 && transparency.length != 2
              || type == 2 && transparency.length != 6
              || type == 3 && transparency.length > palette.length / 3))
        throw new IOException("Invalid PNG transparency");
      int rowBytes = (p.pixelWidth * channels * bits + 7) / 8,
          bpp = Math.max(1, (channels * bits + 7) / 8);
      rgb = new byte[p.pixelWidth * p.pixelHeight * 3];
      alpha =
          type == 4 || type == 6 || transparency != null
              ? new byte[p.pixelWidth * p.pixelHeight]
              : null;
      byte[] previous = new byte[rowBytes], row = new byte[rowBytes];
      InflaterInputStream in =
          new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray()));
      try {
        for (int y = 0; y < p.pixelHeight; y++) {
          int filter = in.read();
          if (filter < 0 || filter > 4) throw new IOException("Invalid PNG row filter");
          int read = 0;
          while (read < rowBytes) {
            int n = in.read(row, read, rowBytes - read);
            if (n < 0) throw new IOException("Truncated PNG pixels");
            read += n;
          }
          for (int x = 0; x < rowBytes; x++) {
            int a = x < bpp ? 0 : row[x - bpp] & 255,
                b = previous[x] & 255,
                c = x < bpp ? 0 : previous[x - bpp] & 255;
            int predict =
                filter == 0
                    ? 0
                    : filter == 1
                        ? a
                        : filter == 2 ? b : filter == 3 ? (a + b) / 2 : paeth(a, b, c);
            row[x] = (byte) ((row[x] & 255) + predict);
          }
          for (int x = 0; x < p.pixelWidth; x++) {
            int off = x * channels, v = sample(row, off, bits), r, g, b, a = 255;
            if (type == 3) {
              if (v * 3 + 2 >= palette.length) throw new IOException("Invalid PNG palette index");
              r = palette[v * 3] & 255;
              g = palette[v * 3 + 1] & 255;
              b = palette[v * 3 + 2] & 255;
              if (transparency != null && v < transparency.length) a = transparency[v] & 255;
            } else if (type == 0 || type == 4) {
              r = g = b = scale(v, bits);
              if (type == 4) a = scale(sample(row, off + 1, bits), bits);
              else if (transparency != null && v == word(transparency, 0)) a = 0;
            } else {
              int green = sample(row, off + 1, bits), blue = sample(row, off + 2, bits);
              r = scale(v, bits);
              g = scale(green, bits);
              b = scale(blue, bits);
              if (type == 6) a = scale(sample(row, off + 3, bits), bits);
              else if (transparency != null
                  && v == word(transparency, 0)
                  && green == word(transparency, 2)
                  && blue == word(transparency, 4)) a = 0;
            }
            int pixel = y * p.pixelWidth + x;
            rgb[pixel * 3] = (byte) r;
            rgb[pixel * 3 + 1] = (byte) g;
            rgb[pixel * 3 + 2] = (byte) b;
            if (alpha != null) alpha[pixel] = (byte) a;
          }
          byte[] swap = previous;
          previous = row;
          row = swap;
        }
        if (in.read() != -1) throw new IOException("Unexpected PNG pixel data");
      } finally {
        in.close();
      }
    }

    private static int sample(byte[] row, int at, int bits) {
      if (bits == 16) return word(row, at * 2);
      if (bits == 8) return row[at] & 255;
      int shift = 8 - bits - (at * bits) % 8;
      return (row[at * bits / 8] >> shift) & ((1 << bits) - 1);
    }

    private static int scale(int value, int bits) {
      return bits == 16 ? value >> 8 : bits == 8 ? value : value * 255 / ((1 << bits) - 1);
    }

    private static int paeth(int a, int b, int c) {
      int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }
  }

  private static int word(byte[] b, int at) {
    return (b[at] & 255) << 8 | b[at + 1] & 255;
  }

  private static int integer(byte[] b, int at) {
    return (b[at] & 255) << 24 | (b[at + 1] & 255) << 16 | (b[at + 2] & 255) << 8 | b[at + 3] & 255;
  }
}
