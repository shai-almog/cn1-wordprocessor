package com.codename1.ui.editor;

/**
 * Exposes a full Unicode bidirectional algorithm (UAX #9) to the word processor. It is Codename One's
 * {@code BidiUtil} (package-private, so this class lives in its package) extended with explicit
 * embeddings, overrides and isolates (X1-X10), paired brackets (N0) and the L1 separator reset. The same
 * change is proposed upstream as docs/framework-patches/bidi-uax9-full.patch; once the framework ships it,
 * the section between the UAX9 markers can be deleted and these methods can delegate to BidiUtil again.
 */
public final class BidiBridge {
  private BidiBridge() {}

  /** Resolved embedding level of every char; even is left-to-right, odd right-to-left. */
  public static byte[] levels(String text, boolean rtlParagraph) {
    return resolveLevels(text, rtlParagraph);
  }

  /** Rule L2: element v is the logical index of the item drawn at visual position v. */
  public static int[] visualOrder(byte[] levels) {
    return BidiUtil.reorderVisual(levels);
  }

  /** True when the text is plain left-to-right and needs no bidi processing. */
  public static boolean trivial(String text, boolean rtlParagraph) {
    return isTrivialLtr(text, rtlParagraph);
  }

  private static final byte L = BidiUtil.L;
  private static final byte R = BidiUtil.R;
  private static final byte AL = BidiUtil.AL;
  private static final byte EN = BidiUtil.EN;
  private static final byte ES = BidiUtil.ES;
  private static final byte ET = BidiUtil.ET;
  private static final byte AN = BidiUtil.AN;
  private static final byte CS = BidiUtil.CS;
  private static final byte NSM = BidiUtil.NSM;
  private static final byte BN = BidiUtil.BN;
  private static final byte ON = BidiUtil.ON;
  private static final byte WS = BidiUtil.WS;

  // BEGIN UAX9 (kept identical to the framework patch body)
    // Explicit formatting characters, separators and paragraph separators (UAX #9 Table 4).
    static final byte LRE = 12;
    static final byte RLE = 13;
    static final byte LRO = 14;
    static final byte RLO = 15;
    static final byte PDF = 16;
    static final byte LRI = 17;
    static final byte RLI = 18;
    static final byte FSI = 19;
    static final byte PDI = 20;
    static final byte B = 21;   // Paragraph Separator
    static final byte S = 22;   // Segment Separator (tab)

    private static final int MAX_DEPTH = 125;
    private static final int MAX_BRACKETS = 63;
    // Bidi_Class of every code point (Unicode 16.0.0, DerivedBidiClass as in the unicode-bidi crate):
    // pairs of chars, the low 16 bits of a range start then (start >> 16) << 5 | class.
    private static final String CLASS_RANGES = "\000\011\011\026\012\025\013\026\014\013\015\025\016\011\034\025\037\026 \013!\012#\005&\012+\004,"
            + "\007-\004.\0070\003:\007;\012A\000[\012a\000{\012\u007F\011\u0085\025\u0086\011\u00A0\007\u00A1\012"
            + "\u00A2\005\u00A6\012\u00AA\000\u00AB\012\u00AD\011\u00AE\012\u00B0\005\u00B2\003\u00B4\012\u00B5\000"
            + "\u00B6\012\u00B9\003\u00BA\000\u00BB\012\u00C0\000\u00D7\012\u00D8\000\u00F7\012\u00F8\000\u02B9\012"
            + "\u02BB\000\u02C2\012\u02D0\000\u02D2\012\u02E0\000\u02E5\012\u02EE\000\u02EF\012\u0300\010\u0370\000"
            + "\u0374\012\u0376\000\u037E\012\u037F\000\u0384\012\u0386\000\u0387\012\u0388\000\u03F6\012\u03F7\000"
            + "\u0483\010\u048A\000\u058A\012\u058B\000\u058D\012\u058F\005\u0590\001\u0591\010\u05BE\001\u05BF\010"
            + "\u05C0\001\u05C1\010\u05C3\001\u05C4\010\u05C6\001\u05C7\010\u05C8\001\u0600\006\u0606\012\u0608\002"
            + "\u0609\005\u060B\002\u060C\007\u060D\002\u060E\012\u0610\010\u061B\002\u064B\010\u0660\006\u066A\005"
            + "\u066B\006\u066D\002\u0670\010\u0671\002\u06D6\010\u06DD\006\u06DE\012\u06DF\010\u06E5\002\u06E7\010"
            + "\u06E9\012\u06EA\010\u06EE\002\u06F0\003\u06FA\002\u0711\010\u0712\002\u0730\010\u074B\002\u07A6\010"
            + "\u07B1\002\u07C0\001\u07EB\010\u07F4\001\u07F6\012\u07FA\001\u07FD\010\u07FE\001\u0816\010\u081A\001"
            + "\u081B\010\u0824\001\u0825\010\u0828\001\u0829\010\u082E\001\u0859\010\u085C\001\u0860\002\u086B\001"
            + "\u0870\002\u088F\001\u0890\006\u0892\001\u0897\010\u08A0\002\u08CA\010\u08E2\006\u08E3\010\u0903\000"
            + "\u093A\010\u093B\000\u093C\010\u093D\000\u0941\010\u0949\000\u094D\010\u094E\000\u0951\010\u0958\000"
            + "\u0962\010\u0964\000\u0981\010\u0982\000\u09BC\010\u09BD\000\u09C1\010\u09C5\000\u09CD\010\u09CE\000"
            + "\u09E2\010\u09E4\000\u09F2\005\u09F4\000\u09FB\005\u09FC\000\u09FE\010\u09FF\000\u0A01\010\u0A03\000"
            + "\u0A3C\010\u0A3D\000\u0A41\010\u0A43\000\u0A47\010\u0A49\000\u0A4B\010\u0A4E\000\u0A51\010\u0A52\000"
            + "\u0A70\010\u0A72\000\u0A75\010\u0A76\000\u0A81\010\u0A83\000\u0ABC\010\u0ABD\000\u0AC1\010\u0AC6\000"
            + "\u0AC7\010\u0AC9\000\u0ACD\010\u0ACE\000\u0AE2\010\u0AE4\000\u0AF1\005\u0AF2\000\u0AFA\010\u0B00\000"
            + "\u0B01\010\u0B02\000\u0B3C\010\u0B3D\000\u0B3F\010\u0B40\000\u0B41\010\u0B45\000\u0B4D\010\u0B4E\000"
            + "\u0B55\010\u0B57\000\u0B62\010\u0B64\000\u0B82\010\u0B83\000\u0BC0\010\u0BC1\000\u0BCD\010\u0BCE\000"
            + "\u0BF3\012\u0BF9\005\u0BFA\012\u0BFB\000\u0C00\010\u0C01\000\u0C04\010\u0C05\000\u0C3C\010\u0C3D\000"
            + "\u0C3E\010\u0C41\000\u0C46\010\u0C49\000\u0C4A\010\u0C4E\000\u0C55\010\u0C57\000\u0C62\010\u0C64\000"
            + "\u0C78\012\u0C7F\000\u0C81\010\u0C82\000\u0CBC\010\u0CBD\000\u0CCC\010\u0CCE\000\u0CE2\010\u0CE4\000"
            + "\u0D00\010\u0D02\000\u0D3B\010\u0D3D\000\u0D41\010\u0D45\000\u0D4D\010\u0D4E\000\u0D62\010\u0D64\000"
            + "\u0D81\010\u0D82\000\u0DCA\010\u0DCB\000\u0DD2\010\u0DD5\000\u0DD6\010\u0DD7\000\u0E31\010\u0E32\000"
            + "\u0E34\010\u0E3B\000\u0E3F\005\u0E40\000\u0E47\010\u0E4F\000\u0EB1\010\u0EB2\000\u0EB4\010\u0EBD\000"
            + "\u0EC8\010\u0ECF\000\u0F18\010\u0F1A\000\u0F35\010\u0F36\000\u0F37\010\u0F38\000\u0F39\010\u0F3A\012"
            + "\u0F3E\000\u0F71\010\u0F7F\000\u0F80\010\u0F85\000\u0F86\010\u0F88\000\u0F8D\010\u0F98\000\u0F99\010"
            + "\u0FBD\000\u0FC6\010\u0FC7\000\u102D\010\u1031\000\u1032\010\u1038\000\u1039\010\u103B\000\u103D\010"
            + "\u103F\000\u1058\010\u105A\000\u105E\010\u1061\000\u1071\010\u1075\000\u1082\010\u1083\000\u1085\010"
            + "\u1087\000\u108D\010\u108E\000\u109D\010\u109E\000\u135D\010\u1360\000\u1390\012\u139A\000\u1400\012"
            + "\u1401\000\u1680\013\u1681\000\u169B\012\u169D\000\u1712\010\u1715\000\u1732\010\u1734\000\u1752\010"
            + "\u1754\000\u1772\010\u1774\000\u17B4\010\u17B6\000\u17B7\010\u17BE\000\u17C6\010\u17C7\000\u17C9\010"
            + "\u17D4\000\u17DB\005\u17DC\000\u17DD\010\u17DE\000\u17F0\012\u17FA\000\u1800\012\u180B\010\u180E\011"
            + "\u180F\010\u1810\000\u1885\010\u1887\000\u18A9\010\u18AA\000\u1920\010\u1923\000\u1927\010\u1929\000"
            + "\u1932\010\u1933\000\u1939\010\u193C\000\u1940\012\u1941\000\u1944\012\u1946\000\u19DE\012\u1A00\000"
            + "\u1A17\010\u1A19\000\u1A1B\010\u1A1C\000\u1A56\010\u1A57\000\u1A58\010\u1A5F\000\u1A60\010\u1A61\000"
            + "\u1A62\010\u1A63\000\u1A65\010\u1A6D\000\u1A73\010\u1A7D\000\u1A7F\010\u1A80\000\u1AB0\010\u1ACF\000"
            + "\u1B00\010\u1B04\000\u1B34\010\u1B35\000\u1B36\010\u1B3B\000\u1B3C\010\u1B3D\000\u1B42\010\u1B43\000"
            + "\u1B6B\010\u1B74\000\u1B80\010\u1B82\000\u1BA2\010\u1BA6\000\u1BA8\010\u1BAA\000\u1BAB\010\u1BAE\000"
            + "\u1BE6\010\u1BE7\000\u1BE8\010\u1BEA\000\u1BED\010\u1BEE\000\u1BEF\010\u1BF2\000\u1C2C\010\u1C34\000"
            + "\u1C36\010\u1C38\000\u1CD0\010\u1CD3\000\u1CD4\010\u1CE1\000\u1CE2\010\u1CE9\000\u1CED\010\u1CEE\000"
            + "\u1CF4\010\u1CF5\000\u1CF8\010\u1CFA\000\u1DC0\010\u1E00\000\u1FBD\012\u1FBE\000\u1FBF\012\u1FC2\000"
            + "\u1FCD\012\u1FD0\000\u1FDD\012\u1FE0\000\u1FED\012\u1FF0\000\u1FFD\012\u1FFF\000\u2000\013\u200B\011"
            + "\u200E\000\u200F\001\u2010\012\u2028\013\u2029\025\u202A\014\u202B\015\u202C\020\u202D\016\u202E\017"
            + "\u202F\007\u2030\005\u2035\012\u2044\007\u2045\012\u205F\013\u2060\011\u2065\000\u2066\021\u2067\022"
            + "\u2068\023\u2069\024\u206A\011\u2070\003\u2071\000\u2074\003\u207A\004\u207C\012\u207F\000\u2080\003"
            + "\u208A\004\u208C\012\u208F\000\u20A0\005\u20D0\010\u20F1\000\u2100\012\u2102\000\u2103\012\u2107\000"
            + "\u2108\012\u210A\000\u2114\012\u2115\000\u2116\012\u2119\000\u211E\012\u2124\000\u2125\012\u2126\000"
            + "\u2127\012\u2128\000\u2129\012\u212A\000\u212E\005\u212F\000\u213A\012\u213C\000\u2140\012\u2145\000"
            + "\u214A\012\u214E\000\u2150\012\u2160\000\u2189\012\u218C\000\u2190\012\u2212\004\u2213\005\u2214\012"
            + "\u2336\000\u237B\012\u2395\000\u2396\012\u242A\000\u2440\012\u244B\000\u2460\012\u2488\003\u249C\000"
            + "\u24EA\012\u26AC\000\u26AD\012\u2800\000\u2900\012\u2B74\000\u2B76\012\u2B96\000\u2B97\012\u2C00\000"
            + "\u2CE5\012\u2CEB\000\u2CEF\010\u2CF2\000\u2CF9\012\u2D00\000\u2D7F\010\u2D80\000\u2DE0\010\u2E00\012"
            + "\u2E5E\000\u2E80\012\u2E9A\000\u2E9B\012\u2EF4\000\u2F00\012\u2FD6\000\u2FF0\012\u3000\013\u3001\012"
            + "\u3005\000\u3008\012\u3021\000\u302A\010\u302E\000\u3030\012\u3031\000\u3036\012\u3038\000\u303D\012"
            + "\u3040\000\u3099\010\u309B\012\u309D\000\u30A0\012\u30A1\000\u30FB\012\u30FC\000\u31C0\012\u31E6\000"
            + "\u31EF\012\u31F0\000\u321D\012\u321F\000\u3250\012\u3260\000\u327C\012\u327F\000\u32B1\012\u32C0\000"
            + "\u32CC\012\u32D0\000\u3377\012\u337B\000\u33DE\012\u33E0\000\u33FF\012\u3400\000\u4DC0\012\u4E00\000"
            + "\uA490\012\uA4C7\000\uA60D\012\uA610\000\uA66F\010\uA673\012\uA674\010\uA67E\012\uA680\000\uA69E\010"
            + "\uA6A0\000\uA6F0\010\uA6F2\000\uA700\012\uA722\000\uA788\012\uA789\000\uA802\010\uA803\000\uA806\010"
            + "\uA807\000\uA80B\010\uA80C\000\uA825\010\uA827\000\uA828\012\uA82C\010\uA82D\000\uA838\005\uA83A\000"
            + "\uA874\012\uA878\000\uA8C4\010\uA8C6\000\uA8E0\010\uA8F2\000\uA8FF\010\uA900\000\uA926\010\uA92E\000"
            + "\uA947\010\uA952\000\uA980\010\uA983\000\uA9B3\010\uA9B4\000\uA9B6\010\uA9BA\000\uA9BC\010\uA9BE\000"
            + "\uA9E5\010\uA9E6\000\uAA29\010\uAA2F\000\uAA31\010\uAA33\000\uAA35\010\uAA37\000\uAA43\010\uAA44\000"
            + "\uAA4C\010\uAA4D\000\uAA7C\010\uAA7D\000\uAAB0\010\uAAB1\000\uAAB2\010\uAAB5\000\uAAB7\010\uAAB9\000"
            + "\uAABE\010\uAAC0\000\uAAC1\010\uAAC2\000\uAAEC\010\uAAEE\000\uAAF6\010\uAAF7\000\uAB6A\012\uAB6C\000"
            + "\uABE5\010\uABE6\000\uABE8\010\uABE9\000\uABED\010\uABEE\000\uFB1D\001\uFB1E\010\uFB1F\001\uFB29\004"
            + "\uFB2A\001\uFB50\002\uFD3E\012\uFD50\002\uFDCF\012\uFDD0\000\uFDF0\002\uFDFD\012\uFE00\010\uFE10\012"
            + "\uFE1A\000\uFE20\010\uFE30\012\uFE50\007\uFE51\012\uFE52\007\uFE53\000\uFE54\012\uFE55\007\uFE56\012"
            + "\uFE5F\005\uFE60\012\uFE62\004\uFE64\012\uFE67\000\uFE68\012\uFE69\005\uFE6B\012\uFE6C\000\uFE70\002"
            + "\uFEFF\011\uFF00\000\uFF01\012\uFF03\005\uFF06\012\uFF0B\004\uFF0C\007\uFF0D\004\uFF0E\007\uFF10\003"
            + "\uFF1A\007\uFF1B\012\uFF21\000\uFF3B\012\uFF41\000\uFF5B\012\uFF66\000\uFFE0\005\uFFE2\012\uFFE5\005"
            + "\uFFE7\000\uFFE8\012\uFFEF\000\uFFF9\012\uFFFE\000\u0101*\u0102 \u0140*\u018D \u0190*\u019D \u01A0*"
            + "\u01A1 \u01FD(\u01FE \u02E0(\u02E1#\u02FC \u0376(\u037B \u0800!\u091F*\u0920!\u0A01(\u0A04!\u0A05("
            + "\u0A07!\u0A0C(\u0A10!\u0A38(\u0A3B!\u0A3F(\u0A40!\u0AE5(\u0AE7!\u0B39*\u0B40!\u0D00\042\u0D24(\u0D28"
            + "!\u0D30&\u0D3A!\u0D40&\u0D4A!\u0D69(\u0D6E*\u0D6F!\u0E60&\u0E7F!\u0EAB(\u0EAD!\u0EC2\042\u0EC5!"
            + "\u0EFC(\u0F00!\u0F30\042\u0F46(\u0F51\042\u0F5A!\u0F82(\u0F86!\u1000 \u1001(\u1002 \u1038(\u1047 "
            + "\u1052*\u1066 \u1070(\u1071 \u1073(\u1075 \u107F(\u1082 \u10B3(\u10B7 \u10B9(\u10BB \u10C2(\u10C3 "
            + "\u1100(\u1103 \u1127(\u112C \u112D(\u1135 \u1173(\u1174 \u1180(\u1182 \u11B6(\u11BF \u11C9(\u11CD "
            + "\u11CF(\u11D0 \u122F(\u1232 \u1234(\u1235 \u1236(\u1238 \u123E(\u123F \u1241(\u1242 \u12DF(\u12E0 "
            + "\u12E3(\u12EB \u1300(\u1302 \u133B(\u133D \u1340(\u1341 \u1366(\u136D \u1370(\u1375 \u13BB(\u13C1 "
            + "\u13CE(\u13CF \u13D0(\u13D1 \u13D2(\u13D3 \u13E1(\u13E3 \u1438(\u1440 \u1442(\u1445 \u1446(\u1447 "
            + "\u145E(\u145F \u14B3(\u14B9 \u14BA(\u14BB \u14BF(\u14C1 \u14C2(\u14C4 \u15B2(\u15B6 \u15BC(\u15BE "
            + "\u15BF(\u15C1 \u15DC(\u15DE \u1633(\u163B \u163D(\u163E \u163F(\u1641 \u1660*\u166D \u16AB(\u16AC "
            + "\u16AD(\u16AE \u16B0(\u16B6 \u16B7(\u16B8 \u171D(\u171E \u171F(\u1720 \u1722(\u1726 \u1727(\u172C "
            + "\u182F(\u1838 \u1839(\u183B \u193B(\u193D \u193E(\u193F \u1943(\u1944 \u19D4(\u19D8 \u19DA(\u19DC "
            + "\u19E0(\u19E1 \u1A01(\u1A07 \u1A09(\u1A0B \u1A33(\u1A39 \u1A3B(\u1A3F \u1A47(\u1A48 \u1A51(\u1A57 "
            + "\u1A59(\u1A5C \u1A8A(\u1A97 \u1A98(\u1A9A \u1C30(\u1C37 \u1C38(\u1C3E \u1C92(\u1CA8 \u1CAA(\u1CB1 "
            + "\u1CB2(\u1CB4 \u1CB5(\u1CB7 \u1D31(\u1D37 \u1D3A(\u1D3B \u1D3C(\u1D3E \u1D3F(\u1D46 \u1D47(\u1D48 "
            + "\u1D90(\u1D92 \u1D95(\u1D96 \u1D97(\u1D98 \u1EF3(\u1EF5 \u1F00(\u1F02 \u1F36(\u1F3B \u1F40(\u1F41 "
            + "\u1F42(\u1F43 \u1F5A(\u1F5B \u1FD5*\u1FDD%\u1FE1*\u1FF2 \u3440(\u3441 \u3447(\u3456 \u611E(\u612A "
            + "\u612D(\u6130 \u6AF0(\u6AF5 \u6B30(\u6B37 \u6F4F(\u6F50 \u6F8F(\u6F93 \u6FE2*\u6FE3 \u6FE4(\u6FE5 "
            + "\uBC9D(\uBC9F \uBCA0)\uBCA4 \uCC00*\uCCD6 \uCCF0#\uCCFA \uCD00*\uCEB4 \uCF00(\uCF2E \uCF30(\uCF47 "
            + "\uD167(\uD16A \uD173)\uD17B(\uD183 \uD185(\uD18C \uD1AA(\uD1AE \uD1E9*\uD1EB \uD200*\uD242(\uD245*"
            + "\uD246 \uD300*\uD357 \uD6C1*\uD6C2 \uD6DB*\uD6DC \uD6FB*\uD6FC \uD715*\uD716 \uD735*\uD736 \uD74F*"
            + "\uD750 \uD76F*\uD770 \uD789*\uD78A \uD7A9*\uD7AA \uD7C3*\uD7C4 \uD7CE#\uD800 \uDA00(\uDA37 \uDA3B("
            + "\uDA6D \uDA75(\uDA76 \uDA84(\uDA85 \uDA9B(\uDAA0 \uDAA1(\uDAB0 \uE000(\uE007 \uE008(\uE019 \uE01B("
            + "\uE022 \uE023(\uE025 \uE026(\uE02B \uE08F(\uE090 \uE130(\uE137 \uE2AE(\uE2AF \uE2EC(\uE2F0 \uE2FF%"
            + "\uE300 \uE4EC(\uE4F0 \uE5EE(\uE5F0 \uE800!\uE8D0(\uE8D7!\uE944(\uE94B!\uEC71\042\uECB5!\uED01\042"
            + "\uED3E!\uEE00\042\uEEF0*\uEEF2\042\uEF00!\uF000*\uF02C \uF030*\uF094 \uF0A0*\uF0AF \uF0B1*\uF0C0 "
            + "\uF0C1*\uF0D0 \uF0D1*\uF0F6 \uF100#\uF10B*\uF110 \uF12F*\uF130 \uF16A*\uF170 \uF1AD*\uF1AE \uF260*"
            + "\uF266 \uF300*\uF6D8 \uF6DC*\uF6ED \uF6F0*\uF6FD \uF700*\uF777 \uF77B*\uF7DA \uF7E0*\uF7EC \uF7F0*"
            + "\uF7F1 \uF800*\uF80C \uF810*\uF848 \uF850*\uF85A \uF860*\uF888 \uF890*\uF8AE \uF8B0*\uF8BC \uF8C0*"
            + "\uF8C2 \uF900*\uFA54 \uFA60*\uFA6E \uFA70*\uFA7D \uFA80*\uFA8A \uFA8F*\uFAC7 \uFACE*\uFADD \uFADF*"
            + "\uFAEA \uFAF0*\uFAF9 \uFB00*\uFB93 \uFB94*\uFBF0#\uFBFA \001\u01C9\002\u01C0 \u01C9\u0080\u01C0"
            + "\u0100\u01C8\u01F0\u01C0";
    // BD14/BD15 paired brackets (BidiBrackets.txt), opening and closing at the same index.
    private static final String OPEN = "([{\u0F3A\u0F3C\u169B\u2045\u207D\u208D\u2308\u230A\u2768\u276A\u276C\u276E\u2770\u2772\u2774\u27C5"
            + "\u27E6\u27E8\u27EA\u27EC\u27EE\u2983\u2985\u2987\u2989\u298B\u298D\u298F\u2991\u2993\u2995\u2997"
            + "\u29D8\u29DA\u29FC\u2E22\u2E24\u2E26\u2E28\u2E55\u2E57\u2E59\u2E5B\u3008\u300A\u300C\u300E\u3010"
            + "\u3014\u3016\u3018\u301A\uFE59\uFE5B\uFE5D\uFF08\uFF3B\uFF5B\uFF5F\uFF62";
    private static final String CLOSE = ")]}\u0F3B\u0F3D\u169C\u2046\u207E\u208E\u2309\u230B\u2769\u276B\u276D\u276F\u2771\u2773\u2775\u27C6"
            + "\u27E7\u27E9\u27EB\u27ED\u27EF\u2984\u2986\u2988\u298A\u298C\u2990\u298E\u2992\u2994\u2996\u2998"
            + "\u29D9\u29DB\u29FD\u2E23\u2E25\u2E27\u2E29\u2E56\u2E58\u2E5A\u2E5C\u3009\u300B\u300D\u300F\u3011"
            + "\u3015\u3017\u3019\u301B\uFE5A\uFE5C\uFE5E\uFF09\uFF3D\uFF5D\uFF60\uFF63";
    private static int[] rangeStart;
    private static byte[] rangeClass;

    /// Bidi_Class of a code point, looked up in the full Unicode table.
    static byte typeOf(int cp) {
        int[] starts = rangeStart;
        if (starts == null) {
            int count = CLASS_RANGES.length() / 2;
            int[] s = new int[count];
            byte[] k = new byte[count];
            for (int i = 0; i < count; i++) {
                int hi = CLASS_RANGES.charAt(2 * i + 1);
                s[i] = ((hi >> 5) << 16) | CLASS_RANGES.charAt(2 * i);
                k[i] = (byte) (hi & 31);
            }
            rangeClass = k;
            rangeStart = s;
            starts = s;
        }
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= cp) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return rangeClass[lo];
    }

    /// Bidi type of the code point at index i; both halves of a surrogate pair get the pair's type.
    static byte typeAt(String text, int i) {
        char c = text.charAt(i);
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < text.length()) {
            char d = text.charAt(i + 1);
            if (d >= 0xDC00 && d <= 0xDFFF) {
                return typeOf(0x10000 + ((c - 0xD800) << 10) + (d - 0xDC00));
            }
        } else if (c >= 0xDC00 && c <= 0xDFFF && i > 0) {
            char h = text.charAt(i - 1);
            if (h >= 0xD800 && h <= 0xDBFF) {
                return typeOf(0x10000 + ((h - 0xD800) << 10) + (c - 0xDC00));
            }
        }
        return typeOf((int) c);
    }

    /// Resolves the embedding level of every character of one paragraph following UAX #9 in full: explicit
    /// embeddings, overrides and isolates (X1-X10), weak types (W1-W7), paired brackets (N0), neutrals
    /// (N1-N2), implicit levels (I1-I2) and the separator/trailing-whitespace reset of L1. Characters removed
    /// by X9 take the level of the preceding character so callers can still draw them.
    static byte[] resolveLevels(String text, boolean baseRtl) {
        int n = text.length();
        byte[] levels = new byte[n];
        if (n == 0) {
            return levels;
        }
        byte para = (byte) (baseRtl ? 1 : 0);
        byte[] initial = new byte[n];
        for (int i = 0; i < n; i++) {
            initial[i] = typeAt(text, i);
        }
        // BD9: matching isolate initiators and PDIs.
        int[] matchingPdi = new int[n];
        int[] matchingInit = new int[n];
        int[] open = new int[n];
        int openCount = 0;
        for (int i = 0; i < n; i++) {
            matchingPdi[i] = -1;
            matchingInit[i] = -1;
            byte t = initial[i];
            if (t == LRI || t == RLI || t == FSI) {
                matchingPdi[i] = n;
                open[openCount++] = i;
            } else if (t == PDI && openCount > 0) {
                int o = open[--openCount];
                matchingPdi[o] = i;
                matchingInit[i] = o;
            } else if (t == B) {
                openCount = 0;
            }
        }
        // X1-X8: the directional status stack.
        byte[] types = new byte[n];
        System.arraycopy(initial, 0, types, 0, n);
        int[] stackLevel = new int[MAX_DEPTH + 2];
        byte[] stackOverride = new byte[MAX_DEPTH + 2];
        boolean[] stackIsolate = new boolean[MAX_DEPTH + 2];
        stackLevel[0] = para;
        stackOverride[0] = ON;
        int sp = 1;
        int overflowIsolates = 0;
        int overflowEmbeddings = 0;
        int validIsolates = 0;
        for (int i = 0; i < n; i++) {
            byte t = initial[i];
            int cur = stackLevel[sp - 1];
            if (t == RLE || t == LRE || t == RLO || t == LRO || t == RLI || t == LRI || t == FSI) {
                boolean isolate = t == RLI || t == LRI || t == FSI;
                boolean rtl;
                if (t == FSI) {
                    rtl = firstStrongRtl(initial, matchingPdi, i + 1, matchingPdi[i]);
                } else {
                    rtl = t == RLE || t == RLO || t == RLI;
                }
                levels[i] = (byte) cur;
                if (isolate && stackOverride[sp - 1] != ON) {
                    types[i] = stackOverride[sp - 1];
                }
                int next = rtl ? (cur + 1) | 1 : (cur + 2) & ~1;
                if (next <= MAX_DEPTH && overflowIsolates == 0 && overflowEmbeddings == 0) {
                    if (isolate) {
                        validIsolates++;
                    }
                    stackLevel[sp] = next;
                    stackOverride[sp] = t == LRO ? L : t == RLO ? R : ON;
                    stackIsolate[sp] = isolate;
                    sp++;
                } else if (isolate) {
                    overflowIsolates++;
                } else if (overflowIsolates == 0) {
                    overflowEmbeddings++;
                }
            } else if (t == PDI) {
                if (overflowIsolates > 0) {
                    overflowIsolates--;
                } else if (validIsolates > 0) {
                    overflowEmbeddings = 0;
                    while (!stackIsolate[sp - 1]) {
                        sp--;
                    }
                    sp--;
                    validIsolates--;
                }
                levels[i] = (byte) stackLevel[sp - 1];
                if (stackOverride[sp - 1] != ON) {
                    types[i] = stackOverride[sp - 1];
                }
            } else if (t == PDF) {
                if (overflowIsolates == 0) {
                    if (overflowEmbeddings > 0) {
                        overflowEmbeddings--;
                    } else if (!stackIsolate[sp - 1] && sp >= 2) {
                        sp--;
                    }
                }
                levels[i] = (byte) stackLevel[sp - 1];
            } else if (t == B) {
                levels[i] = para;
            } else {
                levels[i] = (byte) cur;
                if (t != BN && stackOverride[sp - 1] != ON) {
                    types[i] = stackOverride[sp - 1];
                }
            }
        }
        // X9/X10: level runs over the retained characters, chained into isolating run sequences.
        int[] kept = new int[n];
        int keptCount = 0;
        for (int i = 0; i < n; i++) {
            if (!removed(initial[i])) {
                kept[keptCount++] = i;
            }
        }
        int[] runStart = new int[keptCount + 1];
        int[] runOf = new int[n];
        int runCount = 0;
        for (int k = 0; k < keptCount; k++) {
            if (k == 0 || levels[kept[k]] != levels[kept[k - 1]]) {
                runStart[runCount++] = k;
            }
            runOf[kept[k]] = runCount - 1;
        }
        runStart[runCount] = keptCount;
        int[] seq = new int[keptCount];
        // sos/eos compare embedding levels, not the levels earlier sequences have already resolved (X10).
        byte[] embedding = levels.clone();
        for (int r = 0; r < runCount; r++) {
            int first = kept[runStart[r]];
            if (initial[first] == PDI && matchingInit[first] >= 0) {
                continue;
            }
            int len = 0;
            int run = r;
            while (true) {
                for (int k = runStart[run]; k < runStart[run + 1]; k++) {
                    seq[len++] = kept[k];
                }
                int last = seq[len - 1];
                byte lt = initial[last];
                if ((lt == LRI || lt == RLI || lt == FSI) && matchingPdi[last] < n) {
                    run = runOf[matchingPdi[last]];
                } else {
                    break;
                }
            }
            resolveSequence(text, seq, len, initial, types, levels, embedding, para, n);
        }
        // X9 removed characters take the level of their predecessor.
        for (int i = 0; i < n; i++) {
            if (removed(initial[i])) {
                levels[i] = i == 0 ? para : levels[i - 1];
            }
        }
        // L1: separators, and whitespace before them or at the end of the paragraph, reset to the base.
        boolean reset = true;
        for (int i = n - 1; i >= 0; i--) {
            byte t = initial[i];
            if (t == S || t == B) {
                levels[i] = para;
                reset = true;
            } else if (reset && (t == WS || t == LRI || t == RLI || t == FSI || t == PDI || removed(t))) {
                levels[i] = para;
            } else {
                reset = false;
            }
        }
        return levels;
    }

    private static boolean removed(byte t) {
        return t == RLE || t == LRE || t == RLO || t == LRO || t == PDF || t == BN;
    }

    // P2/P3 within an isolate: the first strong type between from and to, skipping nested isolates.
    private static boolean firstStrongRtl(byte[] initial, int[] matchingPdi, int from, int to) {
        for (int j = from; j < to && j < initial.length; j++) {
            byte t = initial[j];
            if (t == L) {
                return false;
            }
            if (t == R || t == AL) {
                return true;
            }
            if (t == LRI || t == RLI || t == FSI) {
                j = matchingPdi[j];
            } else if (t == B) {
                break;
            }
        }
        return false;
    }

    private static boolean isNi(byte t) {
        return t == ON || t == WS || t == S || t == B || t == LRI || t == RLI || t == FSI || t == PDI;
    }

    // Strong direction for N0/N1: numbers count as R; anything else is not strong.
    private static byte strongDir(byte t) {
        if (t == L) {
            return L;
        }
        if (t == R || t == AL || t == EN || t == AN) {
            return R;
        }
        return ON;
    }

    private static int openIndexOf(char c) {
        return OPEN.indexOf(c == 0x2329 ? (char) 0x3008 : c);
    }

    private static int closeIndexOf(char c) {
        return CLOSE.indexOf(c == 0x232A ? (char) 0x3009 : c);
    }

    private static void resolveSequence(String text, int[] seqIdx, int len, byte[] initial, byte[] types,
            byte[] levels, byte[] runLevels, byte para, int n) {
        int first = seqIdx[0];
        int last = seqIdx[len - 1];
        int level = levels[first];
        int prev = para;
        for (int i = first - 1; i >= 0; i--) {
            if (!removed(initial[i])) {
                prev = runLevels[i];
                break;
            }
        }
        int next = para;
        byte lt = initial[last];
        if (!(lt == LRI || lt == RLI || lt == FSI)) {
            for (int i = last + 1; i < n; i++) {
                if (!removed(initial[i])) {
                    next = runLevels[i];
                    break;
                }
            }
        }
        byte sos = (Math.max(prev, level) & 1) != 0 ? R : L;
        byte eos = (Math.max(next, level) & 1) != 0 ? R : L;
        byte[] t = new byte[len];
        for (int k = 0; k < len; k++) {
            t[k] = types[seqIdx[k]];
        }
        // W1
        for (int k = 0; k < len; k++) {
            if (t[k] == NSM) {
                if (k == 0) {
                    t[k] = sos;
                } else {
                    byte p = t[k - 1];
                    t[k] = (p == LRI || p == RLI || p == FSI || p == PDI) ? ON : p;
                }
            }
        }
        // W2, W3
        byte strong = sos;
        for (int k = 0; k < len; k++) {
            byte c = t[k];
            if (c == L || c == R || c == AL) {
                strong = c;
            } else if (c == EN && strong == AL) {
                t[k] = AN;
            }
        }
        for (int k = 0; k < len; k++) {
            if (t[k] == AL) {
                t[k] = R;
            }
        }
        // W4
        for (int k = 1; k < len - 1; k++) {
            if (t[k] == ES && t[k - 1] == EN && t[k + 1] == EN) {
                t[k] = EN;
            } else if (t[k] == CS && t[k - 1] == t[k + 1] && (t[k - 1] == EN || t[k - 1] == AN)) {
                t[k] = t[k - 1];
            }
        }
        // W5
        for (int k = 0; k < len; k++) {
            if (t[k] == ET) {
                int s = k;
                while (k < len && t[k] == ET) {
                    k++;
                }
                if ((s > 0 && t[s - 1] == EN) || (k < len && t[k] == EN)) {
                    for (int j = s; j < k; j++) {
                        t[j] = EN;
                    }
                }
                k--;
            }
        }
        // W6
        for (int k = 0; k < len; k++) {
            if (t[k] == ES || t[k] == ET || t[k] == CS) {
                t[k] = ON;
            }
        }
        // W7
        strong = sos;
        for (int k = 0; k < len; k++) {
            if (t[k] == L || t[k] == R) {
                strong = t[k];
            } else if (t[k] == EN && strong == L) {
                t[k] = L;
            }
        }
        byte e = (level & 1) != 0 ? R : L;
        // N0: BD16 bracket pairs, processed in order of their opening bracket.
        int[] stackChar = new int[MAX_BRACKETS];
        int[] stackPos = new int[MAX_BRACKETS];
        int depth = 0;
        int[] pairOpen = new int[len];
        int[] pairClose = new int[len];
        int pairs = 0;
        for (int k = 0; k < len; k++) {
            if (t[k] != ON) {
                continue;
            }
            char c = text.charAt(seqIdx[k]);
            int o = openIndexOf(c);
            if (o >= 0) {
                if (depth == MAX_BRACKETS) {
                    break;
                }
                stackChar[depth] = o;
                stackPos[depth++] = k;
                continue;
            }
            int cl = closeIndexOf(c);
            if (cl >= 0) {
                for (int d = depth - 1; d >= 0; d--) {
                    if (stackChar[d] == cl) {
                        pairOpen[pairs] = stackPos[d];
                        pairClose[pairs++] = k;
                        depth = d;
                        break;
                    }
                }
            }
        }
        for (int a = 1; a < pairs; a++) {
            int po = pairOpen[a];
            int pc = pairClose[a];
            int b = a - 1;
            while (b >= 0 && pairOpen[b] > po) {
                pairOpen[b + 1] = pairOpen[b];
                pairClose[b + 1] = pairClose[b];
                b--;
            }
            pairOpen[b + 1] = po;
            pairClose[b + 1] = pc;
        }
        for (int p = 0; p < pairs; p++) {
            int o = pairOpen[p];
            int c = pairClose[p];
            boolean embedding = false;
            boolean opposite = false;
            for (int k = o + 1; k < c; k++) {
                byte d = strongDir(t[k]);
                if (d == e) {
                    embedding = true;
                    break;
                }
                if (d != ON) {
                    opposite = true;
                }
            }
            byte resolved = ON;
            if (embedding) {
                resolved = e;
            } else if (opposite) {
                byte before = sos;
                for (int k = o - 1; k >= 0; k--) {
                    byte d = strongDir(t[k]);
                    if (d != ON) {
                        before = d;
                        break;
                    }
                }
                resolved = before;
            }
            if (resolved != ON) {
                t[o] = resolved;
                t[c] = resolved;
                for (int k = o + 1; k < len && initial[seqIdx[k]] == NSM; k++) {
                    t[k] = resolved;
                }
                for (int k = c + 1; k < len && initial[seqIdx[k]] == NSM; k++) {
                    t[k] = resolved;
                }
            }
        }
        // N1/N2
        for (int k = 0; k < len; k++) {
            if (isNi(t[k])) {
                int s = k;
                while (k < len && isNi(t[k])) {
                    k++;
                }
                byte before = s == 0 ? sos : strongDir(t[s - 1]);
                byte after = k >= len ? eos : strongDir(t[k]);
                byte resolved = before == after ? before : e;
                for (int j = s; j < k; j++) {
                    t[j] = resolved;
                }
                k--;
            }
        }
        // I1/I2
        for (int k = 0; k < len; k++) {
            byte c = t[k];
            int lv = level;
            if ((level & 1) == 0) {
                if (c == R) {
                    lv++;
                } else if (c == AN || c == EN) {
                    lv += 2;
                }
            } else if (c == L || c == EN || c == AN) {
                lv++;
            }
            levels[seqIdx[k]] = (byte) lv;
        }
    }

    /// True if the line, laid out with the given base direction, is purely left-to-right (no reordering
    /// needed). This lets the editor keep its fast LTR path for the common case.
    static boolean isTrivialLtr(String text, boolean baseRtl) {
        if (baseRtl) {
            return text.length() == 0;
        }
        for (int i = 0; i < text.length(); i++) {
            byte t = typeAt(text, i);
            if (t == R || t == AL || t == AN || (t >= LRE && t <= PDI)) {
                return false;
            }
        }
        return true;
    }
  // END UAX9
}
