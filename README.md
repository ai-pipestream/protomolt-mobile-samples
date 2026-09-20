# protomolt-mobile-samples

A small phone app that searches court opinions **entirely on the device**: no
server, no network, no account. It exists to answer one question about
[protomolt-search](https://github.com/ai-pipestream/protomolt-search), the
Pipestream search engine: *does the embedded engine really work as the search
backend of an ordinary iOS or Android app?* The engine's own docs say nobody has
run it on a phone yet. This sample is that run.

## What the app does

The app ships with 25 real federal court opinions (from CourtListener). On first
launch it builds a private search index inside the app's own storage. After
that you can do two things:

| Feature | What you do | What happens |
| --- | --- | --- |
| **Keyword search** | Type `habeas` | Opinions whose text contains the word, best match first (BM25 ranking, the same family of scoring a web search engine uses for words) |
| **Similar opinions** | Tap any opinion | The opinions closest in *meaning* to it, even where they share few exact words |

"Similar opinions" works because every opinion comes with an **embedding**: a
list of 512 numbers that places its text in a space where related documents sit
near each other. Finding similar opinions is finding the nearest points.

The index survives closing the app. Relaunch and it reopens what it built
instead of building again. The app asks for no network permission, and the
engine it links contains no networking code at all.

## What we are building, in order

**Phase 0 — the engine runs in an app.** *Running on a physical iPhone XR;
Android builds and awaits its first run.* Keyword search and similar-opinions, as above. The embeddings for
the 25 opinions were computed ahead of time on a laptop and ship with the app,
so the phone only has to store and search them.

**Phase 1 — search by meaning, typed by you.** Today you can only find
neighbours of an opinion that is already in the index, because the phone has no
way to turn *new* text into an embedding. Phase 1 adds that: a small on-device
embedding model, so you can type "police searched the car without a warrant"
and get relevant opinions even if none uses those words. It also lets us check
that the phone computes the same embeddings the laptop did — the first evidence
that two independent implementations of the model agree.

**Phase 2 — the same on the other platform,** sharing one schema, one fixture,
and one set of expected results, so iOS and Android are provably the same app.

## What this is not

- **Not a product.** 25 opinions, a plain list UI. It is a proof and a
  reference, not a legal research tool.
- **Not the collaborative search** described in the engine's
  `docs/device-shards.md`, where phones answer shared queries. That depends on
  this working first.
- **Not a fork of the engine.** Nothing here changes `protomolt-search`. The app
  consumes it the way any outside developer would: a built XCFramework or AAR
  plus its protobuf contracts. Logic that only a mobile app needs lives here
  first; whether any of it moves into the engine is a later decision.

## Status

| | Builds | Acceptance test | Real device |
| --- | --- | --- | --- |
| Host reference (`tools/wire-probe`) | yes | passes | n/a |
| iOS | yes | passes on iPhone 17 simulator | iPhone XR: index built on device in ~1.5 s, keyword search correct, similar opinions shown, survives kill and relaunch |
| Android | yes (arm64, protobuf lite) | written, not yet run | not yet run |

The acceptance test is the same everywhere: ingest the 25 opinions, check that
`habeas` returns *Forsyth v. Spencer* then *United States v. Dowdell*, check the
five nearest neighbours of the first opinion, close, reopen, check again. Those
expected results match an independent implementation, the Java/Lucene court
sample in `ai-pipestream/protomolt`.

## Try it

Needs a checkout of `protomolt-search` next to this repository, Rust with the
iOS/Android targets, and Xcode or the Android SDK + NDK.

```bash
# iOS
scripts/build-engine.sh                     # engine → ios/Frameworks (about 5 min, once)
cd ios && xcodegen generate && open CourtSearch.xcodeproj
#   run the CourtSearch scheme; launch argument `-query habeas` opens on results

# Host reference: the same engine calls from Rust, no phone involved
cd tools/wire-probe && cargo run -- ../../fixtures/court_opinions_potion512.ndjson habeas
```

## Layout

```
PLAN.md        the engineering plan, findings, and the engine rules learned the hard way
proto/         court.proto — the opinion schema every platform plans its index from
fixtures/      the 25 opinions with precomputed embeddings; court.desc
tools/wire-probe/   host-side reference for the engine's mobile byte ABI
ios/           CourtSearchKit (Swift package: engine + index) and the CourtSearch app
android/       the Android app (in progress)
scripts/       build the engine, regenerate protobuf types, fetch the Phase 1 model
```

`PLAN.md` is the document for engineers: what was verified, what the engine
refuses and why, and the open questions for review.
