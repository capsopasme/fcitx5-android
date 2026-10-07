/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme

/**
 * Voice panel layout, built with plain framework views:
 *
 * ```
 *  status line (backend / hint)
 *  ┌──────────── live transcript ────────────┐
 *  [ ⌫ ]           ( 🎤 / ■ )           [ ↵ ]
 * ```
 */
class VoiceInputUi(val ctx: Context, private val theme: Theme) {

    enum class MicState { Idle, Loading, Listening }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    val statusText = TextView(ctx).apply {
        setTextColor(theme.altKeyTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    val transcriptText = TextView(ctx).apply {
        setTextColor(theme.keyTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(4), dp(16), dp(4))
    }

    private val transcriptScroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        // keep long transcripts inside their own area, never under the status line
        clipToPadding = true
        isFillViewport = true
        addView(transcriptText, FrameLayout.LayoutParams(-1, -2))
    }

    /** an extra action shown when something needs to be fixed (permission / model) */
    val actionButton = TextView(ctx).apply {
        setTextColor(theme.accentKeyBackgroundColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(theme.keyPressHighlightColor), null, null)
        isClickable = true
        visibility = View.GONE
    }

    private val halo = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.accentKeyBackgroundColor)
        }
        alpha = 0.25f
        scaleX = 0.8f
        scaleY = 0.8f
    }

    private val micIcon = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
        imageTintList = ColorStateList.valueOf(theme.accentKeyTextColor)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val micButton = FrameLayout(ctx).apply {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.accentKeyBackgroundColor)
        }
        background = RippleDrawable(ColorStateList.valueOf(theme.keyPressHighlightColor), circle, circle)
        isClickable = true
        contentDescription = ctx.getString(R.string.voice_input)
        addView(micIcon, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
    }

    private val micContainer = FrameLayout(ctx).apply {
        addView(halo, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        addView(micButton, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
    }

    private fun sideButton(@DrawableRes icon: Int, desc: Int) = ImageView(ctx).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        scaleType = ImageView.ScaleType.CENTER
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.keyBackgroundColor)
        }
        background = RippleDrawable(ColorStateList.valueOf(theme.keyPressHighlightColor), bg, bg)
        isClickable = true
        contentDescription = ctx.getString(desc)
    }

    val backspaceButton = sideButton(R.drawable.ic_baseline_backspace_24, R.string.voice_backspace)
    val enterButton = sideButton(R.drawable.ic_baseline_keyboard_return_24, R.string.voice_enter)

    private val controls = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(backspaceButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        addView(micContainer, LinearLayout.LayoutParams(dp(100), dp(100)))
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        addView(enterButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        setPadding(dp(28), 0, dp(28), dp(8))
    }

    /** called when the IME window is shown / hidden while this panel is attached */
    var onWindowVisibilityChanged: ((visible: Boolean) -> Unit)? = null

    val root = object : LinearLayout(ctx) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            this@VoiceInputUi.onWindowVisibilityChanged?.invoke(visibility == View.VISIBLE)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, 0)
        addView(statusText, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(16), 0, dp(16), 0)
        })
        addView(transcriptScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(actionButton, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addView(controls, LinearLayout.LayoutParams(-1, -2))
    }

    fun setMicState(state: MicState) {
        micIcon.setImageResource(
            if (state == MicState.Idle) R.drawable.ic_baseline_keyboard_voice_24
            else R.drawable.ic_baseline_stop_24
        )
        micButton.alpha = if (state == MicState.Loading) 0.6f else 1f
        micButton.contentDescription = ctx.getString(
            if (state == MicState.Idle) R.string.voice_start else R.string.voice_stop
        )
        if (state != MicState.Listening) setLevelStep(0)
    }

    /**
     * Show the microphone level as one of a few halo sizes. Set directly instead of animated:
     * an animation per audio chunk (10/s) kept the panel redrawing at the display's full refresh
     * rate for as long as the microphone was open, even in silence. Now a frame is drawn only
     * when the level actually moves to another step.
     *
     * @param step from [levelStep]
     */
    fun setLevelStep(step: Int) {
        val v = 0.8f + 0.2f * step.coerceIn(0, LEVEL_STEPS) / LEVEL_STEPS
        if (halo.scaleX == v && halo.scaleY == v) return
        halo.scaleX = v
        halo.scaleY = v
    }

    companion object {
        private const val LEVEL_STEPS = 6

        /** below this RMS the room is considered silent, so background noise doesn't flicker */
        private const val NOISE_FLOOR = 0.012f

        /** @param rms microphone level, roughly 0..0.3; @return 0..[LEVEL_STEPS] */
        fun levelStep(rms: Float): Int {
            if (rms < NOISE_FLOOR) return 0
            // a bit of compression, so normal speech covers most of the range
            val v = kotlin.math.sqrt(rms.coerceAtMost(0.25f) / 0.25f)
            return kotlin.math.round(v * LEVEL_STEPS).toInt().coerceIn(1, LEVEL_STEPS)
        }
    }

    /** already committed text is dimmed, the sentence still being recognized is highlighted */
    fun showTranscript(committed: CharSequence, partial: CharSequence) {
        val sb = SpannableStringBuilder()
        sb.append(committed)
        sb.setSpan(
            ForegroundColorSpan(theme.altKeyTextColor), 0, sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.append(partial)
        transcriptText.text = sb
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun showAction(text: CharSequence?, onClick: (() -> Unit)?) {
        if (text == null || onClick == null) {
            actionButton.visibility = View.GONE
            actionButton.setOnClickListener(null)
        } else {
            actionButton.text = text
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener { onClick() }
        }
    }
}
