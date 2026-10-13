# Project instructions

Use JDK 8 for Maven. Build with `scripts/build.sh`; validate changed libraries with their relevant tests, then run the reactor and desktop callback smoke when editing interaction code. `app` is the current CN1 archetype wrapper, not the reusable engine.

Do not mark Wordcraft catalog rows complete without reproducible equivalent behavior and artifact evidence. Do not equate a successful compilation, simulated input callback, synthetic benchmark or smaller incomplete codebase with cross-platform parity or a performance win. Keep limitations in docs/STATUS.md and docs/FORMATS.md current.

Never add, dispatch or rerun native/GraalVM CI jobs. Native tests are local only. The native-test label is banned. No credentials, local recovery documents, generated app artifacts or internal operational logs belong in source control.

Honor the user's Git/PR settings in ~/.codex/config.toml when opening PRs. Do not invent a PR title prefix or draft preference. Local implementation does not authorize public release.
