package dev.local.newsdigest

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ChatMessage(val line: Int, val role: String, val text: String)

/**
 * Talks to server.py's /agent/... endpoints, which proxy to the claude-agents
 * daemon (a real tmux+claude interactive session per conversation) instead
 * of running claude itself - see server.py's CLAUDE_AGENTS_URL comment.
 *
 * Deliberately HTTP-poll-based, not a WebSocket, to get the same
 * resistance to a spotty roaming connection the standalone Claude Agents
 * app's ChatActivity has: every call (spawn/send/stream) is a stateless,
 * idempotent request against a conversation that lives entirely
 * server-side (tmux pane + transcript file) - a dropped connection just
 * means the next poll retries the exact same request, there is no
 * per-connection state on either end to lose or reconnect. The
 * session_id is persisted into the digest's own JSON file server-side
 * (see feed.py's get/set_chat_session), so the conversation survives this
 * Activity being destroyed, the app being killed, or reopening the same
 * article days later - it's a real part of that digest entry, not
 * throwaway per-screen state, and the exact same session_id can be
 * `claude --resume`d from any other tmux pane on this machine.
 */
class AgentChatClient(
    private val context: Context,
    private val onSessionReady: (sessionId: String, resumed: Boolean) -> Unit,
    private val onMessages: (List<ChatMessage>) -> Unit,
    private val onBusyChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    var sessionId: String? = null
        private set
    private var sinceCursor = 0
    private val polling = AtomicBoolean(false)
    private var pollThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Looks for a conversation already spawned for this digest topic (by
     * a previous chat, possibly from a previous app launch) and, if found,
     * loads its full history and resumes polling it. No-ops (silently)
     * if none exists yet - the caller's first send() will spawn one. */
    fun resume(runId: String, topic: String) {
        Thread {
            try {
                val path = "/agent/session?run_id=${ApiClient.encodeQuery(runId)}&topic=${ApiClient.encodeQuery(topic)}"
                val resp = JSONObject(ApiClient.get(context, path))
                val sid = if (resp.isNull("session_id")) null else resp.optString("session_id")
                if (sid == null) return@Thread
                sessionId = sid
                val messages = parseMessages(resp.optJSONArray("messages"))
                sinceCursor = (messages.maxOfOrNull { it.line } ?: -1) + 1
                mainHandler.post {
                    onSessionReady(sid, true)
                    if (messages.isNotEmpty()) onMessages(messages)
                }
                startPolling()
            } catch (e: Exception) {
                // No existing conversation, or the daemon is briefly
                // unreachable - either way, fine to just not resume;
                // sending a first message still works via spawn().
            }
        }.apply { isDaemon = true; name = "AgentChatResume"; start() }
    }

    /** Spawns (or, if one already exists for this topic - e.g. a race with
     * another device - transparently resumes) a real interactive claude
     * session primed with `initialText`, then starts polling it. */
    fun start(runId: String, topic: String, initialText: String) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("run_id", runId)
                    put("topic", topic)
                    put("text", initialText)
                    put("account", Settings.getAccount(context))
                }
                // Spawning a real Claude Code session (not just a keystroke
                // into an existing one) is slow - the daemon waits for tmux
                // to boot the TUI and the transcript file to appear.
                val resp = JSONObject(ApiClient.post(context, "/agent/spawn", body.toString(), readTimeoutMs = 30_000))
                if (resp.has("error")) {
                    mainHandler.post { onError(resp.optString("error")) }
                    return@Thread
                }
                val sid = resp.getString("session_id")
                val resumed = resp.optBoolean("resumed", false)
                sessionId = sid
                sinceCursor = 0
                mainHandler.post { onSessionReady(sid, resumed) }
                startPolling()
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: e.javaClass.simpleName) }
            }
        }.apply { isDaemon = true; name = "AgentChatSpawn"; start() }
    }

    /** Types a follow-up into the already-live session. The reply arrives
     * through the ongoing poll loop, same as any other new transcript
     * line - nothing turn-shaped needs to happen here beyond delivering
     * the text (the daemon durably queues it server-side if the tmux pane
     * momentarily isn't there to receive it, e.g. right after a spawn). */
    fun send(text: String) {
        val sid = sessionId ?: return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("session_id", sid)
                    put("text", text)
                    put("id", UUID.randomUUID().toString())
                }
                val resp = JSONObject(ApiClient.post(context, "/agent/send", body.toString(), readTimeoutMs = 15_000))
                if (resp.has("error")) mainHandler.post { onError(resp.optString("error")) }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: e.javaClass.simpleName) }
            }
        }.apply { isDaemon = true; name = "AgentChatSend"; start() }
    }

    private fun startPolling() {
        if (!polling.compareAndSet(false, true)) return
        pollThread = Thread {
            while (polling.get()) {
                val sid = sessionId
                if (sid == null) {
                    Thread.sleep(1000)
                    continue
                }
                try {
                    val path = "/agent/stream?session_id=${ApiClient.encodeQuery(sid)}&since=$sinceCursor&timeout=25"
                    val resp = JSONObject(ApiClient.get(context, path, readTimeoutMs = 35_000))
                    if (resp.has("error")) throw RuntimeException(resp.optString("error"))
                    val messages = parseMessages(resp.optJSONArray("messages"))
                    val busy = resp.optString("status", "") == "busy"
                    if (messages.isNotEmpty()) {
                        sinceCursor = (messages.maxOfOrNull { it.line } ?: (sinceCursor - 1)) + 1
                        // history from resume() already rendered every role;
                        // from here on, the user's own turns were already
                        // shown optimistically by whatever called send(), so
                        // only assistant/tool text needs delivering live -
                        // otherwise every message this client itself sends
                        // would render twice (once locally, once echoed
                        // back through this same poll).
                        val delta = messages.filter { it.role != "user" }
                        mainHandler.post {
                            onBusyChanged(busy)
                            if (delta.isNotEmpty()) onMessages(delta)
                        }
                    } else {
                        mainHandler.post { onBusyChanged(busy) }
                    }
                } catch (e: Exception) {
                    // Exactly the retry a dropped/roaming connection needs -
                    // no state to rebuild, just try the same idempotent
                    // long-poll again shortly.
                    try {
                        Thread.sleep(3000)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }.apply { isDaemon = true; name = "AgentChatPoll"; start() }
    }

    fun close() {
        polling.set(false)
        pollThread?.interrupt()
    }

    private fun parseMessages(arr: org.json.JSONArray?): List<ChatMessage> {
        if (arr == null) return emptyList()
        val out = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            out.add(ChatMessage(m.optInt("line"), m.optString("role"), m.optString("text")))
        }
        return out
    }
}
