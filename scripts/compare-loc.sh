#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# != 1 ]]; then echo 'Usage: compare-loc.sh /path/to/pinned/wordcraft' >&2; exit 1; fi
command -v cloc >/dev/null
mkdir -p benchmarks/target/loc
cloc document/src/main layout/src/main formats/src/main automation/src/main editor/src/main desktop/src/main app/common/src/main --include-lang=Java --json --out=benchmarks/target/loc/cn1-production.json
cloc "$1/crates" "$1/apps" --include-lang=Rust --exclude-dir=target,tests,benches,examples --json --out=benchmarks/target/loc/wordcraft-source.json
printf '%s\n' 'Rust source can contain inline tests; the Wordcraft count is not yet a matched production-only count.' 'Framework, generated code, runtime and dependency sizes must be reported separately.'
