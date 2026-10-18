# Local native GUI measurement

These helpers instrument **disposable source copies**, never the production app.
CI runs the same steps in the `macos` job of `.github/workflows/metrics.yml`; `collect.sh` runs one launch at a time and keeps launches that never reached ready under `failed/`. `prepare.py` is not idempotent:
restore the original source before applying it again.

1. Build production CN1 with `scripts/build-native.sh` (JDK 8, current CN1 Maven
   artifacts, ParparVM). Preserve the uninstrumented `.app` for size/hash measurement.
2. Pin Wordcraft to `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0` and build
   `cargo build --release --locked -p wordcraft`. Preserve that executable.
3. Copy the CN1 project to a disposable directory. Copy the Wordcraft checkout.
   Run `python3 scripts/native-bench/prepare.py CN1_COPY WORDCRAFT_COPY`.
4. Build both copies through the same production build paths. Do not overlap
   CN1 Maven phases when the projects share artifact coordinates and a local
   repository. Reinstall the production modules afterward.
5. Stop compilation and close interactive test sessions. Run, for each fixture:

   ```sh
   python3 scripts/native-bench/run.py \
     --cn1 CN1_COPY/common/target/mac-native/Build/Products/Release/Main.app/Contents/MacOS/Main \
     --wordcraft WORDCRAFT_COPY/target/release/wordcraft \
     --fixture FIXTURE.docx --out artifacts/native-bench/final/startup-1000 --runs 10
   ```

   Use `startup-1`, `startup-1000`, and `startup-10000` for the corresponding
   paragraph counts. Add `--workload --runs 3` and use `workload-1000` for editing
   and file operations. Output directories must be new.
6. Record production executable hashes and byte sizes, bundle byte totals and
   machine information in `artifacts/native-bench/final/artifacts.json`.
   Regenerate `artifacts/source-metrics.json` from the uninstrumented sources.
   Run `python3 scripts/native-bench/report.py --base YOUR_RUN_DIRECTORY`.
   Use a new directory for each build; retain old runs for before/after comparisons.

The harness launches fresh native processes, alternates application order, samples
RSS with macOS `proc_pidinfo`, and obtains kernel peak RSS/CPU through `wait4`.
It terminates only its own opt-in benchmark child, which uses disposable output
and disables normal recovery writes. Filesystem caches are warm; no cache flushing
or system-wide tuning is performed.

Readiness means completion of the application's CPU document paint callback.
It is **not** the first displayed frame or input-to-photon latency. GPU timestamps
are deliberately absent: the two ports use different presentation paths.

Typing is invoked through each application's editor/command API, not synthetic OS
keystrokes. CN1 uses its actual fonts, pagination and ribbon updates. Wordcraft uses
its normal command dispatch, layout and GUI. Equal input text is not equivalent
formatting, typography, pagination or PDF fidelity. All five DOCX saves per process
must pass ZIP CRC and paragraph-text comparison; each app checks undo restoration.
