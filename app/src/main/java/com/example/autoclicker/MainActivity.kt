package com.example.autoclicker

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val points = mutableListOf<ClickPoint>()
    private val pointMarkers = mutableListOf<View>()

    private lateinit var statusText: TextView
    private lateinit var pointText: TextView
    private lateinit var intervalInput: EditText
    private lateinit var startStopBtn: Button

    private var pickerOverlay: View? = null
    private var stopOverlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        pointText = findViewById(R.id.pointText)
        intervalInput = findViewById(R.id.intervalInput)
        startStopBtn = findViewById(R.id.startStopBtn)

        findViewById<Button>(R.id.openAccessibilityBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.pickPointBtn).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                showFloatingPicker()
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.clearPointsBtn).setOnClickListener {
            clearAllPoints()
        }

        startStopBtn.setOnClickListener {
            if (!ClickerAccessibilityService.isClicking) {
                if (points.isEmpty()) {
                    statusText.text = "أولاً أضف نقطة نقر واحدة على الأقل"
                    return@setOnClickListener
                }
                val service = ClickerAccessibilityService.instance
                if (service == null) {
                    statusText.text = "أولاً فعّل خدمة إمكانية الوصول"
                    return@setOnClickListener
                }
                val interval = intervalInput.text.toString().toLongOrNull() ?: 1000L
                service.onTick = { runOnUiThread { } }
                service.startClicking(points.toList(), interval)
                startStopBtn.text = "إيقاف النقر التلقائي"
                hideAllMarkers()
                showStopOverlay()
            } else {
                ClickerAccessibilityService.instance?.stopClicking()
                startStopBtn.text = "ابدأ النقر التلقائي"
                removeStopOverlay()
                showAllMarkers()
            }
        }

        updatePointText()
    }

    override fun onResume() {
        super.onResume()
        statusText.text = if (ClickerAccessibilityService.instance != null)
            "الحالة: خدمة إمكانية الوصول مفعّلة ✅"
        else
            "الحالة: خدمة إمكانية الوصول غير مفعّلة"
    }

    private fun updatePointText() {
        pointText.text = "عدد نقاط النقر: ${points.size}"
    }

    private fun clearAllPoints() {
        points.clear()
        hideAllMarkers()
        pointMarkers.clear()
        updatePointText()
    }

    private fun showFloatingPicker() {
        if (pickerOverlay != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val marker = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCFF0000.toInt())
                setStroke(4, 0xFFFFFFFF.toInt())
            }
        }

        val size = 100
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 500 - size / 2
        params.y = 900 - size / 2

        var finalX = 500f
        var finalY = 900f

        marker.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        wm.updateViewLayout(marker, params)
                        finalX = params.x + size / 2f
                        finalY = params.y + size / 2f
                    }
                    MotionEvent.ACTION_UP -> {
                        wm.removeView(marker)
                        pickerOverlay = null
                        addPointMarker(finalX, finalY)
                    }
                }
                return true
            }
        })

        wm.addView(marker, params)
        pickerOverlay = marker
    }

    private fun addPointMarker(x: Float, y: Float) {
        points.add(ClickPoint(x, y))
        updatePointText()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = 80
        val label = TextView(this).apply {
            text = points.size.toString()
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC2196F3.toInt())
                setStroke(4, 0xFFFFFFFF.toInt())
            }
        }

        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (x - size / 2).toInt()
        params.y = (y - size / 2).toInt()

        wm.addView(label, params)
        pointMarkers.add(label)
    }

    private fun hideAllMarkers() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        for (marker in pointMarkers) {
            try {
                wm.removeView(marker)
            } catch (e: Exception) {
            }
        }
    }

    private fun showAllMarkers() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = 80
        for ((index, point) in points.withIndex()) {
            val label = TextView(this).apply {
                text = (index + 1).toString()
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC2196F3.toInt())
                    setStroke(4, 0xFFFFFFFF.toInt())
                }
            }
            val params = WindowManager.LayoutParams(
                size, size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = (point.x - size / 2).toInt()
            params.y = (point.y - size / 2).toInt()
            wm.addView(label, params)
            pointMarkers.add(label)
        }
    }

    private fun showStopOverlay() {
        if (stopOverlay != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val stopBtn = TextView(this).apply {
            text = "إيقاف"
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEEE53935.toInt())
                setStroke(4, 0xFFFFFFFF.toInt())
            }
        }

        val size = 150
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 40
        params.y = 200

        stopBtn.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX)
                        val dy = (event.rawY - touchY)
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            wm.updateViewLayout(stopBtn, params)
                            moved = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            runOnUiThread {
                                ClickerAccessibilityService.instance?.stopClicking()
                                startStopBtn.text = "ابدأ النقر التلقائي"
                                removeStopOverlay()
                                showAllMarkers()
                            }
                        }
                    }
                }
                return true
            }
        })

        wm.addView(stopBtn, params)
        stopOverlay = stopBtn
    }

    private fun removeStopOverlay() {
        stopOverlay?.let {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        stopOverlay = null
    }
}
