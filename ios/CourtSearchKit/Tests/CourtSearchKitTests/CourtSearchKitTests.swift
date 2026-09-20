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
