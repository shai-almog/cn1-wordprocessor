#!/usr/bin/env bash
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
app='app/common/target/mac-native/Build/Products/Release/Main.app'
[[ -d "$app" ]] || { echo 'Run scripts/build-native.sh first.' >&2; exit 1; }
exec open "$app"
