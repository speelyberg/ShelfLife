package com.shelflife.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Modifier
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shadow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/** What the overlay shows; rebuilt by MainActivity after every remote press. */
data class OverlayState(
    val title: String? = null,             // the highlighted film, shown before the key hints
    val keys: List<Pair<String, String>>,  // remote button -> what it does
    val held: Movie? = null,               // details card while holding a case
    val status: String? = null,            // short Plex status, top of the screen
    val signIn: SignIn? = null,            // the plex.tv/link panel
    val scroll: Int = 0,                   // ▲ ▼ steps through a long synopsis
    val cardColor: Int? = null,            // the held film's main cover colour
    val settings: SettingsPanel? = null,   // the settings panel, when open
    val action: String? = null,            // one plain pill along the bottom ("Play Movie") instead of the key hints
    val centerHints: Boolean = false,      // hints and the action pill sit bottom-centre rather than in the corner
    val hintsShown: Boolean = true,        // false once the remote has been idle a few seconds
    val gear: Boolean? = null,             // the Settings gear, lower left in the room (true = highlighted)
    val dpad: Dpad? = null,                // what each direction and OK do, laid out around a drawn D-pad
)

/** What the remote's D-pad does here; null = nothing. [hold] is a long press on OK. */
data class Dpad(val up: String? = null, val down: String? = null, val left: String? = null, val right: String? = null,
                val ok: String? = null, val hold: String? = null)

/** One settings row; [value] is shown on the right ("On", "Off", or what OK does). */
data class SettingRow(val title: String, val detail: String, val value: String, val header: Boolean = false)   // header: a section that opens and closes
data class SettingsPanel(val rows: List<SettingRow>, val focus: Int)

/** code == null while a code is being fetched. */
data class SignIn(val code: String?, val note: String)

/**
 * Material 3 for TV: dark scheme, type scale, and the TV-safe margins
 * (48 dp left/right, 27 dp top/bottom) so nothing is lost to overscan.
 */
@Composable
fun Overlay(state: OverlayState) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            // Just right of the case in your hands (its right edge sits ~395 dp across the screen).
            // Each piece eases in and out; the last content is kept so it can animate away.
            val held = remember { mutableStateOf<Pair<Movie, Int?>?>(null) }
            if (state.held != null) held.value = state.held to state.cardColor
            AnimatedVisibility(state.held != null, Modifier.align(Alignment.CenterStart).padding(start = 372.dp),
                // MainActivity shows the card once the case has landed and it leaves quickly: on the Onn's
                // GPU, the card animating during the focus pull halved the pull's frame rate.
                enter = fadeIn(tween(300)) + slideInHorizontally(tween(340)) { it / 6 },
                exit = fadeOut(tween(120))) {
                held.value?.let { (movie, color) -> DetailsCard(movie, state.scroll, color, Modifier) }
            }
            val status = remember { mutableStateOf("") }
            if (state.status != null) status.value = state.status
            AnimatedVisibility(state.status != null, Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { -it / 2 }, exit = fadeOut(tween(250))) { StatusChip(status.value, Modifier) }
            val signIn = remember { mutableStateOf<SignIn?>(null) }
            if (state.signIn != null) signIn.value = state.signIn
            AnimatedVisibility(state.signIn != null, Modifier.align(Alignment.Center),
                enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.96f), exit = fadeOut(tween(220))) { signIn.value?.let { SignInPanel(it, Modifier) } }
            val settings = remember { mutableStateOf<SettingsPanel?>(null) }
            if (state.settings != null) settings.value = state.settings
            AnimatedVisibility(state.settings != null, Modifier.align(Alignment.Center),
                enter = fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.96f), exit = fadeOut(tween(200))) { settings.value?.let { SettingsCard(it, Modifier) } }
            // The highlighted film's title: its own pill on the left, staying up while you browse
            // (the remote hints on the right come and go).
            val title = remember { mutableStateOf("") }
            if (state.title != null) title.value = state.title
            AnimatedVisibility(state.title != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                TitlePill(title.value)
            }
            AnimatedVisibility(state.gear != null, Modifier.align(Alignment.BottomStart), enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                GearButton(state.gear == true)
            }
            val action = remember { mutableStateOf("") }
            if (state.action != null) action.value = state.action
            AnimatedVisibility(state.action != null, Modifier.align(if (state.centerHints) Alignment.BottomCenter else Alignment.BottomEnd), enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                ActionPill(action.value)
            }
            val bar = remember { mutableStateOf(state) }
            if (state.keys.isNotEmpty() || state.dpad != null) bar.value = state
            // The hints sit in the corner and step aside when the remote goes quiet: a small spring up on
            // the next press, a slow fade away.
            AnimatedVisibility((state.keys.isNotEmpty() || state.dpad != null) && state.hintsShown, Modifier.align(if (bar.value.centerHints) Alignment.BottomCenter else Alignment.BottomEnd),
                enter = fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it / 3 },
                exit = fadeOut(tween(700))) {
                KeyHints(bar.value, Modifier)
            }
            val press = bar.value.dpad?.takeIf { it.ok != null || it.hold != null }
            AnimatedVisibility(press != null && state.dpad != null && state.hintsShown && state.status == null, Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { -it / 3 },
                exit = fadeOut(tween(700))) {
                val last = remember { mutableStateOf<Dpad?>(null) }
                if (press != null) last.value = press
                last.value?.let { PressHint(it, Modifier) }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier, shape = RoundedCornerShape(50),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
    ) { Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.titleSmall) }
}

/**
 * Settings in the bottom bar's milky style: one line per setting, the highlighted one explained
 * underneath, and the remote keys at the foot of the card (the bottom bar steps aside).
 */
@Composable
private fun SettingsCard(panel: SettingsPanel, modifier: Modifier) {
    val ink = Color(0xFF2C2C30)
    val soft = Color(0xFF55555B)
    // Never taller than the screen: when the settings don't all fit, their list scrolls to keep the
    // highlighted one in view, and the explanation and keys stay at the foot of the card.
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val scroll = rememberScrollState()
    val rowSpans = remember { HashMap<Int, IntRange>() }
    LaunchedEffect(panel.focus) {
        val span = rowSpans[panel.focus] ?: return@LaunchedEffect
        val view = scroll.viewportSize
        if (span.first < scroll.value) scroll.animateScrollTo(span.first)
        else if (span.last > scroll.value + view) scroll.animateScrollTo(span.last - view)
    }
    Surface(
        modifier = modifier.width(620.dp).heightIn(max = screen - 40.dp), shape = RoundedCornerShape(20.dp),
        colors = SurfaceDefaults.colors(containerColor = Color(0xFFF4F4F2).copy(alpha = 0.9f), contentColor = ink),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Settings", Modifier.padding(horizontal = 12.dp), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = ink)
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) { panel.rows.forEachIndexed { i, row ->
                Row(
                    Modifier.fillMaxWidth().background(if (i == panel.focus) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                        .onGloballyPositioned { c -> val top = c.positionInParent().y.toInt(); rowSpans[i] = top..(top + c.size.height) }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.header) {
                        Text(row.title.uppercase(), Modifier.weight(1f).padding(top = if (i == 0) 0.dp else 8.dp), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.2.sp, color = soft)
                        Text(row.value, fontFamily = Inter, fontSize = 15.sp, color = soft)
                    } else {
                        Text(row.title, Modifier.weight(1f), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = ink)
                        val on = row.value == "On"
                        Box(
                            Modifier.background(if (on) ink else Color.Transparent, RoundedCornerShape(50))
                                .border(1.dp, if (on) ink else Color(0xFF8E8E94), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 3.dp),
                        ) { Text(row.value, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (on) Color.White else ink) }
                    }
                }
                Spacer(Modifier.height(2.dp))
            } }
            Spacer(Modifier.height(10.dp))
            Text(panel.rows.getOrNull(panel.focus)?.detail.orEmpty(), Modifier.padding(horizontal = 12.dp).heightIn(min = 36.dp),
                fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, color = soft)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                KeyHint("▲ ▼", "Choose", ink, soft); KeyHint("OK", "Change", ink, soft); KeyHint("BACK", "Done", ink, soft)
            }
        }
    }
}

/** Big enough to read from the couch: the code is the hero. */
@Composable
private fun SignInPanel(signIn: SignIn, modifier: Modifier) {
    Surface(
        modifier = modifier.width(620.dp), shape = RoundedCornerShape(28.dp),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Connect your Plex library", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            Text("On your phone or computer, go to", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("plex.tv/link", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("and enter", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(16.dp), colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text(signIn.code ?: "····", Modifier.padding(horizontal = 36.dp, vertical = 12.dp), style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 12.sp))
            }
            Spacer(Modifier.height(20.dp))
            Text(signIn.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The card beside the case in your hands, in the cover's main colour. Text is white with a soft
 * shadow on dark colours, dark grey on light ones. (Pinned fallback: the bottom bar's milky white.)
 */
@Composable
private fun DetailsCard(movie: Movie, scroll: Int, cardColor: Int?, modifier: Modifier) {
    val synopsis = rememberScrollState()
    LaunchedEffect(scroll) { synopsis.animateScrollTo(scroll * 160) }
    // No cover colours yet: the bottom bar's milky white with dark grey text.
    val base = cardColor?.let { Color(it) } ?: Color(0xFFF4F4F2)
    val light = base.luminance() > 0.5f
    val ink = if (light) Color(0xFF2C2C30) else Color.White
    val soft = if (light) Color(0xFF4A4A50) else Color.White.copy(alpha = 0.8f)
    val shadow = if (light) null else Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 6f)
    Surface(
        modifier = modifier.width(400.dp),
        shape = RoundedCornerShape(16.dp),
        // See-through like the bottom bar; the blurred room behind keeps the text readable.
        colors = SurfaceDefaults.colors(containerColor = base.copy(alpha = 0.72f), contentColor = ink),
    ) {
        Column(Modifier.padding(28.dp)) {
            Text(movie.title, style = MaterialTheme.typography.headlineMedium.copy(shadow = shadow), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(movie.year?.toString(), movie.rating, movie.minutes?.let { "$it min" }).joinToString("  ·  "),
                style = MaterialTheme.typography.titleSmall.copy(shadow = shadow), color = soft,
            )
            Spacer(Modifier.height(16.dp))
            // A long synopsis scrolls inside the card (▲ ▼) instead of stretching it off screen.
            Box(Modifier.heightIn(max = 190.dp).verticalScroll(synopsis, enabled = false)) {
                Text(movie.summary, style = MaterialTheme.typography.bodyLarge.copy(shadow = shadow), color = ink)
            }
            if (synopsis.maxValue > 0) {
                Spacer(Modifier.height(10.dp))
                Text(if (synopsis.value < synopsis.maxValue) "▼  More" else "▲  Back to top", style = MaterialTheme.typography.labelLarge.copy(shadow = shadow), color = soft)
            }
        }
    }
}

/** Inter: an open-licence typeface in the same spirit as Apple's San Francisco. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/**
 * The highlighted film's title: centred, up to two lines. A title too long for one line breaks
 * straight after its colon ("Legally Blonde 2:" / "Red, White & Blonde") when it has one.
 */
@Composable
private fun BarTitle(title: String, ink: Color, size: Int = 15, maxWidth: androidx.compose.ui.unit.Dp = 230.dp) {
    val style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size * 1.2f).sp, textAlign = TextAlign.Center)
    val measurer = rememberTextMeasurer()
    val maxPx = with(LocalDensity.current) { maxWidth.roundToPx() }
    val text = remember(title) {
        val fits = !measurer.measure(title, style, maxLines = 1, constraints = Constraints(maxWidth = maxPx)).hasVisualOverflow
        if (!fits && ':' in title) title.replaceFirst(Regex(":\\s*"), ":\n") else title
    }
    Text(text, style = style.copy(shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 1f), 3f)), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = maxWidth))
}

/** The film's title in the same dark see-through pill as the hints, larger, up to two lines. */
@Composable
private fun TitlePill(title: String) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        colors = SurfaceDefaults.colors(containerColor = Color(0xFF14161C).copy(alpha = 0.45f), contentColor = Color.White),
    ) {
        Box(Modifier.padding(horizontal = 26.dp, vertical = 19.dp)) { BarTitle(title, Color.White, size = 25, maxWidth = 480.dp) }
    }
}

/** A single action in the bottom-right corner, in the title pill's style: what OK does right now. */
@Composable
private fun ActionPill(text: String) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        colors = SurfaceDefaults.colors(containerColor = Color(0xFF14161C).copy(alpha = 0.45f), contentColor = Color.White),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { BarTitle(text, Color.White, size = 15, maxWidth = 220.dp) }
    }
}

/** Milky translucent white bar, greyish-black type, translucent outlined key caps. */
@Composable
private fun KeyHints(state: OverlayState, modifier: Modifier) {
    val ink = Color.White
    val soft = Color.White.copy(alpha = 0.8f)
    val fill = Color(0xFF14161C).copy(alpha = 0.45f)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),  // a pill at one line, a soft rounded block at two
        colors = SurfaceDefaults.colors(containerColor = fill, contentColor = ink),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = if (state.dpad != null) 7.dp else 9.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            state.dpad?.let { DpadHints(it, soft) }
            for ((key, action) in state.keys) KeyHint(key, action, ink, soft, capFill = Color.Transparent, capBorder = Color.White.copy(alpha = 0.65f))
        }
    }
}

/** What pressing and holding OK do ("Hold (OK) to Shuffle"): a small pill of its own at the top of the screen. */
@Composable
private fun PressHint(d: Dpad, modifier: Modifier) {
    val ink = Color.White
    Surface(
        modifier = modifier, shape = RoundedCornerShape(50),
        colors = SurfaceDefaults.colors(containerColor = Color(0xFF14161C).copy(alpha = 0.45f), contentColor = ink),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((how, action) in listOfNotNull(d.ok?.let { "" to it }, d.hold?.let { "Hold " to it })) Row(verticalAlignment = Alignment.CenterVertically) {
                val words = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = ink)
                if (how.isNotEmpty()) Text(how, style = words)
                // OK drawn as the D-pad ring's centre button.
                Box(Modifier.size(18.dp).background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Text("OK", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 7.sp, color = Color(0xFF2C2C30))
                }
                Text(" to $action", style = words)
            }
        }
    }
}

/**
 * The remote's D-pad as arrows round a plain centre button, each action written on the side of the
 * arrow that does it: left on the left, right on the right, up above. No ▼ arrow (nothing in the room
 * uses it); what OK and holding it do go in their own pill (see [KeyHints]). Unused directions are dimmed.
 */
@Composable
private fun DpadHints(d: Dpad, soft: Color) {
    val label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = soft)
    // Compact: left and right labels level with the arrows, the up label centred above them; no row for a
    // direction that does nothing, so the pill has no empty line.
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.height(30.dp), contentAlignment = Alignment.CenterEnd) {
            Text(d.left ?: "", style = label.copy(textAlign = TextAlign.End), modifier = Modifier.widthIn(min = 64.dp).padding(end = 8.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            d.up?.let { Text(it, style = label, modifier = Modifier.height(15.dp)) }
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.size(30.dp)) {
                    val r = size.minDimension / 2; val c = center
                    fun arrow(dx: Float, dy: Float, on: Boolean) {
                        val tip = androidx.compose.ui.geometry.Offset(c.x + dx * r * 0.86f, c.y + dy * r * 0.86f)
                        val base = androidx.compose.ui.geometry.Offset(c.x + dx * r * 0.62f, c.y + dy * r * 0.62f)
                        val w = r * 0.17f
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(tip.x, tip.y); lineTo(base.x - dy * w, base.y + dx * w); lineTo(base.x + dy * w, base.y - dx * w); close()
                        }
                        drawPath(p, Color.White.copy(alpha = if (on) 1f else 0.22f))
                    }
                    arrow(0f, -1f, d.up != null); arrow(-1f, 0f, d.left != null); arrow(1f, 0f, d.right != null)
                    drawCircle(Color.White.copy(alpha = if (d.ok != null || d.hold != null) 0.92f else 0.3f), r * 0.4f)
                }
            }
        }
        Box(Modifier.height(30.dp), contentAlignment = Alignment.CenterStart) {
            Text(d.right ?: "", style = label, modifier = Modifier.widthIn(min = 64.dp).padding(start = 8.dp))
        }
    }
}

/** A remote button drawn as a see-through key cap, and what it does. */
@Composable
private fun KeyHint(key: String, action: String, ink: Color, soft: Color, capFill: Color = Color.White.copy(alpha = 0.35f), capBorder: Color = Color(0xFF8E8E94)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.background(capFill, RoundedCornerShape(7.dp)).border(1.dp, capBorder, RoundedCornerShape(7.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Text(key, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = ink) }
        Text(action, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = soft)
    }
}

/**
 * A circled gear: dim at rest, white with a dark gear when ◀ highlights it (a little larger, so
 * the highlight reads from the couch). OK then opens Settings.
 */
@Composable
private fun GearButton(focused: Boolean) {
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.12f else 1f, spring(dampingRatio = 0.6f), label = "gear")
    androidx.compose.foundation.Canvas(Modifier.size(52.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (focused) 1f else 0.7f }) {
        val c = center; val r = size.minDimension / 2
        drawCircle(if (focused) Color.White else Color(0xFF14161C).copy(alpha = 0.45f), r)
        drawCircle(Color.White.copy(alpha = if (focused) 1f else 0.65f), r - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        val ink = if (focused) Color(0xFF2C2C30) else Color.White
        val teeth = 8; val outer = r * 0.56f; val inner = r * 0.42f
        val path = androidx.compose.ui.graphics.Path()
        for (i in 0 until teeth * 4) {
            val a = (i / (teeth * 4f)) * 2 * Math.PI - Math.PI / (teeth * 2)
            val rad = if ((i % 4) == 1 || (i % 4) == 2) outer else inner
            val x = c.x + rad * kotlin.math.cos(a).toFloat(); val y = c.y + rad * kotlin.math.sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, ink)
        drawCircle(if (focused) Color.White else Color(0xFF3A3C44), r * 0.17f)
    }
}

/** The settings' frame-rate readout, top-left inside the TV-safe area. */
@Composable
fun FrameReadout(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Text(text, Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 14.sp)
    }
}

/** What the streaming-style controls show; [position] is read live while they're up. */
class StreamingInfo(val title: String, val details: String, val position: () -> Long, val duration: () -> Long, val playing: () -> Boolean, val target: () -> Long?)

/**
 * Streaming-app playback controls, over the film in the full-screen TV view only: a dark fade along the
 * bottom with the title, a progress bar whose knob carries the time, and the time left at its end. While
 * ◀ ▶ seek, the knob shows where the film will pick up.
 */
@Composable
fun StreamingControls(info: StreamingInfo) {
    var now by androidx.compose.runtime.remember { mutableStateOf(info.position()) }
    var length by androidx.compose.runtime.remember { mutableStateOf(info.duration()) }
    var playing by androidx.compose.runtime.remember { mutableStateOf(info.playing()) }
    var target by androidx.compose.runtime.remember { mutableStateOf(info.target()) }
    LaunchedEffect(info) {
        while (true) { now = info.position(); length = info.duration(); playing = info.playing(); target = info.target(); kotlinx.coroutines.delay(200) }
    }
    val at = (target ?: now).coerceIn(0, maxOf(length, 1))
    val share = if (length > 0) at.toFloat() / length else 0f
    fun clock(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = s / 60 % 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.42f).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f)))))
        Column(Modifier.fillMaxWidth().padding(start = 64.dp, end = 64.dp, bottom = 44.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayPauseMark(playing, Modifier.size(30.dp))
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(info.title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (info.details.isNotEmpty()) Text(info.details, fontFamily = Inter, fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(22.dp))
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
                val knobX = maxWidth * share
                // The track, what's been watched, and the knob.
                Box(Modifier.align(Alignment.TopStart).padding(top = 7.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.28f)))
                Box(Modifier.align(Alignment.TopStart).padding(top = 7.dp).width(knobX).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                Box(Modifier.align(Alignment.TopStart).padding(start = (knobX - 10.dp).coerceAtLeast(0.dp)).size(20.dp).clip(RoundedCornerShape(10.dp)).background(Color.White))
                // The time under the knob, kept inside the bar's ends; the time left at the right.
                val label = clock(at)
                Text(label, Modifier.align(Alignment.TopStart).padding(top = 22.dp, start = (knobX - 30.dp).coerceIn(0.dp, maxWidth - 150.dp)),
                    fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Color.White)
                if (length > 0) Text("−" + clock(length - at), Modifier.align(Alignment.TopEnd).padding(top = 22.dp),
                    fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/** What OK will do, as streaming apps draw it: ❚❚ while playing, ▶ while paused. */
@Composable
private fun PlayPauseMark(playing: Boolean, modifier: Modifier) = androidx.compose.foundation.Canvas(modifier) {
    val w = size.width; val h = size.height
    if (!playing) drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.18f, 0f); lineTo(w * 0.92f, h / 2); lineTo(w * 0.18f, h); close() }, Color.White)
    else { drawRect(Color.White, Offset(w * 0.16f, 0f), androidx.compose.ui.geometry.Size(w * 0.24f, h)); drawRect(Color.White, Offset(w * 0.6f, 0f), androidx.compose.ui.geometry.Size(w * 0.24f, h)) }
}
