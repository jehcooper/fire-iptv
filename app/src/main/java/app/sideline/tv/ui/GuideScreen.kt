package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.SidelineApp
import app.sideline.tv.data.Channel
import app.sideline.tv.data.EpgItem
import app.sideline.tv.player.Play
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private data class Slot(val item: EpgItem?, val start: Long, val end: Long)

/**
 * Classic TV guide: channels down the left, time across the top, a red line at "now".
 * Select a programme on now to watch; a past one on a catch-up channel to watch it from the start.
 * The window is 2½ hours; "Earlier" / "Later" move it by an hour.
 */
@Composable
fun ProgramGuide(channels: List<Channel>) {
    val ctx = LocalContext.current
    // Window start, rounded down to the half hour.
    val nowSec = System.currentTimeMillis() / 1000
    var windowStart by remember { mutableLongStateOf(nowSec - nowSec % 1800) }
    val windowLen = 150 * 60L
    var focused by remember { mutableStateOf<Pair<Channel, EpgItem?>?>(null) }
    var tick by remember { mutableLongStateOf(nowSec) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick = System.currentTimeMillis() / 1000 } }

    Column(Modifier.fillMaxSize()) {
        // Details of the focused programme.
        Column(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
            val f = focused
            if (f?.second == null) {
                Text(f?.first?.name ?: "Move through the guide to see what's on. OK on a show that's on now plays it.",
                    color = C.Muted, fontSize = 14.sp, maxLines = 2)
            } else {
                val p = f.second!!
                Text("${f.first.name}  ·  ${timeOf(p.start * 1000)} – ${timeOf(p.end * 1000)}", color = C.Accent, fontSize = 13.sp, maxLines = 1)
                Text(p.title, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (p.description.isNotBlank()) Text(p.description, color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val chanW = 170.dp
            val gridW = maxWidth - chanW - 8.dp
            val perSec = gridW / windowLen.toFloat()
            Column {
                // Time ruler with paging.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                    Row(Modifier.width(chanW), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("◀", false, onClick = { windowStart -= 3600 })
                        Chip("Now", false, onClick = { val n = System.currentTimeMillis() / 1000; windowStart = n - n % 1800 })
                        Chip("▶", false, onClick = { windowStart += 3600 })
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(gridW)) {
                        for (i in 0 until 5) {
                            val t = windowStart + i * 1800
                            Text(timeOf(t * 1000), color = C.Muted, fontSize = 12.sp, modifier = Modifier.offset(x = perSec * (i * 1800f)))
                        }
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                    items(channels, key = { it.key }) { c ->
                        GuideRow(c, windowStart, windowLen, chanW, gridW, perSec, tick,
                            onFocus = { focused = c to it },
                            onPick = { item ->
                                val now = System.currentTimeMillis() / 1000
                                when {
                                    item == null || now in item.start until item.end -> Play.channel(ctx, c, channels)
                                    item.end <= now && c.hasArchive -> Play.catchUp(ctx, c, item)
                                    item.end <= now -> Play.channel(ctx, c, channels)
                                    else -> { /* future: details already shown above */ }
                                }
                            })
                    }
                }
            }
            // "Now" line.
            val nowX = chanW + 8.dp + perSec * (tick - windowStart).toFloat()
            if (tick in windowStart..(windowStart + windowLen)) {
                Box(Modifier.offset(x = nowX, y = 40.dp).width(2.dp).fillMaxHeight().background(C.Live.copy(alpha = 0.8f)))
            }
        }
    }
}

@Composable
private fun GuideRow(
    c: Channel, windowStart: Long, windowLen: Long, chanW: Dp, gridW: Dp, perSec: Dp, now: Long,
    onFocus: (EpgItem?) -> Unit, onPick: (EpgItem?) -> Unit,
) {
    var items by remember(c.key) { mutableStateOf<List<EpgItem>?>(null) }
    LaunchedEffect(c.key) { items = SidelineApp.repo.epgCached(c) }
    val windowEnd = windowStart + windowLen
    val slots = remember(items, windowStart) {
        val list = (items ?: emptyList()).filter { it.end > windowStart && it.start < windowEnd && it.end > it.start }.sortedBy { it.start }
        buildList {
            var cursor = windowStart
            for (it in list) {
                val s = maxOf(it.start, windowStart)
                if (s > cursor + 60) add(Slot(null, cursor, s))
                val e = minOf(it.end, windowEnd)
                if (e > maxOf(s, cursor)) add(Slot(it, maxOf(s, cursor), e))
                cursor = maxOf(cursor, e)
            }
            if (cursor < windowEnd - 60) add(Slot(null, cursor, windowEnd))
        }
    }
    Row(Modifier.height(54.dp), verticalAlignment = Alignment.CenterVertically) {
        FocusCard(onClick = { onPick(null) }, onFocus = { onFocus(null) }, modifier = Modifier.width(chanW).fillMaxHeight(),
            scaleOnFocus = 1.0f, shape = RoundedCornerShape(8.dp)) { _ ->
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(width = 40.dp, height = 28.dp))
                Spacer(Modifier.width(8.dp))
                Text(c.name, color = C.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.width(gridW).fillMaxHeight()) {
            if (items == null) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(C.Surface.copy(alpha = 0.5f)))
            } else slots.forEach { slot ->
                val w = (perSec * (slot.end - slot.start).toFloat() - 3.dp).coerceAtLeast(6.dp)
                val airing = slot.item != null && now in slot.item.start until slot.item.end
                val past = slot.item != null && slot.item.end <= now
                FocusCard(
                    onClick = { onPick(slot.item) }, onFocus = { onFocus(slot.item) },
                    modifier = Modifier.width(w).fillMaxHeight().padding(end = 0.dp), scaleOnFocus = 1.0f, shape = RoundedCornerShape(8.dp),
                ) { f ->
                    Box(Modifier.fillMaxSize().background(when {
                        f -> C.Surface2
                        airing -> Color(0xFF16323A)
                        else -> C.Surface
                    }).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            slot.item?.title ?: "No information",
                            color = if (past && !f) C.Faint else if (slot.item == null) C.Faint else C.Text,
                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (past && c.hasArchive) Text("⟲", color = C.Gold, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
                Spacer(Modifier.width(3.dp))
            }
        }
    }
}
