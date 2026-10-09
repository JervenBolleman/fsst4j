#!/usr/bin/env bash
# Builds libfsst (plus the fsst4j shim) and the upstream "fsst" command line tool from the fsst git submodule.
#
# usage: scripts/build-native.sh [outdir]      build into outdir (default: lib/target/native)
#        scripts/build-native.sh --install     also copy the library to lib/src/main/resources/native/<os>-<arch>/
#
# Supported: Linux (gcc or clang), macOS (clang) and Windows (MinGW-w64 g++ in a bash shell, e.g. Git Bash).
# The <os>-<arch> names match NativeFsst.platform(): linux|macos|windows - amd64|aarch64.
#
# Unlike the upstream CMake build this does not use -march=native, so the library can be shipped inside the jar.
# The AVX512 kernel is compiled with AVX512 enabled and is only used after a runtime cpuid check.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/fsst"
INSTALL=0
OUT="$ROOT/lib/target/native"
for arg in "$@"; do
   case "$arg" in
      --install) INSTALL=1 ;;
      *) OUT="$arg" ;;
   esac
done

if [ ! -f "$SRC/fsst.h" ]; then
   echo "fsst sources not found in $SRC, run: git submodule update --init" >&2
   exit 1
fi

case "$(uname -m)" in
   x86_64|amd64) ARCH=amd64; SIMDFLAGS="-mavx512f -mavx512dq" ;;
   aarch64|arm64) ARCH=aarch64; SIMDFLAGS="" ;;
   *) ARCH=$(uname -m | tr -cd 'a-z0-9'); SIMDFLAGS="" ;;
esac

# -fsigned-char: libfsst compares a char with the terminator byte, make that behave as on x86 everywhere (ARM has
# unsigned char), so the symbol tables are identical on all platforms and to the java implementation.
CXXFLAGS="-std=c++17 -O3 -DNDEBUG -fsigned-char ${EXTRA_CXXFLAGS:-}"
case "$(uname -s)" in
   Linux)
      OS=linux; CXX=${CXX:-g++}; LIB=libfsst.so; EXE=fsst
      CXXFLAGS="$CXXFLAGS -fPIC"
      # avoid depending on the libstdc++ version of the build machine, if static libstdc++ is installed
      if echo 'int main(){}' | "$CXX" -x c++ - -static-libstdc++ -static-libgcc -o /dev/null 2>/dev/null; then
         LDFLAGS="-static-libstdc++ -static-libgcc -lpthread"
      else
         echo "warning: no static libstdc++, the library will depend on the system libstdc++" >&2
         LDFLAGS="-lpthread"
      fi
      SHARED="-shared"
      ;;
   Darwin)
      OS=macos; CXX=${CXX:-clang++}; LIB=libfsst.dylib; EXE=fsst
      CXXFLAGS="$CXXFLAGS -fPIC"
      LDFLAGS="-lpthread"
      SHARED="-dynamiclib -install_name @rpath/libfsst.dylib"
      ;;
   MINGW*|MSYS*|CYGWIN*|CLANG*|UCRT*)
      OS=windows; CXX=${CXX:-g++}; LIB=fsst.dll; EXE=fsst.exe
      # long is 32 bits on windows, libfsst uses __builtin_ctzl on 64 bit values.
      # fsst_avx512.cpp uses the MSVC intrinsic __cpuidex on _WIN32, but only includes intrin.h for MSVC.
      CXXFLAGS="$CXXFLAGS -D__builtin_ctzl=__builtin_ctzll -include intrin.h"
      # a self contained, stripped dll, without libstdc++/libgcc/winpthread dlls
      LDFLAGS="-static -static-libstdc++ -static-libgcc -s"
      SHARED="-shared"
      ;;
   *) echo "unsupported os $(uname -s)" >&2; exit 1 ;;
esac

echo "building $LIB and $EXE for $OS-$ARCH with $CXX into $OUT"
mkdir -p "$OUT/obj"
"$CXX" $CXXFLAGS -c "$SRC/libfsst.cpp" -o "$OUT/obj/libfsst.o"
# upstream compiles the AVX512 kernel with -O1, see the comment in fsst_avx512.cpp
"$CXX" $CXXFLAGS -O1 $SIMDFLAGS -c "$SRC/fsst_avx512.cpp" -o "$OUT/obj/fsst_avx512.o"
"$CXX" $CXXFLAGS -I"$SRC" -c "$ROOT/lib/src/main/native/fsst4j_shim.cpp" -o "$OUT/obj/fsst4j_shim.o"
"$CXX" $SHARED -o "$OUT/$LIB" "$OUT/obj/libfsst.o" "$OUT/obj/fsst_avx512.o" "$OUT/obj/fsst4j_shim.o" $LDFLAGS
# the upstream round trip tool, used by the interoperability tests
"$CXX" $CXXFLAGS -o "$OUT/$EXE" "$SRC/fsst.cpp" "$OUT/obj/libfsst.o" "$OUT/obj/fsst_avx512.o" $LDFLAGS

if [ "$INSTALL" = 1 ]; then
   DEST="$ROOT/lib/src/main/resources/native/$OS-$ARCH"
   mkdir -p "$DEST"
   cp "$OUT/$LIB" "$DEST/$LIB"
   echo "installed $DEST/$LIB"
fi
