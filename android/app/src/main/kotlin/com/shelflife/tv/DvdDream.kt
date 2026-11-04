package com.shelflife.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.service.dreams.DreamService
import android.view.View
import android.widget.FrameLayout

/**
 * ShelfLife as the TV's own screensaver: the room's idle DVD player screen ([Screensaver]) filling
 * the whole screen, black behind it. Pick it in the TV's screensaver settings; its effects are set in
 * ShelfLife's Settings, under TV screensaver ([DvdDream.Settings]). The logo is the one the app downloads
 * on first launch, so until ShelfLife has been opened once the screen stays black.
 */
class DvdDream : DreamService() {
    /** The screensaver's settings, stored with the app's ("settings" preferences). */
    class Settings(context: Context) {
        private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val speeds = listOf("Slow" to 0.06f, "Normal" to 0.1f, "Fast" to 0.16f)
        val sizes = listOf("Small" to 0.14f, "Medium" to 0.2f, "Large" to 0.28f)
        var speed get() = prefs.getInt("dreamSpeed", 1); set(v) = prefs.edit().putInt("dreamSpeed", v).apply()
        var size get() = prefs.getInt("dreamSize", 1); set(v) = prefs.edit().putInt("dreamSize", v).apply()
        var glow get() = prefs.getBoolean("dreamGlow", true); set(v) = prefs.edit().putBoolean("dreamGlow", v).apply()
        var scanlines get() = prefs.getBoolean("dreamScanlines", false); set(v) = prefs.edit().putBoolean("dreamScanlines", v).apply()
        var cornerFlash get() = prefs.getBoolean("dreamCornerFlash", true); set(v) = prefs.edit().putBoolean("dreamCornerFlash", v).apply()
        /** The room's colour setting, except Off: choosing the screensaver means you want it. */
        val mode get() = runCatching { Screensaver.Mode.valueOf(prefs.getString("screensaver", "COLORFUL")!!) }.getOrDefault(Screensaver.Mode.COLORFUL)
            .let { if (it == Screensaver.Mode.OFF) Screensaver.Mode.COLORFUL else it }
    }

    private var saver: Screensaver? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        Extras.init(this)
        val settings = Settings(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val saver = Screensaver(this, { root.width.takeIf { it > 0 }?.let { w -> val h = root.height.toFloat(); floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h, 0f, h) } }) { true }
        saver.mode = settings.mode
        saver.speed = settings.speeds[settings.speed].second
        saver.logoSize = settings.sizes[settings.size].second
        saver.glow = settings.glow
        saver.setLogo(Extras.bitmap(Extras.DVD_VIDEO_LOGO))
        root.addView(saver.view)
        if (settings.scanlines) root.addView(Scanlines(this))
        if (settings.cornerFlash) {
            // A white flash over everything, fading out over a second; at most one every few seconds.
            val flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
            root.addView(flash)
            var lastFlash = 0L
            saver.onCorner = {
                val now = System.currentTimeMillis()
                if (now - lastFlash > 3_000) { lastFlash = now; flash.animate().cancel(); flash.alpha = 0.7f; flash.animate().alpha(0f).setDuration(1_000).start() }
            }
        }
        setContentView(root)
        this.saver = saver
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); saver?.start() }
    override fun onDreamingStopped() { saver?.stop(); super.onDreamingStopped() }

    /** CRT scanlines: a dark line every third pixel row across the whole screen. */
    private class Scanlines(context: Context) : View(context) {
        private val paint = Paint().apply { color = Color.argb(90, 0, 0, 0) }
        override fun onDraw(canvas: Canvas) {
            var y = 0f
            while (y < height) { canvas.drawRect(0f, y, width.toFloat(), y + 1f, paint); y += 3f }
        }
    }
}
