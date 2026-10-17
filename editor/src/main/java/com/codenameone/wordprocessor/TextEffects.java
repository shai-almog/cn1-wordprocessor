package com.codenameone.wordprocessor;

import com.codename1.ui.*;
import java.util.*;

/** Canvas text effects; bounded transparent outline masks preserve underlying content. */
final class TextEffects {
  private static final int PIXEL_BUDGET = 262144;
  private int pixels;
  private final LinkedHashMap<Key, Image> outlines = new LinkedHashMap<Key, Image>(32, .75f, true);

  void draw(Graphics g, String text, int x, int y, Font font, TextStyle style, float scale) {
    if (text.isEmpty()) return;
    CharacterFormat c = style.characters;
    int color = g.getColor();
    int offset = Math.max(1, Math.round(scale));
    if (c.shading >= 0 || c.border >= 0 || !"none".equals(c.enclose) || !c.ruby.isEmpty()) {
      int w = font.stringWidth(text), h = font.getHeight();
      if (c.shading >= 0) {
        g.setColor(c.shading);
        g.fillRect(x, y, w, h);
      }
      g.setColor(c.border >= 0 ? c.border : color);
      if (c.border >= 0) g.drawRect(x, y, w, h);
      int d = Math.max(w, h);
      if ("circle".equals(c.enclose)) g.drawArc(x + w / 2 - d / 2, y + h / 2 - d / 2, d, d, 0, 360);
      else if ("square".equals(c.enclose)) g.drawRect(x + w / 2 - d / 2, y + h / 2 - d / 2, d, d);
      else if ("triangle".equals(c.enclose)) {
        g.drawLine(x + w / 2, y - d / 4, x - d / 4, y + h);
        g.drawLine(x - d / 4, y + h, x + w + d / 4, y + h);
        g.drawLine(x + w + d / 4, y + h, x + w / 2, y - d / 4);
      } else if ("diamond".equals(c.enclose)) {
        int cx = x + w / 2, cy = y + h / 2, r = d * 3 / 4;
        g.drawLine(cx, cy - r, cx + r, cy);
        g.drawLine(cx + r, cy, cx, cy + r);
        g.drawLine(cx, cy + r, cx - r, cy);
        g.drawLine(cx - r, cy, cx, cy - r);
      }
      if (!c.ruby.isEmpty()) {
        // Phonetic guide: drawn above the base text in the same font, centered.
        g.setColor(color);
        g.drawString(c.ruby, x + (w - font.stringWidth(c.ruby)) / 2, y - h * 3 / 4);
      }
      g.setColor(color);
    }
    if (c.shadow) {
      g.setColor(0x808080);
      g.drawString(text, x + offset, y + offset);
    }
    if (c.emboss || c.engrave) {
      g.setColor(c.engrave ? 0x707070 : 0xffffff);
      g.drawString(text, x - offset, y - offset);
      g.setColor(c.engrave ? 0xffffff : 0x707070);
      g.drawString(text, x + offset, y + offset);
    }
    g.setColor(color);
    if (!c.outline) {
      g.drawString(text, x, y);
      return;
    }
    int radius = Math.max(1, Math.round(scale * .5f));
    int height = font.getHeight() + 2 * radius, width = font.stringWidth(text) + 2 * radius;
    // Tile unusually wide runs to bound temporary bitmap storage without splitting shaping.
    int tile = Math.max(1, Math.min(width, 65536 / Math.max(1, height)));
    for (int at = 0; at < width; at += tile) {
      int size = Math.min(tile, width - at);
      Key key = new Key(font, text, color, radius, at, size);
      Image mask = outlines.get(key);
      if (mask == null) {
        Image source = Image.createImage(size + 2 * radius, height, 0);
        Graphics canvas = source.getGraphics();
        canvas.setFont(font);
        canvas.setColor(0xffffff);
        canvas.drawString(text, 2 * radius - at, radius);
        int sw = source.getWidth();
        int[] raw = source.getRGB(), out = new int[size * height];
        for (int row = 0; row < height; row++)
          for (int col = 0; col < size; col++) {
            int center = raw[row * sw + col + radius] >>> 24, maximum = center;
            for (int dy = -radius; dy <= radius; dy++)
              for (int dx = -radius; dx <= radius; dx++) {
                int yy = row + dy;
                if (yy >= 0 && yy < height)
                  maximum = Math.max(maximum, raw[yy * sw + col + radius + dx] >>> 24);
              }
            out[row * size + col] = (maximum - center) << 24 | color;
          }
        mask = Image.createImage(out, size, height);
        if (out.length <= PIXEL_BUDGET) {
          while (!outlines.isEmpty()
              && (pixels + out.length > PIXEL_BUDGET || outlines.size() >= 64)) {
            Iterator<Map.Entry<Key, Image>> entries = outlines.entrySet().iterator();
            Image old = entries.next().getValue();
            pixels -= old.getWidth() * old.getHeight();
            entries.remove();
          }
          outlines.put(key, mask);
          pixels += out.length;
        }
      }
      g.drawImage(mask, x + at - radius, y - radius);
    }
  }

  private static final class Key {
    final Font font;
    final String text;
    final int color, radius, at, width;

    Key(Font f, String t, int c, int r, int a, int w) {
      font = f;
      text = t;
      color = c;
      radius = r;
      at = a;
      width = w;
    }

    public int hashCode() {
      return text.hashCode() * 31 + font.hashCode() + color + at * 13 + radius * 17 + width;
    }

    public boolean equals(Object other) {
      if (!(other instanceof Key)) return false;
      Key k = (Key) other;
      return font == k.font
          && text.equals(k.text)
          && color == k.color
          && radius == k.radius
          && at == k.at
          && width == k.width;
    }
  }
}
