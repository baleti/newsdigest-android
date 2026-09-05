package dev.local.rssreader

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * First-run setup and re-configuration. Nothing here is baked into the
 * source (see Settings.kt) - host, ports, and the claude-relay pairing
 * token are all entered here and stored on-device, the token encrypted.
 *
 * TTS engine choice (Kokoro/Chatterbox) and a Chatterbox-loading indicator
 * belong here too but aren't wired up yet - this screen currently only
 * covers backend pairing.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "Backend setup"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "Enter the host running server/server.py (feed + TTS) and " +
                "claude-relay-daemon.py, plus the pairing token that daemon " +
                "printed on its own first run."
            textSize = 13f
            setPadding(0, 16, 0, 24)
        })

        val hostField = EditText(this).apply {
            hint = "Host (e.g. your WireGuard peer address)"
            setText(Settings.getHost(this@SettingsActivity))
        }
        root.addView(hostField)

        val ttsPortField = EditText(this).apply {
            hint = "Feed/TTS server port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Settings.getTtsPort(this@SettingsActivity).toString())
        }
        root.addView(ttsPortField)

        val relayPortField = EditText(this).apply {
            hint = "claude-relay port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Settings.getRelayPort(this@SettingsActivity).toString())
        }
        root.addView(relayPortField)

        val tokenField = EditText(this).apply {
            hint = "claude-relay pairing token"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Settings.getRelayToken(this@SettingsActivity) ?: "")
        }
        root.addView(tokenField)

        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                val host = hostField.text.toString().trim()
                val ttsPort = ttsPortField.text.toString().trim().toIntOrNull()
                val relayPort = relayPortField.text.toString().trim().toIntOrNull()
                val token = tokenField.text.toString().trim()
                if (host.isEmpty() || ttsPort == null || relayPort == null) {
                    Toast.makeText(this@SettingsActivity, "Fill in host and both ports", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Settings.save(this@SettingsActivity, host, ttsPort, relayPort, token)
                Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
    }
}
