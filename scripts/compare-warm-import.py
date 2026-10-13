#!/usr/bin/env python3
"""Compare warmed in-memory DOCX import plus text extraction; no synthetic font metrics."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=Path,help='Pinned checkout with release CLI already built')
p.add_argument('fixture',type=Path)
p.add_argument('--out',type=Path,required=True,help='New output directory')
p.add_argument('--rounds',type=int,default=3)
p.add_argument('--warmup',type=int,default=50)
p.add_argument('--iterations',type=int,default=100)
a=p.parse_args()
if not 1<=a.rounds<=20 or not 1<=a.iterations<=10000 or not 0<=a.warmup<=1000:p.error('invalid sample counts')
a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve()
root=Path(__file__).resolve().parent.parent
libs=a.wordcraft.resolve()/'target/release/deps'
command=['rustc','--edition=2024','-C','opt-level=3','-C','lto=thin','-C','codegen-units=16','-L','dependency='+str(libs)]
linked=[]
for name in ['wordcraft_docx','wordcraft_doc']:
    candidates=list(libs.glob('lib'+name+'-*.rlib'))
    if len(candidates)!=1:raise SystemExit('Expected one release rlib for '+name+'; resolve stale artifacts first')
    command+=['--extern',name+'='+str(candidates[0])]
    linked.append({'file':candidates[0].name,'sha256':hashlib.sha256(candidates[0].read_bytes()).hexdigest()})
command += [str(root/'benchmarks/wordcraft-import-driver.rs'),'-o',str(a.out/'wordcraft-import-driver')]
subprocess.run(command,check=True)
results={'cn1':[],'wordcraft':[]};expected=None
for round in range(a.rounds):
    for side in (['cn1','wordcraft'] if round%2==0 else ['wordcraft','cn1']):
        output=a.out/(side+'-'+str(round)+'.txt')
        cmd=[str(root/'scripts/benchmark-import.sh') if side=='cn1' else str(a.out/'wordcraft-import-driver'),str(a.fixture.resolve()),str(a.warmup),str(a.iterations),str(output)]
        run=subprocess.run(cmd,check=True,capture_output=True,text=True,timeout=180)
        results[side].extend(json.loads(run.stdout)['samplesMs'])
        actual=output.read_bytes()
        if expected is None:expected=actual
        if actual!=expected:raise SystemExit('Extraction outputs differ; timing comparison rejected')
def stats(values):
    return {'medianMs':statistics.median(values),'p95Ms':sorted(values)[math.ceil(.95*len(values))-1],'samplesMs':values}
report={'schema':1,'operation':'warmed in-memory DOCX parse plus plain-text extraction',
        'rounds':a.rounds,'warmupPerProcess':a.warmup,'samplesPerProcess':a.iterations,
        'cn1':stats(results['cn1']),'wordcraft':stats(results['wordcraft']),
        'outputsEqual':True,'textSha256':hashlib.sha256(expected).hexdigest(),
        'fixtureSha256':hashlib.sha256(a.fixture.read_bytes()).hexdigest(),
        'wordcraftRevision':subprocess.check_output(['git','-C',str(a.wordcraft),'rev-parse','HEAD'],text=True).strip(),
        'wordcraftLibraries':linked,'rustAdapterOptimization':'opt-level=3, lto=thin, codegen-units=16; links existing release dependency artifacts',
        'javaHeap':'-Xms256m -Xmx1g; normal GC; no forced collections',
        'startupMeasured':False,'fileIoMeasured':False,'fontLayoutMeasured':False,'typingMeasured':False,'memoryMeasured':False,
        'limitations':['One simple textual DOCX, no complex-format fidelity benchmark. Importers support different total feature scopes.',
                      'Normal runtime memory management applies: Rust drops documents in each operation; Java reclaims unreachable objects through GC.',
                      'Wall-clock samples exclude process startup, reading the input file, and writing/checking extracted output.'],
        'overallPerformanceGate':'not-evaluated'}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:stats(v) for k,v in results.items()},indent=2))
