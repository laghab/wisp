package app.wisp.keyboard.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.wisp.keyboard.R
import app.wisp.keyboard.kbd.KeyboardView
import app.wisp.keyboard.kbd.LayoutGuard
import app.wisp.keyboard.kbd.LayoutLoader
import app.wisp.keyboard.kbd.Palette
import app.wisp.keyboard.style.KeyboardStyle

/** The real keyboard view, drawn with the style being edited, so what you see is exactly what you will type on. */
@Composable
fun KeyboardPreview(style: KeyboardStyle, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier.fillMaxWidth().padding(bottom = style.bottomGap.dp),
        factory = { c -> KeyboardView(c, LayoutLoader.load(c, R.raw.layout_qwerty), Palette()).also { it.gesturesEnabled = false } },
        update = { v ->
            val base = LayoutLoader.load(context, R.raw.layout_qwerty)
            v.setSpec(if (style.numberRow) LayoutGuard.withNumberRow(base) else base)
            v.palette.set(style.palette(context))
            v.applyStyle(style)
        },
    )
}
