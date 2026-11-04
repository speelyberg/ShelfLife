package com.shelflife.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import java.util.Locale

/**
 * The film on the TV: Media3 on Android's own video surface (hardware decoding, HDR, embedded
 * subtitles). Plays the original file; if the box can't decode it, switches to a Plex transcode.
 */
@OptIn(UnstableApi::class)
class DiscPlayer(context: Context, view: PlayerView, private val events: Events) {
    interface Events {
        fun onEnded(extra: Boolean)
        fun onFailed(message: String)
        fun onTracksChanged()
        fun onOsd(text: String?, sticky: Boolean)
    }

    private val player = ExoPlayer.Builder(context).build()
    var converted = false; private set  // playing a Plex transcode rather than the original file
    private val main = Handler(Looper.getMainLooper())
    private var fallback: ((Long) -> String)? = null
    var extra = false; private set
    private var retried = false
    var scanRate = 0; private set

    init {
        view.player = player
        view.useController = false
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // A decoder that trips its own runtime check usually recovers on a fresh prepare.
                if (error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK && !retried) {
                    retried = true
                    player.prepare()
                    return
                }
                val retry = fallback
                if (retry != null) {
                    fallback = null
                    load(retry(player.currentPosition / 1000), hls = true, startMs = 0, play = player.playWhenReady, extra = extra, fallback = null)
                    converted = true
                } else events.onFailed(error.errorCodeName.lowercase().replace('_', ' '))
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { endScan(); events.onEnded(extra) } }
            override fun onTracksChanged(tracks: Tracks) = events.onTracksChanged()
        })
    }

    /** [fallback] builds a transcode URL starting at the given second, used if direct play fails. */
    fun load(url: String, hls: Boolean, startMs: Long, play: Boolean, extra: Boolean, fallback: ((Long) -> String)?) {
        endScan()
        this.extra = extra
        this.fallback = fallback
        converted = hls
        val item = MediaItem.Builder().setUri(url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        player.stop()  // release the decoders first; reusing them mid-stream can crash some hardware codecs
        retried = false
        player.setMediaItem(item, startMs)
        player.playWhenReady = play
        player.prepare()
    }

    val loaded get() = player.mediaItemCount > 0
    val position get() = player.currentPosition
    /** Length in ms, 0 while unknown. */
    val duration get() = player.duration.takeIf { it > 0 } ?: 0L
    fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))
    val playing get() = player.playWhenReady && scanRate == 0

    fun play() { endScan(); player.play() }
    fun pause() { endScan(); player.pause() }
    fun toggle() {
        if (scanRate != 0 || !player.playWhenReady) { play(); events.onOsd("▶  Play", false) }
        else { pause(); events.onOsd("❚❚  Pause", true) }
    }

    /** Each press doubles the speed (×2 … ×32) like a DVD remote; Play returns to normal. */
    fun scan(forward: Boolean) {
        val sameWay = if (forward) scanRate > 0 else scanRate < 0
        val speed = if (sameWay) minOf(kotlin.math.abs(scanRate) * 2, 32) else 2
        main.removeCallbacks(step)
        scanRate = if (forward) speed else -speed
        player.pause()
        main.post(step)
        events.onOsd(if (forward) "▶▶  ×$speed" else "◀◀  ×$speed", true)
    }
    private val step = object : Runnable {
        override fun run() {
            if (scanRate == 0) return
            val next = player.currentPosition + scanRate * 400L
            if (next <= 0) { player.seekTo(0); play(); events.onOsd("▶  Play", false); return }
            player.seekTo(next)
            main.postDelayed(this, 400)
        }
    }
    private fun endScan() { scanRate = 0; main.removeCallbacks(step) }

    // --- Audio and subtitle tracks from the file itself ---

    private fun groups(type: Int) = player.currentTracks.groups.filter { it.type == type && it.isSupported }

    private fun describe(format: Format, index: Int): String {
        val language = format.language?.let { Locale.forLanguageTag(it).displayLanguage }?.takeIf { it.isNotBlank() && it != "und" }
        val channels = when (format.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
        return listOfNotNull(format.label ?: language ?: "Track ${index + 1}", channels).joinToString(" · ")
    }

    /** All of the file's tracks of a type, in file order (the same order Plex lists them), playable or not. */
    fun groups(type: Int, includeUnsupported: Boolean) = player.currentTracks.groups.filter { it.type == type && (includeUnsupported || it.isSupported) }

    /** Picks the file's track at [index] if this box can decode it; false means Plex has to convert it. */
    fun selectTrack(type: Int, index: Int): Boolean {
        val group = groups(type, includeUnsupported = true).getOrNull(index) ?: return false
        if (!group.isSupported) return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
        return true
    }
    fun subtitlesOff() { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }

    fun audioLabel(): String = groups(C.TRACK_TYPE_AUDIO).withIndex().firstOrNull { it.value.isSelected }?.let { describe(it.value.getTrackFormat(0), it.index) } ?: "Default"
    fun subtitleLabel(): String = groups(C.TRACK_TYPE_TEXT).withIndex().firstOrNull { it.value.isSelected }?.let { describe(it.value.getTrackFormat(0), it.index) } ?: "Off"

    fun cycleAudio() {
        val audio = groups(C.TRACK_TYPE_AUDIO)
        if (audio.size < 2) return
        val next = audio[(audio.indexOfFirst { it.isSelected } + 1).mod(audio.size)]
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(next.mediaTrackGroup, 0)).build()
    }

    /** Off → each subtitle track → Off. */
    fun cycleSubtitles() {
        val text = groups(C.TRACK_TYPE_TEXT)
        val current = text.indexOfFirst { it.isSelected }
        val builder = player.trackSelectionParameters.buildUpon()
        if (current + 1 >= text.size) builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(text[current + 1].mediaTrackGroup, 0))
        player.trackSelectionParameters = builder.build()
    }

    fun stop() { endScan(); player.stop(); player.clearMediaItems() }

    fun release() { endScan(); player.release() }
}
