# Local native Linux build

Run `scripts/build-linux.sh` on Linux with JDK 17 and Maven. It uses Codename One's `local-linux-device` target and ParparVM; the output is an ELF executable, not a JVM UI host. The script selects the host's ARM64 or x64 architecture. Use a project-specific `WORDPROCESSOR_MAVEN_REPO` populated with the same framework snapshot as the other targets. Never install the custom snapshot in `~/.m2`. CI (`build.yml`) builds x64 and arm64 packages; neither has been run on a Linux desktop yet.

The GTK port requires CMake, Ninja, a C compiler and pkg-config, plus development packages for GTK 3, Cairo, Pango, GdkPixbuf, GLib/GIO, fontconfig, FreeType, OpenSSL/libcrypto, libcurl, Epoxy/EGL/GLES, GStreamer (including app/video), WebKitGTK 4.1, libsecret, libnotify and GeoClue. On Ubuntu 22.04 the additional packages include `libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev libwebkit2gtk-4.1-dev libsecret-1-dev libnotify-dev libgeoclue-2-dev librsvg2-common` alongside the GTK/curl/SSL development packages. Xvfb and xauth are needed only for a headless local review session.

```sh
export JAVA_HOME=/path/to/jdk8
export WORDPROCESSOR_MAVEN_REPO=/unique/project/repository
scripts/build-linux.sh
common/target/codenameone/antProject/dist/linux-build/result/Main
```

The app build deliberately uses Maven `clean package`. In the pinned framework, a failed prior build can leave translated C in the next native-input scan; a plain retry then indexes its own generated code and can exhaust several gigabytes of translator heap. Cleaning the application target removes that contamination. Increasing the compiler heap is not the fix.

The default compiler falls back to the host C toolchain when Zig is unavailable. Such an artifact inherits the host glibc/library requirements; it is not a portable Linux release. Record the host distribution, architecture, framework revision and `ldd` output with each acceptance record.

For built-in MCP review, append `-Pagent-debug -Dwordprocessor.agent.port=18768`. The listener is loopback-only. The pinned merged framework (`008f9ece`) does not implement the Linux loopback server transport or the editor input-method bridge; the 2026-10-16 Linux acceptance build adds GTK input-method support, dialog pointer routing, UTF-8 clipboard/native-edit conversion and the isolated Linux-port socket patch archived as `artifacts/parity-20261016-comparison/linux-framework-full.patch`. The framework follow-up is [Codename One PR #6026](https://github.com/codenameone/CodenameOne/pull/6026), head `120d445c7d`. Other platform builds do not use this patch. Its exact source and patch hashes are in `linux-framework-provenance.json`. Install the patched Linux module into a unique repository before building this review configuration. A stock pinned snapshot cannot start the live MCP listener and reports unavailable editor keyboard input. When testing inside a container, run the Python MCP client in the same container rather than publishing its port. When using `xvfb-run` in Docker, pass Docker `--init` so Xvfb readiness signals are handled correctly. Use a disposable document, restore its XML, wait for recovery persistence, then terminate the exact review process. Never leave multiple review applications running.

The 2026-10-16 evidence distinguishes a Linux native build and Xvfb/MCP workflows from physical desktop interaction. Xvfb without a window manager cannot certify title dragging, minimize/maximize, external clipboard interoperability, native file choosers, printing or full desktop parity. See [platform evidence](platforms.json) and [comparison acceptance](comparison-acceptance.json) for the actual result. Windows and physical mobile acceptance remain separate.


The accepted Linux artifact is `linux-arm64-review`, a local ARM64 Ubuntu 22.04 build using `-Dcodename1.arg.linux.debug=true` (RelWithDebInfo, no release LTO) and the opt-in MCP profile. It is an acceptance/debugging artifact, not a release-size or performance baseline. Clang 14 with `LDFLAGS=-fuse-ld=lld` avoids the slow GNU LTO link in the review container. The archive includes the recipe, native CMake configuration, dynamic-library requirements, source patch, exact patched Linux source and hashes.

Local acceptance runs seven comparison checks, thirteen Mailings checks, thirteen XTest document-keyboard checks and four dialog-field checks. The latter exercise initial dialog focus, BMP/supplementary Unicode composition, leaving and returning to a native window, mouse-driven field changes and isolation from the document. The scripts `live-linux-input-smoke.py` and `live-linux-dialog-smoke.py` require an explicitly disposable session and restore/check the original XML. XTest sends X11 events through GTK; this is not physical-keyboard or window-manager certification. Native TextArea overlay editing is a separate API from the EditField input-method path exercised by this app.

The typing checkpoint adds native GTK menu accelerator dispatch before the canvas key handler consumes input. Its Ctrl-B/typing/undo regression passes within 73 local native checks. See [typing acceptance](TYPING-UNDO.md) for the updated artifact and patch chain.
