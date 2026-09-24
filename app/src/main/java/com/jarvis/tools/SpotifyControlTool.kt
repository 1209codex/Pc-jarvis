package com.jarvis.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.media.MediaSessionManager

class SpotifyControlTool(
    private val context: Context,
    private val sessionManager: MediaSessionManager? = null
) : Tool {
    override val name: String = "SPOTIFY_PLAY"
    override val description: String =
        "Searches and plays music, artists, playlists, or albums directly on Spotify. Parameter: query (song title, artist, playlist name, or genre)."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "SPOTIFY_PLAY",
        description = "Deep links and plays music, playlists, and artists on Spotify.",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val query = params["query"]?.trim().orEmpty()
        val spotifyPkg = "com.spotify.music"

        return try {
            val isInstalled = context.packageManager.getLaunchIntentForPackage(spotifyPkg) != null

            if (query.isBlank()) {
                if (isInstalled) {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(spotifyPkg)!!
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    sessionManager?.recordTrackPlay(title = "Spotify App", playerApp = "spotify")
                    ToolResult.Success("Opening Spotify.", mapOf("launched" to true))
                } else {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    ToolResult.Success("Opening Spotify Web Player.", mapOf("launched" to "web"))
                }
            } else {
                sessionManager?.recordTrackPlay(title = query, playerApp = "spotify")

                if (isInstalled) {
                    // Try MediaStore search intent specifically targeted to Spotify first
                    val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        `package` = spotifyPkg
                        putExtra(SearchManager.QUERY, query)
                        putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
                        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    if (mediaIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(mediaIntent)
                        ToolResult.Success("Playing '$query' on Spotify.", mapOf("query" to query, "source" to "app"))
                    } else {
                        // Fallback to Spotify URI scheme
                        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}")).apply {
                            `package` = spotifyPkg
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(uriIntent)
                        ToolResult.Success("Searching '$query' in Spotify.", mapOf("query" to query, "source" to "uri"))
                    }
                } else {
                    // Fallback to Web Player
                    val webUrl = "https://open.spotify.com/search/${Uri.encode(query)}"
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    ToolResult.Success("Opening '$query' on Spotify Web.", mapOf("query" to query, "source" to "web"))
                }
            }
        } catch (e: Exception) {
            ToolResult.Failed("Failed to play on Spotify: ${e.message}")
        }
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        if (!result.success) {
            return VerificationResult.failure("Spotify dispatch failed: ${result.message}")
        }
        val source = result.data["source"] as? String
        return when {
            result.data["launched"] == true || source != null -> VerificationResult.success(
                "Spotify ${if (source != null) "$source " else ""}dispatch confirmed",
                mapOf("outcome" to "DISPATCHED", "playback_confirmed" to "false")
            )
            else -> VerificationResult.unknown("Spotify opened but source not reported", mapOf("outcome" to "UNPARSED"))
        }
    }
}
