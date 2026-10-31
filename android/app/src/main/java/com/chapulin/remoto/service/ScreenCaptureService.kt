package com.chapulin.remoto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chapulin.remoto.R
import com.chapulin.remoto.net.PhoneServer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "chapulin_capture_channel"
        private const val NOTIFICATION_ID = 47474
        private const val TAG = "ChpCapture"

        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastFrameTime = 0L
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private var framesSent = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_START) {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode != 0 && resultData != null) {
                startCapture(resultCode, resultData)
            }
        } else if (action == ACTION_STOP) {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (isRunning) {
            Log.i(TAG, "startCapture ignorado: ya hay una captura activa")
            return
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection fallo", t)
            null
        } ?: return
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection detenido por el sistema")
                stopCapture()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val rawWidth = metrics.widthPixels
        val rawHeight = metrics.heightPixels
        val scale = 0.5f
        val width = (((rawWidth * scale).toInt() / 16) * 16).coerceAtLeast(16)
        val height = (((rawHeight * scale).toInt() / 16) * 16).coerceAtLeast(16)
        val dpi = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "ChapulinCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        // Convierte una Image RGBA_8888 en Bitmap. Tolera rowStride con padding
        // (en ese caso copyPixelsFromBuffer directo lanzaria BufferUnderflow y
        // el frame se pierde en silencio).
        fun imageToBitmap(buffer: ByteBuffer, ps: Int, rs: Int): Bitmap? {
            val needed = (height - 1) * rs + width * ps
            if (rs <= 0 || buffer.capacity() < needed) {
                Log.e(TAG, "buffer corto: cap=${buffer.capacity()} necesarios=$needed (ps=$ps rs=$rs)")
                return null
            }

            // Rapido: filas contiguas -> copia directa.
            if (ps == 4 && rs == width * 4) {
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val dup = buffer.duplicate()
                dup.position(0)
                bmp.copyPixelsFromBuffer(dup)
                return bmp
            }

            // Con padding de fila: conversion explicita RGBA -> ARGB (LE).
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val row = IntArray(width)
            if (ps == 4 && rs % 4 == 0) {
                val ib = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                for (y in 0 until height) {
                    ib.position(y * (rs / 4))
                    ib.get(row, 0, width)
                    for (x in 0 until width) {
                        val v = row[x]
                        row[x] = (v and 0xFF000000.toInt()) or
                            ((v and 0xFF) shl 16) or
                            (v and 0x0000FF00) or
                            ((v ushr 16) and 0xFF)
                    }
                    bmp.setPixels(row, 0, width, 0, y, width, 1)
                }
            } else {
                for (y in 0 until height) {
                    val base = y * rs
                    for (x in 0 until width) {
                        val i = base + x * ps
                        val r = buffer.get(i).toInt() and 0xFF
                        val g = buffer.get(i + 1).toInt() and 0xFF
                        val b = buffer.get(i + 2).toInt() and 0xFF
                        val a = if (ps >= 4) buffer.get(i + 3).toInt() and 0xFF else 0xFF
                        row[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    bmp.setPixels(row, 0, width, 0, y, width, 1)
                }
            }
            return bmp
        }

        // La compresion JPEG corre en un hilo propio: en el hilo principal
        // congelaba la UI y en equipos lentos directamente no llegaban frames.
        if (encoderThread == null) {
            encoderThread = HandlerThread("chp-encoder").also { it.start() }
            encoderHandler = Handler(encoderThread!!.looper)
        }
        framesSent = 0

        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (t: Throwable) {
                Log.e(TAG, "acquireLatestImage fallo", t)
                null
            } ?: return@setOnImageAvailableListener

            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameTime >= 33) {
                    lastFrameTime = now

                    val planes = image.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        if (pixelStride > 0 && rowStride > 0) {
                            val bmp = imageToBitmap(buffer, pixelStride, rowStride)
                            if (bmp != null) {
                                val stream = ByteArrayOutputStream()
                                val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 65, stream)
                                bmp.recycle()
                                if (ok) {
                                    val bytes = stream.toByteArray()
                                    PhoneServer.broadcastFrame(bytes, width, height)
                                    framesSent++
                                    if (framesSent == 1 || framesSent % 90 == 0) {
                                        Log.i(TAG, "frame #$framesSent enviado (${bytes.size} bytes, ${width}x$height)")
                                    }
                                } else {
                                    Log.e(TAG, "Bitmap.compress JPEG devolvio false")
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "fallo al procesar frame #$framesSent", t)
            } finally {
                try {
                    image.close()
                } catch (_: Throwable) {}
            }
        }, encoderHandler)

        isRunning = true
        Log.i(TAG, "captura activa: pantalla ${rawWidth}x$rawHeight -> ${width}x$height dpi=$dpi")
        encoderHandler?.postDelayed({
            if (isRunning && framesSent == 0) {
                Log.w(TAG, "3.5s sin ningun frame: el ImageReader no esta entregando imagenes")
            }
        }, 3500)
    }

    private fun stopCapture() {
        isRunning = false
        Log.i(TAG, "stopCapture (frames enviados: $framesSent)")
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {}
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Throwable) {}
        imageReader = null

        encoderHandler = null
        encoderThread?.quitSafely()
        encoderThread = null

        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {}
        mediaProjection = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transmisión de Pantalla",
                NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chapulín Remoto Activo")
            .setContentText("Transmitiendo pantalla a tu PC...")
            .setSmallIcon(R.drawable.logo_chapulin)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
