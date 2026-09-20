import SwiftUI
import UIKit

/// DESIGN.md's two voices. The reporter speaks in serif with an oxblood accent;
/// the engine speaks in sans with tabular figures on slate.
enum Theme {
    static let oxblood = adaptive(light: 0x7A1F2B, dark: 0xEE8F9C)
    /// In the dark the highlighter stays a highlighter: bright amber with dark ink
    /// on the marked words, not a dim tint under light text.
    static let highlighter = adaptive(light: 0xFFE066, dark: 0xF2C94C)
    static let highlighterInk = adaptive(light: 0x1A1A1A, dark: 0x1F1A00)
    /// Suggestion chips: a wash of the accent, heavier in the dark so the shape still reads.
    static let chipFill = adaptive(light: 0x7A1F2B, dark: 0xEE8F9C, lightAlpha: 0.09, darkAlpha: 0.18)
    static let slateSurface = adaptive(light: 0xE9EEF3, dark: 0x1B2430)
    static let slateInk = adaptive(light: 0x33475B, dark: 0xA9BDD1)

    private static func adaptive(light: UInt32, dark: UInt32, lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) -> Color {
        Color(uiColor: UIColor { traits in
            let isDark = traits.userInterfaceStyle == .dark
            let hex = isDark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                           blue: CGFloat(hex & 0xFF) / 255, alpha: isDark ? darkAlpha : lightAlpha)
        })
    }

    /// The navigation title is case-reporter type too, which SwiftUI exposes only through UIKit.
    static func applyNavigationTitleFont() {
        func serif(_ style: UIFont.TextStyle, _ weight: UIFont.Weight) -> UIFont {
            let base = UIFont.preferredFont(forTextStyle: style)
            let descriptor = base.fontDescriptor.withDesign(.serif)?
                .addingAttributes([.traits: [UIFontDescriptor.TraitKey.weight: weight]]) ?? base.fontDescriptor
            return UIFont(descriptor: descriptor, size: base.pointSize)
        }
        let appearance = UINavigationBar.appearance()
        appearance.largeTitleTextAttributes = [.font: serif(.largeTitle, .bold)]
        appearance.titleTextAttributes = [.font: serif(.headline, .semibold)]
    }
}

extension Text {
    /// Engine voice: sans, tabular figures, slate ink.
    func engineValue() -> some View { font(.subheadline.weight(.semibold)).monospacedDigit() }
    func engineLabel() -> some View { font(.caption).foregroundStyle(Theme.slateInk) }
}
