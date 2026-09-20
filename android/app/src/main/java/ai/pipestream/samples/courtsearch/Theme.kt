package ai.pipestream.samples.courtsearch

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * DESIGN.md's two voices. The reporter speaks in serif with an oxblood accent;
 * the engine speaks in sans with tabular figures on slate. Fixed colours, not
 * wallpaper-derived ones: the design is the same app on every phone.
 */
class Voices(
    val oxblood: Color,
    /** In the dark the highlighter stays a highlighter: bright amber with dark ink, not a dim tint. */
    val highlighter: Color, val highlighterInk: Color,
    /** Suggestion chips: a wash of the accent, heavier in the dark so the shape still reads. */
    val chipFill: Color,
    val slateSurface: Color, val slateInk: Color,
)

private val LightVoices = Voices(Color(0xFF7A1F2B), Color(0xFFFFE066), Color(0xFF1A1A1A),
    Color(0xFF7A1F2B).copy(alpha = 0.09f), Color(0xFFE9EEF3), Color(0xFF33475B))
private val DarkVoices = Voices(Color(0xFFEE8F9C), Color(0xFFF2C94C), Color(0xFF1F1A00),
    Color(0xFFEE8F9C).copy(alpha = 0.18f), Color(0xFF1B2430), Color(0xFFA9BDD1))

val LocalVoices = staticCompositionLocalOf { LightVoices }

object Reporter {
    private fun serif(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal) =
        TextStyle(fontFamily = FontFamily.Serif, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, fontStyle = style)

    val screenTitle = serif(34, 40, FontWeight.Bold)
    val heading = serif(22, 28, FontWeight.SemiBold)
    val sectionHeading = serif(18, 24, FontWeight.SemiBold)
    val captionLarge = serif(28, 34, style = FontStyle.Italic)
    val caption = serif(20, 26, style = FontStyle.Italic)
    val captionSmall = serif(17, 23, style = FontStyle.Italic)
    val citation = serif(15, 20)
    val body = serif(17, 27)
    val snippet = serif(16, 24)
    val small = serif(13, 18)
}

object Engine {
    val value = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val row = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum")
}

@Composable
fun CourtSearchTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val voices = if (dark) DarkVoices else LightVoices
    val scheme = if (dark) darkColorScheme(primary = voices.oxblood, background = Color.Black, surface = Color.Black)
    else lightColorScheme(primary = voices.oxblood, background = Color.White, surface = Color.White)
    CompositionLocalProvider(LocalVoices provides voices) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
