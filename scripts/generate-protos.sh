#!/usr/bin/env bash
# Regenerates the checked-in SwiftProtobuf types from the pinned engine's
# contracts. Needs protoc and a protoc-gen-swift built into .tools/ (see README).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
engine="${ENGINE_DIR:-$here/../protomolt-search}"
plugin="$here/.tools/swift-protobuf/.build/release/protoc-gen-swift"
out="$here/ios/CourtSearchKit/Sources/CourtSearchKit/Generated"
[[ -x "$plugin" ]] || { echo "build protoc-gen-swift first: $plugin" >&2; exit 2; }
rm -rf "$out" && mkdir -p "$out"
cd "$engine/proto"
protoc --plugin="$plugin" --swift_out="$out" --swift_opt=Visibility=Public \
  --swift_opt=FileNaming=PathToUnderscores -I . \
  ai/protomolt/search/v1/search.proto ai/protomolt/search/v1/source.proto \
  ai/protomolt/search/v1/document_identity.proto ai/protomolt/search/v1/schema_report.proto \
  ai/protomolt/search/v1/error_disclosure.proto ai/protomolt/search/mobile/v1/mobile.proto
git -C "$engine" rev-parse HEAD > "$out/ENGINE_REV"

# The sample's own schema: Swift type plus the descriptor set the engine plans from.
cd "$here/proto"
protoc --plugin="$plugin" --swift_out="$out" --swift_opt=Visibility=Public \
  --swift_opt=FileNaming=PathToUnderscores \
  --include_imports --descriptor_set_out="$here/fixtures/court.desc" -I . court/v1/court.proto
cp "$here/fixtures/court.desc" "$here/fixtures/court_opinions_potion512.ndjson" \
  "$here/ios/CourtSearchKit/Sources/CourtSearchKit/Resources/"
