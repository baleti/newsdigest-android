package dev.local.newsdigest

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.view.View

/**
 * Playback-speed slider (0.5x-3x), shown as a small popup anchored to the
 * speed icon in PlayerControlBar - one physical control with one value at
 * a time, not a checklist of fixed stops. Applies live as you drag (the
 * underlying TtsPlaybackService.setPlaybackSpeed() already supports being
 * called anytime, including mid-sentence), not only on release.
 */
object SpeedPicker {
    private const val MIN = 0.5f
    private const val MAX = 3.0f
    private const val STEP = 0.1f
    private val STEPS = Math.round((MAX - MIN) / STEP) // 25

    fun show(context: Context, anchor: View, currentSpeed: Float, onChanged: (Float) -> Unit) {
        val label = TextView(context).apply {
            text = formatSpeed(currentSpeed)
            textSize = 16f
            setTextColor(Theme.onBackground)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, Theme.dp(context, 8))
        }
        val seekBar = SeekBar(context).apply {
            max = STEPS
            progress = Math.round((currentSpeed - MIN) / STEP).coerceIn(0, STEPS)
            progressTintList = ColorStateList.valueOf(Theme.primary)
            thumbTintList = ColorStateList.valueOf(Theme.primary)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedDrawable(Theme.surfaceContainer, context, strokeColor = Theme.outlineVariant)
            setPadding(Theme.dp(context, 20), Theme.dp(context, 16), Theme.dp(context, 20), Theme.dp(context, 12))
            addView(label)
            addView(seekBar, LinearLayout.LayoutParams(Theme.dp(context, 220), LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val popup = PopupWindow(container, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = Theme.dp(context, 8).toFloat()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = MIN + progress * STEP
                label.text = formatSpeed(speed)
                if (fromUser) onChanged(speed)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        popup.showAsDropDown(anchor, 0, -Theme.dp(context, 8))
    }

    fun formatSpeed(speed: Float): String {
        val rounded = Math.round(speed * 100) / 100f
        val trimmed = if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString() else rounded.toString()
        return "${trimmed}x"
    }
}
