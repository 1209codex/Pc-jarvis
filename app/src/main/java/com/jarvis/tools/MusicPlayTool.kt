package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

class MusicPlayTool(private val context: Context? = null) : Tool {
    override val name: String = "MUSIC_PLAY"
    override val description: String = "Plays music or specific tracks across installed music players (YMusic, YouTube Music, Spotify). Parameter: query (song title or artist name)."

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val query = params["query"]

        val ctx = context ?: return if (query.isNullOrBlank()) {
            ToolResult(false, "Music query missing")
        } else {
            ToolResult(true, "Playing $query")
        }

        return try {
            if (query.isNullOrBlank()) {
                // Generic play - try YMusic first, then default
                val ymusicIntent = ctx.packageManager.getLaunchIntentForPackage("com.kapp.youtube.final")
                if (ymusicIntent != null) {
                    ymusicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(ymusicIntent)
                    return ToolResult(true, "Playing music on YMusic")
                }
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                ToolResult(true, "Playing music")
            } else {
                // Try YMusic app directly if installed
                val ymusicIntent = ctx.packageManager.getLaunchIntentForPackage("com.kapp.youtube.final")
                if (ymusicIntent != null) {
                    ymusicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ymusicIntent.putExtra("query", query)
                    ymusicIntent.putExtra("search", query)
                    ctx.startActivity(ymusicIntent)
                    return ToolResult(true, "Playing $query on YMusic")
                }

                // Play specific track: Try standard MediaStore media search first for installed music players (Spotify, YT Music, local players)
                val mediaSearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
                    putExtra("query", query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (mediaSearchIntent.resolveActivity(ctx.packageManager) != null) {
                    ctx.startActivity(mediaSearchIntent)
                    ToolResult(true, "Searching and playing '$query'")
                } else {
                    // Fallback to YouTube Music / Web
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(webIntent)
                    ToolResult(true, "Playing '$query'")
                }
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed to play music: ${e.message}")
        }
    }
}
