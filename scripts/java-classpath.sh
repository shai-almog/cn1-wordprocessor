#!/usr/bin/env bash
# Exports WORDPROCESSOR_CLASSPATH for the JVM tools (CLI/MCP, benchmarks), which live in the
# test sources and run on the JavaSE port.
set -euo pipefail
wordprocessor_root="${BASH_SOURCE[0]%/*}/.."
classpath_file="$wordprocessor_root/common/target/test-classpath.txt"
if [[ ! -f "$classpath_file" ]]; then
  echo 'Run scripts/build.sh first to resolve the Java runtime classpath.' >&2
  exit 1
fi
IFS= read -r classpath < "$classpath_file" || [[ -n "$classpath" ]]
# Git Bash on Windows: the JVM wants Windows paths and ';' (Maven already wrote the file so).
sep=:
if command -v cygpath >/dev/null 2>&1; then sep=';'; wordprocessor_root=$(cygpath -m "$wordprocessor_root"); fi
export WORDPROCESSOR_CLASSPATH="$wordprocessor_root/common/target/test-classes$sep$wordprocessor_root/common/target/classes$sep$classpath"
