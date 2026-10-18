#!/usr/bin/env python3
"""Record resolved runtime artifacts separately from application source counts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tomllib
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=Path)
p.add_argument('--out',type=Path,required=True)
a=p.parse_args()
root=Path(__file__).resolve().parent.parent
paths=sorted(set(Path(s) for s in (root/'common/target/test-classpath.txt').read_text().strip().split(os.pathsep)))
artifacts=[]
for path in paths:
    data=path.read_bytes()
    artifacts.append({'file':path.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),
                      'projectArtifact':'/com/codenameone/wordprocessor/' in path.as_posix()})
lock=tomllib.loads((a.wordcraft/'Cargo.lock').read_text())
packages=lock.get('package',[])
def file_info(path):
    if not path.exists():return None
    data=path.read_bytes()
    return {'file':path.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
report={'schema':1,'java':subprocess.run([java,'-version'],capture_output=True,text=True).stderr.strip(),
        'rustc':subprocess.check_output(['rustc','--version'],text=True).strip(),
        'javaRuntimeArtifacts':artifacts,
        'javaDependencyBytes':sum(x['bytes'] for x in artifacts if not x['projectArtifact']),
        'rustLockedPackages':len(packages),'rustExternalLockedPackages':sum('source' in x for x in packages),
        'rustLockSha256':hashlib.sha256((a.wordcraft/'Cargo.lock').read_bytes()).hexdigest(),
        'nativeApplication':file_info(root/'common/target/mac-native/Build/Products/Release/Main.app/Contents/MacOS/Main'),
        'wordcraftCli':file_info(a.wordcraft/'target/release/wordcraft-cli'),
        'comparableArtifactScope':False,
        'notes':['Java classpath is separate JVM CLI tooling. The application is a ParparVM AppKit binary. Rust artifact is CLI only.',
                 'Classpath jars and lockfile packages are dependency inventories, not comparable source-LOC totals or installed app sizes.',
                 'Source metrics exclude both frameworks and dependencies. CN1 reuses platform UI/input/storage and text-format APIs; Rust reuses its Cargo dependency stack.']}
a.out.parent.mkdir(parents=True,exist_ok=True)
a.out.write_text(json.dumps(report,indent=2)+'\n')
print('Recorded',len(artifacts),'Java artifacts and',len(packages),'Rust lockfile packages')
