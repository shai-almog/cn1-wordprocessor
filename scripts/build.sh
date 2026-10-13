#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
mvn -B -ntp "$@" install
mvn -B -ntp -f desktop/pom.xml org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
