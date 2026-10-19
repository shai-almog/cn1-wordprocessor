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
    # MACOS_SIGN_IDENTITY (a Developer ID Application identity in the keychain) signs with the
    # hardened runtime; NOTARY_* credentials then notarize and staple. Without an identity the
    # app is ad-hoc signed: it runs on Apple Silicon, but Gatekeeper asks the user to allow it.
    identity=${MACOS_SIGN_IDENTITY:--}
    flags=(--force --deep --timestamp --options runtime); [[ $identity == - ]] && flags=(--force --deep)
    codesign "${flags[@]}" --sign "$identity" "$staging/Wordprocessor.app"
    codesign --verify --strict --verbose=2 "$staging/Wordprocessor.app"
    ln -s /Applications "$staging/Applications"
    hdiutil create -quiet -volname Wordprocessor -srcfolder "$staging" -ov -format UDZO "dist/$name.dmg"
    if [[ $identity != - ]]; then
      codesign --force --timestamp --sign "$identity" "dist/$name.dmg"
      if [[ -n ${NOTARY_APPLE_ID:-} ]]; then
        xcrun notarytool submit "dist/$name.dmg" --wait --apple-id "$NOTARY_APPLE_ID" \
          --team-id "$NOTARY_TEAM_ID" --password "$NOTARY_PASSWORD"
        xcrun stapler staple "dist/$name.dmg"
      fi
    fi ;;
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
