package com.jarvis.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.jarvis.service.JarvisForegroundService
import com.jarvis.ui.components.ResponsiveNav
import com.jarvis.ui.data.UiPreferencesStore
import com.jarvis.ui.reliability.*
import com.jarvis.ui.screens.ApiManagerScreen
import com.jarvis.ui.screens.DashboardScreen
import com.jarvis.ui.screens.LogsScreen
import com.jarvis.ui.screens.MemoryScreen
import com.jarvis.ui.screens.SettingsScreen
import com.jarvis.ui.screens.SkillsScreen
import com.jarvis.ui.security.SecureCredentialStore

class MainActivity : AppCompatActivity() {
    private lateinit var container: FrameLayout
    private lateinit var nav: ResponsiveNav
    private lateinit var store: UiPreferencesStore
    private lateinit var apiManager: ApiManager
    private lateinit var runner: BackgroundTaskRunner
    private lateinit var logger: CorrelationLogger
    private var currentRoute = "Dashboard"
    private var dirty = false
    private var service: JarvisForegroundService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? JarvisForegroundService.LocalBinder)?.getService()
            bound = service != null
            (container.findViewWithTag<DashboardScreen>("dashboard"))?.let { screen -> service?.let(screen::bindService) }
            (container.findViewWithTag<SkillsScreen>("skills"))?.let { screen -> service?.let(screen::bindService) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        store = UiPreferencesStore(this)
        apiManager = ApiManager(store, SecureCredentialStore(this))
        runner = BackgroundTaskRunner()
        logger = CorrelationLogger(applicationContext)
        setContentView(buildRoot())
        applyTheme(store.loadSettings().theme)
        requestPermissionsIfNeeded()
        startAndBindService()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (dirty) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Unsaved changes")
                        .setMessage("Leave this configuration without saving?")
                        .setNegativeButton("Stay", null)
                        .setPositiveButton("Discard") { _, _ -> dirty = false; showRoute("Dashboard") }
                        .show()
                } else if (currentRoute != "Dashboard") showRoute("Dashboard") else finish()
            }
        })
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(com.jarvis.ui.components.Ui.BG)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        nav = ResponsiveNav(this) { route -> if (!dirty || route == currentRoute) showRoute(route) else guardRoute(route) }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, com.jarvis.ui.components.Ui.dp(this@MainActivity, 58))
        }
        container = FrameLayout(this).apply { id = View.generateViewId(); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(container)
        root.addView(nav)
        showRoute("Dashboard")
        return root
    }

    private fun guardRoute(route: String) {
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("You have changes in Settings. Save them before leaving?")
            .setNegativeButton("Stay", null)
            .setNeutralButton("Discard") { _, _ -> dirty = false; showRoute(route) }
            .setPositiveButton("Save") { _, _ ->
                val screen = container.getChildAt(0)
                if (screen is SettingsScreen) { screen.saveSettings() }
                dirty = false
                showRoute(route)
            }.show()
    }

    private fun showRoute(route: String) {
        currentRoute = route
        dirty = false
        if (::nav.isInitialized) nav.setRoute(route)
        val screen: View = when (route) {
            "Skills" -> SkillsScreen(this, this, runner) { command ->
                service?.voiceEngine?.let { engine ->
                    lifecycleScope.launch { engine.processTextCommand(command) }
                }
            }.also { s ->
                s.tag = "skills"
                service?.let { s.bindService(it) }
            }
            "Memory" -> MemoryScreen(this)
            "APIs" -> ApiManagerScreen(this, this, apiManager, logger) { service?.reloadConfig() }
            "Settings" -> SettingsScreen(this, store, store.loadSettings(), { dirty = it }, { service?.reloadConfig() })
            "Logs" -> LogsScreen(this, store)
            else -> DashboardScreen(
                this, this, runner, store::loadSettings,
                { showRoute("Settings") },
                logger,
                { showRoute("Skills") },
                { showRoute("Memory") }
            ).also { it.tag = "dashboard" }
        }
        container.removeAllViews(); container.addView(screen)
        if (route == "Dashboard") service?.let { (screen as DashboardScreen).bindService(it) }
    }

    private fun applyTheme(theme: String) {
        val mode = when (theme) { "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES; "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO; else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
        if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != mode) androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun startAndBindService() {
        if (!hasAudioPermission()) return
        JarvisForegroundService.startService(this)
        val intent = Intent(this, JarvisForegroundService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && hasAudioPermission()) {
            requestBatteryOptimizationExemption()
            startAndBindService()
        }
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            requestBatteryOptimizationExemption()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        } catch (e: Exception) {
            // Ignore if restricted
        }
    }

    fun requestUnlockIfKeyguardLocked(onSuccess: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        super.onDismissSucceeded()
                        onSuccess()
                    }
                })
            } else {
                onSuccess()
            }
        } else {
            onSuccess()
        }
    }

    override fun onDestroy() {
        if (bound) runCatching { unbindService(connection) }
        apiManager.cancelAll()
        runner.dispose()
        super.onDestroy()
    }
}
