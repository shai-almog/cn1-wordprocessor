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
    # Apple's timestamp and notary services can stall without returning, and notarytool's own
    # --timeout does not always fire; bound every network call with a hard wall-clock limit.
    # The command runs in its own process group so a timeout also kills the helpers it spawned
    # (hdiutil's, notarytool's); exit 142 (SIGALRM) means the limit was hit.
    limit() {
      local s=$1; shift
      perl -e 'my $t = shift; my $pid = fork; die "fork: $!" unless defined $pid;
        if (!$pid) { setpgrp(0, 0); exec @ARGV or exit 127 }
        $SIG{ALRM} = sub { kill "TERM", -$pid; sleep 5; kill "KILL", -$pid; exit 142 };
        alarm $t; waitpid($pid, 0);
        exit(($? & 127) ? 128 + ($? & 127) : $? >> 8)' "$s" "$@"
    }
    limit 600 codesign "${flags[@]}" --sign "$identity" "$staging/Wordprocessor.app"
    codesign --verify --strict --verbose=2 "$staging/Wordprocessor.app"
    ln -s /Applications "$staging/Applications"
    limit 600 hdiutil create -quiet -volname Wordprocessor -srcfolder "$staging" -ov -format UDZO "dist/$name.dmg"
    if [[ $identity != - ]]; then
      limit 300 codesign --force --timestamp --sign "$identity" "dist/$name.dmg"
      if [[ -n ${NOTARY_APPLE_ID:-} ]]; then
        # Apple can hold a submission for hours (a new team's first ones especially), so wait a
        # bounded time. An unstapled but accepted DMG still passes Gatekeeper online; the staple
        # only adds offline first launch.
        creds=(--apple-id "$NOTARY_APPLE_ID" --team-id "$NOTARY_TEAM_ID" --password "$NOTARY_PASSWORD")
        status=0
        limit 1500 xcrun notarytool submit "dist/$name.dmg" "${creds[@]}" --wait --timeout 20m || status=$?
        if [[ $status == 0 ]]; then
          limit 300 xcrun stapler staple "dist/$name.dmg" \
            || echo "::warning::Stapling $name.dmg failed; it is signed and may be notarized, but not stapled"
        elif [[ $status == 142 ]]; then
          echo "::warning::Notarization of $name.dmg did not finish in 25 minutes; it is signed, not stapled"
        else
          echo "::warning::Notarization of $name.dmg failed (exit $status; see notarytool output above); it is signed, not notarized"
        fi
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
    # The executable is self-contained (resources are linked in); the rest of its directory is
    # the CMake build tree, object files and Main.debug, which do not ship.
    tar -C "$(dirname "$exe")" -czf "dist/$name-$(uname -m).tar.gz" Main ;;
  windows)
    exe=$(find $t -name Main.exe | head -1)
    (cd "$(dirname "$exe")" && 7z a -bd -tzip "$OLDPWD/dist/$name-${WINDOWS_ARCH:-x64}.zip" . >/dev/null) ;;
  *) echo "Unknown platform $platform" >&2; exit 1 ;;
esac
ls -l dist
