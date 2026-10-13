#!/usr/bin/env python3
"""Matched fresh-process extraction: native Java, JVM fallback, launcher and Rust."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import statistics
import subprocess
import time
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft', type=Path)
p.add_argument('fixture', type=Path)
p.add_argument('--iterations', type=int, default=100)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
if os.environ.get('CI'):
    p.error('Native builds/tests are local only')
if not 10 <= a.iterations <= 1000:
    p.error('iterations must be between 10 and 1000')
root = Path(__file__).resolve().parent.parent
native = root/'desktop/target/native/wordprocessor'
launcher = root/'scripts/wordprocessor'
commands = {'native': [str(native)], 'launcher': [str(launcher)],
            'jvm': [str(launcher)], 'wordcraft': [str(a.wordcraft.resolve())]}
a.out.parent.mkdir(parents=True, exist_ok=True)
fixtures = {'1000-paragraphs': a.fixture.resolve()}
# Derive separate small/large workload sizes from the same minimal DOCX package.
with zipfile.ZipFile(a.fixture) as z:
    parts = {name: z.read(name) for name in z.namelist()}
for count in [1, 10000]:
    target = a.out.parent/f'{count}-paragraphs.docx'
    paragraph = '<w:p><w:r><w:t>Startup measurement with ordinary text and no cached document output.</w:t></w:r></w:p>'
    body = ('<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
            + paragraph*count + '</w:body></w:document>')
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in parts.items():
            z.writestr(name, body if name == 'word/document.xml' else data)
    fixtures[f'{count}-paragraphs'] = target.resolve()
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def stats(values):
    return {'medianMs': statistics.median(values),
            'p95Ms': sorted(values)[math.ceil(len(values)*.95)-1], 'samplesMs': values}
report = {'schema': 1, 'operation': 'fresh-process DOCX text extraction',
          'boundary': 'process invocation through exit and captured stdout; warm filesystem cache, no daemon or output cache',
          'warmupsPerArm': 5, 'iterationsPerArm': a.iterations,
          'loadAverageBefore': os.getloadavg(),
          'platform': platform.platform(), 'architecture': platform.machine(),
          'java': subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/java'), '-version'], capture_output=True, text=True).stderr.strip(),
          'nativeToolchain': (native.parent/'toolchain.txt').read_text().strip(),
          'nativeOptions': '-O3; generic armv8-a on this host; no workload-trained PGO (compiler default ML-inferred profiles); three immutable application classes initialized at build time',
          'binaries': {k: {'sha256': sha(path), 'bytes': path.stat().st_size} for k, path in [('native', native), ('wordcraft', a.wordcraft)]},
          'wordcraftBaselineRevision': json.loads((root/'benchmarks/baseline.json').read_text())['revision'],
          'guiStartupMeasured': False, 'fixtures': {}}
for name, fixture in fixtures.items():
    values = {key: [] for key in commands}
    expected = None
    for iteration in range(a.iterations+5):
        keys = list(commands)
        # Rotate all four arms, and reverse every second cycle.
        offset = iteration % len(keys)
        keys = keys[offset:] + keys[:offset]
        if iteration // len(keys) % 2:
            keys.reverse()
        for key in keys:
            env = dict(os.environ, WORDPROCESSOR_RUNTIME='jvm' if key == 'jvm' else 'native')
            start = time.perf_counter_ns()
            r = subprocess.run(commands[key]+['text', str(fixture)], env=env,
                               capture_output=True, check=True, timeout=120)
            elapsed = (time.perf_counter_ns()-start)/1e6
            if expected is None:
                expected = r.stdout
            if r.stdout != expected:
                raise SystemExit(f'{name}: {key} output differs; timings rejected')
            if iteration >= 5:
                values[key].append(elapsed)
    report['fixtures'][name] = {'fixtureSha256': sha(fixture), 'fixtureBytes': fixture.stat().st_size,
        'textSha256': hashlib.sha256(expected).hexdigest(), 'textBytes': len(expected),
        'outputsEqual': True, 'arms': {k: stats(v) for k, v in values.items()}}
    print(name, {k: round(statistics.median(v), 3) for k,v in values.items()}, flush=True)
report['loadAverageAfter'] = os.getloadavg()
a.out.write_text(json.dumps(report, indent=2)+'\n')
