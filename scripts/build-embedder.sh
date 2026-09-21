#!/usr/bin/env bash
# Builds the sample's embedder (embedder-ffi) for the phones:
#   ios      → ios/Frameworks/CourtEmbedder.xcframework
#   android  → android/app/src/main/jniLibs/arm64-v8a/libcourt_embedder_ffi.so
# Both outputs are gitignored build products.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
crate="$here/embedder-ffi"
what="${1:-all}"

build_ios() {
  export IPHONEOS_DEPLOYMENT_TARGET="${IOS_MIN_VERSION:-18.0}"
  local out="$here/ios/Frameworks/CourtEmbedder.xcframework"
  for target in aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios; do
    cargo build --manifest-path "$crate/Cargo.toml" --release --target "$target"
  done
  local stage; stage="$(mktemp -d)"
  # Headers go in a directory named for the module. Two static XCFrameworks that
  # each put module.modulemap at their Headers root collide when Xcode copies
  # them into one include directory; <Module>/module.modulemap does not.
  mkdir -p "$stage/Headers/CourtEmbedder"
  cp "$crate/include/court_embedder.h" "$crate/include/module.modulemap" "$stage/Headers/CourtEmbedder/"
  lipo -create "$crate/target/aarch64-apple-ios-sim/release/libcourt_embedder_ffi.a" \
               "$crate/target/x86_64-apple-ios/release/libcourt_embedder_ffi.a" \
       -output "$stage/libcourt_embedder_ffi-simulator.a"
  rm -rf "$out"
  xcodebuild -create-xcframework \
    -library "$crate/target/aarch64-apple-ios/release/libcourt_embedder_ffi.a" -headers "$stage/Headers" \
    -library "$stage/libcourt_embedder_ffi-simulator.a" -headers "$stage/Headers" \
    -output "$out" >/dev/null
  rm -rf "$stage"
  echo "$out"
}

build_android() {
  local sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  local ndk="${ANDROID_NDK_HOME:-$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)}"
  local toolchain=""
  for host in linux-x86_64 darwin-arm64 darwin-x86_64; do
    [[ -d "$ndk/toolchains/llvm/prebuilt/$host/bin" ]] && toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
  done
  [[ -n "$toolchain" ]] || { echo "no NDK toolchain under $ndk" >&2; exit 2; }
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/aarch64-linux-android${ANDROID_MIN_API:-26}-clang"
  cargo build --manifest-path "$crate/Cargo.toml" --release --target aarch64-linux-android
  local out="$here/android/app/src/main/jniLibs/arm64-v8a"
  mkdir -p "$out"
  cp "$crate/target/aarch64-linux-android/release/libcourt_embedder_ffi.so" "$out/"
  echo "$out/libcourt_embedder_ffi.so"
}

case "$what" in
  ios) build_ios ;;
  android) build_android ;;
  all) build_ios; build_android ;;
  *) echo "usage: $0 [ios|android|all]" >&2; exit 2 ;;
esac
