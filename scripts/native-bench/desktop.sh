#!/usr/bin/env bash
# The desktop benchmark against Wordcraft on this machine's OS (macOS, Linux or Windows/Git
# Bash): production builds and their sizes, fixtures, instrumented copies, startup and workload
# runs, then the report. The metrics workflow runs it on each GitHub runner; Linux runs it under
# xvfb-run. Usage: desktop.sh OUT_DIR [RUNS]
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/../.."
out=$1 runs=${2:-10}
: "${WORDCRAFT_REPO:?}" "${WORDCRAFT_REV:?}"
tmp=${RUNNER_TEMP:-${TMPDIR:-/tmp}}/desktop-bench
rm -rf "$tmp" && mkdir -p "$tmp" "$out"
case "$(uname -s)" in
  Darwin) os=macos build=scripts/build-native.sh ;;
  Linux) os=linux build=scripts/build-linux.sh ;;
  MINGW*|MSYS*) os=windows build=scripts/build-windows.sh ;;
  *) echo "Unsupported OS $(uname -s)" >&2; exit 1 ;;
esac
export WORDPROCESSOR_MAVEN_REPO="$PWD/.local/maven-repository"
if [[ $os == windows ]]; then
  # Git Bash's /usr/bin/link (coreutils) shadows MSVC's link.exe; point Cargo at the real one.
  CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER=$(where.exe link.exe | tr -d '\r' | grep -i msvc | head -1)
  export CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER
fi

cn1_exe() { # the native executable under $1
  case $os in
    macos) echo "$1/common/target/mac-native/Build/Products/Release/Main.app/Contents/MacOS/Main" ;;
    linux) find "$1/common/target" -path '*linux-build*' -type f -name Main -perm -u+x | head -1 ;;
    windows) find "$1/common/target" -name Main.exe | head -1 ;;
  esac
}
wc_exe() { for f in "$1/target/release/wordcraft.exe" "$1/target/release/wordcraft"; do [[ -f $f ]] && { echo "$f"; return 0; }; done; return 0; }

# Fixtures first: the Linux build cleans common/target, which holds the JVM generator.
scripts/build.sh -DskipTests
mvn -B -ntp -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -pl common \
  org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath \
  -DincludeScope=test -Dmdep.outputFile=target/test-classpath.txt
mkdir -p artifacts/startup-final
scripts/benchmark.sh 1 1 artifacts/startup-final/1-paragraphs.docx
scripts/benchmark.sh 1000 1 artifacts/1000-paragraphs.docx
scripts/benchmark.sh 10000 1 artifacts/startup-final/10000-paragraphs.docx

# Production builds, measured for size.
$build -DskipTests
git clone -q "$WORDCRAFT_REPO" "$tmp/wordcraft"
git -C "$tmp/wordcraft" checkout -q "$WORDCRAFT_REV"
(cd "$tmp/wordcraft" && cargo build --release --locked -p wordcraft)
if [[ $os == macos ]]; then (cd "$tmp/wordcraft" && bash packaging/macos/package.sh --arch aarch64); fi
python3 scripts/native-bench/sizes.py "$os" "$(cn1_exe .)" "$(wc_exe "$tmp/wordcraft")" "$tmp/wordcraft" > "$out/artifacts.json"

# Instrumented copies (prepare.py), built the same way.
rsync -a --exclude target --exclude .git --exclude .local --exclude artifacts ./ "$tmp/cn1-bench/" 2>/dev/null \
  || { mkdir -p "$tmp/cn1-bench"; tar --exclude=./target --exclude=./.git --exclude=./.local --exclude=./artifacts -cf - . | tar -C "$tmp/cn1-bench" -xf -; }
cp -R "$tmp/wordcraft" "$tmp/wordcraft-bench"
python3 scripts/native-bench/prepare.py "$tmp/cn1-bench" "$tmp/wordcraft-bench"
(cd "$tmp/cn1-bench" && WORDPROCESSOR_MAVEN_REPO="$WORDPROCESSOR_MAVEN_REPO" $build -DskipTests)
(cd "$tmp/wordcraft-bench" && cargo build --release --locked -p wordcraft)

cn1=$(cn1_exe "$tmp/cn1-bench") wc=$(wc_exe "$tmp/wordcraft-bench")
c=scripts/native-bench/collect.sh
bash $c "$cn1" "$wc" artifacts/startup-final/1-paragraphs.docx "$out/startup-1" "$runs"
bash $c "$cn1" "$wc" artifacts/1000-paragraphs.docx "$out/startup-1000" "$runs"
bash $c "$cn1" "$wc" artifacts/startup-final/10000-paragraphs.docx "$out/startup-10000" "$runs"
bash $c "$cn1" "$wc" artifacts/1000-paragraphs.docx "$out/workload-1000" 3 --workload
python3 scripts/native-bench/report.py --base "$out" \
  --out "$out/native-benchmarks.md" --json "$out/native-benchmarks.json"
