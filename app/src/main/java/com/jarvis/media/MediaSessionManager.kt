package com.jarvis.media

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager as AndroidMediaSessionManager
import android.media.session.PlaybackState

data class TrackInfo(
    val title: String,
    val artist: String = "",
    val playerApp: String = "default",
    val isPlaying: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

data class MediaPreset(
    val id: String,
    val title: String,
    val category: String,
    val query: String
)

/**
 * Manages active media sessions, track playback state, and real-time Android
 * MediaSession inspection across background apps (YouTube, Spotify, Music players).
 */
class MediaSessionManager(
    private val context: Context? = null
) {
    private var currentTrack: TrackInfo? = null
    private val history = mutableListOf<TrackInfo>()
    private var isPlaying: Boolean = false

    val presets = listOf(
        MediaPreset("preset_lofi", "Lofi Chill Study Beats", "Chill", "lofi hip hop radio chill study beats"),
        MediaPreset("preset_workout", "High Energy Workout Pump", "Workout", "high energy gym workout motivation music"),
        MediaPreset("preset_synthwave", "Late Night Synthwave & Cyberpunk", "Night", "synthwave retro electro cyberpunk music"),
        MediaPreset("preset_bollywood", "Bollywood Melodies & Hits", "Bollywood", "top trending hindi bollywood songs")
    )

    @Synchronized
    fun recordTrackPlay(title: String, artist: String = "", playerApp: String = "default") {
        val info = TrackInfo(title = title.trim(), artist = artist.trim(), playerApp = playerApp, isPlaying = true)
        currentTrack = info
        isPlaying = true
        history.add(0, info)
        if (history.size > 25) {
            history.removeAt(history.lastIndex)
        }
    }

    @Synchronized
    fun setPlaybackState(playing: Boolean) {
        isPlaying = playing
        currentTrack = currentTrack?.copy(isPlaying = playing)
    }

    @Synchronized
    fun getCurrentTrack(): TrackInfo? {
        // Query active OS media sessions if available
        if (context != null) {
            try {
                val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? AndroidMediaSessionManager
                val activeSessions = sm?.getActiveSessions(null).orEmpty()
                val playingSession = activeSessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: activeSessions.firstOrNull()

                if (playingSession != null) {
                    val metadata = playingSession.metadata
                    val title = metadata?.description?.title?.toString()
                    val artist = metadata?.description?.subtitle?.toString()
                    val isSessionPlaying = playingSession.playbackState?.state == PlaybackState.STATE_PLAYING

                    if (!title.isNullOrBlank()) {
                        val observed = TrackInfo(
                            title = title,
                            artist = artist.orEmpty(),
                            playerApp = playingSession.packageName,
                            isPlaying = isSessionPlaying
                        )
                        currentTrack = observed
                        isPlaying = isSessionPlaying
                        return observed
                    }
                }
            } catch (_: Exception) {}
        }
        return currentTrack
    }

    @Synchronized
    fun isCurrentlyPlaying(): Boolean {
        if (context != null) {
            try {
                val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? AndroidMediaSessionManager
                val activeSessions = sm?.getActiveSessions(null).orEmpty()
                val anyPlaying = activeSessions.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                if (anyPlaying) return true
            } catch (_: Exception) {}
        }
        return isPlaying
    }

    @Synchronized
    fun getHistory(): List<TrackInfo> = history.toList()

    @Synchronized
    fun clear() {
        currentTrack = null
        history.clear()
        isPlaying = false
    }
}
