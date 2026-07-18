package com.example.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    companion object {
        var instance: ScreenCaptureService? = null
        var mediaProjection: MediaProjection? = null
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIF_ID = 1001

        var targetBitmap: Bitmap? = null
        var isMatching = false
        var isChainMatching = false
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestBitmap: Bitmap? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastClickTime = 0L

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    // ===== منطق التعرف الذكي (هدف واحد) — بدون أي تعديل =====

    private val matchRunnable = object : Runnable {
        override fun run() {
            if (!isMatching) return
            val target = targetBitmap
            val screen = captureScreen()
            if (target != null && screen != null) {
                val point = findMatch(screen, target)
                if (point != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime > 120) {
                        lastClickTime = now
                        mainHandler.post {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                ClickerAccessibilityService.instance?.performClick(
                                    point.x.toFloat(), point.y.toFloat()
                                )
                            }
                        }
                    }
                }
            }
            workerHandler?.postDelayed(this, 60)
        }
    }

    fun startSmartMatching() {
        if (isMatching) return
        stopChainMatching()
        isMatching = true
        if (workerThread == null) {
            workerThread = HandlerThread("MatchWorker").apply { start() }
            workerHandler = Handler(workerThread!!.looper)
        }
        workerHandler?.post(matchRunnable)
    }

    fun stopSmartMatching() {
        isMatching = false
        workerHandler?.removeCallbacks(matchRunnable)
    }

    // ===== منطق التعرف متعدد الأهداف: يفحص كل الصور المحفوظة، وأي وحدة تنطبق ينقر عليها =====
    // بدون ترتيب، بدون مؤقتات — كل دورة يمر على القائمة ويوقف عند أول تطابق

    private var chainWorkerThread: HandlerThread? = null
    private var chainWorkerHandler: Handler? = null
    private var chainTargets: MutableList<Bitmap> = mutableListOf()
    private var lastChainClickTime = 0L

    private val chainRunnable = object : Runnable {
        override fun run() {
            if (!isChainMatching) return
            if (chainTargets.isEmpty()) {
                chainWorkerHandler?.postDelayed(this, 200)
                return
            }

            val screen = captureScreen()
            if (screen != null) {
                val now = System.currentTimeMillis()
                if (now - lastChainClickTime > 120) {
                    for (target in chainTargets) {
                        val point = findMatch(screen, target)
                        if (point != null) {
                            lastChainClickTime = now
                            val clickPoint = point
                            mainHandler.post {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    ClickerAccessibilityService.instance?.performClick(
                                        clickPoint.x.toFloat(), clickPoint.y.toFloat()
                                    )
                                }
                            }
                            break
                        }
                    }
                }
            }
            chainWorkerHandler?.postDelayed(this, 80)
        }
    }

    fun startChainMatching(context: Context): Boolean {
        if (isChainMatching) return true
        stopSmartMatching()

        val files = TargetStorage.listTargets(context)
        if (files.isEmpty()) return false

        isChainMatching = true

        if (chainWorkerThread == null) {
            chainWorkerThread = HandlerThread("ChainWorker").apply { start() }
            chainWorkerHandler = Handler(chainWorkerThread!!.looper)
        }

        chainWorkerHandler?.post {
            chainTargets.clear()
            for (f in files) {
                val bmp = TargetStorage.loadBitmap(f)
                if (bmp != null) chainTargets.add(bmp)
            }
            if (chainTargets.isEmpty()) {
                isChainMatching = false
                return@post
            }
            chainWorkerHandler?.post(chainRunnable)
        }
        return true
    }

    fun stopChainMatching() {
        isChainMatching = false
        chainWorkerHandler?.removeCallbacks(chainRunnable)
    }

    // ===== خوارزمية المطابقة المشتركة =====

    private fun findMatch(screen: Bitmap, target: Bitmap): Point? {
        var targetSmall: Bitmap? = null
        var screenSmall: Bitmap? = null
        return try {
            val targetPixelsFull = IntArray(target.width * target.height)
            target.getPixels(targetPixelsFull, 0, target.width, 0, 0, target.width, target.height)
            if (!hasEnoughVariance(targetPixelsFull)) {
                return null
            }

            val scale = 0.22f
            val tw = (target.width * scale).toInt().coerceAtLeast(6)
            val th = (target.height * scale).toInt().coerceAtLeast(6)
            val sw = (screen.width * scale).toInt().coerceAtLeast(6)
            val sh = (screen.height * scale).toInt().coerceAtLeast(6)

            if (tw >= sw || th >= sh) return null

            targetSmall = Bitmap.createScaledBitmap(target, tw, th, true)
            screenSmall = Bitmap.createScaledBitmap(screen, sw, sh, true)

            val targetPixels = IntArray(tw * th)
            targetSmall.getPixels(targetPixels, 0, tw, 0, 0, tw, th)
            val screenPixels = IntArray(sw * sh)
            screenSmall.getPixels(screenPixels, 0, sw, 0, 0, sw, sh)

            val step = 4
            val sampleStep = 3

            var bestScore = Double.MAX_VALUE
            var secondBestScore = Double.MAX_VALUE
            var bestX = -1
            var bestY = -1

            var y = 0
            while (y <= sh - th) {
                var x = 0
                while (x <= sw - tw) {
                    var totalDiff = 0.0
                    var count = 0
                    var dy = 0
                    while (dy < th) {
                        var dx = 0
                        while (dx < tw) {
                            val tPixel = targetPixels[dy * tw + dx]
                            val sPixel = screenPixels[(y + dy) * sw + (x + dx)]

                            val tr = (tPixel shr 16) and 0xFF
                            val tg = (tPixel shr 8) and 0xFF
                            val tb = tPixel and 0xFF
                            val sr = (sPixel shr 16) and 0xFF
                            val sg = (sPixel shr 8) and 0xFF
                            val sb = sPixel and 0xFF

                            totalDiff += Math.abs(tr - sr) + Math.abs(tg - sg) + Math.abs(tb - sb)
                            count++
                            dx += sampleStep
                        }
                        dy += sampleStep
                    }
                    val avgDiff = totalDiff / count
                    if (avgDiff < bestScore) {
                        secondBestScore = bestScore
                        bestScore = avgDiff
                        bestX = x
                        bestY = y
                        if (bestScore < 5.0) break
                    } else if (avgDiff < secondBestScore) {
                        secondBestScore = avgDiff
                    }
                    x += step
                }
                if (bestScore < 5.0) break
                y += step
            }

            if (bestX == -1 || bestScore > 45.0) return null
            if (secondBestScore < bestScore * 1.08) return null

            val centerXSmall = bestX + tw / 2
            val centerYSmall = bestY + th / 2
            val fullX = (centerXSmall / scale).toInt()
            val fullY = (centerYSmall / scale).toInt()

            Point(fullX, fullY)
        } catch (e: Exception) {
            null
        } finally {
            targetSmall?.recycle()
            screenSmall?.recycle()
            if (screen !== latestBitmap) {
                screen.recycle()
            }
        }
    }

    private fun hasEnoughVariance(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        for (p in pixels) {
            sumR += (p shr 16) and 0xFF
            sumG += (p shr 8) and 0xFF
            sumB += p and 0xFF
        }
        val n = pixels.size
        val avgR = sumR / n; val avgG = sumG / n; val avgB = sumB / n
        var varSum = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            varSum += Math.abs(r - avgR) + Math.abs(g - avgG) + Math.abs(b - avgB)
        }
        val avgVariance = varSum / n
        return avgVariance > 3.0
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")

        if (data != null) {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    virtualDisplay?.release()
                    virtualDisplay = null
                }
            }, Handler(Looper.getMainLooper()))
            setupVirtualDisplay()
        }

        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return latestBitmap

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()

        val old = latestBitmap
        latestBitmap = bitmap
        if (old != null && old !== bitmap && !old.isRecycled) {
            old.recycle()
        }

        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تصوير الشاشة للتعرف الذكي",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Clicker")
            .setContentText("التعرف الذكي على الصور يعمل الآن")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSmartMatching()
        stopChainMatching()
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        chainWorkerThread?.quitSafely()
        chainWorkerThread = null
        chainWorkerHandler = null
        virtualDisplay?.release()
        mediaProjection?.stop()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
