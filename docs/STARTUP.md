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

**Native GUI startup timings have not yet been measured.** A valid comparison must
measure launch to the first presented editable document in both GUI applications,
with identical documents, recovery state, hardware and cache conditions. Report
median/p95 and failed launches separately. CLI extraction timings do not answer this.

The previous GraalVM CLI experiment used the wrong compiler and application boundary.
Its raw records are retained under [history/graalvm-cli](history/graalvm-cli/README.md)
and withdrawn as evidence for this application's native startup performance.
