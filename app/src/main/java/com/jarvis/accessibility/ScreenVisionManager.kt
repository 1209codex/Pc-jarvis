package com.jarvis.accessibility

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class ScreenElement(
    val index: Int,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: android.graphics.Rect,
    val normalizedBounds: List<Float> // [top, left, bottom, right] in 0.0..1.0
)

data class ScreenCaptureResult(
    val base64Image: String?,
    val uiTextTree: String,
    val activePackage: String?,
    val activeActivity: String?,
    val isVisualAvailable: Boolean,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val elements: List<ScreenElement> = emptyList()
)

object ScreenVisionManager {
    private const val TAG = "ScreenVisionManager"
    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 80

    suspend fun captureScreen(): ScreenCaptureResult = withContext(Dispatchers.Default) {
        val service = JarvisAccessibilityService.instance
        val pkg = JarvisAccessibilityService.currentForegroundPackage
        val act = JarvisAccessibilityService.currentActivityName
        val metrics = service?.resources?.displayMetrics
        val screenWidth = metrics?.widthPixels ?: 1080
        val screenHeight = metrics?.heightPixels ?: 2400

        // 1. Capture UI Text Tree & Screen Elements with normalized bounding boxes
        val uiNodes = service?.dumpInteractiveTree().orEmpty()
        val elements = mutableListOf<ScreenElement>()
        val formattedTree = buildString {
            if (pkg != null) appendLine("Foreground App: $pkg ($act)")
            if (uiNodes.isEmpty()) {
                appendLine("No interactive UI text detected in active window.")
            } else {
                appendLine("Visible UI Elements (${uiNodes.size} items):")
                uiNodes.take(40).forEachIndexed { idx, node ->
                    val label = node.text ?: node.contentDescription ?: node.viewId ?: "Element"
                    val top = (node.bounds.top.toFloat() / screenHeight).coerceIn(0f, 1f)
                    val left = (node.bounds.left.toFloat() / screenWidth).coerceIn(0f, 1f)
                    val bottom = (node.bounds.bottom.toFloat() / screenHeight).coerceIn(0f, 1f)
                    val right = (node.bounds.right.toFloat() / screenWidth).coerceIn(0f, 1f)
                    val normBox = listOf(top, left, bottom, right)

                    elements.add(
                        ScreenElement(
                            index = idx + 1,
                            text = node.text,
                            contentDescription = node.contentDescription,
                            viewId = node.viewId,
                            isClickable = node.isClickable,
                            isEditable = node.isEditable,
                            bounds = node.bounds,
                            normalizedBounds = normBox
                        )
                    )

                    appendLine("${idx + 1}. [$label] (clickable=${node.isClickable}, editable=${node.isEditable}, bounds=[${(top*1000).toInt()},${(left*1000).toInt()},${(bottom*1000).toInt()},${(right*1000).toInt()}])")
                }
            }
        }

        // 2. Try to capture real screenshot bitmap via AccessibilityService (API 30+)
        var base64Img: String? = null
        try {
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                service?.takeScreenshotAsync()
            } else null
            if (bitmap != null) {
                base64Img = encodeBitmapToBase64(bitmap)
                bitmap.recycle()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Screenshot capture failed or unsupported: ${e.message}")
        }

        ScreenCaptureResult(
            base64Image = base64Img,
            uiTextTree = formattedTree,
            activePackage = pkg,
            activeActivity = act,
            isVisualAvailable = base64Img != null,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            elements = elements
        )
    }

    fun downscaleBitmap(source: Bitmap, maxDim: Int = MAX_DIMENSION): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= maxDim && height <= maxDim) return source

        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int

        if (width > height) {
            targetWidth = maxDim
            targetHeight = (maxDim / ratio).roundToInt().coerceAtLeast(1)
        } else {
            targetHeight = maxDim
            targetWidth = (maxDim * ratio).roundToInt().coerceAtLeast(1)
        }

        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val scaled = downscaleBitmap(bitmap, MAX_DIMENSION)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        val byteArray = stream.toByteArray()
        if (scaled != bitmap) {
            scaled.recycle()
        }
        val encoded = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }
}
