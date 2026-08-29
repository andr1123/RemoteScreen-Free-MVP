package com.example.remotescreen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        val notification = NotificationCompat.Builder(
            this,
            "screen"
        )
            .setContentTitle("RemoteScreen aktif")
            .setContentText("Screen sharing sedang berjalan")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                7,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(7, notification)
        }

        val resultCode =
            intent?.getIntExtra("resultCode", -1) ?: -1

        val data =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(
                    "resultData",
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("resultData")
            }

        val wsUrl =
            intent?.getStringExtra("wsUrl") ?: ""

        if (resultCode < 0 || data == null || wsUrl.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(
            resultCode,
            data,
            wsUrl
        )

        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent,
        wsUrl: String
    ) {

        if (running.getAndSet(true)) {
            return
        }

        val manager =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        projection =
            manager.getMediaProjection(
                resultCode,
                data
            )

        val metrics = DisplayMetrics()

        val windowManager =
            getSystemService(
                Context.WINDOW_SERVICE
            ) as android.view.WindowManager

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val originalWidth = metrics.widthPixels
        val originalHeight = metrics.heightPixels

        val width =
            minOf(originalWidth, 720)

        val height =
            (originalHeight.toFloat() *
                    width.toFloat() /
                    originalWidth.toFloat())
                .toInt()

        reader = ImageReader.newInstance(
            width,
            height,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )

        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            },
            null
        )

        display =
            projection?.createVirtualDisplay(
                "RemoteScreen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                null
            )

        val client = OkHttpClient()

        val request =
            Request.Builder()
                .url(wsUrl)
                .build()

        socket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response
                    ) {
                        captureLoop()
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        stopCapture()
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {
                        stopCapture()
                    }
                }
            )
    }

    private fun captureLoop() {

        executor.execute {

            while (running.get()) {

                val image =
                    reader?.acquireLatestImage()

                if (image == null) {
                    Thread.sleep(80)
                    continue
                }

                try {

                    val plane =
                        image.planes[0]

                    val pixelStride =
                        plane.pixelStride

                    val rowStride =
                        plane.rowStride

                    val rowPadding =
                        rowStride -
                                pixelStride *
                                image.width

                    val bitmapWidth =
                        image.width +
                                rowPadding /
                                pixelStride

                    val bitmap =
                        Bitmap.createBitmap(
                            bitmapWidth,
                            image.height,
                            Bitmap.Config.ARGB_8888
                        )

                    bitmap.copyPixelsFromBuffer(
                        plane.buffer
                    )

                    val cropped =
                        if (bitmapWidth != image.width) {
                            Bitmap.createBitmap(
                                bitmap,
                                0,
                                0,
                                image.width,
                                image.height
                            )
                        } else {
                            bitmap
                        }

                    val output =
                        ByteArrayOutputStream()

                    cropped.compress(
                        Bitmap.CompressFormat.JPEG,
                        45,
                        output
                    )

                    val bytes =
                        output.toByteArray()

                    socket?.send(
                        ByteString.of(*bytes)
                    )

                    if (cropped !== bitmap) {
                        cropped.recycle()
                    }

                    bitmap.recycle()

                    output.close()

                } catch (_: Exception) {

                    // Abaikan frame yang gagal diproses.

                } finally {

                    image.close()
                }

                Thread.sleep(120)
            }
        }
    }

    private fun stopCapture() {

        if (!running.getAndSet(false)) {
            return
        }

        try {
            socket?.close(
                1000,
                "stopped"
            )
        } catch (_: Exception) {
        }

        try {
            display?.release()
        } catch (_: Exception) {
        }

        try {
            reader?.close()
        } catch (_: Exception) {
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        display = null
        reader = null
        projection = null
        socket = null
    }

    override fun onDestroy() {

        stopCapture()

        executor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            val channel =
                NotificationChannel(
                    "screen",
                    "RemoteScreen",
                    NotificationManager.IMPORTANCE_LOW
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }
}
