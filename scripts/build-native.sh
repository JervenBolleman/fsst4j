#!/usr/bin/env bash
# Builds libfsst (plus the fsst4j shim) and the upstream "fsst" command line tool from the fsst git submodule.
#
# usage: scripts/build-native.sh [outdir]      build into outdir (default: lib/target/native)
#        scripts/build-native.sh --install     build and copy libfsst into lib/src/main/resources/<os>/<arch>/
#
# Unlike the upstream CMake build this does not use -march=native, so the library can be shipped inside the jar.
# The AVX512 kernel is compiled with AVX512 enabled and is only used after a runtime cpuid check.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/fsst"
INSTALL=0
OUT="$ROOT/lib/target/native"
if [ "${1:-}" = "--install" ]; then
   INSTALL=1
elif [ -n "${1:-}" ]; then
   OUT="$1"
fi

if [ ! -f "$SRC/fsst.h" ]; then
   echo "fsst sources not found in $SRC, run: git submodule update --init" >&2
   exit 1
fi

CXX=${CXX:-g++}
CXXFLAGS="-std=c++17 -O3 -DNDEBUG -fPIC"
case "$(uname -m)" in
   x86_64) ARCH=amd64; SIMDFLAGS="-mavx512f -mavx512dq" ;;
   aarch64|arm64) ARCH=aarch64; SIMDFLAGS="" ;;
   *) ARCH=$(uname -m); SIMDFLAGS="" ;;
esac
OS=$(uname -s)

mkdir -p "$OUT/obj"
"$CXX" $CXXFLAGS -c "$SRC/libfsst.cpp" -o "$OUT/obj/libfsst.o"
# upstream compiles the AVX512 kernel with -O1, see the comment in fsst_avx512.cpp
"$CXX" $CXXFLAGS -O1 $SIMDFLAGS -c "$SRC/fsst_avx512.cpp" -o "$OUT/obj/fsst_avx512.o"
"$CXX" $CXXFLAGS -I"$SRC" -c "$ROOT/lib/src/main/native/fsst4j_shim.cpp" -o "$OUT/obj/fsst4j_shim.o"
"$CXX" -shared -o "$OUT/libfsst.so" "$OUT/obj/libfsst.o" "$OUT/obj/fsst_avx512.o" "$OUT/obj/fsst4j_shim.o" -lpthread
# the upstream round trip tool, used by the interoperability tests
"$CXX" $CXXFLAGS -o "$OUT/fsst" "$SRC/fsst.cpp" "$OUT/obj/libfsst.o" "$OUT/obj/fsst_avx512.o" -lpthread

if [ "$INSTALL" = 1 ]; then
   DEST="$ROOT/lib/src/main/resources/$OS/$ARCH"
   mkdir -p "$DEST"
   cp "$OUT/libfsst.so" "$DEST/libfsst.so"
   echo "installed $DEST/libfsst.so"
fi
