#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
source scripts/maven-env.sh
# Maven does not remove resources omitted by a later profile switch.
rm -f common/target/classes/wordprocessor-agent.properties
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp "$@" install
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp -pl common org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath -DincludeScope=test -Dmdep.outputFile=target/test-classpath.txt
