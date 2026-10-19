#!/usr/bin/env python3
"""Fresh native GUI processes, kernel RSS, and completed-document CPU paint markers. Final numbers come from the metrics workflow."""
import argparse,ctypes,hashlib,json,os,platform,signal,statistics,subprocess,time,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
class TaskInfo(ctypes.Structure):
    _fields_=[(n,ctypes.c_uint64) for n in ['virtual','resident','user','system','threads_user','threads_system']]+[(n,ctypes.c_int32) for n in ['policy','faults','pageins','cow','sent','received','mach','unix','csw','threads','running','priority']]
libproc=ctypes.CDLL('/usr/lib/libproc.dylib')
libproc.proc_pidinfo.argtypes=[ctypes.c_int,ctypes.c_int,ctypes.c_uint64,ctypes.c_void_p,ctypes.c_int]
def rss(pid):
    task=TaskInfo(); n=libproc.proc_pidinfo(pid,4,0,ctypes.byref(task),ctypes.sizeof(task))
    return task.resident if n==ctypes.sizeof(task) else None
def quantiles(values):
    if not values:return None
    s=sorted(values);return {'min':s[0],'median':statistics.median(s),'p95':s[min(len(s)-1,int((len(s)-1)*.95+.999999))],'max':s[-1],'samples':len(s)}
def plain(path):
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None
        body=ET.fromstring(z.read('word/document.xml')).find('{*}body')
        return '\n'.join(''.join(t.text or '' for t in p.findall('.//{*}t')) for p in body.findall('{*}p'))
def run(side,exe,fixture,root,workload):
    root.mkdir(parents=True,exist_ok=False)
    env=os.environ.copy();env.update(NATIVE_BENCH_INPUT=str(fixture),NATIVE_BENCH_OUT=str(root),WORDCRAFT_NO_PREFS='1')
    if workload:env['NATIVE_BENCH_WORKLOAD']='1'
    else:env.pop('NATIVE_BENCH_WORKLOAD',None)
    if side=='cn1':
        # The CN1 app has no environment access; it reads this request file (see prepare.py),
        # at the root of the instrumented checkout that contains the executable.
        home=next(d for d in Path(exe).resolve().parents if (d/'common').is_dir() and (d/'pom.xml').is_file())
        def url(p):return 'file://'+Path(p).resolve().as_posix()
        (home/'native-bench.json').write_text(json.dumps({'input':url(fixture),'out':url(root),'workload':bool(workload)}))
    args=[str(exe)]+([str(fixture)] if side=='wordcraft' else [])
    with (root/'process.log').open('wb',buffering=0) as log:
        start=time.monotonic(); wall=time.time(); p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT,env=env)
        samples=[];events=[];cursor=0;ready=None;done=None;error=None;status=None;usage=None
        deadline=start+(180 if workload else 40)
        while time.monotonic()<deadline:
            now=time.monotonic();r=rss(p.pid)
            if r:samples.append([now-start,r])
            with (root/'process.log').open('rb') as data:
                data.seek(cursor);raw=data.read();lines=raw.split(b'\n');cursor+=sum(len(x)+1 for x in lines[:-1])
            for line in lines[:-1]:
                if b'NATIVE_BENCH ' not in line:continue
                try:e=json.loads(line.split(b'NATIVE_BENCH ',1)[1]);e['observedSeconds']=now-start;events.append(e)
                except Exception:continue
                if e['event']=='ready' and ready is None:ready=now
                if e['event']=='done':done=now
                if e['event']=='error':error=e
            pid,status0,ru=os.wait4(p.pid,os.WNOHANG)
            if pid:status=status0;usage=ru;break
            if error or (done and now-done>2) or (not workload and ready and now-ready>5):break
            time.sleep(.01)
        if usage is None:
            # Only this harness's child, with opt-in disposable document/output state.
            p.send_signal(signal.SIGTERM);_,status,usage=os.wait4(p.pid,0)
        p.returncode=os.waitstatus_to_exitcode(status)
    ready_event=next((e for e in events if e['event']=='ready'),None)
    # Wall clocks align the CPU paint marker to launch; ±1 ms CN1 marker resolution.
    ready_clock=start+(ready_event['wallMs']/1000-wall) if ready_event else None
    idle=[r for t,r in samples if ready is not None and ready-start+.5<=t<=ready-start+4.9]
    operations={};paints={}
    for i,e in enumerate(events):
        if e['event']=='operation':operations.setdefault(e['operation'],[]).append(e['ms'])
        if e['event']=='paint':
            paints.setdefault(e['operation'],[]).append(e['ms'])
    validated=[]
    for f in root.glob('saved-*.docx'):
        assert plain(f)==plain(fixture), f'Text mismatch: {f}'
        validated.append({'file':f.name,'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()})
    result={'side':side,'fixture':fixture.name,'workload':workload,'exitCode':p.returncode,'ready':bool(ready),'completed':bool(done) if workload else bool(ready),'error':error,
        'documentReadyCpuMs':(ready_clock-start)*1000 if ready_clock else None,
        'readyDetails':ready_event,'rssBytes':{'idle':quantiles(idle),'sampledWholeRun':quantiles([r for _,r in samples]),'kernelPeak':usage.ru_maxrss},
        'cpuSeconds':usage.ru_utime+usage.ru_stime,'wallSeconds':time.monotonic()-start,'operationsMs':{k:quantiles(v) for k,v in operations.items()},
        'commandToCpuPaintMs':{k:quantiles(v) for k,v in paints.items()},
        'validatedDocx':validated,'events':events,'rssSamples':samples,'startHostClock':start,'startWallClock':wall}
    (root/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(side,fixture.name,'ready',bool(ready),'done',bool(done),'CPU ready ms',round(result['documentReadyCpuMs'] or 0,2),'peak MiB',round(usage.ru_maxrss/1048576,2),'error',error,flush=True)
    return result
if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('--cn1',type=Path,required=True);a.add_argument('--wordcraft',type=Path,required=True);a.add_argument('--fixture',type=Path,required=True);a.add_argument('--out',type=Path,required=True);a.add_argument('--runs',type=int,default=1);a.add_argument('--workload',action='store_true');a.add_argument('--side',choices=['cn1','wordcraft','both'],default='both');v=a.parse_args()
    results=[]
    for i in range(v.runs):
        order=['cn1','wordcraft'] if i%2==0 else ['wordcraft','cn1']
        for side in order:
            if v.side!='both' and side!=v.side:continue
            results.append(run(side,getattr(v,side).resolve(),v.fixture.resolve(),v.out.resolve()/f'{i:02d}-{side}',v.workload))
    v.out.mkdir(parents=True,exist_ok=True);(v.out/'results.json').write_text(json.dumps({'machine':platform.platform(),'loadAverage':os.getloadavg(),'results':results},indent=2)+'\n')
    if any(not r['completed'] or r['error'] for r in results):raise SystemExit(1)
