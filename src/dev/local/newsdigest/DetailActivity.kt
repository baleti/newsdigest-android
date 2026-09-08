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
import android.util.Log
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
import org.json.JSONObject

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
    private lateinit var synthBanner: SynthesizingBanner
    private lateinit var playerBar: PlayerControlBar
    private var readAloudMenuItem: MenuItem? = null
    private var isPlaying = false
    private lateinit var chatContainer: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var scrollView: ScrollView

    private lateinit var readAloud: ReadAloudController
    private var chatTtsService: TtsPlaybackService? = null
    private var chatTtsBound = false

    private var agentChat: AgentChatClient? = null
    private var chatBusyRow: LinearLayout? = null

    private var sourceTitle: String = ""
    private var sourceLink: String = ""
    private var contentForChat: String = ""
    private var rawContent: String = ""
    private var isDigest = false
    private var digestRunId: String = ""
    private var digestTopic: String = ""

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
                    isPlaying = true
                    playerBar.show()
                    playerBar.setPlaying(true)
                    playerBar.setSpeed(readAloud.getSpeed())
                    // The plain caption has no real hyperlinks of its own
                    // (see ReadAloudController's docstring) - the only
                    // ClickableSpans in it are the per-word seek targets
                    // it adds itself, so LinkMovementMethod here is safe
                    // and is what lets tapping a word actually seek.
                    contentView.movementMethod = LinkMovementMethod.getInstance()
                } else {
                    playerBar.hide()
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
            onGenerating = { generating, estimatedMs ->
                if (generating) synthBanner.start(estimatedMs) else synthBanner.stop()
            },
            onPlayingChanged = { playing ->
                isPlaying = playing
                playerBar.setPlaying(playing)
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

        // Set by MainActivity's "combine selected digests" action - the
        // whole point of that flow is to start reading immediately, not
        // land on a screen you still have to press play on. Delayed
        // slightly since bindService() above is asynchronous even for a
        // same-process service; toggleReadAloud() would silently no-op
        // if it fires before onServiceConnected has actually run.
        if (intent.getBooleanExtra("autoReadAloud", false)) {
            window.decorView.postDelayed({ if (!readAloud.isActive()) toggleReadAloud() }, 300)
        }
    }

    private fun dp(v: Int) = Theme.dp(this, v)

    // Sticky in the top bar (survives scrolling) rather than a button
    // inside the scrolling content, per feedback. Speed used to live here
    // too (behind "..."), but now has its own icon in playerBar instead.
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        readAloudMenuItem = menu?.add(0, 1, 0, if (readAloud.isActive()) "Stop" else "Read aloud")
        readAloudMenuItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            toggleReadAloud()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
        }

        playerBar = PlayerControlBar(
            this,
            onRewind = { readAloud.seekRelative(-15_000) },
            onPlayPause = { if (isPlaying) readAloud.pause() else readAloud.resume() },
            onForward = { readAloud.seekRelative(15_000) },
            onSpeedClick = { anchor ->
                SpeedPicker.show(this, anchor, readAloud.getSpeed()) { speed ->
                    readAloud.setSpeed(speed)
                    playerBar.setSpeed(speed)
                }
            },
        )
        outer.addView(playerBar.view)

        synthBanner = SynthesizingBanner(this)
        outer.addView(synthBanner.view) // above the ScrollView, not inside it - stays visible regardless of scroll position

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
        val runId = intent.getStringExtra("runId") ?: ""
        val topic = intent.getStringExtra("topic") ?: "Today"
        val markdown = intent.getStringExtra("markdown") ?: ""
        isDigest = true
        digestRunId = runId
        digestTopic = topic
        titleView.text = topic
        subtitleView.text = date
        rawContent = markdown
        contentForChat = "Today's \"$topic\" digest ($date):\n\n$markdown"
        sourceTitle = "$topic ($date)"
        sourceLink = ""
        contentView.text = MarkdownRenderer.render(markdown) { url -> openArticle(url) }

        // Was this article already chatted about (this session, an earlier
        // one, even another device)? The conversation is persisted
        // server-side against this exact digest run + topic - resume it
        // rather than starting a new one every time the article is opened.
        if (runId.isNotBlank()) {
            ensureAgentChat().resume(runId, topic)
        }
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
            // Pass the RENDERED text, not raw markdown - see
            // ReadAloudController.start()'s doc: this keeps the caption's
            // plain-text form identical to what the server actually
            // strips down to and times, so highlighting/tap-to-seek stay
            // in sync for sentences containing links, bold, etc. instead
            // of silently failing to match (confirmed live before this fix).
            val renderedText = if (isDigest) {
                MarkdownRenderer.render(rawContent) { url -> openArticle(url) }
            } else {
                rawContent
            }
            readAloud.start(sourceTitle, renderedText)
        }
    }

    // ------------------------------------------------------------ chat

    private fun addChatBubble(role: String, text: String) {
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
                this.text = role
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isUser) Theme.primary else Theme.linkColor)
            },
        )
        wrapper.addView(
            TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(Theme.onBackground)
                setPadding(0, dp(4), 0, 0)
            },
        )
        if (!isUser) {
            val readBtn = Button(this).apply {
                this.text = "Read aloud"
                setTextColor(Theme.onBackground)
                Theme.styleGhostButton(this, this@DetailActivity)
                setOnClickListener { speakChatReply(text) }
            }
            val readBtnRow = LinearLayout(this).apply { setPadding(0, dp(6), 0, 0) }
            readBtnRow.addView(readBtn)
            wrapper.addView(readBtnRow)
        }
        chatContainer.addView(row)
    }

    private fun showChatBusy(busy: Boolean) {
        chatBusyRow?.let { chatContainer.removeView(it) }
        chatBusyRow = null
        if (busy) {
            val row = LinearLayout(this).apply {
                setPadding(dp(14), dp(6), dp(14), dp(10))
                addView(
                    TextView(this@DetailActivity).apply {
                        text = "Claude is working…"
                        textSize = 12f
                        setTextColor(Theme.onSurfaceVariant)
                    },
                )
            }
            chatContainer.addView(row)
            chatBusyRow = row
        }
        scrollToBottom()
    }

    /** One AgentChatClient per Activity instance, created lazily so
     * loadDigest()'s resume() call and the first onSend() share the same
     * live conversation/poll loop instead of each spawning their own. */
    private fun ensureAgentChat(): AgentChatClient {
        agentChat?.let { return it }
        val client = AgentChatClient(
            this,
            onSessionReady = { _, resumed -> if (!resumed) Log.i("NewsDigest", "chat: spawned new session") },
            onMessages = { messages ->
                for (m in messages) addChatBubble(if (m.role == "user") "You" else "Assistant", m.text)
                scrollToBottom()
            },
            onBusyChanged = { busy -> showChatBusy(busy) },
            onError = { message -> Toast.makeText(this, "Chat error: $message", Toast.LENGTH_LONG).show() },
        )
        agentChat = client
        return client
    }

    private fun onSend() {
        val question = inputField.text.toString().trim()
        if (question.isEmpty()) return
        inputField.text.clear()

        addChatBubble("You", question)
        scrollToBottom()

        val client = ensureAgentChat()
        if (client.sessionId == null) {
            val prompt = buildString {
                append(contentForChat)
                if (sourceLink.isNotBlank()) append("\n\nSource link: $sourceLink")
                append("\n\nQuestion: $question")
            }
            client.start(digestRunId, digestTopic, prompt)
        } else {
            client.send(question)
        }
    }

    /** Reuses /tts/stream directly (the same protocol ReadAloudController
     * drives for the main article) rather than the old per-delta synth
     * that used to ride along inside /agent/chat's WebSocket - chat
     * replies now arrive as one finished block of text from the poll loop
     * rather than token-by-token, so there is nothing left to synthesize
     * incrementally as it streams. */
    private fun speakChatReply(text: String) {
        if (!chatTtsBound) return
        val svc = chatTtsService ?: return
        readAloud.stop()
        svc.stopAll()
        svc.setListener(object : TtsPlaybackService.HighlightListener {})
        // See ReadAloudController.start()'s comment -- a chat-reply read
        // shares the same TtsPlaybackService instance, and needs the same
        // explicit start to survive leaving this screen (this is in fact
        // the exact "close the activity/window with the conversation"
        // case the fix was asked for).
        startForegroundService(Intent(this, TtsPlaybackService::class.java))
        svc.startSession(sourceTitle)
        Thread {
            val ws = WebSocketClient(Settings.getHost(this), Settings.getTtsPort(this), "/tts/stream", mapOf("X-Peer-Agent" to "1"))
            ws.connect(
                object : WebSocketClient.Listener {
                    private var pendingMeta: JSONObject? = null

                    override fun onOpen() {
                        ws.sendText(
                            JSONObject().apply {
                                put("text", text)
                                put("engine", Settings.getTtsEngine(this@DetailActivity))
                            }.toString(),
                        )
                    }

                    override fun onText(msg: String) {
                        val obj = JSONObject(msg)
                        when (obj.optString("type")) {
                            "sentence" -> pendingMeta = obj
                            "done" -> {
                                svc.endSession()
                                ws.close()
                            }
                            "error" -> {
                                runOnUiThread { Toast.makeText(this@DetailActivity, "Read aloud failed: ${obj.optString("message")}", Toast.LENGTH_LONG).show() }
                                ws.close()
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
                        svc.enqueueSentence(meta.getString("text"), words, data, meta.getInt("sample_rate"))
                    }

                    override fun onFailure(error: Throwable) {}
                },
            )
        }.apply { isDaemon = true; name = "ChatReplyTts"; start() }
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
