#!/usr/bin/env bash
set -euo pipefail

# Builds cryptcore with Android NDK. Requires ANDROID_NDK_ROOT in env.

ABIS=("arm64-v8a")
API=34
ROOT=$(cd "$(dirname "$0")/.." && pwd)

if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  echo "ANDROID_NDK_ROOT not set" >&2
  exit 1
fi

for ABI in "${ABIS[@]}"; do
  BUILD_DIR="$ROOT/native-build/$ABI"
  rm -rf "$BUILD_DIR"
  mkdir -p "$BUILD_DIR"
  cmake -S "$ROOT/app/src/main/cpp" -B "$BUILD_DIR" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-$API \
    -DANDROID_STL=c++_static \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -GNinja
  cmake --build "$BUILD_DIR"
  if [[ "${COPY_TO_JNILIBS:-0}" == "1" ]]; then
    mkdir -p "$ROOT/app/src/main/jniLibs/$ABI"
    cp "$BUILD_DIR"/*.so "$ROOT/app/src/main/jniLibs/$ABI/"
  fi
done

echo "Native build complete."
