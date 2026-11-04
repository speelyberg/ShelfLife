package com.shelflife.tv

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject

/**
 * The disc menu's music: a 30 s preview of the film's main theme from the iTunes Search API (free, no key),
 * played once, faded over its last 2.5 s, then 5 s of quiet before it plays again. iTunes ranks the soundtrack's signature song first for most films (Brother Bear → "On My Way"); a
 * track named for the film or called a theme / main title is preferred over the rest. Streamed live, never stored:
 * only the preview URL (or "none") is cached per film. Previews are Apple's to serve, so nothing ships in the app.
 */
class MenuMusic(private val context: Context) {
    private val cache by lazy { context.getSharedPreferences("menu-music", Context.MODE_PRIVATE) }
    private var player: ExoPlayer? = null
    @Volatile private var playingFor: String? = null

    /** The preview URL for [movie], from the cache or iTunes (network thread). Null when there is nothing fitting. */
    fun lookup(movie: Movie): String? {
        cache.getString(movie.id, null)?.let { return it.takeIf { u -> u != "none" } }
        val url = runCatching { search(movie) }.getOrNull()
        cache.edit().putString(movie.id, url ?: "none").apply()
        android.util.Log.i("ShelfLifeMusic", "${movie.title}: ${url ?: "no theme found"}")
        return url
    }

    private fun search(movie: Movie): String? {
        val term = java.net.URLEncoder.encode("${movie.title} soundtrack", "UTF-8")
        val c = java.net.URL("https://itunes.apple.com/search?term=$term&entity=song&limit=25&country=US").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6_000; c.readTimeout = 6_000
        val results = try { JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("results") } finally { c.disconnect() } ?: return null
        val title = plain(movie.title)
        val rows = (0 until results.length()).map { results.getJSONObject(it) }
            .filter { it.optString("previewUrl").isNotEmpty() }
            .filter { r -> plain(r.optString("collectionName")).let { it.contains(title) && (it.contains("soundtrack") || it.contains("motion picture") || it.contains("score") || it.contains("original")) } }
        if (rows.isEmpty()) return null
        fun score(r: JSONObject, i: Int): Int {
            val name = plain(r.optString("trackName"))
            var s = -i                                           // iTunes' own ranking, best first
            if (name.contains("main title") || name.contains("theme") || name.contains(title)) s += 10
            if (name.contains("end credit") || name.contains("suite") || name.contains("reprise")) s -= 3
            if (name.contains("karaoke") || name.contains("instrumental") || name.contains("remix")) s -= 6
            return s
        }
        return rows.withIndex().maxByOrNull { (i, r) -> score(r, i) }?.value?.optString("previewUrl")
    }
    private fun plain(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]+"), " ").trim()

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var run = 0
    /** Plays [url] under the menu for [movieId]: the preview, a fade over its last 2.5 s, 5 s of quiet, then again (main thread). */
    fun play(movieId: String, url: String, volume: Float = 0.55f) {
        if (playingFor == movieId && player != null) return
        stop()
        playingFor = movieId
        val id = ++run
        val p = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url)); repeatMode = Player.REPEAT_MODE_OFF; this.volume = volume
            prepare(); playWhenReady = true
        }
        player = p
        val fadeMs = 2_500L; val gapMs = 5_000L; val stepMs = 50L
        val tick = object : Runnable {
            override fun run() {
                if (id != this@MenuMusic.run) return
                val left = p.duration - p.currentPosition
                if (p.duration > 0 && left <= fadeMs) p.volume = volume * (left.coerceAtLeast(0L) / fadeMs.toFloat())
                main.postDelayed(this, stepMs)
            }
        }
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED || id != this@MenuMusic.run) return
                p.volume = 0f
                main.postDelayed({ if (id == this@MenuMusic.run) { p.seekTo(0); p.volume = volume; p.playWhenReady = true } }, gapMs)
            }
        })
        main.post(tick)
    }
    fun stop() { run++; player?.release(); player = null; playingFor = null }
}
