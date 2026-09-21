import CourtSearchKit
import Foundation
import SwiftUI

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

    // Hooks the scripted tour (`-demo meaning`) uses to drive views it cannot reach
    // directly. Each is a counter or flag a view observes; nothing else sets them.
    @Published var tourJump = 0
    @Published var tourStep = 0
    @Published var tourBack = 0
    @Published var tourEngine = false
    @Published var tourEngineAnchor: String?
    /// Where the tour's press mark is, in screen coordinates; nil when lifted.
    @Published var tourTouch: CGPoint?
    /// Frames of the controls the tour presses. Not published: views write it while
    /// laying out, and nothing redraws because of it.
    var tourFrames: [String: CGRect] = [:]

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
            if UserDefaults.standard.string(forKey: "demo") == "meaning" { Task { await runTour() } }
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

    /// `-demo meaning`: the Meaning journey, start to finish, with the taps scripted
    /// and drawn. The engine, the model, and every result are real; only the fingers
    /// are not. For recording a demo, and for showing the app without a free hand.
    func runTour() async {
        func pause(_ seconds: Double) async { try? await Task.sleep(for: .milliseconds(Int(seconds * 1000))) }
        /// Shows a press mark on a registered control (or at a point within it), then
        /// lifts it. The caller performs the action between press and lift.
        func tap(_ id: String, at unit: UnitPoint = .center, then action: () -> Void) async {
            if let frame = tourFrames[id] {
                tourTouch = CGPoint(x: frame.minX + frame.width * unit.x, y: frame.minY + frame.height * unit.y)
                await pause(0.32)
            }
            action()
            await pause(0.22)
            tourTouch = nil
        }
        guard embedder != nil else { return }
        await pause(1.5)
        // The segmented control is one view; Meaning is its right half.
        await tap("mode", at: UnitPoint(x: 0.75, y: 0.5)) { mode = .meaning }
        await pause(2.0)
        await tap("chip-0") { query = Self.questions[0] }
        await pause(2.8)
        await tap("result-0") { if let first = results?.first { pendingOpen = first.id } }
        await pause(2.8)
        // Down the page: each press lands on the next shaded passage.
        for _ in 0..<6 {
            await tap("navigator-next") { tourStep += 1 }
            await pause(1.45)
        }
        // The system back button has no frame to register; it sits here.
        tourTouch = CGPoint(x: 44, y: (tourFrames["safe-top"]?.minY ?? 59) + 28)
        await pause(0.32)
        tourBack += 1
        await pause(0.22)
        tourTouch = nil
        await pause(1.3)
        await tap("engine-strip") { tourEngine = true }
        await pause(2.4)
        tourEngineAnchor = "embedder"
        await pause(3.2)
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
                resultsQuery = result.stats
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

    /// The query that produced the result list, kept so the strip can describe the
    /// list again after an opened opinion's own similarity query has come and gone.
    private var resultsQuery: QueryStats?

    /// Back on the result list: the strip describes what is on screen.
    func returnedToResults() { if let resultsQuery { lastQuery = resultsQuery } }

    func neighbours(of opinion: Opinion) async -> [SearchHit] {
        guard let courtIndex, let result = try? await courtIndex.neighbours(of: opinion, limit: 6) else { return [] }
        lastQuery = result.stats
        // The nearest neighbour of a stored vector is itself; drop it.
        return result.hits.filter { $0.id != opinion.id }
    }
}
