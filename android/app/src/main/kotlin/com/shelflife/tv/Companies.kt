package com.shelflife.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * The main production company's logo for a film's case back, from TMDB: the first company TMDB credits
 * that has a logo and isn't the distributor already printed. TMDB serves full-colour logos (Warner's
 * yellow-and-blue shield), so each is kept as a greyscale print, the way a case back prints it: dark
 * colours as dense ink, light ones as faint, a white-on-clear logo inverted first. Kept in
 * files/company-prints/<film id>.png; a ".none" file remembers films without one.
 */
object Companies {
    private lateinit var context: android.content.Context
    private val dir by lazy { java.io.File(context.filesDir, "company-prints").apply { mkdirs() } }
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    fun init(context: android.content.Context) { this.context = context }

    private fun file(movie: Movie, ext: String) = java.io.File(dir, movie.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ext)

    /** Only what's already on disk: safe on any thread. */
    fun cached(movie: Movie): Bitmap? = memory[movie.id] ?: file(movie, ".png").takeIf { it.exists() }
        ?.let { BitmapFactory.decodeFile(it.path) }?.also { memory[movie.id] = it }

    /** Looks the film up on TMDB once (network thread). */
    fun fetch(movie: Movie) {
        if (BuildConfig.TMDB_API_KEY.isEmpty() || cached(movie) != null || file(movie, ".none").exists()) return
        runCatching {
            val q = java.net.URLEncoder.encode(movie.title, "UTF-8")
            val results = json("search/movie?query=$q" + (movie.year?.let { "&primary_release_year=$it" } ?: "")).getJSONArray("results")
            if (results.length() == 0) return@runCatching null
            val companies = json("movie/${results.getJSONObject(0).getInt("id")}").getJSONArray("production_companies")
            val distributor = Bumpers.forMovie(movie, context)
            val path = (0 until companies.length()).map { companies.getJSONObject(it) }
                .firstOrNull { !it.isNull("logo_path") && (distributor == null || Bumpers.forStudio(it.getString("name")) != distributor) }
                ?.getString("logo_path") ?: return@runCatching null
            val logo = get("https://image.tmdb.org/t/p/w500$path") { BitmapFactory.decodeStream(it) }
            logo?.let(::print)
        }.onSuccess { mask ->
            android.util.Log.i("ShelfLifeAudit", "company logo for ${movie.title}: ${if (mask == null) "none" else "printed"}")
            if (mask == null) file(movie, ".none").createNewFile()
            else { file(movie, ".png").outputStream().use { mask.compress(Bitmap.CompressFormat.PNG, 100, it) }; memory[movie.id] = mask }
        }.onFailure { android.util.Log.w("ShelfLifeAudit", "company logo for ${movie.title}: $it") }  // no file, so the next look tries again
    }

    /** The logo as grey ink: alpha = how dark it is. Thin line art (Studio Ghibli's) gets its ink boosted so it
     *  still reads at 34 px; a logo that's mostly white (drawn for a dark page) is inverted. */
    fun print(logo: Bitmap): Bitmap? {
        val w = logo.width; val h = logo.height
        val px = IntArray(w * h).also { logo.getPixels(it, 0, w, 0, 0, w, h) }
        fun lum(c: Int) = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
        var sum = 0f; var n = 0f
        for (c in px) { val a = (c ushr 24) / 255f; if (a > 0.5f) { sum += lum(c) * a; n += a } }
        if (n < 1f) return null
        val light = sum / n > 0.62f
        val out = IntArray(px.size) { i ->
            val c = px[i]; val a = (c ushr 24) / 255f
            val ink = (if (light) lum(c) else 1f - lum(c)) * a
            (minOf(255, (ink * 1.6f * 255).toInt()) shl 24) or 0x141414
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun json(path: String) = get("https://api.themoviedb.org/3/$path" + (if ("?" in path) "&" else "?") + "api_key=${BuildConfig.TMDB_API_KEY}") {
        org.json.JSONObject(it.bufferedReader().readText())
    }

    private fun <T> get(url: String, read: (java.io.InputStream) -> T): T {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 8_000
        try { return c.inputStream.use(read) } finally { c.disconnect() }
    }
}
