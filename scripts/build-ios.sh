#!/usr/bin/env bash
# iOS: ios-source project, built unsigned for the simulator and for devices (sign the device
# build with your team before installing it on a phone).
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
source scripts/maven-env.sh
rm -f common/target/classes/wordprocessor-agent.properties
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp -Dcodename1.platform=ios -Dcodename1.buildTarget=ios-source package "$@"
for sdk in iphonesimulator iphoneos; do
  xcodebuild -quiet -project common/target/app-common-0.1.0-SNAPSHOT-ios-source/Main.xcodeproj \
    -scheme Main -configuration Release -sdk $sdk -derivedDataPath common/target/ios-native \
    CODE_SIGNING_ALLOWED=NO build
done
