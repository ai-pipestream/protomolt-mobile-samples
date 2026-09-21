import Foundation
import SwiftProtobuf

/// One opinion from the bundled fixture. `embedding` is the precomputed
/// potion-retrieval-32M document vector (512 floats, unit length).
public struct Opinion: Decodable, Sendable, Identifiable {
    public let id: String
    public let title: String
    public let body: String
    public let sourceURI: String
    public let dateFiled: String
    public let docketNumber: String
    public let court: String
    public let judges: String
    public let author: String
    public let status: String
    public let embedding: [Float]

    enum CodingKeys: String, CodingKey {
        case id = "doc_id", title, body, sourceURI = "source_uri", dateFiled = "date_filed"
        case docketNumber = "docket_number", court, judges, author, status, embedding
    }
}

/// What the embedder made of a question: the dense-query counterpart of the
/// keyword path's snippet and match count.
public struct EmbeddingStats: Sendable {
    public let milliseconds: Double
    /// WordPiece pieces the model saw, `[UNK]`s included.
    public let pieces: Int
    public let words: Int
    /// Words the model has no entry for and had to spell out in three or more
    /// pieces. WordPiece never gives up on a word (it falls back to single
    /// letters), so "unknown" is not a useful idea here; "spelled out" is.
    public let spelledOutWords: [String]
    public let dimensions: Int
}

/// A sentence's place in an opinion: which paragraph, which sentence of it.
public struct PassageLocation: Sendable, Equatable {
    public let paragraph: Int
    public let sentence: Int
}

/// The sentence of an opinion nearest a question, and how near.
public struct Passage: Sendable {
    public let text: String
    public let location: PassageLocation
    public let similarity: Float
}

/// Per-sentence closeness of one opinion to the last Meaning question: where in
/// the document the meaning was found. `similarities[paragraph][sentence]`;
/// units too short to carry meaning (headings, signature lines) have no value.
public struct Heat: Sendable {
    public let question: String
    public let similarities: [[Float?]]
    public let hottest: PassageLocation?
}

public struct SearchHit: Sendable, Identifiable {
    public let opinion: Opinion
    public let score: Float
    /// Meaning hits only: the question's words whose own vectors lie nearest this
    /// opinion's, nearest first. The dense counterpart of a highlighted snippet:
    /// it says which of your words pulled this opinion in.
    public var closestWords: [String] = []
    /// Meaning hits only: the paragraph nearest the question.
    public var passage: Passage?
    /// Present on keyword hits only: the engine cuts snippets for a lexical
    /// selection and refuses the request on any other shape.
    public let snippet: Snippet?
    /// Every snippet the engine cut for this hit; one, unless more were asked for.
    public var snippets: [Snippet] = []
    public var id: String { opinion.id }
}

/// What the engine reported about one query, plus the app-measured round trip.
public struct QueryStats: Sendable {
    /// The route as the engine names it: `bm25_search`, `search`.
    public let route: String
    public let engineMilliseconds: Double
    public let selectionMilliseconds: Double
    /// Wall time around the call: protobuf encode/decode and the FFI hop included.
    public let roundTripMilliseconds: Double
    public let hits: Int
    public let segments: Int
    public let shards: Int
    /// Present on Meaning queries: what the embedder made of the question.
    public var embedding: EmbeddingStats?
    /// Highest and lowest similarity among the returned hits.
    public var topScore: Float = 0
    public var lowScore: Float = 0
}

public struct SearchResult: Sendable {
    public var hits: [SearchHit]
    public var stats: QueryStats
}

/// The on-device embedder, when a model is bundled. `fixtureWorstDelta` is the
/// largest per-component difference between this phone's vectors for the 25
/// fixture texts and the vectors the Java implementation produced: 0 means the
/// two implementations agree bit for bit on this hardware.
public struct EmbedderStats: Sendable {
    public let dimensions: Int
    public let loadSeconds: Double
    public let fixtureWorstDelta: Float
    public let fixtureSeconds: Double
}

public struct IndexStats: Sendable {
    public let documents: Int
    public let vectorDimensions: Int
    public let bytesOnDisk: Int64
    /// Nil when the index already existed and nothing was ingested this launch.
    public let ingestSeconds: Double?
    public let planFingerprint: String
}

/// The court sample's private on-device index: plan, mapped ingest, flush, and
/// the two demo queries, in the sequence tools/wire-probe verified on the host.
public actor CourtIndex {
    public nonisolated let opinions: [Opinion]
    /// `[opinion][paragraph][sentence]`, cut once: the heatmap's units.
    public nonisolated let sentences: [[[String]]]
    public private(set) var stats: IndexStats
    /// Nil when no model is bundled: search by meaning is then unavailable.
    public nonisolated let embedderStats: EmbedderStats?
    private let embedder: Embedder?
    /// One vector per sentence, `[opinion][paragraph][sentence]`, built once.
    private var passageVectors: [[[[Float]?]]]?
    public private(set) var passageSeconds: Double?
    public private(set) var passageCount = 0
    private var lastQuestion: (text: String, vector: [Float])?
    private let engine: SearchEngine
    private let directory: URL

    private static let messageType = "court.v1.Opinion"

    /// whitespace tokenizer, Porter stemmer, full term vectors from normalized
    /// stems, and the strip-invisible / whitespace / accent-fold / full-case-fold
    /// char filters: the engine's `body_spec()`. The native analyzer has no
    /// default, and ingest and query must use the same spec.
    private static var bodySpec: Ai_Protomolt_Search_V1_AnalysisSpec {
        var spec = Ai_Protomolt_Search_V1_AnalysisSpec()
        spec.tokenizer = 1
        spec.stemmer = 2
        spec.termVectorMode = 1
        spec.termVectorSource = 3
        spec.charFilters = [1, 2, 15, 6]
        return spec
    }

    /// Opens the index under `directory`, creating and filling it from the
    /// bundled fixture on first use. With a `modelDirectory`, also loads the
    /// embedder and checks it against the fixture's vectors.
    public init(directory: URL, modelDirectory: URL? = nil) throws {
        self.directory = directory
        let loadedOpinions = try Self.loadFixture()
        opinions = loadedOpinions
        sentences = loadedOpinions.map(\.sentences)
        if let modelDirectory {
            var start = Date()
            let loaded = try Embedder(directory: modelDirectory)
            let loadSeconds = Date().timeIntervalSince(start)
            start = Date()
            var worst: Float = 0
            for opinion in opinions {
                guard let vector = try loaded.embed(Self.embedText(opinion)) else { continue }
                worst = max(worst, zip(vector, opinion.embedding).map { abs($0 - $1) }.max() ?? 0)
            }
            embedder = loaded
            embedderStats = EmbedderStats(dimensions: loaded.dimensions, loadSeconds: loadSeconds,
                                          fixtureWorstDelta: worst, fixtureSeconds: Date().timeIntervalSince(start))
        } else {
            embedder = nil
            embedderStats = nil
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let path = directory.appendingPathComponent("court.tv").path
        // The engine persists an image plus sidecars (court.tv.live, court.tv.wal, …),
        // and `create` refuses to overwrite any of them, so existence means "any file
        // of this index", not the image alone.
        let exists = try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .contains { $0.hasPrefix("court.tv") }

        var shard = Ai_Protomolt_Search_Mobile_V1_MobileShardConfig()
        shard.indexPath = path
        shard.facetFields = ["id"]
        // Order matters: the FIRST bm25 field is what an unqualified LexicalQuery
        // searches. Every string field the plan lands must be declared here.
        shard.bm25Fields = ["body", "title"]
        var open = Ai_Protomolt_Search_Mobile_V1_MobileOpenRequest()
        open.shards = [shard]

        engine = try SearchEngine(open: open, create: !exists)
        let descriptorSet = try Self.resource("court", "desc")
        // Planning is deterministic and cheap; doing it on every open keeps the
        // fingerprint on hand for the engine panel, not only on first launch.
        let fingerprint = try Self.plan(descriptorSet, engine).fingerprint
        var seconds: Double?
        if !exists {
            let start = Date()
            try Self.ingest(opinions, descriptorSet: descriptorSet, fingerprint: fingerprint, into: engine)
            seconds = Date().timeIntervalSince(start)
        }
        stats = IndexStats(documents: opinions.count, vectorDimensions: opinions.first?.embedding.count ?? 0,
                           bytesOnDisk: Self.bytes(under: directory), ingestSeconds: seconds, planFingerprint: fingerprint)
    }

    /// BM25 over opinion bodies, with type-ahead: the last word is also sent as a
    /// prefix. The term dictionary holds stems, so the text leg covers a finished
    /// word ("sentencing" → "sentenc") and the prefix leg an unfinished one
    /// ("hab" → "habeas"); the engine scores the union.
    public func search(text: String, limit: Int = 5) throws -> SearchResult {
        try run(lexical(text), limit: limit, snippets: 1)
    }

    /// The exact words of `opinion` that a keyword query matched, as the engine
    /// marked them: "sentence", "sentenced", "sentencing" for `sentencing`. The
    /// reading view highlights these. They come from the engine's own highlights
    /// (up to 64 snippets of this opinion), so the app never stems or guesses; a
    /// form that appears only beyond those snippets is missed.
    public func matchedForms(in opinion: Opinion, for text: String) throws -> [String] {
        let result = try run(lexical(text), limit: opinions.count, snippets: 64)
        guard let hit = result.hits.first(where: { $0.id == opinion.id }) else { return [] }
        var seen = Set<String>()
        return hit.snippets.flatMap(\.runs).filter(\.highlighted)
            .map { $0.text.trimmingCharacters(in: .whitespaces).lowercased() }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    private func lexical(_ text: String) -> Ai_Protomolt_Search_V1_SearchQuery {
        var lexical = Ai_Protomolt_Search_V1_LexicalQuery()
        lexical.text = text
        lexical.analysis = Self.bodySpec
        if let last = text.split(whereSeparator: \.isWhitespace).last, text.last?.isWhitespace == false {
            var prefix = Ai_Protomolt_Search_V1_TermPrefix()
            prefix.prefix = String(last)
            prefix.maxExpansions = 32
            lexical.prefixes = [prefix]
        }
        var search = Ai_Protomolt_Search_V1_SearchQuery()
        search.id = "lexical"
        search.lexical = lexical
        return search
    }

    /// Search by meaning: embed the text on the device, query by vector. The
    /// question need not share a word with the opinions it finds.
    public func search(meaning text: String, limit: Int = 5) throws -> SearchResult {
        guard let embedder else {
            throw EngineError(operation: "search(meaning:)", code: -1, message: "no embedding model is bundled")
        }
        let start = DispatchTime.now().uptimeNanoseconds
        let vector = try embedder.embed(text)
        let embedMilliseconds = Double(DispatchTime.now().uptimeNanoseconds - start) / 1e6

        // Each word on its own: one the model cannot place has no vector, and the
        // ones it can place are compared with every hit below.
        let words = Self.words(of: text)
        var wordVectors: [(word: String, vector: [Float])] = []
        for word in words {
            if let own = try embedder.embed(word) { wordVectors.append((word, own)) }
        }
        let embedding = EmbeddingStats(milliseconds: embedMilliseconds, pieces: embedder.pieces(text), words: words.count,
                                       spelledOutWords: words.filter { embedder.pieces($0) >= 3 },
                                       dimensions: embedder.dimensions)
        guard let vector else {
            // No word of the text is in the model's vocabulary: it has no vector,
            // and the engine refuses zero vectors, so there is nothing to ask.
            var stats = QueryStats(route: "search", engineMilliseconds: 0, selectionMilliseconds: 0,
                                   roundTripMilliseconds: 0, hits: 0, segments: 0, shards: 0)
            stats.embedding = embedding
            return SearchResult(hits: [], stats: stats)
        }
        var dense = Ai_Protomolt_Search_V1_DenseQuery()
        dense.vector = vector
        var search = Ai_Protomolt_Search_V1_SearchQuery()
        search.id = "dense"
        search.dense = dense
        var result = try run(search, limit: limit, snippets: 0)
        result.stats.embedding = embedding
        lastQuestion = (text, vector)
        let passages = try preparePassages()

        // Function words sit near everything and explain nothing.
        let content = wordVectors.filter { !Self.functionWords.contains($0.word) }
        result.hits = result.hits.map { hit in
            var scored: [(word: String, similarity: Float)] = []
            for entry in content {
                var similarity: Float = 0
                for (a, b) in zip(entry.vector, hit.opinion.embedding) { similarity += a * b }
                if similarity > 0 { scored.append((entry.word, similarity)) }
            }
            scored.sort { $0.similarity > $1.similarity }
            var explained = hit
            explained.closestWords = scored.prefix(2).map(\.word)
            if let row = opinions.firstIndex(where: { $0.id == hit.opinion.id }) {
                let heat = Self.similarities(of: passages[row], to: vector)
                if let best = heat.hottest, let similarity = heat.values[best.paragraph][best.sentence] {
                    explained.passage = Passage(text: sentences[row][best.paragraph][best.sentence],
                                                location: best, similarity: similarity)
                }
            }
            return explained
        }
        return result
    }

    /// The cross-platform parity report: the fixed query set of tools/wire-probe
    /// `--parity`, one line, scores as raw f32 bit patterns so a single differing
    /// bit shows. macOS, iOS, and Android should print the same line.
    public func parityReport() throws -> String {
        let keywords = ["habeas", "hab", "sentencing", "qualified immun", "maritime"]
        let questions = ["insurance company refused to pay the claim", "contract dispute sent to arbitration",
                         "deported despite fear of persecution", "the prison sentence was too long",
                         "fired after complaining about discrimination"]
        func entry(_ key: String, _ hits: [(row: Int, score: Float)]) -> String {
            key + "=" + hits.map { "\($0.row):" + String(format: "%08x", $0.score.bitPattern) }.joined(separator: ",")
        }
        func rows(_ result: SearchResult) -> [(row: Int, score: Float)] {
            result.hits.map { hit in (opinions.firstIndex { $0.id == hit.opinion.id } ?? -1, hit.score) }
        }
        var entries: [String] = []
        for text in keywords { entries.append(entry("k:\(text)", rows(try search(text: text, limit: 25)))) }
        if embedder != nil {
            for text in questions { entries.append(entry("m:\(text)", rows(try search(meaning: text, limit: 8)))) }
        }
        entries.append(entry("s:0", rows(try neighbours(of: opinions[0], limit: 6))))
        return "court-parity " + entries.joined(separator: ";")
    }

    /// Where in `opinion` the last Meaning question found its meaning; nil before
    /// any Meaning query.
    public func heat(for opinion: Opinion) throws -> Heat? {
        guard let lastQuestion, let row = opinions.firstIndex(where: { $0.id == opinion.id }) else { return nil }
        let heat = Self.similarities(of: try preparePassages()[row], to: lastQuestion.vector)
        return Heat(question: lastQuestion.text, similarities: heat.values, hottest: heat.hottest)
    }

    /// Embeds every sentence of every opinion, once. Static embeddings make this
    /// cheap enough to do on the phone: no forward pass, a table lookup and a mean.
    @discardableResult
    public func preparePassages() throws -> [[[[Float]?]]] {
        if let passageVectors { return passageVectors }
        guard let embedder else { return [] }
        let start = Date()
        var count = 0
        let vectors: [[[[Float]?]]] = try sentences.map { opinion in
            try opinion.map { paragraph in
                try paragraph.map { sentence in
                    // Headings, signature lines, and stray fragments carry no meaning
                    // worth shading, and their tiny vectors are noisy.
                    guard sentence.unicodeScalars.count >= Segmenter.minimumUnit else { return nil }
                    count += 1
                    return try embedder.embed(sentence)
                }
            }
        }
        passageVectors = vectors
        passageCount = count
        passageSeconds = Date().timeIntervalSince(start)
        return vectors
    }

    private static func similarities(of paragraphs: [[[Float]?]], to question: [Float]) -> (values: [[Float?]], hottest: PassageLocation?) {
        var hottest: PassageLocation?
        var best = -Float.infinity
        var values: [[Float?]] = []
        for (p, paragraph) in paragraphs.enumerated() {
            var row: [Float?] = []
            for (n, vector) in paragraph.enumerated() {
                guard let vector else { row.append(nil); continue }
                var similarity: Float = 0
                for (a, b) in zip(vector, question) { similarity += a * b }
                row.append(similarity)
                if similarity > best { best = similarity; hottest = PassageLocation(paragraph: p, sentence: n) }
            }
            values.append(row)
        }
        return (values, hottest)
    }

    /// Lowercased words, punctuation trimmed, in order, without repeats.
    static func words(of text: String) -> [String] {
        var seen = Set<String>()
        return text.lowercased().split(whereSeparator: \.isWhitespace)
            .map { $0.trimmingCharacters(in: .punctuationCharacters) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    private static let functionWords: Set<String> = ["a", "an", "the", "of", "to", "in", "on", "at", "by", "for", "with",
        "about", "after", "before", "despite", "while", "and", "or", "but", "was", "were", "is", "are", "be", "been",
        "it", "its", "this", "that", "too", "very", "not", "no", "from", "as", "into", "over", "under"]

    /// The Java sample's embedding input, reproduced exactly: title, newline, and
    /// `body.substring(0, 2000)`, which counts UTF-16 code units.
    static func embedText(_ opinion: Opinion) -> String {
        "\(opinion.title)\n\(String(decoding: Array(opinion.body.utf16.prefix(2000)), as: UTF16.self))"
    }

    /// Nearest neighbours of a stored opinion's own vector.
    public func neighbours(of opinion: Opinion, limit: Int = 5) throws -> SearchResult {
        var dense = Ai_Protomolt_Search_V1_DenseQuery()
        dense.vector = opinion.embedding
        var search = Ai_Protomolt_Search_V1_SearchQuery()
        search.id = "dense"
        search.dense = dense
        return try run(search, limit: limit, snippets: 0)
    }

    public func close() throws {
        try engine.flush()
        try engine.close()
    }

    private func run(_ search: Ai_Protomolt_Search_V1_SearchQuery, limit: Int, snippets: Int) throws -> SearchResult {
        var selection = Ai_Protomolt_Search_V1_SelectionQuery()
        selection.search = search
        var request = Ai_Protomolt_Search_V1_QueryRequest()
        request.requestID = "court-sample"
        request.k = UInt32(limit)
        request.selectionK = UInt32(limit)
        request.selection = selection
        request.profile = true
        if snippets > 0 {
            var highlight = Ai_Protomolt_Search_V1_HighlightSpec()
            highlight.maxSnippets = UInt32(snippets)
            highlight.maxChars = 180
            highlight.mode = .window
            request.highlight = highlight
        }
        let start = DispatchTime.now().uptimeNanoseconds
        let response = try engine.query(request)
        let roundTrip = Double(DispatchTime.now().uptimeNanoseconds - start) / 1e6

        // Row ids are assigned in ingest order from first_id 0, so a hit's
        // doc_id indexes the fixture directly.
        let hits: [SearchHit] = response.hits.compactMap { hit in
            let row = Int(hit.docID)
            guard opinions.indices.contains(row) else { return nil }
            let opinion = opinions[row]
            let cut = hit.snippets.map { Self.snippet($0, bodyLength: opinion.body.utf16.count) }
            return SearchHit(opinion: opinion, score: hit.score, snippet: cut.first, snippets: cut)
        }
        let profile = response.profile
        var stats = QueryStats(
            route: response.executed, engineMilliseconds: Double(profile.totalMs),
            selectionMilliseconds: Double(profile.selectionMs), roundTripMilliseconds: roundTrip,
            hits: hits.count, segments: Int(profile.segmentsTotal), shards: Int(profile.shardsTotal))
        stats.topScore = hits.map(\.score).max() ?? 0
        stats.lowScore = hits.map(\.score).min() ?? 0
        return SearchResult(hits: hits, stats: stats)
    }

    /// Slices the engine's snippet at its highlight bounds. Offsets are UTF-16
    /// code units of the ORIGINAL text; subtracting `start` makes them relative.
    private static func snippet(_ source: Ai_Protomolt_Search_V1_Snippet, bodyLength: Int) -> Snippet {
        let units = Array(source.text.utf16)
        var runs: [Snippet.Run] = []
        var cursor = 0
        func append(_ range: Range<Int>, _ highlighted: Bool) {
            guard !range.isEmpty, let text = String(utf16CodeUnits: Array(units[range]), count: range.count) as String? else { return }
            // Collapse inside each run, keeping one space where a run began or ended on whitespace.
            let collapsed = text.collapsingWhitespace
            guard !collapsed.isEmpty || !runs.isEmpty else { return }
            let lead = text.first?.isWhitespace == true && !runs.isEmpty ? " " : ""
            let trail = text.last?.isWhitespace == true ? " " : ""
            // The whitespace tokenizer's tokens keep their punctuation ("immunity."),
            // so a mark can end on a full stop. The highlighter covers the word only.
            let word = highlighted ? String(collapsed.reversed().drop(while: { $0.isPunctuation }).reversed()) : collapsed
            runs.append(Snippet.Run(text: lead + word + (word.count == collapsed.count ? trail : ""), highlighted: highlighted))
            if word.count < collapsed.count {
                runs.append(Snippet.Run(text: String(collapsed.dropFirst(word.count)) + trail, highlighted: false))
            }
        }
        for mark in source.highlights {
            let lower = max(cursor, min(units.count, Int(mark.start) - Int(source.start)))
            let upper = max(lower, min(units.count, Int(mark.end) - Int(source.start)))
            append(cursor..<lower, false)
            append(lower..<upper, true)
            cursor = upper
        }
        append(cursor..<units.count, false)
        return Snippet(runs: runs, cutAtStart: source.start > 0, cutAtEnd: Int(source.end) < bodyLength)
    }

    private static func plan(_ descriptorSet: Data, _ engine: SearchEngine) throws -> Ai_Protomolt_Search_V1_MappedPlan {
        var request = Ai_Protomolt_Search_V1_PlanIndexRequest()
        request.descriptorSet = descriptorSet
        request.messageType = messageType
        return try engine.planIndex(request).plan
    }

    private static func ingest(_ opinions: [Opinion], descriptorSet: Data, fingerprint: String, into engine: SearchEngine) throws {
        var bind = Ai_Protomolt_Search_V1_MappedBind()
        bind.descriptorSet = descriptorSet
        bind.messageType = messageType
        bind.expectedFingerprint = fingerprint
        bind.bodyPath = "body"
        // field_analysis names EVERY text path, body included, and replaces the
        // legacy `analysis` field; the two are mutually exclusive.
        bind.fieldAnalysis = ["title", "body"].map { path in
            var analysis = Ai_Protomolt_Search_V1_MappedFieldAnalysis()
            analysis.path = path
            analysis.analysis = bodySpec
            return analysis
        }
        var first = Ai_Protomolt_Search_V1_IngestMappedRequest()
        first.bind = bind

        var batch = Ai_Protomolt_Search_Mobile_V1_MobileIngestMappedBatch()
        batch.shard = 0
        batch.requests = [first]
        for opinion in opinions {
            var message = Court_V1_Opinion()
            message.id = opinion.id
            message.title = opinion.title
            message.body = opinion.body
            message.embedding = opinion.embedding
            var request = Ai_Protomolt_Search_V1_IngestMappedRequest()
            request.document = try message.serializedData()
            batch.requests.append(request)
        }
        let ingested = try engine.ingestMapped(batch)
        guard ingested.added == UInt64(opinions.count), ingested.firstID == 0 else {
            throw EngineError(operation: "ingestMapped", code: -1,
                              message: "added \(ingested.added) from first_id \(ingested.firstID), expected \(opinions.count) from 0")
        }
        try engine.flush()
    }

    private static func loadFixture() throws -> [Opinion] {
        let decoder = JSONDecoder()
        return try resource("court_opinions_potion512", "ndjson")
            .split(separator: UInt8(ascii: "\n"))
            .map { try decoder.decode(Opinion.self, from: Data($0)) }
    }

    private static func resource(_ name: String, _ ext: String) throws -> Data {
        guard let url = Bundle.module.url(forResource: name, withExtension: ext) else {
            throw EngineError(operation: "resource", code: -1, message: "\(name).\(ext) missing from bundle")
        }
        return try Data(contentsOf: url)
    }

    private static func bytes(under directory: URL) -> Int64 {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        guard let files = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: keys) else { return 0 }
        return files.compactMap { $0 as? URL }.reduce(0) { total, url in
            let values = try? url.resourceValues(forKeys: Set(keys))
            return total + Int64(values?.isRegularFile == true ? values?.fileSize ?? 0 : 0)
        }
    }
}
