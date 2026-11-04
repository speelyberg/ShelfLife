package com.shelflife.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * The DVD start-up ritual, recreated (not copied): ShelfLife's own anti-spoiler warning card, a parody of the
 * modern FBI anti-piracy card (res/drawable-nodpi/warning_card.png, made by work/make_fbi_seal.py and
 * work/make_fbi_card.py), then optionally
 * "Feature Presentation": one of the finished cuts with music and voice, picked by release catalog
 * ([Bumpers]): the Disney-style one (Extras.FEATURE_DISNEY) or the Paramount one (Extras.FEATURE_PARAMOUNT).
 * Private use only.
 * [card]: 0 the warning, 2 feature presentation ([clip]), which calls [onFeatureEnd] when it has played.
 * [prohibited] shows the player's circle-slash icon, which is what a skip attempt got you back then;
 * [skip] its ⏭ icon, as if you'd skipped ahead.
 */
@Composable
fun AntiPiracyScreen(card: Int, prohibited: Boolean, skip: Boolean = false, clip: String = Extras.FEATURE_DISNEY,
                     quickChoice: Int = 0, quickDeadline: Long = 0L, quickTotalMs: Long = 5_000L, onFeatureEnd: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // 0 the warning card, 2 feature clip, 3 QuickPlay, 4 Coming Soon, each filling the screen.
        // Hard cuts between every card, clip and trailer, the way a disc player cut.
        AnimatedContent(card, transitionSpec = { androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None }, label = "feature") { k ->
            if (k == 2) FeatureVideo(clip, onFeatureEnd)
            else if (k == 3) QuickPlayCard(quickChoice, quickDeadline, quickTotalMs)
            else if (k == 4) ComingSoonCard()
            else Image(painterResource(R.drawable.warning_card), "ShelfLife anti-spoiler warning", Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        }
        AnimatedVisibility(prohibited, Modifier.align(Alignment.TopEnd).padding(40.dp), enter = fadeIn(tween(80)), exit = fadeOut(tween(250))) {
            ProhibitedIcon()
        }
        // Over the full-width video, the ⏭ sits in the screen's corner, where a player put it.
        AnimatedVisibility(skip, Modifier.align(Alignment.TopEnd).padding(40.dp), enter = fadeIn(tween(80)), exit = fadeOut(tween(300))) {
            SkipIcon()
        }
    }
}

/** Select the intro by release, since Plex's production studio often differs from the distributor. */
object Bumpers {
    class Clip(val file: String, val fallbackMs: Long)   // the video in [Extras] and a timer that ends it if the player never does
    val PARAMOUNT = Clip(Extras.FEATURE_PARAMOUNT, 20_500)   // 17.8 s
    val DISNEY = Clip(Extras.FEATURE_DISNEY, 9_000)          // 6.6 s
    val FOX = Clip(Extras.FEATURE_FOX, 8_000)                // 5.2 s
    val WARNER = Clip(Extras.FEATURE_WARNER, 26_000)         // 23.0 s
    val SONY = Clip(Extras.FEATURE_SONY, 15_500)             // 12.9 s
    val UNIVERSAL = Clip(Extras.FEATURE_UNIVERSAL, 10_500)   // 8.0 s
    private val disney = listOf("disney", "pixar", "touchstone", "hollywood pictures", "caravan pictures", "miramax", "dimension films",
        "buena vista", "marvel studios", "marvel entertainment", "lucasfilm", "espn films", "national geographic")
    private val fox = listOf("20th century", "twentieth century", "fox 2000", "searchlight", "blue sky studios")
    private val sony = listOf("sony pictures", "columbia", "tristar", "screen gems", "revolution studios", "sony pictures animation", "sony pictures classics", "affirm films", "stage 6")
    private val warner = listOf("warner bros", "warner brothers", "new line", "castle rock", "hbo films", "dc films", "dc studios", "turner pictures", "lorimar")
    private val universal = listOf("universal pictures", "universal studios", "focus features", "illumination", "gramercy pictures", "october films", "usa films")
    // Studio Ghibli films Disney released in the US (Buena Vista, Miramax, Touchstone); GKIDS-only ones get no bumper.
    private val disneyGhibli = setOf("1986|castleinthesky", "1988|myneighbortotoro", "1989|kikisdeliveryservice", "1992|porcorosso",
        "1994|pompoko", "1995|whisperoftheheart", "1997|princessmononoke", "1999|myneighborstheyamadas", "2001|spiritedaway",
        "2002|thecatreturns", "2004|howlsmovingcastle", "2006|talesfromearthsea", "2008|ponyo", "2010|arrietty", "2013|thewindrises")
    @Volatile private var paramountReleases: Set<String>? = null
    @Volatile private var foxReleases: Set<String>? = null
    @Volatile private var warnerReleases: Set<String>? = null
    @Volatile private var disneyReleases: Set<String>? = null
    @Volatile private var universalReleases: Set<String>? = null
    private fun releaseKey(title: String, year: Int?): String {
        val plain = java.text.Normalizer.normalize(title.lowercase(java.util.Locale.ROOT)
            .replace("&", "and").replace("½", "12"), java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "")
        return "${year ?: 0}|$plain"
    }
    private fun load(context: android.content.Context, file: String) = context.assets.open(file).bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
    }
    private fun releases(context: android.content.Context): Set<String> = paramountReleases ?: synchronized(this) {
        paramountReleases ?: load(context, "paramount_releases.tsv").also { paramountReleases = it }
    }
    private fun fox(context: android.content.Context): Set<String> = foxReleases ?: synchronized(this) {
        foxReleases ?: load(context, "fox_releases.tsv").also { foxReleases = it }
    }
    private fun warner(context: android.content.Context): Set<String> = warnerReleases ?: synchronized(this) {
        warnerReleases ?: load(context, "warner_releases.tsv").also { warnerReleases = it }
    }
    private fun universal(context: android.content.Context): Set<String> = universalReleases ?: synchronized(this) {
        universalReleases ?: load(context, "universal_releases.tsv").also { universalReleases = it }
    }
    // Plex items with no year (unmatched metadata) are looked up by title alone.
    @Volatile private var titleOnly: Map<Set<String>, Set<String>> = emptyMap()
    private fun has(set: Set<String>, key: String, year: Int?): Boolean {
        if (year != null) return key in set
        val titles = titleOnly[set] ?: set.mapTo(HashSet()) { it.substringAfter('|') }.also { titleOnly = titleOnly + (set to it) }
        return key.substringAfter('|') in titles
    }
    private fun disneyList(context: android.content.Context): Set<String> = disneyReleases ?: synchronized(this) {
        disneyReleases ?: load(context, "disney_releases.tsv").also { disneyReleases = it }
    }
    fun forMovie(movie: Movie, context: android.content.Context): Clip? {
        val key = releaseKey(movie.title, movie.year)
        if (has(releases(context), key, movie.year) || movie.studio?.contains("paramount", ignoreCase = true) == true) return PARAMOUNT
        if (has(fox(context), key, movie.year)) return FOX
        if (has(warner(context), key, movie.year)) return WARNER
        if (has(universal(context), key, movie.year)) return UNIVERSAL   // Universal and Focus Features (and the labels before it)
        if (has(disneyList(context), key, movie.year)) return DISNEY   // Disney, Touchstone, Hollywood Pictures, and Miramax/Dimension in the Disney years
        if (key in disneyGhibli) return DISNEY
        return movie.studio?.let(::forStudio)?.takeIf { it != PARAMOUNT }
    }
    /** Which opener a company name belongs to (also keeps a producer's logo from repeating the distributor's). */
    fun forStudio(name: String): Clip? {
        val studio = name.lowercase(java.util.Locale.ROOT)
        return when {
            "paramount" in studio -> PARAMOUNT
            fox.any { it in studio } -> FOX
            warner.any { it in studio } -> WARNER
            sony.any { it in studio } -> SONY
            universal.any { it in studio } -> UNIVERSAL
            disney.any { it in studio } -> DISNEY
            else -> null
        }
    }
}

/** The feature presentation [clip] (a file in [Extras]), full screen, played by its own Media3 player into a
 *  TextureView (it fades and sits under the ⏭ like any view). Calls [onEnd] when it finishes, or if it can't play. */
@Composable
private fun FeatureVideo(clip: String, onEnd: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val end by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val player = androidx.compose.runtime.remember(clip) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            val file = Extras.file(clip)
            if (file != null) { setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file))); prepare(); playWhenReady = true }
            else end()  // not downloaded: straight on to the film
        }
    }
    androidx.compose.runtime.DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_ENDED) end() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { end() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    androidx.compose.ui.viewinterop.AndroidView({ android.view.TextureView(it).also(player::setVideoTextureView) }, Modifier.fillMaxSize())
}

/** ShelfLife's own start-up choice, in the spirit of the 2000s discs: QuickPlay runs straight into the film,
 *  Main Menu goes by way of the previews. QuickPlay picks itself when [deadline] passes. */
@Composable
private fun QuickPlayCard(choice: Int, deadline: Long, totalMs: Long) {
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), deadline) {
        while (value < deadline) { kotlinx.coroutines.delay(100); value = System.currentTimeMillis() }
    }
    val left = (deadline - now).coerceAtLeast(0L)
    val progress = if (totalMs > 0) (1f - left / totalMs.toFloat()).coerceIn(0f, 1f) else 1f
    // The After Effects background (Extras.QUICKPLAY_BG, with voiceover) carries the mark and the title;
    // the two choices and the countdown are drawn over it along the bottom. ◀ ▶ move between them.
    Box(Modifier.fillMaxSize().background(Color(0xFF1D3466))) {
        LoopVideo(Extras.QUICKPLAY_BG)
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 58.dp).fillMaxWidth(0.70f), horizontalArrangement = Arrangement.SpaceBetween) {
            QuickOption("QuickPlay", choice == 0)
            QuickOption("Previews", choice == 1)
            QuickOption("Main Menu", choice == 2)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(220.dp).height(3.dp).background(Color(0x33FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
            }
            Spacer(Modifier.height(4.dp))
            Text(if (left > 0) "QuickPlay begins in ${(left + 999) / 1000}" else "Starting…", color = Color(0xFFBFD3FF), fontSize = 12.sp)
        }
        Text("QuickPlay can be turned off in Settings", color = Color(0x99FFFFFF), fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 26.dp))
    }
}
/** A video from [Extras] under a card (nothing while it isn't downloaded), full frame, with its own sound; it plays once and holds its last frame. */
@Composable
private fun LoopVideo(clip: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = androidx.compose.runtime.remember(clip) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            Extras.file(clip)?.let { setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(it))); prepare(); playWhenReady = true }
        }
    }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
    androidx.compose.ui.viewinterop.AndroidView({ android.view.TextureView(it).also(player::setVideoTextureView) }, Modifier.fillMaxSize())
}
@Composable
private fun QuickOption(title: String, selected: Boolean) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Box(Modifier.width(200.dp).height(44.dp).background(if (selected) Color.White else Color(0x33FFFFFF), shape), contentAlignment = Alignment.Center) {
        Text(title, color = if (selected) Color(0xFF0B1A4A) else Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black)
    }
}
private val curtain = Brush.radialGradient(listOf(Color(0xFF16337A), Color(0xFF0A1A48), Color(0xFF040A22)), radius = 1500f)

/** The card before the previews; only shown when there is at least one trailer to follow it. */
@Composable
private fun ComingSoonCard() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("COMING SOON", color = Color(0xFFBDBDBD), fontSize = 20.sp, letterSpacing = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text("to own on DVD and Blu-ray", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The DVD player's "skip" mark (⏭), drawn like the prohibited one. */
@Composable
private fun SkipIcon() = Canvas(Modifier.size(54.dp)) {
    val stroke = size.minDimension * 0.12f
    val r = size.minDimension / 2 - stroke / 2
    drawCircle(Color.Black.copy(alpha = 0.35f), r + stroke / 2)
    drawCircle(Color.White, r, style = Stroke(stroke))
    val w = r * 0.42f; val h = r * 0.66f; val bar = r * 0.14f
    val x0 = center.x - (2 * w + bar) / 2; val cy = center.y
    for (k in 0..1) drawPath(Path().apply { moveTo(x0 + k * w, cy - h / 2); lineTo(x0 + (k + 1) * w, cy); lineTo(x0 + k * w, cy + h / 2); close() }, Color.White)
    drawRect(Color.White, androidx.compose.ui.geometry.Offset(x0 + 2 * w, cy - h / 2), Size(bar, h))
}

/** The DVD player's "operation prohibited" mark. */
@Composable
private fun ProhibitedIcon() = Canvas(Modifier.size(54.dp)) {
    val stroke = size.minDimension * 0.12f
    val r = size.minDimension / 2 - stroke / 2
    drawCircle(Color.Black.copy(alpha = 0.35f), r + stroke / 2)
    drawCircle(Color.White, r, style = Stroke(stroke))
    val d = r * 0.7071f
    drawLine(Color.White, Offset(center.x - d, center.y - d), Offset(center.x + d, center.y + d), stroke)
}
