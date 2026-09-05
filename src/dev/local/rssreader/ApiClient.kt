package dev.local.rssreader

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Plain HttpURLConnection, no third-party HTTP library - matches the rest
 * of this project's zero-dependency build. Every call here is blocking and
 * must run off the main thread. Host/ports/token come from Settings
 * (configured once on first launch), not compile-time constants - see
 * Settings.kt for why. */
object ApiClient {
    private const val TIMEOUT_MS = 15000

    class ApiException(val code: Int, message: String) : Exception(message)

    // Not a secret rotation concern the way the relay token is - same
    // trust model as every other service in this WireGuard mesh: the
    // tunnel is the real credential, this header only defends against a
    // browser on another peer being tricked into hitting the port (see
    // server/server.py's security_middleware). Fine as a fixed constant.
    private const val PEER_AGENT_HEADER = "X-Peer-Agent"

    fun ttsGet(context: Context, path: String): String =
        get(Settings.getHost(context), Settings.getTtsPort(context), path) { conn ->
            conn.setRequestProperty(PEER_AGENT_HEADER, "1")
        }

    fun relayGet(context: Context, path: String): String =
        get(Settings.getHost(context), Settings.getRelayPort(context), path) { conn ->
            conn.setRequestProperty("X-Claude-Relay-Token", Settings.getRelayToken(context) ?: "")
        }

    fun relayPost(context: Context, path: String, body: JSONObject): String =
        post(Settings.getHost(context), Settings.getRelayPort(context), path, body) { conn ->
            conn.setRequestProperty("X-Claude-Relay-Token", Settings.getRelayToken(context) ?: "")
        }

    fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun get(host: String, port: Int, path: String, configure: (HttpURLConnection) -> Unit): String {
        val url = URL("http://$host:$port$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        configure(conn)
        return readResponse(conn)
    }

    private fun post(host: String, port: Int, path: String, body: JSONObject, configure: (HttpURLConnection) -> Unit): String {
        val url = URL("http://$host:$port$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        configure(conn)
        conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        return readResponse(conn)
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw ApiException(code, text)
        return text
    }
}
