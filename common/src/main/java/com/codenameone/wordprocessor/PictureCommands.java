package com.codenameone.wordprocessor;

import java.util.Map;

/** Picture Format catalog ids. The picture is `block`, or the block holding the caret. */
final class PictureCommands implements Commands.Extension {
  /** Native image codec used by picture.compress; null in headless runs. */
  interface Codec {
    byte[] resample(
        byte[] data, String mime, float cropL, float cropT, float cropR, float cropB, int maxW, int maxH);
  }

  private static volatile Codec codec;

  static void codec(Codec value) {
    codec = value;
  }

  public void register(final Commands c) {
    c.edit("picture.crop", "block?:integer,left?:number,top?:number,right?:number,bottom?:number",
        "Crop edges as fractions of the source (0-0.95)", (s, a) -> {
          int block = block(s, a);
          var k = picture(s, block).look;
          s.pictureLook(
              block,
              k.crop(
                  f(a, "left", k.cropLeft),
                  f(a, "top", k.cropTop),
                  f(a, "right", k.cropRight),
                  f(a, "bottom", k.cropBottom)));
          return null;
        });
    c.edit("picture.border", "block?:integer,color?:string,width?:number",
        "Picture border color RRGGBB (or none) and width in points", (s, a) -> {
          int block = block(s, a);
          var k = picture(s, block).look;
          var color = a.get("color");
          int rgb =
              "none".equals(color)
                  ? -1
                  : color == null
                      ? (k.borderColor < 0 ? 0 : k.borderColor)
                      : Integer.parseInt(hex((String) color), 16);
          float width = f(a, "width", k.borderWidth > 0 ? k.borderWidth : .75f);
          s.pictureLook(block, k.border(rgb, width));
          return null;
        });
    c.edit("picture.corrections", "block?:integer,brightness?:integer,contrast?:integer",
        "Brightness and contrast, -100 to 100 percent", (s, a) -> {
          int block = block(s, a);
          var k = picture(s, block).look;
          s.pictureLook(
              block,
              k.adjust(
                  Commands.integer(a, "brightness", k.brightness),
                  Commands.integer(a, "contrast", k.contrast),
                  k.transparency,
                  k.colorMode));
          return null;
        });
    c.edit("picture.effects", "block?:integer,effect:string",
        "Artistic effect: none, blur, pencilSketch, posterize, negative, glowEdges or sharpen",
        (s, a) -> {
          int block = block(s, a);
          s.pictureLook(block, picture(s, block).look.effect(Commands.str(a, "effect")));
          return null;
        });
    c.edit("picture.removeBackground", "block?:integer,tolerance?:integer,off?:boolean",
        "Make the border-connected background transparent; tolerance 0-100, or off:true to restore",
        (s, a) -> {
          int block = block(s, a);
          boolean off = a.containsKey("off") && (Boolean) a.get("off");
          int tol = off ? -1 : Commands.integer(a, "tolerance", 20);
          s.pictureLook(block, picture(s, block).look.removeBackground(tol));
          return null;
        });
    c.edit("picture.compress", "block?:integer,ppi?:integer,deleteCropped?:boolean",
        "Compress pictures to a resolution (ppi, default 150) and optionally delete cropped areas",
        (s, a) -> {
          var k = codec;
          if (k == null)
            throw new IllegalArgumentException("Picture compression needs the native image codec");
          int block = block(s, a);
          Picture old = picture(s, block);
          int ppi = Commands.integer(a, "ppi", 150);
          if (ppi < 1) throw new IllegalArgumentException("ppi must be positive");
          boolean del = !a.containsKey("deleteCropped") || (Boolean) a.get("deleteCropped");
          var l = old.look;
          int maxW = Math.max(1, Math.round(old.width * ppi / 72f));
          int maxH = Math.max(1, Math.round(old.height * ppi / 72f));
          var out =
              k.resample(
                  old.bytes(),
                  old.mime,
                  del ? l.cropLeft : 0,
                  del ? l.cropTop : 0,
                  del ? l.cropRight : 0,
                  del ? l.cropBottom : 0,
                  maxW,
                  maxH);
          if (out == null || out.length == 0)
            throw new IllegalArgumentException("Picture compression failed");
          var next =
              new Picture(
                  old.id(), out, old.width, old.height, old.alt, old.align, del ? l.crop(0, 0, 0, 0) : l);
          var keep = s.selection();
          s.replaceDocument(s.document().blocks(s.document().blocks.set(block, next), s.document().nextId));
          s.selectRange(keep.anchorBlock, keep.anchor, keep.block, keep.caret);
          return null;
        });
    c.edit("picture.color", "block?:integer,mode:string",
        "Recolor: color, grayscale, washout or blackWhite", (s, a) -> {
          int block = block(s, a);
          var k = picture(s, block).look;
          s.pictureLook(block, k.adjust(k.brightness, k.contrast, k.transparency, Commands.str(a, "mode")));
          return null;
        });
    c.edit("picture.transparency", "block?:integer,percent:integer",
        "Picture transparency, 0 to 100 percent", (s, a) -> {
          int block = block(s, a);
          var k = picture(s, block).look;
          s.pictureLook(block, k.adjust(k.brightness, k.contrast, Commands.integer(a, "percent"), k.colorMode));
          return null;
        });
    c.edit("picture.style", "block?:integer,name:string",
        "Picture style: simpleFrameBlack, simpleFrameWhite, thickMatteBlack, thinGray or none",
        (s, a) -> {
          int block = block(s, a);
          var k = picture(s, block).look;
          String name = Commands.str(a, "name");
          Picture.Look next =
              "simpleFrameBlack".equals(name)
                  ? k.border(0x000000, 6)
                  : "simpleFrameWhite".equals(name)
                      ? k.border(0xffffff, 8)
                      : "thickMatteBlack".equals(name)
                          ? k.border(0x000000, 14)
                          : "thinGray".equals(name)
                              ? k.border(0x808080, 1)
                              : "none".equals(name) ? k.border(-1, 0) : null;
          if (next == null) throw new IllegalArgumentException("Unknown picture style: " + name);
          s.pictureLook(block, next);
          return null;
        });
    c.edit("picture.change", "block?:integer,base64:string",
        "Replace the picture's image (base64 PNG/JPEG/GIF/BMP/TIFF/WebP), keeping size, look and alt text",
        (s, a) -> {
          int block = block(s, a);
          Picture old = picture(s, block);
          var next =
              new Picture(
                  old.id(),
                  com.codename1.util.Base64.decode(Commands.str(a, "base64").getBytes()),
                  old.width,
                  old.height,
                  old.alt,
                  old.align,
                  old.look);
          var keep = s.selection();
          s.replaceDocument(s.document().blocks(s.document().blocks.set(block, next), s.document().nextId));
          s.selectRange(keep.anchorBlock, keep.anchor, keep.block, keep.caret);
          return null;
        });
    c.edit("arrange.rotation", "block?:integer,degrees?:number,flipH?:boolean,flipV?:boolean",
        "Set picture rotation in degrees and flips", (s, a) -> {
          int block = block(s, a);
          var k = picture(s, block).look;
          s.pictureLook(
              block,
              k.rotate(
                  a.containsKey("degrees") ? Math.round(f(a, "degrees", 0)) : k.rotation,
                  Commands.flag(a, "flipH", k.flipH),
                  Commands.flag(a, "flipV", k.flipV)));
          return null;
        });
    c.edit("arrange.rotate", "block?:integer,kind:string",
        "Rotate right/left 90 degrees or flip horizontal/vertical", (s, a) -> {
          int block = block(s, a);
          var k = picture(s, block).look;
          String kind = Commands.str(a, "kind");
          Picture.Look next =
              "right".equals(kind)
                  ? k.rotate(k.rotation + 90, k.flipH, k.flipV)
                  : "left".equals(kind)
                      ? k.rotate(k.rotation + 270, k.flipH, k.flipV)
                      : "flipH".equals(kind)
                          ? k.rotate(k.rotation, !k.flipH, k.flipV)
                          : "flipV".equals(kind) ? k.rotate(k.rotation, k.flipH, !k.flipV) : null;
          if (next == null) throw new IllegalArgumentException("kind: right, left, flipH or flipV");
          s.pictureLook(block, next);
          return null;
        });
    c.edit("picture.reset", "block?:integer",
        "Remove crop, border and rotation", (s, a) -> {
          s.pictureLook(block(s, a), Picture.Look.NONE);
          return null;
        });
    c.edit("picture.size", "block?:integer,width?:number,height?:number",
        "Resize in points; a single dimension keeps the aspect ratio", (s, a) -> {
          int block = block(s, a);
          Picture p = picture(s, block);
          float w = f(a, "width", 0), h = f(a, "height", 0);
          if (w <= 0 && h <= 0) throw new IllegalArgumentException("Give width or height");
          if (w <= 0) w = p.width * h / p.height;
          if (h <= 0) h = p.height * w / p.width;
          s.updatePicture(block, w, h, p.alt, p.align, false);
          return null;
        });
    for (String id : new String[] {"picture.altText", "object.altText"})
      c.register(
          id,
          "Set the picture's alternative text",
          "block?:integer,text:string",
          true,
          (s, a) -> {
            int block = block(s, a);
            Picture p = picture(s, block);
            String text = Commands.str(a, "text");
            if (text.length() > 2000) throw new IllegalArgumentException("Alt text is too long");
            s.updatePicture(block, p.width, p.height, text, p.align, false);
            return null;
          });
  }

  private static int block(Session s, Map<String, Object> a) {
    return Commands.integer(a, "block", s.selection().block);
  }

  private static Picture picture(Session s, int block) {
    if (block < 0 || block >= s.document().blocks.size()
        || !(s.document().blocks.get(block) instanceof Picture))
      throw new IllegalArgumentException("Select a picture");
    return (Picture) s.document().blocks.get(block);
  }

  private static float f(Map<String, Object> a, String k, float fallback) {
    return a.containsKey(k) ? ((Number) a.get(k)).floatValue() : fallback;
  }

  private static String hex(String v) {
    String h = v.startsWith("#") ? v.substring(1) : v;
    if (h.length() != 6) throw new IllegalArgumentException("Color must be RRGGBB");
    return h;
  }
}
