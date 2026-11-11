package app.wisp.keyboard.kbd

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * The area under the toolbar: the keys, and the panels (emoji, clipboard, rewrite) that replace them. It is always as tall as the keys
 * ([sizer]) are, even while they are hidden behind a panel, so opening anything never changes the keyboard's size.
 */
class PanelStack(context: Context, private val sizer: View) : FrameLayout(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        measureChild(sizer, widthSpec, heightSpec)
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(sizer.measuredHeight, MeasureSpec.EXACTLY))
    }
}
