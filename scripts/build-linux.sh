#!/usr/bin/env bash
# Standard local Maven / ParparVM Linux target. Requires a Linux build host.
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
if [[ -n "${CI:-}" ]]; then echo 'Native builds/tests are local only.' >&2; exit 1; fi
if [[ "$(uname -s)" != Linux ]]; then echo 'Run this script on Linux (or in an isolated Linux container).' >&2; exit 1; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
source scripts/maven-env.sh
case "$(uname -m)" in
  aarch64|arm64) cn1_linux_arch=arm64 ;;
  x86_64|amd64) cn1_linux_arch=x64 ;;
  *) echo 'Unsupported Linux architecture.' >&2; exit 1 ;;
esac
# Prevent a previous debug profile from leaking its listener into a normal build.
rm -f editor/target/classes/wordprocessor-agent.properties
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp -pl editor -am install "$@"
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp -f app/pom.xml \
  -Dcodename1.platform=linux -Dcodename1.buildTarget=local-linux-device \
  -Dcodename1.arg.linux.arch="$cn1_linux_arch" clean package "$@"
