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
    @Published private(set) var index: IndexStats?
    /// Nil while the query is empty: the list shows every opinion instead.
    @Published private(set) var results: [SearchHit]?
    /// The most recent query of either kind; what the engine strip reports.
    @Published private(set) var lastQuery: QueryStats?
    /// Every suggestion returns results in the bundled corpus, across different
    /// areas of law. DESIGN.md lists the same set for every platform.
    static let suggestions = ["habeas", "sentencing", "conspiracy", "insurance",
                              "maritime", "arbitration", "forfeiture", "qualified immunity"]

    @Published var query = "" {
        didSet { if query != oldValue { scheduleSearch() } }
    }

    private var courtIndex: CourtIndex?
    private var pending: Task<Void, Never>?

    func open() async {
        guard courtIndex == nil else { return }
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
            courtIndex = opened
            opinions = opened.opinions
            let stats = await opened.stats
            index = stats
            // One line on stdout, readable from a host with `devicectl … launch --console`.
            print("court-index documents=\(stats.documents) bytes=\(stats.bytesOnDisk) "
                + "ingestSeconds=\(stats.ingestSeconds.map { String(format: "%.3f", $0) } ?? "reopened")")
            phase = .ready
            // `-query habeas` on the launch command line lands in UserDefaults.
            if let launchQuery = UserDefaults.standard.string(forKey: "query") { query = launchQuery }
        } catch {
            phase = .failed(String(describing: error))
        }
    }

    /// Search as you type, 200 ms after the last keystroke.
    private func scheduleSearch() {
        pending?.cancel()
        // Leading space only: a trailing space means the last word is finished,
        // which is what turns the type-ahead prefix off.
        let text = String(query.drop(while: \.isWhitespace))
        guard let courtIndex, !text.trimmingCharacters(in: .whitespaces).isEmpty else {
            results = nil
            return
        }
        pending = Task {
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            do {
                // Ask for every opinion: the corpus is small, and "N of 25" on the engine
                // strip must be the number that matched, not a page size.
                let result = try await courtIndex.search(text: text, limit: opinions.count)
                guard !Task.isCancelled else { return }
                results = result.hits
                lastQuery = result.stats
            } catch {
                phase = .failed(String(describing: error))
            }
        }
    }

    func neighbours(of opinion: Opinion) async -> [SearchHit] {
        guard let courtIndex, let result = try? await courtIndex.neighbours(of: opinion, limit: 6) else { return [] }
        lastQuery = result.stats
        // The nearest neighbour of a stored vector is itself; drop it.
        return result.hits.filter { $0.id != opinion.id }
    }
}
