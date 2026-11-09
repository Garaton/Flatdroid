package com.garathon.flatdroid.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decoded icons by path, so a row scrolling back into view isn't decoded again. Few are kept: the
 * rest come from the icon cache on disk ([IconCache]). An app update deploys to a new directory,
 * so its new icon has a new path.
 */
private val memory = LruCache<String, ImageBitmap>(16)

/** The app's icon; nothing until it is ready, and nothing when Android can't draw it. */
@Composable
fun AppIcon(file: File?, modifier: Modifier = Modifier) {
    val cacheDir = LocalContext.current.applicationContext.cacheDir
    val bitmap by produceState<ImageBitmap?>(file?.let { memory.get(it.path) }, file) {
        // A row shown for another app (the list has no keys) starts from that app's icon, not the last one's.
        value = file?.let { memory.get(it.path) }
        if (value != null || file == null) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                load(file, cacheDir)?.asImageBitmap()?.also { memory.put(file.path, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
    val image = bitmap
    // An icon that wasn't ready fades in; one already in memory is there at once.
    val alpha = remember(file) { Animatable(if (image != null) 1f else 0f) }
    LaunchedEffect(image) { if (image != null) alpha.animateTo(1f, tween(600)) }
    if (image != null) Image(image, contentDescription = null, modifier = modifier.alpha(alpha.value).clip(RoundedCornerShape(8.dp))) else Box(modifier)
}

/** From the icon cache when it is there, else decoded and, for an SVG or a big picture, put there. */
private fun load(file: File, cacheDir: File): Bitmap? {
    val cache = IconCache.shouldCache(file.path, file.length())
    val cached = File(IconCache.dir(cacheDir), IconCache.key(file.path, file.lastModified(), file.length()) + ".png")
    if (cache && cached.isFile) BitmapFactory.decodeFile(cached.path)?.let { return it }
    val bitmap = decodeIcon(file) ?: return null
    if (cache) writeCached(cached, bitmap)
    return bitmap
}

/** Written to a temporary name and renamed, so a half-written file is never read; failing is fine. */
private fun writeCached(file: File, bitmap: Bitmap) {
    try {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!temporary.renameTo(file)) temporary.delete()
    } catch (_: Exception) {
    }
}

/**
 * Decodes an app's icon at most about [ICON_DECODE_PX] pixels wide: the file comes from the app,
 * and a huge one decoded at full size could exhaust memory. SVGs are rendered at that size.
 */
private fun decodeIcon(file: File): Bitmap? {
    if (file.name.endsWith(".svg")) return renderSvg(file)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_DECODE_PX) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * Renders an SVG icon (AndroidSVG). Nothing external is loaded: AndroidSVG fetches images or
 * fonts only through a resolver, and none is registered. Oversized files are skipped.
 */
private fun renderSvg(file: File): Bitmap? {
    if (file.length() > MAX_SVG_BYTES) return null
    val svg = file.inputStream().use { SVG.getFromInputStream(it) }
    // Scaled by its viewBox to fill the bitmap (centered if not square). A fixed width and height
    // (`width="128px"`) would otherwise draw it at that size in the top-left corner; an icon with
    // no viewBox gets one from that size first.
    if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
        svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
    }
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    val bitmap = createBitmap(ICON_DECODE_PX, ICON_DECODE_PX)
    svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, ICON_DECODE_PX.toFloat(), ICON_DECODE_PX.toFloat()))
    return bitmap
}

private const val ICON_DECODE_PX = 192
private const val MAX_SVG_BYTES = 1L shl 20
