package com.codenameone.wordprocessor;

import java.io.IOException;

/** VP8 key-frame (lossy WebP) decoder; follows RFC 6386 and the image-webp crate (MIT). */
final class Vp8Decoder {
  private static final int[] YMODE_TREE = {-4, 2, 4, 6, 0, -1, -2, -3};
  private static final int[] YMODE_PROBS = {145, 156, 163, 128};
  private static final int[] BMODE_TREE = {0, 2, -1, 4, -2, 6, 8, 12, -3, 10, -5, -6, -4, 14, -7, 16, -8, -9};
  private static final int[] UVMODE_TREE = {0, 2, -1, 4, -2, -3};
  private static final int[] UVMODE_PROBS = {142, 114, 183};
  private static final int[] SEGMENT_TREE = {2, 4, 0, -1, -2, -3};
  private static final int[] TOKEN_TREE = {-11, 2, 0, 4, -1, 6, 8, 12, -2, 10, -3, -4, 14, 16, -5, -6, 18, 20, -7, -8, -9, -10};
  private static final int[][] CAT_PROBS = {
    {159}, {165, 145}, {173, 148, 140}, {176, 155, 140, 135}, {180, 157, 141, 134, 130},
    {254, 254, 243, 230, 196, 177, 153, 140, 133, 130, 129}
  };
  private static final int[] CAT_BASE = {5, 7, 11, 19, 35, 67};
  private static final int[] BANDS = {0, 1, 2, 3, 6, 4, 5, 6, 6, 6, 6, 6, 6, 6, 6, 7};
  private static final int[] ZIGZAG = {0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15};
  private static final int[] KEYFRAME_BPRED_MODE_PROBS = {
      231, 120, 48, 89, 115, 113, 120, 152, 112, 152, 179, 64, 126, 170, 118, 46, 70, 95, 175, 69, 143, 80, 85, 82,
      72, 155, 103, 56, 58, 10, 171, 218, 189, 17, 13, 152, 144, 71, 10, 38, 171, 213, 144, 34, 26, 114, 26, 17,
      163, 44, 195, 21, 10, 173, 121, 24, 80, 195, 26, 62, 44, 64, 85, 170, 46, 55, 19, 136, 160, 33, 206, 71,
      63, 20, 8, 114, 114, 208, 12, 9, 226, 81, 40, 11, 96, 182, 84, 29, 16, 36, 134, 183, 89, 137, 98, 101,
      106, 165, 148, 72, 187, 100, 130, 157, 111, 32, 75, 80, 66, 102, 167, 99, 74, 62, 40, 234, 128, 41, 53, 9,
      178, 241, 141, 26, 8, 107, 104, 79, 12, 27, 217, 255, 87, 17, 7, 74, 43, 26, 146, 73, 166, 49, 23, 157,
      65, 38, 105, 160, 51, 52, 31, 115, 128, 87, 68, 71, 44, 114, 51, 15, 186, 23, 47, 41, 14, 110, 182, 183,
      21, 17, 194, 66, 45, 25, 102, 197, 189, 23, 18, 22, 88, 88, 147, 150, 42, 46, 45, 196, 205, 43, 97, 183,
      117, 85, 38, 35, 179, 61, 39, 53, 200, 87, 26, 21, 43, 232, 171, 56, 34, 51, 104, 114, 102, 29, 93, 77,
      107, 54, 32, 26, 51, 1, 81, 43, 31, 39, 28, 85, 171, 58, 165, 90, 98, 64, 34, 22, 116, 206, 23, 34,
      43, 166, 73, 68, 25, 106, 22, 64, 171, 36, 225, 114, 34, 19, 21, 102, 132, 188, 16, 76, 124, 62, 18, 78,
      95, 85, 57, 50, 48, 51, 193, 101, 35, 159, 215, 111, 89, 46, 111, 60, 148, 31, 172, 219, 228, 21, 18, 111,
      112, 113, 77, 85, 179, 255, 38, 120, 114, 40, 42, 1, 196, 245, 209, 10, 25, 109, 100, 80, 8, 43, 154, 1,
      51, 26, 71, 88, 43, 29, 140, 166, 213, 37, 43, 154, 61, 63, 30, 155, 67, 45, 68, 1, 209, 142, 78, 78,
      16, 255, 128, 34, 197, 171, 41, 40, 5, 102, 211, 183, 4, 1, 221, 51, 50, 17, 168, 209, 192, 23, 25, 82,
      125, 98, 42, 88, 104, 85, 117, 175, 82, 95, 84, 53, 89, 128, 100, 113, 101, 45, 75, 79, 123, 47, 51, 128,
      81, 171, 1, 57, 17, 5, 71, 102, 57, 53, 41, 49, 115, 21, 2, 10, 102, 255, 166, 23, 6, 38, 33, 13,
      121, 57, 73, 26, 1, 85, 41, 10, 67, 138, 77, 110, 90, 47, 114, 101, 29, 16, 10, 85, 128, 101, 196, 26,
      57, 18, 10, 102, 102, 213, 34, 20, 43, 117, 20, 15, 36, 163, 128, 68, 1, 26, 138, 31, 36, 171, 27, 166,
      38, 44, 229, 67, 87, 58, 169, 82, 115, 26, 59, 179, 63, 59, 90, 180, 59, 166, 93, 73, 154, 40, 40, 21,
      116, 143, 209, 34, 39, 175, 57, 46, 22, 24, 128, 1, 54, 17, 37, 47, 15, 16, 183, 34, 223, 49, 45, 183,
      46, 17, 33, 183, 6, 98, 15, 32, 183, 65, 32, 73, 115, 28, 128, 23, 128, 205, 40, 3, 9, 115, 51, 192,
      18, 6, 223, 87, 37, 9, 115, 59, 77, 64, 21, 47, 104, 55, 44, 218, 9, 54, 53, 130, 226, 64, 90, 70,
      205, 40, 41, 23, 26, 57, 54, 57, 112, 184, 5, 41, 38, 166, 213, 30, 34, 26, 133, 152, 116, 10, 32, 134,
      75, 32, 12, 51, 192, 255, 160, 43, 51, 39, 19, 53, 221, 26, 114, 32, 73, 255, 31, 9, 65, 234, 2, 15,
      1, 118, 73, 88, 31, 35, 67, 102, 85, 55, 186, 85, 56, 21, 23, 111, 59, 205, 45, 37, 192, 55, 38, 70,
      124, 73, 102, 1, 34, 98, 102, 61, 71, 37, 34, 53, 31, 243, 192, 69, 60, 71, 38, 73, 119, 28, 222, 37,
      68, 45, 128, 34, 1, 47, 11, 245, 171, 62, 17, 19, 70, 146, 85, 55, 62, 70, 75, 15, 9, 9, 64, 255,
      184, 119, 16, 37, 43, 37, 154, 100, 163, 85, 160, 1, 63, 9, 92, 136, 28, 64, 32, 201, 85, 86, 6, 28,
      5, 64, 255, 25, 248, 1, 56, 8, 17, 132, 137, 255, 55, 116, 128, 58, 15, 20, 82, 135, 57, 26, 121, 40,
      164, 50, 31, 137, 154, 133, 25, 35, 218, 51, 103, 44, 131, 131, 123, 31, 6, 158, 86, 40, 64, 135, 148, 224,
      45, 183, 128, 22, 26, 17, 131, 240, 154, 14, 1, 209, 83, 12, 13, 54, 192, 255, 68, 47, 28, 45, 16, 21,
      91, 64, 222, 7, 1, 197, 56, 21, 39, 155, 60, 138, 23, 102, 213, 85, 26, 85, 85, 128, 128, 32, 146, 171,
      18, 11, 7, 63, 144, 171, 4, 4, 246, 35, 27, 10, 146, 174, 171, 12, 26, 128, 190, 80, 35, 99, 180, 80,
      126, 54, 45, 85, 126, 47, 87, 176, 51, 41, 20, 32, 101, 75, 128, 139, 118, 146, 116, 128, 85, 56, 41, 15,
      176, 236, 85, 37, 9, 62, 146, 36, 19, 30, 171, 255, 97, 27, 20, 71, 30, 17, 119, 118, 255, 17, 18, 138,
      101, 38, 60, 138, 55, 70, 43, 26, 142, 138, 45, 61, 62, 219, 1, 81, 188, 64, 32, 41, 20, 117, 151, 142,
      20, 21, 163, 112, 19, 12, 61, 195, 128, 48, 4, 24,
  };

  private static final int[] COEFF_UPDATE_PROBS = {
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 176, 246, 255, 255, 255, 255, 255, 255, 255, 255, 255, 223, 241, 252, 255,
      255, 255, 255, 255, 255, 255, 255, 249, 253, 253, 255, 255, 255, 255, 255, 255, 255, 255, 255, 244, 252, 255, 255, 255,
      255, 255, 255, 255, 255, 234, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255, 253, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 246, 254, 255, 255, 255, 255, 255, 255, 255, 255, 239, 253, 254, 255, 255, 255, 255, 255, 255, 255,
      255, 254, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 248, 254, 255, 255, 255, 255, 255, 255, 255, 255, 251,
      255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 253, 254,
      255, 255, 255, 255, 255, 255, 255, 255, 251, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255, 254, 255, 254, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 254, 253, 255, 254, 255, 255, 255, 255, 255, 255, 250, 255, 254, 255, 254, 255, 255,
      255, 255, 255, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      217, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 225, 252, 241, 253, 255, 255, 254, 255, 255, 255, 255, 234, 250,
      241, 250, 253, 255, 253, 254, 255, 255, 255, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 223, 254, 254, 255,
      255, 255, 255, 255, 255, 255, 255, 238, 253, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255, 248, 254, 255, 255, 255,
      255, 255, 255, 255, 255, 249, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 253, 255, 255, 255, 255, 255, 255, 255, 255, 255, 247, 254, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255, 252,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 254, 254,
      255, 255, 255, 255, 255, 255, 255, 255, 253, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 254, 253, 255, 255, 255, 255, 255, 255, 255, 255, 250, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      186, 251, 250, 255, 255, 255, 255, 255, 255, 255, 255, 234, 251, 244, 254, 255, 255, 255, 255, 255, 255, 255, 251, 251,
      243, 253, 254, 255, 254, 255, 255, 255, 255, 255, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255, 236, 253, 254, 255,
      255, 255, 255, 255, 255, 255, 255, 251, 253, 253, 254, 254, 255, 255, 255, 255, 255, 255, 255, 254, 254, 255, 255, 255,
      255, 255, 255, 255, 255, 254, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 254, 254, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 254,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      248, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 250, 254, 252, 254, 255, 255, 255, 255, 255, 255, 255, 248, 254,
      249, 253, 255, 255, 255, 255, 255, 255, 255, 255, 253, 253, 255, 255, 255, 255, 255, 255, 255, 255, 246, 253, 253, 255,
      255, 255, 255, 255, 255, 255, 255, 252, 254, 251, 254, 254, 255, 255, 255, 255, 255, 255, 255, 254, 252, 255, 255, 255,
      255, 255, 255, 255, 255, 248, 254, 253, 255, 255, 255, 255, 255, 255, 255, 255, 253, 255, 254, 254, 255, 255, 255, 255,
      255, 255, 255, 255, 251, 254, 255, 255, 255, 255, 255, 255, 255, 255, 245, 251, 254, 255, 255, 255, 255, 255, 255, 255,
      255, 253, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 251, 253, 255, 255, 255, 255, 255, 255, 255, 255, 252,
      253, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 252, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 249, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 254, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 253, 255, 255, 255, 255, 255, 255, 255, 255, 250, 255, 255, 255, 255, 255, 255,
      255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
      255, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
  };

  private static final int[] COEFF_PROBS = {
      128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128,
      128, 128, 128, 128, 128, 128, 128, 128, 128, 253, 136, 254, 255, 228, 219, 128, 128, 128, 128, 128, 189, 129, 242, 255,
      227, 213, 255, 219, 128, 128, 128, 106, 126, 227, 252, 214, 209, 255, 255, 128, 128, 128, 1, 98, 248, 255, 236, 226,
      255, 255, 128, 128, 128, 181, 133, 238, 254, 221, 234, 255, 154, 128, 128, 128, 78, 134, 202, 247, 198, 180, 255, 219,
      128, 128, 128, 1, 185, 249, 255, 243, 255, 128, 128, 128, 128, 128, 184, 150, 247, 255, 236, 224, 128, 128, 128, 128,
      128, 77, 110, 216, 255, 236, 230, 128, 128, 128, 128, 128, 1, 101, 251, 255, 241, 255, 128, 128, 128, 128, 128, 170,
      139, 241, 252, 236, 209, 255, 255, 128, 128, 128, 37, 116, 196, 243, 228, 255, 255, 255, 128, 128, 128, 1, 204, 254,
      255, 245, 255, 128, 128, 128, 128, 128, 207, 160, 250, 255, 238, 128, 128, 128, 128, 128, 128, 102, 103, 231, 255, 211,
      171, 128, 128, 128, 128, 128, 1, 152, 252, 255, 240, 255, 128, 128, 128, 128, 128, 177, 135, 243, 255, 234, 225, 128,
      128, 128, 128, 128, 80, 129, 211, 255, 194, 224, 128, 128, 128, 128, 128, 1, 1, 255, 128, 128, 128, 128, 128, 128,
      128, 128, 246, 1, 255, 128, 128, 128, 128, 128, 128, 128, 128, 255, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128,
      198, 35, 237, 223, 193, 187, 162, 160, 145, 155, 62, 131, 45, 198, 221, 172, 176, 220, 157, 252, 221, 1, 68, 47,
      146, 208, 149, 167, 221, 162, 255, 223, 128, 1, 149, 241, 255, 221, 224, 255, 255, 128, 128, 128, 184, 141, 234, 253,
      222, 220, 255, 199, 128, 128, 128, 81, 99, 181, 242, 176, 190, 249, 202, 255, 255, 128, 1, 129, 232, 253, 214, 197,
      242, 196, 255, 255, 128, 99, 121, 210, 250, 201, 198, 255, 202, 128, 128, 128, 23, 91, 163, 242, 170, 187, 247, 210,
      255, 255, 128, 1, 200, 246, 255, 234, 255, 128, 128, 128, 128, 128, 109, 178, 241, 255, 231, 245, 255, 255, 128, 128,
      128, 44, 130, 201, 253, 205, 192, 255, 255, 128, 128, 128, 1, 132, 239, 251, 219, 209, 255, 165, 128, 128, 128, 94,
      136, 225, 251, 218, 190, 255, 255, 128, 128, 128, 22, 100, 174, 245, 186, 161, 255, 199, 128, 128, 128, 1, 182, 249,
      255, 232, 235, 128, 128, 128, 128, 128, 124, 143, 241, 255, 227, 234, 128, 128, 128, 128, 128, 35, 77, 181, 251, 193,
      211, 255, 205, 128, 128, 128, 1, 157, 247, 255, 236, 231, 255, 255, 128, 128, 128, 121, 141, 235, 255, 225, 227, 255,
      255, 128, 128, 128, 45, 99, 188, 251, 195, 217, 255, 224, 128, 128, 128, 1, 1, 251, 255, 213, 255, 128, 128, 128,
      128, 128, 203, 1, 248, 255, 255, 128, 128, 128, 128, 128, 128, 137, 1, 177, 255, 224, 255, 128, 128, 128, 128, 128,
      253, 9, 248, 251, 207, 208, 255, 192, 128, 128, 128, 175, 13, 224, 243, 193, 185, 249, 198, 255, 255, 128, 73, 17,
      171, 221, 161, 179, 236, 167, 255, 234, 128, 1, 95, 247, 253, 212, 183, 255, 255, 128, 128, 128, 239, 90, 244, 250,
      211, 209, 255, 255, 128, 128, 128, 155, 77, 195, 248, 188, 195, 255, 255, 128, 128, 128, 1, 24, 239, 251, 218, 219,
      255, 205, 128, 128, 128, 201, 51, 219, 255, 196, 186, 128, 128, 128, 128, 128, 69, 46, 190, 239, 201, 218, 255, 228,
      128, 128, 128, 1, 191, 251, 255, 255, 128, 128, 128, 128, 128, 128, 223, 165, 249, 255, 213, 255, 128, 128, 128, 128,
      128, 141, 124, 248, 255, 255, 128, 128, 128, 128, 128, 128, 1, 16, 248, 255, 255, 128, 128, 128, 128, 128, 128, 190,
      36, 230, 255, 236, 255, 128, 128, 128, 128, 128, 149, 1, 255, 128, 128, 128, 128, 128, 128, 128, 128, 1, 226, 255,
      128, 128, 128, 128, 128, 128, 128, 128, 247, 192, 255, 128, 128, 128, 128, 128, 128, 128, 128, 240, 128, 255, 128, 128,
      128, 128, 128, 128, 128, 128, 1, 134, 252, 255, 255, 128, 128, 128, 128, 128, 128, 213, 62, 250, 255, 255, 128, 128,
      128, 128, 128, 128, 55, 93, 255, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128,
      128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128,
      202, 24, 213, 235, 186, 191, 220, 160, 240, 175, 255, 126, 38, 182, 232, 169, 184, 228, 174, 255, 187, 128, 61, 46,
      138, 219, 151, 178, 240, 170, 255, 216, 128, 1, 112, 230, 250, 199, 191, 247, 159, 255, 255, 128, 166, 109, 228, 252,
      211, 215, 255, 174, 128, 128, 128, 39, 77, 162, 232, 172, 180, 245, 178, 255, 255, 128, 1, 52, 220, 246, 198, 199,
      249, 220, 255, 255, 128, 124, 74, 191, 243, 183, 193, 250, 221, 255, 255, 128, 24, 71, 130, 219, 154, 170, 243, 182,
      255, 255, 128, 1, 182, 225, 249, 219, 240, 255, 224, 128, 128, 128, 149, 150, 226, 252, 216, 205, 255, 171, 128, 128,
      128, 28, 108, 170, 242, 183, 194, 254, 223, 255, 255, 128, 1, 81, 230, 252, 204, 203, 255, 192, 128, 128, 128, 123,
      102, 209, 247, 188, 196, 255, 233, 128, 128, 128, 20, 95, 153, 243, 164, 173, 255, 203, 128, 128, 128, 1, 222, 248,
      255, 216, 213, 128, 128, 128, 128, 128, 168, 175, 246, 252, 235, 205, 255, 255, 128, 128, 128, 47, 116, 215, 255, 211,
      212, 255, 255, 128, 128, 128, 1, 121, 236, 253, 212, 214, 255, 255, 128, 128, 128, 141, 84, 213, 252, 201, 202, 255,
      219, 128, 128, 128, 42, 80, 160, 240, 162, 185, 255, 205, 128, 128, 128, 1, 1, 255, 128, 128, 128, 128, 128, 128,
      128, 128, 244, 1, 255, 128, 128, 128, 128, 128, 128, 128, 128, 238, 1, 255, 128, 128, 128, 128, 128, 128, 128, 128,
  };

  private static final int[] DC_QUANT = {
      4, 5, 6, 7, 8, 9, 10, 10, 11, 12, 13, 14, 15, 16, 17, 17, 18, 19, 20, 20, 21, 21, 22, 22,
      23, 23, 24, 25, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 37, 38, 39, 40, 41, 42, 43,
      44, 45, 46, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66,
      67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89,
      91, 93, 95, 96, 98, 100, 101, 102, 104, 106, 108, 110, 112, 114, 116, 118, 122, 124, 126, 128, 130, 132, 134, 136,
      138, 140, 143, 145, 148, 151, 154, 157,
  };

  private static final int[] AC_QUANT = {
      4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
      28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51,
      52, 53, 54, 55, 56, 57, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92,
      94, 96, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 119, 122, 125, 128, 131, 134, 137, 140, 143, 146, 149, 152,
      155, 158, 161, 164, 167, 170, 173, 177, 181, 185, 189, 193, 197, 201, 205, 209, 213, 217, 221, 225, 229, 234, 239, 245,
      249, 254, 259, 264, 269, 274, 279, 284,
  };

  private final byte[] d;
  private int pos, end;
  private long value;
  private int range, bitCount;
  private int overrun;
  private final int[][] parts = new int[8][];
  private final int[] probs = COEFF_PROBS.clone();
  private int width, height, mbw, mbh;
  private byte[] ybuf, ubuf, vbuf;
  private boolean segmentsEnabled, updateMap, deltaValues = true;
  private final int[] quantLevel = new int[4], filterLevelSeg = new int[4];
  private final int[] segmentProbs = {255, 255, 255};
  private final int[][] quant = new int[4][6];
  private boolean simpleFilter, lfAdjust;
  private int filterLevel, sharpness, numParts, skipProb = -1;
  private final int[] refDelta = new int[4], modeDelta = new int[4];
  private int[] topComplexity, topModes;
  private final int[] leftComplexity = new int[9], leftModes = new int[4];
  private byte[] topY, topU, topV;
  private final byte[] leftY = new byte[17], leftU = new byte[9], leftV = new byte[9];

  private Vp8Decoder(byte[] d) {
    this.d = d;
  }

  static PictureCodecs.Raster decode(byte[] b, int at, int len) throws IOException {
    return new Vp8Decoder(b).frame(at, len);
  }

  private void init(int at, int len) {
    pos = at;
    end = at + len;
    range = 255;
    bitCount = 0;
    value = (long) next() << 8 | next();
  }

  private int next() {
    if (pos < end) return d[pos++] & 255;
    overrun++;
    return 0;
  }

  /** Bool decoder state of one partition: {pos, end, value, range, bitCount}. */
  private int[] save() {
    return new int[] {pos, end, (int) value, range, bitCount};
  }

  private void load(int[] s) {
    pos = s[0];
    end = s[1];
    value = s[2] & 0xffffffffL;
    range = s[3];
    bitCount = s[4];
  }

  private boolean bool(int prob) throws IOException {
    int split = 1 + (((range - 1) * prob) >> 8);
    long big = (long) split << 8;
    boolean bit;
    if (value >= big) {
      range -= split;
      value -= big;
      bit = true;
    } else {
      range = split;
      bit = false;
    }
    while (range < 128) {
      value <<= 1;
      range <<= 1;
      if (++bitCount == 8) {
        bitCount = 0;
        value |= next();
      }
    }
    if (overrun > 64) throw new IOException("Truncated WebP lossy data");
    return bit;
  }

  private int literal(int n) throws IOException {
    int v = 0;
    while (n-- > 0) v = v << 1 | (bool(128) ? 1 : 0);
    return v;
  }

  private int signed(int n) throws IOException {
    if (!bool(128)) return 0;
    int v = literal(n);
    return bool(128) ? -v : v;
  }

  private int tree(int[] t, int[] p, int poff, int start) throws IOException {
    int i = start;
    do i = t[i + (bool(p[poff + (i >> 1)]) ? 1 : 0)]; while (i > 0);
    return -i;
  }

  private PictureCodecs.Raster frame(int at, int len) throws IOException {
    if (len < 10) throw new IOException("Truncated WebP lossy data");
    int tag = (d[at] & 255) | (d[at + 1] & 255) << 8 | (d[at + 2] & 255) << 16;
    if ((tag & 1) != 0) throw new IOException("WebP lossy frame is not a key frame");
    int first = tag >>> 5;
    if ((d[at + 3] & 255) != 0x9d || (d[at + 4] & 255) != 1 || (d[at + 5] & 255) != 0x2a)
      throw new IOException("Invalid WebP lossy signature");
    width = PictureCodecs.u16le(d, at + 6) & 0x3fff;
    height = PictureCodecs.u16le(d, at + 8) & 0x3fff;
    PictureCodecs.checkSize(width, height);
    mbw = (width + 15) / 16;
    mbh = (height + 15) / 16;
    ybuf = new byte[mbw * 16 * mbh * 16];
    ubuf = new byte[mbw * 8 * mbh * 8];
    vbuf = new byte[ubuf.length];
    int p = at + 10, stop = at + len;
    if (first > stop - p) throw new IOException("Truncated WebP lossy data");
    init(p, first);
    p += first;
    literal(1);
    literal(1);
    segmentsEnabled = bool(128);
    if (segmentsEnabled) {
      updateMap = bool(128);
      if (bool(128)) {
        deltaValues = !bool(128);
        for (int i = 0; i < 4; i++) quantLevel[i] = signed(7);
        for (int i = 0; i < 4; i++) filterLevelSeg[i] = signed(6);
      }
      if (updateMap) for (int i = 0; i < 3; i++) segmentProbs[i] = bool(128) ? literal(8) : 255;
    }
    simpleFilter = bool(128);
    filterLevel = literal(6);
    sharpness = literal(3);
    lfAdjust = bool(128);
    if (lfAdjust && bool(128)) {
      for (int i = 0; i < 4; i++) refDelta[i] = signed(6);
      for (int i = 0; i < 4; i++) modeDelta[i] = signed(6);
    }
    numParts = 1 << literal(2);
    int[] header = save();
    int sizes = p;
    p += 3 * (numParts - 1);
    if (p > stop) throw new IOException("Truncated WebP lossy data");
    for (int i = 0; i < numParts; i++) {
      int size = i < numParts - 1
          ? (d[sizes + 3 * i] & 255) | (d[sizes + 3 * i + 1] & 255) << 8 | (d[sizes + 3 * i + 2] & 255) << 16
          : stop - p;
      if (size > stop - p) throw new IOException("Truncated WebP lossy data");
      init(p, size);
      parts[i] = save();
      p += size;
    }
    load(header);
    int base = literal(7);
    int[] deltas = new int[5];
    for (int i = 0; i < 5; i++) deltas[i] = signed(4);
    for (int s = 0; s < 4; s++) {
      int q = !segmentsEnabled ? base : deltaValues ? base + quantLevel[s] : quantLevel[s];
      quant[s][0] = DC_QUANT[clamp(q + deltas[0], 127)];
      quant[s][1] = AC_QUANT[clamp(q, 127)];
      quant[s][2] = DC_QUANT[clamp(q + deltas[1], 127)] * 2;
      quant[s][3] = Math.max(8, AC_QUANT[clamp(q + deltas[2], 127)] * 155 / 100);
      quant[s][4] = Math.min(132, DC_QUANT[clamp(q + deltas[3], 127)]);
      quant[s][5] = AC_QUANT[clamp(q + deltas[4], 127)];
    }
    literal(1);
    for (int i = 0; i < 1056; i++) if (bool(COEFF_UPDATE_PROBS[i])) probs[i] = literal(8);
    if (bool(128)) skipProb = literal(8);
    topComplexity = new int[mbw * 9];
    topModes = new int[mbw * 4];
    topY = new byte[mbw * 16 + 4 + 16];
    topU = new byte[mbw * 8];
    topV = new byte[mbw * 8];
    java.util.Arrays.fill(topY, (byte) 127);
    java.util.Arrays.fill(topU, (byte) 127);
    java.util.Arrays.fill(topV, (byte) 127);
    int[][] mbInfo = new int[mbw * mbh][];
    int[] res = new int[384];
    for (int mby = 0; mby < mbh; mby++) {
      java.util.Arrays.fill(leftComplexity, 0);
      java.util.Arrays.fill(leftModes, 0);
      java.util.Arrays.fill(leftY, (byte) 129);
      java.util.Arrays.fill(leftU, (byte) 129);
      java.util.Arrays.fill(leftV, (byte) 129);
      int[] part = parts[mby % numParts];
      for (int mbx = 0; mbx < mbw; mbx++) {
        int segment = segmentsEnabled && updateMap ? tree(SEGMENT_TREE, segmentProbs, 0, 0) : 0;
        boolean skipped = skipProb >= 0 && bool(skipProb);
        int ymode = tree(YMODE_TREE, YMODE_PROBS, 0, 0);
        int[] bmodes = new int[16];
        if (ymode == 4) {
          for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
              int m = tree(BMODE_TREE, KEYFRAME_BPRED_MODE_PROBS, (topModes[mbx * 4 + x] * 10 + leftModes[y]) * 9, 0);
              bmodes[y * 4 + x] = m;
              topModes[mbx * 4 + x] = m;
              leftModes[y] = m;
            }
        } else {
          int m = ymode == 0 ? 0 : ymode == 1 ? 2 : ymode == 2 ? 3 : 1;
          java.util.Arrays.fill(bmodes, m);
          for (int i = 0; i < 4; i++) {
            topModes[mbx * 4 + i] = m;
            leftModes[i] = m;
          }
        }
        int uvmode = tree(UVMODE_TREE, UVMODE_PROBS, 0, 0);
        java.util.Arrays.fill(res, 0);
        boolean nonZero = false;
        if (!skipped) {
          int[] header2 = save();
          load(part);
          nonZero = residuals(res, mbx, segment, ymode == 4);
          part = save();
          load(header2);
        } else {
          if (ymode != 4) {
            leftComplexity[0] = 0;
            topComplexity[mbx * 9] = 0;
          }
          for (int i = 1; i < 9; i++) {
            leftComplexity[i] = 0;
            topComplexity[mbx * 9 + i] = 0;
          }
        }
        parts[mby % numParts] = part;
        luma(mbx, mby, ymode, bmodes, res);
        chroma(mbx, mby, uvmode, res);
        mbInfo[mby * mbw + mbx] = new int[] {segment, ymode, skipped ? 1 : 0, nonZero ? 1 : 0};
      }
    }
    if (filterLevel > 0)
      for (int mby = 0; mby < mbh; mby++)
        for (int mbx = 0; mbx < mbw; mbx++) filter(mbx, mby, mbInfo[mby * mbw + mbx]);
    return toArgb();
  }

  private static int clamp(int v, int max) {
    return v < 0 ? 0 : v > max ? max : v;
  }

  private boolean residuals(int[] blocks, int mbx, int segment, boolean bpred) throws IOException {
    int[] q = quant[segment];
    boolean nonZero = false;
    int plane = bpred ? 3 : 1;
    int[] block = new int[16];
    if (!bpred) {
      int ctx = topComplexity[mbx * 9] + leftComplexity[0];
      boolean n = coefficients(block, 1, ctx, q[2], q[3]);
      leftComplexity[0] = topComplexity[mbx * 9] = n ? 1 : 0;
      iwht(block);
      for (int k = 0; k < 16; k++) blocks[16 * k] = block[k];
      plane = 0;
    }
    for (int y = 0; y < 4; y++) {
      int left = leftComplexity[y + 1];
      for (int x = 0; x < 4; x++) {
        int i = x + y * 4;
        System.arraycopy(blocks, i * 16, block, 0, 16);
        boolean n = coefficients(block, plane, topComplexity[mbx * 9 + x + 1] + left, q[0], q[1]);
        if (block[0] != 0 || n) {
          nonZero = true;
          idct(block);
        }
        System.arraycopy(block, 0, blocks, i * 16, 16);
        left = n ? 1 : 0;
        topComplexity[mbx * 9 + x + 1] = left;
      }
      leftComplexity[y + 1] = left;
    }
    for (int j = 5; j <= 7; j += 2)
      for (int y = 0; y < 2; y++) {
        int left = leftComplexity[y + j];
        for (int x = 0; x < 2; x++) {
          int i = x + y * 2 + (j == 5 ? 16 : 20);
          java.util.Arrays.fill(block, 0);
          boolean n = coefficients(block, 2, topComplexity[mbx * 9 + x + j] + left, q[4], q[5]);
          if (block[0] != 0 || n) {
            nonZero = true;
            idct(block);
          }
          System.arraycopy(block, 0, blocks, i * 16, 16);
          left = n ? 1 : 0;
          topComplexity[mbx * 9 + x + j] = left;
        }
        leftComplexity[y + j] = left;
      }
    return nonZero;
  }

  private boolean coefficients(int[] block, int plane, int ctx, int dcq, int acq) throws IOException {
    int first = plane == 0 ? 1 : 0;
    boolean has = false, skip = false;
    for (int i = first; i < 16; i++) {
      int poff = ((plane * 8 + BANDS[i]) * 3 + ctx) * 11;
      int token = tree(TOKEN_TREE, probs, poff, skip ? 2 : 0);
      if (token == 11) break;
      if (token == 0) {
        skip = true;
        has = true;
        ctx = 0;
        continue;
      }
      int v;
      if (token <= 4) v = token;
      else {
        int extra = 0;
        for (int pr : CAT_PROBS[token - 5]) extra = extra + extra + (bool(pr) ? 1 : 0);
        v = CAT_BASE[token - 5] + extra;
      }
      skip = false;
      ctx = v == 1 ? 1 : 2;
      if (bool(128)) v = -v;
      int z = ZIGZAG[i];
      block[z] = v * (z > 0 ? acq : dcq);
      has = true;
    }
    return has;
  }

  private static void iwht(int[] b) {
    for (int i = 0; i < 4; i++) {
      int a1 = b[i] + b[12 + i], b1 = b[4 + i] + b[8 + i], c1 = b[4 + i] - b[8 + i], d1 = b[i] - b[12 + i];
      b[i] = a1 + b1;
      b[4 + i] = c1 + d1;
      b[8 + i] = a1 - b1;
      b[12 + i] = d1 - c1;
    }
    for (int i = 0; i < 16; i += 4) {
      int a1 = b[i] + b[i + 3], b1 = b[i + 1] + b[i + 2], c1 = b[i + 1] - b[i + 2], d1 = b[i] - b[i + 3];
      b[i] = (a1 + b1 + 3) >> 3;
      b[i + 1] = (c1 + d1 + 3) >> 3;
      b[i + 2] = (a1 - b1 + 3) >> 3;
      b[i + 3] = (d1 - c1 + 3) >> 3;
    }
  }

  private static void idct(int[] b) {
    final long c1k = 20091, c2k = 35468;
    for (int i = 0; i < 4; i++) {
      long a1 = (long) b[i] + b[8 + i], b1 = (long) b[i] - b[8 + i];
      long t1 = (b[4 + i] * c2k) >> 16, t2 = b[12 + i] + ((b[12 + i] * c1k) >> 16);
      long c1 = t1 - t2;
      t1 = b[4 + i] + ((b[4 + i] * c1k) >> 16);
      t2 = (b[12 + i] * c2k) >> 16;
      long d1 = t1 + t2;
      b[i] = (int) (a1 + d1);
      b[4 + i] = (int) (b1 + c1);
      b[12 + i] = (int) (a1 - d1);
      b[8 + i] = (int) (b1 - c1);
    }
    for (int i = 0; i < 4; i++) {
      int r = 4 * i;
      long a1 = (long) b[r] + b[r + 2], b1 = (long) b[r] - b[r + 2];
      long t1 = (b[r + 1] * c2k) >> 16, t2 = b[r + 3] + ((b[r + 3] * c1k) >> 16);
      long c1 = t1 - t2;
      t1 = b[r + 1] + ((b[r + 1] * c1k) >> 16);
      t2 = (b[r + 3] * c2k) >> 16;
      long d1 = t1 + t2;
      b[r] = (int) ((a1 + d1 + 4) >> 3);
      b[r + 3] = (int) ((a1 - d1 + 4) >> 3);
      b[r + 1] = (int) ((b1 + c1 + 4) >> 3);
      b[r + 2] = (int) ((b1 - c1 + 4) >> 3);
    }
  }

  private static int avg3(int l, int t, int r) {
    return (l + 2 * t + r + 2) >> 2;
  }

  private static int avg2(int a, int b) {
    return (a + b + 1) >> 1;
  }

  private static void addResidue(byte[] ws, int[] res, int ro, int y0, int x0, int stride) {
    for (int y = 0; y < 4; y++)
      for (int x = 0; x < 4; x++) {
        int at = (y0 + y) * stride + x0 + x;
        ws[at] = (byte) clamp((ws[at] & 255) + res[ro + y * 4 + x], 255);
      }
  }

  private void luma(int mbx, int mby, int ymode, int[] bmodes, int[] res) {
    final int s = 21;
    byte[] ws = new byte[17 * s];
    if (mby == 0) for (int i = 1; i < s; i++) ws[i] = 127;
    else {
      for (int i = 0; i < 16; i++) ws[1 + i] = topY[mbx * 16 + i];
      for (int i = 16; i < 20; i++) ws[1 + i] = mbx == mbw - 1 ? topY[mbx * 16 + 15] : topY[mbx * 16 + i];
    }
    for (int i = 17; i < s; i++) ws[4 * s + i] = ws[8 * s + i] = ws[12 * s + i] = ws[i];
    for (int i = 0; i < 16; i++) ws[(i + 1) * s] = mbx == 0 ? (byte) 129 : leftY[1 + i];
    ws[0] = mby == 0 ? (byte) 127 : mbx == 0 ? (byte) 129 : leftY[0];
    if (ymode == 4) {
      for (int sb = 0; sb < 16; sb++) {
        int y0 = (sb >> 2) * 4 + 1, x0 = (sb & 3) * 4 + 1;
        sub(ws, bmodes[sb], x0, y0, s);
        addResidue(ws, res, sb * 16, y0, x0, s);
      }
    } else {
      predict(ws, ymode, 16, s, mbx, mby);
      for (int sb = 0; sb < 16; sb++) addResidue(ws, res, sb * 16, (sb >> 2) * 4 + 1, (sb & 3) * 4 + 1, s);
    }
    leftY[0] = ws[16];
    for (int i = 0; i < 16; i++) leftY[1 + i] = ws[(i + 1) * s + 16];
    for (int i = 0; i < 16; i++) topY[mbx * 16 + i] = ws[16 * s + 1 + i];
    for (int y = 0; y < 16; y++)
      System.arraycopy(ws, (1 + y) * s + 1, ybuf, (mby * 16 + y) * mbw * 16 + mbx * 16, 16);
  }

  private void chroma(int mbx, int mby, int mode, int[] res) {
    for (int c = 0; c < 2; c++) {
      byte[] top = c == 0 ? topU : topV, left = c == 0 ? leftU : leftV, buf = c == 0 ? ubuf : vbuf;
      final int s = 9;
      byte[] ws = new byte[81];
      for (int i = 0; i < 8; i++) ws[1 + i] = mby == 0 ? (byte) 127 : top[mbx * 8 + i];
      for (int i = 0; i < 8; i++) ws[(i + 1) * s] = mbx == 0 ? (byte) 129 : left[1 + i];
      ws[0] = mby == 0 ? (byte) 127 : mbx == 0 ? (byte) 129 : left[0];
      predict(ws, mode, 8, s, mbx, mby);
      for (int b = 0; b < 4; b++)
        addResidue(ws, res, (16 + 4 * c + b) * 16, (b >> 1) * 4 + 1, (b & 1) * 4 + 1, s);
      left[0] = ws[8];
      for (int i = 0; i < 8; i++) left[1 + i] = ws[(i + 1) * s + 8];
      for (int i = 0; i < 8; i++) top[mbx * 8 + i] = ws[8 * s + 1 + i];
      for (int y = 0; y < 8; y++)
        System.arraycopy(ws, (1 + y) * s + 1, buf, (mby * 8 + y) * mbw * 8 + mbx * 8, 8);
    }
  }

  /** Whole-block prediction: 0 DC, 1 V, 2 H, 3 TM. */
  private static void predict(byte[] a, int mode, int size, int s, int mbx, int mby) {
    if (mode == 0) {
      int sum = 0, shf = size == 8 ? 2 : 3;
      boolean left = mbx != 0, above = mby != 0;
      if (left) {
        for (int y = 0; y < size; y++) sum += a[(y + 1) * s] & 255;
        shf++;
      }
      if (above) {
        for (int x = 1; x <= size; x++) sum += a[x] & 255;
        shf++;
      }
      int dc = !left && !above ? 128 : (sum + (1 << (shf - 1))) >> shf;
      for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) a[(y + 1) * s + 1 + x] = (byte) dc;
    } else if (mode == 1) {
      for (int y = 0; y < size; y++) System.arraycopy(a, 1, a, (y + 1) * s + 1, size);
    } else if (mode == 2) {
      for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) a[(y + 1) * s + 1 + x] = a[(y + 1) * s];
    } else tm(a, size, 1, 1, s);
  }

  private static void tm(byte[] a, int size, int x0, int y0, int s) {
    int p = a[(y0 - 1) * s + x0 - 1] & 255;
    for (int y = 0; y < size; y++) {
      int l = (a[(y0 + y) * s + x0 - 1] & 255) - p;
      for (int x = 0; x < size; x++)
        a[(y0 + y) * s + x0 + x] = (byte) clamp(l + (a[(y0 - 1) * s + x0 + x] & 255), 255);
    }
  }

  private static void set(byte[] a, int s, int x0, int y0, int x, int y, int v) {
    a[(y0 + y) * s + x0 + x] = (byte) v;
  }

  /** 4x4 sub-block prediction: 0 DC, 1 TM, 2 VE, 3 HE, 4 LD, 5 RD, 6 VR, 7 VL, 8 HD, 9 HU. */
  private static void sub(byte[] a, int mode, int x0, int y0, int s) {
    int top = (y0 - 1) * s + x0;
    int p = a[top - 1] & 255;
    int[] t = new int[8], l = new int[4];
    for (int i = 0; i < 8; i++) t[i] = a[top + i] & 255;
    for (int i = 0; i < 4; i++) l[i] = a[(y0 + i) * s + x0 - 1] & 255;
    int[] e = {l[3], l[2], l[1], l[0], p, t[0], t[1], t[2], t[3]};
    switch (mode) {
      case 0: {
        int v = 4;
        for (int i = 0; i < 4; i++) v += t[i] + l[i];
        v >>= 3;
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) set(a, s, x0, y0, x, y, v);
        break;
      }
      case 1: tm(a, 4, x0, y0, s); break;
      case 2: {
        int[] v = {avg3(p, t[0], t[1]), avg3(t[0], t[1], t[2]), avg3(t[1], t[2], t[3]), avg3(t[2], t[3], t[4])};
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) set(a, s, x0, y0, x, y, v[x]);
        break;
      }
      case 3: {
        int[] v = {avg3(p, l[0], l[1]), avg3(l[0], l[1], l[2]), avg3(l[1], l[2], l[3]), avg3(l[2], l[3], l[3])};
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) set(a, s, x0, y0, x, y, v[y]);
        break;
      }
      case 4: {
        int[] v = new int[7];
        for (int i = 0; i < 7; i++) v[i] = avg3(t[i], t[i + 1], t[Math.min(i + 2, 7)]);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) set(a, s, x0, y0, x, y, v[x + y]);
        break;
      }
      case 5: {
        int[] v = new int[7];
        for (int i = 0; i < 7; i++) v[i] = avg3(e[i], e[i + 1], e[i + 2]);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) set(a, s, x0, y0, x, y, v[3 - y + x]);
        break;
      }
      case 6: {
        set(a, s, x0, y0, 0, 3, avg3(e[1], e[2], e[3]));
        set(a, s, x0, y0, 0, 2, avg3(e[2], e[3], e[4]));
        set(a, s, x0, y0, 1, 3, avg3(e[3], e[4], e[5]));
        set(a, s, x0, y0, 0, 1, avg3(e[3], e[4], e[5]));
        set(a, s, x0, y0, 1, 2, avg2(e[4], e[5]));
        set(a, s, x0, y0, 0, 0, avg2(e[4], e[5]));
        set(a, s, x0, y0, 2, 3, avg3(e[4], e[5], e[6]));
        set(a, s, x0, y0, 1, 1, avg3(e[4], e[5], e[6]));
        set(a, s, x0, y0, 2, 2, avg2(e[5], e[6]));
        set(a, s, x0, y0, 1, 0, avg2(e[5], e[6]));
        set(a, s, x0, y0, 3, 3, avg3(e[5], e[6], e[7]));
        set(a, s, x0, y0, 2, 1, avg3(e[5], e[6], e[7]));
        set(a, s, x0, y0, 3, 2, avg2(e[6], e[7]));
        set(a, s, x0, y0, 2, 0, avg2(e[6], e[7]));
        set(a, s, x0, y0, 3, 1, avg3(e[6], e[7], e[8]));
        set(a, s, x0, y0, 3, 0, avg2(e[7], e[8]));
        break;
      }
      case 7: {
        set(a, s, x0, y0, 0, 0, avg2(t[0], t[1]));
        set(a, s, x0, y0, 0, 1, avg3(t[0], t[1], t[2]));
        set(a, s, x0, y0, 0, 2, avg2(t[1], t[2]));
        set(a, s, x0, y0, 1, 0, avg2(t[1], t[2]));
        set(a, s, x0, y0, 1, 1, avg3(t[1], t[2], t[3]));
        set(a, s, x0, y0, 0, 3, avg3(t[1], t[2], t[3]));
        set(a, s, x0, y0, 1, 2, avg2(t[2], t[3]));
        set(a, s, x0, y0, 2, 0, avg2(t[2], t[3]));
        set(a, s, x0, y0, 1, 3, avg3(t[2], t[3], t[4]));
        set(a, s, x0, y0, 2, 1, avg3(t[2], t[3], t[4]));
        set(a, s, x0, y0, 2, 2, avg2(t[3], t[4]));
        set(a, s, x0, y0, 3, 0, avg2(t[3], t[4]));
        set(a, s, x0, y0, 2, 3, avg3(t[3], t[4], t[5]));
        set(a, s, x0, y0, 3, 1, avg3(t[3], t[4], t[5]));
        set(a, s, x0, y0, 3, 2, avg3(t[4], t[5], t[6]));
        set(a, s, x0, y0, 3, 3, avg3(t[5], t[6], t[7]));
        break;
      }
      case 8: {
        set(a, s, x0, y0, 0, 3, avg2(e[0], e[1]));
        set(a, s, x0, y0, 1, 3, avg3(e[0], e[1], e[2]));
        set(a, s, x0, y0, 0, 2, avg2(e[1], e[2]));
        set(a, s, x0, y0, 2, 3, avg2(e[1], e[2]));
        set(a, s, x0, y0, 1, 2, avg3(e[1], e[2], e[3]));
        set(a, s, x0, y0, 3, 3, avg3(e[1], e[2], e[3]));
        set(a, s, x0, y0, 2, 2, avg2(e[2], e[3]));
        set(a, s, x0, y0, 0, 1, avg2(e[2], e[3]));
        set(a, s, x0, y0, 3, 2, avg3(e[2], e[3], e[4]));
        set(a, s, x0, y0, 1, 1, avg3(e[2], e[3], e[4]));
        set(a, s, x0, y0, 2, 1, avg2(e[3], e[4]));
        set(a, s, x0, y0, 0, 0, avg2(e[3], e[4]));
        set(a, s, x0, y0, 3, 1, avg3(e[3], e[4], e[5]));
        set(a, s, x0, y0, 1, 0, avg3(e[3], e[4], e[5]));
        set(a, s, x0, y0, 2, 0, avg3(e[4], e[5], e[6]));
        set(a, s, x0, y0, 3, 0, avg3(e[5], e[6], e[7]));
        break;
      }
      default: {
        set(a, s, x0, y0, 0, 0, avg2(l[0], l[1]));
        set(a, s, x0, y0, 1, 0, avg3(l[0], l[1], l[2]));
        set(a, s, x0, y0, 2, 0, avg2(l[1], l[2]));
        set(a, s, x0, y0, 0, 1, avg2(l[1], l[2]));
        set(a, s, x0, y0, 3, 0, avg3(l[1], l[2], l[3]));
        set(a, s, x0, y0, 1, 1, avg3(l[1], l[2], l[3]));
        set(a, s, x0, y0, 2, 1, avg2(l[2], l[3]));
        set(a, s, x0, y0, 0, 2, avg2(l[2], l[3]));
        set(a, s, x0, y0, 3, 1, avg3(l[2], l[3], l[3]));
        set(a, s, x0, y0, 1, 2, avg3(l[2], l[3], l[3]));
        set(a, s, x0, y0, 2, 2, l[3]);
        set(a, s, x0, y0, 3, 2, l[3]);
        set(a, s, x0, y0, 0, 3, l[3]);
        set(a, s, x0, y0, 1, 3, l[3]);
        set(a, s, x0, y0, 2, 3, l[3]);
        set(a, s, x0, y0, 3, 3, l[3]);
      }
    }
  }

  private static int c8(int v) {
    return v < -128 ? -128 : v > 127 ? 127 : v;
  }

  private static int u2s(byte v) {
    return (v & 255) - 128;
  }

  private static byte s2u(int v) {
    return (byte) (c8(v) + 128);
  }

  private static int px(byte[] b, int at) {
    return b[at] & 255;
  }

  private static int adjust(boolean outer, byte[] b, int at, int st) {
    int p1 = u2s(b[at - 2 * st]), p0 = u2s(b[at - st]), q0 = u2s(b[at]), q1 = u2s(b[at + st]);
    int a = c8((outer ? c8(p1 - q1) : 0) + 3 * (q0 - p0));
    int bb = c8(a + 3) >> 3;
    a = c8(a + 4) >> 3;
    b[at] = s2u(q0 - a);
    b[at - st] = s2u(p0 + bb);
    return a;
  }

  private static boolean simpleOk(int limit, byte[] b, int at, int st) {
    return Math.abs(px(b, at - st) - px(b, at)) * 2 + Math.abs(px(b, at - 2 * st) - px(b, at + st)) / 2 <= limit;
  }

  private static boolean normalOk(int interior, int edge, byte[] b, int at, int st) {
    return simpleOk(edge, b, at, st)
        && Math.abs(px(b, at - 4 * st) - px(b, at - 3 * st)) <= interior
        && Math.abs(px(b, at - 3 * st) - px(b, at - 2 * st)) <= interior
        && Math.abs(px(b, at - 2 * st) - px(b, at - st)) <= interior
        && Math.abs(px(b, at + 3 * st) - px(b, at + 2 * st)) <= interior
        && Math.abs(px(b, at + 2 * st) - px(b, at + st)) <= interior
        && Math.abs(px(b, at + st) - px(b, at)) <= interior;
  }

  private static boolean hev(int t, byte[] b, int at, int st) {
    return Math.abs(px(b, at - 2 * st) - px(b, at - st)) > t || Math.abs(px(b, at + st) - px(b, at)) > t;
  }

  private static void simple(int edge, byte[] b, int at, int st) {
    if (simpleOk(edge, b, at, st)) adjust(true, b, at, st);
  }

  private static void subblock(int hevT, int interior, int edge, byte[] b, int at, int st) {
    if (!normalOk(interior, edge, b, at, st)) return;
    boolean hv = hev(hevT, b, at, st);
    int a = (adjust(hv, b, at, st) + 1) >> 1;
    if (!hv) {
      b[at + st] = s2u(u2s(b[at + st]) - a);
      b[at - 2 * st] = s2u(u2s(b[at - 2 * st]) + a);
    }
  }

  private static void macroblock(int hevT, int interior, int edge, byte[] b, int at, int st) {
    if (!normalOk(interior, edge, b, at, st)) return;
    if (hev(hevT, b, at, st)) {
      adjust(true, b, at, st);
      return;
    }
    int p2 = u2s(b[at - 3 * st]), p1 = u2s(b[at - 2 * st]), p0 = u2s(b[at - st]);
    int q0 = u2s(b[at]), q1 = u2s(b[at + st]), q2 = u2s(b[at + 2 * st]);
    int w = c8(c8(p1 - q1) + 3 * (q0 - p0));
    int a = c8((27 * w + 63) >> 7);
    b[at] = s2u(q0 - a);
    b[at - st] = s2u(p0 + a);
    a = c8((18 * w + 63) >> 7);
    b[at + st] = s2u(q1 - a);
    b[at - 2 * st] = s2u(p1 + a);
    a = c8((9 * w + 63) >> 7);
    b[at + 2 * st] = s2u(q2 - a);
    b[at - 3 * st] = s2u(p2 + a);
  }

  private void filter(int mbx, int mby, int[] mb) {
    int level = filterLevel;
    if (segmentsEnabled) level = deltaValues ? level + filterLevelSeg[mb[0]] : filterLevelSeg[mb[0]];
    level = clamp(level, 63);
    if (lfAdjust) {
      level += refDelta[0];
      if (mb[1] == 4) level += modeDelta[0];
    }
    level = clamp(level, 63);
    if (level == 0) return;
    int interior = level;
    if (sharpness > 0) {
      interior >>= sharpness > 4 ? 2 : 1;
      interior = Math.min(interior, 9 - sharpness);
    }
    if (interior == 0) interior = 1;
    int hevT = level >= 40 ? 2 : level >= 15 ? 1 : 0;
    int mbEdge = (level + 2) * 2 + interior, subEdge = level * 2 + interior;
    boolean inner = mb[1] == 4 || (mb[2] == 0 && mb[3] == 1);
    int lw = mbw * 16, cw = mbw * 8;
    if (mbx > 0) {
      for (int y = 0; y < 16; y++) {
        int at = (mby * 16 + y) * lw + mbx * 16;
        if (simpleFilter) simple(mbEdge, ybuf, at, 1);
        else macroblock(hevT, interior, mbEdge, ybuf, at, 1);
      }
      if (!simpleFilter)
        for (int y = 0; y < 8; y++) {
          int at = (mby * 8 + y) * cw + mbx * 8;
          macroblock(hevT, interior, mbEdge, ubuf, at, 1);
          macroblock(hevT, interior, mbEdge, vbuf, at, 1);
        }
    }
    if (inner) {
      for (int x = 4; x < 16; x += 4)
        for (int y = 0; y < 16; y++) {
          int at = (mby * 16 + y) * lw + mbx * 16 + x;
          if (simpleFilter) simple(subEdge, ybuf, at, 1);
          else subblock(hevT, interior, subEdge, ybuf, at, 1);
        }
      if (!simpleFilter)
        for (int y = 0; y < 8; y++) {
          int at = (mby * 8 + y) * cw + mbx * 8 + 4;
          subblock(hevT, interior, subEdge, ubuf, at, 1);
          subblock(hevT, interior, subEdge, vbuf, at, 1);
        }
    }
    if (mby > 0) {
      for (int x = 0; x < 16; x++) {
        int at = mby * 16 * lw + mbx * 16 + x;
        if (simpleFilter) simple(mbEdge, ybuf, at, lw);
        else macroblock(hevT, interior, mbEdge, ybuf, at, lw);
      }
      if (!simpleFilter)
        for (int x = 0; x < 8; x++) {
          int at = mby * 8 * cw + mbx * 8 + x;
          macroblock(hevT, interior, mbEdge, ubuf, at, cw);
          macroblock(hevT, interior, mbEdge, vbuf, at, cw);
        }
    }
    if (inner) {
      for (int y = 4; y < 16; y += 4)
        for (int x = 0; x < 16; x++) {
          int at = (mby * 16 + y) * lw + mbx * 16 + x;
          if (simpleFilter) simple(subEdge, ybuf, at, lw);
          else subblock(hevT, interior, subEdge, ybuf, at, lw);
        }
      if (!simpleFilter)
        for (int x = 0; x < 8; x++) {
          int at = (mby * 8 + 4) * cw + mbx * 8 + x;
          subblock(hevT, interior, subEdge, ubuf, at, cw);
          subblock(hevT, interior, subEdge, vbuf, at, cw);
        }
    }
  }

  private static int mulhi(int v, int c) {
    return (v * c) >> 8;
  }

  private static int clip(int v) {
    return clamp(v >> 6, 255);
  }

  /** YUV 4:2:0 to ARGB with libwebp's "fancy" bilinear chroma upsampling. */
  private PictureCodecs.Raster toArgb() {
    int[] out = new int[width * height];
    int bw = mbw * 16, cbw = mbw * 8, cw = (width + 1) / 2, ch = (height + 1) / 2;
    for (int y = 0; y < height; y++) {
      int vm = 0, vs = 0;
      if (y > 0) {
        int j = (y - 1) >> 1;
        boolean odd = ((y - 1) & 1) == 0;
        vm = Math.min(odd ? j : j + 1, ch - 1);
        vs = Math.min(odd ? j + 1 : j, ch - 1);
      }
      for (int x = 0; x < width; x++) {
        int hm = 0, hs = 0;
        if (x > 0) {
          int j = (x - 1) >> 1;
          boolean odd = ((x - 1) & 1) == 0;
          hm = Math.min(odd ? j : j + 1, cw - 1);
          hs = Math.min(odd ? j + 1 : j, cw - 1);
        }
        int u = fancy(ubuf, cbw, vm, vs, hm, hs), v = fancy(vbuf, cbw, vm, vs, hm, hs);
        int yy = ybuf[y * bw + x] & 255;
        int r = clip(mulhi(yy, 19077) + mulhi(v, 26149) - 14234);
        int g = clip(mulhi(yy, 19077) - mulhi(u, 6419) - mulhi(v, 13320) + 8708);
        int b = clip(mulhi(yy, 19077) + mulhi(u, 33050) - 17685);
        out[y * width + x] = 0xff000000 | r << 16 | g << 8 | b;
      }
    }
    return new PictureCodecs.Raster(width, height, out);
  }

  private static int fancy(byte[] c, int stride, int vm, int vs, int hm, int hs) {
    return (9 * (c[vm * stride + hm] & 255) + 3 * (c[vm * stride + hs] & 255)
        + 3 * (c[vs * stride + hm] & 255) + (c[vs * stride + hs] & 255) + 8) / 16;
  }
}
