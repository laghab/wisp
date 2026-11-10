#!/usr/bin/env bash
# Builds the on-device text-rewrite engine (llama.cpp, pinned) for every ABI into app/src/main/jniLibs and records the checksums in
# docs/native-libs.sha256. Needs the Android NDK, cmake and ninja; the llama.cpp source is cloned to a scratch folder, not into the repo.
# Usage: tools/build-llm.sh [scratch-dir]
set -euo pipefail
cd "$(dirname "$0")/.."
LLAMA_TAG=v0.9.0
LLAMA_COMMIT=cd959bae99ff4bed22bef2604f45241c09bbb8c8
SCRATCH="${1:-${TMPDIR:-/tmp}/wisp-llm-build}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/29.0.14206865}"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
mkdir -p "$SCRATCH"
if [ ! -d "$SCRATCH/llama.cpp" ]; then git clone -q --branch "$LLAMA_TAG" --depth 1 https://github.com/ggml-org/llama.cpp.git "$SCRATCH/llama.cpp"; fi
[ "$(git -C "$SCRATCH/llama.cpp" rev-parse HEAD)" = "$LLAMA_COMMIT" ] || { echo "llama.cpp is not at the pinned commit $LLAMA_COMMIT" >&2; exit 1; }

build() { # build <abi> <out-name> <extra cmake flags...>
  local abi="$1" name="$2"; shift 2
  local dir="$SCRATCH/build-$abi-$name"
  cmake -S tools/llm -B "$dir" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release -DLLAMA_DIR="$SCRATCH/llama.cpp" -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG" "$@" | tail -1
  cmake --build "$dir" --target wisp_llm | tail -2
  mkdir -p "app/src/main/jniLibs/$abi"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "app/src/main/jniLibs/$abi/$name.so" "$dir/libwisp_llm.so"
  echo "built $abi/$name.so"
}

build arm64-v8a libwisp_llm                  -DGGML_CPU_ARM_ARCH=armv8-a
# Phones from about 2018 on have the dot-product instructions that make 4-bit models much faster; the app picks this file when the CPU reports them.
build arm64-v8a libwisp_llm_dotprod          -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16
# 32-bit ARM (armeabi-v7a) is not built: llama.cpp no longer builds for it, and the rewrite feature reports itself unavailable there.
build x86_64 libwisp_llm

# Pin: replace this engine's lines in the checksum file.
touch docs/native-libs.sha256
grep -v 'libwisp_llm' docs/native-libs.sha256 > docs/native-libs.sha256.new || true
(cd app/src/main/jniLibs && sha256sum */libwisp_llm*.so) >> docs/native-libs.sha256.new
sort -k2 docs/native-libs.sha256.new > docs/native-libs.sha256 && rm docs/native-libs.sha256.new
echo "checksums recorded in docs/native-libs.sha256"
