#!/usr/bin/env bash
set -euo pipefail
wordprocessor_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
classpath_file="$wordprocessor_root/desktop/target/runtime-classpath.txt"
if [[ ! -f "$classpath_file" ]]; then
  echo 'Run scripts/build.sh first to resolve the Java runtime classpath.' >&2
  exit 1
fi
classpath="$(cat "$classpath_file")"
for module in document layout formats automation editor desktop benchmarks; do
  classpath="$wordprocessor_root/$module/target/classes:$classpath"
done
export WORDPROCESSOR_CLASSPATH="$classpath"
