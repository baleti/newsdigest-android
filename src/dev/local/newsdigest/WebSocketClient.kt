package dev.local.newsdigest

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Minimal hand-rolled RFC 6455 WebSocket client - no OkHttp or other
 * library, matching this project's zero-dependency build (java.net has no
 * WebSocket client of its own). Blocking/synchronous: call connect() on a
 * background thread; it doesn't return until the connection closes, and
 * send*()/close() are safe to call from other threads meanwhile.
 *
 * Not general-purpose - built specifically against this app's own
 * WireGuard-only server, which never fragments a logical message across
 * multiple WebSocket frames and never sends anything mid-stream that isn't
 * text/binary/close, so continuation frames and ping/pong are handled
 * defensively rather than to the full spec.
 */
class WebSocketClient(
    private val host: String,
    private val port: Int,
    private val path: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) {
    interface Listener {
        fun onOpen() {}
        fun onText(text: String) {}
        fun onBinary(data: ByteArray) {}
        fun onClosed() {}
        fun onFailure(error: Throwable) {}
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var running = false
    private lateinit var output: OutputStream
    private lateinit var input: DataInputStream

    /** Blocks until the connection ends. Run this on a background thread. */
    fun connect(listener: Listener) {
        try {
            val sock = Socket(host, port)
            sock.tcpNoDelay = true
            socket = sock
            output = sock.getOutputStream()
            input = DataInputStream(BufferedInputStream(sock.getInputStream()))

            val keyBytes = ByteArray(16).also { Random.nextBytes(it) }
            val key = Base64.getEncoder().encodeToString(keyBytes)

            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $host:$port\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                for ((k, v) in extraHeaders) append("$k: $v\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()

            val responseLines = mutableListOf<String>()
            while (true) {
                val line = readLine() ?: throw IOException("connection closed during handshake")
                if (line.isEmpty()) break
                responseLines.add(line)
            }
            if (responseLines.isEmpty() || !responseLines[0].contains(" 101 ")) {
                throw IOException("handshake failed: ${responseLines.firstOrNull()}")
            }
            val acceptHeader = responseLines
                .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
            if (acceptHeader != computeAcceptKey(key)) {
                throw IOException("handshake failed: bad Sec-WebSocket-Accept")
            }

            running = true
            listener.onOpen()
            readLoop(listener)
        } catch (e: Throwable) {
            val wasRunning = running
            running = false
            if (wasRunning || socket != null) listener.onFailure(e)
        }
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    private fun computeAcceptKey(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest((key + magic).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(hash)
    }

    private fun readLoop(listener: Listener) {
        val messageBuffer = ByteArrayOutputStream()
        var messageOpcode = -1
        try {
            while (running) {
                val b0 = input.readUnsignedByte()
                val fin = (b0 and 0x80) != 0
                val opcode = b0 and 0x0F

                val b1 = input.readUnsignedByte()
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    len = (input.readUnsignedByte().toLong() shl 8) or input.readUnsignedByte().toLong()
                } else if (len == 127L) {
                    len = 0L
                    repeat(8) { len = (len shl 8) or input.readUnsignedByte().toLong() }
                }
                val maskKey = if (masked) ByteArray(4).also { input.readFully(it) } else null
                val payload = ByteArray(len.toInt())
                input.readFully(payload)
                if (maskKey != null) {
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }

                when (opcode) {
                    0x1, 0x2 -> {
                        messageOpcode = opcode
                        messageBuffer.reset()
                        messageBuffer.write(payload)
                        if (fin) {
                            deliver(listener, messageOpcode, messageBuffer.toByteArray())
                            messageBuffer.reset()
                        }
                    }
                    0x0 -> {
                        messageBuffer.write(payload)
                        if (fin) {
                            deliver(listener, messageOpcode, messageBuffer.toByteArray())
                            messageBuffer.reset()
                        }
                    }
                    0x8 -> {
                        running = false
                        listener.onClosed()
                        return
                    }
                    0x9 -> sendFrame(0xA, payload)
                    0xA -> { /* pong: ignore */ }
                }
            }
        } catch (e: Throwable) {
            if (running) {
                running = false
                listener.onFailure(e)
            }
        } finally {
            running = false
        }
    }

    private fun deliver(listener: Listener, opcode: Int, data: ByteArray) {
        if (opcode == 0x1) listener.onText(String(data, Charsets.UTF_8)) else listener.onBinary(data)
    }

    fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

    fun sendBinary(data: ByteArray) = sendFrame(0x2, data)

    @Synchronized
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        val maskBit = 0x80
        when {
            payload.size < 126 -> header.write(maskBit or payload.size)
            payload.size <= 0xFFFF -> {
                header.write(maskBit or 126)
                header.write((payload.size shr 8) and 0xFF)
                header.write(payload.size and 0xFF)
            }
            else -> {
                header.write(maskBit or 127)
                for (i in 7 downTo 0) header.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        val maskKey = ByteArray(4).also { Random.nextBytes(it) }
        header.write(maskKey)
        val masked = ByteArray(payload.size)
        for (i in payload.indices) masked[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()

        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }

    fun close() {
        if (!running) return
        running = false
        try { sendFrame(0x8, ByteArray(0)) } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}
