package dev.local.newsdigest

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/**
 * Content view (digest or item) + read-aloud-with-highlighting + a sticky
 * chat box. Two independent bindings to TtsPlaybackService are used on
 * purpose: ReadAloudController owns one for the main content (with live
 * word-highlighting), and this activity owns a second, simpler one for
 * "read this reply aloud" buttons in the chat thread (no highlighting -
 * the reply's full text is already visible as it streamed in, so a
 * separate live-caption view would just duplicate it). Only one of the
 * two is ever actually driving playback at a time, since starting either
 * stops the other's queue first - Android allows multiple bind() calls
 * against the same running service instance, so this isn't two services.
 */
class DetailActivity : Activity() {

    private lateinit var contentContainer: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var contentView: TextView
    private var readAloudMenuItem: MenuItem? = null
    private lateinit var chatContainer: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var scrollView: ScrollView

    private lateinit var readAloud: ReadAloudController
    private var chatTtsService: TtsPlaybackService? = null
    private var chatTtsBound = false

    private var agentChat: AgentChatClient? = null
    private var chatSessionId: String? = null

    private var sourceTitle: String = ""
    private var sourceLink: String = ""
    private var contentForChat: String = ""
    private var rawContent: String = ""
    private var isDigest = false

    private val chatConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            chatTtsService = (service as TtsPlaybackService.LocalBinder).service()
            chatTtsBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            chatTtsService = null
            chatTtsBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readAloud = ReadAloudController(
            this,
            onStateChanged = { playing ->
                // Belt-and-suspenders: setTitle() alone isn't guaranteed to
                // repaint an already-inflated action-bar item on every
                // OEM skin, so force a rebuild too. (The actual bug behind
                // "the button never updates" turned out to be a race in
                // TtsPlaybackService - see its sessionGeneration comment -
                // not this; kept anyway since it's cheap and correct.)
                readAloudMenuItem?.title = if (playing) "Stop" else "Read aloud"
                window.decorView.post { invalidateOptionsMenu() }
                if (playing) {
                    contentView.movementMethod = null // plain caption while reading, no stray link taps
                } else {
                    // Restore the rich static view whenever playback
                    // stops - whether the user stopped it or it finished
                    // on its own reaching the end (onStateChanged fires
                    // for both, so this one place covers it).
                    if (isDigest) {
                        contentView.text = MarkdownRenderer.render(rawContent) { url -> openArticle(url) }
                        contentView.movementMethod = LinkMovementMethod.getInstance()
                    } else {
                        contentView.text = rawContent
                    }
                }
            },
            onCaptionChanged = { caption ->
                if (readAloud.isActive()) contentView.text = caption
            },
        )
        readAloud.bind()
        bindService(Intent(this, TtsPlaybackService::class.java), chatConnection, Context.BIND_AUTO_CREATE)

        buildUi()

        when (intent.getStringExtra("type")) {
            "digest" -> loadDigest()
            "item" -> loadItem()
            else -> titleView.text = "Unknown entry"
        }
    }

    private fun dp(v: Int) = Theme.dp(this, v)

    // Sticky in the top bar (survives scrolling) rather than a button
    // inside the scrolling content, per feedback.
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        readAloudMenuItem = menu?.add(0, 1, 0, if (readAloud.isActive()) "Stop" else "Read aloud")
        readAloudMenuItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        SpeedMenu.addTo(menu, readAloud.getSpeed())
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            toggleReadAloud()
            return true
        }
        if (SpeedMenu.handle(item, readAloud)) return true
        return super.onOptionsItemSelected(item)
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
        }

        scrollView = ScrollView(this).apply { setBackgroundColor(Theme.bg) }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scrollView.addView(contentContainer)
        outer.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        titleView = TextView(this).apply {
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.onBackground)
        }
        subtitleView = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.muted)
            setPadding(0, dp(6), 0, dp(16))
        }
        contentView = TextView(this).apply {
            textSize = 15f
            setTextColor(Theme.onBackground)
            setLineSpacing(dp(4).toFloat(), 1f)
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(Theme.linkColor)
        }

        contentContainer.addView(titleView)
        contentContainer.addView(subtitleView)
        contentContainer.addView(contentView)
        contentContainer.addView(spacer(28))
        chatContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentContainer.addView(chatContainer)
        contentContainer.addView(spacer(80)) // keep last chat bubble clear of the sticky bar

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.surface)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val topBorder = android.graphics.drawable.GradientDrawable()
            topBorder.setColor(Theme.surface)
            elevation = dp(8).toFloat()
        }
        inputField = EditText(this).apply {
            hint = "Ask a question..."
            Theme.styleEditText(this, this@DetailActivity)
        }
        val sendButton = Button(this).apply {
            text = "Send"
            setTextColor(Theme.onPrimary)
            Theme.stylePrimaryButton(this, this@DetailActivity)
            setOnClickListener { onSend() }
        }
        inputRow.addView(inputField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(spacerHorizontal(8))
        inputRow.addView(sendButton)
        outer.addView(inputRow)

        setContentView(outer)
    }

    private fun spacer(dpVal: Int) = TextView(this).apply { setPadding(0, dp(dpVal), 0, 0) }
    private fun spacerHorizontal(dpVal: Int) = TextView(this).apply { setPadding(dp(dpVal), 0, 0, 0) }

    private fun loadDigest() {
        val date = intent.getStringExtra("date") ?: ""
        val markdown = intent.getStringExtra("markdown") ?: ""
        isDigest = true
        titleView.text = "Today's digest"
        subtitleView.text = date
        rawContent = markdown
        contentForChat = "Today's RSS digest ($date):\n\n$markdown"
        sourceTitle = "Today's digest ($date)"
        sourceLink = ""
        contentView.text = MarkdownRenderer.render(markdown) { url -> openArticle(url) }
    }

    private fun loadItem() {
        val title = intent.getStringExtra("title") ?: ""
        val summary = intent.getStringExtra("summary") ?: ""
        val feedTitle = intent.getStringExtra("feedTitle") ?: ""
        val link = intent.getStringExtra("link") ?: ""
        isDigest = false
        titleView.text = title
        subtitleView.text = feedTitle
        rawContent = summary
        contentForChat = "Article \"$title\" from $feedTitle:\n\n$summary"
        sourceTitle = title
        sourceLink = link
        contentView.text = summary

        val openArticleButton = Button(this).apply {
            text = "Open full article"
            setTextColor(Theme.onBackground)
            Theme.styleGhostButton(this, this@DetailActivity)
        }
        openArticleButton.setOnClickListener { openArticle(link) }
        val idx = contentContainer.indexOfChild(contentView)
        contentContainer.addView(spacer(16), idx + 1)
        contentContainer.addView(openArticleButton, idx + 2)
    }

    private fun openArticle(link: String) {
        if (link.isBlank()) return
        startActivity(Intent(this, ArticleActivity::class.java).apply { putExtra("link", link) })
    }

    private fun toggleReadAloud() {
        if (readAloud.isActive()) {
            readAloud.stop()
            // restore the rich static view once playback stops
            if (isDigest) {
                contentView.text = MarkdownRenderer.render(rawContent) { url -> openArticle(url) }
                contentView.movementMethod = LinkMovementMethod.getInstance()
            } else {
                contentView.text = rawContent
            }
        } else {
            chatTtsService?.stopAll()
            readAloud.start(sourceTitle, rawContent)
        }
    }

    // ------------------------------------------------------------ chat

    private fun addChatBubble(role: String, initialText: String): TextView {
        val isUser = role == "You"
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Theme.roundedDrawable(if (isUser) Theme.surfaceContainer else Theme.surface, this@DetailActivity)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(wrapper)
        row.setPadding(0, dp(6), 0, dp(6))

        wrapper.addView(
            TextView(this).apply {
                text = role
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isUser) Theme.primary else Theme.linkColor)
            },
        )
        val body = TextView(this).apply {
            text = initialText
            textSize = 14f
            setTextColor(Theme.onBackground)
            setPadding(0, dp(4), 0, 0)
        }
        wrapper.addView(body)
        chatContainer.addView(row)
        return body
    }

    private fun onSend() {
        val question = inputField.text.toString().trim()
        if (question.isEmpty()) return
        inputField.text.clear()

        addChatBubble("You", question)
        val assistantBubble = addChatBubble("Assistant", "…")
        scrollToBottom()

        val turnSentences = mutableListOf<CachedSentence>()
        var autoForward = false
        var receivedFirstDelta = false
        var turnFinished = false

        val readBtn = Button(this).apply {
            text = "Read aloud"
            isEnabled = false
            setTextColor(Theme.onBackground)
            Theme.styleGhostButton(this, this@DetailActivity)
        }
        val readBtnRow = LinearLayout(this).apply { setPadding(0, 0, 0, dp(10)) }
        readBtnRow.addView(readBtn)
        chatContainer.addView(readBtnRow)
        readBtn.setOnClickListener {
            readAloud.stop()
            autoForward = true
            chatTtsService?.let { svc ->
                svc.stopAll()
                svc.setListener(object : TtsPlaybackService.HighlightListener {})
                svc.startSession(sourceTitle)
                for (s in turnSentences) svc.enqueueSentence(s.text, s.words, s.pcm, s.sampleRate)
                // If the reply had already finished streaming before this
                // button was tapped, no further onSentenceReady calls will
                // ever arrive to trigger endSession() below - say so now,
                // or the service waits forever for sentences that aren't
                // coming and the queue never reports idle once this plays out.
                if (turnFinished) svc.endSession()
            }
        }

        val client = AgentChatClient(
            this,
            onSessionId = { sid -> chatSessionId = sid },
            onTextDelta = { delta ->
                if (!receivedFirstDelta) {
                    receivedFirstDelta = true
                    assistantBubble.text = delta
                } else {
                    assistantBubble.append(delta)
                }
                scrollToBottom()
            },
            onSentenceReady = { sentence ->
                turnSentences.add(sentence)
                readBtn.isEnabled = true
                if (autoForward) {
                    chatTtsService?.enqueueSentence(sentence.text, sentence.words, sentence.pcm, sentence.sampleRate)
                }
            },
            onTurnDone = {
                turnFinished = true
                // Only meaningful once autoForward is true (read-aloud was
                // engaged for this turn); harmless no-op otherwise since
                // startSession() resets sessionEnded per-session anyway.
                if (autoForward) chatTtsService?.endSession()
            },
            onError = { message ->
                assistantBubble.text = "Error: $message"
                Toast.makeText(this, "Chat error: $message", Toast.LENGTH_LONG).show()
            },
        )
        agentChat = client

        val sid = chatSessionId
        if (sid == null) {
            val prompt = buildString {
                append(contentForChat)
                if (sourceLink.isNotBlank()) append("\n\nSource link: $sourceLink")
                append("\n\nQuestion: $question")
            }
            client.start(prompt)
        } else {
            client.continueChat(sid, question)
        }
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        readAloud.unbind()
        agentChat?.close()
        if (chatTtsBound) {
            try { unbindService(chatConnection) } catch (_: Exception) {}
        }
    }
}
