#!/usr/bin/env python3
"""Independently export and raster-check the paired exact-height table fixture.

Requires Poppler (pdftoppm) and Pillow. This is one geometry fixture, not a
rendered-equivalence suite or typography/performance benchmark.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from PIL import Image
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=Path)
p.add_argument('paired',type=Path,help='Output directory from table-row-probes.py')
p.add_argument('--out',type=Path,required=True)
a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve()
cn=Path(__file__).resolve().parent/'wordprocessor';report={}
for side,tool in [('cn1',cn),('wordcraft',a.wordcraft.resolve())]:
    source=a.paired.resolve()/('height-exact-'+side+'.docx');pdf=a.out/(side+'.pdf');png=a.out/(side+'.png')
    for cmd in [[tool,'convert',source,pdf],['pdftoppm','-f','1','-singlefile','-scale-to','1200','-png',pdf,png.with_suffix('')]]:
        result=subprocess.run(list(map(str,cmd)),capture_output=True,text=True,timeout=120)
        with (a.out/'commands.log').open('a') as log:log.write(json.dumps(list(map(str,cmd)))+'\n'+result.stdout+result.stderr+'\n')
        result.check_returncode()
    im=Image.open(png).convert('RGB');w,h=im.size;rows=[]
    for y in range(h):
        xs=[x for x in range(w) if min(im.getpixel((x,y)))<235]
        if len(xs)>200:rows.append({'y':y,'left':min(xs),'right':max(xs)})
    report[side]={'imageSize':[w,h],'horizontalBorders':rows,'pdfSha256':hashlib.sha256(pdf.read_bytes()).hexdigest()}
x,y=report['cn1']['horizontalBorders'],report['wordcraft']['horizontalBorders']
report['passed']=len(x)==len(y)==3 and all(a['left']==b['left'] and a['right']==b['right'] and abs(a['y']-b['y'])<=2 for a,b in zip(x,y))
report['boundary']=__doc__+' Horizontal borders must match; vertical differences up to two pixels at 1200px page height are accepted.'
(a.out/'render-check.json').write_text(json.dumps(report,indent=2)+'\n')
clipping={}
for side in ['cn1','wordcraft']:
    im=Image.open(a.out/(side+'.png')).convert('RGB');b=report[side]['horizontalBorders'][-1]
    clipping[side]={'inkBelowTable':sum(min(im.getpixel((x,y)))<180 for y in range(b['y']+2,b['y']+45) for x in range(b['left'],b['right']+1)), 'rowHeightsPixels':[report[side]['horizontalBorders'][i+1]['y']-report[side]['horizontalBorders'][i]['y'] for i in range(2)]}
clipping['boundary']='Single exact-height fixture. Overflow ink is measured below the table; this does not change the strict border comparison result.'
clipping['cn1OverflowClipped']=clipping['cn1']['inkBelowTable']==0
clipping['rowHeightsMatch']=clipping['cn1']['rowHeightsPixels']==clipping['wordcraft']['rowHeightsPixels']
(a.out/'clipping-check.json').write_text(json.dumps(clipping,indent=2)+'\n')
print(json.dumps(report,indent=2));raise SystemExit(0 if report['passed'] else 1)
