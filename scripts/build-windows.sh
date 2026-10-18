#!/usr/bin/env bash
# Windows: ParparVM to C, compiled with clang-cl inside the Visual Studio environment. Run on
# Windows (Git Bash); elsewhere point CN1_XWIN_SYSROOT at an `xwin splat` SDK to cross-compile.
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
source scripts/maven-env.sh
rm -f common/target/classes/wordprocessor-agent.properties
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp \
  -Dcodename1.platform=win -Dcodename1.buildTarget=local-windows-device \
  -Dcodename1.arg.windows.arch="${WINDOWS_ARCH:-x64}" package "$@"
