package com.jarvis.overlay

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.app.PendingIntent
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import com.jarvis.R

/**
 * Android Quick Settings Notification Shade Tile for the JARVIS Floating Orb.
 * Allows users to toggle the always-on-top ambient Arc Reactor presence from anywhere in the OS.
 */
@RequiresApi(Build.VERSION_CODES.N)
class JarvisOverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        if (JarvisFloatingOverlayService.isRunning) {
            val stopIntent = Intent(this, JarvisFloatingOverlayService::class.java).apply {
                action = JarvisFloatingOverlayService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            val startIntent = Intent(this, JarvisFloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
        }

        // Slight delay to allow service lifecycle to flip
        qsTile?.let { tile ->
            tile.state = if (JarvisFloatingOverlayService.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val active = JarvisFloatingOverlayService.isRunning

        tile.label = "JARVIS Orb"
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.contentDescription = if (active) "JARVIS Floating Orb is Active" else "JARVIS Floating Orb is Inactive"
        tile.updateTile()
    }
}
