package com.shelflife.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Other people's marks, kept out of the source and the APK and fetched once into the app's files:
 * - the DVD Video and Blu-ray logos from Wikimedia Commons (where they were taken from),
 * - the studio logos for the case backs from TMDB (needs TMDB_API_KEY),
 * - the Feature Presentation intros and the QuickPlay background (renders, so they have no public source) from
 *   https://speelyberg.com/shelflife/assets, or EXTRAS_URL in android/secrets.env (docs/extras.md).
 * Anything missing is simply left out (no intro, no logo) and tried again next launch.
 */
object Extras {
    const val FEATURE_DISNEY = "feature_presentation.mp4"
    const val FEATURE_PARAMOUNT = "feature_presentation_paramount.mp4"
    const val FEATURE_FOX = "feature_presentation_fox.mp4"
    const val FEATURE_WARNER = "feature_presentation_warner.mp4"
    const val FEATURE_SONY = "feature_presentation_sony.mp4"
    const val FEATURE_UNIVERSAL = "feature_presentation_universal.mp4"
    const val QUICKPLAY_BG = "quickplay_bg.mp4"
    const val BLURAY_LOGO = "bluray_logo.commons.png"
    const val DVD_VIDEO_LOGO = "dvd_video_logo.commons.png"
    fun studioLogo(name: String) = "studio_$name.tmdb.png"
    private val videos = listOf(FEATURE_DISNEY, FEATURE_PARAMOUNT, FEATURE_FOX, FEATURE_WARNER, FEATURE_SONY, FEATURE_UNIVERSAL, QUICKPLAY_BG)

    private enum class Look { WHITE, INK }
    /** Commons file name, the width to render it at, and how it's printed (white, or as case-back ink). */
    private val commons = mapOf(
        DVD_VIDEO_LOGO to Triple("DVD-Video_Logo.svg", 480, Look.WHITE),   // tinted in code (Screensaver)
        BLURAY_LOGO to Triple("Blu-ray_Disc.svg", 108, Look.WHITE),        // 54 px wide on the spine
    )
    /** TMDB company ids of each distributor's logo (Sony's films carry Columbia's torch lady). */
    private val tmdb = mapOf("disney" to 2, "paramount" to 4, "fox" to 25, "warner" to 174, "sony" to 5, "universal" to 33)
        .mapKeys { studioLogo(it.key) }
    private val all = videos + commons.keys + tmdb.keys
    private const val TAG = "ShelfLifeExtras"
    // Wikimedia asks every client to name itself.
    private const val AGENT = "ShelfLife/0.2 (Android TV; https://github.com/speelyberg/ShelfLife)"

    private lateinit var dir: File
    fun init(context: Context) { dir = File(context.filesDir, "extras").apply { mkdirs() } }

    /** The downloaded file, or null while it isn't there. */
    fun file(name: String): File? = File(dir, name).takeIf { it.isFile && it.length() > 0 }
    fun bitmap(name: String): Bitmap? = file(name)?.let { BitmapFactory.decodeFile(it.path) }

    /** Fetches whatever is missing (blocking; run it off the main thread). True when anything new arrived. */
    fun download(): Boolean {
        dir.listFiles()?.filter { it.name !in all }?.forEach { it.delete() }  // older names, half downloads
        val base = BuildConfig.EXTRAS_URL.trimEnd('/')
        var got = false
        for (name in all) {
            if (file(name) != null) continue
            runCatching {
                val dest = File(dir, name)
                val c = commons[name]; val company = tmdb[name]
                when {
                    c != null -> save(dest, look(fetch("https://commons.wikimedia.org/wiki/Special:FilePath/${c.first}?width=${c.second}") { BitmapFactory.decodeStream(it) }, c.second, c.third))
                    company != null -> {
                        if (BuildConfig.TMDB_API_KEY.isEmpty()) return@runCatching
                        val path = Companies.json("company/$company").getString("logo_path")
                        save(dest, look(fetch("https://image.tmdb.org/t/p/w500$path") { BitmapFactory.decodeStream(it) }, 0, Look.INK))
                    }
                    base.isNotEmpty() -> {
                        val tmp = File(dir, "$name.part")
                        fetch("$base/$name") { input -> tmp.outputStream().use { input.copyTo(it) } }
                        tmp.renameTo(dest)
                    }
                    else -> return@runCatching
                }
                if (dest.length() > 0) { got = true; android.util.Log.i(TAG, "$name: ${dest.length() / 1024} KB") }
            }.onFailure { android.util.Log.w(TAG, "$name: ${it.message}") }
        }
        return got
    }

    private fun <T> fetch(url: String, read: (java.io.InputStream) -> T): T {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", AGENT)
        c.connectTimeout = 8_000; c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.use(read)
        } finally { c.disconnect() }
    }

    /** White (alpha kept), scaled to [width] when it's wider; or as case-back ink (see Companies.print). */
    private fun look(bitmap: Bitmap?, width: Int, look: Look): Bitmap {
        requireNotNull(bitmap) { "not an image" }
        if (look == Look.INK) return requireNotNull(Companies.print(bitmap)) { "no ink in the logo" }
        val b = if (width in 1 until bitmap.width) Bitmap.createScaledBitmap(bitmap, width, bitmap.height * width / bitmap.width, true) else bitmap
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        for (i in px.indices) px[i] = (px[i] and 0xFF000000.toInt()) or 0xFFFFFF
        return Bitmap.createBitmap(px, b.width, b.height, Bitmap.Config.ARGB_8888)
    }

    private fun save(dest: File, bitmap: Bitmap) {
        val tmp = File(dir, dest.name + ".part")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(dest)
    }
}
