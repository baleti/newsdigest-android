package dev.local.rssreader

import android.app.Activity
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class ArticleActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        statusView = TextView(this).apply { text = "Loading article..." }
        root.addView(statusView)
        setContentView(ScrollView(this).apply { addView(root) })

        val link = intent.getStringExtra("link")
        if (link.isNullOrBlank()) {
            statusView.text = "No article link provided"
            return
        }
        load(link)
    }

    private fun load(link: String) {
        Thread {
            try {
                val path = "/feed/article?link=" + ApiClient.encodeQuery(link)
                val article = JSONObject(ApiClient.ttsGet(this@ArticleActivity, path))
                val title = article.optString("title")
                val contentHtml = article.optString("content_html")
                val plainText = Html.fromHtml(contentHtml, Html.FROM_HTML_MODE_LEGACY).toString()

                runOnUiThread {
                    root.removeAllViews()
                    root.addView(TextView(this).apply {
                        text = title
                        textSize = 18f
                    })
                    root.addView(Button(this).apply {
                        text = "Read aloud"
                        setOnClickListener {
                            Toast.makeText(this@ArticleActivity, "Read-aloud playback not wired up yet", Toast.LENGTH_SHORT).show()
                        }
                    })
                    root.addView(TextView(this).apply {
                        text = plainText
                        textSize = 15f
                        setPadding(0, 30, 0, 0)
                    })
                }
            } catch (e: Exception) {
                Log.e("RssReader", "article load failed", e)
                runOnUiThread { statusView.text = "Failed to load article: ${e.message}" }
            }
        }.start()
    }
}
