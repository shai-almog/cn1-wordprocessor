#!/usr/bin/env bash
# Standard local CN1 Android source build followed by the generated Gradle project.
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"; fi
if [[ -z "${JAVA17_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA17_HOME="$(/usr/libexec/java_home -v 17)"; fi
: "${JAVA17_HOME:?Set JAVA17_HOME to JDK 17 or newer for Android Gradle}"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
source scripts/maven-env.sh
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.local/gradle-repository}"
rm -f editor/target/classes/wordprocessor-agent.properties
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp -pl editor -am install "$@"
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp -f app/pom.xml -Dcodename1.platform=android -Dcodename1.buildTarget=android-source package "$@"
project="$PWD/app/common/target/app-common-0.1.0-SNAPSHOT-android-source"
JAVA_HOME="$JAVA17_HOME" bash "$project/gradlew" -p "$project" --no-daemon assembleDebug
