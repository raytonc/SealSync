package com.junkfood.seal.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.util.LruCache
import com.junkfood.seal.util.cacheEmbeddedThumbnail
import com.junkfood.seal.util.extractEmbeddedThumbnail
import com.junkfood.seal.util.getCachedThumbnailPath
import kotlin.math.max

/**
 * Album art for the widget, which needs real bitmaps rather than an image loader.
 *
 * Reads the artwork embedded in the audio file through the same disk cache the in-app
 * list uses, so each file is opened at most once. Bitmaps come back square, centre-cropped
 * (YouTube thumbnails are 16:9) and with rounded corners, because RemoteViews cannot clip.
 *
 * Blocking: call from a background thread.
 */
object Artwork {

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Remembers files with no artwork, so they are not reopened on every redraw. */
    private val missing = mutableSetOf<String>()

    fun load(context: Context, uri: String, sizePx: Int, cornerPx: Float): Bitmap? {
        val key = "$uri@$sizePx"
        cache.get(key)?.let { return it }
        synchronized(missing) { if (uri in missing) return null }

        val parsed = Uri.parse(uri)
        val path = getCachedThumbnailPath(context, parsed) ?: run {
            val bytes = extractEmbeddedThumbnail(context, parsed)
            if (bytes == null) {
                synchronized(missing) { missing += uri }
                return null
            }
            cacheEmbeddedThumbnail(context, parsed, bytes)
        } ?: return null

        val bitmap = decodeSquare(path, sizePx, cornerPx) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decodeSquare(path: String, sizePx: Int, cornerPx: Float): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decode at no more than twice the target, then scale the rest of the way.
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx * 2) sample *= 2
        val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: return null

        val scale = sizePx / minOf(source.width, source.height).toFloat()
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (sizePx - source.width * scale) / 2f,
                (sizePx - source.height * scale) / 2f,
            )
        }
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(matrix) }
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
            max(cornerPx, 0f), max(cornerPx, 0f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader },
        )
        if (source != out) source.recycle()
        return out
    }
}
