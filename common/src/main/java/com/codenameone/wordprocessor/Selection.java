package com.codenameone.wordprocessor;

/** Anchor and focus positions, addressed by top-level block and UTF-16 offset. */
public final class Selection {
  public final int anchorBlock, block, anchor, caret;

  public Selection(int block, int anchor, int caret) {
    this(block, anchor, block, caret);
  }

  public Selection(int anchorBlock, int anchor, int block, int caret) {
    if (anchorBlock < 0 || block < 0 || anchor < 0 || caret < 0)
      throw new IllegalArgumentException();
    this.anchorBlock = anchorBlock;
    this.block = block;
    this.anchor = anchor;
    this.caret = caret;
  }

  public int startBlock() {
    return Math.min(anchorBlock, block);
  }

  public int endBlock() {
    return Math.max(anchorBlock, block);
  }

  public int start() {
    return anchorBlock < block ? anchor : block < anchorBlock ? caret : Math.min(anchor, caret);
  }

  public int end() {
    return anchorBlock < block ? caret : block < anchorBlock ? anchor : Math.max(anchor, caret);
  }

  public boolean collapsed() {
    return anchorBlock == block && anchor == caret;
  }
}
