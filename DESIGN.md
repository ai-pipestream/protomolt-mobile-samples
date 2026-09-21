# Court Search — shared UX spec

One design, two implementations (SwiftUI, Jetpack Compose). Where the platforms
disagree, this document wins; native controls and system type are used to carry
it out, not to reinterpret it.

## What the app is

A legal research tool in miniature, with a window onto the engine underneath.
Its two audiences are served by two voices that never mix:

| Voice | Carries | Type | Colour |
| --- | --- | --- | --- |
| The reporter | Case names, citations, opinion text, snippets | System serif (New York / Noto Serif) | Oxblood accent `#7A1F2B` (dark mode `#EE8F9C`) |
| The engine | Timings, counts, index facts | System sans, tabular figures | Slate surface `#E9EEF3` / `#1B2430`, slate ink `#33475B` / `#A9BDD1` |

The one loud element is the **highlighter**: matched words in a snippet sit on
`#FFE066`, like a marked-up brief. In dark mode it stays a highlighter rather
than dimming to a tint: `#F2C94C` with the marked words in dark ink `#1F1A00`.
Suggestion chips are a wash of the accent, 9% in light and 18% in dark so the
shape still reads on black. Nothing else is
decorated. Backgrounds are the system's own, in light and dark.

## Content rules

- A case is always shown as a citation: the caption in serif italic, then
  `No. 08-1855 (1st Cir. Feb. 12, 2010)` with Bluebook month abbreviations
  (Jan. Feb. Mar. Apr. May June July Aug. Sept. Oct. Nov. Dec.).
- The panel reads `Before Torruella, Selya, Howard`; the author reads
  `Howard, J.`. Either is omitted when the source has none. `Unpublished` is
  shown; `Published` is the norm and is not.
- Snippets come from the engine (`QueryRequest.highlight`, window mode, one
  snippet of 180 UTF-16 units). Whitespace runs collapse to one space; an
  ellipsis marks each cut edge. The app never computes its own snippet.
- Scores are shown to three decimals in the engine voice.

## Screens

**Search.** Title, then the search field at the top (never the bottom), then the
engine strip, then one of three states.

*Start* (empty query) — not a list. A short invitation: "Search the opinions",
one sentence on what to type and that it runs on the phone, then the suggestions
as tappable chips, then the way into similarity search ("Open an opinion to see
the ones closest to it in meaning") with **Browse all 25 opinions**. The full
list lives behind Browse; it is a directory, not a landing page.

*Results* — appear as you type, 200 ms after the last keystroke, replacing the
start card. Type-ahead is real: the last word is also sent to the engine as a
prefix, so `hab` already finds the habeas opinions.

*No matches* — "No opinion has a word starting with "{query}"", one sentence
that search reads opinion text and not case names, and the suggestions again.
An empty screen is an invitation to act.

Suggestions, identical on every platform, each verified to return results in
the bundled corpus and chosen to span areas of law: habeas, sentencing,
conspiracy, insurance, maritime, arbitration, forfeiture, qualified immunity.

**Keyword / Meaning.** When an embedding model is bundled, a two-segment switch
sits between the search field and the engine strip. Keyword is everything above.
Meaning embeds the typed text on the phone and finds the nearest opinions: the
prompt becomes "Describe what you are looking for", the start card explains it in
one sentence and offers questions instead of words (insurance company refused to
pay the claim; contract dispute sent to arbitration; deported despite fear of
persecution; the prison sentence was too long; fired after complaining about
discrimination). Nothing was matched word for word, so a Meaning hit explains
itself differently: the **nearest sentence** of the opinion, set like a snippet
with a highlighter rule at its left edge; **Closest words**, the two words of the
question nearest the opinion; and a small **similarity bar** beside the score,
relative to the best hit on screen. Its one empty state is text with no
vector: "None of those words are in the model's vocabulary." Without a model the
switch is absent and nothing else changes.

**Engine strip.** One slate row under the search field, always visible:
engine time of the last query, `{hits} of {documents}`, the route in plain words
(`Keyword` for `bm25_search`, `Similarity` for `search`), and `On device`.
Beside the start card it shows index facts instead: the strip always describes
what is on screen, never a query that has since been cleared. Tapping it opens the engine panel.

**Engine panel** (sheet). Three groups, engine voice throughout:
*Last query* — route as the engine names it, engine time, selection time,
round trip (app-measured, includes protobuf and the FFI hop), hits, segments,
shards, and for a Meaning query: time to embed the question, its words and
WordPiece pieces, any words the model had to spell out, and the best and last
similarity shown. The strip, for a Meaning query, shows both costs: embed and
search. A dense query
returns a top-k, so the strip says `Top 8 nearest`, never `8 of 25`.
*Embedder* (when bundled) — model, dimensions, load time, and "Against Java
vectors: identical, 25 of 25", the launch-time conformance check. *Index* — opinions, vectors × dimensions, on disk, built in / reopened
from disk, plan fingerprint (first 12 hex). *Privacy* — "No network permission.
The engine links no networking code."

**Heatmap.** Opening a Meaning hit shades the opinion's text by closeness to the
question, sentence by sentence: only the top fifth is shaded, from a faint wash to
the full highlighter (dark ink once the wash is strong). A slate note above the
text names the question and offers **Jump to the closest passage**. Keyword mode
and similar-opinion navigation show no heat. The highlighter therefore means one
thing everywhere: *this is where your query landed*, exact in Keyword, graded in
Meaning. Keyword mode marks the reading view too: every whole-word occurrence of a
form the engine matched, at full strength.

**Navigator.** Whenever the reading view has marks, a small control floats at the
bottom right: up, a counter ("3 of 33"), down. It steps through the marks in
reading order, wraps at the ends, lands each mark clear of the bars, and
underlines the current one at full strength. The slate note above the text says
what is marked and how many there are.

**Washes in the dark.** A graded wash never changes the ink. Over black, a partial
amber wash is a mid brown and dark ink on it is unreadable, so shaded text keeps
its own colour and the wash is capped (0.42 dark, 0.75 light). Dark ink is for the
full-strength highlighter only: keyword matches, snippets, the current mark.

**Opinion.** Caption, citation, panel, author; *Similar opinions* (nearest
neighbours of this opinion's vector, similarity to three decimals); then the
full opinion text in serif, paragraphs reflowed, measure capped for reading.
Opening an opinion updates the engine strip with the similarity query's numbers.

## Motion

Motion answers an action and nothing else: results and the start card cross
over when the query changes, the engine strip's figures roll to their new
values, a suggestion chip gives a selection haptic. No entrance animations.

## Behaviour both platforms share

- Every engine call runs off the main thread.
- Launch arguments: `query` opens on results; `resetIndex` re-ingests; `mode
  meaning`; `open N` lands on the Nth result's reading view; `jump` scrolls it to
  the closest passage; `parity` prints the cross-platform report.
- Text is cut into paragraphs and sentences exactly as
  `tools/passages_reference.py` does.
- One `court-index …` line on stdout/logcat per index open.
