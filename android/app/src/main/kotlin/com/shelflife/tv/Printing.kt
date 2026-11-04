package com.shelflife.tv

import androidx.core.graphics.ColorUtils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Draws DVD sleeve artwork with Android's text engine; uploaded to Filament as textures. */
object Printing {
    private fun darker(color: Int, amount: Float) = Color.rgb(
        (Color.red(color) * (1 - amount)).toInt(), (Color.green(color) * (1 - amount)).toInt(), (Color.blue(color) * (1 - amount)).toInt())

    /**
     * The edition band across the top, like real cases: black "4K UHD" for 4K, blue "BLU-RAY"
     * for HD (720/1080), plain "DVD" for standard definition or unknown.
     */
    /** Band colour (null = none) and label for a Plex resolution. */
    private fun edition(resolution: String?): Pair<Int?, String> {
        val res = resolution?.lowercase() ?: return null to "DVD"
        return when {
            res.startsWith("4k") || res.startsWith("2160") -> Color.rgb(12, 12, 12) to "4K UHD"
            res.startsWith("1080") || res.startsWith("720") -> Color.rgb(10, 86, 170) to "BLU-RAY"
            else -> null to "DVD"
        }
    }

    /** The small print on the back: what the disc actually holds. */
    private fun formatLine(resolution: String?): String {
        val res = resolution?.lowercase() ?: return "DVD Video"
        return when {
            res.startsWith("4k") || res.startsWith("2160") -> "4K Ultra HD · 2160p"
            res.startsWith("1080") -> "Blu-ray · 1080p High Definition"
            res.startsWith("720") -> "Blu-ray · 720p High Definition"
            else -> "DVD Video · Standard Definition"
        }
    }

    private fun shortRating(rating: String?): String? {
        val r = rating?.substringAfterLast('/')?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        return if (r == "NOT RATED" || r == "UNRATED") "NR" else r
    }

    private fun editionBand(canvas: Canvas, resolution: String?, paint: Paint) {
        val (band, label) = edition(resolution)
        if (band != null) {
            canvas.drawRect(0f, 0f, 64f, 58f, Paint().apply { color = band })
            canvas.drawRect(0f, 58f, 64f, 61f, Paint().apply { color = Color.argb(140, 255, 255, 255) })
        }
        val logo = bluRayLogo
        if (label == "BLU-RAY" && logo != null) {
            // The official Blu-ray Disc mark, white on the blue band, as on a real Blu-ray spine.
            val w = 54f; val h = w * logo.height / logo.width
            canvas.drawBitmap(logo, null, RectF(32f - w / 2, 29f - h / 2, 32f + w / 2, 29f + h / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            return
        }
        paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 17f
        while (paint.measureText(label) > 56 && paint.textSize > 9) paint.textSize -= 0.5f
        canvas.drawText(label, 32f, 36f, paint)
    }

    /** The Blu-ray Disc logo (white), fetched by Extras from Wikimedia Commons; null until then (the band says BLU-RAY instead). */
    @Volatile var bluRayLogo: Bitmap? = null

    /** One-ink logos for the back: the distributor (whose feature presentation plays), then the main
     *  production company when it's someone else. Set by MainActivity. */
    @Volatile var studioLogos: (Movie) -> List<Bitmap> = { emptyList() }

    /** The age rating in a small outlined box above the year (US "PG-13", UK "gb/15" → "15", "Not Rated" → "NR"). */
    private fun ratingBadge(canvas: Canvas, rating: String?, paint: Paint) {
        val r = shortRating(rating) ?: return
        paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 15f
        while (paint.measureText(r) > 46 && paint.textSize > 8) paint.textSize -= 0.5f
        val w = paint.measureText(r) + 10f
        val box = RectF(32f - w / 2, 812f, 32f + w / 2, 838f)
        canvas.drawRoundRect(box, 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) })
        canvas.drawRoundRect(box, 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.WHITE })
        canvas.drawText(r, 32f, 831f, paint)
    }

    /**
     * The room's soft reflection on the TV's glass: a broad warm sheen from the window side (right)
     * and a faint cooler glow from the room, on black so the screen still reads as off.
     */
    fun screenSheen(): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(640f, 0f, 120f, 360f,
            intArrayOf(Color.argb(125, 255, 236, 210), Color.argb(52, 255, 230, 200), Color.argb(0, 0, 0, 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 640f, 360f, p)
        p.shader = RadialGradient(520f, 70f, 260f, Color.argb(75, 255, 240, 220), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 640f, 360f, p)
        p.shader = RadialGradient(120f, 300f, 280f, Color.argb(22, 200, 215, 240), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 640f, 360f, p)
        return bitmap
    }

    /** Poster palettes by film id, filled as spines are printed; generated sleeves use them too. */
    val palettes = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

    /** The film's poster palette, or shades of its fallback colour when there is no poster. */
    fun paletteFor(movie: Movie): IntArray = palettes[movie.id] ?: IntArray(18) { i ->
        val hsv = FloatArray(3).also { Color.colorToHSV(movie.color, it) }
        hsv[0] = (hsv[0] + ((i * 37) % 50 - 25)).mod(360f); hsv[2] = (hsv[2] * (0.55f + (i % 5) * 0.12f)).coerceIn(0f, 1f)
        Color.HSVToColor(hsv)
    }

    /**
     * Soft overlapping colour splotches, each near the height its colour sits on the poster (top row
     * first). Shared by spines and the generated covers so a case reads as one design.
     */
    fun splotches(canvas: Canvas, w: Float, h: Float, palette: IntArray, seedKey: String, minR: Float, spreadR: Float) {
        canvas.drawColor(darker(average(palette), 0.3f))
        var seed = seedKey.fold(5381L) { acc, c -> acc * 33 + c.code }
        fun random(): Float { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 40) and 0xFFFFFF).toFloat() / 0xFFFFFF }
        val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
        val rows = (palette.size + 2) / 3
        for (pass in 0 until 2) palette.forEachIndexed { i, color ->
            val cx = ((i % 3) + random()) / 3f * w
            val cy = ((i / 3) + random()) / rows * h
            val r = minR + random() * spreadR
            bubble.shader = RadialGradient(cx, cy, r, Color.argb(230, Color.red(color), Color.green(color), Color.blue(color)), Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, bubble)
        }
    }

    /**
     * The sleeve of a case still on the shelf (seen on its side when it is picked out): the poster's
     * palette stretched into a soft, blurry colour field, matching the spine's colours.
     */
    fun shelfCover(movie: Movie, flip: Boolean = false): Bitmap {
        val pal = paletteFor(movie)
        val grid = Bitmap.createBitmap(3, 6, Bitmap.Config.ARGB_8888)
        for (i in 0 until 18) grid.setPixel(if (flip) 2 - i % 3 else i % 3, i / 3, pal[i % pal.size])
        val soft = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(grid, 9, 16, true), 128, 181, true)
        val out = soft.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        splotchesOver(canvas, 128f, 181f, pal, movie.id + if (flip) "-b" else "-f", 30f, 34f)
        canvas.drawColor(Color.argb(35, 0, 0, 0))
        // The back's side shows the end of a sticker wrapped round from the spine.
        if (flip) stickerPlan(movie)?.takeIf { it.wrap }?.let { drawSticker(canvas, it, BACK, out.width, out.height) }
        return out
    }

    /** [splotches] without the flat base colour, for layering over a soft field. */
    private fun splotchesOver(canvas: Canvas, w: Float, h: Float, palette: IntArray, seedKey: String, minR: Float, spreadR: Float) {
        var seed = seedKey.fold(5381L) { acc, c -> acc * 33 + c.code }
        fun random(): Float { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 40) and 0xFFFFFF).toFloat() / 0xFFFFFF }
        val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
        val rows = (palette.size + 2) / 3
        palette.forEachIndexed { i, color ->
            val cx = ((i % 3) + random()) / 3f * w; val cy = ((i / 3) + random()) / rows * h; val r = minR + random() * spreadR
            bubble.shader = RadialGradient(cx, cy, r, Color.argb(170, Color.red(color), Color.green(color), Color.blue(color)), Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, bubble)
        }
    }

    /**
     * Keeps a spine title readable without losing the cover's colours. What's behind the title is
     * measured (WCAG contrast): 4:1 normally; 5.5:1 for pairings that blur or shimmer (red/blue,
     * green/orange, purple/blue, teal/blue); only 3:1 for the classic designer pairings (red/green,
     * orange/blue, magenta/green), which are left as the cover has them wherever possible. Either the spine goes
     * darker under a light title or lighter under a dark one, whichever needs the smaller change: the
     * spine's colour is the cover too, so washing it counts as much as shifting the title (except for
     * red titles, which must not drift to pink — there the spine gives way). The title only moves in
     * lightness, keeping its hue. Paints the wash; returns the colour.
     */
    private fun legible(title: Int, bitmap: Bitmap, canvas: Canvas): Int {
        val region = IntArray(32 * 700).also { bitmap.getPixels(it, 0, 32, 16, 100, 32, 700) }
        val behind = average(region)
        val pair = setOf(hueFamily(title), hueFamily(behind))
        val target = when (pair) { in clashing -> 5.5; in designPairs -> 3.0; else -> 4.0 }
        if (ColorUtils.calculateContrast(title, behind) >= target) return title
        val titleWeight = if (hueFamily(title) == Hue.RED) 3.0 else 1.0
        val behindLum = ColorUtils.calculateLuminance(behind)
        var best: Triple<Double, Int, Int>? = null  // score, wash colour with alpha, title
        for (towards in intArrayOf(Color.BLACK, Color.WHITE)) for (alpha in 0..150 step 10) {
            val bg = ColorUtils.blendARGB(behind, towards, alpha / 255f)
            val (shift, fixed) = shiftLightness(title, bg, target, lighter = towards == Color.BLACK) ?: continue
            // Greying a white or near-black cover's spine loses what it looks like: that counts double.
            val washWeight = if ((towards == Color.BLACK && behindLum > 0.4) || (towards == Color.WHITE && behindLum < 0.1)) 2.0 else 1.0
            val score = shift * titleWeight + alpha / 255.0 * washWeight
            if (best == null || score < best.first) best = Triple(score, ColorUtils.setAlphaComponent(towards, alpha), fixed)
        }
        val (_, wash, fixed) = best ?: return if (ColorUtils.calculateLuminance(behind) < 0.4) Color.WHITE else Color.rgb(20, 20, 24)
        if (Color.alpha(wash) > 0) canvas.drawColor(wash)
        return fixed
    }

    /** White for small print on a dark part of the spine, dark ink on a light one (white vanished on white covers). */
    private fun smallPrint(bitmap: Bitmap, y: Int): Int {
        val px = IntArray(32 * 16).also { bitmap.getPixels(it, 0, 32, 16, y, 32, 16) }
        return if (ColorUtils.calculateLuminance(average(px)) > 0.4) Color.rgb(28, 28, 32) else Color.WHITE
    }

    /**
     * A shadow only where it helps, judged from what's really behind the title (after [legible]):
     * a lighter title gets a dark shadow to lift it off a mid-tone or busy spine, but none on a
     * calm dark one, where it would only smudge the edges. A darker title never gets a dark shadow
     * (it muddies the colour — red on a white cover pops more without); over a busy spine it gets a
     * faint light halo instead. Returns radius, vertical offset and colour, or null for none.
     */
    private fun titleShadow(title: Int, bitmap: Bitmap): Triple<Float, Float, Int>? {
        val px = IntArray(32 * 700).also { bitmap.getPixels(it, 0, 32, 16, 100, 32, 700) }
        var sum = 0.0; var sq = 0.0; var n = 0
        for (i in px.indices step 7) { val l = ColorUtils.calculateLuminance(px[i]); sum += l; sq += l * l; n++ }
        val mean = sum / n
        val spread = kotlin.math.sqrt(maxOf(0.0, sq / n - mean * mean))  // how busy the spine is under the title
        val busy = spread > 0.05
        return if (ColorUtils.calculateLuminance(title) > mean) {
            if (mean < 0.05 && !busy) null else Triple(3.5f, 1f, Color.argb(if (busy) 200 else 140, 0, 0, 0))
        } else {
            if (busy) Triple(3.5f, 0f, Color.argb(140, 255, 255, 255)) else null
        }
    }

    /** The smallest step in lightness (HSL) that gets [title] to [target] contrast on [bg]; null if none does. */
    private fun shiftLightness(title: Int, bg: Int, target: Double, lighter: Boolean): Pair<Double, Int>? {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(title, it) }
        for (step in 0..20) {
            val s = step * 0.05f
            val c = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], (hsl[2] + if (lighter) s else -s).coerceIn(0f, 1f)))
            if (ColorUtils.calculateContrast(c, bg) >= target) return s.toDouble() to c
        }
        return null
    }

    private enum class Hue { RED, ORANGE, YELLOW, GREEN, TEAL, BLUE, PURPLE, MAGENTA }
    /** A colour's family on the wheel; null for whites, greys and near-blacks. */
    private fun hueFamily(c: Int): Hue? {
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        if (hsv[1] < 0.3f || hsv[2] < 0.25f) return null
        val h = hsv[0]
        return when {
            h < 15f || h >= 345f -> Hue.RED; h < 45f -> Hue.ORANGE; h < 70f -> Hue.YELLOW; h < 165f -> Hue.GREEN
            h < 200f -> Hue.TEAL; h < 255f -> Hue.BLUE; h < 290f -> Hue.PURPLE; else -> Hue.MAGENTA
        }
    }
    private val clashing = setOf(setOf(Hue.RED, Hue.BLUE), setOf(Hue.GREEN, Hue.ORANGE), setOf(Hue.PURPLE, Hue.BLUE), setOf(Hue.TEAL, Hue.BLUE))
    private val designPairs = setOf(setOf(Hue.RED, Hue.GREEN), setOf(Hue.ORANGE, Hue.BLUE), setOf(Hue.MAGENTA, Hue.GREEN))

    /** Each film's main real colours, most common first (see [accents]). */
    val accents = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

    /** Black ink, as a title colour taken from a cover's big black shapes (see [accents]). */
    val INK = Color.rgb(18, 18, 20)

    /**
     * The poster's main colours, most common first: up to three real colours (whites and greys set
     * aside), each covering at least 3% of it, plus [INK] when near-black covers at least 15% —
     * a big dark tree on a pale cover counts as one of its colours. Empty when there's neither.
     */
    fun accents(poster: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(poster, 24, 36, true)
        val hsv = FloatArray(3)
        val count = IntArray(12); val sums = Array(12) { IntArray(3) }  // 30° hue bins
        var black = 0
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val p = small.getPixel(x, y)
            Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.2f) { black++; continue }
            if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue
            val bin = (hsv[0] / 30f).toInt() % 12
            count[bin]++; sums[bin][0] += Color.red(p); sums[bin][1] += Color.green(p); sums[bin][2] += Color.blue(p)
        }
        val n = small.width * small.height
        val colours = count.indices.sortedByDescending { count[it] }.take(3).filter { count[it] >= n * 0.03f }
            .map { count[it] to (sums[it].let { s -> Color.rgb(s[0] / count[it], s[1] / count[it], s[2] / count[it]) }) }
        return (colours + listOfNotNull(if (black >= n * 0.15f) black to INK else null)).sortedByDescending { it.first }.map { it.second }.toIntArray()
    }

    /** At least half the cover is white or grey (little colour, not dark). */
    private fun mostlyWhiteOrGrey(palette: IntArray): Boolean {
        val hsv = FloatArray(3)
        return palette.count { Color.colorToHSV(it, hsv); hsv[1] < 0.18f && hsv[2] > 0.35f } * 2 >= palette.size
    }
    private fun isAchromatic(c: Int) = FloatArray(3).also { Color.colorToHSV(c, it) }[1] < 0.18f

    /**
     * The colour a film's title starts from. A coloured (or black) logo is used exactly. A white or
     * light grey logo, or none, would print a white title — so instead the title takes the cover's
     * most common vivid colour that isn't the spine's own hue (a red title on a blue spine, not blue
     * on blue). Black counts as a colour: when big black shapes outweigh every hue on the cover
     * and the spine is light enough for black to read, the title is printed black. Mostly white or
     * grey covers take their biggest colour (black included), else dark ink. White stays only when
     * the cover has nothing to give.
     */
    private fun titleSource(movie: Movie, palette: IntArray?, logoColor: Int?, behind: Int? = null): Int? {
        val lightNeutral = logoColor == null || FloatArray(3).also { Color.colorToHSV(logoColor, it) }.let { it[1] < 0.18f && it[2] > 0.6f }
        if (palette == null || !lightNeutral) return logoColor
        val accents = accents[movie.id] ?: IntArray(0)
        if (mostlyWhiteOrGrey(palette)) return accents.firstOrNull() ?: INK
        // What's really under the title on the printed spine (estimated from the palette when not printing).
        val under = behind ?: darker(average(palette), 0.2f)
        val spineHue = hueFamily(under)
        // A light cover (judged without its black shapes): black text suits it, and [legible] lightens
        // the spine under it toward the cover's own pale colour where it needs more contrast.
        val light = palette.filter { FloatArray(3).also { h -> Color.colorToHSV(it, h) }[2] >= 0.2f }
        val lightCover = light.isNotEmpty() && ColorUtils.calculateLuminance(average(light.toIntArray())) >= 0.35
        val hsv = FloatArray(3)
        return accents.firstOrNull {
            if (it == INK) lightCover else { Color.colorToHSV(it, hsv); hsv[1] >= 0.45f && hsv[2] >= 0.45f && hueFamily(it) != spineHue }
        } ?: logoColor
    }

    /** The poster squeezed to 3 x 6 pixels: its colours, top row first. */
    fun palette(poster: Bitmap): IntArray {
        val tiny = Bitmap.createScaledBitmap(poster, 3, 6, true)
        return IntArray(18) { tiny.getPixel(it % 3, it / 3) }
    }

    private fun average(colors: IntArray) = Color.rgb(colors.sumOf { Color.red(it) } / colors.size, colors.sumOf { Color.green(it) } / colors.size, colors.sumOf { Color.blue(it) } / colors.size)

    /**
     * 64 x 900: small enough for 144 on a TV box, sharp enough at shelf distance.
     * With a poster palette the spine is soft overlapping colour bubbles, each near the height
     * its colour sits on the poster (same look as the Mac app).
     */
    /** AUTO: [titleShadow]'s rule; NONE: no shadow (for the spine sheet's comparison). */
    enum class TitleShadow { NONE, AUTO }

    fun spine(movie: Movie, palette: IntArray? = null, logoColor: Int? = null, shadow: TitleShadow = TitleShadow.AUTO): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (palette == null) canvas.drawColor(darker(movie.color, 0.45f)) else {
            splotches(canvas, 64f, 900f, palette, movie.id, 45f, 60f)
            canvas.drawColor(Color.argb(50, 0, 0, 0))  // a light wash; the title's shadow does the rest
        }
        // Red or pink lettering on a red spine disappears: those titles go white.
        val spineBase = palette?.let { average(it) } ?: movie.color
        val under = average(IntArray(32 * 700).also { bitmap.getPixels(it, 0, 32, 16, 100, 32, 700) })
        val logoColor = titleSource(movie, palette, logoColor, under)?.takeUnless { isRed(spineBase) && (isRed(it) || isPink(it)) }
        // How far the spine darkens or lightens under the title is decided by [legible] from the
        // measured contrast (it replaced fixed washes for bright, grey and dark logos).
        val titleColor = legible(logoColor ?: Color.WHITE, bitmap, canvas)
        titleColors[movie.id] = titleColor
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        editionBand(canvas, movie.resolution, paint)
        movie.versions.firstOrNull()?.video?.let { v ->
            paint.color = smallPrint(bitmap, 66); paint.textSize = 11f; paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(if ("Dolby Vision" in v) "DV" else "HDR", 32f, 78f, paint)
        }
        ratingBadge(canvas, movie.rating, paint)
        paint.color = smallPrint(bitmap, 872); paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 14f; canvas.drawText(movie.year?.toString() ?: "", 32f, 885f, paint)
        // Title runs top-to-bottom along the spine, shrunk to fit; past the smallest readable size it is cut with "…".
        // Room between the edition band (top) and the rating badge (bottom).
        val (title, titleSize) = spineTitle(movie)
        paint.textSize = titleSize
        paint.color = titleColor
        when (shadow) {
            TitleShadow.NONE -> {}
            TitleShadow.AUTO -> titleShadow(titleColor, bitmap)?.let { (radius, dy, color) -> paint.setShadowLayer(radius, 0f, dy, color) }
        }
        canvas.save(); canvas.rotate(90f, 32f, 435f)
        canvas.drawText(title, 32f, 435f + paint.textSize * 0.35f, paint)
        canvas.restore()
        stickerPlan(movie)?.takeIf { it.wrap }?.let { drawSticker(canvas, it, SPINE, bitmap.width, bitmap.height) }
        return bitmap
    }

    // --- "Previously Viewed": the rental-store sticker on films Plex has as watched ---

    /**
     * The sticker, centred at ([cx], [cy]), [w] x [h] (any units: px, or mm under a scaled canvas),
     * turned [angle] degrees: super pale yellow label stock (a touch lighter at the top), a die-cut edge,
     * a soft shadow under it, black condensed type on one line. Drawn from scratch; nothing is an image.
     */
    private fun sticker(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, angle: Float) {
        canvas.save(); canvas.rotate(angle, cx, cy)
        val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2); val corner = h * 0.12f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0); maskFilter = android.graphics.BlurMaskFilter(h * 0.08f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        canvas.drawRoundRect(RectF(r.left + h * 0.03f, r.top + h * 0.06f, r.right + h * 0.03f, r.bottom + h * 0.06f), corner, corner, shadow)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(255, 252, 222), Color.rgb(250, 242, 196), Shader.TileMode.CLAMP) }
        canvas.drawRoundRect(r, corner, corner, fill)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = h * 0.03f; color = Color.rgb(222, 208, 150) }
        canvas.drawRoundRect(r, corner, corner, edge)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 18, 18); textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); letterSpacing = 0.04f; isSubpixelText = true }
        ink.textSize = h * 0.52f
        while (ink.measureText(STICKER_TEXT) > w * 0.9f && ink.textSize > h * 0.2f) ink.textSize -= h * 0.01f
        canvas.drawText(STICKER_TEXT, cx, cy + ink.textSize * 0.36f, ink)
        canvas.restore()
    }

    private fun seeded(key: String): java.util.Random = java.util.Random(key.fold(1125899906842597L) { h, c -> 31 * h + c.code })

    // Every sticker is the same real size. The insert textures map onto the case like this: the spine
    // bitmap (64 x 900) runs SPINE_ARC mm round the spine from its back edge to its front edge and
    // SPINE_LEN mm top to bottom; the front and back bitmaps cover FLAT_W x FLAT_H mm, the front's left
    // edge and the back's right edge meeting the spine.
    private const val STICKER_TEXT = "PREVIOUSLY VIEWED"
    private const val STICKER_W = 40f; private const val STICKER_H = 9f
    private const val SPINE_ARC = 16.8f; private const val SPINE_LEN = 184f
    private const val FLAT_W = 131.3f; private const val FLAT_H = 187f
    private const val BACK = 0; private const val SPINE = 1

    /**
     * Where a watched film's sticker sits, worked out from the film alone (seeded by its id) so the
     * spine and the back, printed at different times, agree. Stickers go on the back only. About half
     * sit at the back's spine edge and wrap ~3 mm round onto the spine (clear of the spine's title,
     * which starts ~4 mm in) — those show from the shelf; the rest go wherever the back is calmest.
     * [s] runs round the case from the spine's back edge (0, negative is on the back); [z] is mm above the middle.
     */
    private class StickerPlan(val wrap: Boolean, val s: Float, val z: Float, val angle: Float)

    private fun stickerPlan(movie: Movie): StickerPlan? {
        if (!movie.watched) return null
        val rnd = seeded(movie.id + "-sticker")
        if (rnd.nextFloat() >= 0.55f) return StickerPlan(false, 0f, 0f, 0f)  // placed by scanning the back (coverSticker)
        // Below the back's title and above its rating and format line: 32% to 86% of the way down.
        val f = 0.32f + rnd.nextFloat() * 0.54f
        return StickerPlan(true, 3f - STICKER_W / 2 - rnd.nextFloat() * 1f, FLAT_H / 2 - f * FLAT_H, (rnd.nextFloat() - 0.5f) * 5f)
    }

    /** Draws a plan's sticker on one insert bitmap ([face] BACK or SPINE, [w] x [h] px); what falls off the bitmap is clipped. */
    private fun drawSticker(canvas: Canvas, plan: StickerPlan, face: Int, w: Int, h: Int) {
        canvas.save()
        if (face == SPINE) { canvas.scale(w / SPINE_ARC, h / SPINE_LEN); canvas.translate(0f, SPINE_LEN / 2) }
        else { canvas.scale(w / FLAT_W, h / FLAT_H); canvas.translate(FLAT_W, FLAT_H / 2) }
        sticker(canvas, plan.s, -plan.z, STICKER_W, STICKER_H, plan.angle)
        canvas.restore()
    }

    /** A watched film's sticker on its back cover: at the spine edge (wrapping round) or on the calmest spot. */
    private fun backSticker(canvas: Canvas, bitmap: Bitmap, movie: Movie) {
        val plan = stickerPlan(movie) ?: return
        if (plan.wrap) drawSticker(canvas, plan, BACK, bitmap.width, bitmap.height) else coverSticker(canvas, bitmap, movie.id, bottom = false)
    }

    /** The spine's title as printed: upper case, shrunk to fit 680 px (not below 17 px), then cut with "…". */
    private fun spineTitle(movie: Movie): Pair<String, Float> {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 30f }
        while (p.measureText(movie.title.uppercase()) > 680 && p.textSize > 17) p.textSize -= 1
        return android.text.TextUtils.ellipsize(movie.title.uppercase(), TextPaint(p), 680f, android.text.TextUtils.TruncateAt.END).toString() to p.textSize
    }

    /**
     * On a back cover the text and pictures are wherever the layout put them, so it is scanned:
     * shrunk to 64 x 90, edge strength under each candidate spot (corners and side edges, inset a
     * little) scored as its average plus half its 90th percentile (a little lettering counts even in
     * calm surroundings), and the sticker goes on the calmest spot — or, when two are within 10%,
     * either of them. Text and faces are busy; sky and backgrounds are calm. Choice and tilt are fixed per film.
     */
    private fun coverSticker(canvas: Canvas, cover: Bitmap, seedKey: String, bottom: Boolean = true) {
        val gw = 64; val gh = 90
        val small = Bitmap.createScaledBitmap(cover, gw, gh, true)
        val lum = FloatArray(gw * gh) { i -> small.getPixel(i % gw, i / gw).let { 0.299f * Color.red(it) + 0.587f * Color.green(it) + 0.114f * Color.blue(it) } }
        val edge = FloatArray(gw * gh) { i ->
            val x = i % gw; val y = i / gw
            val dx = lum[y * gw + minOf(gw - 1, x + 1)] - lum[y * gw + maxOf(0, x - 1)]
            val dy = lum[minOf(gh - 1, y + 1) * gw + x] - lum[maxOf(0, y - 1) * gw + x]
            kotlin.math.abs(dx) + kotlin.math.abs(dy)
        }
        val W = cover.width.toFloat(); val H = cover.height.toFloat()
        val sw = STICKER_W * W / FLAT_W; val sh = STICKER_H * H / FLAT_H
        val inset = 0.07f
        val spots = listOf(inset to inset + 0.03f, 1 - inset to inset + 0.03f, inset to 1 - inset, 1 - inset to 1 - inset,
            inset to 0.5f, 1 - inset to 0.5f, 0.5f to 1 - inset, 0.5f to inset + 0.03f)
            .filter { bottom || it.second < 0.9f }  // a back's bottom row is the billing block: logos and rating
            .map { (fx, fy) -> (fx * W).coerceIn(sw / 2 + W * 0.02f, W - sw / 2 - W * 0.02f) to (fy * H).coerceIn(sh / 2 + H * 0.02f, H - sh / 2 - H * 0.02f) }
        fun score(cx: Float, cy: Float): Float {
            val x0 = ((cx - sw * 0.6f) / W * gw).toInt().coerceIn(0, gw - 1); val x1 = ((cx + sw * 0.6f) / W * gw).toInt().coerceIn(0, gw - 1)
            val y0 = ((cy - sh * 0.8f) / H * gh).toInt().coerceIn(0, gh - 1); val y1 = ((cy + sh * 0.8f) / H * gh).toInt().coerceIn(0, gh - 1)
            val v = ArrayList<Float>()
            for (y in y0..y1) for (x in x0..x1) v += edge[y * gw + x]
            v.sort()
            return v.average().toFloat() + 0.5f * v[(v.size * 0.9f).toInt().coerceAtMost(v.size - 1)]
        }
        val rnd = seeded(seedKey + "-cover-sticker")
        val ranked = spots.map { it to score(it.first, it.second) }.sortedBy { it.second }
        val best = ranked.filter { it.second <= ranked[0].second * 1.1f + 1f }.map { it.first }
        val (cx, cy) = best[rnd.nextInt(best.size)]
        sticker(canvas, cx, cy, sw, sh, (rnd.nextFloat() - 0.5f) * 16f)
    }

    /** The real poster, cropped to the sleeve's shape. */
    fun front(poster: Bitmap, width: Int = 1024): Bitmap {
        // Full detail for the one case in your hands (the shelf never loads these); [width] 512 for "Standard".
        val height = width * 1448 / 1024
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scale = maxOf(width.toFloat() / poster.width, height.toFloat() / poster.height)
        val w = width / scale; val h = height / scale
        val src = Rect(((poster.width - w) / 2).toInt(), ((poster.height - h) / 2).toInt(), ((poster.width + w) / 2).toInt(), ((poster.height + h) / 2).toInt())
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(poster, src, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        return bitmap
    }

    /** Stand-in cover until the real poster arrives: the poster's colours as splotches, like the spine. */
    fun front(movie: Movie): Bitmap {
        val bitmap = Bitmap.createBitmap(512, 724, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        splotches(canvas, 512f, 724f, paletteFor(movie), movie.id, 150f, 170f)
        canvas.drawColor(Color.argb(60, 0, 0, 0))
        // Shrinks to stay above the year, however long the title.
        fitted(canvas, movie.title, 40f, 400f, 432, 230, 54f, 32f, Typeface.DEFAULT_BOLD, Color.WHITE, 4)
        text(canvas, movie.year?.toString() ?: "", 40f, 650f, 432, 26f, Typeface.DEFAULT, Color.argb(200, 255, 255, 255))
        return bitmap
    }

    /**
     * The printed side of a pressed DVD: bare silver with the rainbow sheen of the data layer showing
     * through, fine grooves, a clear hub, and black printing — or the film's own logo when Plex has one.
     * 1024 x 1024; the label mesh maps the full 12 cm disc onto it.
     */
    fun disc(movie: Movie, logo: Bitmap? = null, logoFill: Int? = null): Bitmap {
        val s = 1024; val cx = s / 2f; val rr = s / 2f
        // Two layers: the bare disc (silver, faint rainbow, grooves, hub) is printed partly see-through so
        // the turning rainbow layer underneath shows through it; the ink goes on top fully opaque, the way
        // a printed disc's ink covers the rainbow rather than taking it on.
        val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        var canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Brushed silver base.
        p.shader = RadialGradient(cx, cx, rr, intArrayOf(Color.rgb(236, 238, 242), Color.rgb(196, 200, 207), Color.rgb(226, 229, 234)), floatArrayOf(0.2f, 0.72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cx, rr, p)
        // Diffraction rainbow in two opposite sectors, as light catches a real disc.
        val clear = Color.argb(0, 255, 255, 255)
        fun hue(h: Float, a: Int) = Color.HSVToColor(a, floatArrayOf(h, 0.85f, 1f))
        val bow = listOf(clear, hue(350f, 45), hue(40f, 55), hue(95f, 50), hue(180f, 50), hue(240f, 55), hue(290f, 45), clear)  // the moving sheen layer adds the rest
        val sweep = android.graphics.SweepGradient(cx, cx, (bow + bow).toIntArray(), null)
        sweep.setLocalMatrix(android.graphics.Matrix().apply { setRotate(-35f, cx, cx) })
        p.shader = sweep
        canvas.drawCircle(cx, cx, rr, p)
        // Fine concentric grooves.
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1f
        var r = rr * 0.38f
        while (r < rr) { p.color = Color.argb(if ((r / 5).toInt() % 2 == 0) 16 else 8, 0, 0, 0); canvas.drawCircle(cx, cx, r, p); r += 2.5f }
        // Glossy streak across the face.
        p.style = Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(0f, s.toFloat(), s.toFloat(), 0f,
            intArrayOf(clear, Color.argb(0, 255, 255, 255), Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255), clear), floatArrayOf(0f, 0.38f, 0.5f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cx, rr, p)
        // Clear plastic hub, stacking ring and the centre hole.
        p.shader = null
        p.color = Color.argb(150, 175, 182, 190); canvas.drawCircle(cx, cx, rr * 0.37f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.argb(70, 0, 0, 0); canvas.drawCircle(cx, cx, rr * 0.29f, p)
        p.style = Paint.Style.FILL; p.color = Color.rgb(20, 20, 22); canvas.drawCircle(cx, cx, rr * 0.127f, p)
        p.color = Color.argb(255, 120, 126, 134); p.style = Paint.Style.STROKE; p.strokeWidth = 3f; canvas.drawCircle(cx, cx, rr * 0.37f, p)
        p.style = Paint.Style.FILL

        val printed = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        canvas = Canvas(printed)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint().apply { alpha = 165 })
        p.shader = null; p.style = Paint.Style.FILL; p.alpha = 255
        p.color = Color.rgb(20, 20, 22); canvas.drawCircle(cx, cx, rr * 0.127f, p)  // the centre hole stays solid
        val ink = Color.argb(235, 12, 12, 14)
        // Top: the logo in its own colours, else the title in black.
        val box = RectF(cx - rr * 0.6f, cx - rr * 0.86f, cx + rr * 0.6f, cx - rr * 0.42f)
        if (logo != null) {
            val scale = minOf(box.width() / logo.width, box.height() / logo.height)
            val w = logo.width * scale; val h = logo.height * scale
            val dst = RectF(cx - w / 2, box.centerY() - h / 2, cx + w / 2, box.centerY() + h / 2)
            // Filled: the logo's shape in one flat colour (the spine title's), like a one-ink disc print,
            // with a faint dark edge so light colours still read on silver.
            val fill = logoFill ?: Color.rgb(12, 12, 14)
            val shadow = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = android.graphics.PorterDuffColorFilter(Color.argb(110, 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_IN) }
            // Only light inks need the edge; on dark or strong ones (a red logo) it just muddies the print.
            if (ColorUtils.calculateLuminance(fill) > 0.35) canvas.drawBitmap(logo, null, RectF(dst.left + 2f, dst.top + 3f, dst.right + 2f, dst.bottom + 3f), shadow)
            canvas.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = android.graphics.PorterDuffColorFilter(fill, android.graphics.PorterDuff.Mode.SRC_IN) })
        } else {
            fitted(canvas, movie.title.uppercase(), box.left, box.top + 20f, box.width().toInt(), box.height().toInt() - 20, 64f, 30f, Typeface.DEFAULT_BOLD, ink, 3)
        }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textAlign = Paint.Align.CENTER }
        // Left and right of the hub: year and running time; edition beneath.
        tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = 30f
        movie.year?.let { canvas.drawText(it.toString(), cx - rr * 0.57f, cx + 10f, tp) }
        movie.minutes?.let { canvas.drawText("$it MIN", cx + rr * 0.57f, cx + 10f, tp) }
        // Bottom: the DVD Video mark, rating and edition.
        tp.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC); tp.textSize = 66f
        canvas.drawText("DVD", cx, cx + rr * 0.58f, tp)
        tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = 20f; tp.letterSpacing = 0.5f
        canvas.drawText("VIDEO", cx, cx + rr * 0.58f + 26f, tp)
        tp.letterSpacing = 0.05f; tp.textSize = 22f
        val small = listOfNotNull(movie.rating?.substringAfterLast('/')?.uppercase(), movie.edition?.uppercase() ?: movie.versions.firstOrNull()?.label?.uppercase()?.takeIf { movie.versions.size > 1 }).joinToString("   ·   ")
        if (small.isNotEmpty()) canvas.drawText(small, cx, cx + rr * 0.80f, tp)
        tp.typeface = Typeface.DEFAULT; tp.textSize = 15f
        canvas.drawText("FOR PRIVATE HOME USE ONLY", cx, cx + rr * 0.88f, tp)
        return printed
    }

    /** A spine as the GPU takes it: RGBA bytes of every mip level, largest first, down to 1×1. */
    class Mips(val width: Int, val height: Int, val data: java.nio.ByteBuffer)

    /** Bytes in [width]×[height]'s whole mip chain (sizes halve with integer division, as Filament's do). */
    fun mipBytes(width: Int, height: Int): Int {
        var w = width; var h = height; var n = w * h * 4
        while (w > 1 || h > 1) { w = maxOf(1, w / 2); h = maxOf(1, h / 2); n += w * h * 4 }
        return n
    }

    /** [bitmap] and its mip levels, each the average of 2×2 pixels of the last. Slow on the TV box's CPU
     *  (as long as decoding the PNG), so it's for worker threads, once per film: [writeMips] keeps it. */
    fun mips(bitmap: Bitmap): Mips {
        var w = bitmap.width; var h = bitmap.height
        val px = ByteArray(mipBytes(w, h)).also { bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it)) }
        var from = 0; var to = w * h * 4
        while (w > 1 || h > 1) {
            val nw = maxOf(1, w / 2); val nh = maxOf(1, h / 2)
            for (y in 0 until nh) {
                val r0 = from + minOf(2 * y, h - 1) * w * 4; val r1 = from + minOf(2 * y + 1, h - 1) * w * 4
                for (x in 0 until nw) {
                    val c0 = minOf(2 * x, w - 1) * 4; val c1 = minOf(2 * x + 1, w - 1) * 4
                    for (k in 0 until 4) px[to + (y * nw + x) * 4 + k] = (((px[r0 + c0 + k].toInt() and 255) + (px[r0 + c1 + k].toInt() and 255) +
                        (px[r1 + c0 + k].toInt() and 255) + (px[r1 + c1 + k].toInt() and 255) + 2) / 4).toByte()
                }
            }
            from = to; to += nw * nh * 4; w = nw; h = nh
        }
        return Mips(bitmap.width, bitmap.height, java.nio.ByteBuffer.allocateDirect(px.size).put(px).flip() as java.nio.ByteBuffer)
    }

    /** Saves [mips] as width, height, then the bytes; written aside and renamed, so a half-written file is never read. */
    fun writeMips(mips: Mips, file: java.io.File) {
        val tmp = java.io.File(file.path + ".tmp" + Thread.currentThread().id)  // a shuffle may be making the same film
        java.io.FileOutputStream(tmp).channel.use { ch ->
            ch.write(java.nio.ByteBuffer.allocate(8).putInt(mips.width).putInt(mips.height).flip() as java.nio.ByteBuffer)
            val data = mips.data.duplicate(); while (data.hasRemaining()) ch.write(data)
        }
        if (!tmp.renameTo(file)) tmp.delete()
    }

    /** A spine saved by [writeMips], or null when there's none (or it isn't whole). */
    fun readMips(file: java.io.File): Mips? = runCatching {
        java.io.FileInputStream(file).channel.use { ch ->
            val head = java.nio.ByteBuffer.allocate(8); while (head.hasRemaining() && ch.read(head) >= 0) Unit
            head.flip(); val w = head.int; val h = head.int
            if (w !in 1..4096 || h !in 1..4096 || ch.size() != 8L + mipBytes(w, h)) return@use null
            val data = java.nio.ByteBuffer.allocateDirect(mipBytes(w, h)); while (data.hasRemaining() && ch.read(data) >= 0) Unit
            Mips(w, h, data.flip() as java.nio.ByteBuffer)
        }
    }.getOrNull()

    private const val GLOSS_BLUR = 0.11f  // how out of focus the sleeve's reflection is (texture widths)

    /**
     * The plastic sleeve's highlight, white with alpha: a soft, out-of-focus streak,
     * leaning like light from a window. Transparent at both ends, so sliding it (Shelf moves it with
     * the case's turn) takes it cleanly off the cover.
     */
    fun glossBand(): Bitmap {
        val w = 512; val h = 256
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = x / (w - 1f); val v = y / (h - 1f)
            val d = u - 0.5f - (v - 0.5f) * 0.28f                       // the streak leans across
            // The reflection out of focus: the sharp streak (width 0.03, strength 0.5) and its glow (0.12,
            // 0.25) each blurred by a wide lens blur — widths add in quadrature, strength spreads out. The
            // 1.9 brings the spread-out glow back up to 0.6 at its peak (the sharp one peaked at 0.75).
            fun blurred(width: Float, strength: Float): Float { val wb = kotlin.math.sqrt(width * width + GLOSS_BLUR * GLOSS_BLUR); return strength * width / wb * kotlin.math.exp(-(d / wb) * (d / wb)) }
            val a = 1.9f * (blurred(0.03f, 0.5f) + blurred(0.12f, 0.25f))
            val edge = (minOf(u, 1 - u) / 0.15f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }  // fades to nothing at both ends
            px[y * w + x] = Color.argb((a * edge * 255).toInt().coerceIn(0, 255), 255, 255, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** A film still without its letterbox: near-black bands at the top and bottom cut away. */
    fun trimBars(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        fun dark(y: Int): Boolean {
            var sum = 0
            for (x in 0 until w step 4) { val p = src.getPixel(x, y); sum += maxOf(Color.red(p), Color.green(p), Color.blue(p)) }
            return sum / ((w + 3) / 4) < 18
        }
        var top = 0; while (top < h / 3 && dark(top)) top++
        var bottom = h - 1; while (bottom > h * 2 / 3 && dark(bottom)) bottom--
        // Only real bars (both sides, a few rows each); a dark scene keeps its frame.
        return if (top >= 4 && h - 1 - bottom >= 4) Bitmap.createBitmap(src, 0, top, w, bottom - top + 1) else src
    }

    /** A soft copy of [src]: three box blurs (close to a gaussian of about [radius] px), turned upside down when [flip]. */
    fun blurred(src: Bitmap, radius: Int, flip: Boolean, passes: Int = 3): Bitmap {
        val w = src.width; val h = src.height
        val a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val b = IntArray(w * h)
        repeat(if (radius > 0) passes else 0) { boxBlur(a, b, w, h, radius, horizontal = true); boxBlur(b, a, w, h, radius, horizontal = false) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (flip) for (y in 0 until h) out.setPixels(a, (h - 1 - y) * w, w, 0, y, w, 1) else out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }
    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w; val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w; val lineStep = if (horizontal) w else 1
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = line * lineStep
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = src[base + i.coerceIn(0, len - 1) * step]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (i in 0 until len) {
                dst[base + i * step] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[base + minOf(len - 1, i + r + 1) * step]; val sub = src[base + maxOf(0, i - r) * step]
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255); sg += ((add shr 8) and 255) - ((sub shr 8) and 255); sb += (add and 255) - (sub and 255)
            }
        }
    }

    /** A poster turned into a soft 16:9 backdrop: shrunk to a few pixels and stretched back, then dimmed. */
    fun blurredBackdrop(poster: Bitmap): Bitmap {
        val tiny = Bitmap.createScaledBitmap(poster, 8, 12, true)
        val mid = Bitmap.createScaledBitmap(tiny, 32, 18, true)          // 16:9, crops nothing important at this blur
        val out = Bitmap.createScaledBitmap(mid, 1280, 720, true).copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(Color.argb(70, 0, 0, 0))
        return out
    }

    /**
     * The cover's one main colour: the palette colour with the most similar neighbours, favouring
     * colourful ones over greys (so a mostly-black poster with a red title gives red, not black).
     */
    fun mainColor(palette: IntArray): Int {
        val hsv = FloatArray(3)
        fun dist(a: Int, b: Int) = kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
        return palette.maxByOrNull { c ->
            Color.colorToHSV(c, hsv)
            palette.count { dist(it, c) < 90 } * (0.35f + hsv[1] * hsv[2])
        } ?: Color.DKGRAY
    }

    /** Transparent rainbow bands (two opposite sectors) for the disc's turning sheen layer. */
    fun discSheen(): Bitmap {
        val s = 512; val c = s / 2f
        val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val clear = Color.argb(0, 255, 255, 255)
        fun hue(h: Float, a: Int) = Color.HSVToColor(a, floatArrayOf(h, 0.9f, 1f))
        val bow = listOf(clear, clear, hue(330f, 120), hue(25f, 150), hue(60f, 150), hue(120f, 140), hue(190f, 140), hue(250f, 150), hue(290f, 120), clear, clear)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = android.graphics.SweepGradient(c, c, (bow + bow).toIntArray(), null) }
        canvas.drawCircle(c, c, c, p)
        // Strongest on the data area, gone over the clear hub.
        val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
        hub.shader = RadialGradient(c, c, c * 0.42f, intArrayOf(Color.BLACK, Color.BLACK, Color.argb(0, 0, 0, 0)), floatArrayOf(0f, 0.82f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(c, c, c * 0.42f, hub)
        return bitmap
    }

    /** Spine title colours by film id (the logo colour after the spine's rules), reused for disc logos. */
    val titleColors = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The colour the spine prints this film's title in, from its logo. */
    fun titleColorFor(movie: Movie, logo: Bitmap): Int = titleColors[movie.id] ?: run {
        val palette = palettes[movie.id]
        val c = titleSource(movie, palette, logoColor(logo, spineBackground(movie, palette))) ?: Color.WHITE
        val base = palette?.let { average(it) } ?: movie.color
        if (isRed(base) && (isRed(c) || isPink(c))) Color.WHITE else c
    }

    fun back(movie: Movie, poster: Bitmap? = null, scale: Int = 2): Bitmap {
        // Drawn at 2x (1024 x 1448) so the small print stays crisp up close; layout is in 512 x 724 units.
        val bitmap = Bitmap.createBitmap(512 * scale, 724 * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat())
        if (poster == null) {
            splotches(canvas, 512f, 724f, paletteFor(movie), movie.id + "-back", 160f, 180f)
            canvas.drawColor(Color.argb(150, 255, 255, 255))  // lightened so dark text reads, like the poster back
        } else {
            // Shrinking to a few pixels then stretching back with filtering is a cheap, very strong blur.
            val tiny = Bitmap.createScaledBitmap(poster, 6, 9, true)
            canvas.drawBitmap(Bitmap.createScaledBitmap(tiny, 512, 724, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawColor(Color.argb(150, 255, 255, 255))  // lightened so dark text reads
        }
        // Edition band across the top, matching the spine (and real case backs).
        val (band, bandLabel) = edition(movie.resolution)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        canvas.drawRect(0f, 0f, 512f, 52f, Paint().apply { color = band ?: Color.rgb(28, 28, 30) })
        paint.color = Color.WHITE; paint.textSize = 28f; paint.letterSpacing = 0.12f
        canvas.drawText(bandLabel, 256f, 36f, paint)
        // Under the band: the edition, or how many versions this case holds.
        val subtitle = when {
            movie.versions.size > 1 -> "${movie.versions.size} VERSIONS · " + movie.versions.joinToString(" / ") { it.label.uppercase() }
            movie.edition != null -> movie.edition!!.uppercase()
            else -> null
        }
        if (subtitle != null) {
            canvas.drawRect(0f, 52f, 512f, 80f, Paint().apply { color = Color.argb(200, 255, 255, 255) })
            paint.color = Color.rgb(25, 25, 25); paint.textSize = 15f; paint.letterSpacing = 0.1f
            canvas.drawText(android.text.TextUtils.ellipsize(subtitle, TextPaint(paint), 470f, android.text.TextUtils.TruncateAt.END).toString(), 256f, 71f, paint)
        }
        val titleTop = if (subtitle != null) 100f else 76f
        // Billing block, like a real case back: under a rule, the formats on two full-width lines,
        // then the studio logos on the left and the rating on the right, each with its own room.
        val bottom = 724f - 30f
        canvas.drawRect(36f, bottom - 92f, 476f, bottom - 90.5f, Paint().apply { color = Color.argb(90, 0, 0, 0) })
        val best = movie.versions.firstOrNull()
        val tech = listOfNotNull(best?.video, best?.audio).joinToString("  ·  ")
        val format = if (movie.versions.size > 1) "${movie.versions.size}-Disc Set · " + formatLine(movie.resolution).substringBefore(" ·") else formatLine(movie.resolution)
        paint.textAlign = Paint.Align.LEFT; paint.letterSpacing = 0f
        paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 16f; paint.color = Color.rgb(30, 30, 30)
        if (tech.isNotEmpty()) canvas.drawText(android.text.TextUtils.ellipsize(tech, TextPaint(paint), 440f, android.text.TextUtils.TruncateAt.END).toString(), 36f, bottom - 70f, paint)
        paint.typeface = Typeface.DEFAULT; paint.color = Color.rgb(60, 60, 60)
        canvas.drawText(format, 36f, if (tech.isNotEmpty()) bottom - 50f else bottom - 70f, paint)
        var right = 476f
        val rating = shortRating(movie.rating)
        if (rating != null) {
            paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 22f; paint.color = Color.rgb(20, 20, 20)
            val w = paint.measureText(rating) + 16f
            canvas.drawRoundRect(RectF(right - w, bottom - 30f, right, bottom + 4f), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.rgb(20, 20, 20) })
            canvas.drawText(rating, right - w + 8f, bottom - 5f, paint)
            right -= w + 20f
        }
        // Logos sit on the bottom line, up to 34 tall, shrunk together if they'd reach the rating.
        val logos = studioLogos(movie)
        if (logos.isNotEmpty()) {
            val widths = logos.map { minOf(150f, 34f * it.width / it.height) }
            val k = minOf(1f, (right - 36f - 18f * (logos.size - 1)) / widths.sum())
            val ink = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = android.graphics.PorterDuffColorFilter(Color.rgb(20, 20, 20), android.graphics.PorterDuff.Mode.SRC_IN) }
            var x = 36f
            logos.forEachIndexed { i, logo ->
                val w = widths[i] * k; val h = w * logo.height / logo.width
                canvas.drawBitmap(logo, null, RectF(x, bottom + 4f - h, x + w, bottom + 4f), ink)
                x += w + 18f
            }
        }

        val ink = Color.rgb(20, 20, 20)
        val titleBottom = fitted(canvas, movie.title, 36f, titleTop, 440, 120, 46f, 28f, Typeface.DEFAULT_BOLD, ink, 3)
        val facts = listOfNotNull(movie.year?.toString(), movie.minutes?.let { "$it min" }).joinToString("  ·  ")
        val factsBottom = fitted(canvas, facts, 36f, titleBottom + 12f, 440, 40, 25f, 25f, Typeface.DEFAULT, Color.rgb(70, 70, 70), 1)
        val top = factsBottom + 24f
        fitted(canvas, movie.summary, 36f, top, 440, (bottom - 108f - top).toInt(), 31f, 19f, Typeface.DEFAULT, Color.rgb(28, 28, 28), Int.MAX_VALUE)
        backSticker(Canvas(bitmap), bitmap, movie)  // a watched film's "Previously Viewed" sticker, in real size
        return bitmap
    }

    /** Draws wrapped text, shrinking from [maxSize] to [minSize] to fit [height]; returns its bottom edge. */
    private fun fitted(canvas: Canvas, value: String, x: Float, y: Float, width: Int, height: Int, maxSize: Float, minSize: Float, face: Typeface, color: Int, maxLines: Int): Float {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; this.color = color }
        var size = maxSize
        fun layout(lines: Int) = StaticLayout.Builder.obtain(value, 0, value.length, paint.apply { textSize = size }, width)
            .setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        var l = layout(maxLines)
        while ((l.height > height || l.getEllipsisCount(l.lineCount - 1) > 0) && size > minSize) { size -= 1f; l = layout(maxLines) }
        if (l.height > height) {  // still too tall at the smallest size: keep the lines that fit, end with "…"
            val fit = maxOf(1, (0 until l.lineCount).count { l.getLineBottom(it) <= height })
            l = layout(fit)
        }
        canvas.save(); canvas.translate(x, y); l.draw(canvas); canvas.restore()
        return y + l.height
    }

    /** How a logo's inked area sits in its own bounding box: [solidity] the inked share of the box,
     *  [rim] the inked share of the box's outline (a plaque's edge is inked all round; lettering's isn't). */
    class Silhouette(val solidity: Float, val rim: Float, val inked: Int)

    fun silhouette(alpha: IntArray, w: Int, h: Int): Silhouette? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var n = 0
        for (i in alpha.indices) if (alpha[i] > 128) { val x = i % w; val y = i / w; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; n++ }
        if (n == 0) return null
        // The outline two pixels in, so a soft anti-aliased edge doesn't count against a plaque.
        val ax = minOf(x0 + 2, x1); val bx = maxOf(x1 - 2, x0); val ay = minOf(y0 + 2, y1); val by = maxOf(y1 - 2, y0)
        var rim = 0; var all = 0
        for (x in ax..bx) { all += 2; if (alpha[ay * w + x] > 128) rim++; if (alpha[by * w + x] > 128) rim++ }
        for (y in ay..by) { all += 2; if (alpha[y * w + ax] > 128) rim++; if (alpha[y * w + bx] > 128) rim++ }
        return Silhouette(n.toFloat() / ((x1 - x0 + 1) * (y1 - y0 + 1)), rim.toFloat() / maxOf(1, all), n)
    }

    /** A logo's silhouette is a plaque, not lettering: a one-ink print of it would be a solid block. */
    fun isPlaque(s: Silhouette) = s.rim > 0.85f && s.solidity > 0.75f

    /**
     * The shape a one-ink disc print takes from [logo], or null when it has none (the disc then prints the
     * title). The rules, for any logo:
     * - faint (even its most solid part mostly see-through, like Anemone's): printed at full strength;
     * - on a plaque, outlined by a box (Kangaroo Jack's yellow sign, The Social Network's blue bar): the
     *   plaque's colour, sampled round its edge, is keyed out and the lettering kept; if that doesn't leave
     *   clean lettering, no logo;
     * - nearly solid without a box (Rushmore's letter tiles): keyed the same way when that opens it up into
     *   lettering; otherwise it's heavy lettering and printed as is, unless it's solid enough to be a block
     *   (a glow round the whole logo, like Godzilla's): then no logo.
     * Judged on a copy the size of a 320x160 fetch, so the same logo gets the same verdict at any size.
     */
    fun inkMask(logo: Bitmap): Bitmap? {
        val w = logo.width; val h = logo.height
        val px = IntArray(w * h).also { logo.getPixels(it, 0, w, 0, 0, w, h) }
        val top = px.maxOf { it ushr 24 }
        if (top < 16) return null
        fun boost(a: Int) = a * 255 / (if (top < 200) top else 255)
        val k = minOf(1f, 320f / w, 160f / h)
        val small = if (k < 1f) Bitmap.createScaledBitmap(logo, maxOf(1, (w * k).toInt()), maxOf(1, (h * k).toInt()), true) else logo
        val sw = small.width; val sh = small.height
        val spx = IntArray(sw * sh).also { small.getPixels(it, 0, sw, 0, 0, sw, sh) }
        val salpha = IntArray(spx.size) { boost(spx[it] ushr 24) }
        fun full(alphaOf: (Int) -> Int) = Bitmap.createBitmap(IntArray(px.size) { (alphaOf(it) shl 24) or (px[it] and 0xFFFFFF) }, w, h, Bitmap.Config.ARGB_8888)
        val plain = if (top < 200) full { boost(px[it] ushr 24) } else logo
        val before = silhouette(salpha, sw, sh) ?: return null
        val boxed = isPlaque(before)
        val dense = before.solidity > 0.8f
        if (!boxed && !dense) return plain
        // The plaque's colour: the median of the inked pixels near its edge.
        var x0 = sw; var y0 = sh; var x1 = -1; var y1 = -1
        for (i in salpha.indices) if (salpha[i] > 128) { val x = i % sw; val y = i / sw; x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y) }
        val m = maxOf(2, (minOf(x1 - x0, y1 - y0) * 0.04f).toInt())
        val edge = ArrayList<Int>()
        for (y in y0..y1) for (x in x0..x1) if ((x - x0 < m || x1 - x < m || y - y0 < m || y1 - y < m) && salpha[y * sw + x] > 128) edge += spx[y * sw + x]
        fun med(shift: Int) = edge.map { (it shr shift) and 255 }.sorted()[edge.size / 2]
        val pr = med(16); val pg = med(8); val pb = med(0)
        fun distance(c: Int): Float { val dr = ((c shr 16) and 255) - pr; val dg = ((c shr 8) and 255) - pg; val db = (c and 255) - pb; return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) }
        // How far "plaque" reaches: wider when its edge is shaded (a darker rim, a gradient), so the rim goes
        // too; three quarters of the edge, so lettering that touches the edge doesn't count.
        val reach = (edge.map { distance(it) }.sorted()[edge.size * 3 / 4] + 30f).coerceIn(50f, 110f)
        fun key(c: Int, a: Int) = (a * ((distance(c) - reach) / 50f).coerceIn(0f, 1f)).toInt()
        // Clean when what's left is lettering: no longer box-shaped and more than specks; a nearly solid logo
        // without a box must also have opened up (heavy lettering keyed against its own colour leaves little).
        val after = silhouette(IntArray(spx.size) { key(spx[it], salpha[it]) }, sw, sh)
        val clean = after != null && !isPlaque(after) &&
            (if (boxed) after.inked >= before.inked * 0.05f else after.solidity < 0.65f && after.inked >= before.inked * 0.15f)
        return when {
            clean -> full { key(px[it], boost(px[it] ushr 24)) }
            boxed || before.solidity > 0.9f -> null       // never a block on the disc (Godzilla's glow is one)
            else -> plain
        }
    }

    /**
     * The main colour of a film's logo: similar colours are grouped and the biggest group wins,
     * with saturated colours favoured, so a red logo with a white outline stays red.
     */
    fun logoColor(logo: Bitmap, background: Int): Int {
        val small = Bitmap.createScaledBitmap(logo, 64, (64f * logo.height / logo.width).toInt().coerceIn(4, 64), true)
        val weight = HashMap<Int, Float>(); val sums = HashMap<Int, IntArray>()
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val p = small.getPixel(x, y)
            if (Color.alpha(p) < 200) continue  // skip soft edges
            Color.colorToHSV(p, hsv)
            val bucket = (Color.red(p) shr 5 shl 6) or (Color.green(p) shr 5 shl 3) or (Color.blue(p) shr 5)
            weight[bucket] = (weight[bucket] ?: 0f) + 0.25f + hsv[1] * hsv[2]
            val sum = sums.getOrPut(bucket) { IntArray(4) }
            sum[0] += Color.red(p); sum[1] += Color.green(p); sum[2] += Color.blue(p); sum[3]++
        }
        val best = weight.maxByOrNull { it.value }?.key ?: return Color.WHITE
        val sum = sums.getValue(best)
        return Color.rgb(sum[0] / sum[3], sum[1] / sum[3], sum[2] / sum[3])  // used exactly; the spine adapts instead
    }

    /** Reds (including deep and brick reds), judged by hue with enough colour to count. */
    private fun isRed(c: Int): Boolean {
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        return (hsv[0] >= 345f || hsv[0] <= 15f) && hsv[1] > 0.35f && hsv[2] > 0.2f
    }

    /** Saturated, bright reds (and red-oranges), which luminance alone calls "dark". */
    private fun isVividRed(c: Int): Boolean {
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        return (hsv[0] >= 340f || hsv[0] <= 22f) && hsv[1] > 0.45f && hsv[2] > 0.5f
    }

    /** Pinks and magentas: their low green makes them look "dark" by the numbers even when vivid. */
    private fun isPink(c: Int): Boolean {
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        return (hsv[0] >= 290f || hsv[0] <= 15f) && hsv[1] in 0.2f..0.85f && hsv[2] > 0.6f ||
            (hsv[0] in 300f..345f && hsv[1] > 0.2f && hsv[2] > 0.5f)
    }

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /** The colour the spine's title sits on (used to keep logo colours readable). */
    fun spineBackground(movie: Movie, palette: IntArray?) = if (palette == null) darker(movie.color, 0.45f) else darker(average(palette), 0.5f)  // bubbles + the 30% black wash

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, width: Int, size: Float, face: Typeface, color: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; this.color = color }
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
    }
}
