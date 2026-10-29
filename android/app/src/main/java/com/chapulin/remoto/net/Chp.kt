package com.chapulin.remoto.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocolo CHP/1. Ver docs/PROTOCOLO.md.
 */
object Chp {
    const val MAGIC = 0x4843          // "CH"
    const val VERSION = 1
    const val HEADER_SIZE = 12
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    const val T_HELLO = 1
    const val T_HELLO_ACK = 2
    const val T_AUTH = 3
    const val T_AUTH_RESULT = 4
    const val T_VIDEO_FRAME = 5
    const val T_INPUT = 6
    const val T_PING = 7
    const val T_PONG = 8
    const val T_DISCONNECT = 9
    const val T_SET_QUALITY = 10

    const val INPUT_MOVE = 0
    const val INPUT_BUTTON_DOWN = 1
    const val INPUT_BUTTON_UP = 2
    const val INPUT_WHEEL = 3
    const val INPUT_KEY_DOWN = 4
    const val INPUT_KEY_UP = 5
    const val INPUT_CHAR = 6

    const val FLAG_EXTENDED = 0x0001

    const val CLIENT_ANDROID = 2

    fun packet(type: Int, seq: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(MAGIC.toShort())
        buffer.put(VERSION.toByte())
        buffer.put(type.toByte())
        buffer.putInt(payload.size)
        buffer.putInt(seq)
        buffer.put(payload)
        return buffer.array()
    }

    fun helloPayload(): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1)             // proto_min
            putShort(1)             // proto_max
            put(CLIENT_ANDROID.toByte())
            put(byteArrayOf(1, 0, 0)) // caps: jpeg
            putInt(0)               // screen_w (lo decide el host)
            putInt(0)               // screen_h
        }.array()

    fun inputPayload(
        kind: Int,
        button: Int,
        x: Int,
        y: Int,
        delta: Int,
        key: Int,
        flags: Int = 0
    ): ByteArray =
        ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(kind.toByte())
            put(button.toByte())
            putShort(x.coerceIn(0, 65535).toShort())
            putShort(y.coerceIn(0, 65535).toShort())
            putShort(delta.coerceIn(-32768, 32767).toShort())
            putShort(key.coerceIn(0, 65535).toShort())
            putInt(flags)
        }.array()

    fun qualityPayload(maxWidth: Int, quality: Int, fps: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(maxWidth.coerceIn(320, 3840).toShort())
            put(quality.coerceIn(10, 95).toByte())
            put(fps.coerceIn(1, 60).toByte())
        }.array()
}
