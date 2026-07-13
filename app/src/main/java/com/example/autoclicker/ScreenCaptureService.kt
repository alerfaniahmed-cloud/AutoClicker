package com.example.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

class ScreenCaptureService : Service() {

    companion object {
        var instance: ScreenCaptureService? = null
        var mediaProjection: MediaProjection? = null
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIF_ID = 1001

        var targetBitmap: Bitmap? = null
        var isMatching = false
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestBitmap: Bitmap? = null

    private val matchHandler = Handler(Looper.getMainLooper())
    private var lastClickTime = 0L

    private val matchRunnable = object : Runnable {
        override fun run() {
            if (!isMatching) return
            val target = targetBitmap
            val screen = captureScreen()
            if (target != null && screen != null) {
                val point = findMatch(screen, target)
                if (point != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime > 1500) {
                        lastClickTime = now
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            ClickerAccessibilityService.instance?.performClick(
                                point.x.toFloat(), point.y.toFloat()
                            )
                        }
                    }
                }
            }
            matchHandler.postDelayed(this, 700)
        }
    }

    fun startSmartMatching() {
        if (isMatching) return
        isMatching = true
        matchHandler.post(matchRunnable)
    }

    fun stopSmartMatching() {
        isMatching = false
        matchHandler.removeCallbacks(matchRunnable)
    }

    private fun findMatch(screen: Bitmap, target: Bitmap): Point? {
        return try {
            val screenMat = Mat()
            Utils.bitmapToMat(screen, screenMat)
            val targetMat = Mat()
            Utils.bitmapToMat(target, targetMat)

            Imgproc.cvtColor(screenMat, screenMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(targetMat, targetMat, Imgproc.COLOR_RGBA2RGB)

            val resultCols = screenMat.cols() - targetMat.cols() + 1
            val resultRows = screenMat.rows() - targetMat.rows() + 1
            if (resultCols <= 0 || resultRows <= 0) return null

            val result = Mat(resultRows, resultCols, CvType.CV_32FC1)
            Imgproc.matchTemplate(screenMat, targetMat, result, Imgproc.TM_CCOEFF_NORMED)

            val mmr = Core.minMaxLoc(result)
            if (mmr.maxVal < 0.75) return null

            Point(
                mmr.maxLoc.x + targetMat.cols() / 2.0,
                mmr.maxLoc.y + targetMat.rows() / 2.0
            )
        } catch (e: Exception) {
            null
        }
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

        latestBitmap = bitmap
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
        virtualDisplay?.release()
        mediaProjection?.stop()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
