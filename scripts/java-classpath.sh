#!/usr/bin/env bash
set -euo pipefail
wordprocessor_root="${BASH_SOURCE[0]%/*}/.."
classpath_file="$wordprocessor_root/desktop/target/runtime-classpath.txt"
if [[ ! -f "$classpath_file" ]]; then
  echo 'Run scripts/build.sh first to resolve the Java runtime classpath.' >&2
  exit 1
fi
IFS= read -r classpath < "$classpath_file" || [[ -n "$classpath" ]]
modules='document layout formats automation editor desktop benchmarks'
if [[ "${1:-}" == cli ]]; then
  # CLI codecs need CN1 core; GUI/JCEF/JOGL/SQLite are not CLI dependencies.
  IFS=: read -r -a jars <<< "$classpath"
  classpath=''
  for jar in "${jars[@]}"; do
    case "$jar" in */codenameone-core/*) classpath="$jar";; esac
  done
  [[ -n "$classpath" ]] || { echo 'Missing Codename One core dependency' >&2; exit 1; }
  modules='document layout formats automation desktop'
fi
for module in $modules; do
  classpath="$wordprocessor_root/$module/target/classes:$classpath"
done
export WORDPROCESSOR_CLASSPATH="$classpath"
