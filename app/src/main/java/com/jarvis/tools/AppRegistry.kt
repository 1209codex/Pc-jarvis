package com.jarvis.tools

import android.content.Context
import android.content.Intent
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object AppRegistry {
    val ALLOWED_PACKAGES = mapOf(
        // WhatsApp
        "whatsapp" to "com.whatsapp",
        "whats app" to "com.whatsapp",
        "wa" to "com.whatsapp",
        "whatsapp business" to "com.whatsapp.w4b",
        "wa business" to "com.whatsapp.w4b",

        // YouTube
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "ytube" to "com.google.android.youtube",
        "ymusic" to "com.kapp.youtube.final",
        "y music" to "com.kapp.youtube.final",
        "youtube music" to "com.google.android.apps.youtube.music",

        // Camera
        "camera" to "com.sec.android.app.camera",
        "cam" to "com.sec.android.app.camera",

        // Samsung Notes & Notes
        "notes" to "com.samsung.android.app.notes",
        "samsung notes" to "com.samsung.android.app.notes",
        "samsung note" to "com.samsung.android.app.notes",
        "s notes" to "com.samsung.android.app.notes",
        "s-notes" to "com.samsung.android.app.notes",

        // ChatGPT & AI Assistants
        "chatgpt" to "com.openai.chatgpt",
        "chat gpt" to "com.openai.chatgpt",
        "openai" to "com.openai.chatgpt",
        "open ai" to "com.openai.chatgpt",
        "gemini" to "com.google.android.apps.bard",
        "google gemini" to "com.google.android.apps.bard",
        "bard" to "com.google.android.apps.bard",
        "claude" to "com.anthropic.claude",
        "deepseek" to "com.deepseek.chatgpt",
        "perplexity" to "ai.perplexity.app.android",

        // Video & Media Players (MX Player Pro / Free)
        "mx player" to "com.mxtech.videoplayer.pro",
        "mx player pro" to "com.mxtech.videoplayer.pro",
        "mxplayer" to "com.mxtech.videoplayer.pro",
        "mxplayer pro" to "com.mxtech.videoplayer.pro",
        "mx player ad" to "com.mxtech.videoplayer.ad",
        "mx" to "com.mxtech.videoplayer.pro",
        "netflix" to "com.netflix.mediaclient",
        "music" to "com.sec.android.app.music",
        "samsung music" to "com.sec.android.app.music",

        // Google & Chrome
        "google" to "com.google.android.googlequicksearchbox",
        "google search" to "com.google.android.googlequicksearchbox",
        "google app" to "com.google.android.googlequicksearchbox",
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "chrome browser" to "com.android.chrome",
        "chrome beta" to "com.chrome.beta",
        "browser" to "com.android.chrome",

        // Gaming Hub / Game Launcher
        "gaming hub" to "com.samsung.android.game.gamehome",
        "game launcher" to "com.samsung.android.game.gamehome",
        "game hub" to "com.samsung.android.game.gamehome",
        "samsung game launcher" to "com.samsung.android.game.gamehome",
        "samsung gaming hub" to "com.samsung.android.game.gamehome",

        // Social & Communication
        "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "gmail" to "com.google.android.gm",

        // Creativity, Games & Productivity
        "picsart" to "com.picsart.studio",
        "oneroom" to "com.community.oneroom",
        "pubg" to "com.pubg.imobile",
        "genshin" to "com.levelinfinite.gst",
        "phonepe" to "com.phonepe.app",
        "kotak" to "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge",
        "keep" to "com.google.android.keep",
        "docs" to "com.google.android.apps.docs",
        "files" to "com.google.android.apps.nbu.files",
        "photos" to "com.google.android.apps.photos",
        "maps" to "com.google.android.apps.maps",
        "calculator" to "com.sec.android.app.popupcalculator",
        "voice recorder" to "com.sec.android.app.voicenote",
        "digilocker" to "com.digilocker.android",
        "termux" to "com.termux",
        "github" to "com.github.android",
        "zarchiver" to "ru.zdevs.zarchiver",
        "kde connect" to "org.kde.kdeconnect_tp",
        "calendar" to "com.samsung.android.calendar",
        "clock" to "com.sec.android.app.clockpackage",
        "my files" to "com.sec.android.app.myfiles",
        "fm" to "com.sec.android.app.fm",
        "reminder" to "com.samsung.android.app.reminder",
        "bixby" to "com.samsung.android.bixby.agent",
        "gallery" to "com.sec.android.gallery3d",
        "settings" to "com.android.settings",
        "play store" to "com.android.vending",
        "amazon" to "in.amazon.mShop.android.shopping"
    )

    private val dynamicPackageCache = ConcurrentHashMap<String, String>()

    /**
     * Resolves an app name or key to an installed package name.
     * Checks the static ALLOWED_PACKAGES first, then cache, and falls back to
     * AppScanner / querying installed launcher activities on the device.
     */
    fun resolvePackage(context: Context?, appNameOrKey: String): String? {
        val key = appNameOrKey.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return null

        // 1. Static allowlist
        ALLOWED_PACKAGES[key]?.let { return it }

        // 2. In-memory dynamic package cache
        dynamicPackageCache[key]?.let { return it }

        val ctx = context ?: return null
        val pm = ctx.packageManager

        // 3. If key itself is already a valid package name installed on device
        if (key.contains(".")) {
            try {
                pm.getPackageInfo(key, 0)
                dynamicPackageCache[key] = key
                return key
            } catch (_: Exception) {}
        }

        // 4. Query via AppScanner if available
        val scanned = AppScanner.searchApps(ctx, key)
        if (scanned.isNotEmpty()) {
            val bestMatch = scanned.firstOrNull { it.label.lowercase(Locale.ROOT) == key }
                ?: scanned.first()
            dynamicPackageCache[key] = bestMatch.packageName
            return bestMatch.packageName
        }

        // 5. Fallback: Direct launcher activity resolution
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val list = pm.queryIntentActivities(mainIntent, 0)

            // Exact label match
            val exact = list.firstOrNull {
                it.loadLabel(pm).toString().lowercase(Locale.ROOT) == key
            }
            if (exact != null) {
                val pkg = exact.activityInfo.packageName
                dynamicPackageCache[key] = pkg
                return pkg
            }

            // Contains match on label
            val containsMatch = list.firstOrNull {
                val label = it.loadLabel(pm).toString().lowercase(Locale.ROOT)
                label.contains(key) || key.contains(label)
            }
            if (containsMatch != null) {
                val pkg = containsMatch.activityInfo.packageName
                dynamicPackageCache[key] = pkg
                return pkg
            }

            // Package name contains key
            val pkgMatch = list.firstOrNull {
                it.activityInfo.packageName.lowercase(Locale.ROOT).contains(key)
            }
            if (pkgMatch != null) {
                val pkg = pkgMatch.activityInfo.packageName
                dynamicPackageCache[key] = pkg
                return pkg
            }

            null
        } catch (_: Exception) {
            null
        }
    }
}
