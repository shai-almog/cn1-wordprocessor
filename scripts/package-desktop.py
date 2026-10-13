#!/usr/bin/env python3
"""Create a local JavaSE development distribution; requires a separately installed JDK 8."""
from pathlib import Path
import shutil
import zipfile
root = Path(__file__).resolve().parent.parent
out = root/'desktop/target/distribution'
out.mkdir(parents=True, exist_ok=True)
shutil.copy2(root/'notices/Adobe-AFM-MustRead.html', out/'Adobe-AFM-MustRead.html')
lib = out/'lib'
if lib.exists():
    shutil.rmtree(lib)
lib.mkdir()
classpath = (root/'desktop/target/runtime-classpath.txt').read_text().strip().split(':')
classpath.append(str(root/'desktop/target/desktop-0.1.0-SNAPSHOT.jar'))
for value in classpath:
    source = Path(value)
    target = lib/source.name
    if target.exists() and target.read_bytes() != source.read_bytes():
        raise SystemExit('Conflicting dependency filename: '+source.name)
    shutil.copy2(source, target)
launcher = out/'run.sh'
launcher.write_text('''#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"; fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp 'lib/*' com.codenameone.wordprocessor.Desktop
''')
launcher.chmod(0o755)
(out/'run.cmd').write_text('''@echo off\r
cd /d "%~dp0"\r
if defined JAVA_HOME (\r
  "%JAVA_HOME%\\bin\\java" -cp "lib/*" com.codenameone.wordprocessor.Desktop\r
) else (\r
  java -cp "lib/*" com.codenameone.wordprocessor.Desktop\r
)\r
''')
(out/'README.txt').write_text('CN1 Word Processor development distribution. Requires JDK 8 and a graphical desktop.\nRun run.sh on macOS/Linux or run.cmd on Windows. Windows/Linux execution is unverified.\nThis is an unsigned local test artifact, not a production release. Read docs/STATUS.md in the source repository.\n')
archive = out.parent/'cn1-wordprocessor-desktop.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for path in sorted(out.rglob('*')):
        if path.is_file(): z.write(path,Path('cn1-wordprocessor')/path.relative_to(out))
print(archive)
