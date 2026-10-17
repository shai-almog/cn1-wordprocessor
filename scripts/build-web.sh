#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
source scripts/maven-env.sh
# Build current libraries and remove any stale opt-in native debugging resource.
rm -f editor/target/classes/wordprocessor-agent.properties
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp -pl editor -am -DskipTests install "$@"
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp -f app/pom.xml package -DskipTests -Dcodename1.platform=javascript -Dcodename1.buildTarget=local-javascript "$@"
mkdir -p app/common/target/web-preview
unzip -q -o app/common/target/app-common-0.1.0-SNAPSHOT.zip -d app/common/target/web-preview
printf '%s\n' 'Built app/common/target/web-preview. Run scripts/serve-web.sh for a local preview.'
printf '%s\n' 'After rebuilding, close all preview tabs/PWA windows before reopening; an existing client retains its cached bundle. A new PORT also gives a fresh acceptance origin.'
