#!/usr/bin/env python3
"""Compare process-start-to-text-output on one identical DOCX, with output equality."""
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

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft', type=Path)
p.add_argument('document', type=Path)
p.add_argument('--iterations', type=int, default=10)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
if not 5 <= a.iterations <= 1000:
    p.error('iterations must be between 5 and 1000')
root = Path(__file__).resolve().parent.parent
commands = {
    'cn1': [str(root/'scripts/wordprocessor'), 'text', str(a.document.resolve())],
    'wordcraft': [str(a.wordcraft.resolve()), 'text', str(a.document.resolve())],
}
results = {name: [] for name in commands}
expected = None
for iteration in range(a.iterations + 1):
    # Alternate order to reduce a simple first/second cache bias; first pair is warmup.
    order = list(commands) if iteration % 2 == 0 else list(reversed(commands))
    for name in order:
        start = time.perf_counter_ns()
        run = subprocess.run(commands[name], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             check=True, timeout=120)
        elapsed = (time.perf_counter_ns() - start) / 1e6
        if expected is None:
            expected = run.stdout
        if run.stdout != expected:
            raise SystemExit('Text outputs differ; comparison rejected before reporting timings')
        if iteration:
            results[name].append(elapsed)
def stats(values):
    return {'medianMs': statistics.median(values),
            'p95Ms': sorted(values)[math.ceil(len(values)*.95)-1], 'samplesMs': values}
baseline = json.loads((root/'benchmarks/baseline.json').read_text())
report = {'schema': 1, 'operation': 'fresh-process DOCX text extraction',
          'timingBoundary': 'process invocation to captured stdout completion; includes runtime startup',
          'cache': 'one discarded invocation per app; subsequent invocations use warm filesystem caches',
          'platform': platform.platform(), 'architecture': platform.machine(),
          'iterations': a.iterations, 'fixtureSha256': hashlib.sha256(a.document.read_bytes()).hexdigest(),
          'textSha256': hashlib.sha256(expected).hexdigest(), 'textBytes': len(expected),
          'outputsEqual': True,
          'wordcraftVersion': subprocess.check_output([str(a.wordcraft.resolve()), '--version'], text=True).strip(),
          'baselineRevision': baseline['revision'],
          'binaryRevisionVerified': False,
          'java': subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java', '-version'], capture_output=True, text=True).stderr.strip(),
          'cn1': stats(results['cn1']), 'wordcraft': stats(results['wordcraft']),
          'cn1RuntimeSelection': 'jvm tooling; not application startup',
          'renderingMeasured': False, 'typingMeasured': False, 'peakMemoryMeasured': False,
          'overallPerformanceGate': 'not-evaluated'}
a.out.parent.mkdir(parents=True, exist_ok=True)
a.out.write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report, indent=2))
