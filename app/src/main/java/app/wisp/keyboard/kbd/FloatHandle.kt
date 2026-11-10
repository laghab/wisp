package app.wisp.keyboard.kbd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** The grip along the top of a floating keyboard: drag it to move the keyboard. */
class FloatHandle(context: Context, private val palette: Palette) : View(context) {
    /** How far the finger has moved since it went down (px), and whether it has lifted. Measured from the start so one odd sample cannot accumulate. */
    var onMoved: ((Float, Float, Boolean) -> Unit)? = null
    /** The finger went down: remember where the keyboard is. */
    var onGrab: (() -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 4f * d }
    private var lastX = 0f; private var lastY = 0f

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (HEIGHT_DP * d).toInt())

    override fun onDraw(c: Canvas) {
        c.drawColor(palette.background)
        paint.color = palette.textMuted
        val cx = width / 2f; val cy = height / 2f
        c.drawLine(cx - 18f * d, cy, cx + 18f * d, cy, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY; onGrab?.invoke() }
            MotionEvent.ACTION_MOVE -> onMoved?.invoke(e.rawX - lastX, e.rawY - lastY, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onMoved?.invoke(e.rawX - lastX, e.rawY - lastY, true)
        }
        return true
    }

    companion object { const val HEIGHT_DP = 20f }
}
