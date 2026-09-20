import Foundation
import SwiftProtobuf

/// One opinion from the bundled fixture. `embedding` is the precomputed
/// potion-retrieval-32M document vector (512 floats, unit length).
public struct Opinion: Decodable, Sendable, Identifiable {
    public let id: String
    public let title: String
    public let body: String
    public let sourceURI: String
    public let embedding: [Float]

    enum CodingKeys: String, CodingKey {
        case id = "doc_id", title, body, sourceURI = "source_uri", embedding
    }
}

public struct SearchHit: Sendable, Identifiable {
    public let opinion: Opinion
    public let score: Float
    public var id: String { opinion.id }
}

public struct IndexStats: Sendable {
    public let documents: Int
    public let bytesOnDisk: Int64
    /// Nil when the index already existed and nothing was ingested this launch.
    public let ingestSeconds: Double?
}

/// The court sample's private on-device index: plan, mapped ingest, flush, and
/// the two demo queries, in the sequence tools/wire-probe verified on the host.
public actor CourtIndex {
    public nonisolated let opinions: [Opinion]
    public private(set) var stats: IndexStats
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
    /// bundled fixture on first use.
    public init(directory: URL) throws {
        self.directory = directory
        opinions = try Self.loadFixture()
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
        var seconds: Double?
        if !exists {
            let start = Date()
            try Self.ingest(opinions, into: engine)
            seconds = Date().timeIntervalSince(start)
        }
        stats = IndexStats(documents: opinions.count, bytesOnDisk: Self.bytes(under: directory), ingestSeconds: seconds)
    }

    /// BM25 over opinion bodies.
    public func search(text: String, limit: Int = 5) throws -> [SearchHit] {
        var lexical = Ai_Protomolt_Search_V1_LexicalQuery()
        lexical.text = text
        lexical.analysis = Self.bodySpec
        var search = Ai_Protomolt_Search_V1_SearchQuery()
        search.id = "lexical"
        search.lexical = lexical
        return try run(search, limit: limit)
    }

    /// Nearest neighbours of a stored opinion's own vector. Phase 0 has no
    /// on-device embedder, so queries are documents, as in the Java sample.
    public func neighbours(of opinion: Opinion, limit: Int = 5) throws -> [SearchHit] {
        var dense = Ai_Protomolt_Search_V1_DenseQuery()
        dense.vector = opinion.embedding
        var search = Ai_Protomolt_Search_V1_SearchQuery()
        search.id = "dense"
        search.dense = dense
        return try run(search, limit: limit)
    }

    public func close() throws {
        try engine.flush()
        try engine.close()
    }

    private func run(_ search: Ai_Protomolt_Search_V1_SearchQuery, limit: Int) throws -> [SearchHit] {
        var selection = Ai_Protomolt_Search_V1_SelectionQuery()
        selection.search = search
        var request = Ai_Protomolt_Search_V1_QueryRequest()
        request.requestID = "court-sample"
        request.k = UInt32(limit)
        request.selectionK = UInt32(limit)
        request.selection = selection
        // Row ids are assigned in ingest order from first_id 0, so a hit's
        // doc_id indexes the fixture directly.
        return try engine.query(request).hits.compactMap { hit in
            let row = Int(hit.docID)
            guard opinions.indices.contains(row) else { return nil }
            return SearchHit(opinion: opinions[row], score: hit.score)
        }
    }

    private static func ingest(_ opinions: [Opinion], into engine: SearchEngine) throws {
        let descriptorSet = try resource("court", "desc")

        var planRequest = Ai_Protomolt_Search_V1_PlanIndexRequest()
        planRequest.descriptorSet = descriptorSet
        planRequest.messageType = messageType
        let plan = try engine.planIndex(planRequest).plan

        var bind = Ai_Protomolt_Search_V1_MappedBind()
        bind.descriptorSet = descriptorSet
        bind.messageType = messageType
        bind.expectedFingerprint = plan.fingerprint
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
