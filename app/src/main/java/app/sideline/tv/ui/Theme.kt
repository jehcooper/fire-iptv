package app.sideline.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.sideline.tv.R

/** One colour scheme. [geometric] = Space Grotesk font; otherwise Inter. */
data class Palette(
    val id: String, val name: String,
    val bg: Long, val surface: Long, val surface2: Long, val rail: Long, val accent: Long, val onAccent: Long,
    val text: Long = 0xFFF2F4F7, val muted: Long = 0xFFA3ABB8, val faint: Long = 0xFF6E7684,
    val live: Long = 0xFFFF4757, val gold: Long = 0xFFF5C451, val geometric: Boolean = true,
)

/** Themes you can pick in Settings → Theme. "Midnight teal" is the default. */
object Themes {
    val all = listOf(
        Palette("teal", "Midnight teal", 0xFF121519, 0xFF1C2027, 0xFF282D36, 0xFF161920, 0xFF31B9D9, 0xFF071418),
        Palette("lavender", "Lavender", 0xFF2A2C47, 0xFF3A3D60, 0xFF4E5279, 0xFF24263F, 0xFFF7F5FF, 0xFF262842,
            text = 0xFFFFFFFF, muted = 0xFFC3C5E0, faint = 0xFF9093B6, live = 0xFFFF3D57, gold = 0xFFFFCB5C, geometric = false),
        Palette("ocean", "Ocean blue", 0xFF0E1522, 0xFF172133, 0xFF223047, 0xFF121B2A, 0xFF4C8DFF, 0xFF06142E),
        Palette("emerald", "Emerald", 0xFF0F1513, 0xFF18211D, 0xFF243029, 0xFF131B18, 0xFF34D399, 0xFF04170F),
        Palette("amber", "Amber night", 0xFF15130F, 0xFF211D17, 0xFF2F2920, 0xFF1A1712, 0xFFF2A93B, 0xFF1E1303),
        Palette("crimson", "Crimson", 0xFF141113, 0xFF211A1E, 0xFF2F252B, 0xFF1A1418, 0xFFFF4D6D, 0xFF1F050B, live = 0xFFFF8A3D),
        Palette("graphite", "Graphite", 0xFF111111, 0xFF1C1C1C, 0xFF2A2A2A, 0xFF171717, 0xFFFFFFFF, 0xFF111111),
    )
    val current = mutableStateOf(all.first())
    fun set(id: String) { current.value = all.firstOrNull { it.id == id } ?: all.first() }
}

/** Colours used everywhere; they follow the chosen theme. */
object C {
    private val p get() = Themes.current.value
    val Bg get() = Color(p.bg)
    val Surface get() = Color(p.surface)
    val Surface2 get() = Color(p.surface2)
    /** Left menu strip. */
    val Rail get() = Color(p.rail)
    /** Frosted panel fill. */
    val Glass get() = Color(p.surface).copy(alpha = 0.8f)
    /** Selection, focus outline, buttons, progress. */
    val Accent get() = Color(p.accent)
    /** Text on an accent-filled button. */
    val OnAccent get() = Color(p.onAccent)
    val Live get() = Color(p.live)
    val Gold get() = Color(p.gold)
    val Text get() = Color(p.text)
    val Muted get() = Color(p.muted)
    val Faint get() = Color(p.faint)

    fun hex(h: String?, fallback: Color = Surface2): Color = try {
        if (h.isNullOrBlank()) fallback else Color(android.graphics.Color.parseColor("#" + h.removePrefix("#")))
    } catch (e: Exception) { fallback }
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

/** Space Grotesk (open-source geometric sans, close to the reference app's look). */
val Grotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_medium, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
    Font(R.font.space_grotesk_bold, FontWeight.ExtraBold),
)

private fun Typography.withFont(f: FontFamily): Typography {
    fun TextStyle.x() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.x(), displayMedium = displayMedium.x(), displaySmall = displaySmall.x(),
        headlineLarge = headlineLarge.x(), headlineMedium = headlineMedium.x(), headlineSmall = headlineSmall.x(),
        titleLarge = titleLarge.x(), titleMedium = titleMedium.x(), titleSmall = titleSmall.x(),
        bodyLarge = bodyLarge.x(), bodyMedium = bodyMedium.x(), bodySmall = bodySmall.x(),
        labelLarge = labelLarge.x(), labelMedium = labelMedium.x(), labelSmall = labelSmall.x(),
    )
}

@Composable
fun SidelineTheme(content: @Composable () -> Unit) {
    val p = Themes.current.value
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent, onPrimary = C.OnAccent, background = C.Bg, surface = C.Surface,
            onSurface = C.Text, onBackground = C.Text,
        ),
        typography = Typography().withFont(if (p.geometric) Grotesk else Inter),
        content = content,
    )
}
