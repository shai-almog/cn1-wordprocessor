#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/java-classpath.sh"
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"; fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Dwordprocessor.smoke=true -Dwordprocessor.home=.cn1-wordprocessor-smoke -Dwordprocessor.capture="$wordprocessor_root/desktop/target/editor.png" -cp "$wordprocessor_root/desktop/target/test-classes:$WORDPROCESSOR_CLASSPATH" com.codenameone.wordprocessor.Desktop
