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
        let habeas = try await index.search(text: "habeas").map(\.opinion.title)
        XCTAssertEqual(habeas, ["Forsyth v. Spencer", "United States v. Dowdell"])

        let first = index.opinions[0]
        let neighbours = try await index.neighbours(of: first)
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
