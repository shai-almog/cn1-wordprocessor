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
