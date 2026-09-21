# Using Protomolt Search in a mobile app

A practical guide for putting [protomolt-search](https://github.com/ai-pipestream/protomolt-search)'s
embedded engine inside an iOS or Android app, written from building Court Search.
It covers the calls to make, the rules the engine enforces that its mobile
documentation does not yet spell out, what else the query call can do, how to add
search by meaning, what to expect
on real phones, and the problems you will hit with the workaround for each.

Everything here was observed on 2026-09-20 against the engine revision recorded in
`ios/CourtSearchKit/Sources/CourtSearchKit/Generated/ENGINE_REV`. Where the app
looks or behaves a certain way, [DESIGN.md](DESIGN.md) says why.

**Where to read code.** Each platform has the same three small files:

| | iOS (Swift) | Android (Kotlin) |
| --- | --- | --- |
| Typed calls over the engine's byte ABI | `ios/CourtSearchKit/…/SearchEngine.swift` | `android/…/SearchEngine.kt` |
| Open, plan, ingest, query | `…/CourtIndex.swift` | `…/CourtIndex.kt` |
| Text to vector | `…/Embedder.swift` | `…/Embedder.kt` |

`tools/wire-probe/src/main.rs` makes the same calls from Rust on your desk, with
real protobuf types and no phone. Start there if you want to experiment: it builds
in a minute and prints what the engine answers.

## How the engine is consumed

The engine ships as an XCFramework (iOS) or an AAR (Android) plus its protobuf
contracts. Every call takes one encoded protobuf message and returns one encoded
`MobileResponse`, which holds either a payload or an error. Nothing else crosses
the boundary, and errors never arrive as exceptions or panics.

- **iOS**: generate Swift types with SwiftProtobuf (`scripts/generate-protos.sh`;
  the output is checked in so the app builds without codegen tools) and call through
  the engine's small Swift facade.
- **Android**: let Gradle compile the contracts with the **lite** runtime (see
  *Problems and workarounds*), and call the engine's `ProtomoltSearch` JNI class.
- Every call blocks. Keep them off the main thread: an actor on iOS, a
  single-thread dispatcher on Android.

The engine asks for no network permission and links no networking code. Your app
inherits that for free as long as it adds none of its own.

## The five calls

`open(create)` → `planIndex` → `ingestMapped(bind + documents)` → `flush` → `query`.

1. **Open.** Give the shard a file path in your app's private storage and declare
   its fields. `create` for a new index, `open` for an existing one.
2. **Plan.** Send a `FileDescriptorSet` for your document's protobuf message. The
   engine derives an index plan from it and returns a fingerprint. Court Search
   describes an opinion in `proto/court/v1/court.proto` and ships the compiled
   descriptor as `fixtures/court.desc`, so every platform plans from the same bytes.
3. **Ingest.** One batch: a `bind` naming the message type and the fingerprint you
   just received, then each document as the serialized bytes of your own message.
   Vectors ride along as a repeated float field.
4. **Flush** to make it durable.
5. **Query.** A `LexicalQuery` for keywords, a `DenseQuery` for a vector.

## Rules to follow

Each of these was learned from a refusal, a build failure, or a silently wrong
answer. None is written down for the mobile path today.

1. **Declare every string field.** Each string field your plan lands must be listed
   on the shard (`bm25_fields`), or ingest refuses with `FAILED_PRECONDITION`.
2. **Put your main text field first in `bm25_fields`.** An unqualified
   `LexicalQuery` searches the *first* entry. With `["title", "body"]`, a word that
   appears only in bodies returns nothing, with no error. This is the one rule that
   fails silently.
3. **Give every text field an analyzer.** `MappedBind.field_analysis` must name
   every text path, the body included, and cannot be combined with the older
   `analysis` field. The on-device analyzer has no default, because its output is
   what gets persisted. Use the same spec at ingest and at query time.
4. **Plan before you bind.** The fingerprint comes from `planIndex` on the same
   descriptor. (The engine's own device tests hardcode one; with your own schema you
   must ask.) Planning is deterministic and cheap enough to repeat on every open.
5. **Ask for snippets only on keyword queries.** `QueryRequest.highlight` makes the
   engine cut passages and mark the matched words for you, with offsets in UTF-16
   units of the original text. On a vector query the engine refuses the whole
   request, so leave the field off. `profile = true` works on both and returns the
   engine's own timings; `executed` names the route it took.
6. **For search-as-you-type, send the text and a prefix together.**
   `LexicalQuery.prefixes` expands a prefix in a dictionary of *stems*, so a finished
   word sent only as a prefix ("sentencing") misses its own stem ("sentenc"). Send
   the full text, plus the last word again as a prefix; the engine scores the union.
7. **An index is a file and its sidecars.** `court.tv` persists alongside
   `court.tv.live`, `court.tv.segments/`, and `court.tv.wal/`, and `create` refuses
   to overwrite any of them. To ask "does my index exist", look for any file with
   that prefix.
8. **To highlight a whole document, ask the engine which words matched.** Request up
   to 64 snippets and collect the marked words: for `sentencing` you get "sentence",
   "sentenced", "sentencing". Highlight those and your app never has to stem. A form
   that occurs only beyond those snippets is missed.

## What else the `query` call reaches

Court Search uses a small part of what the mobile `query` call accepts. All of the
following are fields of the same `QueryRequest`, available from a phone today. The
page named beside each is in the engine's `docs/`.

| You want | Set | Engine doc |
| --- | --- | --- |
| Keyword and meaning in one query | `selection.composite`: several clauses with an operator and a scoring strategy, instead of a single `search` | `hybrid-retrieval.md` |
| Must, should, and must-not | `selection.boolean` | — |
| A phrase rather than separate words | `LexicalQuery.phrase` (`slop` for how loose) | `phrase-search.md`, `phrase-proximity.md` |
| "car" to also find "vehicle" | `LexicalQuery.synonyms`: rules supplied with the query | `synonyms.md` |
| Only some documents | `selection.filter.cel`: a CEL expression over stored columns; it combines with vector queries too | `cel-filters.md`, `vector-filters.md` |
| Counts beside the results | `aggregate`: aggregations, histograms, percentiles, `group_by` | `facets.md`, `range-facets.md` |
| Order by something other than relevance | `sort`, by column | — |
| More results without asking for all of them | `cursor`, fed from the response's `next_cursor` | — |
| One hit per group | `collapse`, by column | — |
| Field values back with each hit | `projections`: named expressions, returned in `QueryHit.projected` | `map-projection.md` |
| Relevance adjusted by stored values | `LexicalQuery.score_stages`, `boosts`, `scorer` | `score-functions.md` |
| Exact vector scores | `DenseQuery.score_mode = FP32_RERANK`; `execution_mode` chooses exact or approximate traversal | — |
| Why a hit scored as it did | `explain = true` | `explain.md` |
| Results as they are found | the `queryStream` calls instead of `query` | `embedded-mobile.md` |

Filters, facets, sorting, and projections work on columns, so the fields they use
must be part of the message you index and declared on the shard (facet, numeric,
or integer fields), the way rule 1 requires for text. Court Search indexes only an
opinion's id, title, body, and vector; its court, date, and judges are display
data the engine never sees.

Not reachable from the mobile calls today: term suggestions ("did you mean"). The
engine has them (`suggest.md`), as separate calls the mobile ABI does not expose.

## Adding search by meaning

The embedded engine ships no embedder on purpose: vectors are the caller's job. So
an app that wants to search by meaning brings its own way to turn text into
vectors, and must use the *same* model that produced the vectors in its index.

Court Search uses [`minishlab/potion-retrieval-32M`](https://huggingface.co/minishlab/potion-retrieval-32M),
a [Model2Vec](https://github.com/MinishLab/model2vec) static embedding model (512
dimensions, 123 MB, MIT). Static means no neural network runs: a table lookup per
word-piece, a mean, a normalization. That makes it practical on a phone, at the
cost of ignoring word order.

[`embedder-ffi/`](embedder-ffi) wraps the engine project's Rust crate
`protomolt-embedder` in four C functions and a JNI class: open a model directory,
embed text, count word-pieces, close. `scripts/build-embedder.sh` builds it for
both platforms in seconds. You can lift this crate into your own app as it is.

**The model is a file, not code.** `scripts/fetch-model.sh` downloads it; the build
copies it into the app. iOS maps it straight from the bundle. Android cannot map an
APK asset by path, so the app copies it into storage on first launch, which puts it
on the phone twice. Nothing is downloaded at run time.

**Does it give the same vectors as the server side?** Yes. The 25 vectors in this
sample's index were produced by a different implementation, the Java provider
(OpenNLP `StaticEmbeddingModel`). The Rust embedder, given the same model and text,
produces vectors identical in every component: max |Δ| = 0 over 25 × 512 floats,
with a negative control showing the comparison can see a difference.

| Where | How | Result |
| --- | --- | --- |
| Host | `cargo test` in `embedder-ffi`, through the C ABI | identical |
| iOS simulator | `CourtSearchKitTests`, model via `TEST_RUNNER_COURT_MODEL_DIR` | identical |
| iPhone XR (iOS 18.7, 3 GB) | at every app launch | identical |
| Pixel 11 Pro (Android 17) | instrumented test, and at every app launch | identical |

So you can build an index on a server in Java and query it from a phone in Rust.

**Explaining a vector hit.** A keyword hit explains itself with a snippet; a vector
hit gives you a score and nothing else. Because static embeddings are so cheap,
Court Search embeds every sentence of every opinion on the phone (4,075 of them),
and uses those to show the nearest sentence in each result and to shade the reading
view by closeness to the question. This explains the engine's ranking after the
fact; it does not change it.

Two things to know about the model:

- WordPiece never fails on a word. Gibberish is spelled out from single-letter
  pieces and still gets a vector, so you cannot count "unknown words". Court Search
  reports words *spelled out* in three or more pieces instead.
- If your document vectors cover only the start of each document (here: the title
  and first 2,000 characters), document-level similarity is coarse. Per-passage
  vectors in the index are the real fix.

## What to expect on a phone

Single debug-build runs (release engine, debug app code), not benchmarks.

| | iPhone XR (A12, 3 GB) | Pixel 11 Pro | iOS simulator (M2 host) |
| --- | --- | --- | --- |
| Build the index: plan, 25 documents (about 1 MB of text, 512-dim vectors), durable flush | 2.23 s first launch, then 1.52 / 1.58 / 1.56 s | 2.90 s | — |
| Index on disk | 5,998,953 bytes | 6.0 MB | 5,998,953 bytes |
| A keyword query, engine-reported time | not recorded | 5.5–11 ms | 1.8–17 ms |
| Load the 123 MB model | 0.19–0.42 s | 0.06–0.22 s | 0.50 s |
| Embed one question | about 0.03 ms | — | 0.03 ms |
| Embed 4,075 sentences | 0.12 s | 0.5 s warm, 2.7 s first run | 0.07 s |
| App size with the model | 173 MB | 157 MB APK | 173 MB |

The model costs less memory than its size suggests. On the Pixel it appears as
131 MB of *clean, file-backed* mapping (`dumpsys meminfo`: private clean 129 MB,
private dirty 4 KB): the kernel can evict and re-read it, and it is not heap. The
3 GB iPhone XR loads it in under half a second. Behaviour under real memory
pressure has not been exercised.

The index survives the app being killed and reopens without re-ingesting. On iOS,
returning from lock or background works. Not tested: a write or flush arriving
while an iPhone is locked, where file protection could refuse it. It matters if
your app ingests in the background.

## Are results the same on every device?

Rankings and keyword scores: yes. Vector scores: only between CPUs of the same
generation.

Every platform here can print a parity report: 11 fixed queries, scores as raw
32-bit patterns (`tools/parity_compare.py` diffs them).

| | macOS M2 (probe) | iOS simulator (M2) | Pixel 11 Pro | iPhone XR (A12, 2018) |
| --- | --- | --- | --- | --- |
| CPU `dotprod` / `i8mm` | yes / yes | yes / yes | yes / yes | **no / no** |
| Keyword (BM25) scores | reference | bit-identical | bit-identical | bit-identical |
| Vector scores | reference | bit-identical | bit-identical | **differ by up to 0.0032** |
| Rankings, 11 queries | reference | identical | identical | **4 differ** |

The vector engine (turbovec) picks its kernels at run time from two CPU features,
`dotprod` and `i8mm` (`pack.rs:1212`, `search.rs:2097`), and the two kernels do not
round alike. The iPhone XR's A12 has neither, so it takes the fallback path. Its
scores are as good an approximation of the true cosine as the others', just not the
same one: two opinions at 0.7135 and 0.7111 come back as 0.7142 and 0.7143 and swap
places (Lucene's exact values are 0.7136 and 0.7106).

What this means for you: on one device, nothing. **Do not compare or merge vector
scores computed on different devices**, and do not pin exact vector scores or
near-tie orderings in tests that run on more than one CPU generation. Plenty of
phones in use predate these instructions. An x86 host has not been compared.

## Problems and workarounds

| Problem | Workaround used here |
| --- | --- |
| The engine's `scripts/build-android-aar.sh` does not run on macOS: `${var^^}` needs bash 4 (macOS ships 3.2), and it looks for an NDK host directory `darwin-arm64` that does not exist (the NDK ships `darwin-x86_64` universal binaries) | `scripts/build-engine-android.sh`, a portable equivalent. arm64 only by default, which covers every phone and the emulators on an Apple Silicon Mac |
| Full `protobuf-java` cannot compile the engine's contracts: fields named `descriptor` generate a `getDescriptor()` that collides with the runtime's | `protobuf-javalite`, which has no descriptors, and is the smaller runtime Android wants anyway |
| Gradle fails with "Missing output directives" | Android projects get no default protoc output; declare `builtins { create("java") { option("lite") } }` |
| Linking a second Rust library into an iOS app | Two Rust static libraries link together fine. Two XCFrameworks that each put `module.modulemap` at their Headers root collide; put one's headers under `Headers/<ModuleName>/` |
| `./gradlew connectedDebugAndroidTest` reports FAILED after every test passed (seen with AGP 9.4.1 over wireless adb: its post-run pull of extra test output fails, and disabling that collection breaks the task) | `scripts/test-android.sh` runs the instrumentation directly and reports what the device says |
| The Java Model2Vec provider cannot load `potion-retrieval-32M` as published: without a `vocab.txt` it reads `tokenizer.json` as a Unigram model and fails | `scripts/fetch-model.sh` derives `vocab.txt` from `tokenizer.json`. The Rust embedder does not need it |
| Court opinions from PDF are double-spaced: every line is followed by a blank line, page markers ("-7-") sit in the flow, and "v.", "F.3d", "St. Paul" are full of periods that end nothing | `tools/passages_reference.py` is one specification for cutting the text into paragraphs and sentences; the Swift and Kotlin ports reproduce its 4,075 units exactly and the tests assert the count |
| Toolchain versions | The engine's CI pins Rust 1.98 and NDK r29; release builds succeeded here on Rust 1.92 and NDK r28 |

## Not covered

- Per-passage vectors in the index itself (the engine's chunk role), so the engine
  ranks by passage rather than the app explaining hits afterwards.
- A parity run on an x86 host; behaviour under memory pressure; writes while an
  iPhone is locked.
- Collaborative search across phones, which the engine project proposes in its
  `docs/device-shards.md`. That design depends on what this sample shows working.

## For the engine's maintainers

Things this sample works around that would be better fixed at the source: the
turbovec fallback kernel's rounding (or a documented statement that score identity
holds per CPU feature set); the two macOS problems in `build-android-aar.sh`; the
`descriptor` field names, or a note that Java consumers need the lite runtime; a
protoc output in `mobile/android/device-tests`; a mobile-path page covering the
rules above, rule 2 most of all; the stale PlanIndex row in `device-shards.md`
(`protomolt_search_plan_index` exists); WordPiece support for `tokenizer.json` in
the Java Model2Vec loader; and, in `protomolt-embedder`, loading from a file
descriptor with an offset (so Android can map the model from the APK) and a batch
embed call.
