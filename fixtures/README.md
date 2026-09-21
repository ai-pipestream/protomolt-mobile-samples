# Fixtures

`court_opinions_potion512.ndjson` — 25 CourtListener opinions with document-level
embeddings, one JSON object per line: `doc_id`, `title`, `body`, `language`,
`source_uri`, `document_type`, `author`, `embedding` (512 floats, unit length),
`embedding_dims`.

Produced by the Java court sample in `ai-pipestream/protomolt` at `dc49800cd66950356a883d99141e00427df6ad65`:

    ./gradlew :samples:runCourtDocIndex -Pmodel2vec=<potion-retrieval-32M dir>

Model: `minishlab/potion-retrieval-32M` (MIT). Embedded text is the title, a
newline, and the first 2,000 characters of the body. The vectors come from the
OpenNLP Model2Vec provider, not from `protomolt-embedder`. The two agree bit for
bit (GUIDE.md), which the apps re-check at every launch. The provider needs a `vocab.txt` derived from the model's `tokenizer.json`
(see GUIDE.md, "Problems and workarounds").
