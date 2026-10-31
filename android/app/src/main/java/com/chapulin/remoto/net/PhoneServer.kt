package com.chapulin.remoto.net

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.chapulin.remoto.service.ChapulinAccessibilityService
import com.chapulin.remoto.service.ScreenCaptureService
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

object PhoneServer {

    private const val TAG = "ChpPhoneServer"

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clients = CopyOnWriteArrayList<ClientSession>()
    private var framesBroadcast = 0

    var activePin: String = "000000"
    var isClientConnected: Boolean = false
        private set
    var connectedClientIp: String? = null
        private set

    var onStatusChange: (() -> Unit)? = null

    private class ClientSession(
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream
    ) {
        val writeLock = Any()
        var downX = -1f
        var downY = -1f
        var lastX = -1f
        var lastY = -1f
        var isDown = false
        var downButton: Byte = 0
    }

    fun start(port: Int = 47474, pin: String) {
        activePin = pin
        if (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
            return
        }

        stop()
        isRunning = true

        thread(name = "chp-phone-server", isDaemon = true) {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "PhoneServer escuchando en el puerto $port")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    socket.tcpNoDelay = true
                    socket.soTimeout = 10000

                    thread(name = "chp-client-handler", isDaemon = true) {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PhoneServer acepto fallo: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        clients.forEach {
            try {
                it.socket.close()
            } catch (_: Exception) {}
        }
        clients.clear()
        isClientConnected = false
        connectedClientIp = null
        onStatusChange?.invoke()
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val header = ByteArray(Chp.HEADER_SIZE)
        input.readFully(header)

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.short.toInt() and 0xFFFF
        if (magic != Chp.MAGIC) throw IOException("Cabecera inválida")

        val version = buffer.get().toInt() and 0xFF
        if (version != Chp.VERSION) throw IOException("Versión inválida: $version")

        val type = buffer.get().toInt() and 0xFF
        val length = buffer.int
        buffer.int

        if (length < 0 || length > Chp.MAX_PAYLOAD) {
            throw IOException("Paquete demasiado grande: $length")
        }

        val payload = ByteArray(length)
        if (length > 0) input.readFully(payload)
        return type to payload
    }

    private fun handleClient(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val peer = try {
            socket.inetAddress?.hostAddress ?: "?"
        } catch (_: Exception) {
            "?"
        }
        Log.i(TAG, "cliente conectado desde $peer")

        try {
            val (helloType, _) = readPacket(input)
            if (helloType != Chp.T_HELLO) {
                Log.w(TAG, "primer paquete de $peer no es HELLO (tipo=$helloType), se corta")
                socket.close()
                return
            }

            val metrics = android.content.res.Resources.getSystem().displayMetrics
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels

            val ackPayload = ByteBuffer.allocate(24 + 7).order(ByteOrder.LITTLE_ENDIAN)
            ackPayload.putShort(1.toShort())
            ackPayload.putShort(47474.toShort())
            ackPayload.putInt(screenW)
            ackPayload.putInt(screenH)
            ackPayload.put(1.toByte())
            ackPayload.put(1.toByte())
            ackPayload.put(7.toByte())
            ackPayload.put(ByteArray(9))
            ackPayload.put("Android".toByteArray(Charsets.UTF_8))

            val ackPacket = Chp.packet(Chp.T_HELLO_ACK, 0, ackPayload.array())
            output.write(ackPacket)
            output.flush()

            val (authType, authBytes) = readPacket(input)
            if (authType != Chp.T_AUTH) {
                socket.close()
                return
            }

            val receivedPin = String(authBytes, Charsets.UTF_8).trim()
            if (receivedPin != activePin.trim()) {
                Log.w(TAG, "PIN incorrecto de $peer (recibido ${receivedPin.length} chars)")
                val failPacket = Chp.packet(Chp.T_AUTH_RESULT, 0, byteArrayOf(1))
                output.write(failPacket)
                output.flush()
                socket.close()
                return
            }

            Log.i(TAG, "PIN aceptado, autenticando a $peer")
            val okPacket = Chp.packet(Chp.T_AUTH_RESULT, 0, byteArrayOf(0))
            output.write(okPacket)
            output.flush()

            socket.soTimeout = 0

            val session = ClientSession(socket, input, output)
            clients.add(session)
            isClientConnected = true
            connectedClientIp = peer
            onStatusChange?.invoke()

            startPlaceholderThread(session)

            while (isRunning && !socket.isClosed) {
                val (t, payload) = readPacket(input)
                when (t) {
                    Chp.T_INPUT -> processInput(payload, session)
                    Chp.T_PING -> {
                        synchronized(session.writeLock) {
                            session.output.write(Chp.packet(Chp.T_PONG, 0, payload))
                            session.output.flush()
                        }
                    }
                    Chp.T_DISCONNECT -> break
                }
            }
        } catch (e: java.io.IOException) {
            Log.i(TAG, "conexion con $peer termino: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "handleClient($peer) fallo", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
            clients.removeAll { it.socket == socket }
            if (clients.isEmpty()) {
                isClientConnected = false
                connectedClientIp = null
            }
            onStatusChange?.invoke()
        }
    }

    private fun processInput(payload: ByteArray, session: ClientSession) {
        if (payload.size < 14) return
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val kind = buf.get().toInt() and 0xFF
        val button = buf.get()
        val normX = buf.short.toInt() and 0xFFFF
        val normY = buf.short.toInt() and 0xFFFF
        val delta = buf.short.toInt()
        val key = buf.short.toInt() and 0xFFFF

        val service = ChapulinAccessibilityService.instance ?: return
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()

        val curX = (normX / 65535f) * screenW
        val curY = (normY / 65535f) * screenH

        when (kind) {
            // MOVE
            0 -> if (session.isDown) { session.lastX = curX; session.lastY = curY }
            // BUTTON_DOWN
            1 -> {
                session.downX = curX; session.downY = curY
                session.lastX = curX; session.lastY = curY
                session.isDown = true
                session.downButton = button
                // Boton derecho -> long-press (contexto) en el punto exacto.
                if (button == 1.toByte()) service.dispatchLongPress(curX, curY, 600)
            }
            // BUTTON_UP
            2 -> {
                if (session.isDown) {
                    val dx = session.lastX - session.downX
                    val dy = session.lastY - session.downY
                    val dist = Math.hypot(dx.toDouble(), dy.toDouble())
                    // El long-press del boton derecho ya se disparo al bajar.
                    if (session.downButton == 0.toByte()) {
                        if (dist < 20) service.dispatchClick(session.downX, session.downY)
                        else service.dispatchSwipe(session.downX, session.downY, session.lastX, session.lastY, 120)
                    }
                    session.isDown = false
                    session.downButton = 0
                }
            }
            // WHEEL -> scroll vertical (delta en notches de 120)
            3 -> if (delta != 0) service.dispatchScroll(curX, curY, (delta / 120f) * 400f)
            // KEY_DOWN -> navegacion global. Acepta scancodes del PC (Keymap.kt,
            // Set 1) y los codigos legacy que ya usaba el cliente Android.
            4 -> {
                // Las flechas/Home/End viajan con el bit extended (0x8000); lo
                // separamos para casar solo con el scancode base.
                val baseKey = key and 0x7FFF
                when (baseKey) {
                    Keymap.ESC and 0x7FFF, 4 -> service.doBack()      // Esc / Back
                    Keymap.WIN and 0x7FFF, 3 -> service.doHome()      // Win / Home
                    Keymap.TAB and 0x7FFF, 187 -> service.doRecents() // Tab / Recents
                    26 -> service.doLock()
                }
            }
            // INPUT_CHAR -> escribir texto en el campo activo
            6 -> service.typeText(String(Character.toChars(key)))
        }
    }

    fun broadcastFrame(jpeg: ByteArray, width: Int, height: Int) {
        if (clients.isEmpty()) return

        val info = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        info.putShort(width.toShort())
        info.putShort(height.toShort())
        info.put(1.toByte())
        info.put(0.toByte())
        info.putShort(100.toShort())
        info.putInt((System.currentTimeMillis() and 0xFFFFFFFFL).toInt())

        val payload = ByteBuffer.allocate(12 + jpeg.size)
        payload.put(info.array())
        payload.put(jpeg)

        val packet = Chp.packet(Chp.T_VIDEO_FRAME, 0, payload.array())

        framesBroadcast++
        if (framesBroadcast == 1 || framesBroadcast % 90 == 0) {
            Log.i(TAG, "broadcast #$framesBroadcast (${jpeg.size} bytes, ${width}x$height) -> ${clients.size} cliente(s)")
        }

        for (c in clients) {
            try {
                synchronized(c.writeLock) {
                    c.output.write(packet)
                    c.output.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo enviar frame: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun startPlaceholderThread(session: ClientSession) {
        thread(isDaemon = true) {
            try {
                val w = 540
                val h = 1170
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                val paint = Paint().apply {
                    isAntiAlias = true
                }
                var pausado = false
                var enviados = 0

                while (isRunning && !session.socket.isClosed) {
                    if (ScreenCaptureService.isRunning) {
                        if (!pausado) {
                            Log.i(TAG, "placeholder en pausa: captura de pantalla activa")
                            pausado = true
                        }
                        // Keepalive: con la pantalla estatica la captura no genera
                        // frames, y el PC cortaria la conexion por timeout de lectura.
                        try {
                            val ping = ByteBuffer.allocate(4)
                                .putInt((System.currentTimeMillis() and 0xFFFFFFFFL).toInt())
                                .array()
                            synchronized(session.writeLock) {
                                session.output.write(Chp.packet(Chp.T_PING, 0, ping))
                                session.output.flush()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "keepalive fallo: ${e.message}")
                            throw e
                        }
                        Thread.sleep(1000)
                        continue
                    }
                    if (pausado) {
                        Log.i(TAG, "placeholder reanudado: captura detenida")
                        pausado = false
                    }

                    canvas.drawColor(Color.rgb(20, 26, 32))
                    paint.color = Color.rgb(156, 204, 101)
                    paint.textSize = 34f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("Chapulín Remoto", w / 2f, h / 2f - 60f, paint)

                    paint.textSize = 22f
                    paint.color = Color.rgb(200, 210, 220)
                    canvas.drawText("PC Conectada", w / 2f, h / 2f, paint)

                    paint.textSize = 18f
                    paint.color = Color.rgb(120, 144, 156)
                    canvas.drawText("Presiona 'Transmitir Pantalla' en tu celular", w / 2f, h / 2f + 50f, paint)

                    val stream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                    val bytes = stream.toByteArray()

                    synchronized(session.writeLock) {
                        val info = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                        info.putShort(w.toShort())
                        info.putShort(h.toShort())
                        info.put(1.toByte())
                        info.put(0.toByte())
                        info.putShort(100.toShort())
                        info.putInt((System.currentTimeMillis() and 0xFFFFFFFFL).toInt())

                        val payload = ByteBuffer.allocate(12 + bytes.size)
                        payload.put(info.array())
                        payload.put(bytes)

                        session.output.write(Chp.packet(Chp.T_VIDEO_FRAME, 0, payload.array()))
                        session.output.flush()
                    }

                    enviados++
                    if (enviados == 1) Log.i(TAG, "placeholder enviado al cliente (${bytes.size} bytes)")

                    Thread.sleep(1000)
                }
                bmp.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "placeholder fallo: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }
}
