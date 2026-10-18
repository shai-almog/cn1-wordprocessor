#!/usr/bin/env bash
# One process per launch, alternating apps, until each side has N completed runs. A launch
# that never reaches ready (a Codename One window that is hidden or fully covered is never
# painted) is kept under failed/ and retried, never counted.
# Usage: collect.sh CN1_EXECUTABLE WORDCRAFT_EXECUTABLE FIXTURE OUT_DIR RUNS [--workload]
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/../.."
cn1=$1 wc=$2 fixture=$3 out=$4 n=$5 extra=${6:-}
mkdir -p "$out/runs" "$out/failed"
attempt=0
for _ in $(seq 1 $((n * 6))); do
  c1=$(ls "$out/runs" | grep -c cn1 || true)
  c2=$(ls "$out/runs" | grep -c wordcraft || true)
  if [[ $c1 -ge $n && $c2 -ge $n ]]; then break; fi
  if [[ $c1 -le $c2 && $c1 -lt $n ]]; then side=cn1; else side=wordcraft; fi
  attempt=$((attempt + 1))
  one="$out/.one"
  rm -rf "$one"
  python3 scripts/native-bench/run.py --cn1 "$cn1" --wordcraft "$wc" --fixture "$fixture" \
    --out "$one" --runs 1 --side "$side" $extra || true
  name=$(printf '%02d-%s' "$attempt" "$side")
  if python3 -c "import json,sys;sys.exit(0 if json.load(open('$one/results.json'))['results'][0]['completed'] else 1)" 2>/dev/null
  then mv "$one" "$out/runs/$name"; else mv "$one" "$out/failed/$name" 2>/dev/null || true; fi
done
python3 - "$out" <<'PY'
import glob, json, sys
out = sys.argv[1]; results = []; meta = None
for f in sorted(glob.glob(out + '/runs/*/results.json')):
    d = json.load(open(f)); meta = meta or d; results += d['results']
meta['results'] = results
json.dump(meta, open(out + '/results.json', 'w'))
print(out, {s: sum(r['side'] == s for r in results) for s in ('cn1', 'wordcraft')},
      'failed', len(glob.glob(out + '/failed/*')))
PY
