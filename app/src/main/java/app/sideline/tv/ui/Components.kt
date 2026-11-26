package app.sideline.tv.ui

import androidx.compose.runtime.produceState
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sideline.tv.SidelineApp
import app.sideline.tv.sports.AlertKind
import app.sideline.tv.sports.Game
import app.sideline.tv.sports.Leagues
import app.sideline.tv.sports.ScoreAlert
import coil.compose.AsyncImage
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How opaque cards are drawn (1 = solid). Player panels lower it to the see-through level set in Settings,
 * so rows and buttons are as see-through as the panel itself.
 */
val LocalPanelAlpha = androidx.compose.runtime.compositionLocalOf { 1f }

/**
 * For a row of tiles: moving back into the row (up/down from another row) lands on the tile you left,
 * instead of whichever tile happens to be lined up with you. Standard TV behaviour.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.rememberRowPosition(): Modifier = this.focusRestorer()

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
    val panelAlpha = LocalPanelAlpha.current
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
            .background(if (focused) C.Surface2.copy(alpha = panelAlpha) else C.Surface.copy(alpha = panelAlpha))
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
        Text(text, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    FocusCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), scaleOnFocus = 1.08f) { f ->
        Box(
            Modifier
                .background(if (selected) C.Accent.copy(alpha = if (f) 1f else 0.85f) else Color.Transparent)
                .padding(horizontal = if (compact) 13.dp else 18.dp, vertical = if (compact) 7.dp else 8.dp),
        ) {
            Text(text, color = if (selected) C.OnAccent else C.Text, fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

fun timeOf(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
fun dayTimeOf(ms: Long): String = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(ms))

/** Scoreboard tile with team colours bleeding in from the edges. */
@Composable
fun GameCard(
    game: Game, followed: Boolean, spoilerFree: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null, compact: Boolean = false,
) {
    val away = game.away
    val home = game.home
    val left = C.hex(away?.color).copy(alpha = 0.55f)
    val right = C.hex(home?.color).copy(alpha = 0.55f)
    // Compact = about half the height, for Home's My Teams row and the Sports tab.
    // Compact cards size to their content (no fixed height), so nothing is ever cut off.
    val w = if (compact) 250.dp else 300.dp
    val sized = if (compact) modifier.width(w) else modifier.width(w).height(150.dp)
    FocusCard(onClick = onClick, onMenu = onMenu, modifier = sized) { _ ->
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(left, C.Surface, C.Surface, right))))
        Column((if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 8.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tag = Leagues.byId(game.leagueId)?.tag
                if (compact && tag != null) {
                    Text(tag, color = C.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(5.dp))
                }
                when {
                    game.isLive -> if (compact) Text("● LIVE", color = C.Live, fontSize = 12.sp, fontWeight = FontWeight.Bold) else Pill("LIVE", C.Live, Color.White)
                    game.isFinal -> if (compact) Text("FINAL", color = C.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold) else Pill("FINAL", C.Faint, C.Text)
                    else -> if (compact) Text(if (game.startMillis > 0) dayTimeOf(game.startMillis) else "", color = C.Muted, fontSize = 12.sp)
                        else Pill(if (game.startMillis > 0) dayTimeOf(game.startMillis) else "UPCOMING", C.Surface2, C.Text)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (game.isLive || game.isFinal) game.detail else game.broadcasts.take(2).joinToString(" · "),
                    color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (followed) Text("🔔", fontSize = if (compact) 12.sp else 14.sp)
            }
            Spacer(Modifier.height(if (compact) 3.dp else 10.dp))
            if (game.competitors.size == 2) {
                game.competitors.sortedBy { it.home }.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = if (compact) 1.dp else 3.dp)) {
                        AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(if (compact) 20.dp else 34.dp), contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
                        Text(if (compact) c.abbr.ifBlank { c.shortName } else c.shortName.ifBlank { c.name }, color = C.Text,
                            fontSize = if (compact) 13.sp else 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val shown = if (game.state == "pre") "" else if (spoilerFree) "•" else c.score
                        Text(shown, color = C.Text, fontSize = if (compact) 15.sp else 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(game.title, color = C.Text, fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                game.competitors.take(if (compact) 1 else 3).forEachIndexed { i, c ->
                    Text("${i + 1}. ${c.name}  ${if (spoilerFree) "" else c.score}", color = C.Muted, fontSize = if (compact) 12.sp else 14.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Channel or poster tile with an image and caption. */
/** True inside poster grids: tiles fill their column instead of a fixed size. */
val LocalPosterFill = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun PosterCard(
    title: String, image: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
    wide: Boolean = false, badge: String? = null, onFocus: (() -> Unit)? = null, onMenu: (() -> Unit)? = null,
    badgeColor: Color = C.Accent, badgeTextColor: Color = C.OnAccent,
    /** "movie" or "series" for your library items (lets the grid fetch TMDB's sharper poster). */
    kind: String? = null,
    /** Line under the title; defaults to year · genre. */
    meta: String? = null,
) {
    // Every poster (grids and rows alike) is a clean picture with the title and year · genre underneath.
    if (!wide) {
        GridPoster(title, image, onClick, if (LocalPosterFill.current) modifier else modifier.width(150.dp), badge, onFocus, onMenu, badgeColor, kind, meta)
        return
    }
    val w = if (wide) 220.dp else 150.dp
    val h = if (wide) 124.dp else 225.dp
    val sized = if (LocalPosterFill.current) modifier.fillMaxWidth().aspectRatio(16f / 9f) else modifier.width(w).height(h)
    FocusCard(onClick = onClick, onFocus = onFocus, onMenu = onMenu, modifier = sized) { focused ->
        AsyncImage(
            model = image, contentDescription = null,
            placeholder = if (wide) null else ColorPainter(C.Surface2),
            contentScale = if (wide) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(if (wide) 18.dp else 0.dp),
        )
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, C.Bg.copy(alpha = 0.93f))))
                .padding(10.dp),
        ) {
            Text(title, color = C.Text, fontSize = 13.sp, maxLines = if (focused) 3 else 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal)
        }
        if (badge != null) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Pill(badge, badgeColor, badgeTextColor) }
    }
}

/**
 * Grid tile (Movies, Series, people and genre pages): a clean poster with nothing on it, and below it the
 * full title (never cut short; it wraps) and the rating · year. Library posters are swapped for TMDB's sharper
 * artwork when a match is found.
 */
@Composable
private fun GridPoster(
    title: String, image: String?, onClick: () -> Unit, modifier: Modifier, badge: String?,
    onFocus: (() -> Unit)?, onMenu: (() -> Unit)?, badgeColor: Color, kind: String?, metaOverride: String? = null,
) {
    val clean = remember(title) { app.sideline.tv.data.MovieInfoService.cleanTitle(title) }
    val hasYearInTitle = Regex("\\((19|20)\\d{2}\\)\\s*$").containsMatchIn(title)
    val name = if (kind != null) clean.first else if (hasYearInTitle) title.replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"), "") else title
    val year = clean.second ?: Regex("\\((19|20)\\d{2}\\)").find(title)?.value?.trim('(', ')')
    val hq by produceState<String?>(app.sideline.tv.data.TmdbApi.cachedPoster(clean.first, clean.second, kind == "series"), title, kind) {
        if (kind == null || value != null || !app.sideline.tv.data.Tmdb.configured) return@produceState
        delay(450)   // only for tiles you stop on or scroll past slowly
        value = app.sideline.tv.data.TmdbApi.poster(clean.first, clean.second, kind == "series")
    }
    val genres = remember(hq, clean) { if (kind != null) app.sideline.tv.data.TmdbApi.cachedGenres(clean.first, clean.second, kind == "series") else emptyList() }
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        FocusCard(onClick = onClick, onFocus = { focused = true; onFocus?.invoke() }, onMenu = onMenu, scaleOnFocus = 1.04f,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.hasFocus }) { _ ->
            AsyncImage(model = hq ?: image, contentDescription = name, placeholder = ColorPainter(C.Surface2),
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Rating in the bottom-right corner of the poster.
            if (badge != null) Text(badge, color = if (badgeColor == C.Live) Color.White else C.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(if (badgeColor == C.Live) C.Live else Color(0xCC0B0D10), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }
        // Full title (wraps, never cut short), accent-coloured while selected; then year · genre.
        Text(name, color = if (focused) C.Accent else C.Text, fontSize = 14.sp, lineHeight = 1.3.em,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
        val meta = metaOverride ?: listOfNotNull(year, genres.firstOrNull()).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp))
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
                Modifier.clip(RoundedCornerShape(16.dp)).background(C.Bg.copy(alpha = (100 - SidelineApp.prefs.popupTransparency.collectAsState().value) / 100f))
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
fun TvDialog(onDismiss: () -> Unit, dim: Boolean = true, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        if (!dim) {
            // Panels over video: don't darken the picture behind (the panel sets its own see-through level).
            val view = androidx.compose.ui.platform.LocalView.current
            val window = ((view as? androidx.compose.ui.window.DialogWindowProvider)
                ?: (view.parent as? androidx.compose.ui.window.DialogWindowProvider))?.window
            androidx.compose.runtime.SideEffect { window?.setDimAmount(0f) }
        }
        content()
    }
}

// ---------------- Confirmations ----------------

/** App-wide "Are you sure?" prompt, so favourites/deletes/hides never happen by accident. */
object Confirm {
    data class Req(val title: String, val message: String, val yes: String, val destructive: Boolean, val action: () -> Unit)
    var req by mutableStateOf<Req?>(null)

    fun ask(title: String, message: String = "", yes: String = "Yes", destructive: Boolean = false, action: () -> Unit) {
        req = Req(title, message, yes, destructive, action)
    }
}

/** Shows the pending confirmation. Destructive ones put the focus on Cancel, so a stray OK does nothing. */
@Composable
fun ConfirmHost() {
    val r = Confirm.req ?: return
    val yesFocus = remember(r) { FocusRequester() }
    val noFocus = remember(r) { FocusRequester() }
    LaunchedEffect(r) {
        delay(200)
        try { (if (r.destructive) noFocus else yesFocus).requestFocus() } catch (_: Exception) {}
    }
    TvDialog({ Confirm.req = null }) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(560.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(28.dp)) {
                Text(r.title, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (r.message.isNotBlank()) Text(r.message, color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip(r.yes, selected = !r.destructive, modifier = Modifier.focusRequester(yesFocus), onClick = {
                        Confirm.req = null
                        r.action()
                    })
                    Chip("Cancel", selected = false, modifier = Modifier.focusRequester(noFocus), onClick = { Confirm.req = null })
                }
            }
        }
    }
}

// ---------------- Search ----------------

/** The search entry on a page: shows the current search; OK opens [SearchDialog]. */
@Composable
fun SearchButton(query: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth(), scaleOnFocus = 1.01f, shape = RoundedCornerShape(10.dp)) { _ ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔍", fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text(if (query.isBlank()) hint else "\"$query\"  ·  OK to change", color = if (query.isBlank()) C.Muted else C.Text, fontSize = 16.sp)
        }
    }
}

/**
 * Search in its own window: keyboard opens straight away, Search/Done on the keyboard (or the Search button)
 * closes it and shows results. Keeping the text box out of the page stops it trapping the remote's focus.
 */
@Composable
fun SearchDialog(
    initial: String, title: String, onSearch: (String) -> Unit, onDismiss: () -> Unit,
    placeholder: String = "Type at least 2 letters", actionLabel: String = "Search", secret: Boolean = false,
) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length))) }
    val field = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(250)
        try { field.requestFocus(); keyboard?.show() } catch (_: Exception) {}
    }
    fun done() { keyboard?.hide(); onSearch(text.text.trim()) }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color(0xD92A2C47)), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.padding(top = 60.dp).width(720.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(28.dp)) {
                Text(title, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text(placeholder, color = C.Faint) },
                    visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
                        else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { done() }, onDone = { done() }),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = C.Accent, unfocusedBorderColor = C.Surface2,
                        focusedTextColor = C.Text, unfocusedTextColor = C.Text, cursorColor = C.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(actionLabel, true, onClick = { done() })
                    if (actionLabel == "Search") Chip("Clear", false, onClick = { text = androidx.compose.ui.text.input.TextFieldValue("") })
                    Chip("Cancel", false, onClick = onDismiss)
                }
            }
        }
    }
}

/** "Starting now" pop-up for TV-guide reminders, with Watch now. (The player has its own version.) */
/** Pop-up when a new episode of a show you follow (on Trakt) has reached your library. */
@Composable
fun NewEpisodeHost() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val list by app.sideline.tv.data.Trakt.newEpisodes.collectAsState()
    val cur = list.firstOrNull() ?: return
    val play = remember(cur) { FocusRequester() }
    fun next() { app.sideline.tv.data.Trakt.dismissNewEpisodes() }
    LaunchedEffect(cur) { delay(200); try { play.requestFocus() } catch (_: Exception) {} }
    TvDialog({ next() }) {
        Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.TopEnd) {
            Column(Modifier.width(500.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface).border(2.dp, C.Accent, RoundedCornerShape(16.dp)).padding(20.dp)) {
                Text("🔔 New episode in your library", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(app.sideline.tv.data.MovieInfoService.cleanTitle(cur.series.name).first, color = C.Text, fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                Text("S${cur.episode.season} E${cur.episode.number} · ${cur.episode.title}", color = C.Muted, fontSize = 14.sp, maxLines = 2)
                if (list.size > 1) Text("+ ${list.size - 1} more new " + if (list.size == 2) "episode" else "episodes",
                    color = C.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                    Chip("▶ Play now", true, modifier = Modifier.focusRequester(play), onClick = {
                        next(); app.sideline.tv.player.Play.episode(ctx, cur.episode, cur.series.name, cur.series.seriesId, cur.series.cover)
                    })
                    Chip("Later", false, onClick = { next() })
                }
            }
        }
    }
}

@Composable
fun ReminderHost() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var r by remember { mutableStateOf<app.sideline.tv.data.Reminder?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // Only while this screen is in front; when the player is showing, it shows its own pop-up instead.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { SidelineApp.reminders.due.collect { r = it } }
    }
    val cur = r ?: return
    val watch = remember(cur) { FocusRequester() }
    LaunchedEffect(cur) {
        delay(200); try { watch.requestFocus() } catch (_: Exception) {}
        delay(90_000); if (r == cur) r = null
    }
    TvDialog({ r = null }) {
        Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.TopEnd) {
            Column(Modifier.width(460.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface).border(2.dp, C.Accent, RoundedCornerShape(16.dp)).padding(20.dp)) {
                Text("🔔 Starting now", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(cur.title, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                Text("${cur.channelName} · ${timeOf(cur.start * 1000)}", color = C.Muted, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                    Chip("▶ Watch now", true, modifier = Modifier.focusRequester(watch), onClick = {
                        r = null
                        SidelineApp.repo.channel(cur.channelKey)?.let { app.sideline.tv.player.Play.channel(ctx, it) }
                    })
                    Chip("Dismiss", false, onClick = { r = null })
                }
            }
        }
    }
}
