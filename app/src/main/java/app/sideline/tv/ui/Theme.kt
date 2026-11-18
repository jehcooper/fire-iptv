package app.sideline.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.sideline.tv.R

/** Slate-lavender palette with white highlights (frosted look). */
object C {
    val Bg = Color(0xFF2A2C47)
    val Surface = Color(0xFF3A3D60)
    val Surface2 = Color(0xFF4E5279)
    /** Frosted pill / panel fill. */
    val Glass = Color(0x8C5F6388)
    /** Selected items and focus outlines are white, as in the reference app. */
    val Accent = Color(0xFFF7F5FF)
    val Live = Color(0xFFFF3D57)
    val Gold = Color(0xFFFFCB5C)
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0xFFC3C5E0)
    val Faint = Color(0xFF9093B6)
    /** Dark text on white (selected) pills. */
    val OnAccent = Color(0xFF262842)

    fun hex(h: String?, fallback: Color = Surface2): Color = try {
        if (h.isNullOrBlank()) fallback else Color(android.graphics.Color.parseColor("#" + h.removePrefix("#")))
    } catch (e: Exception) { fallback }
}

/** Inter (bundled; open-source, very close to Apple's SF Pro used by the reference app). */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

private fun Typography.inter(): Typography {
    fun TextStyle.f() = copy(fontFamily = Inter)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

@Composable
fun SidelineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = C.OnAccent,
            background = C.Bg,
            surface = C.Surface,
            onSurface = C.Text,
            onBackground = C.Text,
        ),
        typography = Typography().inter(),
        content = content,
    )
}
