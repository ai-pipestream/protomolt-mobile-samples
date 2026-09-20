import CourtSearchKit
import Foundation

/// Owns the on-device index. Every engine call blocks, so the index is opened on
/// a detached task and queried through its actor, never on the main thread.
@MainActor
final class SearchModel: ObservableObject {
    enum Phase {
        case opening
        case ready
        case failed(String)
    }

    @Published private(set) var phase: Phase = .opening
    @Published private(set) var opinions: [Opinion] = []
    @Published private(set) var stats: IndexStats?
    @Published private(set) var results: [SearchHit]?
    @Published var query = ""

    private var index: CourtIndex?

    func open() async {
        guard index == nil else { return }
        do {
            let directory = try FileManager.default
                .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                .appendingPathComponent("court-index", isDirectory: true)
            // `-resetIndex YES` deletes the stored index first, so a launch re-ingests
            // without reinstalling the app (which would also drop developer trust).
            if UserDefaults.standard.bool(forKey: "resetIndex") {
                try? FileManager.default.removeItem(at: directory)
            }
            let opened = try await Task.detached(priority: .userInitiated) {
                try CourtIndex(directory: directory)
            }.value
            index = opened
            opinions = opened.opinions
            let openedStats = await opened.stats
            stats = openedStats
            // One line on stdout, readable from a host with `devicectl … launch --console`.
            print("court-index documents=\(openedStats.documents) bytes=\(openedStats.bytesOnDisk) "
                + "ingestSeconds=\(openedStats.ingestSeconds.map { String(format: "%.3f", $0) } ?? "reopened")")
            phase = .ready
            // `-query habeas` on the launch command line lands in UserDefaults; it
            // lets a script or a demo open straight onto a result list.
            if let launchQuery = UserDefaults.standard.string(forKey: "query") {
                query = launchQuery
                await search()
            }
        } catch {
            phase = .failed(String(describing: error))
        }
    }

    func search() async {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard let index, !text.isEmpty else {
            results = nil
            return
        }
        do {
            results = try await index.search(text: text, limit: 10)
        } catch {
            phase = .failed(String(describing: error))
        }
    }

    func neighbours(of opinion: Opinion) async -> [SearchHit] {
        guard let index else { return [] }
        // The nearest neighbour of a stored vector is itself; drop it.
        return ((try? await index.neighbours(of: opinion, limit: 6)) ?? []).filter { $0.id != opinion.id }
    }
}
