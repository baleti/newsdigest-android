package dev.local.rssreader

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

class DetailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        when (intent.getStringExtra("type")) {
            "digest" -> buildDigestView(root)
            "item" -> buildItemView(root)
            else -> root.addView(TextView(this).apply { text = "Unknown entry" })
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun buildDigestView(root: LinearLayout) {
        val date = intent.getStringExtra("date") ?: ""
        val markdown = intent.getStringExtra("markdown") ?: ""
        val references = JSONArray(intent.getStringExtra("references") ?: "[]")

        root.addView(TextView(this).apply {
            text = "Today's digest - $date"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        })

        // Plain text for now - markdown rendering with inline reference
        // buttons and read-aloud word highlighting is the next pass on
        // this screen, not yet wired.
        root.addView(TextView(this).apply {
            text = markdown
            textSize = 15f
            setPadding(0, 30, 0, 30)
        })

        addReadAloudButton(root, markdown)

        if (references.length() > 0) {
            root.addView(TextView(this).apply {
                text = "Articles mentioned"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 30, 0, 10)
            })
            for (i in 0 until references.length()) {
                val ref = references.getJSONObject(i)
                root.addView(Button(this).apply {
                    text = ref.optString("label").take(60)
                    setOnClickListener {
                        val link = ref.optString("url")
                        startActivity(Intent(this@DetailActivity, ArticleActivity::class.java).apply {
                            putExtra("link", link)
                        })
                    }
                })
            }
        }
    }

    private fun buildItemView(root: LinearLayout) {
        val title = intent.getStringExtra("title") ?: ""
        val summary = intent.getStringExtra("summary") ?: ""
        val feedTitle = intent.getStringExtra("feedTitle") ?: ""
        val link = intent.getStringExtra("link") ?: ""

        root.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = feedTitle
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 10, 0, 20)
        })
        root.addView(TextView(this).apply {
            text = summary
            textSize = 15f
        })

        addReadAloudButton(root, summary)

        root.addView(Button(this).apply {
            text = "Open full article"
            setOnClickListener {
                startActivity(Intent(this@DetailActivity, ArticleActivity::class.java).apply {
                    putExtra("link", link)
                })
            }
        })
    }

    private fun addReadAloudButton(root: LinearLayout, spokenText: String) {
        root.addView(Button(this).apply {
            text = "Read aloud"
            setOnClickListener {
                // TtsPlaybackService's WebSocket/AudioTrack/MediaSession
                // pipeline isn't wired up yet - next build step.
                Toast.makeText(this@DetailActivity, "Read-aloud playback not wired up yet", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
