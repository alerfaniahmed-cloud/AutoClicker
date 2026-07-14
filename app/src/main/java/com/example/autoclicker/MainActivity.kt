package com.example.autoclicker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
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
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {

    companion object {
        val points = mutableListOf<ClickPoint>()
        val pointMarkers = mutableListOf<View>()
        const val SCREEN_CAPTURE_REQUEST_CODE = 4001
    }

    private lateinit var statusText: TextView
    private lateinit var pointText: TextView
    private lateinit var intervalInput: EditText
    private lateinit var startStopBtn: Button

    private var pickerOverlay: View? = null
    private var stopOverlay: View? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        setContentView(R.layout.activity_main)

        showCrashLogIfExists()

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
            if (points.isEmpty()) {
                statusText.text = "أولاً أضف نقطة نقر واحدة على الأقل"
                return@setOnClickListener
            }
            val service = ClickerAccessibilityService.instance
            if (service == null) {
                statusText.text = "أولاً فعّل خدمة إمكانية الوصول"
                return@setOnClickListener
            }
            hideAllMarkers()
            showControlOverlay()
            statusText.text = "اضغط الزر العائم بالشاشة للتحكم بالنقر"
        }

        findViewById<Button>(R.id.pickTargetBtn).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }
            if (ScreenCaptureService.instance == null) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
            } else {
                showTargetPicker()
            }
        }

        findViewById<Button>(R.id.smartClickBtn).setOnClickListener {
            val svc = ScreenCaptureService.instance
            if (svc == null) {
                statusText.text = "أولاً التقط صورة الهدف"
                return@setOnClickListener
            }
            if (ScreenCaptureService.targetBitmap == null) {
                statusText.text = "ما فيه صورة هدف محفوظة، التقطها أول"
                return@setOnClickListener
            }
            if (ClickerAccessibilityService.instance == null) {
                statusText.text = "فعّل خدمة إمكانية الوصول أولاً"
                return@setOnClickListener
            }
            val btn = it as Button
            if (ScreenCaptureService.isMatching) {
                svc.stopSmartMatching()
                btn.text = "بدء التعرف الذكي"
                statusText.text = "التعرف الذكي متوقف"
            } else {
                svc.startSmartMatching()
                btn.text = "إيقاف التعرف الذكي"
                statusText.text = "التعرف الذكي شغال..."
            }
        }

        updatePointText()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                openFileOutput("crash_log.txt", Context.MODE_PRIVATE).use { fos ->
                    fos.write(sw.toString().toByteArray())
                }
            } catch (e: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun showCrashLogIfExists() {
        val file = getFileStreamPath("crash_log.txt")
        if (file != null && file.exists()) {
            val content = try { file.readText() } catch (e: Exception) { "تعذر قراءة الملف" }
            file.delete()
            AlertDialog.Builder(this)
                .setTitle("آخر خطأ حصل بالتطبيق")
                .setMessage(content)
                .setPositiveButton("حسناً", null)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusText.text = "جاري تجهيز التقاط الشاشة..."
            handler.postDelayed({
                showTargetPicker()
            }, 800)
        }
    }

    override fun onResume() {
        super.onResume()
        showCrashLogIfExists()
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

    private fun showTargetPicker() {
        if (pickerOverlay != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val marker = TextView(this).apply {
            text = "🎯"
            gravity = Gravity.CENTER
            textSize = 20f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCFFC107.toInt())
                setStroke(4, 0xFFFFFFFF.toInt())
            }
        }

        val size = 120
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
                        saveTargetImage(finalX, finalY)
                    }
                }
                return true
            }
        })

        wm.addView(marker, params)
        pickerOverlay = marker
    }

    private fun saveTargetImage(x: Float, y: Float) {
        val svc = ScreenCaptureService.instance
        val full = svc?.captureScreen()
        if (full == null) {
            statusText.text = "ما قدرت ألتقط الشاشة، حاول مرة ثانية"
            return
        }
        val boxSize = 160
        val half = boxSize / 2
        val left = (x - half).toInt().coerceIn(0, (full.width - boxSize).coerceAtLeast(0))
        val top = (y - half).toInt().coerceIn(0, (full.height - boxSize).coerceAtLeast(0))
        val w = boxSize.coerceAtMost(full.width)
        val h = boxSize.coerceAtMost(full.height)
        val cropped = Bitmap.createBitmap(full, left, top, w, h)
        ScreenCaptureService.targetBitmap = cropped
        statusText.text = "تم حفظ صورة الهدف ✅ الآن اضغط بدء التعرف الذكي"
    }

    private fun addPointMarker(x: Float, y: Float) {
        points.add(ClickPoint(x, y))
        updatePointText()
        drawMarker(points.size - 1)
    }

    private fun drawMarker(index: Int) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = 80
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

        val point = points[index]
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

        label.setOnTouchListener(object : View.OnTouchListener {
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
                        wm.updateViewLayout(label, params)
                    }
                    MotionEvent.ACTION_UP -> {
                        val newX = params.x + size / 2f
                        val newY = params.y + size / 2f
                        points[index] = ClickPoint(newX, newY)
                    }
                }
                return true
            }
        })

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
        pointMarkers.clear()
    }

    private fun showAllMarkers() {
        for (i in points.indices) {
            drawMarker(i)
        }
    }

    private fun showControlOverlay() {
        if (stopOverlay != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val controlBtn = TextView(this).apply {
            text = "▶"
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            textSize = 20f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE43A047.toInt())
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

        controlBtn.setOnTouchListener(object : View.OnTouchListener {
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
                            wm.updateViewLayout(controlBtn, params)
                            moved = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            val svc = ClickerAccessibilityService.instance
                            if (svc != null) {
                                if (ClickerAccessibilityService.isClicking) {
                                    svc.stopClicking()
                                    controlBtn.text = "▶"
                                    controlBtn.background = GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(0xEE43A047.toInt())
                                        setStroke(4, 0xFFFFFFFF.toInt())
                                    }
                                } else {
                                    val interval = intervalInput.text.toString().toLongOrNull() ?: 1000L
                                    svc.startClicking(points.toList(), interval)
                                    controlBtn.text = "⏸"
                                    controlBtn.background = GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(0xEEE53935.toInt())
                                        setStroke(4, 0xFFFFFFFF.toInt())
                                    }
                                }
                            }
                        }
                    }
                }
                return true
            }
        })

        wm.addView(controlBtn, params)
        stopOverlay = controlBtn
    }
}
