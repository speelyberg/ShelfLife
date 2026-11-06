package com.shelflife.tv

import android.content.Context
import android.graphics.Color
import android.service.dreams.DreamService
import android.widget.FrameLayout

/**
 * ShelfLife as the TV's own screensaver: the room's idle DVD player screen ([Screensaver]) filling
 * the whole screen, black behind it. Pick it in the TV's screensaver settings; its effects are set in
 * ShelfLife's Settings, under TV screensaver ([DvdDream.Settings]), and the room's TV follows them too. The logo is the one the app downloads
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
        saver.use(settings)
        saver.setLogo(Extras.bitmap(Extras.DVD_VIDEO_LOGO))
        root.addView(saver.view)
        setContentView(root)
        this.saver = saver
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); saver?.start() }
    override fun onDreamingStopped() { saver?.stop(); super.onDreamingStopped() }
}
