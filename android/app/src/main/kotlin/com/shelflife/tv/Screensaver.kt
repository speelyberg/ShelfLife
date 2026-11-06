package com.shelflife.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The DVD player's idle screen on the TV: the DVD Video logo drifting around the picture and
 * changing colour at every wall. It's a flat Android view mapped onto the TV's picture with a
 * perspective matrix, so it stays on the TV from any camera angle (and keeps bouncing while the
 * TV is off screen) without the 3D room being redrawn for it. [quad] gives the picture's corners on
 * screen (the room's TV, or the whole screen for [DvdDream]).
 */
class Screensaver(context: Context, private val quad: () -> FloatArray?, private val active: () -> Boolean) : Choreographer.FrameCallback {
    enum class Mode(val label: String) { COLORFUL("Colorful"), GREY("Grey"), WHITE("White"), OFF("Off") }
    var mode = Mode.COLORFUL
        set(value) { field = value; tint(if (value == Mode.COLORFUL) colors[colorIndex] else if (value == Mode.GREY) Color.rgb(150, 150, 150) else Color.WHITE) }

    private companion object {
        const val LOGO_W = 384; const val LOGO_H = LOGO_W * 287 / 480
        const val TV_W = 1459f; const val TV_H = 818f  // the picture in millimetres: the bounce's own space
        const val GLOW_PAD = 40; const val GLOW_R = 9     // glow margin round the logo, and blur radius (3 box passes), logo-view px
    }
    /** Logo width, as a share of the picture's, and how fast it drifts (picture widths a second). */
    var logoSize = 0.2f
    var speed = 0.1f
    /** CRT scanlines over the picture: a dark line every third screen row. */
    var scanlines = false
        set(value) { field = value; effects.invalidate() }
    /** The picture flashes white when the logo reaches a corner (both walls at once, or near enough to look it). */
    var cornerFlash = false
    private var lastFlash = 0L
    private val flash = android.animation.ValueAnimator.ofFloat(0.7f, 0f).apply {
        duration = 1_000
        addUpdateListener { effects.flash = it.animatedValue as Float; effects.invalidate() }
    }

    /** The effects set in Settings → TV screensaver; the room's TV and [DvdDream] both follow them. */
    fun use(s: DvdDream.Settings) {
        speed = s.speeds[s.speed].second; logoSize = s.sizes[s.size].second
        glow = s.glow; scanlines = s.scanlines; cornerFlash = s.cornerFlash
    }
    /**
     * A soft glow behind the logo, in its colour: a blurred copy of the logo, made once on the CPU.
     * (A live RenderEffect blur on the moving view left smeared trails on the Onn's Mali GPU.)
     */
    var glow = false
        set(value) {
            field = value
            if (value && glowView == null) glowView = ImageView(logo.context).apply {
                scaleType = ImageView.ScaleType.FIT_XY; alpha = 0.75f
                layoutParams = FrameLayout.LayoutParams(LOGO_W + 2 * GLOW_PAD, LOGO_H + 2 * GLOW_PAD)
                setColorFilter(currentTint, PorterDuff.Mode.SRC_IN)
                logoBitmap?.let { setImageBitmap(glowOf(it)) }
                view.addView(this, 0)
            }
            glowView?.visibility = if (value) View.VISIBLE else View.GONE
        }
    private var glowView: ImageView? = null
    private var logoBitmap: android.graphics.Bitmap? = null
    private val glowMatrix = Matrix()
    private var currentTint = Color.WHITE
    private val logo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY; alpha = 0.92f
        layoutParams = FrameLayout.LayoutParams(LOGO_W, LOGO_H)
    }
    private val effects = Effects(context)
    /** Full-screen; MainActivity adds it above the 3D view, under the overlay. */
    val view = FrameLayout(context).apply { visibility = View.GONE; addView(logo); addView(effects) }

    private val colors = intArrayOf(0xFF3D7BFF.toInt(), 0xFFFF3D9A.toInt(), 0xFF3DDC84.toInt(), 0xFFFFD23D.toInt(),
        0xFFFF7A3D.toInt(), 0xFFA45CFF.toInt(), 0xFF3DE0FF.toInt(), 0xFFFF4040.toInt())
    private var colorIndex = 0
    private var x = 0.3f; private var y = 0.2f      // logo's top-left, as shares of the picture
    private var vx = 1f; private var vy = 1f        // directions
    private var last = 0L
    private var shown = false
    private val choreographer = Choreographer.getInstance()
    private val tvMatrix = Matrix(); private val logoMatrix = Matrix()
    private val src = floatArrayOf(0f, 0f, TV_W, 0f, TV_W, TV_H, 0f, TV_H)

    init { mode = Mode.COLORFUL }

    fun start() { choreographer.removeFrameCallback(this); choreographer.postFrameCallback(this) }
    fun stop() = choreographer.removeFrameCallback(this)

    override fun doFrame(now: Long) {
        choreographer.postFrameCallback(this)
        val quad = quad()
        // Only touch the views while the TV is actually on screen: every change makes the UI layer
        // redraw, which on the Onn's GPU slowed the 3D (shelf browsing, the case's focus pull).
        val onScreen = quad != null && (0 until 4).any { quad[it * 2] in 0f..1920f && quad[it * 2 + 1] in 0f..1080f } ||
            quad != null && quad[0] < 0f && quad[2] > 1920f  // zoomed in past the TV's edges (TV view)
        if (mode == Mode.OFF || !hasLogo || !active()) {
            if (shown) {
                shown = false
                logo.animate().cancel()
                if (onScreen) logo.animate().alpha(0f).setDuration(300).withEndAction { view.visibility = View.GONE }.start()
                else view.visibility = View.GONE
            }
            last = 0L
            return
        }
        if (!shown) {
            shown = true
            view.visibility = View.VISIBLE
            logo.animate().cancel()
            // Fade the small logo, never the full-screen layer (a full-screen fade is costly on the Mali).
            if (onScreen) { logo.alpha = 0f; logo.animate().alpha(0.92f).setDuration(500).start() } else logo.alpha = 0.92f
        }
        // The bounce runs in the picture's own space, so it carries on the same wherever the camera is.
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
        last = now
        x += vx * speed * dt; y += vy * speed * dt * TV_W / TV_H
        val maxX = 1f - logoSize; val maxY = 1f - logoSize * TV_W * LOGO_H / LOGO_W / TV_H
        var hitX = false; var hitY = false
        if (x <= 0f) { x = 0f; vx = 1f; hitX = true } else if (x >= maxX) { x = maxX; vx = -1f; hitX = true }
        if (y <= 0f) { y = 0f; vy = 1f; hitY = true } else if (y >= maxY) { y = maxY; vy = -1f; hitY = true }
        val hit = hitX || hitY
        // A corner: both walls this frame, or the other wall within a hair (the eye can't tell).
        val near = 0.012f
        val corner = (hitX && (y < near || y > maxY - near)) || (hitY && (x < near || x > maxX - near))
        // At most one flash every few seconds.
        if (corner && cornerFlash && onScreen && System.currentTimeMillis() - lastFlash > 3_000) { lastFlash = System.currentTimeMillis(); flash.start() }
        if (hit && mode == Mode.COLORFUL) { colorIndex = (colorIndex + 1 + (0 until colors.size - 1).random()) % colors.size; if (onScreen) tint(colors[colorIndex]) }
        if (!onScreen) { if (logo.visibility != View.INVISIBLE) logo.visibility = View.INVISIBLE; effects.at(null); return }
        effects.at(quad)
        if (logo.visibility != View.VISIBLE) { logo.visibility = View.VISIBLE; if (mode == Mode.COLORFUL) tint(colors[colorIndex]) }
        // Logo pixels → picture → screen (perspective, from the TV's corners in the last 3D frame).
        tvMatrix.setPolyToPoly(src, 0, quad!!, 0, 4)
        val s = logoSize * TV_W / LOGO_W
        logoMatrix.setScale(s, s); logoMatrix.postTranslate(x * TV_W, y * TV_H); logoMatrix.postConcat(tvMatrix)
        logo.animationMatrix = logoMatrix
        glowView?.let { glowMatrix.setTranslate(-GLOW_PAD.toFloat(), -GLOW_PAD.toFloat()); glowMatrix.postConcat(logoMatrix); it.animationMatrix = glowMatrix }
    }

    /** The DVD Video logo from [Extras]; null hides it (the screensaver then shows nothing). */
    fun setLogo(bitmap: android.graphics.Bitmap?) {
        logo.setImageBitmap(bitmap); logoBitmap = bitmap; hasLogo = bitmap != null
        glowView?.setImageBitmap(bitmap?.let { glowOf(it) })
    }

    /** The logo's shape, padded and blurred (alpha only, three box passes ≈ a gaussian), as white for tinting. */
    private fun glowOf(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = LOGO_W; val h = LOGO_H; val p = GLOW_PAD; val ww = w + 2 * p; val hh = h + 2 * p
        val px = IntArray(w * h).also { android.graphics.Bitmap.createScaledBitmap(src, w, h, true).getPixels(it, 0, w, 0, 0, w, h) }
        var a = IntArray(ww * hh)
        for (y in 0 until h) for (x in 0 until w) a[(y + p) * ww + x + p] = px[y * w + x] ushr 24
        var b = IntArray(ww * hh)
        repeat(3) {
            for (horizontal in listOf(true, false)) {
                val lines = if (horizontal) hh else ww; val len = if (horizontal) ww else hh
                val step = if (horizontal) 1 else ww; val lineStep = if (horizontal) ww else 1
                for (line in 0 until lines) {
                    val base = line * lineStep
                    var sum = 0
                    for (i in -GLOW_R..GLOW_R) sum += a[base + i.coerceIn(0, len - 1) * step]
                    for (i in 0 until len) {
                        b[base + i * step] = sum / (2 * GLOW_R + 1)
                        sum += a[base + (i + GLOW_R + 1).coerceAtMost(len - 1) * step] - a[base + (i - GLOW_R).coerceAtLeast(0) * step]
                    }
                }
                val t = a; a = b; b = t
            }
        }
        for (i in a.indices) a[i] = ((a[i] * 3 / 2).coerceAtMost(255) shl 24) or 0xFFFFFF
        return android.graphics.Bitmap.createBitmap(a, ww, hh, android.graphics.Bitmap.Config.ARGB_8888)
    }
    private var hasLogo = false

    /**
     * Scanlines and the corner flash, clipped to the picture's corners on screen (the room's TV, or the
     * whole screen). Redrawn only when the picture moves or the flash fades.
     */
    private inner class Effects(context: Context) : View(context) {
        private var quad: FloatArray? = null
        var flash = 0f
        private val path = android.graphics.Path()
        private val line = android.graphics.Paint().apply { color = Color.argb(90, 0, 0, 0) }
        private val white = android.graphics.Paint().apply { color = Color.WHITE }

        fun at(q: FloatArray?) {
            if (q == null && quad == null || q != null && quad?.contentEquals(q) == true) return
            quad = q?.copyOf()
            if (scanlines || flash > 0f) invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val q = quad ?: return
            if (!scanlines && flash <= 0f) return
            path.rewind(); path.moveTo(q[0], q[1]); for (i in 1..3) path.lineTo(q[i * 2], q[i * 2 + 1]); path.close()
            canvas.save(); canvas.clipPath(path)
            if (scanlines) {
                val bottom = minOf(height.toFloat(), maxOf(q[1], q[3], q[5], q[7]))
                var y = (maxOf(0f, minOf(q[1], q[3], q[5], q[7])) / 3).toInt() * 3f
                while (y < bottom) { canvas.drawRect(0f, y, width.toFloat(), y + 1f, line); y += 3f }
            }
            if (flash > 0f) { white.alpha = (flash * 255).toInt(); canvas.drawPaint(white) }
            canvas.restore()
        }
    }

    private fun tint(color: Int) { currentTint = color; logo.setColorFilter(color, PorterDuff.Mode.SRC_IN); glowView?.setColorFilter(color, PorterDuff.Mode.SRC_IN) }
}
