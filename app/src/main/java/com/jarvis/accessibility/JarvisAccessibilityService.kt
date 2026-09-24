package com.jarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

data class UiNodeInfo(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean
)

class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef.set(this)
        Log.i(TAG, "JarvisAccessibilityService connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            _currentForegroundPackage = pkg
        }
        val cls = event.className?.toString()
        if (!cls.isNullOrBlank()) {
            _currentActivityName = cls
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "JarvisAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instanceRef.compareAndSet(this, null)
        Log.i(TAG, "JarvisAccessibilityService destroyed")
    }

    /**
     * Attempts to click a node. If the node itself is not clickable, traverses
     * up the hierarchy to find a clickable parent. If no clickable parent exists,
     * falls back to dispatching a physical tap gesture at the node's screen center!
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var curr: AccessibilityNodeInfo? = node
        while (curr != null) {
            if (curr.isClickable) {
                val clicked = curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) return true
            }
            curr = curr.parent
        }

        // Fallback: tap at coordinates
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            return clickCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    /**
     * Sets text on an editable node or focused node.
     */
    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Dispatches a tap gesture at (x, y) coordinates on the screen.
     */
    fun clickCoordinates(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture click at ($x, $y) completed successfully")
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture click at ($x, $y) cancelled")
                onComplete?.invoke(false)
            }
        }, Handler(Looper.getMainLooper()))
    }

    suspend fun clickCoordinatesAsync(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { continuation ->
            val dispatched = clickCoordinates(x, y) { success ->
                if (continuation.isActive) continuation.resume(success)
            }
            if (!dispatched && continuation.isActive) {
                continuation.resume(false)
            }
        }

    /**
     * Clicks normalized screen coordinates (0.0 to 1.0) scaled to the active display metrics.
     */
    suspend fun clickNormalizedCoordinatesAsync(normX: Float, normY: Float): Boolean {
        val displayMetrics = resources.displayMetrics
        val realX = normX.coerceIn(0f, 1f) * displayMetrics.widthPixels
        val realY = normY.coerceIn(0f, 1f) * displayMetrics.heightPixels
        return clickCoordinatesAsync(realX, realY)
    }

    /**
     * Long-presses on the specified screen coordinates for durationMs (default 700ms).
     */
    suspend fun longPressCoordinatesAsync(x: Float, y: Float, durationMs: Long = 700): Boolean =
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(400))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Long press at ($x, $y) completed")
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Long press at ($x, $y) cancelled")
                    if (continuation.isActive) continuation.resume(false)
                }
            }, Handler(Looper.getMainLooper()))

            if (!dispatched && continuation.isActive) {
                continuation.resume(false)
            }
        }

    /**
     * Double-taps on the specified screen coordinates.
     */
    suspend fun doubleTapCoordinatesAsync(x: Float, y: Float): Boolean {
        val tap1 = clickCoordinatesAsync(x, y)
        if (!tap1) return false
        delay(120)
        return clickCoordinatesAsync(x, y)
    }

    /**
     * Dispatches a swipe gesture from (startX, startY) to (endX, endY).
     */
    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe from ($startX, $startY) to ($endX, $endY) completed")
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled")
                onComplete?.invoke(false)
            }
        }, Handler(Looper.getMainLooper()))
    }

    suspend fun swipeAsync(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val dispatched = swipe(startX, startY, endX, endY, durationMs) { success ->
            if (continuation.isActive) continuation.resume(success)
        }
        if (!dispatched && continuation.isActive) {
            continuation.resume(false)
        }
    }

    suspend fun scrollDown(): Boolean {
        val scrollNode = findFirstScrollableNode()
        if (scrollNode != null && scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true
        }
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        return swipeAsync(width / 2f, height * 0.75f, width / 2f, height * 0.25f, 300)
    }

    suspend fun scrollUp(): Boolean {
        val scrollNode = findFirstScrollableNode()
        if (scrollNode != null && scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            return true
        }
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        return swipeAsync(width / 2f, height * 0.25f, width / 2f, height * 0.75f, 300)
    }

    /**
     * Takes an on-demand screenshot of the active display without permission prompts on Android 11+ (API 30+).
     */
    @RequiresApi(android.os.Build.VERSION_CODES.R)
    suspend fun takeScreenshotAsync(displayId: Int = android.view.Display.DEFAULT_DISPLAY): android.graphics.Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            try {
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                takeScreenshot(displayId, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hwBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            val softwareBitmap = hwBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            hwBuffer.close()
                            if (continuation.isActive) continuation.resume(softwareBitmap)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error wrapping screenshot buffer: ${e.message}", e)
                            if (continuation.isActive) continuation.resume(null)
                        } finally {
                            executor.shutdown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed with error code: $errorCode")
                        executor.shutdown()
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to invoke takeScreenshot: ${e.message}", e)
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /**
     * Actively searches YouTube UI and taps the first video result.
     */
    suspend fun searchAndPlayYouTube(query: String): Boolean {
        // 1. Wait for YouTube to be in foreground
        var reachedYouTube = false
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            val fg = currentForegroundPackage
            if (fg != null && fg.contains("youtube", ignoreCase = true)) {
                reachedYouTube = true
                break
            }
            delay(200)
        }
        if (!reachedYouTube) return false

        delay(800)

        // 2. Click the Search button (Search icon on Home screen)
        val searchButton = findNodeByContentDescription("Search")
            ?: findNodeByViewId("com.google.android.youtube:id/menu_item_view")
            ?: findNodeByViewId("com.google.android.youtube:id/menu_item_1")

        if (searchButton != null) {
            val target = if (searchButton.isClickable) searchButton else searchButton.parent
            target?.let { clickNode(it) }
        } else {
            // Tap top-right search icon area if node not found
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels.toFloat()
            clickCoordinatesAsync(width - 60f, 150f)
        }

        delay(700)

        // 3. Find search EditText and enter query
        val searchEdit = findNodeByViewId("com.google.android.youtube:id/search_edit_text")
            ?: findFirstEditableNode()

        if (searchEdit != null) {
            setNodeText(searchEdit, query)
            delay(400)
            // 4. Tap the first suggestion or execute search
            val suggestion = findNodeByViewId("com.google.android.youtube:id/text")
                ?: findNodeByText(query, exact = false)
            if (suggestion != null) {
                val sugTarget = if (suggestion.isClickable) suggestion else suggestion.parent
                sugTarget?.let { clickNode(it) }
            }
        }

        // 5. Click the top video result card
        return clickFirstYouTubeResult(query)
    }

    /**
     * Finds and clicks the first video item in YouTube search results to ensure actual playback.
     */
     suspend fun clickFirstYouTubeResult(query: String? = null): Boolean {
        // Wait up to 4 seconds for YouTube to reach the foreground
        var reachedYouTube = false
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            val fg = currentForegroundPackage
            if (fg != null && fg.contains("youtube", ignoreCase = true)) {
                reachedYouTube = true
                break
            }
            delay(200)
        }
        if (!reachedYouTube) {
            Log.w(TAG, "YouTube did not reach foreground within timeout")
            return false
        }

        // Wait 1.2s for video cards and search results to render
        delay(1200)

        val root = rootInActiveWindow
        if (root != null) {
            val queryWords = query?.lowercase()?.split(Regex("\\s+"))?.filter {
                it.length > 2 && it !in setOf("song", "video", "play", "chalao", "the", "and", "youtube")
            } ?: emptyList()

            // 1. Try to find a video title or description node matching query words
            if (queryWords.isNotEmpty()) {
                val matchedNode = dfsFind(root) { node ->
                    val text = node.text?.toString()?.lowercase()
                    val desc = node.contentDescription?.toString()?.lowercase()
                    val matches = queryWords.any { word ->
                        text?.contains(word) == true || desc?.contains(word) == true
                    }
                    matches && (node.isClickable || node.parent?.isClickable == true)
                }
                if (matchedNode != null) {
                    val target = if (matchedNode.isClickable) matchedNode else matchedNode.parent
                    if (target != null && clickNode(target)) {
                        Log.i(TAG, "Clicked matched YouTube video node: ${matchedNode.text ?: matchedNode.contentDescription}")
                        return true
                    }
                }
            }

            // 2. Try to find any video item view or thumbnail in YouTube UI
            val videoItemNode = dfsFind(root) { node ->
                val id = node.viewIdResourceName?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val isVideoContainer = id.contains("thumbnail") || id.contains("video_info") || id.contains("compact_video_item") ||
                        (desc.contains("views") && (desc.contains("ago") || desc.contains("duration") || desc.contains("minute") || desc.contains("hour")))
                isVideoContainer && (node.isClickable || node.parent?.isClickable == true)
            }
            if (videoItemNode != null) {
                val target = if (videoItemNode.isClickable) videoItemNode else videoItemNode.parent
                if (target != null && clickNode(target)) {
                    Log.i(TAG, "Clicked YouTube video item container")
                    return true
                }
            }
        }

        // 3. Fallback: Tap coordinates directly on the top video result card
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val tapX = width * 0.5f
        val tapY = height * 0.32f
        Log.i(TAG, "Tapping YouTube first result at ($tapX, $tapY)")
        return clickCoordinatesAsync(tapX, tapY)
    }

    /**
     * Searches for a node matching text (contains or exact).
     */
    fun findNodeByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val lower = text.lowercase().trim()
        return dfsFind(root) { node ->
            val nodeText = node.text?.toString()?.lowercase()?.trim()
            if (nodeText != null) {
                if (exact) nodeText == lower else nodeText.contains(lower)
            } else false
        }
    }

    /**
     * Searches for a node matching content description.
     */
    fun findNodeByContentDescription(desc: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val lower = desc.lowercase().trim()
        return dfsFind(root) { node ->
            val nodeDesc = node.contentDescription?.toString()?.lowercase()?.trim()
            if (nodeDesc != null) {
                if (exact) nodeDesc == lower else nodeDesc.contains(lower)
            } else false
        }
    }

    /**
     * Searches for a node by view ID resource name (e.g. "com.whatsapp:id/send").
     */
    fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return nodes?.firstOrNull()
    }

    /**
     * Finds first editable input field on screen.
     */
    fun findFirstEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return dfsFind(root) { it.isEditable }
    }

    /**
     * Finds first scrollable container on screen.
     */
    fun findFirstScrollableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return dfsFind(root) { it.isScrollable }
    }

    /**
     * Dumps all interactive or content-bearing elements on screen.
     */
    fun dumpInteractiveTree(): List<UiNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val list = mutableListOf<UiNodeInfo>()
        dfsCollect(root, list)
        return list
    }

    private fun dfsCollect(node: AccessibilityNodeInfo?, out: MutableList<UiNodeInfo>) {
        if (node == null) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val id = node.viewIdResourceName
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isClickable || node.isEditable) {
            out.add(
                UiNodeInfo(
                    text = text,
                    contentDescription = desc,
                    viewId = id,
                    className = node.className?.toString(),
                    bounds = rect,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable
                )
            )
        }

        for (i in 0 until node.childCount) {
            dfsCollect(node.getChild(i), out)
        }
    }

    private fun dfsFind(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val found = dfsFind(node.getChild(i), predicate)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val TAG = "JarvisAccessibility"
        private val instanceRef = AtomicReference<JarvisAccessibilityService?>(null)
        private var _currentForegroundPackage: String? = null
        private var _currentActivityName: String? = null

        val instance: JarvisAccessibilityService?
            get() = instanceRef.get()

        val isEnabled: Boolean
            get() = instanceRef.get() != null

        val currentForegroundPackage: String?
            get() = instance?.rootInActiveWindow?.packageName?.toString() ?: _currentForegroundPackage

        val currentActivityName: String?
            get() = _currentActivityName
    }
}
