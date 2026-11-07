package app.sideline.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Bg = Color(0xFF0B0F14)
    val Surface = Color(0xFF131A22)
    val Surface2 = Color(0xFF1C2631)
    val Accent = Color(0xFF2EE59D)
    val Live = Color(0xFFFF4D5E)
    val Gold = Color(0xFFFFC94D)
    val Text = Color(0xFFE8EEF3)
    val Muted = Color(0xFF8FA3B5)
    val Faint = Color(0xFF55687A)

    fun hex(h: String?, fallback: Color = Surface2): Color = try {
        if (h.isNullOrBlank()) fallback else Color(android.graphics.Color.parseColor("#" + h.removePrefix("#")))
    } catch (e: Exception) { fallback }
}

@Composable
fun SidelineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = C.Bg,
            background = C.Bg,
            surface = C.Surface,
            onSurface = C.Text,
            onBackground = C.Text,
        ),
        content = content,
    )
}
