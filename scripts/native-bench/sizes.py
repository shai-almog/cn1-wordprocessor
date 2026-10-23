#!/usr/bin/env python3
"""Production executable/bundle sizes and machine facts for report.py.
Usage: sizes.py OS CN1_EXE WORDCRAFT_EXE WORDCRAFT_CHECKOUT"""
import glob, hashlib, json, os, platform, subprocess, sys
osname, cn1_exe, wc_exe, wc_root = sys.argv[1:5]
def tree(path):
    return sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(path) for f in fs
               if not os.path.islink(os.path.join(r, f)))
def side(exe, bundle):
    return {'executableBytes': os.path.getsize(exe), 'appBundleBytes': tree(bundle),
            'sha256': hashlib.sha256(open(exe, 'rb').read()).hexdigest()}
if osname == 'macos':
    cn1_bundle = os.path.dirname(os.path.dirname(os.path.dirname(cn1_exe)))
    wc_bundle = (glob.glob(f'{wc_root}/target/*/WordCraft.app') + glob.glob(f'{wc_root}/*.app') + [os.path.dirname(wc_exe)])[0]
else:
    # Both ship a single self-contained executable here (dist.sh packages only CN1's Main, not
    # the CMake build tree beside it).
    cn1_bundle, wc_bundle = None, None
def ram():
    if osname == 'macos': return int(subprocess.check_output(['sysctl', '-n', 'hw.memsize']))
    if osname == 'linux': return os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES')
    import ctypes
    class M(ctypes.Structure):
        _fields_ = [('len', ctypes.c_ulong), ('load', ctypes.c_ulong), ('total', ctypes.c_ulonglong)] + [(f'f{i}', ctypes.c_ulonglong) for i in range(6)]
    m = M(); m.len = ctypes.sizeof(M); ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m)); return m.total
wc = side(wc_exe, wc_bundle) if wc_bundle else {**side(wc_exe, os.path.dirname(wc_exe)), 'appBundleBytes': os.path.getsize(wc_exe)}
cn1 = side(cn1_exe, cn1_bundle) if cn1_bundle else {**side(cn1_exe, os.path.dirname(cn1_exe)), 'appBundleBytes': os.path.getsize(cn1_exe)}
json.dump({'os': osname, 'cn1': cn1, 'wordcraft': wc,
           'platform': platform.platform(), 'machine': f"{platform.machine()} GitHub {os.environ.get('ImageOS', osname)} runner",
           'cpuCount': os.cpu_count(), 'ramBytes': ram(),
           'wordcraftRevision': os.environ['WORDCRAFT_REV'], 'frameworkRevision': 'cn1 7.0.279',
           'cn1Revision': os.environ.get('GITHUB_SHA', '')}, sys.stdout, indent=2)
