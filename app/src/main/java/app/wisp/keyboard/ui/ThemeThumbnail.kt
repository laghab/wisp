package app.wisp.keyboard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.wisp.keyboard.R
import app.wisp.keyboard.kbd.KeyboardView
import app.wisp.keyboard.kbd.LayoutLoader
import app.wisp.keyboard.kbd.Palette
import app.wisp.keyboard.style.KeyboardStyle

/** What a theme looks like: the real keyboard drawn off-screen with that theme (keys, borders, shadows, rivets, band, the space bar's picture). */
object ThemeThumbnails {
    /** The width the keyboard is laid out at, in dp, like a phone's; the bitmap is that drawing scaled down to [WIDTH_PX]. */
    private const val LAYOUT_DP = 360
    private const val WIDTH_PX = 720

    fun render(context: Context, themeId: String, dark: Boolean): Bitmap {
        val d = context.resources.displayMetrics.density
        val w = (LAYOUT_DP * d).toInt()
        val style = KeyboardStyle(theme = themeId, colors = if (dark) "dark" else "light", spaceArt = "still")
        val view = KeyboardView(context, LayoutLoader.load(context, R.raw.layout_qwerty), Palette())
        view.gesturesEnabled = false
        view.palette.set(style.palette(context))
        view.applyStyle(style)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, w, view.measuredHeight)
        val scale = WIDTH_PX / w.toFloat()
        val bmp = Bitmap.createBitmap(WIDTH_PX, (view.measuredHeight * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.scale(scale, scale)
        view.draw(c)
        view.dispose()
        return bmp
    }
}

/** A theme's thumbnail, drawn once when it first shows. */
@Composable
fun ThemeThumbnail(themeId: String, dark: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, themeId, dark) { value = ThemeThumbnails.render(context, themeId, dark).asImageBitmap() }
    image?.let { Image(it, null, modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height), contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.High) }
}
