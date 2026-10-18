# Project instructions

Use JDK 17 for Maven (Java 17 is the minimum language level). Build with `scripts/build.sh`; validate changed libraries with their relevant tests, then run the reactor and validate the native application when editing interaction code. `common` is the standard Codename One lifecycle. Use Maven mac-source and ParparVM for the application. Do not add a custom Swing host or use GraalVM as its native compiler.

Do not mark Wordcraft catalog rows complete without reproducible equivalent behavior and artifact evidence. Do not equate a successful compilation, simulated input callback, synthetic benchmark or smaller incomplete codebase with cross-platform parity or a performance win. Keep limitations in docs/STATUS.md and docs/FORMATS.md current.

Native CI lives in `.github/workflows/build.yml` (every platform's distributable, reused by `release.yml` on `v*` tags) and `metrics.yml` (benchmarks against Wordcraft); agents must not dispatch or rerun them, or push tags. Never add GraalVM jobs. The native-test label is banned. No credentials, local recovery documents, generated app artifacts or internal operational logs belong in source control.

Honor the user's Git/PR settings in ~/.codex/config.toml when opening PRs. Do not invent a PR title prefix or draft preference. Local implementation does not authorize public release.

Use an isolated Maven repository for every custom Codename One snapshot and app build. Never use ~/.m2 or a common external cache path such as /tmp/local-cn1. The build scripts source scripts/maven-env.sh: use the project-local default or a unique external path recorded in .local/maven-repo-path. Always pass that same repository explicitly to framework Maven commands.

Keep at most one agent-owned native review app running. Reuse and replace the existing review bundle after quitting it; do not create a new running bundle for each iteration. Terminate simulator test apps when acceptance ends. Identify exact PIDs/bundle IDs before cleanup and preserve unrelated user applications and documents.
