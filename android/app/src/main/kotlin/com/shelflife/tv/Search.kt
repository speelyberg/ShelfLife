package com.shelflife.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Search, above the top shelf: [query] as typed, [results] matching it, [focus] -1 for the bar or the
 * highlighted result. [editing]: the on-screen keyboard is up. [first] is the first result row shown.
 */
data class SearchState(val query: String, val results: List<Movie>, val focus: Int, val editing: Boolean, val loading: Boolean, val first: Int = 0) {
    companion object { const val COLUMNS = 5; const val ROWS = 2; const val GRID_SCALE = 1.55f }   // the results run this many times the shelf's width
}

/**
 * The search bar, as wide as the shelf and just above the DVD tower ([shelfTop]: the rows' left x, right x and the
 * tower top's y in screen pixels), and below it the results as DVD cases, five to a row, over the blurred room.
 */
@Composable
fun SearchScreen(state: SearchState?, shelfTop: FloatArray?, covers: Map<String, ImageBitmap>) {
    val last = remember { mutableStateOf<SearchState?>(null) }
    if (state != null) last.value = state
    val density = LocalDensity.current
    val top = shelfTop ?: return
    val left = with(density) { top[0].toDp() }
    val width = with(density) { (top[1] - top[0]).toDp() }
    val shelfY = with(density) { top[2].toDp() }
    // The results are wider than the shelf (bigger cases), centred on it, kept inside the TV-safe margins.
    val screen = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp }
    val gridWidth = minOf(width * SearchState.GRID_SCALE, screen - 96.dp)
    val gridLeft = (left + width / 2 - gridWidth / 2).coerceIn(48.dp, screen - 48.dp - gridWidth)
    AnimatedVisibility(state != null, enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { -it / 12 }, exit = fadeOut(tween(200))) {
        val s = last.value ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize()) {
            // The bar sits just above the tower's top; the cases go below it, over the (blurred) shelf.
            val barH = 64.dp
            val gap = 18.dp
            val barY = shelfY - barH - gap
            SearchBar(s, Modifier.offset(left, barY).width(width).height(barH))
            if (s.results.isNotEmpty()) {
                Column(Modifier.offset(gridLeft, shelfY).width(gridWidth), verticalArrangement = Arrangement.spacedBy(gap)) {
                    val rows = s.results.chunked(SearchState.COLUMNS)
                    for (r in s.first until minOf(rows.size, s.first + SearchState.ROWS)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (c in 0 until SearchState.COLUMNS) {
                                val i = r * SearchState.COLUMNS + c
                                Box(Modifier.weight(1f)) {
                                    rows[r].getOrNull(c)?.let { DvdCase(it, covers[it.id], s.focus == i) }
                                }
                            }
                        }
                    }
                }
            } else if (s.query.isNotBlank() && !s.loading && !s.editing) {
                Text("No films match “${s.query}”", Modifier.offset(left, shelfY + 12.dp).width(width).padding(horizontal = 4.dp),
                    style = TextStyle(fontFamily = Inter, fontSize = 18.sp, color = Color.White.copy(alpha = 0.85f)))
            }
        }
    }
}

/** A milky see-through bar like the remote hints: a magnifier, then what you've typed (or a prompt). */
@Composable
private fun SearchBar(s: SearchState, modifier: Modifier) {
    val focused = s.focus < 0
    val ink = Color(0xFF1C1C21)
    Row(
        modifier.shadow(if (focused) 14.dp else 4.dp, RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .background(Color.White.copy(alpha = if (focused) 0.92f else 0.62f))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(32.dp))
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension * 0.34f
            val c = Offset(r + 2f, r + 2f)
            drawCircle(ink.copy(alpha = 0.75f), r, c, style = Stroke(size.minDimension * 0.11f))
            drawLine(ink.copy(alpha = 0.75f), Offset(c.x + r * 0.72f, c.y + r * 0.72f), Offset(size.width - 2f, size.height - 2f), size.minDimension * 0.13f)
        }
        Spacer(Modifier.width(16.dp))
        val prompt = s.query.isEmpty()
        val caret = if (s.editing) " |" else ""
        Text(if (prompt) (if (s.editing) "|" else "Search your films") else s.query + caret, Modifier.weight(1f),
            style = TextStyle(fontFamily = Inter, fontWeight = if (prompt) FontWeight.Normal else FontWeight.SemiBold, fontSize = 22.sp),
            color = ink.copy(alpha = if (prompt) 0.5f else 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (focused && !s.editing) Text("OK to type", style = TextStyle(fontFamily = Inter, fontSize = 15.sp), color = ink.copy(alpha = 0.55f))
        // The highlighted case's title, here rather than in the title pill (which would cover the cases).
        s.results.getOrNull(s.focus)?.let {
            Text(it.title, Modifier.padding(start = 16.dp), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
                color = ink.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.loading) Text("Loading library…", style = TextStyle(fontFamily = Inter, fontSize = 15.sp), color = ink.copy(alpha = 0.55f))
    }
}

/**
 * A DVD keep case seen from the front: black plastic with rounded corners and the hinge ridge down the
 * left, the cover under the clear sleeve (with its sheen), lifted and outlined when highlighted.
 */
@Composable
private fun DvdCase(movie: Movie, cover: ImageBitmap?, focused: Boolean) {
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "lift")
    Box(
        Modifier.fillMaxWidth().aspectRatio(135f / 190f)
            .graphicsLayer { val k = 1f + 0.08f * lift; scaleX = k; scaleY = k; translationY = -10f * lift }
            .shadow((6 + 12 * lift).dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0E0E10))
            .border((1 + 2 * lift).dp, Color.White.copy(alpha = 0.12f + 0.78f * lift), RoundedCornerShape(6.dp)),
    ) {
        // The cover: inset from the plastic's edge, more on the hinge side.
        Box(Modifier.fillMaxSize().padding(start = 9.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(2.dp))) {
            if (cover != null) Image(cover, movie.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Color(movie.color)), contentAlignment = Alignment.Center) {
                Text(movie.title, Modifier.padding(8.dp), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White), maxLines = 4)
            }
            // The clear sleeve's sheen, brightest at the top left.
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.06f)), Offset.Zero, Offset(400f, 600f))))
        }
        // The hinge ridge.
        Box(Modifier.padding(start = 3.dp, top = 6.dp, bottom = 6.dp).width(2.dp).fillMaxSize().background(Color.White.copy(alpha = 0.08f)))
    }
}
