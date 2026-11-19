#!/usr/bin/env bash
# Compila freetype, libxml2, libpng y el motor de FontForge (sin interfaz) para Android.
# Corre dentro del contenedor. El resultado queda en /work/salida.
set -euo pipefail

ABI="${ABI:-arm64-v8a}"
API="${API:-29}"                       # Android 10 o superior
FF_REF="${FF_REF:-master}"             # rama o tag de FontForge
FREETYPE_REF="${FREETYPE_REF:-VER-2-13-3}"
LIBXML2_REF="${LIBXML2_REF:-v2.13.8}"
LIBICONV_VER="${LIBICONV_VER:-1.18}"
LIBPNG_REF="${LIBPNG_REF:-v1.6.44}"
JOBS="${JOBS:-$(nproc)}"

WORK="${WORK:-/work}"
SRC=$WORK/fuentes
BUILD=$WORK/build
PREFIX=$WORK/build/prefix
OUT=$WORK/salida
mkdir -p "$SRC" "$BUILD" "$PREFIX" "$OUT"

# Toolchain: NDK de Android por defecto. TOOLCHAIN_FILE permite probar con otro compilador cruzado.
if [[ -n "${TOOLCHAIN_FILE:-}" ]]; then
  TC=(-DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE")
else
  TC=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
      -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API")
fi
COMMON=("${TC[@]}" -G Ninja -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_PREFIX="$PREFIX"
        -DCMAKE_PREFIX_PATH="$PREFIX" -DCMAKE_FIND_ROOT_PATH="$PREFIX"
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON)

clone() { # url ref dir
  if [[ ! -d "$3/.git" ]]; then
    echo ">> Descargando $1 ($2)"
    git clone --depth 1 --branch "$2" "$1" "$3"
  fi
}

clone https://github.com/freetype/freetype.git "$FREETYPE_REF" "$SRC/freetype"
clone https://github.com/GNOME/libxml2.git     "$LIBXML2_REF"  "$SRC/libxml2"
clone https://github.com/pnggroup/libpng.git  "$LIBPNG_REF"   "$SRC/libpng"
clone https://github.com/fontforge/fontforge.git "$FF_REF"     "$SRC/fontforge"

# El NDK ya trae zlib. Solo para pruebas con otro compilador cruzado: BUILD_ZLIB=1
if [[ "${BUILD_ZLIB:-0}" == 1 ]]; then
  clone https://github.com/madler/zlib.git v1.3.1 "$SRC/zlib"
  cmake -S "$SRC/zlib" -B "$BUILD/zlib" "${COMMON[@]}" -DZLIB_BUILD_EXAMPLES=OFF
  cmake --build "$BUILD/zlib" -j "$JOBS" && cmake --install "$BUILD/zlib"
  rm -f "$PREFIX"/lib/libz.so*
fi

# GNU libiconv: el iconv de Android no trae las codificaciones que FontForge necesita
# (UCS-4 desde Latin-1, Mac Roman, codificaciones asiaticas de las tablas de nombres).
ICONV_ARGS=()
if [[ -z "${TOOLCHAIN_FILE:-}" ]]; then
  echo ">> [0/4] libiconv $LIBICONV_VER"
  case "$ABI" in
    arm64-v8a) HOST=aarch64-linux-android ;;
    x86_64)    HOST=x86_64-linux-android ;;
    *) echo "ABI no soportada: $ABI"; exit 1 ;;
  esac
  TC_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  if [[ -f "$PREFIX/lib/libiconv.a" ]]; then
    echo "   (ya compilada)"
  else
  if [[ ! -d "$SRC/libiconv-$LIBICONV_VER" ]]; then
    curl -fsSL "https://ftp.gnu.org/pub/gnu/libiconv/libiconv-$LIBICONV_VER.tar.gz" | tar xz -C "$SRC"
  fi
  mkdir -p "$BUILD/libiconv"
  (cd "$BUILD/libiconv" && "$SRC/libiconv-$LIBICONV_VER/configure" --host="$HOST" --prefix="$PREFIX" \
      --enable-static --disable-shared --with-pic --disable-nls \
      CC="$TC_BIN/$HOST$API-clang" AR="$TC_BIN/llvm-ar" RANLIB="$TC_BIN/llvm-ranlib" >/dev/null \
   && make -j "$JOBS" >/dev/null && make install >/dev/null)
  fi
  ICONV_ARGS=(-DIconv_IS_BUILT_IN=OFF -DIconv_INCLUDE_DIR="$PREFIX/include" -DIconv_LIBRARY="$PREFIX/lib/libiconv.a")
fi

echo ">> [1/4] freetype"
cmake -S "$SRC/freetype" -B "$BUILD/freetype" "${COMMON[@]}" \
  -DBUILD_SHARED_LIBS=OFF -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON \
  -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON -DFT_DISABLE_ZLIB=OFF
cmake --build "$BUILD/freetype" -j "$JOBS"
cmake --install "$BUILD/freetype"

echo ">> [2/4] libxml2"
cmake -S "$SRC/libxml2" -B "$BUILD/libxml2" "${COMMON[@]}" \
  -DBUILD_SHARED_LIBS=OFF -DLIBXML2_WITH_PYTHON=OFF -DLIBXML2_WITH_LZMA=OFF \
  -DLIBXML2_WITH_ICU=OFF -DLIBXML2_WITH_ICONV=OFF -DLIBXML2_WITH_TESTS=OFF \
  -DLIBXML2_WITH_PROGRAMS=OFF -DLIBXML2_WITH_ZLIB=OFF
cmake --build "$BUILD/libxml2" -j "$JOBS"
cmake --install "$BUILD/libxml2"

echo ">> [3/4] libpng (imágenes de fondo en la capa Atrás)"
cmake -S "$SRC/libpng" -B "$BUILD/libpng" "${COMMON[@]}" \
  -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF -DPNG_TOOLS=OFF \
  -DPNG_FRAMEWORK=OFF -DSKIP_INSTALL_PROGRAMS=ON
cmake --build "$BUILD/libpng" -j "$JOBS"
cmake --install "$BUILD/libpng"

echo ">> [4/4] FontForge (motor sin interfaz)"
cmake -S "$SRC/fontforge" -B "$BUILD/fontforge" "${COMMON[@]}" \
  -DCMAKE_INSTALL_PREFIX="$OUT/fontforge" \
  -DBUILD_SHARED_LIBS=ON -DENABLE_GUI=OFF \
  -DENABLE_PYTHON_SCRIPTING=OFF -DENABLE_PYTHON_EXTENSION=OFF \
  -DENABLE_LIBSPIRO=OFF -DENABLE_LIBGIF=OFF -DENABLE_LIBJPEG=OFF -DENABLE_LIBPNG=ON \
  -DENABLE_LIBREADLINE=OFF -DENABLE_LIBTIFF=OFF -DENABLE_WOFF2=OFF \
  -DENABLE_LIBUNIBREAK=OFF -DENABLE_HARFBUZZ=OFF -DENABLE_DOCS=OFF \
  -DLIBXML2_LIBRARY="$PREFIX/lib/libxml2.a" -DLIBXML2_INCLUDE_DIR="$PREFIX/include/libxml2" \
  -DFREETYPE_LIBRARY="$PREFIX/lib/libfreetype.a" -DFREETYPE_INCLUDE_DIRS="$PREFIX/include/freetype2" \
  -DPNG_LIBRARY="$PREFIX/lib/libpng16.a" -DPNG_PNG_INCLUDE_DIR="$PREFIX/include" \
  "${ICONV_ARGS[@]}"
cmake --build "$BUILD/fontforge" -j "$JOBS"
cmake --install "$BUILD/fontforge"

# Quitar simbolos de depuracion para reducir el tamano
if [[ -z "${TOOLCHAIN_FILE:-}" ]]; then
  "$TC_BIN/llvm-strip" --strip-unneeded "$OUT/fontforge/bin/fontforge" "$OUT/fontforge/lib/"libfontforge.so*
fi

# Lanzador y prueba para ejecutar en la tablet (Termux)
cp "$(dirname "$0")/prueba.pe" "$OUT/fontforge/"
cat > "$OUT/fontforge/fontforge.sh" <<'RUN'
#!/system/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
export LD_LIBRARY_PATH="$DIR/lib:$LD_LIBRARY_PATH"
exec "$DIR/bin/fontforge" "$@"
RUN
chmod +x "$OUT/fontforge/fontforge.sh"
(cd "$OUT" && tar czf "fontforge-android-$ABI.tar.gz" fontforge)

echo
echo ">> Listo. Archivos generados:"
file "$OUT/fontforge/bin/fontforge" "$OUT/fontforge/lib/"libfontforge.so* | sed "s|$OUT/||"
echo ">> Paquete: salida/fontforge-android-$ABI.tar.gz"
