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

/** At most 1/16 of the app's heap, and 16 MB. */
private val MEMORY_BYTES = minOf(Runtime.getRuntime().maxMemory() / 16, 16L * 1024 * 1024).toInt()

/**
 * Decoded icons by path, so a row scrolling back into view isn't decoded again. The rest come from
 * the icon cache on disk ([IconCache]). An app update deploys to a new directory, so its new icon
 * has a new path.
 */
private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
    // By memory, not count: a few hundred 128 px icons, so the Home list and a stretch of the browser's stay decoded.
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Icons shown before in this process: one that comes back (scrolled out of a list and in again) doesn't fade in again. */
private val shownBefore = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

/** Drops the decoded icons of the files under [dir] (the browser's, once it is left); Home's stay. */
fun forgetIcons(dir: File) {
    val prefix = dir.path.trimEnd('/') + "/"
    memory.snapshot().keys.filter { it.startsWith(prefix) }.forEach { memory.remove(it) }
    shownBefore.removeAll { it.startsWith(prefix) }
}

/**
 * The app's icon; nothing until it is ready, and nothing when Android can't draw it. [decodePx]:
 * decoded no larger than that (the shown size, in pixels) rather than up to [ICON_DECODE_PX], so a
 * long list keeps more icons in memory.
 */
@Composable
fun AppIcon(file: File?, modifier: Modifier = Modifier, decodePx: Int? = null) {
    val cacheDir = LocalContext.current.applicationContext.cacheDir
    val key = file?.let { if (decodePx != null) "${it.path}@$decodePx" else it.path }
    val bitmap by produceState<ImageBitmap?>(key?.let { memory.get(it) }, key) {
        // A row shown for another app (the list has no keys) starts from that app's icon, not the last one's.
        value = key?.let { memory.get(it) }
        if (value != null || file == null || key == null) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                load(file, cacheDir, decodePx ?: ICON_DECODE_PX)?.asImageBitmap()?.also { memory.put(key, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
    val image = bitmap
    // An icon that wasn't ready fades in the first time it is shown; one in memory, or shown before, is there at once.
    val alpha = remember(file) { Animatable(if (image != null || file?.path in shownBefore) 1f else 0f) }
    LaunchedEffect(image) {
        if (image != null) {
            file?.let { shownBefore += it.path }
            alpha.animateTo(1f, tween(600))
        }
    }
    if (image != null) Image(image, contentDescription = null, modifier = modifier.alpha(alpha.value).clip(RoundedCornerShape(8.dp))) else Box(modifier)
}

/**
 * From the icon cache when it is there, else decoded and, for an SVG or a big picture, put there
 * (at [ICON_DECODE_PX]); in either case no larger than [px].
 */
private fun load(file: File, cacheDir: File, px: Int): Bitmap? {
    val cache = IconCache.shouldCache(file.path, file.length())
    val cached = File(IconCache.dir(cacheDir), IconCache.key(file.path, file.lastModified(), file.length()) + ".png")
    if (cache && cached.isFile) decodePng(cached, px)?.let { return it }
    if (!cache) return decodeIcon(file, px)
    val bitmap = decodeIcon(file, ICON_DECODE_PX) ?: return null
    writeCached(cached, bitmap)
    return if (px >= ICON_DECODE_PX) bitmap else decodePng(cached, px) ?: bitmap
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
 * Decodes an app's icon at most [px] pixels wide: the file comes from the app, and a huge one
 * decoded at full size could exhaust memory. SVGs are rendered at that size.
 */
private fun decodeIcon(file: File, px: Int): Bitmap? = if (file.name.endsWith(".svg")) renderSvg(file, px) else decodePng(file, px)

/**
 * A picture no larger than [px]: halved while still at least that big (cheap, while decoding), then
 * scaled the rest of the way in the same decode.
 */
private fun decodePng(file: File, px: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
    val sampled = maxOf(bounds.outWidth, bounds.outHeight) / sample
    return BitmapFactory.decodeFile(
        file.path,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            if (sampled > px) {
                inScaled = true
                inDensity = sampled
                inTargetDensity = px
            }
        },
    )
}

/**
 * Renders an SVG icon (AndroidSVG). Nothing external is loaded: AndroidSVG fetches images or
 * fonts only through a resolver, and none is registered. Oversized files are skipped.
 */
private fun renderSvg(file: File, px: Int): Bitmap? {
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
    val bitmap = createBitmap(px, px)
    svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, px.toFloat(), px.toFloat()))
    return bitmap
}

private const val ICON_DECODE_PX = 192
private const val MAX_SVG_BYTES = 1L shl 20
