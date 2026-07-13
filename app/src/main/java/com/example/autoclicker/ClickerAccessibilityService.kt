package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

data class ClickPoint(val x: Float, val y: Float)

class ClickerAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ClickerAccessibilityService? = null
        private const val TAG = "ClickerService"

        var isClicking = false
            private set
    }

    private var points: List<ClickPoint> = emptyList()
    private var currentIndex = 0
    private var intervalMs = 1000L
    private val handler = Handler(Looper.getMainLooper())
    var onTick: (() -> Unit)? = null

    private val clickRunnable = object : Runnable {
        override fun run() {
            if (isClicking && points.isNotEmpty()) {
                val point = points[currentIndex]
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    performClick(point.x, point.y)
                }
                currentIndex = (currentIndex + 1) % points.size
                onTick?.invoke()
                handler.postDelayed(this, intervalMs)
            }
        }
    }

    fun startClicking(clickPoints: List<ClickPoint>, delayMs: Long) {
        if (clickPoints.isEmpty()) return
        points = clickPoints
        intervalMs = delayMs
        currentIndex = 0
        isClicking = true
        handler.post(clickRunnable)
    }

    fun stopClicking() {
        isClicking = false
        handler.removeCallbacks(clickRunnable)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        stopClicking()
        instance = null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun performClick(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gestureBuilder = GestureDescription.Builder()
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 50)
        gestureBuilder.addStroke(strokeDescription)

        dispatchGesture(gestureBuilder.build(), null, null)
    }
}
