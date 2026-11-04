package com.shelflife.tv

/** A film on the shelf. Plex titles replace the samples once signed in. */
data class Movie(
    val id: String,
    val title: String,
    val year: Int?,
    val summary: String,
    val minutes: Int?,
    val rating: String?,
    val color: Int,  // spine colour until Plex poster colours arrive
    val thumb: String? = null,  // Plex poster path
    val logo: String? = null,   // Plex clear logo path, when the server has one
    val resolution: String? = null,  // Plex videoResolution: "4k", "1080", "720", "sd"…
    val versions: List<Version> = emptyList(),  // 2+ when the library holds several cuts/files of this film
    val watched: Boolean = false,  // Plex has it as played: the case gets a "Previously Viewed" sticker
    val studio: String? = null,    // Plex's studio, e.g. "Walt Disney Pictures": picks the feature-presentation bumper
) {
    /** One playable copy of a film: a Plex item (ratingKey) and which of its files (mediaIndex). */
    data class Version(
        val ratingKey: String, val mediaIndex: Int, val label: String, val resolution: String?, val edition: String?,
        val video: String? = null,  // e.g. "Dolby Vision · HDR10"
        val audio: String? = null,  // e.g. "Dolby Atmos" or "DTS-HD MA 7.1"
        val file: String = "",      // file name, the last resort for telling versions apart
        val durationMs: Long = 0,
        val part: Boolean = false,  // one piece (episode/act) of the film, not a whole copy
    )

    /** The edition name when the shelf holds just one version of the film (e.g. "Director's Cut"). */
    val edition: String? get() = versions.singleOrNull()?.edition

    companion object {
        private val palette = intArrayOf(0xFFB31F2E.toInt(), 0xFF1A42A6.toInt(), 0xFFE0750F.toInt(), 0xFF0F7A4F.toInt(), 0xFF612E8F.toInt(), 0xFF304052.toInt())
        fun colorFor(id: String) = palette[Math.floorMod(id.hashCode(), palette.size)]

        val samples = listOf(
            Movie("demo-1", "Arrival", 2016, "A linguist is recruited to communicate with visitors whose arrival changes humanity's idea of time.", 116, "PG-13", 0xFF1A42A6.toInt(), resolution = "4k"),
            Movie("demo-2", "Spirited Away", 2001, "A young girl enters a world of spirits and must find her way back to her family.", 125, "PG", 0xFFB31F2E.toInt(), resolution = "1080"),
            Movie("demo-3", "The Grand Budapest Hotel", 2014, "A legendary concierge and his lobby boy are swept into an unlikely caper.", 99, "R", 0xFF612E8F.toInt(), resolution = "sd"),
            Movie("demo-4", "Moon", 2009, "Near the end of a lunar contract, an isolated worker confronts a disturbing truth.", 97, "R", 0xFF0F7A4F.toInt(), resolution = "720"),
            Movie("demo-6", "Eternal Sunshine of the Spotless Mind", 2004, "Joel Barish is stunned to discover that his girlfriend Clementine has had her memories of their tumultuous relationship erased by a small New York firm. Out of desperation, he contacts the inventor of the process, Dr. Howard Mierzwiak, to have Clementine removed from his own memory. But as Joel's memories progressively disappear, he begins to rediscover their earlier passion. From deep within the recesses of his brain, Joel attempts to escape the procedure, hiding Clementine in memories where she doesn't belong. As the technicians chase him through his own mind, he relives their first meeting on a frozen beach, their quiet nights, and their bitter arguments, and comes to wonder whether forgetting her is what he really wants after all.", 108, "R", 0xFF2E6F9E.toInt(), resolution = "4k", versions = listOf(
                Version("demo-6", 0, "Theatrical", "4k", null, "Dolby Vision · HDR10", "Dolby Atmos"),
                Version("demo-6", 1, "Extended", "1080", "Extended", null, "DTS-HD MA 5.1"))),
            Movie("demo-5", "The Fall", 2006, "A stuntman tells an epic story to a young patient in a Los Angeles hospital.", 117, "R", 0xFFE0750F.toInt()),
        )
    }

    /** For the resume file: everything needed to put this film back on the shelf without Plex's list. */
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id); put("title", title); year?.let { put("year", it) }; put("summary", summary); minutes?.let { put("minutes", it) }
        rating?.let { put("rating", it) }; put("color", color); thumb?.let { put("thumb", it) }; logo?.let { put("logo", it) }
        resolution?.let { put("resolution", it) }
        put("watched", watched); studio?.let { put("studio", it) }
        put("versions", org.json.JSONArray().apply {
            versions.forEach { v ->
                put(org.json.JSONObject().apply {
                    put("ratingKey", v.ratingKey); put("mediaIndex", v.mediaIndex); put("label", v.label); v.resolution?.let { put("resolution", it) }
                    v.edition?.let { put("edition", it) }; v.video?.let { put("video", it) }; v.audio?.let { put("audio", it) }
                    put("file", v.file); put("durationMs", v.durationMs); put("part", v.part)
                })
            }
        })
    }
}

fun movieFromJson(o: org.json.JSONObject): Movie {
    fun str(k: String) = if (o.has(k)) o.getString(k) else null
    fun int(k: String) = if (o.has(k)) o.getInt(k) else null
    val vs = o.optJSONArray("versions")
    return Movie(o.getString("id"), o.getString("title"), int("year"), o.optString("summary"), int("minutes"), str("rating"), o.getInt("color"),
        str("thumb"), str("logo"), str("resolution"),
        (0 until (vs?.length() ?: 0)).map { i ->
            val v = vs!!.getJSONObject(i)
            fun vs(k: String) = if (v.has(k)) v.getString(k) else null
            Movie.Version(v.getString("ratingKey"), v.getInt("mediaIndex"), v.getString("label"), vs("resolution"), vs("edition"), vs("video"), vs("audio"),
                v.optString("file"), v.optLong("durationMs"), v.optBoolean("part"))
        }, o.optBoolean("watched"), str("studio"))
}
