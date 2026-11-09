package app.sideline.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sideline.tv.sports.Game
import app.sideline.tv.sports.Situation
import kotlin.math.roundToInt

/** "MIZ 64% ▲" — the team more likely to win, and which way that just moved. */
fun winProbText(g: Game): String? {
    val h = g.homeWinPct ?: return null
    val home = g.home ?: return null
    val away = g.away ?: return null
    val homeFav = h >= 0.5
    val pct = ((if (homeFav) h else 1 - h) * 100).roundToInt()
    val who = (if (homeFav) home else away).let { it.abbr.ifBlank { it.shortName } }
    val prev = g.prevHomeWinPct
    val arrow = when {
        prev == null || kotlin.math.abs(prev - h) < 0.005 -> ""
        (h > prev) == homeFav -> " ▲"
        else -> " ▼"
    }
    return "$who $pct%$arrow"
}

/** One compact line under a pinned score (check [hasLiveStrip] first). */
@Composable
fun LiveStrip(g: Game) {
    val sit = g.situation
    val hk = g.hockey
    val sc = g.soccer
    val wp = winProbText(g)
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            sit != null && (sit.downDistance.isNotBlank() || sit.yardLine != null) -> {
                FieldBar(g, sit, 170.dp)
                Spacer(Modifier.width(10.dp))
                Text(sit.downDistance, color = if (sit.redZone) C.Live else C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            hk != null -> {
                hk.sogAway?.let { a -> hk.sogHome?.let { h -> Text("SOG $a–$h", color = C.Text, fontSize = 13.sp) } }
                hockeyBadge(g)?.let { (text, color) -> Spacer(Modifier.width(8.dp)); Badge(text, color) }
            }
            sc != null && sc.possAway != null && sc.possHome != null -> {
                PossessionBar(g, sc.possAway, sc.possHome, 150.dp)
                if (sc.shotsAway != null && sc.shotsHome != null) {
                    Spacer(Modifier.width(10.dp))
                    Text("Shots ${sc.shotsAway}–${sc.shotsHome}", color = C.Muted, fontSize = 12.sp)
                }
            }
        }
        if (wp != null) {
            Spacer(Modifier.width(10.dp))
            Text("Win $wp", color = C.Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

fun hasLiveStrip(g: Game): Boolean = g.isLive && (
    (g.situation != null && (g.situation.downDistance.isNotBlank() || g.situation.yardLine != null)) ||
        (g.hockey != null && (g.hockey.sogAway != null || g.hockey.ppSide != null || g.hockey.emptyNetSide != null)) ||
        (g.soccer?.possAway != null) || g.homeWinPct != null
    )

/** "PP TOR 1:24 (5v4)" or "Empty net MIZ". */
fun hockeyBadge(g: Game): Pair<String, Color>? {
    val hk = g.hockey ?: return null
    fun abbr(side: String) = (if (side == "home") g.home else g.away)?.let { it.abbr.ifBlank { it.shortName } } ?: side
    return when {
        hk.emptyNetSide != null -> "Empty net ${abbr(hk.emptyNetSide)}" to C.Live
        hk.ppSide != null -> listOfNotNull("PP ${abbr(hk.ppSide)}", hk.ppTime, hk.strength?.let { "($it)" }).joinToString(" ") to C.Gold
        hk.strength != null -> hk.strength to C.Muted
        else -> null
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Text(text, color = C.Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** Fuller version for the Games panel and the game screen. */
@Composable
fun LiveDetailsFull(g: Game) {
    if (!g.isLive && g.soccer?.goals.isNullOrEmpty()) return
    Column {
        val sit = g.situation
        if (g.isLive && sit != null) {
            if (sit.downDistance.isNotBlank() || sit.yardLine != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    FieldBar(g, sit, 220.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(sit.downDistance, color = if (sit.redZone) C.Live else C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (sit.lastPlay.isNotBlank()) Text("Last play: ${sit.lastPlay}", color = C.Faint, fontSize = 12.sp, maxLines = 2)
        }
        g.hockey?.let { hk ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                if (hk.sogAway != null && hk.sogHome != null) {
                    Text("Shots on goal  ${g.away?.abbr} ${hk.sogAway} – ${hk.sogHome} ${g.home?.abbr}", color = C.Text, fontSize = 14.sp)
                    Spacer(Modifier.width(10.dp))
                }
                hockeyBadge(g)?.let { (text, color) -> Badge(text, color) }
            }
            hk.lastGoal?.let { Text("🚨 Last goal: $it", color = C.Muted, fontSize = 13.sp, maxLines = 2) }
        }
        g.soccer?.let { sc ->
            if (sc.possAway != null && sc.possHome != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Possession", color = C.Muted, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    PossessionBar(g, sc.possAway, sc.possHome, 220.dp)
                }
            }
            val parts = listOfNotNull(
                if (sc.shotsAway != null && sc.shotsHome != null)
                    "Shots ${sc.shotsAway}${sc.onTargetAway?.let { " ($it)" } ?: ""} – ${sc.shotsHome}${sc.onTargetHome?.let { " ($it)" } ?: ""}" else null,
                if (sc.cornersAway != null && sc.cornersHome != null) "Corners ${sc.cornersAway}–${sc.cornersHome}" else null,
                if (sc.redAway + sc.redHome > 0) "Red cards ${"🟥".repeat(sc.redAway)}${if (sc.redAway == 0) "0" else ""} – ${"🟥".repeat(sc.redHome)}${if (sc.redHome == 0) "0" else ""}" else null,
            )
            if (parts.isNotEmpty()) Text(parts.joinToString("  ·  ") + if (sc.onTargetAway != null) "   (on target)" else "",
                color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            sc.goals.forEach { goal ->
                val team = (if (goal.home) g.home else g.away)?.let { it.abbr.ifBlank { it.shortName } } ?: ""
                Text(
                    "⚽ ${goal.minute}  ${goal.scorer}${if (goal.kind.isNotBlank()) " (${goal.kind})" else ""}" +
                        (goal.assist?.let { "  · assist $it" } ?: "") + "  · $team",
                    color = C.Muted, fontSize = 13.sp,
                )
            }
        }
        winProbText(g)?.let { wp ->
            Text("Win probability: $wp", color = C.Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Two-colour possession bar in team colours, away on the left. */
@Composable
fun PossessionBar(g: Game, away: Double, home: Double, width: Dp) {
    val total = (away + home).takeIf { it > 0 } ?: 100.0
    val a = (away / total).toFloat().coerceIn(0.05f, 0.95f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${away.roundToInt()}%", color = C.Text, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Row(Modifier.width(width).height(10.dp).clip(RoundedCornerShape(5.dp))) {
            Box(Modifier.weight(a).fillMaxHeight().background(C.hex(g.away?.color, Color(0xFF5B7A99))))
            Box(Modifier.weight(1f - a).fillMaxHeight().background(C.hex(g.home?.color, Color(0xFFB0BEC5))))
        }
        Spacer(Modifier.width(6.dp))
        Text("${home.roundToInt()}%", color = C.Text, fontSize = 12.sp)
    }
}

/**
 * Mini football field: away team's end zone on the left, home on the right.
 * White dot = ball, arrow = direction of the drive, red tint inside the 20.
 */
@Composable
fun FieldBar(g: Game, sit: Situation, width: Dp) {
    val away = g.away
    val home = g.home
    val fromLeft: Int? = sit.yardLine?.let { y ->
        when {
            y == 50 -> 50
            sit.territory != null && away != null && sit.territory.equals(away.abbr, true) -> y
            sit.territory != null && home != null && sit.territory.equals(home.abbr, true) -> 100 - y
            else -> null
        }
    }
    val awayHasBall = sit.possessionTeamId != null && sit.possessionTeamId == away?.id
    Box(Modifier.width(width).height(14.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E5C33))) {
        Box(Modifier.width(width * 0.08f).fillMaxHeight().background(C.hex(away?.color, Color(0xFF2B3A48))))
        Box(Modifier.width(width * 0.08f).fillMaxHeight().align(Alignment.CenterEnd).background(C.hex(home?.color, Color(0xFF2B3A48))))
        Box(Modifier.width(1.dp).fillMaxHeight().align(Alignment.Center).background(Color(0x66FFFFFF)))
        if (sit.redZone) {
            // Away team drives toward the home end zone on the right, and vice versa.
            Box(Modifier.width(width * 0.18f).fillMaxHeight().align(if (awayHasBall) Alignment.CenterEnd else Alignment.CenterStart)
                .background(Color(0x55FF4D5E)))
        }
        if (fromLeft != null) {
            val x = width * 0.08f + (width * 0.84f) * (fromLeft / 100f)
            Text(
                if (awayHasBall) "●▸" else "◂●",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterStart).offset(x = x - (if (awayHasBall) 4.dp else 12.dp)),
            )
        }
    }
}
