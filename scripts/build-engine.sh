#!/usr/bin/env bash
# Builds ProtomoltSearch.xcframework from a pinned protomolt-search checkout.
set -euo pipefail
ENGINE_REV="${ENGINE_REV:-beb936404125dfc717e572cfa325a6b7826fd78f}"
ENGINE_DIR="${ENGINE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/protomolt-search}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$here/ios/Frameworks/ProtomoltSearch.xcframework"
actual="$(git -C "$ENGINE_DIR" rev-parse HEAD)"
if [[ "$actual" != "$ENGINE_REV" ]]; then
  echo "protomolt-search is at $actual, sample is pinned to $ENGINE_REV" >&2
  exit 2
fi
mkdir -p "$(dirname "$out")"
"$ENGINE_DIR/scripts/build-apple-xcframework.sh" "$out"
