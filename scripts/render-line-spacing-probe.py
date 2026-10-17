#!/usr/bin/env python3
"""Compare line advance in independently rendered fixed-spacing PDF fixtures.
This does not certify font/glyph, baseline-position or general rendered parity.
"""
import argparse, hashlib, json, subprocess, zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve();cn=Path(__file__).resolve().parent/'wordprocessor';report={'boundary':__doc__,'checks':[]}
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=120)
 with (a.out/'commands.log').open('a') as log:log.write(json.dumps(list(map(str,args)))+'\n'+r.stdout+r.stderr+'\n')
 r.check_returncode()
 return r.stdout
run([cn,'new',a.out/'empty.cn1doc'])
for rule,value in [('exact',24),('atLeast',24),('exact',6)]:
 name=rule+'-'+str(value);source=a.out/(name+'.docx')
 steps=[('text.insert',{'text':'Sample line\nSample line\nSample line'}),('selection.set',{'block':0,'start':0,'endBlock':2,'end':11}),('paragraph.set',{'property':'before','value':'0'}),('paragraph.set',{'property':'after','value':'0'}),('paragraph.spacing',{'rule':rule,'value':value})]
 macro=a.out/(name+'.json');macro.write_text(json.dumps({'version':1,'steps':[{'command':n,'args':v} for n,v in steps]}));run([cn,'run',a.out/'empty.cn1doc',macro,source])
 results={}
 for side,tool in [('cn1',cn),('wordcraft',a.wordcraft.resolve())]:
  pdf=a.out/(name+'-'+side+'.pdf');png=pdf.with_suffix('.png');run([tool,'convert',source,pdf]);run(['pdftoppm','-r','144','-f','1','-singlefile','-png',pdf,png.with_suffix('')]);(pdf.with_suffix('.fonts.txt')).write_text(run(['pdffonts',pdf]))
  im=Image.open(png).convert('RGB');occupied=[]
  for y in range(im.height):
   if any(min(im.getpixel((x,y)))<180 for x in range(0,min(600,im.width))):occupied.append(y)
  bands=[]
  for y in occupied:
   if not bands or y>bands[-1][1]+1:bands.append([y,y])
   else:bands[-1][1]=y
  results[side]={'inkBands':bands,'advances':[bands[i+1][0]-bands[i][0] for i in range(len(bands)-1)],'sha256':hashlib.sha256(pdf.read_bytes()).hexdigest()}
 # 144 dpi: one point is two pixels. A 1px raster rounding tolerance applies.
 passed=all(len(r['inkBands'])==3 and all(abs(d-value*2)<=1 for d in r['advances']) for r in results.values())
 if value==6:
  with zipfile.ZipFile(source) as z:
   ns='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';margin=ET.fromstring(z.read('word/document.xml')).find('.//'+ns+'pgMar');top=int(margin.get(ns+'top'))/10
  report['checks'].append({'case':name,'lineAdvanceMatches':None,'reason':'Tight lines touch; raster bands cannot measure individual advances.','expectedVerticalBounds':[top,top+3*value*2], 'cn1ClipsToLineArea':all(b[0]>=top and b[1]<top+3*value*2 for b in results['cn1']['inkBands']), 'wordcraftClipsToLineArea':all(b[0]>=top and b[1]<top+3*value*2 for b in results['wordcraft']['inkBands']),**results})
 else:report['checks'].append({'case':name,'lineAdvanceMatches':passed,**results})
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
raise SystemExit(0 if all(c['lineAdvanceMatches'] is not False for c in report['checks']) and report['checks'][-1]['cn1ClipsToLineArea'] else 1)
