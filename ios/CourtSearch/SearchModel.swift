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

    enum Mode: String, CaseIterable, Identifiable {
        case keyword = "Keyword"
        case meaning = "Meaning"
        var id: Self { self }
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
    @Published var mode: Mode = .keyword {
        didSet { if mode != oldValue { scheduleSearch() } }
    }
    /// Nil when no model is bundled: the Meaning mode is then not offered.
    @Published private(set) var embedder: EmbedderStats?

    /// Questions in plain language, none sharing a caption word with what it finds.
    /// Each was checked against the bundled corpus with tools/wire-probe --meaning.
    static let questions = ["insurance company refused to pay the claim",
                            "contract dispute sent to arbitration",
                            "deported despite fear of persecution",
                            "the prison sentence was too long",
                            "fired after complaining about discrimination"]

    /// `-open 1` on the launch command line: the result to open once the launch
    /// query has answered, so a script or demo can land on the reading view.
    @Published var pendingOpen: String?
    private var launchOpen = UserDefaults.standard.object(forKey: "open") as? Int ?? Int(UserDefaults.standard.string(forKey: "open") ?? "")

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
            let model = Bundle.main.url(forResource: "potion-retrieval-32M", withExtension: nil)
            let opened = try await Task.detached(priority: .userInitiated) {
                try CourtIndex(directory: directory, modelDirectory: model)
            }.value
            embedder = opened.embedderStats
            courtIndex = opened
            opinions = opened.opinions
            let stats = await opened.stats
            index = stats
            // One line on stdout, readable from a host with `devicectl … launch --console`.
            print("court-index documents=\(stats.documents) bytes=\(stats.bytesOnDisk) "
                + "ingestSeconds=\(stats.ingestSeconds.map { String(format: "%.3f", $0) } ?? "reopened")")
            if let embedder {
                print(String(format: "court-embedder dims=%d loadSeconds=%.3f fixtureWorstDelta=%g fixtureSeconds=%.3f",
                             embedder.dimensions, embedder.loadSeconds, embedder.fixtureWorstDelta, embedder.fixtureSeconds))
            }
            if UserDefaults.standard.string(forKey: "mode") == "meaning", embedder != nil { mode = .meaning }
            phase = .ready
            // The vector engine picks its kernels from these at run time, so they
            // belong beside any score comparison between devices.
            func feature(_ name: String) -> Int32 {
                var value: Int32 = 0; var size = MemoryLayout<Int32>.size
                return sysctlbyname(name, &value, &size, nil, 0) == 0 ? value : -1
            }
            print("court-cpu dotprod=\(feature("hw.optional.arm.FEAT_DotProd")) i8mm=\(feature("hw.optional.arm.FEAT_I8MM"))")
            // `-parity YES`: print the cross-platform report (tools/parity_compare.py).
            if UserDefaults.standard.bool(forKey: "parity"), let line = try? await opened.parityReport() { print(line) }
            preparePassages()
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
        let mode = mode
        pending = Task {
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            do {
                // Ask for every opinion: the corpus is small, and "N of 25" on the engine
                // strip must be the number that matched, not a page size.
                let result = mode == .meaning
                    ? try await courtIndex.search(meaning: text, limit: 8)
                    : try await courtIndex.search(text: text, limit: opinions.count)
                guard !Task.isCancelled else { return }
                results = result.hits
                lastQuery = result.stats
                if let position = launchOpen, result.hits.indices.contains(position - 1) {
                    launchOpen = nil
                    pendingOpen = result.hits[position - 1].id
                }
            } catch {
                phase = .failed(String(describing: error))
            }
        }
    }

    /// Where the current Meaning question found its meaning in `opinion`; nil in
    /// Keyword mode or with no question on screen.
    func heat(for opinion: Opinion) async -> Heat? {
        guard mode == .meaning, results != nil, let courtIndex else { return nil }
        return try? await courtIndex.heat(for: opinion)
    }

    /// The words of `opinion` the current Keyword query matched, as the engine marked
    /// them; empty in Meaning mode or with no query on screen.
    func matchedForms(in opinion: Opinion) async -> [String] {
        let text = String(query.drop(while: \.isWhitespace))
        guard mode == .keyword, results != nil, !text.isEmpty, let courtIndex else { return [] }
        return (try? await courtIndex.matchedForms(in: opinion, for: text)) ?? []
    }

    /// Passage vectors are built once, in the background, right after launch, so the
    /// first Meaning query does not pay for them.
    @Published private(set) var passages: (count: Int, seconds: Double)?

    private func preparePassages() {
        guard let courtIndex, embedder != nil else { return }
        Task {
            _ = try? await courtIndex.preparePassages()
            if let seconds = await courtIndex.passageSeconds {
                passages = (await courtIndex.passageCount, seconds)
                print(String(format: "court-passages count=%d seconds=%.3f", passages!.count, seconds))
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
