#!/usr/bin/env bash
# Collects the built application for one platform into dist/ as a ready-to-run archive.
# Usage: dist.sh mac|ios|android|web|linux|windows [VERSION]
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
platform=$1 version=${2:-dev} t=common/target
mkdir -p dist
name="Wordprocessor-$version-$platform"
case $platform in
  mac)
    app=$t/mac-native/Build/Products/Release/Main.app
    staging=$(mktemp -d); cp -R "$app" "$staging/Wordprocessor.app"
    hdiutil create -quiet -volname Wordprocessor -srcfolder "$staging" -ov -format UDZO "dist/$name.dmg" ;;
  ios)
    (cd $t/ios-native/Build/Products/Release-iphonesimulator && zip -qry "$OLDPWD/dist/$name-simulator.zip" Main.app)
    staging=$(mktemp -d); mkdir "$staging/Payload"
    cp -R $t/ios-native/Build/Products/Release-iphoneos/Main.app "$staging/Payload/"
    (cd "$staging" && zip -qry "$OLDPWD/dist/$name-unsigned.ipa" Payload) ;;
  android)
    cp "$(find $t -path '*outputs/apk/*' -name '*.apk' | head -1)" "dist/$name.apk" ;;
  web)
    (cd $t/web-preview && zip -qr "$OLDPWD/dist/$name.zip" .) ;;
  linux)
    exe=$(find $t -path '*linux-build*' -type f -name Main -perm -u+x | head -1)
    tar -C "$(dirname "$exe")" -czf "dist/$name-$(uname -m).tar.gz" . ;;
  windows)
    exe=$(find $t -name Main.exe | head -1)
    (cd "$(dirname "$exe")" && 7z a -bd -tzip "$OLDPWD/dist/$name-${WINDOWS_ARCH:-x64}.zip" . >/dev/null) ;;
  *) echo "Unknown platform $platform" >&2; exit 1 ;;
esac
ls -l dist
