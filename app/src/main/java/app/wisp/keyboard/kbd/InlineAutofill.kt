package app.wisp.keyboard.kbd

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.util.Size
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.inline.InlineContentView
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * Password-manager logins as chips in the toolbar strip (Android 11+), built the way HeliBoard does it: fixed-height specs, and a clip view so
 * the chips, which another process draws on their own surfaces, stay inside the strip instead of spilling over the keys.
 */
@SuppressLint("RestrictedApi")
@RequiresApi(30)
object InlineAutofill {
    fun request(context: Context, palette: Palette, heightPx: Int): InlineSuggestionsRequest {
        val chip = Icon.createWithResource(context, androidx.autofill.R.drawable.autofill_inline_suggestion_chip_background).setTint(palette.special)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(androidx.autofill.inline.common.ViewStyle.Builder().setBackground(chip).setPadding(0, 0, 0, 0).build())
            .setChipStyle(androidx.autofill.inline.common.ViewStyle.Builder().setBackground(chip).build())
            .setTitleStyle(androidx.autofill.inline.common.TextViewStyle.Builder().setTextColor(palette.text).setTextSize(12f).build())
            .setSubtitleStyle(androidx.autofill.inline.common.TextViewStyle.Builder().setTextColor(palette.textMuted).setTextSize(10f).build())
            .build()
        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val spec = InlinePresentationSpec.Builder(Size(100, heightPx), Size(740, heightPx)).setStyle(styles).build()
        // some password managers insist on three specs
        return InlineSuggestionsRequest.Builder(listOf(spec, spec, spec)).setMaxSuggestionCount(6).build()
    }

    /** A strip-sized view holding the chips as they finish inflating. */
    fun view(suggestions: List<InlineSuggestion>, context: Context): View {
        val row = LinearLayout(context)
        for (s in suggestions) {
            s.inflate(context, Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), context.mainExecutor) { v -> if (v != null) row.addView(v) }
        }
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(row) }
        return ClipView(context).apply { addView(scroll) }
    }

    /** Keeps every chip surface inside this view's own bounds. */
    private class ClipView(context: Context) : FrameLayout(context) {
        private val draw = ViewTreeObserver.OnDrawListener { clip(this) }
        init { addView(SurfaceView(context).apply { setZOrderOnTop(true); holder.setFormat(PixelFormat.TRANSPARENT) }, 0, 0) }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnDrawListener(draw) }
        override fun onDetachedFromWindow() { viewTreeObserver.removeOnDrawListener(draw); super.onDetachedFromWindow() }

        private fun clip(parent: ViewGroup) {
            val mine = IntArray(2).also { getLocationOnScreen(it) }
            for (i in 0 until parent.childCount) {
                val c = parent.getChildAt(i)
                if (c is InlineContentView) {
                    val at = IntArray(2).also { c.getLocationOnScreen(it) }
                    val x = mine[0] - at[0]; val y = mine[1] - at[1]
                    c.clipBounds = Rect(x, y, x + width, y + height)
                } else if (c is ViewGroup) clip(c)
            }
        }
    }
}
