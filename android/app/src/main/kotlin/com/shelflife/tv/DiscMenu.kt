package com.shelflife.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

enum class MenuPage { MAIN, EXTRAS, SETTINGS, SCENES }

/** One chapter for Scene Selection; [thumb] arrives later (or never, when Plex made no chapter images). */
data class SceneTile(val title: String, val startMs: Long, val thumb: ImageBitmap? = null)

data class DiscMenuState(
    val movie: Movie,
    val page: MenuPage = MenuPage.MAIN,
    val art: ImageBitmap? = null,
    val poster: ImageBitmap? = null,
    val resume: Boolean = false,
    val extras: List<String>? = null,  // null while loading
    val audio: String = "Default",
    val subtitles: String = "Off",
    val version: String? = null,  // only when the film has several versions
    val scenes: List<SceneTile>? = null,  // the film's chapters; null when Plex has none (no Scene Selection)
)

class DiscMenuActions(
    val play: () -> Unit,
    val open: (MenuPage) -> Unit,
    val playExtra: (Int) -> Unit,
    val cycleAudio: () -> Unit,
    val cycleSubtitles: () -> Unit,
    val cycleVersion: () -> Unit,
    val startOver: () -> Unit,
    val playScene: (Int) -> Unit,
)

/** The disc's menu, built from Plex art and details, driven by the remote (Material 3 for TV focus). */
@Composable
fun DiscMenu(state: DiscMenuState, actions: DiscMenuActions) {
    val first = remember(state.page) { FocusRequester() }
    // Staged entrance: the backdrop fades up first, then the menu slides in, then the cover.
    val artAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    var showContent by remember { androidx.compose.runtime.mutableStateOf(false) }
    // No staged entrance: the menu is simply there, as on a disc.
    LaunchedEffect(state.art) { if (state.art != null) artAlpha.snapTo(1f) }
    LaunchedEffect(Unit) { showContent = true }
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
        state.art?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = artAlpha.value }, contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF0000000), Color(0x99000000), Color(0x4D000000)))))
        if (state.page == MenuPage.SCENES && state.scenes != null) { SceneSelection(state, state.scenes, actions, first); return@Box }
        Row(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.animation.AnimatedVisibility(showContent, Modifier.weight(1f), enter = androidx.compose.animation.EnterTransition.None) {
            Column(Modifier.padding(end = 48.dp)) {
                Text(state.movie.title, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(listOfNotNull(state.movie.year?.toString(), state.movie.minutes?.let { "$it min" }, state.movie.rating).joinToString("  ·  "),
                    style = MaterialTheme.typography.titleMedium, color = Color(0xFFBDBDBD))
                Spacer(Modifier.height(36.dp))
                val items: List<Pair<String, () -> Unit>> = when (state.page) {
                    MenuPage.MAIN -> listOfNotNull(
                        (if (state.resume) "Resume Movie" else "Play Movie") to actions.play,
                        if (state.resume) "Start Over" to actions.startOver else null,
                        state.scenes?.let { "Scene Selection" to { actions.open(MenuPage.SCENES) } },
                        "Special Features" to { actions.open(MenuPage.EXTRAS) },
                        "Settings" to { actions.open(MenuPage.SETTINGS) },
                    )
                    MenuPage.EXTRAS -> (state.extras ?: emptyList()).mapIndexed { i, title -> title to { actions.playExtra(i) } } + ("Back" to { actions.open(MenuPage.MAIN) })
                    MenuPage.SCENES -> emptyList()
                    MenuPage.SETTINGS -> listOfNotNull(
                        state.version?.let { "Play Version: $it" to actions.cycleVersion },
                        "Audio: ${state.audio}" to actions.cycleAudio,
                        "Subtitles: ${state.subtitles}" to actions.cycleSubtitles,
                        "Back" to { actions.open(MenuPage.MAIN) },
                    )
                }
                if (state.page == MenuPage.EXTRAS) {
                    Text("SPECIAL FEATURES", style = MaterialTheme.typography.labelLarge, color = Color(0xFFBDBDBD), letterSpacing = 4.sp)
                    if (state.extras?.isEmpty() != false) Text(if (state.extras == null) "Loading…" else "No special features for this film in Plex.", style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.padding(vertical = 12.dp))
                }
                if (state.page == MenuPage.SETTINGS) Text("SETTINGS", style = MaterialTheme.typography.labelLarge, color = Color(0xFFBDBDBD), letterSpacing = 4.sp)
                // Scrolls when there are more items than fit (long special-features lists).
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(items) { i, (label, action) ->
                        ListItem(
                            selected = false, onClick = action,
                            headlineContent = { Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            // DVD-menu contrast: readable white items, the focused one a white bar with dark text.
                            colors = ListItemDefaults.colors(contentColor = Color.White.copy(alpha = 0.85f), focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("BACK  ${if (state.page == MenuPage.MAIN) "eject the disc" else "main menu"}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9E9E9E))
            }
            }
            androidx.compose.animation.AnimatedVisibility(showContent && state.poster != null, enter = androidx.compose.animation.EnterTransition.None) {
                state.poster?.let {
                    Image(it, null, Modifier.size(width = 260.dp, height = 390.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                }
            }
        }
    }
    LaunchedEffect(state.page, state.extras?.size, showContent) {
        if (showContent) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    }
}

/**
 * Scene Selection, as on a DVD: four chapter stills per page in a 2 x 2 grid, numbered, with
 * Previous / Main Menu / Next underneath. OK on a still plays the film from that chapter.
 */
@Composable
private fun SceneSelection(state: DiscMenuState, scenes: List<SceneTile>, actions: DiscMenuActions, first: FocusRequester) {
    var page by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val pages = (scenes.size + 3) / 4
    val focusFirst = remember { FocusRequester() }
    val tileBorder = androidx.tv.material3.ClickableSurfaceDefaults.border(
        focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(8.dp)))
    val buttonColors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White.copy(alpha = 0.85f),
        focusedContainerColor = Color.White, focusedContentColor = Color.Black)
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SCENE SELECTION", style = MaterialTheme.typography.labelLarge, color = Color(0xFFBDBDBD), letterSpacing = 4.sp)
        Text(state.movie.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                for (col in 0 until 2) {
                    val i = page * 4 + row * 2 + col
                    val scene = scenes.getOrNull(i)
                    Column(Modifier.width(240.dp)) {
                        if (scene != null) {
                            androidx.tv.material3.Surface(onClick = { actions.playScene(i) },
                                modifier = Modifier.size(240.dp, 135.dp).then(if (row == 0 && col == 0) Modifier.focusRequester(focusFirst) else Modifier),
                                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f), border = tileBorder,
                                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1C1C20), focusedContainerColor = Color(0xFF1C1C20))) {
                                scene.thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                    ?: Text("${i + 1}", Modifier.align(Alignment.Center), style = MaterialTheme.typography.displayMedium, color = Color.White.copy(alpha = 0.5f))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("${i + 1}.  ${scene.title}", style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            @Composable fun button(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
                androidx.tv.material3.Surface(onClick = onClick, enabled = enabled, modifier = modifier, colors = buttonColors,
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(50))) {
                    Text(label, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else Color.White.copy(alpha = 0.25f))
                }
            // Previous / Next only when there's a page that way (a greyed button just sits in the way).
            if (page > 0) button("◀  Previous", true) { page-- }
            button("Main Menu", true) { actions.open(MenuPage.MAIN) }
            if (page < pages - 1) button("Next  ▶", true) { page++ }
        }
        Text("Page ${page + 1} of $pages", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = Color(0xFF9E9E9E))
    }
    LaunchedEffect(page) { kotlinx.coroutines.delay(60); runCatching { focusFirst.requestFocus() } }
}

/**
 * Startup: the logo centred on a full-screen dark projector-blue gradient, a thin loading bar beneath tracking
 * real progress (Plex, then each spine printed), then a fade into the room.
 */
@Composable
fun LoadingCurtain(visible: Boolean, progress: Float, logoStart: Boolean, filmStop: Boolean, onLogoSettled: () -> Unit, onShown: () -> Unit = {}) {
    // Starts fully visible (no fade-in), so nothing behind it ever shows first.
    val state = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(true) }
    state.targetState = visible
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }
        onShown()
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = state,
        enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(800)),
    ) {
        val shown by androidx.compose.animation.core.animateFloatAsState(progress.coerceIn(0f, 1f), androidx.compose.animation.core.tween(350), label = "load")
        Box(
            Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF333F54), Color(0xFF1C2433), Color(0xFF0F131C)))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The logo itself (no tile); the full-screen gradient is the backdrop.
                AnimatedLogo(Modifier.size(width = 152.dp, height = 240.dp), logoStart, filmStop, onLogoSettled)
                Spacer(Modifier.height(28.dp))
                Box(Modifier.width(200.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.85f)))
                }
            }
        }
    }
}

/**
 * The logo, animated: the film's sprocket holes run downwards (motion-blurred, only inside the strip's black
 * border) until [filmStop], then ease to a halt in their own places; the last book on each shelf stands straight
 * up until [booksStart], then tips left onto its neighbours, top shelf first, into the logo's own pose. Once both
 * have finished it is just the still logo. All geometry is in logo.png pixels (460×728), measured from the image.
 */
@Composable
fun AnimatedLogo(modifier: Modifier, booksStart: Boolean, filmStop: Boolean, onSettled: () -> Unit) {
    val logo = ImageBitmap.imageResource(R.drawable.logo)
    val tilts = remember { List(3) { androidx.compose.animation.core.Animatable(0f) } }
    val film = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }  // how far the holes have run
    val blur = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }  // the streak length this frame
    var booksDone by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var filmDone by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val stop by androidx.compose.runtime.rememberUpdatedState(filmStop)
    // Held upright until [booksStart]: the room's GPU warm-up behind the curtain stalls the UI, so the fall waits it out.
    LaunchedEffect(booksStart) {
        if (!booksStart) return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            tilts.forEachIndexed { i, tilt ->
                launch {
                    tilt.animateTo(13f, androidx.compose.animation.core.keyframes {
                        durationMillis = 850 + i * 150
                        0f at 150 + i * 150                                                // stand up straight
                        13f at 600 + i * 150 using androidx.compose.animation.core.EaseIn  // fall onto the neighbour
                        11f at 700 + i * 150 using androidx.compose.animation.core.EaseOut // small bounce
                    })
                }
            }
        }
        booksDone = true
    }
    LaunchedEffect(Unit) {
        val top = 608f; val pitch = 76f    // full speed: eight holes a second
        var last = 0L; var speed = 0f
        while (!stop) androidx.compose.runtime.withFrameNanos { t ->
            val dt = if (last == 0L) 0f else minOf((t - last) / 1e9f, 0.05f); last = t
            speed = minOf(top, speed + top * dt / 0.3f)   // up to speed in 0.3 s
            film.floatValue += speed * dt; blur.floatValue = speed / 24f
        }
        // Slow down evenly (about 1.8 s) to land on a whole number of holes, so they stop in the logo's places.
        val from = film.floatValue; val v = maxOf(speed, 1f)
        val distance = kotlin.math.ceil((from + v * 0.9f) / pitch) * pitch - from
        val duration = 2 * distance / v; var elapsed = 0f
        while (elapsed < duration) androidx.compose.runtime.withFrameNanos { t ->
            elapsed = minOf(duration, elapsed + if (last == 0L) 0f else minOf((t - last) / 1e9f, 0.05f)); last = t
            val k = 1 - elapsed / duration
            film.floatValue = from + distance * (1 - k * k); blur.floatValue = v * k / 24f
        }
        filmDone = true
    }
    if (booksDone && filmDone) {
        LaunchedEffect(Unit) { onSettled() }
        Image(logo, "ShelfLife", modifier); return
    }
    val pixels = remember(logo) { logo.toPixelMap() }
    val panel = pixels[350, 90]; val black = pixels[30, 300]; val hole = pixels[55, 98]
    val streak = Color(hole.red - black.red, hole.green - black.green, hole.blue - black.blue)  // added onto the black
    // Per shelf: the book's length, its centre (for its colour), and the shelf's top edge (its pivot's y).
    val books = remember { listOf(Triple(103.6f, 160, 214f), Triple(123.6f, 358, 422f), Triple(109.6f, 573, 630f)) }
    val frame = remember {
        androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(20f, 20f, 440f, 708f, androidx.compose.ui.geometry.CornerRadius(38f))) }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val s = minOf(size.width / logo.width, size.height / logo.height)
        translate((size.width - logo.width * s) / 2, (size.height - logo.height * s) / 2) {
            scale(s, s, androidx.compose.ui.geometry.Offset.Zero) {
                drawImage(logo)
                clipPath(frame) {
                    // Paint out the still holes, then draw them run on by [film]: each streaked over [blur] as
                    // copies added onto the black, fading in and out where the run enters and leaves the column.
                    val run = film.floatValue % 76f; val streakLen = blur.floatValue
                    val copies = if (streakLen < 0.5f) 1 else 8
                    for (x in floatArrayOf(20f, 368f)) drawRect(black, androidx.compose.ui.geometry.Offset(x, 20f), androidx.compose.ui.geometry.Size(72f, 688f))
                    for (k in -1..8) {
                        val y = 76f + 76f * k + run
                        val centre = y + 22f
                        val fade = (minOf(centre - 22f, 706f - centre) / 76f).coerceIn(0f, 1f)
                        if (fade == 0f) continue
                        for (c in 0 until copies) {
                            val dy = if (copies == 1) 0f else -streakLen * c / (copies - 1)
                            for (x in floatArrayOf(38f, 388f)) drawRoundRect(streak, androidx.compose.ui.geometry.Offset(x, y + dy), androidx.compose.ui.geometry.Size(34f, 44f),
                                androidx.compose.ui.geometry.CornerRadius(8f), alpha = fade / copies, blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
                        }
                    }
                }
                books.forEachIndexed { i, (length, centreY, shelfTop) ->
                    // Paint out the still leaning book, then draw it at its current tilt about its bottom-left corner.
                    drawRect(panel, androidx.compose.ui.geometry.Offset(274f, shelfTop - 144f), androidx.compose.ui.geometry.Size(86f, 144f))
                    val pivot = androidx.compose.ui.geometry.Offset(304f, shelfTop)
                    rotate(-tilts[i].value, pivot) {
                        drawRoundRect(pixels[306, centreY], androidx.compose.ui.geometry.Offset(304f, shelfTop - length),
                            androidx.compose.ui.geometry.Size(30f, length), androidx.compose.ui.geometry.CornerRadius(5f))
                    }
                }
            }
        }
    }
}

/** DVD-player style indicator in the top-left corner (inside the TV-safe area). */
@Composable
fun OnScreenDisplay(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
        Text(text, color = Color(0xFF8CFF99), fontSize = 44.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}
