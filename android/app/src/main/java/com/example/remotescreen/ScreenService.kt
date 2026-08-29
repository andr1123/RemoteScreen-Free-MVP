package com.example.remotescreen

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScreenService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var socket: WebSocket? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = NotificationCompat.Builder(this, "screen")
            .setContentTitle("RemoteScreen aktif")
            .setContentText("Screen sharing sedang berjalan")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true).build()

        if (Build.VERSION.SDK_INT >= 29)
            startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, notification)

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("resultData")
        val wsUrl = intent?.getStringExtra("wsUrl") ?: ""
        if (resultCode < 0 || data == null || wsUrl.isBlank()) { stopSelf(); return START_NOT_STICKY }

        startCapture(resultCode, data, wsUrl)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent, wsUrl: String) {
        if (running.getAndSet(true)) return

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = minOf(metrics.widthPixels, 720)
        val height = (metrics.heightPixels.toFloat() * width / metrics.widthPixels).toInt()

        reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, null)

        display = projection?.createVirtualDisplay(
            "RemoteScreen", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, null
        )

        socket = OkHttpClient().newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { captureLoop() }
            })
    }

    private fun captureLoop() {
        executor.execute {
            while (running.get()) {
                val image = reader?.acquireLatestImage() ?: run { Thread.sleep(80); continue }
                try {
                    val plane = image.planes[0]
                    val bitmap = Bitmap.createBitmap(
                        image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride,
                        image.height, Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                    val out = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 45, out)
                    socket?.send(ByteString.of(*out.toByteArray()))
                    cropped.recycle()
                    bitmap.recycle()
                } finally { image.close() }
                Thread.sleep(120)
            }
        }
    }

    private fun stopCapture() {
        if (!running.getAndSet(false)) return
        try { socket?.close(1000, "stopped") } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; reader = null; projection = null
    }

    override fun onDestroy() {
        stopCapture()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("screen", "RemoteScreen", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
