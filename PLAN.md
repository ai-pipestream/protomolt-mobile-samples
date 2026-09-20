# On-device court search sample — plan

Draft, 2026-09-20. Doc references such as `device-shards.md` and
`embedded-mobile.md` are in `ai-pipestream/protomolt-search` under `docs/`. This
plans the single-device sample that has to work before the collaborative-shard
integration described there is testable.

Feasible, and further along than expected. The Rust engine compiles clean for
`aarch64-apple-ios` today, and the AAR and XCFramework packaging scripts already
exist. The sample is app code, not new engine work. It runs in three phases,
and the first needs no open decision settled: precomputed vectors on iOS, then
an on-device embedder owned by the sample, then Android.

Working rule for this sample: logic that only a mobile app needs is built into
the sample first. Whether any of it moves into the main project is a later,
separate decision. The sample lives in its own repository,
`protomolt-mobile-samples`, and consumes the engine as a built XCFramework and
AAR from a pinned `protomolt-search` revision. Nothing here changes that
repository.

## Verdict

Every precondition is already met on the dev machine this was checked on.
Nothing needs installing before work starts, except one exported variable.

| Precondition | State on 2026-09-20 |
| --- | --- |
| Rust mobile targets | All 5 installed |
| Rust core compiles for iOS | Clean in 44.7s, warnings only |
| Xcode | 26.5 (17F42) |
| Android NDK | 4 versions present, newest 28.0.12916984 |
| protoc | 25.3 |
| XCFramework packaging | `scripts/build-apple-xcframework.sh` exists |
| AAR packaging | `scripts/build-android-aar.sh` exists |
| `ANDROID_HOME` / `ANDROID_NDK_HOME` | Unset — the AAR script exits 2 without one |
| Rust version | 1.92.0 locally; CI and mobile packaging pin 1.98.0 |
| NDK version | r28 newest locally; packaging pins r29 |

The compile check was the decisive test:

```
cargo check --locked -p protomolt-search-embedded -p protomolt-embedder \
  --target aarch64-apple-ios
→ Finished `dev` profile in 44.70s   (exit 0)
```

The only warnings came from the parent `pipestream-search` lib (5 dead-code
warnings, `last_seq` and `accept_seq` in `src/relay.rs`). Neither mobile crate
produced any.

## What already exists

The mobile surface is built, symmetric across platforms, and has test
harnesses. None of it needs writing.

| Surface | Location | State |
| --- | --- | --- |
| C ABI | `crates/protomolt-search-embedded/src/mobile.rs` | 12 exported functions |
| Crate shape | `crate-type = ["rlib", "staticlib", "cdylib"]` | iOS static + Android shared |
| JNI binding | `jni = "0.22.4"` | Declared and wired |
| Swift wrapper | `mobile/apple/ProtomoltSearch.swift` | 12 public funcs |
| C header + modulemap | `mobile/apple/include/` | Present |
| Java wrapper | `mobile/android/.../ProtomoltSearch.java` | 12 native methods |
| AAR metadata | `mobile/android/AndroidManifest.xml`, `proguard.txt` | Present |
| Wire format | `proto/ai/protomolt/search/mobile/v1/mobile.proto` | 11 messages |
| Target sweep | `scripts/check-mobile.sh` | 5 targets |
| Device tests | `mobile/apple/device-tests/`, `mobile/android/device-tests/` | Harnesses only |

Both wrappers expose the same protobuf-bytes-in, protobuf-bytes-out calls:
`open`, `ingestMapped`, `acceptDocument`, `describeSchema`, `planIndex`,
`readAcceptedDocuments`, `query`, `queryStreamOpen`/`Next`/`Close`, `flush`,
`close`. An app never touches the C ABI directly.

One correction to carry back: `device-shards.md` lists "expose planning through
the mobile byte ABI" as outstanding, but `protomolt_search_plan_index` exists in
`mobile.rs`. That doc is dated 2026-09-05 and has drifted.

## The embedder boundary

The embedded runtime ships no embedder, and that is policy, not an omission.
`embedded-mobile.md` lists it under the no-egress boundary: "Embeddings enter as
caller-supplied vectors or fields in mapped protobuf documents."
`plans/embedder-model-choice.md` repeats it: the runtime "accepts
caller-supplied vectors and deliberately ships no embedder." The sample respects
that. The engine is consumed as the published XCFramework and AAR, unchanged.

So the app is the caller that supplies vectors. Two facts shape how:

1. **`protomolt-embedder` is Rust, and a spike.** Swift and Kotlin cannot call it
   without an FFI layer, and the root manifest keeps it "dev-only so the product
   gains no dependency on the provider while the ownership question stands."
2. **The sample may own that FFI layer.** A small C ABI around
   `protomolt-embedder` (a git dependency pinned by revision), built as its own
   static and shared library in this repository, gives the app an embedder without touching the engine
   crate, the mobile ABI, or the ownership question. If it proves out, it is a
   candidate to upstream; until then it is sample code.

Rejected: adding `embed` to `mobile.rs` (breaks the documented boundary), and
reimplementing the tokenizer and pooling in Swift and Kotlin (two more
implementations of a contract that `embedder-model-choice.md` says was "learned
by differential test, not from docs").

### The model does not block the sample

`embedder-model-choice.md` frames the table choice as the gate for *shipping* an
embedder, and its title says it "blocks the code less than it looks." For a
sample it does not block at all:

| | `minilm-l6-v2-static` | `potion-retrieval-32M` |
| --- | --- | --- |
| Dim | 256 | 512 |
| Table f32 | ~31 MB | 123 MB (measured) |
| Obtainable | No — only at `/work/court-corpus/models/` on the build host | Yes — Hugging Face, MIT |

The sample uses potion-512 because it is the one that can be downloaded. 123 MB
is acceptable in a demo app; it is a product concern, tracked in that document,
not here. The engine staticlib strips to about 10 MB, so the table dominates
either way. Do not quantize on device: that "silently forks the score space."

### Conformance is not yet proven

`device-shards.md` lists "cross-platform embedding conformance" as remaining,
and there is already evidence the implementations diverge on what they accept.
Loading `potion-retrieval-32M` into the Java Model2Vec provider (`protomolt`,
`:samples:runCourtDocIndex -Pmodel2vec=`) fails:

```
InvalidFormatException: Malformed tokenizer.json at offset 2618:
  model.vocab is an object; only the Unigram list layout ([piece, score] pairs)
  maps pieces to matrix rows here
```

OpenNLP's `StaticEmbeddingModel.load()` dispatches on file presence: `vocab.txt`
selects the WordPiece branch, otherwise `tokenizer.json` goes to the
Unigram/SentencePiece branch. potion's tokenizer is WordPiece
(bge-base-en-v1.5) and Hugging Face ships no `vocab.txt`, so it took the wrong
branch. Deriving `vocab.txt` from `tokenizer.json` (63,091 tokens, contiguous
ids, matching the `[63091, 512]` matrix) makes it load and produce sensible
neighbours.

Two things follow, both outside this sample. The Java provider needs either a
WordPiece reader for `tokenizer.json` or a documented `vocab.txt` requirement.
And Phase 0 below produces a useful side effect: vectors precomputed by one
implementation and queried on device give a first cross-implementation check
once Phase 1 embeds the same text with the Rust embedder.

## Scope

The sample proves one loop on one device: documents and their vectors land in
the embedded index on the phone, persist, and come back through lexical and
nearest-neighbour search. Nobody has run that on physical hardware.

In scope:

- The 25-opinion CourtListener fixture the Java sample uses
  (`protomolt/samples/src/main/resources/fixtures/court/opinions_sample.jsonl`).
- Ingest through the mobile ABI's mapped-ingest path. The device tests already
  wire an `embedding` field through it
  (`ProtomoltSearchDeviceTests.swift:157`), so the vector path is exercised;
  the app copies that wire setup rather than inventing one.
- A search screen per platform with the Java sample's two demos: a text query
  and a nearest-neighbour query.
- Index size on disk and first-run ingest time, reported in the UI.

Out of scope, explicitly:

- Collaborative or federated search. `device-shards.md` covers that, and it
  depends on this working first.
- Networking of any kind. The AAR ships without `INTERNET` permission and that
  guarantee stays.
- Changes to `crates/`, `mobile/apple/ProtomoltSearch.swift`,
  `mobile/android/.../ProtomoltSearch.java`, or `mobile.proto`.

## Build path

### Phase 0 — precomputed vectors, iOS

No embedder on the device, so no model, ownership, or FFI question applies. The
Java sample's nearest-neighbour demo already queries with a stored document
vector rather than embedded query text; Phase 0 does the same.

1. `scripts/build-engine.sh` → `ios/Frameworks/ProtomoltSearch.xcframework`,
   from the pinned engine revision. **Done 2026-09-20:** release link succeeds on
   Rust 1.92, about 1m 25s per target; device slice 60 MB unstripped.
2. Precomputed potion-512 vectors for the 25 opinions. **Done 2026-09-20:**
   `fixtures/court_opinions_potion512.ndjson`, produced by the Java sample with
   the same text window (title, newline, first 2,000 characters of body).
3. SwiftUI app under `ios/`. **Done 2026-09-20.** `ios/CourtSearchKit` is a Swift
   package holding the XCFramework binary target, checked-in SwiftProtobuf types
   (`scripts/generate-protos.sh`), the vendored Swift facade, a typed engine
   wrapper, and `CourtIndex`, an actor that keeps every blocking engine call off
   the main thread. `ios/CourtSearch` is the app, generated by `xcodegen` from
   `ios/project.yml`. The opinion schema is a real `proto/court/v1/court.proto`,
   so iOS, Android, and the probe plan from one descriptor.
4. Simulator: **done 2026-09-20** on iPhone 17. Physical iPhone: **first run
   2026-09-20 on an iPhone XR** (3 GB RAM), signed with a personal team. The
   `ios-arm64` slice linked and ran; the app container shows `court.tv.live`,
   `court.tv.segments/` and a populated `court.tv.wal/gen-000000/`, and the
   "habeas" query returned the expected two opinions on screen. Kill and relaunch
   reattaches to the stored index (`ingestSeconds=reopened`). The similar-opinions
   list (dense query) renders on the device; its exact order was not compared
   against the expected ranking by eye, which the simulator test asserts. Locking the
   phone and backgrounding the app, then returning, leaves search working. That
   covers resume only: the app writes to the index just once, during first-launch
   ingest, so a write or flush arriving while the device is locked (where iOS
   file protection could refuse it) is still untested. It becomes relevant when
   a sample ingests in the background.

   On-device ingest (plan + 25 opinions, about 1 MB of text with 512-dim vectors,
   + durable flush; release engine, debug Swift): **2.23 s** on the first launch
   after a fresh install, then **1.520 / 1.583 / 1.560 s** on three rebuilds via
   `-resetIndex YES`. Index size 5,998,953 bytes every time. Read from the host
   with `devicectl device process launch --console`; the app prints one
   `court-index …` line per open. This is, as far as the
   engine's docs record, its first execution on phone hardware.

Acceptance, all passing on the simulator through
`CourtSearchKitTests.testIngestSearchAndReopen` and again in the running app:
the text query for "habeas" returns the same two opinions as the Java sample,
the nearest-neighbour query returns the query document first with the Lucene
ranking behind it, and both survive close and reopen. The app reports 6 MB on
disk and, on relaunch, "reopened, nothing ingested."

One engine behaviour cost a test failure and is worth knowing: the index at
`court.tv` persists as an image plus sidecars (`court.tv.live`, `court.tv.wal`),
and `create` refuses to overwrite any of them. "Does the index exist" means
"does any file with that prefix exist", not "does `court.tv` exist".

Run it:

```bash
scripts/build-engine.sh                      # once, ~5 min
cd ios && xcodegen generate
xcodebuild test -scheme CourtSearchKit -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath .build/xcode               # from ios/CourtSearchKit
open CourtSearch.xcodeproj                    # launch argument `-query habeas` opens on results
```

### Verified wire sequence (host, 2026-09-20)

`tools/wire-probe` drives the same C ABI the phone uses, from Rust with real
protobuf types, against the fixture. It is the reference the Swift and Kotlin
apps reproduce. All three Phase 0 acceptance checks pass on the host:

| Check | Result |
| --- | --- |
| Text query "habeas" | Forsyth v. Spencer, United States v. Dowdell — same two, same order as the Java/Lucene sample |
| Nearest neighbours of opinion 0 | Davila-Gonzalez, Rodríguez-Vélez, De-la-Rosa-Ramos, Diaz, Ekasala — identical ranking to Lucene |
| Close and reopen | Same results from the reopened index |
| Index on disk | 3.5 MiB for 25 opinions with 512-dim vectors |

Scores agree across engines as well as ranks. Lucene reports cosine as
`(1 + cos) / 2`; converting back, Lucene's 0.7956, 0.7934, 0.7136, 0.7106 sit
beside the embedded engine's 0.7981, 0.7899, 0.7135, 0.7111. The residual is
TurboQuant quantization, and no calibration call was needed, which matters
because the mobile ABI does not expose one.

The sequence is `open(create)` → `planIndex` → `ingestMapped(bind + documents)`
→ `flush` → `query`. Four rules were learned from refusals, none of them
written down for the mobile path:

1. Every string field the plan lands must be declared on the shard
   (`bm25_fields`), or ingest refuses with `FAILED_PRECONDITION`.
2. The **first** `bm25_fields` entry is what an unqualified `LexicalQuery`
   searches. `["title", "body"]` silently returns nothing for body terms.
3. `MappedBind.field_analysis` must name every text path, body included, and is
   mutually exclusive with the legacy `analysis` field. The native analyzer has
   no default spec.
4. `expected_fingerprint` comes from `planIndex` on the same descriptor set. The
   device tests hardcode theirs; an app with its own schema plans first.

### Phase 1 — on-device embedder, owned by the sample

1. `embedder-ffi/`: a small crate with a C ABI over
   `protomolt-embedder` — load a model directory, embed a string, free a buffer.
   Built for the same three Apple targets and linked beside the engine.
2. Bundle the potion table and vocabulary. Free-text semantic query: embed on
   device, query by vector.
3. Compare on-device vectors for the 25 fixture texts against the Phase 0
   precomputed ones. Agreement within float tolerance is the first
   cross-implementation conformance evidence; disagreement is a finding to
   report, not to paper over.

### Phase 2 — Android

Export `ANDROID_HOME` or `ANDROID_NDK_HOME`, `scripts/build-android-aar.sh`,
then a Kotlin app under `android/` mirroring Phases 0 and 1. The
embedder FFI crate gains a JNI entry point or is reached through a thin JNI
shim in the sample.

## Risks and open questions

| Risk | Impact | Note |
| --- | --- | --- |
| Toolchain drift | Build differences from CI | Rust 1.92 vs pinned 1.98; NDK r28 vs pinned r29. `--locked` check passed on 1.92, release link untested until Phase 0 step 1 |
| Embedder is a spike | Correctness in Phase 1 | Conformance-tested against model2vec 0.9, never carried product load |
| Java/Rust vector agreement | Silent wrong results | Unproven; Phase 1 step 3 measures it |
| No physical-device run ever | Unknown unknowns | Harnesses exist, recorded results do not |
| Bundle size | Demo only | 123 MB table; product decision lives in `embedder-model-choice.md` |
| `device-shards.md` is stale | Planning | At least the PlanIndex row is out of date |

Open questions for review:

- If the sample's embedder FFI proves out, does it upstream as its own crate or
  fold into the embedded package?
- Should the Java `tokenizer.json` WordPiece gap be filed against `protomolt` or
  against the OpenNLP preview build?
