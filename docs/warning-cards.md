# Warning cards

Before a film plays from the start, the app shows a warning card for 6.5 s. The first 3 s can't be skipped: pressing a key shows the player's circle-slash "prohibited" mark, as DVD players did. After that, OK skips it. The **Warning card** setting turns it off. It's stored as `fbiWarning`, which follows the old `warnings` switch until changed.

## Now: the ShelfLife anti-spoiler card (from 2 November 2026)

One full-screen 16:9 card, baked into the app as `android/app/src/main/res/drawable-nodpi/warning_card.png` and drawn by `AntiPiracyScreen` (card 0) in `AntiPiracy.kt`. It parodies the modern (Blu-ray era) FBI anti-piracy card: black, the seal on the left, white text on the right.

It's made by two scripts, procedurally, with no copied pixels:

1. `python3 work/make_fbi_seal.py` draws the seal as layers in `work/fbi-seal/`:
   - the gold spike ring, measured from the real seal;
   - the motto ribbon, reading FRESHLY · BUTTERED · INVESTIGATORS;
   - the disc, rings, stars, the striped emblem with its gold cap and scales;
   - the lettering: DEPARTMENT OF MOVIE NIGHT, SHELFLIFE ANTI-SPOILER WARNING, BUREAU OF BE KIND, PLEASE REWIND.
2. `python3 work/make_fbi_card.py` puts the seal on the 1920×1080 card with the warning text and writes `work/fbi-seal/fbi_card.png`.
3. Copy that file to `android/app/src/main/res/drawable-nodpi/warning_card.png` and rebuild.

Run both with a `python3` that has Pillow, numpy and OpenCV. Every shape, colour and word is a named constant at the top of each script.

The real seal it was measured from is `work/warning-sources/fbi_apw.png` (FBI, US government work, public domain). It's used for measurements and side-by-side checks (`work/fbi-seal/fbi_check.png`) only.

## Before: the FBI and Interpol cards (until 2 November 2026)

Two cards in the style of a 2000s DVD, pillarboxed in 4:3 on the widescreen TV, cut in hard one after the other:

| Card | Look | Time |
|---|---|---|
| FBI anti-piracy warning | Royal-blue radial gradient, the official FBI anti-piracy seal on the left, white bold text | 6.5 s |
| Interpol warning | Black, the Interpol emblem in white on the left, white bold text | 4.5 s (11 s for both) |

- **Settings:** each card had its own switch, "FBI warning" (`fbiWarning`) and "Interpol warning" (`interpolWarning`). Both followed the old `warnings` switch.
- **Skipping:** after the 3 s lock, OK on the FBI card cut straight to the Interpol card, which then ran its own 4.5 s.
- **Images:** neither was in the APK. `Extras.kt` downloaded both from Wikimedia Commons on first launch:
  - `Fbias-anti-piracy-warning-seal.png`, rendered 800 px wide, shown as-is;
  - `Emblema_Interpol_azul.png`, rendered 842 px wide, cut out of its white page and turned white.

  Without them, the cards showed text only. App copies are in `work/warning-sources/app-copies/` (`warning_fbi_seal.png`, `warning_interpol_emblem.png`).
- **DVD mastering:** each card was laid out at 576 × 432 dp, drawn once offscreen, then "mastered" like a DVD-era disc:
  1. scaled to SD (720 × 540);
  2. given edge enhancement, an unsharp mask that leaves faint halos round the lettering;
  3. compressed as JPEG at quality 30, so the flat blue bands into 8×8 blocks as MPEG-2 does;
  4. shown upscaled, as a player would.

### The old code

From `AntiPiracy.kt`. Inside `AntiPiracyScreen`, cards 0 (FBI) and 1 (Interpol) shared the pillarboxed frame:

```kotlin
// A 4:3 picture pillarboxed on the widescreen TV, full height. The cards are laid out at their
// original 576 x 432 dp design size and scaled up to fill it.
BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(4f / 3f), contentAlignment = Alignment.Center) {
    val fit = maxHeight / 432.dp
    Box(Modifier.requiredSize(576.dp, 432.dp).graphicsLayer { scaleX = fit; scaleY = fit }) {
        AnimatedContent(card, transitionSpec = { EnterTransition.None togetherWith ExitTransition.None }, label = "card") { c ->
            if (c == 0) Mastered { FbiCard() } else Mastered { InterpolCard() }
        }
    }
    AnimatedVisibility(prohibited, Modifier.align(Alignment.TopEnd).padding(28.dp), enter = fadeIn(tween(80)), exit = fadeOut(tween(250))) {
        ProhibitedIcon()
    }
}
```

The cards and the mastering:

```kotlin
private val royal = Brush.radialGradient(listOf(Color(0xFF2346C9), Color(0xFF12309E), Color(0xFF071663)), radius = 900f)

/** An image from [Extras]; the space stays empty while it isn't downloaded. */
@Composable
private fun ExtraImage(name: String, description: String, modifier: Modifier) {
    val bitmap = remember(name) { Extras.bitmap(name)?.asImageBitmap() }
    if (bitmap != null) Image(bitmap, description, modifier) else Spacer(modifier)
}

@Composable
private fun FbiCard() {
    Row(
        Modifier.fillMaxSize().background(royal).padding(horizontal = 34.dp, vertical = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtraImage(Extras.FBI_SEAL, "FBI Anti-Piracy Warning seal", Modifier.size(180.dp))
        Spacer(Modifier.width(26.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text("FBI ANTI-PIRACY WARNING", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(16.dp))
            Body("Unauthorized reproduction or distribution of this copyrighted work is illegal. Criminal copyright infringement, including infringement without monetary gain, is investigated by the FBI and is punishable by up to 5 years in federal prison and a fine of \$250,000.", size = 16, align = TextAlign.Start)
        }
    }
}

@Composable
private fun InterpolCard() {
    Row(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtraImage(Extras.INTERPOL_EMBLEM, "Interpol emblem", Modifier.size(width = 150.dp, height = 192.dp))
        Spacer(Modifier.width(30.dp))
        Column {
            Text("INTERPOL WARNING", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(16.dp))
            Body("The unauthorised reproduction, distribution or exhibition of this copyrighted film is a criminal offence. Intellectual property crime is investigated by law enforcement agencies worldwide and may result in criminal prosecution and civil penalties.", size = 16, align = TextAlign.Start)
        }
    }
}

@Composable
private fun Body(text: String, color: Color = Color.White, size: Int = 18, align: TextAlign = TextAlign.Center) =
    Text(text, color = color, fontSize = size.sp, lineHeight = (size * 1.42f).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, textAlign = align)

/**
 * The card as it came off a mass-produced disc: drawn once, scaled to DVD resolution (720 x 540),
 * edge-enhanced like a DVD-era master, then compressed with JPEG (the same 8x8-block DCT and 4:2:0
 * colour as an MPEG-2 frame) and shown upscaled, as a player would. Digital, not worn: no noise, no wobble.
 */
@Composable
private fun Mastered(content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    Box(Modifier.fillMaxSize()) {
        // Laid out and recorded, never drawn: only the mastered copy reaches the screen.
        Box(Modifier.fillMaxSize().drawWithContent { layer.record { this@drawWithContent.drawContent() } }) { content() }
        frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low) }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }; withFrameNanos { }  // laid out and drawn once
        val drawn = layer.toImageBitmap().asAndroidBitmap()
        frame = withContext(Dispatchers.Default) { master(drawn) }.asImageBitmap()
    }
}

/** DVD-style mastering of one still: SD size, edge enhancement, MPEG-like 8x8 compression. */
private fun master(drawn: android.graphics.Bitmap): android.graphics.Bitmap {
    val sd = android.graphics.Bitmap.createScaledBitmap(drawn.copy(android.graphics.Bitmap.Config.ARGB_8888, false), 720, 540, true)
    // Edge enhancement (unsharp mask): the faint halos round lettering DVD-era masters had.
    val soft = Printing.blurred(sd, radius = 1, flip = false, passes = 1)
    val a = IntArray(720 * 540).also { sd.getPixels(it, 0, 720, 0, 0, 720, 540) }
    val b = IntArray(720 * 540).also { soft.getPixels(it, 0, 720, 0, 0, 720, 540) }
    for (i in a.indices) {
        fun ch(s: Int) = (((a[i] shr s) and 255) * 1.7f - ((b[i] shr s) and 255) * 0.7f).toInt().coerceIn(0, 255)
        a[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    val sharp = android.graphics.Bitmap.createBitmap(a, 720, 540, android.graphics.Bitmap.Config.ARGB_8888)
    val bytes = java.io.ByteArrayOutputStream()
    sharp.compress(android.graphics.Bitmap.CompressFormat.JPEG, WARNING_QUALITY, bytes)
    return android.graphics.BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size())
}

/** JPEG quality of the mastered warning cards: lower = chunkier 8x8 blocks in the flat blue. */
private const val WARNING_QUALITY = 30
```

From `MainActivity.kt`, the timing for both cards:

```kotlin
private var interpolNext = false   // the FBI card is followed by the Interpol one
private val fbiOn get() = prefs.getBoolean("fbiWarning", prefs.getBoolean("warnings", true))
private val interpolOn get() = prefs.getBoolean("interpolWarning", prefs.getBoolean("warnings", true))

/** The warning cards that are on: FBI for 6.5 s, Interpol for 4.5 s, then [then]. */
private fun showWarning(then: () -> Unit) {
    // …
    interpolNext = fbiOn && interpolOn
    warningCard.value = if (fbiOn) 0 else 1
    refresh()
    if (warningCard.value == 0) main.postDelayed({ if (run == warningRun && warningCard.value == 0) { if (interpolNext) warningCard.value = 1 else endWarning() } }, 6_500)
    main.postDelayed({ if (run == warningRun) endWarning() }, if (interpolNext) 11_000 else if (fbiOn) 6_500 else 4_500)
}

// In warningKey, after the 3 s lock:
} else if (warningCard.value == 0 && interpolNext) {
    // Skip to the Interpol card, which then runs its own time.
    warningRun++
    val run = warningRun
    warningCard.value = 1
    main.postDelayed({ if (run == warningRun) endWarning() }, 4_500)
} else endWarning()

// Settings rows:
SettingRow("FBI warning", "The FBI anti-piracy card before a film plays from the start.", onOff(fbiOn)) to { flip("fbiWarning", fbiOn) },
SettingRow("Interpol warning", "The Interpol card before a film plays from the start, after the FBI one.", onOff(interpolOn)) to { flip("interpolWarning", interpolOn) },
```

From `Extras.kt`, the downloads:

```kotlin
const val FBI_SEAL = "fbi_seal.commons.png"
const val INTERPOL_EMBLEM = "interpol_emblem.commons2.png"
// in `commons`:
FBI_SEAL to Triple("Fbias-anti-piracy-warning-seal.png", 800, Look.AS_IS),
INTERPOL_EMBLEM to Triple("Emblema_Interpol_azul.png", 842, Look.INK_WHITE),  // blue on an opaque white page
```

The `master()` function was recovered from an earlier version after the code was removed, so check it against a build before reusing it.
