package dev.local.rssreader

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Plain HttpURLConnection, no third-party HTTP library - matches the rest
 * of this project's zero-dependency build. Every call here is blocking and
 * must run off the main thread. Host/port come from Settings (configured
 * once on first launch), not compile-time constants - see Settings.kt. */
object ApiClient {
    private const val TIMEOUT_MS = 15000

    class ApiException(val code: Int, message: String) : Exception(message)

    // Not a secret rotation concern the way a bearer token is - same trust
    // model as every other service in this WireGuard mesh: the tunnel is
    // the real credential, this header only defends against a browser on
    // another peer being tricked into hitting the port (see
    // server/server.py's security_middleware). Fine as a fixed constant.
    private const val PEER_AGENT_HEADER = "X-Peer-Agent"

    fun get(context: Context, path: String): String {
        val url = URL("http://${Settings.getHost(context)}:${Settings.getTtsPort(context)}$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty(PEER_AGENT_HEADER, "1")
        return readResponse(conn)
    }

    fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw ApiException(code, text)
        return text
    }
}
