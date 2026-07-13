package com.example.autoclicker

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private var clickX = 500f
    private var clickY = 900f
    private var isClicking = false
    private val handler = Handler(Looper.getMainLooper())
    private var intervalMs = 1000L

    private lateinit var statusText: TextView
    private lateinit var pointText: TextView
    private lateinit var intervalInput: EditText
    private lateinit var startStopBtn: Button

    private var overlayView: View? = null

    private val clickRunnable = object : Runnable {
        override fun run() {
            if (isClicking) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ClickerAccessibilityService.instance?.performClick(clickX, clickY)
                }
                handler.postDelayed(this, intervalMs)
            }
        }
    }

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

        startStopBtn.setOnClickListener {
            if (!isClicking) {
                intervalMs = intervalInput.text.toString().toLongOrNull() ?: 1000L
                if (ClickerAccessibilityService.instance == null) {
                    statusText.text = "الحالة: فعّل خدمة إمكانية الوصول أولاً"
                    return@setOnClickListener
                }
                isClicking = true
                startStopBtn.text = "إيقاف النقر التلقائي"
                handler.post(clickRunnable)
            } else {
                isClicking = false
                startStopBtn.text = "ابدأ النقر التلقائي"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        statusText.text = if (ClickerAccessibilityService.instance != null)
            "الحالة: خدمة إمكانية الوصول مفعّلة ✅"
        else
            "الحالة: خدمة إمكانية الوصول غير مفعّلة ⚠️"
    }

    private fun showFloatingPicker() {
        if (overlayView != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val marker = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xCCFF0000.toInt())
                setStroke(4, 0xFFFFFFFF.toInt())
            }}

        val size = 100
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = clickX.toInt() - size / 2
        params.y = clickY.toInt() - size / 2

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
                        clickX = params.x + size / 2f
                        clickY = params.y + size / 2f
                        runOnUiThread {
                            pointText.text = "نقطة النقر الحالية: ${clickX.toInt()} , ${clickY.toInt()}"
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        wm.removeView(marker)
                        overlayView = null
                    }
                }
                return true
            }
        })

        wm.addView(marker, params)
        overlayView = marker
    }
}
