# Native Linux build

Run `scripts/build-linux.sh` on Linux with JDK 17 and Maven. It uses Codename One's
`local-linux-device` target and ParparVM; the output is a native ELF executable with no JVM.
The script builds for the host architecture (x64 or ARM64). CI (`build.yml`) builds both and
packages each as a tarball holding the single executable.

The GTK port needs CMake, Ninja, a C compiler and pkg-config, plus development packages for
GTK 3, Cairo, Pango, GdkPixbuf, GLib/GIO, fontconfig, FreeType, OpenSSL, libcurl, Epoxy/EGL/GLES,
GStreamer (including app/video), WebKitGTK 4.1, libsecret, libnotify and GeoClue. On Ubuntu that
includes `libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev libwebkit2gtk-4.1-dev
libsecret-1-dev libnotify-dev libgeoclue-2-dev librsvg2-common` alongside the GTK, curl and SSL
development packages. `.github/workflows/build.yml` has the exact list CI installs.

```sh
export WORDPROCESSOR_MAVEN_REPO=/unique/project/repository   # never ~/.m2
scripts/build-linux.sh
common/target/codenameone/antProject/dist/linux-build/result/Main
```

The build uses `mvn clean package` on purpose: a failed earlier build can leave translated C
in the next native-input scan, which can exhaust the translator's heap. Cleaning fixes it;
raising the heap does not.

Without Zig the compiler falls back to the host C toolchain, so the executable inherits the
host's glibc and library requirements.

For live debugging through the built-in MCP server, append `-Pagent-debug` (see
[LIVE-MCP](LIVE-MCP.md)).
