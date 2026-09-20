#!/usr/bin/env bash
# Downloads potion-retrieval-32M (123 MB, MIT) and derives the vocab.txt the
# WordPiece loaders read. Phase 1 only; Phase 0 ships precomputed vectors.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dir="${1:-$here/models/potion-retrieval-32M}"
base=https://huggingface.co/minishlab/potion-retrieval-32M/resolve/main
mkdir -p "$dir"
for f in config.json tokenizer.json tokenizer_config.json model.safetensors; do
  [[ -s "$dir/$f" ]] || curl -fsSL -o "$dir/$f" "$base/$f"
done
python3 - "$dir" <<'PY'
import json, sys
d = sys.argv[1]
vocab = json.load(open(f"{d}/tokenizer.json"))["model"]["vocab"]
tokens = sorted(vocab.items(), key=lambda kv: kv[1])
assert [i for _, i in tokens] == list(range(len(tokens))), "vocab ids are not contiguous"
open(f"{d}/vocab.txt", "w").write("\n".join(t for t, _ in tokens) + "\n")
print(f"vocab.txt: {len(tokens)} tokens")
PY
