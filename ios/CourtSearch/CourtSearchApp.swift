import SwiftUI

@main
struct CourtSearchApp: App {
    @StateObject private var model = SearchModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .task { await model.open() }
        }
    }
}
