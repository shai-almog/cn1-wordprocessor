#!/usr/bin/env python3
"""Summarize local native GUI measurements; CPU paint is not screen presentation."""
import argparse,json,statistics,math,hashlib,zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base',type=Path,default=root/'artifacts/native-bench/final')
parser.add_argument('--out',type=Path,default=root/'artifacts/native-benchmarks.md')
parser.add_argument('--json',type=Path,default=root/'docs/native-benchmarks.json')
args=parser.parse_args()
base=args.base.resolve()
art=json.loads((base/'artifacts.json').read_text())
sm=root/'artifacts/source-metrics.json'
metrics=json.loads(sm.read_text()) if sm.exists() else None
osname=art.get('os','macos')
OSNAME={'macos':'macOS','linux':'Linux','windows':'Windows'}[osname]
def q(v):
    v=sorted(v)
    return {'min':v[0],'median':statistics.median(v),'p95':v[math.ceil((len(v)-1)*.95)],'max':v[-1],'n':len(v)}
def fmt(v):return f'{v:,.2f}'
fixtures={}
for count,rel in [(1,'artifacts/startup-final/1-paragraphs.docx'),(1000,'artifacts/1000-paragraphs.docx'),(10000,'artifacts/startup-final/10000-paragraphs.docx')]:
    p=root/rel
    with zipfile.ZipFile(p) as z:
        body=ET.fromstring(z.read('word/document.xml')).find('{*}body')
        paragraphs=body.findall('{*}p')
        text='\n'.join(''.join(t.text or '' for t in p.findall('.//{*}t')) for p in paragraphs)
    fixtures[str(count)]={'path':rel,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'paragraphs':len(paragraphs),'characters':len(text)}
summary={'fixtures':fixtures,'artifacts':art,'startup':{},'workload':{},'source':{k:metrics[k]['applicationTotal'] for k in ['cn1Java','wordcraftRust']} if metrics else None}
lines=[f'# Native GUI benchmarks: {OSNAME}','', f"Native {art['machine'].split()[0]} release builds of both applications on {OSNAME}, same machine, same fixtures.",'', '## Startup and idle memory','', 'Ten fresh processes per application/document, alternating launch order, warm filesystem cache. Each process stays open for five seconds after the first completed CPU document-paint marker. The marker includes loading, layout and issuing document draw commands, but excludes GPU completion and display presentation. Neither true cold-boot startup nor input-to-photon latency is measured.','', '| Paragraphs | App | CPU-ready median / p95 (ms) | Idle RSS min / median / max (MiB) | Highest kernel RSS peak (MiB) | Successful launches |','| ---: | --- | ---: | ---: | ---: | ---: |']
for count in [1,1000,10000]:
    data=json.loads((base/f'startup-{count}/results.json').read_text())['results']
    summary['startup'][str(count)]={}
    for side in ['cn1','wordcraft']:
        rows=[r for r in data if r['side']==side]
        good=[r for r in rows if r['completed'] and not r['error']]
        idle=[r for row in good for t,r in row['rssSamples'] if .5<=t-next(e['observedSeconds'] for e in row['events'] if e['event']=='ready')<=4.9]
        result={'launches':len(rows),'successes':len(good),'cpuReadyMs':q([r['documentReadyCpuMs'] for r in good]),'idleRssBytes':q(idle),'kernelPeakBytes':q([r['rssBytes']['kernelPeak'] for r in good]),'sampledWholeRunMinBytes':min(r['rssBytes']['sampledWholeRun']['min'] for r in good),'pages':[r['readyDetails']['pages'] for r in good]}
        summary['startup'][str(count)][side]=result
        a=result['cpuReadyMs'];b=result['idleRssBytes']
        lines.append(f"| {count:,} | {side} | {fmt(a['median'])} / {fmt(a['p95'])} | {fmt(b['min']/2**20)} / {fmt(b['median']/2**20)} / {fmt(b['max']/2**20)} | {fmt(result['kernelPeakBytes']['max']/2**20)} | {len(good)}/{len(rows)} |")
discarded={c:len(list((base/f'startup-{c}/failed').glob('*'))) if (base/f'startup-{c}/failed').is_dir() else 0 for c in [1,1000,10000]}
lines+=['',f"Launches are collected one at a time and each must show a visible window. Codename One does not paint a window that is hidden or fully covered, so such a launch never reaches ready; those attempts were discarded and retried, never counted as fast results. Discarded CN1 attempts (kept under each `failed/` directory): {', '.join(f'{v} at {k:,}' for k,v in discarded.items())} paragraphs."]
lines+=['','RSS sampled every ~10 ms; idle samples start 0.5 seconds after the observed ready event. Kernel peak comes from wait4/ru_maxrss (Windows: PeakWorkingSetSize) and covers the whole process lifetime. The raw process minimum includes startup before the app is usable and is reported in JSON separately. All values are resident memory, not virtual address-space reservations.','', '## Editing and file operations','', 'Three fresh process runs per app on the same 1,000-paragraph DOCX. Each performs 50 insert/undo pairs at the start, a 10,000-character paste/undo, five DOCX saves, five reopens and three PDF exports. Autosave is disabled in both instrumentation copies; normal production recovery is isolated. Each saved DOCX is CRC checked and its paragraph text compared with the input. Undo must restore the original text.','', '| Operation | CN1 operation median / p95 (ms) | Wordcraft operation median / p95 (ms) | CN1 command-to-CPU-paint median / p95 (ms) | Wordcraft command-to-CPU-paint median / p95 (ms) |','| --- | ---: | ---: | ---: | ---: |']
data=json.loads((base/'workload-1000/results.json').read_text())['results']
for side in ['cn1','wordcraft']:
    rows=[r for r in data if r['side']==side]
    result={'successes':sum(bool(r['completed'] and not r['error']) for r in rows),'runs':len(rows),'cpuSeconds':q([r['cpuSeconds'] for r in rows]),'wallSeconds':q([r['wallSeconds'] for r in rows]),'kernelPeakBytes':q([r['rssBytes']['kernelPeak'] for r in rows]),'sampledWholeRunMinBytes':min(r['rssBytes']['sampledWholeRun']['min'] for r in rows),'validatedDocx':sum(len(r['validatedDocx']) for r in rows),'operations':{},'commandToCpuPaint':{}}
    for event,key in [('operation','operations'),('paint','commandToCpuPaint')]:
        names=sorted({e['operation'] for r in rows for e in r['events'] if e['event']==event})
        for name in names:result[key][name]=q([e['ms'] for r in rows for e in r['events'] if e['event']==event and e['operation']==name])
    summary['workload'][side]=result
for name in summary['workload']['cn1']['operations']:
    cells=[]
    for key in ['operations','commandToCpuPaint']:
        for side in ['cn1','wordcraft']:
            a=summary['workload'][side][key][name];cells.append(fmt(a['median'])+' / '+fmt(a['p95']))
    lines.append('| '+name+' | '+' | '.join(cells)+' |')
lines+=['','| Whole scripted process | CN1 | Wordcraft |','| --- | ---: | ---: |']
for label,key,scale in [('Maximum kernel RSS peak (MiB)','kernelPeakBytes',2**20),('Median CPU seconds','cpuSeconds',1),('Median elapsed seconds','wallSeconds',1)]:
    stat='max' if key=='kernelPeakBytes' else 'median'
    lines.append('| '+label+' | '+' | '.join(fmt(summary['workload'][s][key][stat]/scale) for s in ['cn1','wordcraft'])+' |')
lines+=['','Whole-process CPU/wall includes the five-second idle period, operation pacing and two seconds after completion. CN1 uses a 25 ms timer; Wordcraft requests repaint checks at 20 ms during the scripted workload. These harness overheads differ, so CPU/wall are diagnostic rather than an isolated engine comparison. Operation timings use each app’s normal command/model path; command-to-paint includes that app’s resulting UI refresh.','', 'DOCX validation checks plain text, not complete formatting equivalence. CN1 PDF currently has a much smaller typography/feature scope. Page counts and font metrics differ. Export timings therefore do not establish equal-fidelity throughput.','', '## Production size and source','', '| Metric | CN1 + Java | Wordcraft Rust |','| --- | ---: | ---: |']
for label,key in [('Native executable bytes','executableBytes'),('Unsigned app bundle bytes','appBundleBytes')]:lines.append(f"| {label} | {art['cn1'][key]:,} | {art['wordcraft'][key]:,} |")
for label,key in ([] if not metrics else [('Application code lines (explicit tests pruned)','testPrunedCodeLines'),('Application source tokens (o200k_base, explicit tests pruned)','testPrunedTokens')]):lines.append(f"| {label} | {summary['source']['cn1Java'][key]:,} | {summary['source']['wordcraftRust'][key]:,} |")
lines+=['','Sizes use uninstrumented release executables. On macOS the CN1 bundle includes resources and bundled OFL fonts and the Wordcraft bundle uses its upstream packaging recipe without signing/notarization or optional craft-fonts; on Linux/Windows each app ships as its single executable. These are logical byte totals, not compressed download or allocated disk sizes. Framework/dependency code, fonts and generated output are excluded from application LOC/tokens. Source counts are not a matched-feature comparison.','', '## Environment and evidence','',f"{art['machine']}, {art['cpuCount']} logical CPUs, {art['ramBytes']//2**30} GiB RAM, {art['platform']}. Maven/ParparVM release versus Cargo release. Wordcraft revision `{art['wordcraftRevision']}`; CN1 framework `{art['frameworkRevision']}`.",'', f'Measured on {OSNAME} only; each desktop OS has its own report.','', f'Reproduce with `scripts/native-bench/README.md`. Raw per-process events, RSS samples, output files, fixture paths and hashes are under `{base.relative_to(root)}/`. The [summary]({args.json.name}) includes distributions and production executable hashes. Instrumentation is confined to disposable copies; application source remains uninstrumented.']
args.out.write_text('\n'.join(lines)+'\n')
args.json.write_text(json.dumps(summary,indent=2)+'\n')
