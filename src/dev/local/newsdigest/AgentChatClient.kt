package dev.local.newsdigest

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

data class CachedSentence(val text: String, val words: List<WordTiming>, val pcm: ByteArray, val sampleRate: Int)

/**
 * One WebSocket connection per turn against /agent/chat - the server has
 * no per-connection state that needs to persist between turns, since
 * --session-id (first turn) / --resume (every follow-up) carries
 * continuity server-side instead. See server.py's agent_chat doc comment
 * for why this is a fresh `claude --print` subprocess per turn rather than
 * one long-lived interactive session.
 */
class AgentChatClient(
    private val context: Context,
    private val onSessionId: (String) -> Unit,
    private val onTextDelta: (delta: String) -> Unit,
    private val onSentenceReady: (CachedSentence) -> Unit,
    private val onTurnDone: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var ws: WebSocketClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(text: String) {
        connectAndSend(
            JSONObject().apply {
                put("cmd", "start")
                put("account", Settings.getAccount(context))
                put("text", text)
                put("engine", Settings.getTtsEngine(context))
            },
        )
    }

    fun continueChat(sessionId: String, text: String) {
        connectAndSend(
            JSONObject().apply {
                put("cmd", "continue")
                put("session_id", sessionId)
                put("account", Settings.getAccount(context))
                put("text", text)
                put("engine", Settings.getTtsEngine(context))
            },
        )
    }

    private fun connectAndSend(payload: JSONObject) {
        Thread {
            val client = WebSocketClient(
                Settings.getHost(context),
                Settings.getTtsPort(context),
                "/agent/chat",
                mapOf("X-Peer-Agent" to "1"),
            )
            ws = client
            client.connect(
                object : WebSocketClient.Listener {
                    private var pendingMeta: JSONObject? = null

                    override fun onOpen() {
                        client.sendText(payload.toString())
                    }

                    override fun onText(text: String) {
                        val obj = JSONObject(text)
                        when (obj.optString("type")) {
                            "session" -> mainHandler.post { onSessionId(obj.optString("session_id")) }
                            "text_delta" -> mainHandler.post { onTextDelta(obj.optString("text")) }
                            "sentence" -> pendingMeta = obj
                            "turn_done" -> {
                                client.close()
                                mainHandler.post { onTurnDone() }
                            }
                            "error" -> {
                                client.close()
                                mainHandler.post { onError(obj.optString("message")) }
                            }
                        }
                    }

                    override fun onBinary(data: ByteArray) {
                        val meta = pendingMeta ?: return
                        val words = mutableListOf<WordTiming>()
                        meta.optJSONArray("words")?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val w = arr.getJSONObject(i)
                                words.add(WordTiming(w.getString("word"), w.getInt("start_ms"), w.getInt("end_ms")))
                            }
                        }
                        val sentence = CachedSentence(meta.getString("text"), words, data, meta.getInt("sample_rate"))
                        mainHandler.post { onSentenceReady(sentence) }
                    }

                    override fun onFailure(error: Throwable) {
                        mainHandler.post { onError(error.message ?: error.javaClass.simpleName) }
                    }
                },
            )
        }.apply { isDaemon = true; name = "AgentChatWs"; start() }
    }

    fun close() {
        ws?.close()
    }
}
