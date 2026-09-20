#!/usr/bin/env bash
# Builds ProtomoltSearch.aar from the pinned protomolt-search checkout.
#
# Same steps as the engine's scripts/build-android-aar.sh, which does not run on
# a macOS host: it needs bash 4 (`${var^^}`; macOS ships 3.2) and looks for an
# NDK host directory named darwin-arm64, while the NDK ships only darwin-x86_64
# (universal binaries) on every Mac. Candidate fixes to upstream.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
engine="${ENGINE_DIR:-$here/../protomolt-search}"
output="${1:-$here/android/libs/ProtomoltSearch.aar}"
min_api="${ANDROID_MIN_API:-26}"
# arm64 covers every phone and the emulators on an Apple Silicon host. Add x86_64
# only for emulators on an Intel host: ANDROID_ABIS="arm64-v8a x86_64".
abis="${ANDROID_ABIS:-arm64-v8a}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ndk="${ANDROID_NDK_HOME:-$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)}"
[[ -e "$output" ]] && { echo "refusing to overwrite existing AAR: $output" >&2; exit 2; }

toolchain=""
for host in linux-x86_64 darwin-arm64 darwin-x86_64; do
  [[ -d "$ndk/toolchains/llvm/prebuilt/$host/bin" ]] && toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
done
[[ -n "$toolchain" ]] || { echo "no NDK toolchain under $ndk" >&2; exit 2; }
echo "NDK: $ndk"

stage="$(mktemp -d)"; trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/assets/ai/protomolt/search/mobile/v1" "$stage/assets/ai/protomolt/search/v1" "$(dirname "$output")"

for abi in $abis; do
  case "$abi" in
    arm64-v8a) target=aarch64-linux-android ;;
    x86_64) target=x86_64-linux-android ;;
    *) echo "unsupported ABI: $abi" >&2; exit 2 ;;
  esac
  linker="$toolchain/${target}${min_api}-clang"
  [[ -x "$linker" ]] || { echo "Android linker is missing: $linker" >&2; exit 2; }
  var="CARGO_TARGET_$(printf '%s' "$target" | tr 'a-z-' 'A-Z_')_LINKER"
  export "$var=$linker"
  cargo build --manifest-path "$engine/Cargo.toml" --locked --release \
    -p protomolt-search-embedded --target "$target"
  mkdir -p "$stage/jni/$abi"
  cp "$engine/target/$target/release/libprotomolt_search_embedded.so" "$stage/jni/$abi/"
done

mkdir -p "$stage/classes"
javac --release 8 -d "$stage/classes" \
  "$engine/mobile/android/src/main/java/ai/pipestream/search/mobile/ProtomoltSearch.java"
jar --create --file "$stage/classes.jar" -C "$stage/classes" .
rm -rf "$stage/classes"
cp "$engine/mobile/android/AndroidManifest.xml" "$engine/mobile/android/proguard.txt" "$engine/mobile/android/R.txt" "$stage/"
cp "$engine/proto/ai/protomolt/search/mobile/v1/mobile.proto" "$stage/assets/ai/protomolt/search/mobile/v1/"
cp "$engine/proto/ai/protomolt/search/v1/"*.proto "$stage/assets/ai/protomolt/search/v1/"
jar --create --file "$output" -C "$stage" .
echo "$output"
