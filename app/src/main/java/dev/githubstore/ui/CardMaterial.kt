package dev.githubstore.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.*

/** A small, shared lighting texture, baked once per card geometry, never per frame. */
private data class MaterialKey(val width: Int, val height: Int, val radius: Float, val neutral: Boolean)
private val materialCache = LruCache<MaterialKey, ImageBitmap>(8)

internal fun Modifier.beveledCardMaterial(radius: Dp, neutralSurface: Boolean): Modifier = drawWithCache {
    // Lighting varies slowly: a small texture is enough even on 4K TVs. Artwork
    // and the final silhouette stay at native resolution, not this lower scale.
    val textureScale = min(1f, 384f / size.width.coerceAtLeast(1f))
    val key = MaterialKey((size.width * textureScale).roundToInt().coerceAtLeast(1),
        (size.height * textureScale).roundToInt().coerceAtLeast(1),
        (radius.toPx() * textureScale * 4f).roundToInt() / 4f, neutralSurface)
    val material = materialCache.get(key) ?: bakeMaterial(key).also { materialCache.put(key, it) }
    val destination = IntSize(ceil(size.width).toInt().coerceAtLeast(1), ceil(size.height).toInt().coerceAtLeast(1))
    onDrawBehind { drawImage(material, dstSize = destination, filterQuality = FilterQuality.Medium) }
}

/**
 * Rounded-rectangle distance defines a fine directional optical edge.
 * A broad softbox reflection lights the face without an inset bevel band.
 * The texture has no silhouette mask: artwork + material are clipped once by
 * their shared parent, retaining a clean antialiased edge while scaling.
 */
private fun bakeMaterial(key: MaterialKey): ImageBitmap {
    val w = key.width; val h = key.height
    val halfW = w / 2f; val halfH = h / 2f
    val radius = key.radius.coerceAtMost(min(halfW, halfH))
    val pixels = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val px = x + .5f - halfW; val py = y + .5f - halfH
        val qx = abs(px) - (halfW - radius); val qy = abs(py) - (halfH - radius)
        val ox = max(qx, 0f); val oy = max(qy, 0f)
        val outside = sqrt(ox * ox + oy * oy)
        val distance = -(outside + min(max(qx, qy), 0f) - radius)
        val gx: Float; val gy: Float
        if (outside > .0001f) {
            gx = ox / outside * sign(px); gy = oy / outside * sign(py)
        } else if (qx > qy) { gx = sign(px); gy = 0f }
        else { gx = 0f; gy = sign(py) }
        // A thin optical edge, not a thick inflated bevel. The broad face
        // receives a single softbox above-left; its centre remains clear.
        val edgeWidth = max(radius * .032f, .65f)
        val rim = exp(-((distance - edgeWidth * .8f) / edgeWidth).pow(2))
        val lightSide = max(-gx * .48f - gy * .88f, 0f)
        // One shared light direction: even the top rim fades toward the right,
        // rather than tracing a constant-brightness outline around the card.
        val u = (x + .5f) / w
        val v = (y + .5f) / h
        val alongLight = (.52f * u + .48f * v).coerceIn(0f, 1f)
        val illumination = (1f - alongLight).pow(2.2f)
        val softbox = exp(-((u + .12f) / .85f).pow(2) - ((v + .18f) / .90f).pow(2))
        val specular = (.32f * softbox + .56f * rim * lightSide * illumination)
            .coerceAtMost(.64f)
        // The back-facing surface gently darkens diagonally; no lower highlight
        // or dark bevel band competes with the upper-left light source.
        val diffuse = 1f - alongLight.pow(2) * if (key.neutral) .065f else .10f
        // Combine multiplicative shade and reflected white into a single RGBA
        // overlay, preserving the real logo/brand hue underneath.
        val alpha = (1f - diffuse * (1f - specular)).coerceIn(0f, 1f)
        val gray = if (alpha > .0001f) (specular / alpha * 255f).roundToInt().coerceIn(0, 255) else 0
        pixels[y * w + x] = ((alpha * 255).roundToInt() shl 24) or (gray shl 16) or (gray shl 8) or gray
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
