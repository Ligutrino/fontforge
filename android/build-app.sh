#!/usr/bin/env bash
# Compila el motor (build.sh), el puente nativo y la app Android. Resultado: salida/ffglifos.apk
set -euo pipefail
WORK="${WORK:-/work}"
ABI="${ABI:-arm64-v8a}"
API="${API:-29}"
OUT=$WORK/salida
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
APP_SRC="${APP_SRC:-/work/app}"

# 1. Motor de FontForge (reutiliza lo ya compilado)
"$SCRIPTS/build.sh"

# 2. Puente nativo libffglifos.so
echo ">> [4/5] Puente nativo (libffglifos)"
TC_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
cmake -S "$APP_SRC/nativo" -B "$WORK/build/ffglifos" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
  -DFF_SRC="$WORK/fuentes/fontforge" -DFF_BUILD="$WORK/build/fontforge"
cmake --build "$WORK/build/ffglifos"

# 3. App Android
echo ">> [5/5] App Android (Gradle)"
PROY=$WORK/proyecto
rm -rf "$PROY"
cp -r "$APP_SRC/android" "$PROY"
LIBS="$PROY/app/src/main/jniLibs/$ABI"
mkdir -p "$LIBS"
cp "$OUT/fontforge/lib/libfontforge.so" "$LIBS/"
cp "$WORK/build/ffglifos/libffglifos.so" "$LIBS/"
"$TC_BIN/llvm-strip" --strip-unneeded "$LIBS/libffglifos.so"
echo "sdk.dir=$ANDROID_HOME" > "$PROY/local.properties"

export GRADLE_USER_HOME=$WORK/gradle
export ANDROID_USER_HOME=$WORK/android-home    # guarda la clave de firma entre compilaciones
(cd "$PROY" && gradle --no-daemon --console=plain assembleRelease)

cp "$PROY/app/build/outputs/apk/release/app-release.apk" "$OUT/ffglifos.apk"
echo
echo ">> Listo: salida/ffglifos.apk ($(du -h "$OUT/ffglifos.apk" | cut -f1))"
