package com.shelflife.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Plex over plain HTTPS/JSON. All calls block: run them off the main thread. */
class Plex(private val clientId: String) {
    class Pin(val id: Long, val code: String, val expiresInSeconds: Int)
    class Server(val base: String, val token: String, val route: String)

    private fun open(url: String, method: String = "GET", timeoutMs: Int = 15_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs; readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Plex-Client-Identifier", clientId)
            setRequestProperty("X-Plex-Product", "ShelfLife")
            setRequestProperty("X-Plex-Device-Name", "ShelfLife (Android TV)")
            setRequestProperty("X-Plex-Platform", "Android")
        }

    private fun text(url: String, method: String = "GET", timeoutMs: Int = 15_000): String {
        val c = open(url, method, timeoutMs)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Plex answered ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    // --- Account: the plex.tv/link flow made for TVs (4-character code, entered on a phone) ---

    fun createPin(): Pin = JSONObject(text("https://plex.tv/api/v2/pins?strong=false", "POST")).let { Pin(it.getLong("id"), it.getString("code"), it.optInt("expiresIn", 900)) }

    /** The account token once the code has been entered at plex.tv/link, else null. */
    fun tokenFor(pin: Pin): String? = JSONObject(text("https://plex.tv/api/v2/pins/${pin.id}")).optString("authToken").takeIf { it.isNotEmpty() && it != "null" }

    /** Your own servers first; for each, home network, then remote access, then Plex's relay. */
    fun findServer(accountToken: String): Server {
        val resources = JSONArray(text("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&X-Plex-Token=$accountToken"))
        val servers = (0 until resources.length()).map { resources.getJSONObject(it) }
            .filter { it.optString("provides").contains("server") }
            .sortedBy { if (it.optBoolean("owned")) 0 else 1 }
        for (server in servers) {
            val token = server.optString("accessToken").ifEmpty { accountToken }
            val connections = server.optJSONArray("connections") ?: continue
            val ordered = (0 until connections.length()).map { connections.getJSONObject(it) }
                .sortedBy { if (it.optBoolean("relay")) 2 else if (it.optBoolean("local")) 0 else 1 }
            for (connection in ordered) {
                val base = connection.getString("uri").trimEnd('/')
                val ok = runCatching { text("$base/identity?X-Plex-Token=$token", timeoutMs = 4_000) }.isSuccess
                if (ok) return Server(base, token, if (connection.optBoolean("relay")) "relay" else if (connection.optBoolean("local")) "home network" else "remote access")
            }
        }
        throw IllegalStateException("None of your Plex servers answered. Is the server PC on?")
    }

    // --- Server ---

    fun url(server: Server, path: String, query: Map<String, String> = emptyMap()): String {
        val b = Uri.parse(server.base + path).buildUpon()
        query.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        return b.appendQueryParameter("X-Plex-Token", server.token).build().toString()
    }

    /** One random shelf-full from the first movie library, plus the library's size. */
    fun shelf(server: Server, count: Int = 144): Pair<List<Movie>, Int> {
        val sections = JSONObject(text(url(server, "/library/sections"))).getJSONObject("MediaContainer").optJSONArray("Directory") ?: JSONArray()
        val key = (0 until sections.length()).map { sections.getJSONObject(it) }.firstOrNull { it.optString("type") == "movie" }?.getString("key")
            ?: throw IllegalStateException("No movie library was found on your Plex server.")
        val container = JSONObject(text(url(server, "/library/sections/$key/all", mapOf("sort" to "random", "X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to "${count + 24}", "includeImages" to "1")))).getJSONObject("MediaContainer")
        val items = container.optJSONArray("Metadata") ?: JSONArray()
        val movies = group(items, count)
        return movies to container.optInt("totalSize", movies.size)
    }

    class Extra(val title: String, val partKey: String?)
    class Track(val id: Long, val name: String, val isDefault: Boolean)
    /** A chapter from the file, for Scene Selection; [thumb] is Plex's chapter image path, when it made one. */
    class Chapter(val title: String, val startMs: Long, val thumb: String?)
    /** What the disc menu and player need: background art, the file, its tracks, and special features. */
    class DiscInfo(val art: String?, val partKey: String?, val partId: Long?, val audio: List<Track>, val subtitles: List<Track>, val extras: List<Extra>,
                   val viewOffset: Long = 0, val durationMs: Long = 0, val chapters: List<Chapter> = emptyList())

    fun discInfo(server: Server, ratingKey: String, mediaIndex: Int = 0): DiscInfo {
        fun firstPart(v: JSONObject) = v.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)?.optString("key")?.ifEmpty { null }
        val detail = JSONObject(text(url(server, "/library/metadata/$ratingKey", mapOf("includeChapters" to "1")))).getJSONObject("MediaContainer").getJSONArray("Metadata").getJSONObject(0)
        val chapters = detail.optJSONArray("Chapter")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .sortedBy { it.optLong("startTimeOffset") }
            .mapIndexed { i, c -> Chapter(c.optString("tag").ifEmpty { "Chapter ${i + 1}" }, c.optLong("startTimeOffset"), c.optString("thumb").ifEmpty { null }) }
        val extras = runCatching {
            val list = JSONObject(text(url(server, "/library/metadata/$ratingKey/extras"))).getJSONObject("MediaContainer").optJSONArray("Metadata") ?: JSONArray()
            (0 until list.length()).map { list.getJSONObject(it) }.map { Extra(it.optString("title"), firstPart(it)) }
        }.getOrDefault(emptyList())
        val part = detail.optJSONArray("Media")?.let { it.optJSONObject(mediaIndex) ?: it.optJSONObject(0) }?.optJSONArray("Part")?.optJSONObject(0)
        val streams = part?.optJSONArray("Stream")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        fun tracks(type: Int) = streams.filter { it.optInt("streamType") == type }.map { Track(it.getLong("id"), it.optString("displayTitle", "Track"), it.optBoolean("selected")) }
        return DiscInfo(detail.optString("art").ifEmpty { null }, part?.optString("key")?.ifEmpty { null } ?: firstPart(detail), part?.optLong("id"), tracks(2), tracks(3), extras,
            viewOffset = detail.optLong("viewOffset"), durationMs = detail.optLong("duration"), chapters = chapters)
    }

    /** Remembers a track choice on the server, as Plex's own apps do, so its transcoder uses it. */
    fun selectTracks(server: Server, partId: Long, audioId: Long?, subtitleId: Long?) {
        val q = mutableMapOf("allParts" to "1", "subtitleStreamID" to "${subtitleId ?: 0}")
        audioId?.let { q["audioStreamID"] = "$it" }
        text(url(server, "/library/parts/$partId", q), "PUT")
    }

    /** The original file, untouched: the TV box's hardware decoders take it from here. */
    /** The film's trailers in Plex (library files or Plex Pass streams) as playable parts; empty when it has none. */
    fun trailers(server: Server, ratingKey: String): List<Extra> = runCatching {
        val list = JSONObject(text(url(server, "/library/metadata/$ratingKey/extras"), timeoutMs = 6_000)).getJSONObject("MediaContainer").optJSONArray("Metadata") ?: JSONArray()
        (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("subtype") == "trailer" }
            .mapNotNull { v -> v.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)?.optString("key")?.ifEmpty { null }?.let { Extra(v.optString("title"), it) } }
    }.getOrDefault(emptyList())
    fun directUrl(server: Server, partKey: String) = if (partKey.startsWith("http")) partKey else url(server, partKey)

    /** Plex converts on the fly (HLS) for anything the box can't decode itself. */
    // Transcode sessions this app has started, so they can be ended on eject instead of idling on the server.
    private val sessions = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    /** The sessions to end; the set is emptied. Call [stopTranscodes] with the result off the main thread. */
    fun takeSessions(): List<String> = synchronized(sessions) { sessions.toList().also { sessions.clear() } }
    fun stopTranscodes(server: Server, ids: List<String>) {
        for (id in ids) runCatching { text(url(server, "/video/:/transcode/universal/stop", mapOf("session" to id)), timeoutMs = 5_000) }
    }

    /**
     * Tells Plex where playback is ("playing", "paused" or "stopped"). Plex saves the position for
     * Resume, shows the film as in progress, and marks it watched once a stop lands past ~90%.
     */
    fun timeline(server: Server, ratingKey: String, state: String, timeMs: Long, durationMs: Long) = runCatching {
        text(url(server, "/:/timeline", mapOf("ratingKey" to ratingKey, "key" to "/library/metadata/$ratingKey", "state" to state,
            "time" to "${timeMs.coerceAtLeast(0)}", "duration" to "${durationMs.coerceAtLeast(0)}", "identifier" to "com.plexapp.plugins.library")), timeoutMs = 8_000)
    }.isSuccess

    /** Marks a film watched (used when it plays to the end). */
    fun scrobble(server: Server, ratingKey: String) = runCatching {
        text(url(server, "/:/scrobble", mapOf("key" to ratingKey, "identifier" to "com.plexapp.plugins.library")), timeoutMs = 8_000)
    }.isSuccess

    fun transcodeUrl(server: Server, ratingKey: String, offsetSeconds: Long, convertAudio: Boolean = false, burnSubtitles: Boolean = false, mediaIndex: Int = 0): String {
        val session = java.util.UUID.randomUUID().toString()
        sessions += session
        return url(server, "/video/:/transcode/universal/start.m3u8", mapOf(
            "path" to "/library/metadata/$ratingKey", "mediaIndex" to "$mediaIndex", "partIndex" to "0", "protocol" to "hls",
            "fastSeek" to "1", "directPlay" to "0", "directStream" to if (burnSubtitles) "0" else "1",
            "directStreamAudio" to if (convertAudio) "0" else "1",  // 0: Plex converts the audio to something the box decodes
            "subtitles" to if (burnSubtitles) "burn" else "none",
            "offset" to "$offsetSeconds", "session" to session, "X-Plex-Session-Identifier" to session,
            "X-Plex-Client-Identifier" to clientId, "X-Plex-Product" to "ShelfLife", "X-Plex-Platform" to "Android",
        ))
    }

    /** Library items → shelf films: each file is a version, and items sharing a Plex guid become one film. */
    private fun group(items: JSONArray, limit: Int): List<Movie> {
        // Every file of every item becomes a version; items of the same film (same Plex guid, e.g.
        // separate editions) are combined onto one case.
        val groups = LinkedHashMap<String, MutableList<Pair<JSONObject, Movie.Version>>>()
        for (v in (0 until items.length()).map { items.getJSONObject(it) }) {
            val media = v.optJSONArray("Media") ?: JSONArray()
            val key = v.optString("guid").ifEmpty { v.optString("title") + "|" + v.optInt("year") }
            for (i in 0 until maxOf(1, media.length())) {
                val m = media.optJSONObject(i)
                val file = m?.optJSONArray("Part")?.optJSONObject(0)?.optString("file").orEmpty()
                val edition = v.optString("editionTitle").ifEmpty { null } ?: editionFromFile(file)
                val res = m?.optString("videoResolution")?.ifEmpty { null }
                groups.getOrPut(key) { mutableListOf() } += v to Movie.Version(v.getString("ratingKey"), i, edition ?: "", res, edition, videoDetails(m, file), audioDetails(m, file), file.substringAfterLast('/').substringAfterLast('\\'), m?.optLong("duration") ?: 0L)
            }
        }
        return groups.values.asSequence().mapNotNull { entries ->
            val v = entries.first().first
            // Drop sample clips; files much shorter than the longest are parts (episodes, acts).
            val longest = entries.maxOf { it.second.durationMs }
            val kept = entries.map { it.second }.filter { ver ->
                !Regex("""(?i)(^|[^a-z])sample([^a-z]|$)""").containsMatchIn(ver.file) && (longest == 0L || ver.durationMs == 0L || ver.durationMs >= longest * 0.15)
            }.ifEmpty { entries.map { it.second } }
                .map { it.copy(part = longest > 0 && it.durationMs in 1 until (longest * 0.7).toLong()) }
                .let { list ->
                    // Same resolution, sound and picture, no edition: 3+ such files are episodes/parts;
                    // a pair is just a duplicate copy, so keep one.
                    val spec = { x: Movie.Version -> listOf(x.resolution, x.audio, x.video, x.edition) }
                    list.groupBy(spec).values.flatMap { same ->
                        when {
                            same.size >= 3 && same.all { it.edition == null } -> same.map { it.copy(part = true) }
                            same.size == 2 && same.none { it.part || it.edition != null } -> same.take(1)
                            else -> same
                        }
                    }
                }
            if (looksLikeSeries(kept)) return@mapNotNull null  // TV shows filed as movies (e.g. Future Boy Conan) stay off the shelf
            val versions = labelled(kept.sortedWith(compareBy<Movie.Version> { it.part }.thenByDescending { resolutionRank(it.resolution) }.thenBy { it.file }), v.optString("title"))
            val id = v.getString("ratingKey")
            Movie(
                id = id, title = v.optString("title"), year = v.optInt("year").takeIf { it > 0 },
                summary = v.optString("summary").ifEmpty { "No synopsis from Plex." },
                minutes = v.optLong("duration").takeIf { it > 0 }?.let { (it / 60_000).toInt() },
                rating = v.optString("contentRating").ifEmpty { null },
                color = Movie.colorFor(id),
                thumb = v.optString("thumb").ifEmpty { null },
                logo = entries.firstNotNullOfOrNull { clearLogo(it.first) },
                resolution = versions.first().resolution,
                versions = versions,
                watched = entries.any { it.first.optInt("viewCount") > 0 },
                studio = v.optString("studio").ifEmpty { null },
            )
        }.take(limit).toList()
    }

    private val episodeName = Regex("""(?i)(\bS\d{1,2}\s?E\d{1,3}\b|\b\d{1,2}x\d{2}\b|\bEp(isode)?\.?\s?\d{1,3}\b)""")

    /** Episode-style file names, or eight or more pieces: a series, not a film. */
    private fun looksLikeSeries(versions: List<Movie.Version>): Boolean =
        versions.count { it.part } >= 8 || versions.count { episodeName.containsMatchIn(it.file) } >= 2

    /** One-off survey of the whole movie library: how versions combine, editions, HDR and audio. */
    fun libraryReport(server: Server): List<String> {
        val sections = JSONObject(text(url(server, "/library/sections"))).getJSONObject("MediaContainer").optJSONArray("Directory") ?: JSONArray()
        val key = (0 until sections.length()).map { sections.getJSONObject(it) }.firstOrNull { it.optString("type") == "movie" }?.getString("key") ?: return listOf("no movie library")
        val items = JSONObject(text(url(server, "/library/sections/$key/all"), timeoutMs = 60_000)).getJSONObject("MediaContainer").optJSONArray("Metadata") ?: JSONArray()
        val films = group(items, Int.MAX_VALUE)
        val series = (0 until items.length()).map { items.getJSONObject(it) }.filter { group(JSONArray().put(it), 1).isEmpty() }.map { it.optString("title") }
        val multi = films.filter { it.versions.size > 1 }
        val all = films.flatMap { it.versions }
        val out = mutableListOf("${items.length()} Plex items -> ${films.size} cases; ${multi.size} cases hold 2+ versions (${all.size} files); left off as series: ${series.joinToString().ifEmpty { "none" }}")
        out += "editions: " + all.mapNotNull { it.edition }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none" }
        out += "video: " + all.groupingBy { it.video ?: "SDR/unknown" }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }
        out += "audio: " + all.groupingBy { it.audio ?: "unknown" }.eachCount().entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key} ${it.value}" }
        out += "resolution: " + all.groupingBy { it.resolution ?: "?" }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }
        multi.forEach { f -> out += "  ${f.title} (${f.year}): " + f.versions.joinToString(" | ") { "${it.label} [${it.resolution}${it.video?.let { v -> ", $v" } ?: ""}${it.audio?.let { a -> ", $a" } ?: ""}]" } }
        return out
    }

    private fun resolutionRank(res: String?) = when {
        res == null -> 0
        res.startsWith("4k") || res.startsWith("2160") -> 4
        res.startsWith("1080") -> 3
        res.startsWith("720") -> 2
        else -> 1
    }

    /**
     * Menu names for versions, most telling first: the edition; else the resolution; if two share
     * that, add the sound format; if still tied, a cleaned-up file name; numbers only as a last resort.
     */
    private fun labelled(versions: List<Movie.Version>, title: String = ""): List<Movie.Version> {
        fun res(r: String?) = when (resolutionRank(r)) { 4 -> "4K"; 3 -> "1080p"; 2 -> "720p"; 1 -> "SD"; else -> "Standard" }
        val untitled = versions.count { it.edition == null }
        var named = versions.map { v ->
            v.copy(label = when {
                v.edition != null -> v.edition
                untitled == 1 && versions.size > 1 -> "Theatrical"
                else -> res(v.resolution)
            })
        }
        fun ties(list: List<Movie.Version>, v: Movie.Version) = list.count { it.label == v.label } > 1
        fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
        // Pieces of one film: a name from the file (S01E03, Act 2…) or "Part n".
        val parts = named.filter { it.part }
        named = named.map { v -> if (!v.part) v else (cleanFileName(v.file)?.takeIf { norm(it) != norm(title) } ?: "Part ${parts.indexOf(v) + 1}").let { v.copy(label = it) } }
        // Tied copies: name them by picture format if that differs (Dolby Vision vs SDR), else by sound.
        named = named.map { v ->
            if (v.part || !ties(named, v)) v else {
                val group = named.filter { it.label == v.label }
                if (group.map { it.video }.distinct().size > 1) v.copy(label = "${v.label} · ${v.video ?: "SDR"}")
                else if (v.audio != null) v.copy(label = "${v.label} · ${v.audio}") else v
            }
        }
        named = named.map { v -> if (!v.part && ties(named, v)) cleanFileName(v.file)?.takeIf { norm(it) != norm(title) && !norm(title).startsWith(norm(it)) }?.let { v.copy(label = it) } ?: v else v }
        return named.mapIndexed { i, v -> if (ties(named, v)) v.copy(label = "${v.label} · ${named.take(i + 1).count { it.label == v.label }}") else v }
    }

    /** "Future.Boy.Conan.S01E03.1080p.BluRay.x264.mkv" → "S01E03"; otherwise the name without tech tags. */
    private fun cleanFileName(file: String): String? {
        val base = file.substringBeforeLast('.').replace('.', ' ').replace('_', ' ')
        Regex("""(?i)\b(S\d{1,2}E\d{1,3}|E\d{1,3}|Ep(isode)?\s*\d+|Act\s*\d+|Part\s*\d+|Pt\s*\d+|Disc\s*\d+|CD\s*\d+)\b""").find(base)?.let { return it.value.replaceFirstChar { c -> c.uppercase() } }
        val cleaned = base.replace(Regex("""(?i)\b(19|20)\d\d\b.*$"""), "").replace(Regex("""(?i)\b(2160p|1080p|720p|480p|4k|uhd|bluray|blu-ray|web-?dl|webrip|remux|x26[45]|h\.?26[45]|hevc|hdr\w*|dv|atmos|dts\S*|aac\S*|ac3|eac3|ddp?\S*)\b"""), "").trim()
        return cleaned.takeIf { it.length in 2..40 }
    }

    /** HDR formats, from Plex's media fields and the file name (Plex lists don't always carry them). */
    private fun videoDetails(m: JSONObject?, file: String): String? {
        val name = file.substringAfterLast('/').lowercase()
        val range = m?.optString("videoDynamicRange").orEmpty().lowercase()
        val tags = mutableListOf<String>()
        if ("dolby vision" in range || m?.optBoolean("DOVIPresent") == true || Regex("""(^|[^a-z])(dv|dovi|dolby[ .]?vision)([^a-z]|$)""").containsMatchIn(name)) tags += "Dolby Vision"
        if ("hdr10+" in range || "hdr10+" in name || "hdr10plus" in name) tags += "HDR10+"
        else if ("hdr" in range || Regex("""(^|[^a-z])hdr(10)?([^a-z+]|$)""").containsMatchIn(name)) tags += "HDR10"
        return tags.joinToString(" · ").ifEmpty { null }
    }

    /** The headline audio format, the way case backs print it. */
    private fun audioDetails(m: JSONObject?, file: String): String? {
        m ?: return null
        val codec = m.optString("audioCodec").lowercase()
        val profile = m.optString("audioProfile").lowercase()
        val name = file.substringAfterLast('/').lowercase()
        val channels = when (m.optInt("audioChannels")) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
        if ("atmos" in name || "atmos" in profile) return "Dolby Atmos"
        if ("dts:x" in name || "dtsx" in name) return "DTS:X"
        val format = when {
            codec == "truehd" -> "Dolby TrueHD"
            codec == "eac3" -> "Dolby Digital Plus"
            codec == "ac3" -> "Dolby Digital"
            codec.startsWith("dca") || codec.startsWith("dts") -> if ("ma" in profile || "ma" in codec || "dts-hd" in name) "DTS-HD MA" else "DTS"
            codec == "flac" -> "FLAC"
            codec == "opus" -> "Opus"
            codec == "aac" -> "AAC"
            codec.isNotEmpty() -> codec.uppercase()
            else -> return null
        }
        return listOfNotNull(format, channels).joinToString(" ")
    }

    /** Edition names from common file/folder naming ("Director's Cut", "{edition-Final Cut}", "Extended"…). */
    private fun editionFromFile(path: String): String? {
        if (path.isEmpty()) return null
        Regex("""\{edition-([^}]+)\}""", RegexOption.IGNORE_CASE).find(path)?.let { return it.groupValues[1].trim() }
        val name = path.substringAfterLast('/').substringAfterLast('\\').replace('.', ' ').replace('_', ' ')
        val known = listOf(
            "director'?s cut" to "Director's Cut", "directors cut" to "Director's Cut", "final cut" to "Final Cut",
            "extended (cut|edition)?" to "Extended", "ultimate (cut|edition)" to "Ultimate Edition", "unrated" to "Unrated",
            "theatrical( cut)?" to "Theatrical", "special edition" to "Special Edition", "anniversary (edition)?" to "Anniversary Edition",
            "criterion" to "Criterion", "remastered" to "Remastered", "imax" to "IMAX", "open matte" to "Open Matte",
            "black (and|&) chrome" to "Black & Chrome", "redux" to "Redux", "uncut" to "Uncut",
        )
        return known.firstOrNull { (pattern, _) -> Regex("""(^|[^a-z])$pattern([^a-z]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(name) }?.second
    }

    /** The film's title logo (transparent PNG), from the Image list newer Plex servers include. */
    private fun clearLogo(v: JSONObject): String? {
        val images = v.optJSONArray("Image") ?: return null
        return (0 until images.length()).map { images.getJSONObject(it) }.firstOrNull { it.optString("type") == "clearLogo" }?.optString("url")?.ifEmpty { null }
    }

    /** For servers whose library list leaves the logo out: ask for the one film's details. */
    fun logo(server: Server, ratingKey: String): String? = runCatching {
        clearLogo(JSONObject(text(url(server, "/library/metadata/$ratingKey"))).getJSONObject("MediaContainer").getJSONArray("Metadata").getJSONObject(0))
    }.getOrNull()

    /** Plex resizes artwork server-side, so the TV only downloads what it shows. */
    /** [png] keeps transparency (logos). */
    fun image(server: Server, thumb: String, width: Int, height: Int, png: Boolean = false): Bitmap? = runCatching {
        val q = mutableMapOf("width" to "$width", "height" to "$height", "minSize" to "1", "upscale" to "1", "url" to thumb)
        if (png) q["format"] = "png"
        val c = open(url(server, "/photo/:/transcode", q), timeoutMs = 10_000)
        try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
    }.getOrNull()
}
