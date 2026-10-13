#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
mvn verify "$@"
