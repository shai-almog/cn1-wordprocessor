#!/usr/bin/env bash
# Local only. Never invoke native-image builds/tests in CI.
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
if [[ -n "${CI:-}" ]]; then echo 'Native builds/tests are local only.' >&2; exit 1; fi
: "${GRAALVM_HOME:?Set GRAALVM_HOME to a JDK with native-image (tested with GraalVM 21)}"
[[ -x "$GRAALVM_HOME/bin/native-image" ]] || { echo 'native-image is missing' >&2; exit 1; }
scripts/build.sh "$@"
source scripts/java-classpath.sh cli
mkdir -p desktop/target/native
"$GRAALVM_HOME/bin/native-image" --no-fallback -O3 -J-Xmx6g \
  --initialize-at-build-time=com.codenameone.wordprocessor.TextStyle,com.codenameone.wordprocessor.PageSpec,com.codenameone.wordprocessor.PdfMetrics \
  -cp "$WORDPROCESSOR_CLASSPATH" com.codenameone.wordprocessor.Cli desktop/target/native/wordprocessor.pending
cp notices/Adobe-AFM-MustRead.html desktop/target/native/
python3 scripts/test-cli-runtimes.py --native desktop/target/native/wordprocessor.pending
mv desktop/target/native/wordprocessor.pending desktop/target/native/wordprocessor
"$GRAALVM_HOME/bin/native-image" --version > desktop/target/native/toolchain.txt
