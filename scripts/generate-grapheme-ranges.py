#!/usr/bin/env python3
"""Generate Unicode 16 default extended-grapheme properties from pinned local data."""
from pathlib import Path
import hashlib, json
root = Path(__file__).resolve().parent.parent
data = root / 'third-party/unicode-16.0.0'
for name, entry in json.loads((data / 'manifest.json').read_text()).items():
    assert hashlib.sha256((data / name).read_bytes()).hexdigest() == entry['sha256'], name
names = ['Other','CR','LF','Control','Extend','ZWJ','Regional_Indicator','Prepend','SpacingMark','L','V','T','LV','LVT']
def rows(file):
    for line in (data / file).read_text().splitlines():
        line = line.split('#')[0].strip()
        if not line: continue
        parts = [p.strip() for p in line.split(';')]
        bounds = [int(x,16) for x in parts[0].split('..')]
        yield bounds[0], bounds[-1], parts[1:]
def table(rows):
    merged = []
    for a,b,v in sorted(rows):
        if merged and merged[-1][1]+1 == a and merged[-1][2] == v: merged[-1][1] = b
        else: merged.append([a,b,v])
    values = [hex(v) for row in merged for v in row]
    return ',\n'.join('    '+', '.join(values[i:i+12]) for i in range(0,len(values),12))
gcb = table((a,b,names.index(p[0])) for a,b,p in rows('GraphemeBreakProperty.txt') if p[0] not in ['LV','LVT'])
ep = table((a,b,1) for a,b,p in rows('emoji-data.txt') if p[0]=='Extended_Pictographic')
incb = table((a,b,['None','Consonant','Extend','Linker'].index(p[1])) for a,b,p in rows('DerivedCoreProperties.txt') if p[0]=='InCB')
source = """package com.codenameone.wordprocessor;

/** Generated Unicode 16.0.0 properties. See scripts/generate-grapheme-ranges.py
 * and third-party/unicode-16.0.0/LICENSE.txt. Do not edit these ranges manually. */
final class GraphemeProperties {
  private GraphemeProperties() {}
"""
source += '  static final int '+', '.join(n.upper()+'='+str(i) for i,n in enumerate(names))+';\n'
for name, values in [('BREAKS',gcb),('PICTOGRAPHS',ep),('CONJUNCTS',incb)]:
    source += '  private static final int[] '+name+' = {\n'+values+'\n  };\n'
source += """
  static int breaks(int cp) {
    if (cp >= 0xac00 && cp <= 0xd7a3) return (cp - 0xac00) % 28 == 0 ? LV : LVT;
    return lookup(BREAKS, cp);
  }
  static boolean pictograph(int cp) { return lookup(PICTOGRAPHS, cp) != 0; }
  static int conjunct(int cp) { return lookup(CONJUNCTS, cp); }
  private static int lookup(int[] ranges, int cp) {
    int low = 0, high = ranges.length / 3 - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1, i = mid * 3;
      if (cp < ranges[i]) high = mid - 1;
      else if (cp > ranges[i + 1]) low = mid + 1;
      else return ranges[i + 2];
    }
    return 0;
  }
}
"""
(root/'common/src/main/java/com/codenameone/wordprocessor/GraphemeProperties.java').write_text(source)
print('Generated grapheme properties:',len(source),'bytes')
