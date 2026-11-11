package dev.githubstore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.githubstore.domain.StoreApp

// This known README asset has a central mark on its own opaque brand background.
internal fun isCenteredBannerArtwork(url: String): Boolean =
    url.substringBefore('?').endsWith("/banner-logo-solid.svg")

internal fun isAuthorAvatar(url: String) = url.startsWith("https://avatars.githubusercontent.com/") ||
    Regex("^https://github\\.com/[^/]+\\.png(?:[?].*)?$").matches(url)

@Composable fun AppMark(app: StoreApp, modifier: Modifier = Modifier, size: Int = 58,
                       fallbackShading: Boolean = true, shape: Shape? = RoundedCornerShape((size * .24f).dp),
                       symbolShadow: Shadow? = null) {
    val source = app.iconUrl
    var loaded by remember(source) { mutableStateOf(false) }
    val bannerArtwork = isCenteredBannerArtwork(app.iconUrl)
    val tileGradient = remember(app.color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(app.color.toInt(), hsv)
        val top = Color(android.graphics.Color.HSVToColor(floatArrayOf(
            hsv[0] - 9f, (hsv[1] * .94f).coerceAtMost(1f), (hsv[2] + .22f).coerceAtMost(1f))))
        val bottom = Color(android.graphics.Color.HSVToColor(floatArrayOf(
            hsv[0] + 5f, (hsv[1] * 1.08f).coerceAtMost(1f), (hsv[2] + .06f).coerceAtMost(1f))))
        Brush.linearGradient(listOf(top, bottom))
    }

    // Original artwork sits on a neutral surface. Brand gradients belong only to
    // the explicit letter fallback, never behind a successfully loaded logo.
    // A parent material can own the silhouette, avoiding two independently
    // antialiased masks around the artwork and its lighting overlay.
    Box(modifier.size(size.dp).testTag("app-icon-${app.id}").semantics { stateDescription = if (loaded) { if (isAuthorAvatar(source)) "作者头像，非项目图标" else "在线图标" } else "占位图标" }.then(if (shape != null) Modifier.clip(shape) else Modifier)
        .background(if (loaded) Color.White else Color(app.color.toInt())), contentAlignment = Alignment.Center) {
        if (!loaded && !fallbackShading) Box(Modifier.matchParentSize().background(tileGradient))
        if (!loaded && fallbackShading) Box(Modifier.matchParentSize().background(Brush.linearGradient(
            listOf(Color.White.copy(.18f), Color.Transparent, Color.Black.copy(.10f)))))
        if (!loaded) Text(app.symbol, color = Color.White, fontSize = (size * .46f).sp, fontWeight = FontWeight.Bold,
            style = TextStyle(shadow = symbolShadow))
        if (source.isNotEmpty()) AsyncImage(source, if (isAuthorAvatar(source)) "${app.name} 作者头像（非项目图标）" else "${app.name} 图标",
            Modifier.fillMaxSize().padding(if (bannerArtwork) 0.dp else (size * .12f).dp).alpha(if (loaded) 1f else 0f),
            // This README asset is a centered symbol in a 5:1 transparent banner.
            // Crop its empty sides; fit other logos without distorting/cutting them.
            contentScale = if (bannerArtwork) ContentScale.Crop else ContentScale.Fit,
            onLoading = { loaded = false }, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}
