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

    // متغيرات خاصة بتحديد صورة الهدف (منفصلة تماماً عن نقاط النقر العادية)
    private var topLeftMarker: View? = null
    private var bottomRightMarker: View? = null
    private var boxOutline: View? = null
    private var confirmBtn: View? = null
    private var targetHint: View? = null
    private var topLeftParams: WindowManager.LayoutParams? = null
    private var bottomRightParams: WindowManager.LayoutParams? = null
    private var boxParams: WindowManager.LayoutParams? = null
    private val markerSize = 50

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
                showTargetSelector()
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
                showTargetSelector()
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

    // ===== تحديد صورة الهدف بعلامتين قابلتين للسحب، بدون حجب باقي الشاشة =====

    private fun showTargetSelector() {
        if (topLeftMarker != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val hint = TextView(this).apply {
            text = "اسحب الدائرتين لتحديد زاويتي الهدف، ثم اضغط ✅"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xAA000000.toInt())
            setPadding(20, 10, 20, 10)
        }
        val hintParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        hintParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        hintParams.y = 100
        wm.addView(hint, hintParams)
        targetHint = hint

        val box = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x33FFC107)
                setStroke(4, 0xFFFFC107.toInt())
            }
        }
        val bParams = WindowManager.LayoutParams(
            160, 160,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        bParams.gravity = Gravity.TOP or Gravity.START
        bParams.x = 460
        bParams.y = 820
        wm.addView(box, bParams)
        boxOutline = box
        boxParams = bParams

        val tl = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF0000.toInt())
                setStroke(4, 0xFFFFFFFF.toInt())
            }
        }
        val tlParams = WindowManager.LayoutParams(
            markerSize, markerSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        tlParams.gravity = Gravity.TOP or Gravity.START
        tlParams.x = 460 - markerSize / 2
        tlParams.y = 820 - markerSize / 2

        val br = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2196F3.toInt())
                setStroke(4, 0xFFFFFFFF.toInt())
            }
        }
        val brParams = WindowManager.LayoutParams(
            markerSize, markerSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        brParams.gravity = Gravity.TOP or Gravity.START
        brParams.x = 460 + 160 - markerSize / 2
        brParams.y = 820 + 160 - markerSize / 2

        tl.setOnTouchListener(makeCornerTouchListener(wm, tl, tlParams))
        br.setOnTouchListener(makeCornerTouchListener(wm, br, brParams))

        wm.addView(tl, tlParams)
        wm.addView(br, brParams)
        topLeftMarker = tl
        bottomRightMarker = br
        topLeftParams = tlParams
        bottomRightParams = brParams

        val confirm = TextView(this).apply {
            text = "✅"
            gravity = Gravity.CENTER
            textSize = 22f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE43A047.toInt())
                setStroke(4, 0xFFFFFFFF.toInt())
            }
        }
        val confirmParams = WindowManager.LayoutParams(
            110, 110,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        confirmParams.gravity = Gravity.TOP or Gravity.START
        confirmParams.x = 40
        confirmParams.y = 300

        confirm.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = confirmParams.x
                        initialY = confirmParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX)
                        val dy = (event.rawY - touchY)
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            confirmParams.x = initialX + dx.toInt()
                            confirmParams.y = initialY + dy.toInt()
                            wm.updateViewLayout(confirm, confirmParams)
                            moved = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            finishTargetSelection()
                        }
                    }
                }
                return true
            }
        })

        wm.addView(confirm, confirmParams)
        confirmBtn = confirm

        statusText.text = "حرك الدائرتين لتحديد الهدف، ثم اضغط ✅"
    }

    private fun makeCornerTouchListener(
        wm: WindowManager,
        view: View,
        params: WindowManager.LayoutParams
    ): View.OnTouchListener {
        return object : View.OnTouchListener {
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
                        wm.updateViewLayout(view, params)
                        updateBoxOutline()
                    }
                }
                return true
            }
        }
    }

    private fun updateBoxOutline() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tl = topLeftParams ?: return
        val br = bottomRightParams ?: return
        val box = boxOutline ?: return
        val bp = boxParams ?: return

        val tlCx = tl.x + markerSize / 2
        val tlCy = tl.y + markerSize / 2
        val brCx = br.x + markerSize / 2
        val brCy = br.y + markerSize / 2

        val left = Math.min(tlCx, brCx)
        val top = Math.min(tlCy, brCy)
        val w = Math.abs(brCx - tlCx).coerceAtLeast(10)
        val h = Math.abs(brCy - tlCy).coerceAtLeast(10)

        bp.x = left
        bp.y = top
        bp.width = w
        bp.height = h
        try { wm.updateViewLayout(box, bp) } catch (e: Exception) {}
    }

    private fun finishTargetSelection() {
        val tl = topLeftParams
        val br = bottomRightParams
        if (tl == null || br == null) return

        val tlCx = tl.x + markerSize / 2
        val tlCy = tl.y + markerSize / 2
        val brCx = br.x + markerSize / 2
        val brCy = br.y + markerSize / 2

        val left = Math.min(tlCx, brCx)
        val top = Math.min(tlCy, brCy)
        val w = Math.abs(brCx - tlCx)
        val h = Math.abs(brCy - tlCy)

        cleanupTargetSelector()

        if (w > 20 && h > 20) {
            handler.postDelayed({
                saveTargetImageRect(left, top, w, h)
            }, 200)
        } else {
            statusText.text = "المساحة صغيرة جداً، حاول من جديد"
        }
    }

    private fun cleanupTargetSelector() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try { topLeftMarker?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { bottomRightMarker?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { boxOutline?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { confirmBtn?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { targetHint?.let { wm.removeView(it) } } catch (e: Exception) {}
        topLeftMarker = null
        bottomRightMarker = null
        boxOutline = null
        confirmBtn = null
        targetHint = null
        topLeftParams = null
        bottomRightParams = null
        boxParams = null
    }

    private fun saveTargetImageRect(left: Int, top: Int, w: Int, h: Int) {
        val svc = ScreenCaptureService.instance
        val full = svc?.captureScreen()
        if (full == null) {
            statusText.text = "ما قدرت ألتقط الشاشة، حاول مرة ثانية"
            return
        }
        val safeLeft = left.coerceIn(0, (full.width - 1).coerceAtLeast(0))
        val safeTop = top.coerceIn(0, (full.height - 1).coerceAtLeast(0))
        val safeW = w.coerceAtMost(full.width - safeLeft).coerceAtLeast(1)
        val safeH = h.coerceAtMost(full.height - safeTop).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(full, safeLeft, safeTop, safeW, safeH)
        ScreenCaptureService.targetBitmap = cropped
        statusText.text = "تم حفظ صورة الهدف ✅ الآن اضغط بدء التعرف الذكي"
    }

    // ===== نهاية جزء تحديد الهدف =====

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
