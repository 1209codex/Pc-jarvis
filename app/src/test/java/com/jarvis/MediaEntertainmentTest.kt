package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.MediaSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.media.MediaSessionManager
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private class MediaStubTool(
    override val name: String,
    override val description: String = ""
) : Tool {
    override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult(true, "ok")
}

class MediaEntertainmentTest {

    @Test
    fun testToolRegistryResolvesMediaAliases() {
        val registry = ToolRegistry()
        registry.register(MediaStubTool("MEDIA_CONTROL"))
        registry.register(MediaStubTool("SPOTIFY_PLAY"))
        registry.register(MediaStubTool("MUSIC_PLAY"))
        registry.register(MediaStubTool("MEDIA_STOP"))

        assertEquals("MEDIA_CONTROL", registry.resolveCanonicalToolName("NEXT_TRACK"))
        assertEquals("MEDIA_CONTROL", registry.resolveCanonicalToolName("SKIP_SONG"))
        assertEquals("MEDIA_CONTROL", registry.resolveCanonicalToolName("PREVIOUS_TRACK"))
        assertEquals("MEDIA_CONTROL", registry.resolveCanonicalToolName("RESUME_MUSIC"))
        assertEquals("MEDIA_CONTROL", registry.resolveCanonicalToolName("MEDIA_RESUME"))
        assertEquals("MEDIA_CONTROL", registry.resolveCanonicalToolName("SEEK_FORWARD"))
        assertEquals("MEDIA_CONTROL", registry.resolveCanonicalToolName("SEEK_BACKWARD"))

        assertEquals("SPOTIFY_PLAY", registry.resolveCanonicalToolName("SPOTIFY"))
        assertEquals("SPOTIFY_PLAY", registry.resolveCanonicalToolName("PLAY_SPOTIFY"))
    }

    @Test
    fun testMediaSessionManagerTrackingAndPresets() {
        val manager = MediaSessionManager()

        // Initial state
        assertNull(manager.getCurrentTrack())
        assertFalse(manager.isCurrentlyPlaying())
        assertTrue(manager.getHistory().isEmpty())

        // Record a track
        manager.recordTrackPlay("Starboy", "The Weeknd", "spotify")
        assertEquals("Starboy", manager.getCurrentTrack()?.title)
        assertEquals("The Weeknd", manager.getCurrentTrack()?.artist)
        assertEquals("spotify", manager.getCurrentTrack()?.playerApp)
        assertTrue(manager.isCurrentlyPlaying())
        assertEquals(1, manager.getHistory().size)

        // Record second track
        manager.recordTrackPlay("Blinding Lights", "The Weeknd", "spotify")
        assertEquals("Blinding Lights", manager.getCurrentTrack()?.title)
        assertEquals(2, manager.getHistory().size)

        // State transitions
        manager.setPlaybackState(false)
        assertFalse(manager.isCurrentlyPlaying())

        // Presets verification
        assertEquals(4, manager.presets.size)
        assertTrue(manager.presets.any { it.id == "preset_lofi" })
        assertTrue(manager.presets.any { it.id == "preset_workout" })
        assertTrue(manager.presets.any { it.id == "preset_synthwave" })
        assertTrue(manager.presets.any { it.id == "preset_bollywood" })

        // Clear
        manager.clear()
        assertNull(manager.getCurrentTrack())
        assertFalse(manager.isCurrentlyPlaying())
        assertTrue(manager.getHistory().isEmpty())
    }

    @Test
    fun testMediaSkillHandlesBilingualPlaybackControls() = runBlocking {
        val skill = MediaSkill()
        val context = SkillContext(goal = "", workingMemory = AgentWorkingMemory(goal = ""))

        // Next song (English & Hindi)
        assertTrue(skill.canHandle("next song", context))
        val nextEng = skill.execute("next song", context)
        assertEquals("MEDIA_CONTROL", nextEng.proposedAction?.type)
        assertEquals("next", nextEng.proposedAction?.params?.get("action"))

        assertTrue(skill.canHandle("agla gaana bajao", context))
        val nextHin = skill.execute("agla gaana bajao", context)
        assertEquals("MEDIA_CONTROL", nextHin.proposedAction?.type)
        assertEquals("next", nextHin.proposedAction?.params?.get("action"))

        // Previous song (English & Hindi)
        assertTrue(skill.canHandle("previous track", context))
        val prevEng = skill.execute("previous track", context)
        assertEquals("MEDIA_CONTROL", prevEng.proposedAction?.type)
        assertEquals("previous", prevEng.proposedAction?.params?.get("action"))

        assertTrue(skill.canHandle("pichhla gaana", context))
        val prevHin = skill.execute("pichhla gaana", context)
        assertEquals("MEDIA_CONTROL", prevHin.proposedAction?.type)
        assertEquals("previous", prevHin.proposedAction?.params?.get("action"))

        // Resume & Pause
        assertTrue(skill.canHandle("resume music", context))
        val resumeRes = skill.execute("resume music", context)
        assertEquals("MEDIA_CONTROL", resumeRes.proposedAction?.type)
        assertEquals("play", resumeRes.proposedAction?.params?.get("action"))

        assertTrue(skill.canHandle("pause music", context))
        val pauseRes = skill.execute("pause music", context)
        assertEquals("MEDIA_CONTROL", pauseRes.proposedAction?.type)
        assertEquals("pause", pauseRes.proposedAction?.params?.get("action"))
    }

    @Test
    fun testMediaSkillHandlesSpotifyAndLyricsCommands() = runBlocking {
        val skill = MediaSkill()
        val context = SkillContext(goal = "", workingMemory = AgentWorkingMemory(goal = ""))

        // Spotify play
        assertTrue(skill.canHandle("play shape of you on spotify", context))
        val spotifyRes = skill.execute("play shape of you on spotify", context)
        assertEquals("SPOTIFY_PLAY", spotifyRes.proposedAction?.type)
        assertTrue(spotifyRes.proposedAction?.params?.get("query")?.contains("shape of you") == true)

        // Spotify Hindi
        assertTrue(skill.canHandle("spotify pe arijit singh ke gaane bajao", context))
        val spotifyHin = skill.execute("spotify pe arijit singh ke gaane bajao", context)
        assertEquals("SPOTIFY_PLAY", spotifyHin.proposedAction?.type)
        assertTrue(spotifyHin.proposedAction?.params?.get("query")?.contains("arijit singh") == true)

        // Lyrics
        assertTrue(skill.canHandle("show lyrics for bohemian rhapsody", context))
        val lyricsRes = skill.execute("show lyrics for bohemian rhapsody", context)
        assertEquals("WEB_SEARCH", lyricsRes.proposedAction?.type)
        assertEquals("bohemian rhapsody lyrics", lyricsRes.proposedAction?.params?.get("query"))

        assertTrue(skill.canHandle("is gaane ke bol dikhao", context))
        val lyricsHin = skill.execute("is gaane ke bol dikhao", context)
        assertEquals("WEB_SEARCH", lyricsHin.proposedAction?.type)
    }

    @Test
    fun testIntentResolverFastPathMediaCommands() {
        // Media Next
        val nextIntent = IntentResolver.resolve("next song")
        assertNotNull(nextIntent)
        assertEquals(AssistantIntent.MEDIA_NEXT, nextIntent?.intent)
        assertEquals("MEDIA_CONTROL", nextIntent?.directPlan?.actions?.firstOrNull()?.type)
        assertEquals("next", nextIntent?.directPlan?.actions?.firstOrNull()?.params?.get("action"))

        val nextHinIntent = IntentResolver.resolve("agla gaana")
        assertNotNull(nextHinIntent)
        assertEquals(AssistantIntent.MEDIA_NEXT, nextHinIntent?.intent)

        // Media Previous
        val prevIntent = IntentResolver.resolve("previous track")
        assertNotNull(prevIntent)
        assertEquals(AssistantIntent.MEDIA_PREVIOUS, prevIntent?.intent)
        assertEquals("MEDIA_CONTROL", prevIntent?.directPlan?.actions?.firstOrNull()?.type)
        assertEquals("previous", prevIntent?.directPlan?.actions?.firstOrNull()?.params?.get("action"))

        // Media Resume
        val resumeIntent = IntentResolver.resolve("resume music")
        assertNotNull(resumeIntent)
        assertEquals(AssistantIntent.MEDIA_RESUME, resumeIntent?.intent)
        assertEquals("MEDIA_CONTROL", resumeIntent?.directPlan?.actions?.firstOrNull()?.type)
        assertEquals("play", resumeIntent?.directPlan?.actions?.firstOrNull()?.params?.get("action"))

        // Spotify Play
        val spotifyIntent = IntentResolver.resolve("play coldplay on spotify")
        assertNotNull(spotifyIntent)
        assertEquals(AssistantIntent.SPOTIFY_PLAY, spotifyIntent?.intent)
        assertEquals("SPOTIFY_PLAY", spotifyIntent?.directPlan?.actions?.firstOrNull()?.type)
        assertEquals("coldplay", spotifyIntent?.directPlan?.actions?.firstOrNull()?.params?.get("query"))

        // Lyrics
        val lyricsIntent = IntentResolver.resolve("show lyrics for hotel california")
        assertNotNull(lyricsIntent)
        assertEquals(AssistantIntent.SEARCH_WEB, lyricsIntent?.intent)
        assertEquals("SEARCH_WEB", lyricsIntent?.directPlan?.actions?.firstOrNull()?.type)
        assertEquals("hotel california lyrics", lyricsIntent?.directPlan?.actions?.firstOrNull()?.params?.get("query"))
    }
}
