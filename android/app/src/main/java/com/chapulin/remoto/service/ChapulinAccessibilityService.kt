package com.chapulin.remoto.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChapulinAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ChapulinAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun dispatchClick(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 120) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(50))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // Rueda del raton: gesto vertical corto (scroll arriba/abajo).
    fun dispatchScroll(x: Float, y: Float, dy: Float, duration: Long = 120) {
        val endY = (y - dy).coerceIn(0f, Resources.getSystem().displayMetrics.heightPixels.toFloat())
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(50))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // Long-press (click sostenido) con el boton derecho del raton.
    fun dispatchLongPress(x: Float, y: Float, duration: Long = 600) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(100))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // Zoom con dos dedos (pellizco): factor > 1 = acercar, < 1 = alejar.
    fun dispatchPinch(cxFactor: Float, cyFactor: Float, factor: Float, duration: Long = 160) {
        val metrics = Resources.getSystem().displayMetrics
        val cx = cxFactor * metrics.widthPixels
        val cy = cyFactor * metrics.heightPixels
        val half = 300f / factor
        val pIn = Path().apply {
            moveTo(cx - half, cy)
            lineTo(cx - half / factor, cy)
        }
        val pOut = Path().apply {
            moveTo(cx + half, cy)
            lineTo(cx + half / factor, cy)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pIn, 0, duration))
            .addStroke(GestureDescription.StrokeDescription(pOut, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // Escribe texto en el campo activo usando ACTION_SET_TEXT.
    fun typeText(text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val node = rootInActiveWindow ?: return false
        val target = findEditable(node) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    fun doBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun doHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun doRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun doLock(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }
}
