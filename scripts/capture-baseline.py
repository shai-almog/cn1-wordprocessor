#!/usr/bin/env python3
"""Capture a reviewed local Wordcraft checkout; never fetches or asserts functional parity."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('checkout', type=Path)
p.add_argument('--out', type=Path, required=True, help='New snapshot path; does not overwrite the accepted baseline')
a = p.parse_args()
if a.out.exists():
    p.error('Output already exists; select a new path')
path = a.checkout/'crates/engine/src/catalog.rs'
data = path.read_bytes()
match = re.search(r'pub const CATALOG: &str = "\\\n(.*?)";', data.decode(), re.S)
if match is None:
    raise SystemExit('Catalog format changed; inspect upstream before updating the parser')
features = []
for line in match.group(1).splitlines():
    if not line.strip():
        continue
    fields = line.split('|')
    if len(fields) != 4:
        raise SystemExit('Unexpected catalog row: '+line)
    tab, group, feature, command = fields
    features.append(dict(tab=tab, group=group, feature=feature, wordcraftCommand=command,
                         wordcraftVerification='not-run', cn1Status='pending', evidence=[]))
report = dict(repository='https://github.com/storytold/wordcraft',
              revision=subprocess.check_output(['git','-C',str(a.checkout),'rev-parse','HEAD'], text=True).strip(),
              checkoutDirty=bool(subprocess.check_output(['git','-C',str(a.checkout),'status','--porcelain'])),
              catalogSource='crates/engine/src/catalog.rs', catalogSha256=hashlib.sha256(data).hexdigest(),
              catalogEntries=len(features), capturedDate=datetime.date.today().isoformat(),
              performanceGate=dict(maxTimingRegressionPercent=5,requireEquivalentDocumentsFontsOperations=True,status='not-evaluated'),
              features=features)
a.out.parent.mkdir(parents=True, exist_ok=True)
a.out.write_text(json.dumps(report, indent=2)+'\n')
print('Captured',len(features),'catalog entries at',report['revision'])
