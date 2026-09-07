package dev.local.rssreader

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * First-run setup and re-configuration. Nothing here is baked into the
 * source (see Settings.kt) - host, port, TTS engine, and which Claude
 * account chat uses are all entered/picked here and stored on-device.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = Theme.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Theme.bg)
        }

        fun spacer(dp: Int) = TextView(this).apply { setPadding(0, Theme.dp(this@SettingsActivity, dp), 0, 0) }
        fun sectionLabel(label: String) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Theme.onSurfaceVariant)
        }

        root.addView(
            TextView(this).apply {
                text = "Backend setup"
                textSize = 22f
                setTextColor(Theme.onBackground)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "Enter the host running server/server.py - it serves the feed, " +
                    "TTS, and agent chat all on one port."
                textSize = 13f
                setTextColor(Theme.onSurfaceVariant)
                setPadding(0, Theme.dp(this@SettingsActivity, 8), 0, Theme.dp(this@SettingsActivity, 20))
            },
        )

        val hostField = EditText(this).apply {
            hint = "Host (e.g. your WireGuard peer address)"
            setText(Settings.getHost(this@SettingsActivity))
            Theme.styleEditText(this, this@SettingsActivity)
        }
        root.addView(hostField)
        root.addView(spacer(Theme.spacingDp))

        val portField = EditText(this).apply {
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Settings.getTtsPort(this@SettingsActivity).toString())
            Theme.styleEditText(this, this@SettingsActivity)
        }
        root.addView(portField)

        root.addView(spacer(24))
        root.addView(sectionLabel("TTS engine"))
        root.addView(spacer(6))
        val engineGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val kokoroRadio = RadioButton(this).apply {
            text = "Kokoro (fast)"
            id = 1
            setTextColor(Theme.onBackground)
            buttonTintList = android.content.res.ColorStateList.valueOf(Theme.primary)
        }
        val chatterboxRadio = RadioButton(this).apply {
            text = "Chatterbox (natural)"
            id = 2
            setTextColor(Theme.onBackground)
            buttonTintList = android.content.res.ColorStateList.valueOf(Theme.primary)
            setPadding(Theme.dp(this@SettingsActivity, 20), 0, 0, 0)
        }
        engineGroup.addView(kokoroRadio)
        engineGroup.addView(chatterboxRadio)
        root.addView(engineGroup)
        if (Settings.getTtsEngine(this) == "chatterbox") chatterboxRadio.isChecked = true else kokoroRadio.isChecked = true

        val engineStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.muted)
            setPadding(0, Theme.dp(this@SettingsActivity, 8), 0, 0)
        }
        root.addView(engineStatus)
        checkEngineStatus(hostField, portField, engineStatus)

        root.addView(spacer(24))
        root.addView(sectionLabel("Claude account for chat"))
        root.addView(spacer(6))
        val accountField = EditText(this).apply {
            hint = "account (claude / claude2 / claude3)"
            setText(Settings.getAccount(this@SettingsActivity))
            Theme.styleEditText(this, this@SettingsActivity)
        }
        root.addView(accountField)

        root.addView(spacer(28))
        root.addView(
            Button(this).apply {
                text = "Save"
                setTextColor(Theme.onPrimary)
                Theme.stylePrimaryButton(this, this@SettingsActivity)
                setOnClickListener {
                    val host = hostField.text.toString().trim()
                    val port = portField.text.toString().trim().toIntOrNull()
                    val account = accountField.text.toString().trim().ifBlank { Settings.DEFAULT_ACCOUNT }
                    if (host.isEmpty() || port == null) {
                        Toast.makeText(this@SettingsActivity, "Fill in host and port", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val engine = if (chatterboxRadio.isChecked) "chatterbox" else "kokoro"
                    Settings.save(this@SettingsActivity, host, port, engine, account)
                    Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            },
        )

        setContentView(ScrollView(this).apply { setBackgroundColor(Theme.bg); addView(root) })
    }

    /** Chatterbox takes ~30s to load its CUDA model after the server
     * starts - shown here so a user picking it right after (re)starting
     * the server understands why the first read-aloud might be slow to
     * start, rather than looking broken. */
    private fun checkEngineStatus(hostField: EditText, portField: EditText, statusView: TextView) {
        Thread {
            try {
                val host = hostField.text.toString().trim()
                val port = portField.text.toString().trim().toIntOrNull() ?: return@Thread
                if (host.isEmpty()) return@Thread
                val url = java.net.URL("http://$host:$port/status")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("X-Peer-Agent", "1")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = org.json.JSONObject(body)
                val text = "kokoro: ${obj.optString("kokoro", "?")} · chatterbox: ${obj.optString("chatterbox", "?")}"
                runOnUiThread { statusView.text = text }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "Server unreachable - check host/port" }
            }
        }.apply { isDaemon = true }.start()
    }
}
