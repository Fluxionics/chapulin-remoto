package com.chapulin.remoto.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

data class HostTarget(val address: String, val port: Int, val pin: String)

/**
 * Cliente CHP/1. [connect] bloquea: llamalo siempre desde Dispatchers.IO.
 */
class RemoteClient {

    interface Listener {
        fun onConnected(hostName: String)
        fun onFrame(bitmap: Bitmap)
        fun onClosed(reason: String)
    }

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private val writeLock = Any()
    private val sequence = AtomicInteger(1)

    // Cola de salida: la UI solo encola, un hilo dedicado escribe en el socket.
    // Sin esto, tocar la pantalla lanza NetworkOnMainThreadException.
    private val outbox = LinkedBlockingQueue<ByteArray>()
    private var writer: Thread? = null

    private companion object {
        const val MAX_QUEUED_PACKETS = 512
    }

    @Volatile
    private var closed = false

    @Volatile
    private var connected = false

    fun connect(host: String, port: Int, pin: String, listener: Listener) {
        val tcp = Socket()
        tcp.tcpNoDelay = true
        tcp.soTimeout = 15000
        tcp.connect(InetSocketAddress(host, port), 5000)

        socket = tcp
        val input = DataInputStream(BufferedInputStream(tcp.getInputStream()))
        val out = BufferedOutputStream(tcp.getOutputStream())
        output = out

        writeDirect(Chp.packet(Chp.T_HELLO, 0, Chp.helloPayload()))

        val (ackType, ackPayload) = readPacket(input)
        if (ackType != Chp.T_HELLO_ACK) {
            throw IOException("Respuesta inesperada del host (tipo $ackType)")
        }
        val hostNameLength = if (ackPayload.size > 14) ackPayload[14].toInt() and 0xFF else 0
        val hostName =
            if (hostNameLength > 0 && ackPayload.size >= 24 + hostNameLength) {
                String(ackPayload, 24, hostNameLength, Charsets.UTF_8)
            } else {
                "PC"
            }

        writeDirect(Chp.packet(Chp.T_AUTH, 0, pin.toByteArray(Charsets.UTF_8)))

        val (authType, authPayload) = readPacket(input)
        if (authType != Chp.T_AUTH_RESULT) {
            throw IOException("Respuesta inesperada al PIN (tipo $authType)")
        }
        when (authPayload.firstOrNull()?.toInt() ?: -1) {
            0 -> Unit
            1 -> throw IOException("PIN incorrecto")
            else -> throw IOException("Acceso bloqueado por el PC")
        }

        connected = true
        tcp.soTimeout = 0
        startWriter()
        listener.onConnected(hostName)

        thread(name = "chp-reader", isDaemon = true) { readLoop(input, listener) }
        thread(name = "chp-ping", isDaemon = true) { pingLoop() }
    }

    private fun pingLoop() {
        try {
            while (!closed && connected) {
                Thread.sleep(5000)
                if (closed || !connected) break
                val stamp = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
                val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(stamp).array()
                sendPacket(Chp.T_PING, payload)
            }
        } catch (ignored: InterruptedException) {
        } catch (ignored: IOException) {
        }
    }

    private fun readLoop(input: DataInputStream, listener: Listener) {
        try {
            while (!closed) {
                val (type, payload) = readPacket(input)
                when (type) {
                    Chp.T_VIDEO_FRAME -> {
                        if (payload.size <= 12) continue
                        val bitmap =
                            BitmapFactory.decodeByteArray(payload, 12, payload.size - 12)
                        if (bitmap != null) listener.onFrame(bitmap)
                    }

                    Chp.T_PING -> sendPacket(Chp.T_PONG, payload)

                    Chp.T_DISCONNECT -> {
                        val reason = payload.firstOrNull()?.toInt() ?: 0
                        throw IOException(disconnectText(reason))
                    }
                }
            }
        } catch (t: Throwable) {
            if (!closed) {
                connected = false
                listener.onClosed(t.message ?: "Conexión perdida")
            }
        } finally {
            close()
        }
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val header = ByteArray(Chp.HEADER_SIZE)
        input.readFully(header)

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.short.toInt() and 0xFFFF
        if (magic != Chp.MAGIC) throw IOException("Cabecera de protocolo inválida")

        val version = buffer.get().toInt() and 0xFF
        if (version != Chp.VERSION) throw IOException("Versión de protocolo no soportada: $version")

        val type = buffer.get().toInt() and 0xFF
        val length = buffer.int
        buffer.int // seq

        if (length < 0 || length > Chp.MAX_PAYLOAD) {
            throw IOException("Paquete demasiado grande: $length")
        }

        val payload = ByteArray(length)
        if (length > 0) input.readFully(payload)
        return type to payload
    }

    private fun startWriter() {
        writer = thread(name = "chp-writer", isDaemon = true) {
            try {
                while (!closed) {
                    val bytes = outbox.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    val stream = output ?: break
                    synchronized(writeLock) {
                        stream.write(bytes)
                        stream.flush()
                    }
                }
            } catch (ignored: InterruptedException) {
            } catch (ignored: IOException) {
                if (!closed) close()
            }
        }
    }

    private fun sendPacket(type: Int, payload: ByteArray) {
        if (closed) return
        val bytes = Chp.packet(type, sequence.getAndIncrement(), payload)

        // Si la cola se desborda (red lenta), tiramos lo mas viejo antes que
        // bloquear la UI o comernos la memoria.
        if (outbox.size >= MAX_QUEUED_PACKETS) outbox.poll()
        outbox.offer(bytes)
    }

    /** Escritura directa, solo para el handshake (ya corre en un hilo de IO). */
    private fun writeDirect(bytes: ByteArray) {
        val stream = output ?: return
        synchronized(writeLock) {
            stream.write(bytes)
            stream.flush()
        }
    }

    fun sendMove(x: Int, y: Int) {
        sendPacket(Chp.T_INPUT, Chp.inputPayload(Chp.INPUT_MOVE, 0, x, y, 0, 0))
    }

    fun sendMouseDown(button: Int, x: Int, y: Int) {
        sendPacket(Chp.T_INPUT, Chp.inputPayload(Chp.INPUT_BUTTON_DOWN, button, x, y, 0, 0))
    }

    fun sendMouseUp(button: Int, x: Int, y: Int) {
        sendPacket(Chp.T_INPUT, Chp.inputPayload(Chp.INPUT_BUTTON_UP, button, x, y, 0, 0))
    }

    fun sendClick(button: Int, x: Int, y: Int) {
        sendMouseDown(button, x, y)
        sendMouseUp(button, x, y)
    }

    fun sendWheel(delta: Int) {
        sendPacket(Chp.T_INPUT, Chp.inputPayload(Chp.INPUT_WHEEL, 0, 0, 0, delta, 0))
    }

    fun sendKey(scancode: Int, down: Boolean, flags: Int = 0) {
        val kind = if (down) Chp.INPUT_KEY_DOWN else Chp.INPUT_KEY_UP
        sendPacket(Chp.T_INPUT, Chp.inputPayload(kind, 0, 0, 0, 0, scancode and 0xFFFF, flags))
    }

    fun sendText(codeUnit: Int) {
        sendPacket(Chp.T_INPUT, Chp.inputPayload(Chp.INPUT_CHAR, 0, 0, 0, 0, codeUnit))
    }

    fun setQuality(maxWidth: Int, quality: Int, fps: Int) {
        sendPacket(Chp.T_SET_QUALITY, Chp.qualityPayload(maxWidth, quality, fps))
    }

    fun close() {
        if (closed) return
        closed = true
        connected = false
        outbox.clear()
        writer?.interrupt()
        try {
            socket?.close()
        } catch (ignored: IOException) {
        }
        socket = null
        output = null
    }

    private fun disconnectText(reason: Int): String = when (reason) {
        1 -> "Error de protocolo"
        2 -> "Autenticación rechazada"
        3 -> "El PC ya tiene una sesión activa"
        4 -> "Tiempo de espera agotado"
        5 -> "El host se está cerrando"
        else -> "El PC cerró la sesión"
    }
}
