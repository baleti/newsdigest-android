package dev.local.newsdigest

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.EditText

/**
 * Small shared style kit so every screen looks like one app instead of a
 * pile of default system widgets - dark background, rounded surfaces,
 * ripple-backed buttons, a single amber/gold accent (matching the app
 * icon's palette). Ported from the structure of the sibling
 * claudeagents-android app's own Theme.kt, with this app's own colors.
 */
object Theme {
    const val bg = 0xFF17150F.toInt()
    const val surface = 0xFF211E17.toInt()
    const val surfaceContainer = 0xFF3A342A.toInt()
    const val onBackground = 0xFFEDE7DD.toInt()
    const val onSurfaceVariant = 0xFFCBBFA9.toInt()
    const val outline = 0xFF8F8368.toInt()
    const val outlineVariant = 0xFF3A342A.toInt()
    const val primary = 0xFFF2B632.toInt()
    const val onPrimary = 0xFF2E2000.toInt()
    const val error = 0xFFFFB4AB.toInt()
    const val errorContainer = 0xFF4A1410.toInt()
    const val muted = 0xFF8A8270.toInt()
    const val linkColor = 0xFF7FC4FF.toInt()

    // Digest categories are whatever the generator decides that day (see
    // build_digest_json.py) - unbounded and unpredictable, so there's no
    // fixed color per category name. Instead: a spread of hues distinct
    // enough from each other AND from the amber primary (used for the
    // separate source-count badge), picked by a stable hash of the
    // category string - same category always lands on the same color
    // within a run, different categories usually land on different ones.
    private val categoryPalette = listOf(
        0xFFB0413E.toInt(), // red
        0xFFB0713A.toInt(), // orange
        0xFF4C8C4A.toInt(), // green
        0xFF2E8B87.toInt(), // teal
        0xFF3A6EA5.toInt(), // blue
        0xFF5A5AB0.toInt(), // indigo
        0xFF8A4CA8.toInt(), // purple
        0xFFB0407A.toInt(), // rose
    )

    fun colorForCategory(category: String): Int {
        if (category.isBlank()) return surfaceContainer
        val idx = (category.trim().lowercase().hashCode().and(Int.MAX_VALUE)) % categoryPalette.size
        return categoryPalette[idx]
    }

    const val roundingDp = 10
    const val spacingDp = 8

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun roundedDrawable(color: Int, context: Context, radiusDp: Int = roundingDp, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(color)
        d.cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(context, 1), strokeColor)
        return d
    }

    fun rippleOn(base: GradientDrawable): RippleDrawable =
        RippleDrawable(android.content.res.ColorStateList.valueOf(outline and 0x66FFFFFF.toInt()), base, base)

    fun styleEditText(e: EditText, context: Context) {
        e.setTextColor(onBackground)
        e.setHintTextColor(muted)
        e.background = roundedDrawable(surface, context, strokeColor = outlineVariant)
        e.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        try {
            e.highlightColor = primary and 0x55FFFFFF.toInt()
        } catch (_: Throwable) {
        }
    }

    fun stylePrimaryButton(view: View, context: Context) {
        view.background = rippleOn(roundedDrawable(primary, context))
        val pad = dp(context, 12)
        view.setPadding(pad, dp(context, 10), pad, dp(context, 10))
    }

    fun styleGhostButton(view: View, context: Context) {
        view.background = rippleOn(roundedDrawable(Color.TRANSPARENT, context, strokeColor = outlineVariant))
        val pad = dp(context, 12)
        view.setPadding(pad, dp(context, 10), pad, dp(context, 10))
    }
}
