#!/usr/bin/env bash
#
# Builds the flashable Live FPS HUD module.
#
#   ./build.sh                 # uses $ANDROID_HOME / $ANDROID_SDK_ROOT
#   ANDROID_HOME=~/Android/Sdk ./build.sh
#
# Output: build/fox_live_fps-<version>.zip
#
set -euo pipefail
cd "$(dirname "$0")"

MODULE_ID=fox_live_fps
BUILD=build
PKG=$BUILD/pkg
VERSION=$(grep -m1 '^version=' module.prop | cut -d= -f2)

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}

find_android_jar() {
	if [ -n "${ANDROID_JAR:-}" ]; then echo "$ANDROID_JAR"; return; fi
	ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null |
		sort -t- -k2 -n | tail -n1
}

find_d8() {
	if [ -n "${D8:-}" ]; then echo "$D8"; return; fi
	ls "$SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -n1
}

ANDROID_JAR=$(find_android_jar)
D8=$(find_d8)

[ -n "$ANDROID_JAR" ] || {
	echo "error: no android.jar found under $SDK/platforms" >&2
	echo "       install one with:  sdkmanager 'platforms;android-36'" >&2
	exit 1
}
[ -n "$D8" ] || {
	echo "error: no d8 found under $SDK/build-tools" >&2
	echo "       install one with:  sdkmanager 'build-tools;36.0.0'" >&2
	exit 1
}

echo "==> android.jar : $ANDROID_JAR"
echo "==> d8          : $D8"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$PKG"

echo "==> compiling java"
javac -Xlint:-options --release 8 -nowarn \
	-cp "$ANDROID_JAR" \
	-d "$BUILD/classes" \
	$(find src -name '*.java')

echo "==> dexing"
"$D8" --release --min-api 26 --lib "$ANDROID_JAR" \
	--output "$BUILD/dex" \
	$(find "$BUILD/classes" -name '*.class')

echo "==> packaging"
cp "$BUILD/dex/classes.dex" "$PKG/fps_overlay.dex"
cp module.prop customize.sh service.sh action.sh uninstall.sh config.default.prop "$PKG/"
mkdir -p "$PKG/system/bin" "$PKG/webroot"
cp system/bin/fpshud "$PKG/system/bin/fpshud"
cp -r webroot/. "$PKG/webroot/"
[ -f ../LICENSE ] && cp ../LICENSE "$PKG/LICENSE"

chmod 0755 "$PKG/system/bin/fpshud" "$PKG"/*.sh

ZIP=$(pwd)/$BUILD/${MODULE_ID}-${VERSION}.zip
(cd "$PKG" && zip -qr9 "$ZIP" .)
# stable filename as well, so CI/scripts can pick it up without knowing the version
cp -f "$ZIP" "$(pwd)/${MODULE_ID}.zip"

echo
echo "==> $ZIP"
unzip -l "$ZIP"
