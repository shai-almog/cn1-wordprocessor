# Native application startup

The application now uses Codename One's standard Maven `mac-source` target and
ParparVM, followed by an Xcode Release build of the generated AppKit/Metal project.
Run `scripts/build-native.sh`, then `scripts/run-desktop.sh`.

The framework checkout used was `master` at `d519cd4e6f`, which includes shared ZIP
APIs from `5eddb95098` (#6006). The locally installed ParparVM bundle was rebuilt
from that checkout before generating the application. Older snapshot bundles can
lack these APIs even when the Java source compiles against a full JDK.

The first native launch crashed because the application constructed a CN1 Container
in a field initializer before `Display.init()`. The generated native stub constructs
`Main` before initializing Display. Editor construction now happens in `Main.init()`.
A regression test exercises construction before Display initialization.

The rebuilt native application was launched and its rendered document inspected.
Its ZIP recovery record was independently checked for its CRC, ZIP integrity and
XML contents. The executable is ARM64 and links AppKit/Metal without a JVM.

Native GUI startup and RSS have now been measured using repeated fresh native
processes and matching DOCX inputs. See [the complete report](NATIVE-BENCHMARKS.md)
and [machine-readable distributions](native-benchmarks.json). Readiness is the end
of CPU document painting, not GPU presentation; the filesystem cache is warm.
The final application uses Aqua's native macOS menus and title bar alongside the
Word-style ribbon. Native menu execution and Command-F were verified interactively.

The local core, Mac port, build engine and Maven plugin were rebuilt from the
framework checkout for the Aqua build. The app explicitly selects
`macos.themeMode=aqua`, retains the native theme's constants, and declares commands
through `Command.setDesktopMenu` / `setDesktopShortcut`. It does not install a
custom NSMenu or initialize Display itself.

The previous GraalVM CLI experiment used the wrong compiler and application boundary.
Its raw records are retained under [history/graalvm-cli](history/graalvm-cli/README.md)
and withdrawn as evidence for this application's native startup performance.
