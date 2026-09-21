import XCTest
@testable import CourtSearchKit

/// The Phase 0 acceptance checks, run on a simulator or device. Expected
/// results are the ones tools/wire-probe and the Java/Lucene sample both produce.
final class CourtSearchKitTests: XCTestCase {
    func testIngestSearchAndReopen() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("court-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        var index = try CourtIndex(directory: directory)
        let created = await index.stats
        XCTAssertEqual(created.documents, 25)
        XCTAssertNotNil(created.ingestSeconds)
        XCTAssertGreaterThan(created.bytesOnDisk, 0)
        try await assertDemoQueries(index)

        try await index.close()
        index = try CourtIndex(directory: directory)
        let reopened = await index.stats
        XCTAssertNil(reopened.ingestSeconds, "reopen must attach to the existing index, not re-ingest")
        try await assertDemoQueries(index)
        try await index.close()
    }

    /// Search by meaning, and this device's embedder against the Java vectors.
    /// Needs the model: `TEST_RUNNER_COURT_MODEL_DIR=<dir> xcodebuild test …`
    /// (the simulator reads host paths). Skipped without it.
    func testSearchByMeaningAndEmbedderConformance() async throws {
        guard let path = ProcessInfo.processInfo.environment["COURT_MODEL_DIR"] else {
            throw XCTSkip("set TEST_RUNNER_COURT_MODEL_DIR to a potion-retrieval-32M directory")
        }
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("court-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let index = try CourtIndex(directory: directory, modelDirectory: URL(fileURLWithPath: path))

        let embedder = try XCTUnwrap(index.embedderStats)
        XCTAssertEqual(embedder.dimensions, 512)
        XCTAssertEqual(embedder.fixtureWorstDelta, 0, "Rust and Java embedders must agree bit for bit")

        // No word in common with the caption it finds.
        let result = try await index.search(meaning: "insurance company refused to pay the claim")
        XCTAssertEqual(result.hits.first?.opinion.title, "Baker v. St. Paul Travelers Insurance")
        XCTAssertEqual(result.stats.route, "search")
        let embedding = try XCTUnwrap(result.stats.embedding)
        XCTAssertEqual(embedding.words, 7)
        XCTAssertTrue(embedding.spelledOutWords.isEmpty)
        XCTAssertGreaterThanOrEqual(embedding.pieces, 7)
        XCTAssertGreaterThan(result.stats.topScore, result.stats.lowScore)
        // Why this opinion: the question's own words, nearest first.
        XCTAssertEqual(result.hits[0].closestWords.first, "insurance")

        // Where the meaning was found. The nearest sentence is about paying a premium
        // for coverage: it answers the question without sharing one of its words,
        // which is the point of searching by meaning.
        let passage = try XCTUnwrap(result.hits[0].passage)
        XCTAssertTrue(passage.text.contains("paid a premium"), passage.text)
        for word in ["insurance", "company", "refused", "claim"] {
            XCTAssertFalse(passage.text.localizedCaseInsensitiveContains(word), "shares “\(word)” with the question")
        }
        let heat = try await index.heat(for: result.hits[0].opinion)
        XCTAssertEqual(try XCTUnwrap(heat).hottest, passage.location)
        let prepared = await index.passageCount
        XCTAssertEqual(prepared, 4075, "segmentation must match tools/passages_reference.py")

        // WordPiece never gives up on a word: gibberish still gets a vector, spelled
        // out letter by letter, and is reported as such.
        let partial = try await index.search(meaning: "insurance qzxvkjw")
        XCTAssertEqual(try XCTUnwrap(partial.stats.embedding).spelledOutWords, ["qzxvkjw"])
        let nothing = try await index.search(meaning: "")
        XCTAssertTrue(nothing.hits.isEmpty, "empty text has no vector and asks nothing")
        try await index.close()
    }

    private func assertDemoQueries(_ index: CourtIndex) async throws {
        let habeas = try await index.search(text: "habeas")
        XCTAssertEqual(habeas.hits.map(\.opinion.title), ["Forsyth v. Spencer", "United States v. Dowdell"])
        XCTAssertEqual(habeas.stats.route, "bm25_search")
        XCTAssertGreaterThan(habeas.stats.engineMilliseconds, 0)
        // The engine cuts the snippet and marks the match; the app only slices it.
        let marked = try XCTUnwrap(habeas.hits[0].snippet).runs.filter(\.highlighted).map(\.text)
        XCTAssertEqual(marked.map { $0.trimmingCharacters(in: .whitespaces) }, ["habeas"])
        XCTAssertEqual(habeas.hits[0].opinion.citation, "No. 09-1011 (1st Cir. Feb. 16, 2010)")

        let first = index.opinions[0]
        // The reading view highlights the engine's own matched forms, stems included.
        let aguirre = try XCTUnwrap(index.opinions.first { $0.title == "United States v. Aguirre-Gonzalez" })
        let forms = try await index.matchedForms(in: aguirre, for: "sentencing")
        XCTAssertTrue(forms.contains("sentencing") && forms.contains("sentence"), "\(forms)")

        // Type-ahead: an unfinished word finds what the finished one does.
        let typing = try await index.search(text: "hab")
        XCTAssertEqual(Array(typing.hits.map(\.opinion.title).prefix(2)), ["Forsyth v. Spencer", "United States v. Dowdell"])

        let similar = try await index.neighbours(of: first)
        XCTAssertEqual(similar.stats.route, "search")
        XCTAssertNil(similar.hits[0].snippet, "snippets are a keyword-only feature")
        let neighbours = similar.hits
        XCTAssertEqual(neighbours.map(\.opinion.title), [
            "United States v. Davila-Gonzalez",
            "United States v. Rodríguez-Vélez",
            "United States v. De-la-Rosa-Ramos",
            "United States v. Diaz",
            "United States v. Ekasala",
        ])
        XCTAssertEqual(neighbours[0].score, 1.0, accuracy: 0.01)
    }
}
