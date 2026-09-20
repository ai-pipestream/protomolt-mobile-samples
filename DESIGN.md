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

**Engine strip.** One slate row under the search field, always visible:
engine time of the last query, `{hits} of {documents}`, the route in plain words
(`Keyword` for `bm25_search`, `Similarity` for `search`), and `On device`.
Beside the start card it shows index facts instead: the strip always describes
what is on screen, never a query that has since been cleared. Tapping it opens the engine panel.

**Engine panel** (sheet). Three groups, engine voice throughout:
*Last query* — route as the engine names it, engine time, selection time,
round trip (app-measured, includes protobuf and the FFI hop), hits, segments,
shards. *Index* — opinions, vectors × dimensions, on disk, built in / reopened
from disk, plan fingerprint (first 12 hex). *Privacy* — "No network permission.
The engine links no networking code."

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
- Launch arguments: `query` opens on results; `resetIndex` re-ingests.
- One `court-index …` line on stdout/logcat per index open.
