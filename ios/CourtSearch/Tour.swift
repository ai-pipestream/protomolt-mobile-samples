import SwiftUI

// The scripted tour (`-demo meaning`) has no fingers, so it draws them. A control
// the tour presses registers its frame; the tour asks for a tap there, a press mark
// appears, and then the control's action runs. None of this exists outside a tour.

extension View {
    /// Registers this control's on-screen frame under `id`, for the tour to tap.
    func tourTarget(_ id: String, in model: SearchModel) -> some View {
        background(GeometryReader { geometry in
            let frame = geometry.frame(in: .global)
            Color.clear
                .onAppear { model.tourFrames[id] = frame }
                .onChange(of: frame) { _, new in model.tourFrames[id] = new }
        })
    }
}

/// The press mark: a soft disc that lands, lingers a moment, and lifts.
struct TourTouchLayer: View {
    @EnvironmentObject private var model: SearchModel

    var body: some View {
        GeometryReader { geometry in
            let origin = geometry.frame(in: .global).origin
            if let touch = model.tourTouch {
                Circle()
                    .fill(Color.primary.opacity(0.22))
                    .overlay(Circle().stroke(Color.primary.opacity(0.35), lineWidth: 1.5))
                    .frame(width: 46, height: 46)
                    .position(x: touch.x - origin.x, y: touch.y - origin.y)
                    .transition(.scale(scale: 1.5).combined(with: .opacity))
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .animation(.easeOut(duration: 0.18), value: model.tourTouch)
    }
}
