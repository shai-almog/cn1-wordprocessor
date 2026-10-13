#!/usr/bin/env bash
# Codename One's standard Maven ParparVM macOS target. Local builds only.
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
if [[ -n "${CI:-}" ]]; then echo 'Native builds/tests are local only.' >&2; exit 1; fi
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
mvn -B -ntp -pl editor -am install "$@"
mvn -B -ntp -f app/pom.xml -Dcodename1.platform=ios -Dcodename1.buildTarget=mac-source package "$@"
xcodebuild -project app/common/target/app-common-0.1.0-SNAPSHOT-mac-source/Main.xcodeproj \
  -scheme Main -configuration Release -derivedDataPath app/common/target/mac-native \
  -destination 'platform=macOS,arch=arm64' CODE_SIGNING_ALLOWED=NO build
