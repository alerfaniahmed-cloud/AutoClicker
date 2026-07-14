package com.example.autoclicker

import android.app.Activity
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
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ChainTargetsActivity : AppCompatActivity() {

    companion object {
        const val SCREEN_CAPTURE_REQUEST_CODE = 5001
    }

    private lateinit var container: LinearLayout
    private lateinit var countText: TextView

    private val handler = Handler(Looper.getMainLooper())

    // متغيرات أداة تحديد الهدف (نفس آلية الشاشة الرئيسية، مستقلة هنا)
    private var topLeftMarker: View? = null
    private var bottomRightMarker: View? = null
    private var boxOutline: View? = null
    private var confirmBtn: View? = null
    private var targetHint: View? = null
    private var topLeftParams: WindowManager.LayoutParams? = null
    private var bottomRightParams: WindowManager.LayoutParams? = null
    private var boxParams: WindowManager.LayoutParams? = null
    private val markerSize = 50

    private var magnifierView: ImageView? = null
    private val magnifierSize = 320
    private var lastMagnifierUpdate = 0L
    private var magnifierThread: HandlerThread? = null
    private var magnifierHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chain_targets)

        container = findViewById(R.id.targetsListContainer)
        countText = findViewById(R.id.chainCountText)

        findViewById<Button>(R.id.backBtn).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.addTargetBtn).setOnClickListener {
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

        refreshList()
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
            handler.postDelayed({
                showTargetSelector()
            }, 800)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        container.removeAllViews()
        val targets = TargetStorage.listTargets(this)
        countText.text = "عدد الأهداف: ${targets.size}"

        if (targets.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "ما فيه أهداف محفوظة بعد"
                setPadding(0, 40, 0, 0)
            }
            container.addView(emptyText)
            return
        }

        targets.forEachIndexed { index, file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = Gravity.CENTER_VERTICAL
            }

            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(150, 150)
                val bmp = TargetStorage.loadBitmap(file)
                if (bmp != null) setImageBitmap(bmp)
                setBackgroundColor(0xFF333333.toInt())
            }

            val label = TextView(this).apply {
                text = "  خطوة ${index + 1}"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val deleteBtn = Button(this).apply {
                text = "حذف"
                setOnClickListener {
                    TargetStorage.deleteTarget(file)
                    refreshList()
                }
            }

            row.addView(thumb)
            row.addView(label)
            row.addView(deleteBtn)
            container.addView(row)
        }
    }

    // ===== أداة تحديد الهدف (نسخة مستقلة لهذي الشاشة) =====

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

        val magnifier = ImageView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            scaleType = ImageView.ScaleType.FIT_XY
        }
        val magParams = WindowManager.LayoutParams(
            magnifierSize, magnifierSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        magParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        magParams.y = 160
        wm.addView(magnifier, magParams)
        magnifierView = magnifier

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

        updateBoxOutline()
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

        updateMagnifier(left, top, w, h)
    }

    private fun updateMagnifier(left: Int, top: Int, w: Int, h: Int) {
        val now = System.currentTimeMillis()
        if (now - lastMagnifierUpdate < 150) return
        lastMagnifierUpdate = now

        if (magnifierThread == null) {
            magnifierThread = HandlerThread("MagnifierWorkerChain").apply { start() }
            magnifierHandler = Handler(magnifierThread!!.looper)
        }

        magnifierHandler?.post {
            val svc = ScreenCaptureService.instance ?: return@post
            try {
                val full = svc.captureScreen() ?: return@post
                val safeLeft = left.coerceIn(0, (full.width - 1).coerceAtLeast(0))
                val safeTop = top.coerceIn(0, (full.height - 1).coerceAtLeast(0))
                val safeW = w.coerceAtMost(full.width - safeLeft).coerceAtLeast(1)
                val safeH = h.coerceAtMost(full.height - safeTop).coerceAtLeast(1)
                val cropped = Bitmap.createBitmap(full, safeLeft, safeTop, safeW, safeH)
                handler.post {
                    magnifierView?.setImageBitmap(cropped)
                }
            } catch (e: Exception) {
            }
        }
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
        }
    }

    private fun cleanupTargetSelector() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try { topLeftMarker?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { bottomRightMarker?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { boxOutline?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { confirmBtn?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { targetHint?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { magnifierView?.let { wm.removeView(it) } } catch (e: Exception) {}
        topLeftMarker = null
        bottomRightMarker = null
        boxOutline = null
        confirmBtn = null
        targetHint = null
        magnifierView = null
        topLeftParams = null
        bottomRightParams = null
        boxParams = null
        magnifierThread?.quitSafely()
        magnifierThread = null
        magnifierHandler = null
    }

    private fun saveTargetImageRect(left: Int, top: Int, w: Int, h: Int) {
        val svc = ScreenCaptureService.instance
        val full = svc?.captureScreen() ?: return
        val safeLeft = left.coerceIn(0, (full.width - 1).coerceAtLeast(0))
        val safeTop = top.coerceIn(0, (full.height - 1).coerceAtLeast(0))
        val safeW = w.coerceAtMost(full.width - safeLeft).coerceAtLeast(1)
        val safeH = h.coerceAtMost(full.height - safeTop).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(full, safeLeft, safeTop, safeW, safeH)
        TargetStorage.saveTarget(this, cropped)
        refreshList()
    }
}
