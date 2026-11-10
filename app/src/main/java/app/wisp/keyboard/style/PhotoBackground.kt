package app.wisp.keyboard.style

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** A photo behind the keys: stored once at keyboard size, darkened as much as the labels need to stay readable. */
object PhotoBackground {
    private const val MAX_WIDTH = 1080

    fun file(context: Context) = File(context.filesDir, "background.jpg")

    /** Copies the picked image into the app's storage, scaled down. Returns the stored path, or null when it cannot be read. */
    fun import(context: Context, uri: Uri): String? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val out = file(context)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        return out.path
    }

    fun load(path: String): Bitmap? = if (path.isEmpty()) null else runCatching { BitmapFactory.decodeFile(path) }.getOrNull()

    /** Average luminance (0..1) of a small copy of [b]. */
    fun meanLuminance(b: Bitmap): Double {
        val s = Bitmap.createScaledBitmap(b, 16, 16, true)
        var sum = 0.0
        for (y in 0 until 16) for (x in 0 until 16) sum += Brand.luminance(s.getPixel(x, y))
        if (s !== b) s.recycle()
        return sum / 256.0
    }

    /**
     * The dimming (percent) to draw with: what the user asked for, raised until a label colour [fg] reads (4.5:1) on the dimmed photo,
     * and never above [MAX_DIM]. [photoLuminance] is the photo's mean luminance (0..1).
     */
    fun effectiveDim(requested: Int, photoLuminance: Double, fg: Int): Int {
        val fgL = Brand.luminance(fg)
        var d = requested.coerceIn(0, MAX_DIM)
        while (d < MAX_DIM && contrastAt(photoLuminance, d, fgL) < 4.5) d += 5
        return d.coerceAtMost(MAX_DIM)
    }

    private fun contrastAt(photoL: Double, dimPercent: Int, fgL: Double): Double {
        // Dimming mixes the photo with black; luminance is not linear in colour, but black over a mean is a fair estimate.
        val l = photoL * (1 - dimPercent / 100.0)
        return (maxOf(l, fgL) + 0.05) / (minOf(l, fgL) + 0.05)
    }

    const val MAX_DIM = 90
}
