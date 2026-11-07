package app.sideline.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sideline.tv.SidelineApp
import app.sideline.tv.sports.AlertKind
import app.sideline.tv.sports.Game
import app.sideline.tv.sports.ScoreAlert
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The basic remote-friendly tile: grows and lights up when focused. Menu button = secondary action. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    onFocus: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    scaleOnFocus: Float = 1.05f,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) scaleOnFocus else 1f, label = "scale")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .onPreviewKeyEvent { e ->
                if (onMenu != null && e.key == Key.Menu) {
                    if (e.type == KeyEventType.KeyUp) onMenu()
                    true
                } else false
            }
            .clip(shape)
            .background(if (focused) C.Surface2 else C.Surface)
            .border(if (focused) 3.dp else 0.dp, if (focused) C.Accent else Color.Transparent, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) { content(focused) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(bottom = 10.dp))
}

@Composable
fun Pill(text: String, color: Color, textColor: Color = C.Bg) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), scaleOnFocus = 1.08f) { f ->
        Box(
            Modifier
                .background(if (selected) C.Accent.copy(alpha = if (f) 1f else 0.85f) else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text(text, color = if (selected) C.Bg else C.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

fun timeOf(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
fun dayTimeOf(ms: Long): String = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(ms))

/** Scoreboard tile with team colours bleeding in from the edges. */
@Composable
fun GameCard(game: Game, followed: Boolean, spoilerFree: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onMenu: (() -> Unit)? = null) {
    val away = game.away
    val home = game.home
    val left = C.hex(away?.color).copy(alpha = 0.55f)
    val right = C.hex(home?.color).copy(alpha = 0.55f)
    FocusCard(onClick = onClick, onMenu = onMenu, modifier = modifier.width(300.dp).height(150.dp)) { _ ->
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(left, C.Surface, C.Surface, right))))
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    game.isLive -> Pill("LIVE", C.Live, Color.White)
                    game.isFinal -> Pill("FINAL", C.Faint, C.Text)
                    else -> Pill(if (game.startMillis > 0) dayTimeOf(game.startMillis) else "UPCOMING", C.Surface2, C.Text)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (game.isLive || game.isFinal) game.detail else game.broadcasts.take(2).joinToString(" · "),
                    color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (followed) Text("🔔", fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (game.competitors.size == 2) {
                game.competitors.sortedBy { it.home }.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(34.dp), contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(10.dp))
                        Text(c.shortName.ifBlank { c.name }, color = C.Text, fontSize = 17.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val shown = if (game.state == "pre") "" else if (spoilerFree) "•" else c.score
                        Text(shown, color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(game.title, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                game.competitors.forEachIndexed { i, c ->
                    Text("${i + 1}. ${c.name}  ${if (spoilerFree) "" else c.score}", color = C.Muted, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Channel or poster tile with an image and caption. */
@Composable
fun PosterCard(
    title: String, image: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
    wide: Boolean = false, badge: String? = null, onFocus: (() -> Unit)? = null, onMenu: (() -> Unit)? = null,
) {
    val w = if (wide) 220.dp else 150.dp
    val h = if (wide) 124.dp else 225.dp
    FocusCard(onClick = onClick, onFocus = onFocus, onMenu = onMenu, modifier = modifier.width(w).height(h)) { focused ->
        AsyncImage(
            model = image, contentDescription = null,
            contentScale = if (wide) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(if (wide) 18.dp else 0.dp),
        )
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE0B0F14))))
                .padding(10.dp),
        ) {
            Text(title, color = C.Text, fontSize = 13.sp, maxLines = if (focused) 3 else 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal)
        }
        if (badge != null) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Pill(badge, C.Accent) }
    }
}

/** Pop-up for followed games: slides in at the top-right, then gets out of the way. */
@Composable
fun ScorePopupHost(modifier: Modifier = Modifier, skipStarts: Boolean = false) {
    var current by remember { mutableStateOf<ScoreAlert?>(null) }
    var visible by remember { mutableStateOf(false) }
    val spoilerFree by SidelineApp.prefs.spoilerFree.collectAsState()
    LaunchedEffect(Unit) {
        SidelineApp.scores.alerts.collect { a ->
            if (skipStarts && (a.kind == AlertKind.STARTED || a.kind == AlertKind.STARTING_SOON)) return@collect
            current = a
            visible = true
            delay(if (a.synopsis.isNotBlank()) 9000 else 7000)
            visible = false
            delay(400)
        }
    }
    Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = visible && current != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            val a = current ?: return@AnimatedVisibility
            val g = a.game
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xF0131A22))
                    .border(2.dp, if (a.kind == AlertKind.SCORE) C.Accent else C.Faint, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (g.isLive) C.Live else C.Muted))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(a.headline, color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (g.competitors.size == 2) {
                        g.competitors.sortedBy { it.home }.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(26.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(c.abbr.ifBlank { c.shortName }, color = C.Text, fontSize = 17.sp, modifier = Modifier.width(110.dp), maxLines = 1)
                                Text(if (spoilerFree) "•" else c.score, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Text(g.title, color = C.Text, fontSize = 16.sp)
                    }
                    if (a.synopsis.isNotBlank() && !spoilerFree) {
                        Text(a.synopsis, color = C.Text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp).width(380.dp))
                    }
                    Text(g.detail, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = C.Muted, fontSize = 15.sp)
    }
}

@Composable
fun SidePanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxHeight().background(C.Surface)) { content() }
}

/**
 * Full-screen panel in its own window, so the remote's focus stays inside it until Back is pressed.
 * (Drawing panels on top of a screen let focus leak to the screen underneath.)
 */
@Composable
fun TvDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) { content() }
}
