# Native application startup

The application is a standard Codename One Maven project built with the `mac-source` target and ParparVM, followed by an Xcode Release build of the generated AppKit/Metal project. The result is an ARM64 executable that links AppKit/Metal and runs without a JVM. Use JDK 17 for Maven.

Build with `scripts/build-native.sh`, then run with `scripts/run-desktop.sh`. Builds use an isolated Maven repository (see `scripts/maven-env.sh`), never `~/.m2`. The installed ParparVM bundle must come from a framework checkout that includes the shared ZIP APIs; older snapshot bundles can lack them even when the source compiles against a full JDK.

## Lifecycle rule

The generated native stub constructs `Main` before `Display` is initialized, so `Main` must not create Codename One components in field initializers. Editor construction happens in `Main.init()`. A regression test covers construction before Display initialization.

## Menus

The Mac build uses Aqua's native menus and title bar alongside the ribbon. It selects `macos.themeMode=aqua`, keeps the native theme's constants, and declares commands through `Command.setDesktopMenu` / `setDesktopShortcut`. It installs no custom NSMenu and does not initialize Display itself.

Startup time and memory benchmarks are produced by CI (`metrics.yml`); see the README for current results.
