import SwiftUI

@main
struct CourtSearchApp: App {
    @StateObject private var model = SearchModel()

    init() { Theme.applyNavigationTitleFont() }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .tint(Theme.oxblood)
                .task { await model.open() }
        }
    }
}
