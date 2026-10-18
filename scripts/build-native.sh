#!/usr/bin/env bash
# Codename One's standard Maven ParparVM macOS target. Local builds only.
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
source scripts/maven-env.sh
# Prevent a previous development profile from leaking its listener into a normal build.
rm -f common/target/classes/wordprocessor-agent.properties
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp -Dcodename1.platform=ios -Dcodename1.buildTarget=mac-source package "$@"
xcodebuild -project common/target/app-common-0.1.0-SNAPSHOT-mac-source/Main.xcodeproj \
  -scheme Main -configuration Release -derivedDataPath common/target/mac-native \
  -destination 'platform=macOS,arch=arm64' CODE_SIGNING_ALLOWED=NO build
